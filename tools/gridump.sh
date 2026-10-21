#!/system/bin/sh
# 密集网格只读 dump：逐 4 字节读 strategy 结构体，match sysfs 真值定位偏移。
# 专治【非均匀位移】（§89 OS4.0.0.32）——单一 delta 自解析失效时用它逐字对账。
# 不加载模块、不写内核内存。用法: adb push 后 su -c "sh /data/local/tmp/gridump.sh 0x9e0 0xba4 40"
# 只读密集网格 dump：从 START 到 END 每 4 字节读一个 s32，match sysfs 真值定位偏移
# 不加载模块、不写内核内存。用法: sh gridump.sh START_HEX END_HEX DUR
TR=/sys/kernel/tracing
SYM=mca_battery_shutdown_check_shutdown_delay
START=${1:-0x9e0}
END=${2:-0xac0}
DUR=${3:-6}

echo "=== grid dump $START..$END ==="
OLD_ON=$(cat $TR/tracing_on)
echo '-:fgg' >> $TR/kprobe_events 2>/dev/null

ARGS=""
off=$((START))
end=$((END))
while [ $off -lt $end ]; do
  h=$(printf '%x' $off)
  ARGS="$ARGS w$h=+0x$h(%x0):s32"
  off=$((off + 4))
done

echo "p:fgg $SYM $ARGS" >> $TR/kprobe_events 2>&1
RC=$?
echo "register rc=$RC"
if [ $RC -ne 0 ]; then
  echo "!! register failed"; dmesg | tail -3; exit 1
fi
echo 1 > $TR/events/kprobes/fgg/enable
echo > $TR/trace
echo 1 > $TR/tracing_on
sleep $DUR
echo 0 > $TR/events/kprobes/fgg/enable
echo "--- last hit ---"
grep fgg $TR/trace | tail -1
echo "hits=$(grep -c fgg $TR/trace)"
echo '-:fgg' >> $TR/kprobe_events 2>/dev/null
echo "$OLD_ON" > $TR/tracing_on
