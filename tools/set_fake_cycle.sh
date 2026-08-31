#!/system/bin/sh
# 伪装循环次数 = 驱动自带的 fake 接口（fg_sysfs_store 只 str 到 chip+0x1a0，不写电量计 DF）
# 用法: set_fake_cycle.sh <值>   （不带参数=只看现状）
FGM=/sys/devices/virtual/xm_power/fg_master
BAT=/sys/class/power_supply/battery
SFG=/sys/devices/platform/soc/soc:strategy-fg
TR=/sys/kernel/tracing
SYM=mca_battery_shutdown_check_shutdown_delay
NEW=$1

dumpcyc() {
  echo '-:cyc' >> $TR/kprobe_events 2>/dev/null
  echo "p:cyc $SYM cyca04=+0xa04(%x0):s32 tmpa08=+0xa08(%x0):s32 fw=+0xa90(%x0):s32 dly=+0xa94(%x0):s32" >> $TR/kprobe_events 2>&1
  echo 1 > $TR/events/kprobes/cyc/enable 2>/dev/null
  echo > $TR/trace; echo 1 > $TR/tracing_on
  sleep 3
  echo 0 > $TR/tracing_on
  grep 'cyc:' $TR/trace | tail -1 | sed 's/.*cyc: /    strategy struct: /'
  echo 0 > $TR/events/kprobes/cyc/enable 2>/dev/null
  echo '-:cyc' >> $TR/kprobe_events 2>/dev/null
}

show() {
  echo "  fg_master/cyclecount  = $(cat $FGM/cyclecount)"
  echo "  battery/cycle_count   = $(cat $BAT/cycle_count)"
  echo "  fg_master/qmax_cyclecount = $(cat $FGM/qmax_cyclecount)"
  dumpcyc
}

OLD_ON=$(cat $TR/tracing_on)
echo "=== [1] 当前 ==="
show

if [ -n "$NEW" ]; then
  echo
  echo "=== [2] 写入 cyclecount = $NEW ==="
  echo "$NEW" > $FGM/cyclecount
  echo "  写入 rc=$?"
  dmesg | grep -i "fake cyclecount" | tail -2
  sleep 3
  echo
  echo "=== [3] 写入后 ==="
  show
fi

echo "$OLD_ON" > $TR/tracing_on
echo
echo "=== 说明 ==="
echo "  该写入只落在驱动 RAM（chip+0x1a0），不碰电量计 DF；重启即恢复。"
echo "  恢复真实值: echo 369 > $FGM/cyclecount"
