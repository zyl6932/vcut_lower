#!/system/bin/sh
#
# 诊断采集（2026-10-21）—— 给【非 popsicle 机型】适配用。全程只读，不写内核内存。
#
# 产物：/sdcard/Download/vcut_diag_<机型>_<时间>.tar.gz，把它发回即可。包含：
#   diag.txt   机型/内核/符号/sysfs 真值/结构体宽范围 dump/模块状态/日志
#   charge.txt 充电增强：控制节点、投票项、设备树充电表、温度节点、守护进程日志
#   turbo_session.log / charge_hist.log  本次充电记录 / 历史充电采样（有才带）
#   *.ko       目标函数所在的厂商模块（回电脑反汇编找锚点、核对符号 CRC）
#   config.gz  内核配置（如需为该内核重编模块）
#
# 用法：
#   ① 已装本模块：KernelSU/Magisk 里点模块的「执行」按钮（观测模式下即运行本脚本）
#   ② 没装/装不上：adb push collect.sh /data/local/tmp/ && adb shell su -c sh /data/local/tmp/collect.sh
#
# ★隐私：电池序列号、MAC、生产/首用日期等节点一律不采。
#
TARGET=mca_battery_shutdown_check_shutdown_delay
TR=/sys/kernel/tracing
BAT=/sys/class/power_supply/battery
FGM=/sys/devices/virtual/xm_power/fg_master
SFG=/sys/devices/platform/soc/soc:strategy-fg
MP=/sys/module/vcut_lower/parameters
DATA=/data/adb/vcut_lower
HERE=${0%/*}
EV=vcut_diag
DUMP_START=$((0x800))
DUMP_END=$((0xe00))
PER_EV=96
MAXWAIT=90

DEV=$(getprop ro.product.device)
STAMP=$(date +%m%d_%H%M%S)
OUT=/sdcard/Download/vcut_diag_${DEV}_$STAMP
mkdir -p "$OUT" || { echo "无法创建 $OUT"; exit 1; }
D="$OUT/diag.txt"

sec() { echo; echo "===== $* ====="; }
# 打印目录下所有单值节点，跳过隐私项与超长值
dumpdir() {
  [ -d "$1" ] || { echo "(不存在 $1)"; return; }
  for f in "$1"/*; do
    [ -f "$f" ] || continue
    n=${f##*/}
    case "$n" in *sn*|*SN*|*mac*|*date*|*serial*|uevent|*_addr) continue ;; esac
    v=$(head -c 120 "$f" 2>/dev/null | tr '\n' ' ')
    [ -n "$v" ] && echo "$n=$v"
  done
}

echo "采集中（约 1~2 分钟，需等目标函数被调用）..."
{
  sec "设备"
  for p in ro.product.device ro.product.model ro.product.marketname ro.product.manufacturer \
           ro.build.version.incremental ro.build.version.release ro.build.display.id \
           ro.soc.model ro.board.platform ro.boot.hardware \
           ro.boot.verifiedbootstate ro.boot.vbmeta.device_state ro.boot.slot_suffix ro.boot.vbmeta.digest; do
    echo "$p=$(getprop $p)"
  done
  echo "uname=$(uname -a)"
  cat /proc/version
  echo "ksud=$(/data/adb/ksud -V 2>/dev/null) magisk=$(magisk -c 2>/dev/null)"
  echo "kptr_restrict=$(cat /proc/sys/kernel/kptr_restrict) selinux=$(getenforce 2>/dev/null)"

  sec "目标符号（kallsyms）"
  grep -E "mca_battery_shutdown_|strategy_class_fg_ops_get_cyclecount|strategy_fg_set_fake_soc" /proc/kallsyms | head -40

  sec "相关模块"
  lsmod | grep -iE "mca|strategy|vcut|bq27|fg" | head -40

  sec "battery"; dumpdir $BAT
  sec "fg_master"; dumpdir $FGM
  sec "strategy-fg"; dumpdir "$SFG"

  sec "vcut_lower 模块状态"
  if [ -d "$MP" ]; then
    for f in "$MP"/*; do echo "${f##*/}=$(cat "$f" 2>/dev/null)"; done
  else
    echo "(未加载)"
  fi
  dmesg 2>/dev/null | grep vcut_lower | tail -40

  sec "boot.log"
  tail -60 "$DATA/boot.log" 2>/dev/null
  sec "config.sh"
  grep -v '^#' "$DATA/config.sh" 2>/dev/null | grep -v '^$'
} > "$D" 2>&1

