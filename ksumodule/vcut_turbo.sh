#!/system/bin/sh
#
# 充电增强守护进程 —— 由 TurboCharge v2.1（turbo_charge 模块）并入 vcut_lower（2026-10-21）
#
#   功能 A：摘除有线热限流（亮屏发热时不被砍到 2000mA）
#   功能 B：强制提升 curr_max，绕过 jeita 瓶颈
#   护栏：SOC / 带载电压 / 电芯·充电电路·接口三路温度，超限自动撤回，带回差
#
#   v1.9 起 B 改为「充电阶梯」：按带载电池电压分档强制电流（拐点与默认电流取本机设备树 FFC 表），
#   同一次充电内只降档不回升；新增固定的电芯温度下限 23℃（低温不强制，交还原厂，防析锂）。
#   充电中每 5 s 记一行到 /dev/vcut_turbo.log 供 App「功率 / 时间」显示。
#
#   v1.8 并入时（A 与护栏逻辑沿用 TurboCharge v2.1）只改了：
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

# 充电阶梯：LADDER_V = 拐点电压(mV)，LADDER_MA = 各档电流(mA)，比拐点多一个；逗号分隔。
# 空 = 不分档，全程 FORCE_MA（v1.8 行为）
LADDER_V=
LADDER_MA=

ENABLE_THERMAL_REMOVE=0
ENABLE_FORCE=0

POLL=5                  # 充电中采样间隔(秒)
IDLE_POLL=30            # 未充电时采样间隔(秒)
LOG_EVERY=6
MAX_LOG=262144
# ====================================================================

# 固定护栏（不读配置）：电芯温度下限 23.0℃，回差 1.0℃；带载电压上限、单档电流上限
MIN_BTEMP=230
HYST_MIN_BTEMP=10
VBAT_CEIL=4495
MA_CEIL=18000

DLOG=/dev/vcut_turbo.log
# 充电记录（v1.9）：每次充电（插上到拔掉）一个文件，文件名 = 开始时间（epoch 秒），只留最近 KEEP_SESS 次。
# 充电中先写内存盘 DLOG，每 SYNC_EVERY 轮（约 1 分钟）把新增的行追加进来，拔掉时再同步一次——重启最多丢 1 分钟。
SESS=/data/adb/vcut_lower/sessions
SVAR=/dev/vcut_turbo.sess      # 本次充电的会话变量：App 切开关重启本进程时接着记同一次
KEEP_SESS=30
SYNC_EVERY=12
RELOAD=/dev/vcut_turbo.reload

load_cfg() {
  [ -f "$CFG" ] && . "$CFG"
  [ "$MAX_VBAT" -gt "$VBAT_CEIL" ] 2>/dev/null && MAX_VBAT=$VBAT_CEIL
  [ "$FORCE_MA" -gt "$MA_CEIL" ] 2>/dev/null && FORCE_MA=$MA_CEIL
}
mkdir -p /data/adb/vcut_lower
load_cfg

V=/sys/kernel/debug/business-votable/curr_max
B=/sys/class/power_supply/battery
U=/sys/class/power_supply/usb
TR=/sys/class/xm_power/charger/charger_thermal/wired_thermal_remove

mount -t debugfs none /sys/kernel/debug 2>/dev/null

# ---- 状态文件：App 读它判断「开关是否真的在生效」----
st() { echo "run=$1 sid=${SID:-0} A=${TR_ON:-0} B=${FORCE_ON:-0} step=$((${STEP:--1} + 1)) ma=${CUR_MA:-0} online=${online:-0} soc=${soc:-0} vbat=${vbat:-0} ibat=${ibat:-0} btemp=${btemp:-0} ctherm=${ctherm:-0} conn=${conn:-0} why=${2:-}" > "$STATE"; }

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

