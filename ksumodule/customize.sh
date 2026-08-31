#!/system/bin/sh

ui_print "- VCut Lower v1.2-2620-os4"
ui_print "-   关机电压 3130->2620 / 摘除 3000mV vpack 硬闸"
ui_print "-   FCC 实时读 / 电量全程按同一 mAh 刻度均匀下降"

# ---------- 机型/内核校验：这个模块的偏移是针对特定 build 逆向出来的 ----------
DEV=$(getprop ro.product.device)
KREL=$(uname -r)

ui_print "- 设备: $DEV"
ui_print "- 内核: $KREL"

[ "$ARCH" = "arm64" ] || abort "! 仅支持 arm64"

if [ "$DEV" != "popsicle" ]; then
  ui_print "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  ui_print "! 本模块的 struct 偏移是针对 popsicle"
  ui_print "! (小米17 Pro Max) 逆向得到的，"
  ui_print "! 在其他机型上偏移几乎必然不同。"
  ui_print "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  abort "! 机型不匹配，已中止"
fi

case "$KREL" in
  6.12.69-android16-6-*)
    ui_print "- 内核版本匹配 (OS4.0 / 6.12.69-android16-6)" ;;
  6.12.23-android16-5-*)
    ui_print "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
    ui_print "! 这是 OS3.0 的旧内核。本版(v1.2)只适配"
    ui_print "! OS4.0 / 6.12.69-android16-6，偏移不同。"
    ui_print "! 请用 v1.1 版，或先升级系统。"
    ui_print "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
    abort "! 内核版本不匹配，已中止"
    ;;
  *)
    ui_print "! 内核不是 6.12.69-android16-6-*"
    ui_print "! 符号 CRC 与 struct 偏移很可能都对不上"
    ui_print "! 装上后 insmod 会失败，或指令指纹校验拒绝加载"
    ui_print "! （这是安全的失败：模块不会被加载，不会写内核内存）"
    ;;
esac

lsmod | grep -q '^mca_strategy_fg_comp' || abort "! 未找到 mca_strategy_fg_comp 模块"

# ---------- 配置文件放 /data，模块升级不覆盖 ----------
DATA=/data/adb/vcut_lower
mkdir -p "$DATA"
if [ -f "$DATA/config.sh" ]; then
  ui_print "- 保留已有配置 $DATA/config.sh"
  rm -f "$MODPATH/config.sh.default"
else
  mv "$MODPATH/config.sh.default" "$DATA/config.sh"
  ui_print "- 已写入默认配置 $DATA/config.sh"
fi
echo 0 > "$DATA/bootfail"

set_perm "$MODPATH/service.sh"   0 0 0755
set_perm "$MODPATH/action.sh"    0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/vcut_lower.ko" 0 0 0644
set_perm "$MODPATH/module.prop"  0 0 0644
set_perm_recursive "$DATA" 0 0 0700 0600

ui_print " "
ui_print "- 安装完成，重启后生效"
ui_print "- 配置: $DATA/config.sh"
ui_print "- 日志: $DATA/boot.log"
ui_print "- 状态: 在 KernelSU 里点模块的「执行」查看"
ui_print " "
ui_print "! 安全说明："
ui_print "!  连续 2 次开机不稳定会自动停用本模块"
ui_print "!  KernelSU 音量下键安全模式可禁用全部模块"
ui_print "!  rmmod vcut_lower 即完全还原"
ui_print " "
ui_print "! ★电池风险（务必知悉）："
ui_print "!  本模块只放宽【软件】关机电压，电量计固件与"
ui_print "!  PMIC 硬件过放保护完全不碰。但反复深放到"
ui_print "!  2.6~2.7V 会加速电池老化、有析锂风险。"
ui_print "!  低温环境不要深放。默认 2620 已较激进，"
ui_print "!  求稳可在 config.sh 里调高（如 2800/2800/2700）。"
ui_print "!  风险自负。"
