#!/system/bin/sh
#
# 诊断采集（2026-10-21）—— 给【非 popsicle 机型】适配用。全程只读，不写内核内存。
#
# 产物：/sdcard/Download/vcut_diag_<机型>_<时间>.tar.gz，把它发回即可。包含：
#   diag.txt   机型/内核/符号/sysfs 真值/结构体宽范围 dump/模块状态/日志
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
           ro.soc.model ro.board.platform ro.boot.hardware; do
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
