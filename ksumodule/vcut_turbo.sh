#!/system/bin/sh
#
# 充电增强守护进程 —— 由 TurboCharge v2.1（turbo_charge 模块）并入 vcut_lower（2026-10-21）
#
#   功能 A：摘除有线热限流（亮屏发热时不被砍到 2000mA）
#   功能 B：强制提升 curr_max，绕过 jeita 瓶颈
#   护栏：SOC / 带载电压 / 电芯·充电电路·接口三路温度，超限自动撤回，带回差
#
#   ★两项功能与护栏逻辑与 TurboCharge v2.1 一字未改。并入时只改了：
#     1) 配置读 /data/adb/vcut_lower/config.sh，日志写 /data/adb/vcut_lower/turbo.log
#     2) 每轮把实时状态写到 /dev/vcut_turbo.state 供 App 显示（tmpfs，不磨闪存）
#     3) A、B 都关时直接退出；本机没有对应节点时标记 unsupported 后退出
#     4) 改名 vcut_turbo.sh，避免和旧 turbo_charge.sh 的 pkill 互相误杀
#
CFG=/data/adb/vcut_lower/config.sh
LOG=/data/adb/vcut_lower/turbo.log
STATE=/dev/vcut_turbo.state

# ==================== 默认参数（config.sh 可覆盖）====================
FORCE_MA=18000
MAX_SOC=80
MAX_VBAT=4460
MAX_BTEMP=420
MAX_CTHERM=70000
MAX_CONN=45000

HYST_SOC=3
HYST_VBAT=80
HYST_BTEMP=30
HYST_CTHERM=4000
HYST_CONN=3000

ENABLE_THERMAL_REMOVE=0
ENABLE_FORCE=0

POLL=5                  # 充电中采样间隔(秒)
IDLE_POLL=30            # 未充电时采样间隔(秒)
LOG_EVERY=6
MAX_LOG=262144
# ====================================================================

mkdir -p /data/adb/vcut_lower
[ -f "$CFG" ] && . "$CFG"

V=/sys/kernel/debug/business-votable/curr_max
B=/sys/class/power_supply/battery
U=/sys/class/power_supply/usb
TR=/sys/class/xm_power/charger/charger_thermal/wired_thermal_remove

mount -t debugfs none /sys/kernel/debug 2>/dev/null

# ---- 状态文件：App 读它判断「开关是否真的在生效」----
st() { echo "run=$1 A=${TR_ON:-0} B=${FORCE_ON:-0} online=${online:-0} soc=${soc:-0} vbat=${vbat:-0} ibat=${ibat:-0} btemp=${btemp:-0} ctherm=${ctherm:-0} conn=${conn:-0} why=${2:-}" > "$STATE"; }

if [ "$ENABLE_THERMAL_REMOVE" != 1 ] && [ "$ENABLE_FORCE" != 1 ]; then
  st off; exit 0
fi
# 节点缺失 = 不是同一套驱动（目前只在 17 Pro Max 上验证），fail-safe：不运行
if [ ! -e "$TR" ] && [ ! -d "$V" ]; then
  st unsupported "本机没有热限流/curr_max 控制节点"; exit 0
fi
[ -e "$TR" ] || ENABLE_THERMAL_REMOVE=0
[ -d "$V" ] || ENABLE_FORCE=0

# ---- 启动时解析一次温度节点路径 ----
resolve_zone() {
  _rz=""
  for _d in /sys/class/thermal/thermal_zone*/; do
    read -r _t < "$_d/type" 2>/dev/null || continue
    if [ "$_t" = "$1" ]; then _rz="${_d}temp"; break; fi
  done
  echo "$_rz"
}
CT=$(resolve_zone charger_therm)
CN=$(resolve_zone conn_therm)

# ---- rd VAR FILE : 零 fork 读一个数字，读不到/非数字则 0 ----
rd() {
  eval "$1=0"
  [ -n "$2" ] || return 0
  read -r _v < "$2" 2>/dev/null || return 0
  case "$_v" in ''|*[!0-9-]*) return 0;; esac
  eval "$1=\$_v"
}

logn=0
log() {
  logn=$((logn + 1))
  if [ $((logn % 50)) = 1 ]; then
    [ "$(stat -c %s "$LOG" 2>/dev/null || echo 0)" -gt "$MAX_LOG" ] && : > "$LOG"
  fi
  echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"
}

tr_off()    { echo 0 > "$TR" 2>/dev/null; }
force_off() { echo 0 > "$V/force_active" 2>/dev/null; echo 0 > "$V/force_val" 2>/dev/null; }
all_off()   { force_off; tr_off; }

trap 'all_off; TR_ON=0; FORCE_ON=0; st off "已停止"; log "收到退出信号，已全部撤回"; exit 0' INT TERM HUP

all_off
log "=== 充电增强启动  A=${ENABLE_THERMAL_REMOVE} B(force ${FORCE_MA}mA)=${ENABLE_FORCE}"
log "    温度节点: charger=${CT:-未找到} conn=${CN:-未找到}  poll=${POLL}/${IDLE_POLL}s"
log "    护栏 soc<${MAX_SOC} vbat<${MAX_VBAT} btemp<${MAX_BTEMP} ctherm<${MAX_CTHERM} conn<${MAX_CONN}（带回差）"