# ---------- 充电增强（v1.9）：控制节点 / 投票 / 设备树充电表 / 温度节点 / 守护进程记录 ----------
# 单独写 charge.txt。别的机型能不能开亮屏快充、提升电流，原厂阶梯是什么，都靠这一份判断（tools/analyze_diag.py 解析）。
C="$OUT/charge.txt"
VT=/sys/kernel/debug/business-votable
DT=/proc/device-tree/soc
# 设备树属性：字符串表（以 \0 结尾、去掉 \0 后全是可打印字符）写 s:，其余写 x:十六进制（大端 u32）
# 可打印字符用 \040-\176 范围：busybox 的 tr 不认 [:print:]（2026-10-21 真机上全被判成 x:）；
# 系统 toybox 的 tr 连范围也不对，所以 analyze_diag.py 对 x: 还会按字符串兜底
dtprop() {
  if [ "$(tail -c 1 "$1" | od -An -tx1 | tr -d ' ')" = "00" ] && \
     [ "$(tr -d '\0' < "$1" | tr -d '\040-\176' | wc -c)" -eq 0 ] && [ -n "$(tr -d '\0' < "$1")" ]; then
    echo "${1##*/}=s:$(head -c 4096 "$1" | tr '\0' ' ')"
  else
    echo "${1##*/}=x:$(head -c 1024 "$1" | od -An -v -tx1 | tr -s ' \n' ' ')"
  fi
}
dtnode() {
  [ -d "$1" ] || { echo "(不存在 $1)"; return; }
  for f in "$1"/*; do
    [ -f "$f" ] || continue
    case "${f##*/}" in name|phandle) continue ;; esac
    dtprop "$f"
  done
}
{
  mount -t debugfs none /sys/kernel/debug 2>/dev/null
  sec "控制节点"
  for f in /sys/class/xm_power/charger/charger_thermal/wired_thermal_remove $VT/curr_max/force_val $VT/curr_max/force_active; do
    if [ -e "$f" ]; then echo "$f=$(cat "$f" 2>/dev/null)"; else echo "$f=(不存在)"; fi
  done

  sec "投票项（business-votable）"
  if [ -d "$VT" ]; then
    for d in "$VT"/*/; do
      n=${d%/}; n=${n##*/}
      echo "--- $n"
      head -c 4096 "$d/status" 2>/dev/null
      echo
      # 有没有 force_val/force_active（能不能像 curr_max 那样强制）
      echo "[节点] $(ls "$d" 2>/dev/null | tr '\n' ' ')"
    done
  else
    echo "(不存在 $VT)"
  fi

  sec "设备树：充电相关节点"
  ls -d "$DT"/*charg* "$DT"/*jeita* "$DT"/*batt* 2>/dev/null | sed "s#^$DT/##"
  for n in mca_strategy_quick_charge mca_strategy_buckchg_class mca_quick_charge_batt_para_gbl smart_charge \
           mca_buckchg_jeita mca_buckchg_jeita_gbl_para mca_buckchg_jeita_second_para mca_buckchg_jeita_tmp_para; do
    sec "dt/$n"; dtnode "$DT/$n"
  done

  sec "温度节点（thermal_zone 编号 类型 温度）"
  for z in /sys/class/thermal/thermal_zone*; do
    read -r ty < "$z/type" 2>/dev/null || continue
    read -r tp < "$z/temp" 2>/dev/null || tp=?
    echo "${z##*/} $ty $tp"
  done

  sec "供电"
  ls /sys/class/power_supply/
  sec "usb"; dumpdir /sys/class/power_supply/usb
  sec "xm_power/charger"; ls /sys/class/xm_power/charger/ 2>/dev/null
  # 电荷泵 / 快充 / 热控档位：投票项里看不出谁在限流时（小米 15 充电 6.9A），靠这几组判断
  for s in chargerpump quick_charge charger_thermal charger_common charge_interface power smart_charge; do
    [ -d "/sys/class/xm_power/charger/$s" ] || continue
    sec "xm_power/charger/$s"; dumpdir "/sys/class/xm_power/charger/$s"
  done

  sec "充电增强守护进程"
  echo "alive=$(pgrep -f vcut_turbo.sh | head -1)"
  cat /dev/vcut_turbo.state 2>/dev/null
  sec "turbo.log（最后 200 行）"
  tail -200 "$DATA/turbo.log" 2>/dev/null
} > "$C" 2>&1
# ---------- 充电实验（2026-10-21）：没有 curr_max 的机型（小米 15 系），找出真正控制充电电流的投票项 ----------
# 做法：插着快充时，把每个候选投票项依次【强制成低值】20 秒，看电池电流跟不跟着掉。
#   哪个一压电流就掉，哪个就在充电通路上 → 守护进程的「提升电流」改用它。
#   只往低压：充电只会变慢，绝不会超出原厂允许的范围。实验前记下每个节点原来的 force 状态，
#   结束（含中途被打断）原样恢复。电芯 >45℃ 或充电电路 >75℃ 立即中止。
#   有 curr_max 的机型（17 系列）不做：守护进程 B 已经在用它，避免抢节点。
#   产物 charge_probe.txt，tools/analyze_diag.py [5] 解析。
P="$OUT/charge_probe.txt"
PB=/sys/class/power_supply/battery
PQ=/sys/class/xm_power/charger/quick_charge
PCP=/sys/class/xm_power/charger/chargerpump
PROBE_LOW=1500
PROBE_SET="multi_chg_cur single_chg_cur div4_multi div4_single div2_multi div2_single div1_multi div1_single buck_charge_curr"
probe_zone() {
  for _d in /sys/class/thermal/thermal_zone*/; do
    read -r _t < "$_d/type" 2>/dev/null || continue
    [ "$_t" = "$1" ] && { echo "${_d}temp"; return; }
  done
}
probe_restore() {
  [ -n "$PROBE_SAVED" ] || return 0
  for _e in $PROBE_SAVED; do        # 名字:force_active:force_val
    _n=${_e%%:*}; _r=${_e#*:}; _a=${_r%%:*}; _v=${_r#*:}
    echo "$_v" > "$VT/$_n/force_val" 2>/dev/null
    echo "$_a" > "$VT/$_n/force_active" 2>/dev/null
  done
  PROBE_SAVED=
}
# 一行采样：阶段 名字 秒 ibat(µA) vbat(µV) 电芯温度(0.1℃) 充电电路(m℃) ibus_total 充电阶段 vfc_iout
probe_sample() {
  echo "S $1 $2 $(cat $PB/current_now 2>/dev/null) $(cat $PB/voltage_now 2>/dev/null) $(cat $PB/temp 2>/dev/null)" \
       "$(cat "$PCT" 2>/dev/null) $(cat $PCP/ibus_total 2>/dev/null) $(cat $PQ/charging_stage 2>/dev/null) $(cat $PQ/vfc_iout 2>/dev/null)"
}
# 采 N 秒（每 2 秒一行）；过热返回 1
probe_phase() {
  _t=0
  while [ $_t -lt "$3" ]; do
    probe_sample "$1" "$2" "$_t"
    _bt=$(cat $PB/temp 2>/dev/null); _ct=$(cat "$PCT" 2>/dev/null)
    [ "${_bt:-0}" -gt 450 ] 2>/dev/null && { echo "ABORT 电芯 ${_bt}"; return 1; }
    [ "${_ct:-0}" -gt 75000 ] 2>/dev/null && { echo "ABORT 充电电路 ${_ct}"; return 1; }
    sleep 2; _t=$((_t + 2))
  done
  return 0
}
probe_run() {
  echo "===== 充电实验（强制成 ${PROBE_LOW} 各 20 秒） ====="
  echo "wired_thermal_remove=$(cat /sys/class/xm_power/charger/charger_thermal/wired_thermal_remove 2>/dev/null)"
  echo "cp_path_enable=$(cat $PQ/cp_path_enable 2>/dev/null)"
  echo "real_type=$(cat /sys/class/xm_power/charger/charger_common/real_type 2>/dev/null) soc=$(cat $PB/capacity 2>/dev/null)"
  echo "# S 阶段 投票项 秒 ibat(µA) vbat(µV) btemp(0.1℃) ctherm(m℃) ibus_total charging_stage vfc_iout"
  PROBE_SAVED=
  for n in $PROBE_SET; do
    [ -w "$VT/$n/force_val" ] && [ -w "$VT/$n/force_active" ] || continue
    PROBE_SAVED="$PROBE_SAVED $n:$(cat "$VT/$n/force_active" 2>/dev/null):$(cat "$VT/$n/force_val" 2>/dev/null)"
  done
  echo "原 force 状态:$PROBE_SAVED"
  probe_phase base - 20 || return 0
  for n in $PROBE_SET; do
    [ -w "$VT/$n/force_val" ] && [ -w "$VT/$n/force_active" ] || { echo "SKIP $n（没有 force 节点）"; continue; }
    echo "V $n $(grep effective "$VT/$n/status" 2>/dev/null)"
    echo "$PROBE_LOW" > "$VT/$n/force_val"; echo 1 > "$VT/$n/force_active"
    probe_phase low "$n" 20; ok=$?
    echo 0 > "$VT/$n/force_active"
    [ $ok = 0 ] || return 0
    probe_phase back "$n" 16 || return 0
  done
  echo "END"
}
if [ -d "$VT" ] && [ ! -d "$VT/curr_max" ] && [ "$(cat /sys/class/power_supply/usb/online 2>/dev/null)" = "1" ] && \
   [ "$(cat $PB/status 2>/dev/null)" = "Charging" ]; then
  PCT=$(probe_zone charger_therm); [ -n "$PCT" ] || PCT=$(probe_zone charger_therm0)
  echo "检测到正在充电：追加约 6 分钟充电实验（会短暂降低充电速度），请保持充电器连接..."
  trap 'probe_restore' EXIT INT TERM HUP
  probe_run > "$P" 2>&1
  probe_restore
  trap - EXIT INT TERM HUP
  echo "充电实验完成，已全部撤回。"
fi

# 本次充电记录与历史充电采样（阶梯图按电量画横轴用），原样带上
cp /dev/vcut_turbo.log "$OUT/turbo_session.log" 2>/dev/null
cp "$DATA/charge_hist.log" "$OUT/charge_hist.log" 2>/dev/null
# 解锁容量实测记录（跨越原厂电压闸时的 rm、之后的放电量）
cp "$DATA/unlock.log" "$DATA/unlock.cur" "$OUT/" 2>/dev/null

# ---------- 结构体宽范围只读 dump ----------
{
  sec "结构体 dump 0x$(printf %x $DUMP_START)..0x$(printf %x $DUMP_END)（x0 = strategy struct）"
  if [ ! -w "$TR/kprobe_events" ]; then
    echo "(tracefs 不可写，跳过)"
  else
    NEV=$(( (DUMP_END - DUMP_START) / 4 / PER_EV ))
    OLD_ON=$(cat $TR/tracing_on)
    i=0
    while [ $i -lt $NEV ]; do
      echo "-:$EV$i" >> $TR/kprobe_events 2>/dev/null
      ARGS=""; off=$((DUMP_START + i * PER_EV * 4)); end=$((off + PER_EV * 4))
      while [ $off -lt $end ]; do
        ARGS="$ARGS o$(printf %x $off)=+0x$(printf %x $off)(%x0):s32"; off=$((off + 4))
      done
      echo "p:$EV$i $TARGET$ARGS" >> $TR/kprobe_events 2>/dev/null || echo "注册 $EV$i 失败"
      i=$((i + 1))
    done
    echo > $TR/trace
    i=0; while [ $i -lt $NEV ]; do echo 1 > $TR/events/kprobes/$EV$i/enable 2>/dev/null; i=$((i + 1)); done
    echo 1 > $TR/tracing_on
    t=0
    while [ $t -lt $MAXWAIT ]; do
      sleep 3; t=$((t + 3))
      grep -q " $EV$((NEV - 1)): " $TR/trace && break
    done
    echo "等待 ${t}s"
    # ★先全部读完再删事件：删除 kprobe 事件会清空 trace 缓冲区
    i=0
    while [ $i -lt $NEV ]; do
      echo 0 > $TR/events/kprobes/$EV$i/enable 2>/dev/null
      grep " $EV$i: " $TR/trace | tail -1 | tr ' ' '\n' | grep '^o'
      i=$((i + 1))
    done
    i=0
    while [ $i -lt $NEV ]; do echo "-:$EV$i" >> $TR/kprobe_events 2>/dev/null; i=$((i + 1)); done
    echo "$OLD_ON" > $TR/tracing_on
    sec "dump 后 sysfs 快照"
    for n in voltage_now current_now capacity temp cycle_count charge_full charge_full_design; do
      echo "bat/$n=$(cat $BAT/$n 2>/dev/null)"
    done
    for n in rm charger_full design_capacity cyclecount qmax_cyclecount rsoc temp vbatt cutoff_vol; do
      echo "fgm/$n=$(cat $FGM/$n 2>/dev/null)"
    done
  fi

  sec "autooff 试运行"
  if [ -f "$HERE/autooff.sh" ]; then
    sh "$HERE/autooff.sh" 2>&1
  else
    echo "(无 autooff.sh)"
  fi
} >> "$D" 2>&1

# ---------- 厂商模块文件 ----------
MOD=$(grep -w "$TARGET" /proc/kallsyms | head -1 | sed -n 's/.*\[\(.*\)\].*/\1/p')
echo "目标函数所在模块: ${MOD:-未找到}" >> "$D"
if [ -n "$MOD" ]; then
  for d in /vendor_dlkm/lib/modules /vendor/lib/modules /system_dlkm/lib/modules /odm/lib/modules /lib/modules; do
    [ -f "$d/$MOD.ko" ] && { cp "$d/$MOD.ko" "$OUT/"; echo "已复制 $d/$MOD.ko" >> "$D"; break; }
  done
fi
# 电量计驱动：伪装电量计循环（写 fg_master/cyclecount）是否"纯 RAM"要比对它的反汇编
for d in /vendor_dlkm/lib/modules /vendor/lib/modules; do
  [ -f "$d/bq27z561.ko" ] && { cp "$d/bq27z561.ko" "$OUT/"; echo "已复制 $d/bq27z561.ko" >> "$D"; break; }
done
# 本模块导入的符号的 CRC 来源：各挑一个导入了它的原厂模块（CRC 不同时据此重新 patch）
mkdir -p "$OUT/crc"
for sym in param_ops_int register_kprobe register_kretprobe unregister_kretprobe; do
  for d in /vendor_dlkm/lib/modules /vendor/lib/modules /system_dlkm/lib/modules; do
    f=$(grep -l "$sym" "$d"/*.ko 2>/dev/null | head -1)
    [ -n "$f" ] && { cp "$f" "$OUT/crc/"; echo "CRC 来源 $sym <- $f" >> "$D"; break; }
  done
done
[ -f /proc/config.gz ] && cp /proc/config.gz "$OUT/config.gz"

cd /sdcard/Download && tar czf "${OUT##*/}.tar.gz" "${OUT##*/}" && rm -rf "$OUT"
echo
echo "完成：/sdcard/Download/${OUT##*/}.tar.gz"
echo "把这个文件发回即可。"
