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

# ---------- 同一时间只跑一个加载流程 ----------
# App 的「重新加载」可能被连点，两个流程并发会抢 rmmod/insmod 与 tracefs 探针（2026-10-21 实际发生）。
# 锁放 /dev（tmpfs）：重启自动清掉，不会因残留锁让开机不加载。放在失败计数之前，避免并发实例误加计数。
LOCK=/dev/vcut_lower.lock
if ! mkdir "$LOCK" 2>/dev/null; then
  log "已有一个加载流程在运行，本次跳过"
  exit 0
fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

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
AUTO_OFF=1          # 1=insmod 前用 autooff.sh 自动坐实只读字段偏移（只读，失败不影响加载）
ENABLE=1
MODE=full
[ -f "$DATA/mode.sh" ] && . "$DATA/mode.sh"   # 安装时按机型写入：popsicle=full，其他=probe
[ -f "$CFG" ] && . "$CFG"                     # config.sh 里写 MODE=full 可人工放行（分析确认后）

# ★观测模式（未验证机型）：一切写入强制关闭，模块只读；开机后自动采集诊断包
if [ "$MODE" != "full" ]; then
  MODE=probe
  ENABLE=0; SOC_CTL=0; KILL_VPACK_GATE=0; FAKE_CYCLE=-1; FAKE_FG_CYCLE=-1
fi
log "MODE=$MODE device=$(getprop ro.product.device) build=$(getprop ro.build.version.incremental)"

# 观测模式下每个系统版本采一次诊断包（后台跑，不拖慢开机稳定计时）
run_collect() {
  [ "$MODE" = "probe" ] || return 0
  mark="$DATA/diag_done_$(getprop ro.build.version.incremental)"
  [ -f "$mark" ] && return 0
  ( sh "$MODDIR/collect.sh" >>"$LOG" 2>&1 && touch "$mark" ) &
  log "观测模式：已在后台生成诊断包（/sdcard/Download/vcut_diag_*.tar.gz）"
}

# ---------- 充电增强守护进程（TurboCharge 并入）----------
# 与关机电压互不依赖：观测模式、模块校验没过都照常跑。A、B 都关时它自己会立即退出。
# 先停旧的：重新加载时会再跑一遍 service.sh，旧进程收到 TERM 会先撤回 A/B 再退出。
pkill -f vcut_turbo.sh 2>/dev/null && sleep 1
nohup sh "$MODDIR/vcut_turbo.sh" >/dev/null 2>&1 &

# ---------- 加载 ----------
lsmod | grep -q '^vcut_lower' && { rmmod vcut_lower 2>/dev/null; sleep 1; }

# ★系统更新后结构体可能【非均匀位移】（§89）：只读字段没有锚点，这里按 sysfs 真值逐字对账，
#   唯一命中的才作为 ovr_* 传给模块；对不上的字段模块沿用 delta 推导，validate 仍是最后一道关。
OVR=""
if [ "$AUTO_OFF" = "1" ] && [ -f "$MODDIR/autooff.sh" ]; then
  OVR=$(sh "$MODDIR/autooff.sh" 2>>"$LOG")
  log "autooff: ${OVR:-（无坐实结果，全部沿用 delta）}"
fi

# 按内核选 .ko：17 系列 6.12/android16 用 vcut_lower.ko；小米 15 等 6.6/android15 用 vcut_lower_6.6.ko（2026-10-21）
KO="$MODDIR/vcut_lower.ko"
case "$(uname -r)" in
  6.6.*) KO="$MODDIR/vcut_lower_6.6.ko" ;;
esac
[ -f "$KO" ] || log "★没有适配本内核 $(uname -r) 的模块文件 ${KO##*/}（只采集诊断，不加载）"
log "模块文件: ${KO##*/}"
# shellcheck disable=SC2086  # $OVR 需要按空格拆成多个参数
insmod "$KO" $OVR \
    enable="$ENABLE" \
    vcut_fw="$VCUT_FW" vcut_delay="$VCUT_DELAY" vcut_sw="$VCUT_SW" \
    kill_vpack_gate="$KILL_VPACK_GATE" \
    soc_ctl="$SOC_CTL" fake_cycle="$FAKE_CYCLE" fake_fg_cycle=-1 \
    fcc_mah="$FCC_MAH" qbot_mah="$QBOT_MAH" rint_mohm="$RINT_MOHM" >>"$LOG" 2>&1
log "insmod rc=$? (enable=$ENABLE fw/delay/sw=$VCUT_FW/$VCUT_DELAY/$VCUT_SW soc_ctl=$SOC_CTL fake_cycle=$FAKE_CYCLE qbot=$QBOT_MAH)"

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
# ★armed 只在【首次命中】时才由 validate 置位。目标函数约 10s 才调用一次（OS4 实测），
#   原先固定 sleep 5 会在没命中时把 armed=0 误判成校验失败（17 Pro 观测模式实际踩到）。
#   所以先等到 t_hits>0 再判；一直没命中则保持加载——未命中就不会写任何东西，首次命中时照常校验。
MP=/sys/module/vcut_lower/parameters
w=0
while [ "$(cat $MP/t_hits 2>/dev/null)" = "0" ] && [ $w -lt 90 ]; do sleep 3; w=$((w + 3)); done
hits=$(cat $MP/t_hits 2>/dev/null)
armed=$(cat $MP/armed 2>/dev/null)
if [ -z "$hits" ]; then
  log "★模块未在运行（insmod 失败？），见上方 insmod rc"
  run_collect
  exit 0
elif [ "$hits" = "0" ]; then
  log "等待 ${w}s 目标函数仍未被调用，保持加载（首次命中时再校验，未通过不会写入）"
elif [ "$armed" != "1" ]; then
  log "★armed=$armed 校验未通过（已命中 $hits 次），已卸载模块（偏移可能与当前内核不符）"
  rmmod vcut_lower 2>/dev/null
  run_collect
  exit 0
fi
[ "$armed" = "1" ] && log "armed=1 校验通过（命中 ${hits} 次，t_resolved=$(cat $MP/t_resolved) sig_ok=$(cat $MP/sig_ok) anchors=$(cat $MP/t_anchors) t_ovr=$(cat $MP/t_ovr) t_cyc_ok=$(cat $MP/t_cyc_ok)）"
run_collect

# ---------- 稳定后清零失败计数 ----------
# 加载与校验已完成，先放锁：等稳定的这 2 分钟里允许再次「重新加载」
rmdir "$LOCK" 2>/dev/null
sleep "$STABLE_SEC"
echo 0 > "$GUARD"
log "已稳定运行 ${STABLE_SEC}s，失败计数清零；t_hits=$(cat /sys/module/vcut_lower/parameters/t_hits 2>/dev/null)"
