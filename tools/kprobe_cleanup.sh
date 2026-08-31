#!/system/bin/sh
# 清除本项目注册的所有 tracefs kprobe 事件，恢复原状
TR=/sys/kernel/tracing
for e in fgdump chipok fgmac fgrd; do
  [ -f $TR/events/kprobes/$e/enable ] && echo 0 > $TR/events/kprobes/$e/enable 2>/dev/null
  echo "-:$e" >> $TR/kprobe_events 2>/dev/null
done
echo 0 > $TR/tracing_on
echo > $TR/trace
echo "剩余 kprobe_events:"
cat $TR/kprobe_events
echo "[END] tracing_on=$(cat $TR/tracing_on)"
