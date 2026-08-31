#!/system/bin/sh
# 立即把 vcutoff 三值写回原厂值（3030/3030/2930）
# 手法：用模块自身以原值加载一次，写入后立即卸载
TR=/sys/kernel/tracing
SYM=mca_battery_shutdown_check_shutdown_delay
KO=/data/local/tmp/vcut_lower.ko

dump() {
  echo '-:vr' >> $TR/kprobe_events 2>/dev/null
  echo "p:vr $SYM fw=+0xa90(%x0):s32 dly=+0xa94(%x0):s32 sw=+0xa98(%x0):s32 vpen=+0xaa1(%x0):u8" >> $TR/kprobe_events 2>&1
  echo 1 > $TR/events/kprobes/vr/enable 2>/dev/null
  echo > $TR/trace; echo 1 > $TR/tracing_on
  sleep 2
  echo 0 > $TR/tracing_on
  grep vr $TR/trace | tail -1 | sed 's/.*vr: //'
  echo 0 > $TR/events/kprobes/vr/enable 2>/dev/null
  echo '-:vr' >> $TR/kprobe_events 2>/dev/null
}

OLD_ON=$(cat $TR/tracing_on)
echo "=== [1] 当前（应为被我们留下的 2700/2700/2600）==="
dump

echo
echo "=== [2] 以原厂值加载模块，写回 3030/3030/2930 ==="
insmod $KO enable=1 vcut_fw=3030 vcut_delay=3030 vcut_sw=2930 kill_vpack_gate=0
echo "  insmod rc=$?"
sleep 3
dump

echo
echo "=== [3] 卸载 ==="
rmmod vcut_lower
echo "  rmmod rc=$?"
sleep 2
dmesg | grep -i vcut_lower | tail -2

echo
echo "=== [4] 最终状态（应为 3030/3030/2930，vpen=1）==="
dump
echo "$OLD_ON" > $TR/tracing_on
echo "  已加载模块? $(lsmod | grep -c '^vcut_lower')"
echo "  tainted=$(cat /proc/sys/kernel/tainted)"
