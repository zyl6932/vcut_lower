#!/system/bin/sh
#
# 开机加载 vcut_lower.ko
#
# ★最重要的部分是「开机循环自停用」：
#   自启动唯一的真风险是——模块若在开机时 panic，会变成开机→崩溃→重启的死循环，
#   那才是真正难恢复的状态。这里用一个跨重启的失败计数来兜底：
#     每次开机先把计数 +1；只有稳定运行 STABLE_SEC 秒后才清零。
#     若连续 MAX_FAIL 次开机都没能撑到清零，就创建 disable 文件自我停用。
#   另外 KernelSU 本身还有音量下键安全模式可以禁用全部模块，作为最后一道保险。
#
MODDIR=${0%/*}
DATA=/data/adb/vcut_lower
LOG=$DATA/boot.log
GUARD=$DATA/bootfail
CFG=$DATA/config.sh

MAX_FAIL=2          # 连续多少次开机不稳定就自停用
STABLE_SEC=120      # 撑过多少秒算「这次开机稳定」

mkdir -p "$DATA"
# 日志不要无限长
[ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 65536 ] && : > "$LOG"

log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"; }

# ---------- 开机循环自停用 ----------
n=$(cat "$GUARD" 2>/dev/null)
case "$n" in ''|*[!0-9]*) n=0 ;; esac
if [ "$n" -ge "$MAX_FAIL" ]; then
  log "★连续 $n 次开机未达稳定，自动停用模块（如需恢复：删除 $MODDIR/disable 并清空 $GUARD）"
  touch "$MODDIR/disable"
  exit 0
fi
echo $((n + 1)) > "$GUARD"

# ---------- 等系统起来 ----------
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done
sleep 15

# ---------- 参数（config.sh 可覆盖，升级模块不会丢）----------
VCUT_FW=2620
VCUT_DELAY=2620
VCUT_SW=2520
KILL_VPACK_GATE=1
SOC_CTL=1
FAKE_CYCLE=1
FAKE_FG_CYCLE=1
FCC_MAH=6801
QBOT_MAH=0
RINT_MOHM=28
[ -f "$CFG" ] && . "$CFG"

# ---------- 加载 ----------
lsmod | grep -q '^vcut_lower' && { rmmod vcut_lower 2>/dev/null; sleep 1; }
insmod "$MODDIR/vcut_lower.ko" \
    enable=1 \
    vcut_fw="$VCUT_FW" vcut_delay="$VCUT_DELAY" vcut_sw="$VCUT_SW" \
    kill_vpack_gate="$KILL_VPACK_GATE" \
    soc_ctl="$SOC_CTL" fake_cycle="$FAKE_CYCLE" fake_fg_cycle=-1 \
    fcc_mah="$FCC_MAH" qbot_mah="$QBOT_MAH" rint_mohm="$RINT_MOHM" >>"$LOG" 2>&1
log "insmod rc=$? (fw/delay/sw=$VCUT_FW/$VCUT_DELAY/$VCUT_SW soc_ctl=$SOC_CTL fake_cycle=$FAKE_CYCLE qbot=$QBOT_MAH)"

# ---------- 伪装 fg 循环数：抬回被老化策略压掉的满充电压 ----------
# ★2026-08-05 实测（三次可重复）：唯一有效的杠杆是【写 fg_master/cyclecount】。
#   模块里那个 kretprobe（挂 strategy_class_fg_ops_get_cyclecount）够不着 FV 计算 ——
#   实测它和 fake_cycle 一样只能改到 battery/cycle_count 的显示，term_volt 纹丝不动，
#   所以模块参数固定传 -1，这里改走 sysfs。
#
#   该写口是 bq27z561 驱动自带的 fake 接口，反汇编证实【纯 RAM，不写电量计 DF】
#   （qmax_cyclecount 始终是真值）。已实测连续 20 分钟、跨充放电切换都不会被刷回，
#   所以开机写一次就够，不需要常驻进程。
FGM=/sys/devices/virtual/xm_power/fg_master
CHGIF=/sys/devices/platform/soc/soc:mca_charge_interface
if [ "$FAKE_FG_CYCLE" -ge 0 ] 2>/dev/null && [ -w "$FGM/cyclecount" ]; then
  real=$(cat "$FGM/cyclecount")
  echo "$FAKE_FG_CYCLE" > "$FGM/cyclecount"
  log "fg cyclecount: $real -> $(cat $FGM/cyclecount)（DF 真值 qmax_cyclecount=$(cat $FGM/qmax_cyclecount) 未动）"

  # ★票是粘的：改完必须触发一次充电状态变化，jeita 才会重算满充电压
  if [ -w "$CHGIF/input_suspend" ]; then
    echo "micharge buck 1" > "$CHGIF/input_suspend"; sleep 5
    echo "micharge buck 0" > "$CHGIF/input_suspend"; sleep 6
  fi
  D=/data/local/tmp/.vcutdbg; mkdir -p $D
  mount -t debugfs none $D 2>/dev/null
  log "重新投票后 term_volt: $(grep effective $D/business-votable/term_volt/status 2>/dev/null)"
  umount $D 2>/dev/null; rmdir $D 2>/dev/null
fi

# ---------- 自校验：armed 必须为 1 ----------
# armed=0 表示模块首次命中时的结构体合法性校验没过（偏移在当前 build 上不对），
# 这种情况绝不能让它继续跑，立刻卸载。
sleep 5
armed=$(cat /sys/module/vcut_lower/parameters/armed 2>/dev/null)
if [ "$armed" != "1" ]; then
  log "★armed=$armed 校验未通过，已卸载模块（偏移可能与当前内核不符）"
  rmmod vcut_lower 2>/dev/null
  exit 0
fi
log "armed=1 校验通过"

# ---------- 稳定后清零失败计数 ----------
sleep "$STABLE_SEC"
echo 0 > "$GUARD"
log "已稳定运行 ${STABLE_SEC}s，失败计数清零；t_hits=$(cat /sys/module/vcut_lower/parameters/t_hits 2>/dev/null)"
