#!/system/bin/sh
# KernelSU「执行」按钮：查看状态 / 快速切换
MP=/sys/module/vcut_lower/parameters
SFG=/sys/devices/platform/soc/soc:strategy-fg
BAT=/sys/class/power_supply/battery
FGM=/sys/devices/virtual/xm_power/fg_master
DATA=/data/adb/vcut_lower
MODE=full
[ -f "$DATA/mode.sh" ] && . "$DATA/mode.sh"
[ -f "$DATA/config.sh" ] && eval "$(grep '^MODE=' "$DATA/config.sh")"

# 观测模式（未验证机型）：「执行」按钮 = 立即生成一份诊断包
if [ "$MODE" != "full" ]; then
  echo "=========== VCut Lower 观测模式 ==========="
  echo "机型 $(getprop ro.product.device) 未经验证，模块只读不写。"
  echo "正在生成诊断包，请保持亮屏 1~2 分钟..."
  sh "${0%/*}/collect.sh"
  exit 0
fi

echo "=========== VCut Lower 状态 ==========="
if lsmod | grep -q '^vcut_lower'; then
  echo "模块: 已加载"
  printf "  armed=%s enable=%s soc_ctl=%s\n" \
      "$(cat $MP/armed)" "$(cat $MP/enable)" "$(cat $MP/soc_ctl)"
  printf "  关机电压 fw/delay/sw = %s/%s/%s mV\n" \
      "$(cat $MP/vcut_fw)" "$(cat $MP/vcut_delay)" "$(cat $MP/vcut_sw)"
  printf "  伪装循环 fake_cycle=%s (当前 [0xa04]=%s)\n" \
      "$(cat $MP/fake_cycle)" "$(cat $MP/t_cycle)"
  printf "  标定参数 FCC=%smAh QBOT=%smAh Rint=%smΩ\n" \
      "$(cat $MP/fcc_mah)" "$(cat $MP/qbot_mah)" "$(cat $MP/rint_mohm)"
  echo "  --- 实时遥测 ---"
  printf "  均值电压=%smV 电流=%sµA(正=放电) 温度=%s(0.1℃)\n" \
      "$(cat $MP/t_mean_volt)" "$(cat $MP/t_current)" "$(cat $MP/t_temp)"
  printf "  raw_soc=%s(厘%%) 显示=%s%% 判空=%s\n" \
      "$(cat $MP/t_raw_soc)" "$(cat $MP/t_disp)" "$(cat $MP/t_vbatt_empty)"
  printf "  kprobe 命中=%s  fail-safe=%s (0=正常 1=心跳超时 2=低压兜底)\n" \
      "$(cat $MP/t_hits)" "$(cat $MP/t_failsafe)"
else
  echo "模块: ★未加载"
  echo "  最近日志:"
  tail -6 "$DATA/boot.log" 2>/dev/null | sed 's/^/    /'
fi

echo
echo "--- 系统侧 ---"
printf "  capacity=%s%%  fake_soc=%s  raw_soc=%s\n" \
    "$(cat $BAT/capacity)" "$(cat $SFG/fake_soc)" "$(cat $SFG/raw_soc)"
printf "  电压=%smV  状态=%s  温度=%s\n" \
    "$(( $(cat $BAT/voltage_now) / 1000 ))" "$(cat $BAT/status)" "$(cat $BAT/temp)"
printf "  cycle_count=%s  FCC=%smAh  charge_full=%smAh\n" \
    "$(cat $BAT/cycle_count)" "$(( $(cat $FGM/charger_full) / 1000 ))" \
    "$(( $(cat $BAT/charge_full) / 1000 ))"
echo "  开机失败计数=$(cat $DATA/bootfail 2>/dev/null)"

echo
echo "音量上：临时卸载模块（还原原厂，重启后按配置恢复）"
echo "音量下：把关机电压临时改回保守档 2900"
key="$(timeout 5 getevent -qlc 1 2>/dev/null | awk '/KEY_VOLUME(UP|DOWN).*UP/ {print $3; exit}')"
case "$key" in
  KEY_VOLUMEUP)
    rmmod vcut_lower && echo "已卸载并还原" || echo "卸载失败"
    ;;
  KEY_VOLUMEDOWN)
    echo 2900 > $MP/vcut_fw; echo 2900 > $MP/vcut_delay; echo 2800 > $MP/vcut_sw
    echo "已临时改为 2900/2900/2800（重启后回到 config.sh 的值）"
    ;;
  *) echo "未选择操作" ;;
esac