TR_ON=0; FORCE_ON=0; n=0; rescan=0
while true; do
  # 温度节点没解析到时，每 ~20 轮重试一次；期间一律不生效（fail-safe）
  if [ -z "$CT" ] || [ -z "$CN" ]; then
    rescan=$((rescan + 1))
    if [ $((rescan % 20)) = 1 ]; then
      [ -z "$CT" ] && CT=$(resolve_zone charger_therm)
      [ -z "$CN" ] && CN=$(resolve_zone conn_therm)
      [ -n "$CT" ] && [ -n "$CN" ] && log "温度节点已解析: $CT $CN"
    fi
  fi

  rd online  "$U/online"
  rd soc     "$B/capacity"
  rd vnow    "$B/voltage_now"; vbat=$((vnow / 1000))
  rd btemp   "$B/temp"
  rd ctherm  "$CT"
  rd conn    "$CN"
  rd ibat    "$B/current_now"

  # ---- 温度判定（带回差）----
  t_ok=1; t_why=""
  if [ -z "$CT" ] || [ -z "$CN" ]; then
    t_ok=0; t_why="温度节点未就绪"
  else
    if [ "$TR_ON" = 1 ] || [ "$FORCE_ON" = 1 ]; then
      lim_b=$MAX_BTEMP;  lim_c=$MAX_CTHERM;  lim_n=$MAX_CONN
    else
      lim_b=$((MAX_BTEMP - HYST_BTEMP)); lim_c=$((MAX_CTHERM - HYST_CTHERM)); lim_n=$((MAX_CONN - HYST_CONN))
    fi
    [ "$btemp"  -ge "$lim_b" ] && { t_ok=0; t_why="电芯 ${btemp}"; }
    [ "$t_ok" = 1 ] && [ "$ctherm" -ge "$lim_c" ] && { t_ok=0; t_why="充电电路 ${ctherm}"; }
    [ "$t_ok" = 1 ] && [ "$conn"   -ge "$lim_n" ] && { t_ok=0; t_why="接口 ${conn}"; }
  fi

  # ---- 电量/电压判定（带回差）----
  s_ok=1; s_why=""
  if [ "$FORCE_ON" = 1 ]; then
    lim_s=$MAX_SOC; lim_v=$MAX_VBAT
  else
    lim_s=$((MAX_SOC - HYST_SOC)); lim_v=$((MAX_VBAT - HYST_VBAT))
  fi
  [ "$soc"  -ge "$lim_s" ] && { s_ok=0; s_why="SOC ${soc}%"; }
  [ "$s_ok" = 1 ] && [ "$vbat" -ge "$lim_v" ] && { s_ok=0; s_why="vbat ${vbat}mV"; }

  # ---- 功能A ----
  if [ "$ENABLE_THERMAL_REMOVE" = 1 ] && [ "$online" = 1 ] && [ "$t_ok" = 1 ]; then
    if [ "$TR_ON" = 0 ]; then echo 1 > "$TR"; TR_ON=1; log "A 生效: 热限流已摘 (ctherm=${ctherm} conn=${conn})"; fi
  else
    if [ "$TR_ON" = 1 ]; then
      tr_off; TR_ON=0
      [ "$online" = 1 ] && log "A 撤回: ${t_why} 超限，恢复原厂热限流" || log "A 撤回: 未充电"
    fi
  fi

  # ---- 功能B ----
  if [ "$ENABLE_FORCE" = 1 ] && [ "$online" = 1 ] && [ "$t_ok" = 1 ] && [ "$s_ok" = 1 ]; then
    if [ "$FORCE_ON" = 0 ]; then
      echo "$FORCE_MA" > "$V/force_val"; echo 1 > "$V/force_active"
      FORCE_ON=1; n=0
      log "B 生效: force ${FORCE_MA}mA (soc=${soc}% vbat=${vbat}mV btemp=${btemp})"
    fi
  else
    if [ "$FORCE_ON" = 1 ]; then
      force_off; FORCE_ON=0
      if [ "$online" != 1 ]; then log "B 撤回: 未充电"
      elif [ "$t_ok" = 0 ]; then log "B 撤回: ${t_why} 超限"
      else log "B 撤回: ${s_why} 达上限"; fi
    fi
  fi

  # 本轮没生效的原因（App 显示用）：未充电 > 温度 > 电量/电压
  if [ "$online" != 1 ]; then why="未充电"
  elif [ "$t_ok" = 0 ]; then why="$t_why 超限"
  elif [ "$s_ok" = 0 ] && [ "$ENABLE_FORCE" = 1 ]; then why="$s_why 达上限"
  else why=""; fi
  st on "$why"

  if [ "$TR_ON" = 1 ] || [ "$FORCE_ON" = 1 ]; then
    n=$((n + 1))
    if [ $((n % LOG_EVERY)) = 1 ]; then
      log "  A=${TR_ON} B=${FORCE_ON} ibat=${ibat} vbat=${vbat} soc=${soc} btemp=${btemp} ctherm=${ctherm} conn=${conn} eff=$(grep effective $V/status 2>/dev/null | sed 's/.*v=//')"
    fi
  fi

  if [ "$online" = 1 ]; then sleep "$POLL"; else sleep "$IDLE_POLL"; fi
done
