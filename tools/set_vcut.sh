#!/system/bin/sh
# 运行时改 vcutoff 参数并用 tracefs 直读结构体独立验证
# 用法: set_vcut.sh <fw> <delay> <sw>
MP=/sys/module/vcut_lower/parameters
TR=/sys/kernel/tracing
SYM=mca_battery_shutdown_check_shutdown_delay
FW=${1:-2700}; DLY=${2:-2700}; SW=${3:-2600}

dump() {
  echo 0 > $TR/events/kprobes/vchk/enable 2>/dev/null
  echo '-:vchk' >> $TR/kprobe_events 2>/dev/null
  echo "p:vchk $SYM fw=+0xa90(%x0):s32 dly=+0xa94(%x0):s32 sw=+0xa98(%x0):s32 vpen=+0xaa1(%x0):u8 mean=+0x9fc(%x0):s32 soc=+0xa38(%x0):s32" >> $TR/kprobe_events 2>&1
  echo 1 > $TR/events/kprobes/vchk/enable 2>/dev/null
  echo > $TR/trace
  echo 1 > $TR/tracing_on
  sleep 3
  echo 0 > $TR/tracing_on
  grep vchk $TR/trace | tail -2 | sed 's/.*vchk: //'
  echo 0 > $TR/events/kprobes/vchk/enable 2>/dev/null
  echo '-:vchk' >> $TR/kprobe_events 2>/dev/null
}

echo "=== [1] 改之前（结构体实际值）==="
OLD_ON=$(cat $TR/tracing_on)
dump

echo
echo "=== [2] 写入新参数 $FW / $DLY / $SW ==="
echo $FW  > $MP/vcut_fw
echo $DLY > $MP/vcut_delay
echo $SW  > $MP/vcut_sw
echo "  模块参数回读: fw=$(cat $MP/vcut_fw) delay=$(cat $MP/vcut_delay) sw=$(cat $MP/vcut_sw)"
sleep 2

echo
echo "=== [3] 改之后（结构体实际值，应为 $FW/$DLY/$SW）==="
dump
echo "$OLD_ON" > $TR/tracing_on

echo
echo "=== [4] 边界测试：试写一个低于安全下限 VCUT_FLOOR=2500 的值 ==="
echo 2000 > $MP/vcut_delay
echo "  写入 2000 后模块参数 = $(cat $MP/vcut_delay)（模块内部会钳到 2500）"
sleep 2
echo "  结构体实际值:"
dump
echo "  --- 恢复为 $DLY ---"
echo $DLY > $MP/vcut_delay
sleep 2
dump
echo "$OLD_ON" > $TR/tracing_on

echo
echo "=== [5] 系统健康 ==="
echo "  t_hits=$(cat $MP/t_hits)  armed=$(cat $MP/armed)  tainted=$(cat /proc/sys/kernel/tainted)"
echo "  capacity=$(cat /sys/class/power_supply/battery/capacity)  voltage=$(cat /sys/class/power_supply/battery/voltage_now)  status=$(cat /sys/class/power_supply/battery/status)"
dmesg | grep -iE 'BUG|panic|Call trace' | tail -3
echo "  （无 BUG/panic 输出即正常）"
