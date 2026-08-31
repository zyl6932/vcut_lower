#!/system/bin/sh
#
# 深放实测：断充 + 全程记录（★只读不写，fake_soc 由内核模块接管，采集器绝不插手）
#
# 产出 /data/local/tmp/discharge_test.csv —— 这是标定 QBOT / R_int / f(V) 的原始数据，
# 也是验证"能否真的放到 2.7V 才关机"的证据。手机关机后文件仍在，重启后 pull 即可。
#
set -u
BAT=/sys/class/power_supply/battery
FGM=/sys/devices/virtual/xm_power/fg_master
SFG=/sys/devices/platform/soc/soc:strategy-fg
MP=/sys/module/vcut_lower/parameters
CI=/sys/devices/platform/soc/soc:mca_charge_interface
LOG=/data/local/tmp/discharge_test.csv

rd() { read -r _v < "$1" 2>/dev/null && echo "$_v" || echo ""; }

# 退出时恢复充电（正常退出/被 kill 都会走到；断电关机则靠重启自然恢复）
restore() {
    echo "micharge buck 0" > $CI/input_suspend 2>/dev/null
    echo "$(date '+%H:%M:%S') RESTORE_CHARGING suspend_status=$(rd $CI/suspend_status)" >> $LOG
}
trap restore EXIT INT TERM HUP

[ -f "$LOG" ] && mv "$LOG" "/data/local/tmp/discharge_test.$(date +%m%d_%H%M).csv"
echo "time,uptime_s,raw_soc,rm_uAh,V_mV,I_uA,temp,capacity,fake_soc,t_disp,t_mean_volt,t_failsafe,status,seg" > $LOG

echo "=== 断充（USB 数据不断，电池真放电）==="
echo "micharge buck 1" > $CI/input_suspend
sleep 2
echo "  suspend_status=$(rd $CI/suspend_status)  usb_online=$(rd /sys/class/power_supply/usb/online)  status=$(rd $BAT/status)"
echo "  模块 vcut_delay=$(rd $MP/vcut_delay)  soc_ctl=$(rd $MP/soc_ctl)  armed=$(rd $MP/armed)"
echo "  开始记录 -> $LOG"

bottom_marked=0
while :; do
    up=$(cut -d. -f1 /proc/uptime)
    raw=$(rd $SFG/raw_soc)
    [ -z "$raw" ] && raw=-1

    if [ "$raw" -le 0 ] 2>/dev/null; then seg=bottom; tick=2; else seg=top; tick=10; fi

    # 首次进入底部段打个标记：QBOT 的积分从这一刻开始
    if [ "$seg" = "bottom" ] && [ "$bottom_marked" = "0" ]; then
        echo "$(date '+%H:%M:%S'),$up,,,,,,,,,,,,BOTTOM_START" >> $LOG
        bottom_marked=1
    fi

    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
        "$(date '+%H:%M:%S')" "$up" "$raw" \
        "$(rd $FGM/rm)" "$(( $(rd $BAT/voltage_now) / 1000 ))" "$(rd $BAT/current_now)" \
        "$(rd $BAT/temp)" "$(rd $BAT/capacity)" "$(rd $SFG/fake_soc)" \
        "$(rd $MP/t_disp)" "$(rd $MP/t_mean_volt)" "$(rd $MP/t_failsafe)" \
        "$(rd $BAT/status)" "$seg" >> $LOG

    sleep $tick
done
