#!/system/bin/sh
# 步骤0：只读 dump —— 用 tracefs kprobe_events 验证 strategy_fg struct 偏移
# 不加载任何模块、不写任何内核内存。结束时自动移除 kprobe 并恢复 tracing_on。
# 用法: adb shell su -c "sh /data/local/tmp/step0_dump_offsets.sh [秒数]"
# 注：新建 ftrace instance 受 SELinux(debugfs_tracing_instances) 拒写，故用全局 tracefs。

TR=/sys/kernel/tracing
SYM=mca_battery_shutdown_check_shutdown_delay
DUR=${1:-25}
BAT=/sys/class/power_supply/battery
FGM=/sys/devices/virtual/xm_power/fg_master
SFG=/sys/devices/platform/soc/soc:strategy-fg

echo "=============== 步骤0 只读 dump ==============="
echo "kernel: $(uname -r)"
echo

echo "--- [1] 目标符号在 kallsyms（kptr_restrict=2 故地址显示为0，按名注册不受影响）---"
grep -w "$SYM" /proc/kallsyms
echo

echo "--- [2] sysfs 真值（对照基准，采样前）---"
for n in voltage_now current_now capacity temp status; do
  [ -f $BAT/$n ] && echo "  battery/$n = $(cat $BAT/$n 2>/dev/null)"
done
for n in cyclecount rsoc rm cutoff_vol; do
  [ -f $FGM/$n ] && echo "  fg_master/$n = $(cat $FGM/$n 2>/dev/null)"
done
for n in fake_soc fake_temp enable_rollback; do
  [ -f $SFG/$n ] && echo "  strategy-fg/$n = $(cat $SFG/$n 2>/dev/null)"
done
echo

echo "--- [3] 注册只读 kprobe（全局 tracefs）---"
OLD_ON=$(cat $TR/tracing_on)
echo "  原 tracing_on = $OLD_ON"
echo '-:fgdump' >> $TR/kprobe_events 2>/dev/null

ARGS="self=%x0:x64"
# ★2026-08-29 更新为 OS4.0.0.25 的偏移（在 OS4.0.0.20 基础上整体再 +0x10，无例外）
#   上一版（OS4.0.0.20）整体 +0x1A8，其 vpack 闸是例外 0xa91（不是位移得到的 0xa90）
#   本次位移后 gate 落在 0xaa1，而 0xaa0 正是那个 8 处引用的高频标志 —— 别写它
ARGS="$ARGS cur9f0=+0x9f0(%x0):s32"
ARGS="$ARGS mean9fc=+0x9fc(%x0):s32"
ARGS="$ARGS cyca04=+0xa04(%x0):s32"
ARGS="$ARGS tmpa08=+0xa08(%x0):s32"
ARGS="$ARGS soca38=+0xa38(%x0):s32"
ARGS="$ARGS fwa90=+0xa90(%x0):s32"
ARGS="$ARGS dlya94=+0xa94(%x0):s32"
ARGS="$ARGS swa98=+0xa98(%x0):s32"
ARGS="$ARGS emptya9c=+0xa9c(%x0):u8"
ARGS="$ARGS gateaa1=+0xaa1(%x0):u8"
ARGS="$ARGS fsocb04=+0xb04(%x0):s32"
ARGS="$ARGS vpackb90=+0xb90(%x0):s32"
ARGS="$ARGS arg1v=+0(%x1):s32"

echo "p:fgdump $SYM $ARGS" >> $TR/kprobe_events 2>&1
RC=$?
echo "  注册 rc=$RC"
if [ $RC -ne 0 ]; then
  echo "  !! 直接符号注册失败，试 MOD:SYM 形式"
  echo "p:fgdump mca_strategy_fg_comp:$SYM $ARGS" >> $TR/kprobe_events 2>&1
  RC=$?
  echo "  注册 rc=$RC"
fi
[ $RC -ne 0 ] && { echo "!! 注册失败，放弃"; dmesg | tail -5; exit 1; }
echo "  已注册: $(grep fgdump $TR/kprobe_events)"
echo

echo "--- [4] 采样 ${DUR}s ---"
echo 1 > $TR/events/kprobes/fgdump/enable
echo > $TR/trace
echo 1 > $TR/tracing_on
sleep $DUR
echo 0 > $TR/events/kprobes/fgdump/enable

echo "--- [5] TRACE 输出 ---"
grep fgdump $TR/trace | tail -20
echo "  命中次数 = $(grep -c fgdump $TR/trace)"
echo

echo "--- [6] sysfs 真值（采样后）---"
echo "  battery/voltage_now = $(cat $BAT/voltage_now 2>/dev/null)"
echo "  battery/capacity    = $(cat $BAT/capacity 2>/dev/null)"
echo "  battery/temp        = $(cat $BAT/temp 2>/dev/null)"
echo "  fg_master/rsoc      = $(cat $FGM/rsoc 2>/dev/null)"
echo "  fg_master/cyclecount= $(cat $FGM/cyclecount 2>/dev/null)"
echo

echo "--- [7] 清理 ---"
echo '-:fgdump' >> $TR/kprobe_events 2>/dev/null
echo "$OLD_ON" > $TR/tracing_on
echo "  剩余 fgdump: [$(grep fgdump $TR/kprobe_events)]  tracing_on 恢复为 $(cat $TR/tracing_on)"
echo "=============== 完成 ==============="
