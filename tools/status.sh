#!/system/bin/sh
# 当前状态总览
SFG=/sys/devices/platform/soc/soc:strategy-fg
MP=/sys/module/vcut_lower/parameters
TR=/sys/kernel/tracing
SYM=mca_battery_shutdown_check_shutdown_delay

echo "=== [1] 内核模块 ==="
if lsmod | grep -q '^vcut_lower'; then
  echo "  ★已加载"
  for p in armed enable t_hits vcut_fw vcut_delay vcut_sw kill_vpack_gate restore_on_exit; do
    [ -f $MP/$p ] && printf '    %-16s = %s\n' "$p" "$(cat $MP/$p)"
  done
else
  echo "  未加载"
fi

echo
echo "=== [2] watchdog 进程 ==="
if pgrep -f soc_watchdog.sh >/dev/null 2>&1; then
  echo "  ★运行中 pid=$(pgrep -f soc_watchdog.sh | tr '\n' ' ')"
  echo "  CSV 行数 = $(wc -l < /data/local/tmp/soc_watchdog.csv 2>/dev/null || echo 0)"
else
  echo "  未运行"
fi

echo
echo "=== [3] 设备上已部署的文件 ==="
ls -1 /data/local/tmp/ 2>/dev/null | grep -iE 'vcut|watchdog|soc_' | sed 's/^/    /' || echo "    无"

echo
echo "=== [4] 电池 ==="
echo "  capacity = $(cat /sys/class/power_supply/battery/capacity)"
echo "  voltage  = $(cat /sys/class/power_supply/battery/voltage_now)"
echo "  status   = $(cat /sys/class/power_supply/battery/status)"
echo "  raw_soc  = $(cat $SFG/raw_soc)"
echo "  fake_soc = $(cat $SFG/fake_soc)"

echo
echo "=== [5] ★内核里 vcutoff 三值的真实状态（tracefs 直读）==="
# ★事件名用 vcutchk（不是 st）：全局 trace 缓冲区里有别的进程写 trace_marker，
#   短名字 + 宽松 grep 会被顶掉（实测被 cct-algo 的 tracing_mark_write 匹配到）
echo '-:vcutchk' >> $TR/kprobe_events 2>/dev/null
echo "p:vcutchk $SYM fw=+0xa90(%x0):s32 dly=+0xa94(%x0):s32 sw=+0xa98(%x0):s32 vpen=+0xaa1(%x0):u8" >> $TR/kprobe_events 2>&1
OLD_ON=$(cat $TR/tracing_on)
echo 1 > $TR/events/kprobes/vcutchk/enable 2>/dev/null
echo > $TR/trace; echo 1 > $TR/tracing_on
sleep 3
echo 0 > $TR/tracing_on
grep 'vcutchk:' $TR/trace | tail -1 | sed 's/.*vcutchk: /  /'
echo 0 > $TR/events/kprobes/vcutchk/enable 2>/dev/null
echo '-:vcutchk' >> $TR/kprobe_events 2>/dev/null
echo "$OLD_ON" > $TR/tracing_on
echo "  （原厂值 = 3030/3030/2930，vpen=1）"