# ---- 充电阶梯 ----
# lad_step：按 vbat 算出应在第几档（0 起），结果放 LV
lad_step() {
  LV=0; _ifs=$IFS; IFS=,
  for _n in $LADDER_V; do [ "$vbat" -ge "$_n" ] 2>/dev/null && LV=$((LV + 1)); done
  IFS=$_ifs
}
# lad_ma N：第 N 档的电流放 TMA；档数不够取最后一档，没配阶梯取 FORCE_MA；不超过 MA_CEIL
lad_ma() {
  TMA=$FORCE_MA; _i=0; _ifs=$IFS; IFS=,
  for _m in $LADDER_MA; do
    case "$_m" in ''|*[!0-9]*) continue;; esac
    TMA=$_m; [ "$_i" -ge "$1" ] && break; _i=$((_i + 1))
  done
  IFS=$_ifs
  [ "$TMA" -gt "$MA_CEIL" ] && TMA=$MA_CEIL
}
# 本次充电的采样（DLOG）：D 行 = 采样，E 行 = 事件。时间是真实时间（epoch 秒）：插上时读一次 date，
# 之后按开机秒数推算，不必每 5 秒 fork 一次 date
up=0; now=0; SID=; ep0=0; up0=0; dn=0; sn=0; sc=0
uptime_s() { read -r up _ < /proc/uptime; up=${up%.*}; }
now_s() { uptime_s; now=$((ep0 + up - up0)); }
dlog() { now_s; echo "E $now $*" >> "$DLOG"; dn=$((dn + 1)); }
sess_save() { echo "SID=$SID ep0=$ep0 up0=$up0 dn=$dn sn=$sn" > "$SVAR"; }
# 把 DLOG 里还没写进 /data 的行（第 sn+1 行起）追加到本次的文件
sess_sync() {
  [ -n "$SID" ] && [ "$dn" -gt "$sn" ] || return 0
  mkdir -p "$SESS"
  sed -n "$((sn + 1)),\$p" "$DLOG" >> "$SESS/$SID.log" && sn=$dn
  sess_save
}
sess_begin() {
  ep0=$(date +%s); uptime_s; up0=$up; SID=$ep0
  : > "$DLOG"; dn=0; sn=0; sc=0
  dlog "插入充电器"; sess_save
}
sess_end() {
  dlog "拔出充电器"; sess_sync
  now_s
  [ $((now - ep0)) -lt 60 ] && rm -f "$SESS/$SID.log"     # 不到 1 分钟（插歪、抖动）不留
  SID=; rm -f "$SVAR"
  _n=$(ls "$SESS"/*.log 2>/dev/null | wc -l)
  if [ "$_n" -gt "$KEEP_SESS" ]; then
    ls "$SESS"/*.log | sort | head -n $((_n - KEEP_SESS)) | while read -r _f; do rm -f "$_f"; done
  fi
}

# sleep 放后台再 wait：收到信号时 wait 立刻返回、trap 马上执行。原来前台 sleep 要睡完（未充电时 30 秒）才处理信号，
# App 切开关重启守护进程时，旧进程过半分钟才退出、还把 run=off 写进状态，盖掉新进程的状态（开关显示要等很久）
SLP=
trap '[ -n "$SLP" ] && kill "$SLP" 2>/dev/null; sess_sync; all_off; TR_ON=0; FORCE_ON=0; st off "已停止"; log "收到退出信号，已全部撤回"; exit 0' INT TERM HUP

all_off
log "=== 充电增强启动  A=${ENABLE_THERMAL_REMOVE} B=${ENABLE_FORCE} 阶梯 V=[${LADDER_V:-无}] mA=[${LADDER_MA:-$FORCE_MA}]"
log "    温度节点: charger=${CT:-未找到} conn=${CN:-未找到}  poll=${POLL}/${IDLE_POLL}s"
log "    护栏 soc<${MAX_SOC} vbat<${MAX_VBAT} ${MIN_BTEMP}<=btemp<${MAX_BTEMP} ctherm<${MAX_CTHERM} conn<${MAX_CONN}（带回差）"

TR_ON=0; FORCE_ON=0; n=0; rescan=0
STEP=-1; up_cnt=0; CUR_MA=0; was_online=0
# App 开关 A/B 会重启本进程：会话还在就接着记同一次。但两个开关都关时本进程不运行，期间可能已经拔插过——
# 最后一行距今超过 2 分钟就不接了（旧的那次已同步到 /data），按新的一次开始
if [ -s "$DLOG" ] && [ -f "$SVAR" ]; then
  . "$SVAR"
  _last=$(tail -n 1 "$DLOG" | cut -d' ' -f2)
  now_s
  if [ -n "$SID" ] && [ $((now - ${_last:-0})) -lt 120 ]; then was_online=1; else SID=; rm -f "$SVAR"; fi
fi
while true; do
  # App 改了阶梯/护栏：重新读配置，档位重新判定（ENABLE_* 的开关由 App 重启本进程，不走这里）
  if [ -f "$RELOAD" ]; then
    rm -f "$RELOAD"; load_cfg; STEP=-1; up_cnt=0
    log "配置已更新: 阶梯 V=[${LADDER_V:-无}] mA=[${LADDER_MA:-$FORCE_MA}] vbat<${MAX_VBAT} soc<${MAX_SOC}"
  fi

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

  # ---- 充电阶梯档位：同一次充电只降档不回升（连续 2 次采样越过拐点才换档，防尖峰）；拔掉充电器清零 ----
  if [ "$online" = 1 ]; then
    if [ "$was_online" != 1 ]; then sess_begin; STEP=-1; fi
    lad_step
    if [ "$STEP" -lt 0 ]; then STEP=$LV; up_cnt=0
    elif [ "$LV" -gt "$STEP" ]; then
      up_cnt=$((up_cnt + 1))
      [ "$up_cnt" -ge 2 ] && { STEP=$LV; up_cnt=0; }
    else up_cnt=0; fi
  else
    [ "$was_online" = 1 ] && sess_end
    STEP=-1; up_cnt=0
  fi
  was_online=$online

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

  # ---- 电芯温度下限（只管 B；带回差）：低温原厂本来就在限流，强推大电流有析锂风险 ----
  l_ok=1
  if [ "$FORCE_ON" = 1 ]; then lim_l=$MIN_BTEMP; else lim_l=$((MIN_BTEMP + HYST_MIN_BTEMP)); fi
  [ "$btemp" -lt "$lim_l" ] && l_ok=0

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
  if [ "$ENABLE_FORCE" = 1 ] && [ "$online" = 1 ] && [ "$t_ok" = 1 ] && [ "$s_ok" = 1 ] && [ "$l_ok" = 1 ]; then
    lad_ma "$STEP"
    if [ "$FORCE_ON" = 0 ]; then
      echo "$TMA" > "$V/force_val"; echo 1 > "$V/force_active"
      FORCE_ON=1; n=0; CUR_MA=$TMA
      log "B 生效: 第 $((STEP + 1)) 档 force ${TMA}mA (soc=${soc}% vbat=${vbat}mV btemp=${btemp})"
      dlog "强制生效 第 $((STEP + 1)) 档 ${TMA}mA"
    elif [ "$TMA" != "$CUR_MA" ]; then
      echo "$TMA" > "$V/force_val"; CUR_MA=$TMA
      log "B 换档: 第 $((STEP + 1)) 档 force ${TMA}mA (vbat=${vbat}mV)"
      dlog "第 $((STEP + 1)) 档 ${TMA}mA（${vbat}mV）"
    fi
  else
    if [ "$FORCE_ON" = 1 ]; then
      force_off; FORCE_ON=0; CUR_MA=0
      if [ "$online" != 1 ]; then log "B 撤回: 未充电"
      elif [ "$t_ok" = 0 ]; then log "B 撤回: ${t_why} 超限"; dlog "撤回：${t_why} 超限"
      elif [ "$l_ok" = 0 ]; then log "B 撤回: 电芯 ${btemp} 低于下限"; dlog "撤回：电芯 ${btemp} 低于下限"
      else log "B 撤回: ${s_why} 达上限"; dlog "撤回：${s_why} 达上限"; fi
    fi
  fi

  # 本轮没生效的原因（App 显示用）：未充电 > 温度 > 低温 > 电量/电压
  if [ "$online" != 1 ]; then why="未充电"
  elif [ "$t_ok" = 0 ]; then why="$t_why 超限"
  elif [ "$l_ok" = 0 ] && [ "$ENABLE_FORCE" = 1 ]; then why="电芯 ${btemp} 低于下限"
  elif [ "$s_ok" = 0 ] && [ "$ENABLE_FORCE" = 1 ]; then why="$s_why 达上限"
  else why=""; fi
  st on "$why"

  if [ "$TR_ON" = 1 ] || [ "$FORCE_ON" = 1 ]; then
    n=$((n + 1))
    if [ $((n % LOG_EVERY)) = 1 ]; then
      log "  A=${TR_ON} B=${FORCE_ON} ibat=${ibat} vbat=${vbat} soc=${soc} btemp=${btemp} ctherm=${ctherm} conn=${conn} eff=$(grep effective $V/status 2>/dev/null | sed 's/.*v=//')"
    fi
  fi

  # 本次充电的采样（App「充电记录」）：时间 电压mV 电流µA 电量 电芯温度 充电电路温度 接口温度 档位 强制mA
  # （电压/电流/电量的列位置与 v1.9 早期一致，App 的横轴换算按 $3 $4 $5 取）
  if [ "$online" = 1 ]; then
    now_s
    echo "D $now $vbat $ibat $soc $btemp $ctherm $conn $((STEP + 1)) $CUR_MA" >> "$DLOG"
    dn=$((dn + 1)); sc=$((sc + 1))
    [ "$sc" -ge "$SYNC_EVERY" ] && { sess_sync; sc=0; }
    # 内存盘里约 8 小时上限：先同步，再只留后一半（/data 里的文件是完整的）
    if [ "$dn" -gt 6000 ]; then
      sess_sync
      tail -n 3000 "$DLOG" > "$DLOG.t" && mv "$DLOG.t" "$DLOG"; dn=3000; sn=3000; sess_save
    fi
  fi

  if [ "$online" = 1 ]; then sleep "$POLL" & else sleep "$IDLE_POLL" & fi
  SLP=$!; wait "$SLP"; SLP=
done
