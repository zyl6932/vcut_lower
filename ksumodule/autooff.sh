#!/system/bin/sh
#
# 开机时自动坐实【只读字段】偏移（2026-10-21）—— 把 tools/gridump.sh 的人工对账自动化。
#
# ★为什么需要：current/cycle/temp/rm/fcc/raw_soc/soc/design 在目标函数里没有语义锚点，
#   模块只能用全局 delta 推导；OS4.0.0.32 那种【非均匀位移】（§89）一来就错位，
#   validate 失败 → armed=0 → 只能回电脑重新适配。
#
# 做法（全程只读，不加载模块、不写内核内存）：
#   1) tracefs 临时 kprobe（两个事件各 96 个参数），把 strategy 结构体 0x9c0..0xcc0 逐 4 字节读出来
#   2) 先在整段里找与 design_capacity【精确相等且唯一】的位置 → 得到本 build 的位移 D
#      （OS4.0.4.0 实测整体 +0x130，按编译期基线开窗根本够不着，必须先定锚）
#   3) 其余字段在「基线 + D ± 0x40」窗口里找与 sysfs 真值相符的位置
#   4) ★只有【唯一命中】才采用；0 个或多个候选 → 不给，模块沿用 delta 推导（fail-closed）
#      唯一例外：fcc 有两个等值副本时取紧贴 rm 之后那个（rm/fcc 相邻跨所有 build 恒定）
#   5) 采用的偏移还必须保持基线的先后顺序，否则全部作废
#
# 输出：stdout 一行 insmod 参数（如 "ovr_rm=0xa1c ovr_fcc=0xa20"），可能为空。
#       诊断信息走 stderr（service.sh 重定向进 boot.log）。
#
# ★下面的基线（BASE_*，十进制）必须与 vcut_lower.c 里的 BASE_* 保持一致。
# ★真值节点（OS4.0.4.0 实测）：结构体 fcc == fg_master/charger_full，
#   【不是】battery/charge_full（后者 7417000，结构体 6958000）。
#
TR=${AUTOOFF_TR:-/sys/kernel/tracing}      # 环境变量只用于离线测试
SYM=mca_battery_shutdown_check_shutdown_delay
EV=vcut_autooff
BAT=${AUTOOFF_BAT:-/sys/class/power_supply/battery}
FGM=${AUTOOFF_FGM:-/sys/devices/virtual/xm_power/fg_master}
START=$((0x9c0))
END=$((0xcc0))
PER_EV=96           # 每个事件的参数数（内核上限 128；命令行还受 4KB 写缓冲限制）
MAXWAIT=${AUTOOFF_WAIT:-60}   # OS4.0.4.0 上目标函数约 10s 才调用一次，要等到命中为止

say() { echo "autooff: $*" >&2; }
rd() { v=$(cat "$1" 2>/dev/null); case "$v" in ''|*[!0-9-]*) echo X ;; *) echo "$v" ;; esac; }

[ -w "$TR/kprobe_events" ] || { say "tracefs 不可写，跳过"; exit 0; }

NEV=$(( (END - START) / 4 / PER_EV ))
cleanup() {
  i=0
  while [ $i -lt $NEV ]; do
    [ -e "$TR/events/kprobes/$EV$i" ] && echo 0 > "$TR/events/kprobes/$EV$i/enable"
    echo "-:$EV$i" >> "$TR/kprobe_events" 2>/dev/null
    i=$((i + 1))
  done
  [ -n "$OLD_ON" ] && echo "$OLD_ON" > "$TR/tracing_on"
}

OLD_ON=$(cat "$TR/tracing_on")
cleanup

i=0
while [ $i -lt $NEV ]; do
  ARGS=""
  off=$((START + i * PER_EV * 4))
  end=$((off + PER_EV * 4))
  while [ $off -lt $end ]; do
    ARGS="$ARGS o$off=+0x$(printf '%x' $off)(%x0):s32"
    off=$((off + 4))
  done
  if ! echo "p:$EV$i $SYM$ARGS" >> "$TR/kprobe_events" 2>/dev/null; then
    say "注册 kprobe $EV$i 失败，跳过"; cleanup; exit 0
  fi
  i=$((i + 1))
done

echo > "$TR/trace"
i=0
while [ $i -lt $NEV ]; do echo 1 > "$TR/events/kprobes/$EV$i/enable"; i=$((i + 1)); done
echo 1 > "$TR/tracing_on"

# 等到每个事件都至少命中一次（同一次函数调用里依次触发）
t=0
while [ $t -lt $MAXWAIT ]; do
  sleep 2; t=$((t + 2))
  got=0; i=0
  while [ $i -lt $NEV ]; do grep -q " $EV$i: " "$TR/trace" && got=$((got + 1)); i=$((i + 1)); done
  [ $got -eq $NEV ] && break
done
i=0
while [ $i -lt $NEV ]; do echo 0 > "$TR/events/kprobes/$EV$i/enable"; i=$((i + 1)); done
LINES=""
i=0
while [ $i -lt $NEV ]; do
  LINES="$LINES
$(grep " $EV$i: " "$TR/trace" | tail -1)"
  i=$((i + 1))
done
# sysfs 紧接着读
S_DESIGN=$(rd $FGM/design_capacity); [ "$S_DESIGN" = X ] && S_DESIGN=$(rd $BAT/charge_full_design)
S_FULL=$(rd $FGM/charger_full)
S_TEMP=$(rd $BAT/temp)
S_CUR=$(rd $BAT/current_now)
S_CAP=$(rd $BAT/capacity)
S_CYC1=$(rd $BAT/cycle_count)
S_CYC2=$(rd $FGM/cyclecount)
S_CYC3=$(rd $FGM/qmax_cyclecount)
S_RM=$(rd $FGM/rm)
cleanup

[ $got -eq $NEV ] || { say "${MAXWAIT}s 内目标函数未命中（$got/$NEV），跳过"; exit 0; }
say "命中用时 ${t}s；sysfs design=$S_DESIGN charger_full=$S_FULL temp=$S_TEMP cur=$S_CUR cap=$S_CAP cyc=$S_CYC1/$S_CYC2/$S_CYC3 rm=$S_RM"

echo "$LINES" | awk \
  -v design="$S_DESIGN" -v full="$S_FULL" -v temp="$S_TEMP" -v cur="$S_CUR" \
  -v cap="$S_CAP" -v cyc1="$S_CYC1" -v cyc2="$S_CYC2" -v cyc3="$S_CYC3" -v rm="$S_RM" '
function abs(x) { return x < 0 ? -x : x }
# v 是否接近 t（t 为 X 表示该 sysfs 节点读不到）；STRICT=1 时要求完全相等
function near(v, t, tol) { return t != "X" && abs(v - t) <= (STRICT ? 0 : tol) }
# 容量类：兼容 sysfs 用 mAh 或 µAh 两种单位
function nearcap(v, t, pct) {
  if (t == "X" || t <= 0) return 0
  if (STRICT) pct = 0
  return abs(v - t) <= t * pct || abs(v - t * 1000) <= t * 1000 * pct
}
# ≤5 的循环数（含被伪装成 1 的 fg cyclecount）和标志位撞值，不拿来匹配
function cycsrc(t) { return t != "X" && t > 5 ? t : "X" }
# 两轮：先找完全相等（唯一即采用，多个即歧义），一个都没有再放宽到容差
function pick(name, base, kind, pref,   r) {
  STRICT = 1; r = scan(name, base, kind, pref)
  if (r == -2) { STRICT = 0; r = scan(name, base, kind, pref) }
  return r < 0 ? -1 : r
}
# 在 [base+D-win, base+D+win] 且位于 (LO, design) 之间的范围里找满足 kind 条件的位置；唯一命中返回偏移，0 个返回 -2，多个返回 -1
# pref ≥ 0 时：多个候选里恰好含 pref 则取 pref（只用于有结构不变量背书的字段）
function scan(name, base, kind, pref,   off, n, got, v, ok, cand, haspref) {
  n = 0; cand = ""; haspref = 0
  # 小数值字段（易与标志/副本撞值）窗口收窄到 ±0x20，大数值字段 ±0x40
  win = (kind == "cur" || kind == "cycle" || kind == "temp" || kind == "soc") ? 32 : 64
  for (off = base + D - win; off <= base + D + win; off += 4) {
    if (!(off in W)) continue
    # 结构顺序约束：必须在上一个已坐实字段之后、design 锚点之前
    if (off <= LO || off >= dsg) continue
    v = W[off]
    if (kind == "design")      ok = nearcap(v, design, 0.005)
    else if (kind == "fcc")    ok = nearcap(v, full, 0.005)
    else if (kind == "rm")     ok = nearcap(v, rm, 0.01)
    else if (kind == "temp")   ok = near(v, temp, 20)
    else if (kind == "soc")    ok = near(v, cap, 1)
    else if (kind == "cycle")  ok = near(v, cycsrc(cyc1), 0) || near(v, cycsrc(cyc2), 0) || near(v, cycsrc(cyc3), 0)
    else if (kind == "cur")    ok = cur != "X" && abs(v - cur) <= (abs(cur) / 4 > 150000 ? abs(cur) / 4 : 150000)
    else if (kind == "raw")    ok = near(v, RAWT, 30)
    if (ok) { n++; got = off; cand = cand sprintf(" 0x%x(%d)", off, v); if (off == pref) haspref = 1 }
  }
  if (n > 1 && haspref) {
    R[name] = pref; LO = pref
    printf "autooff: %-7s 0x%x -> 0x%x 多候选(%s)取结构相邻位\n", name, base, pref, substr(cand, 2) > "/dev/stderr"
    return pref
  }
  if (n == 1) {
    R[name] = got; LO = got
    printf "autooff: %-7s 0x%x -> 0x%x 唯一命中%s\n", name, base, got, (STRICT && kind != "cur" ? "（精确）" : "") > "/dev/stderr"
    return got
  }
  if (n == 0 && STRICT) return -2
  printf "autooff: %-7s 未坐实（%d 个候选:%s），沿用 delta\n", name, n, cand > "/dev/stderr"
  return -1
}
{
  for (i = 1; i <= NF; i++)
    if ($i ~ /^o[0-9]+=/) { split($i, kv, "="); W[substr(kv[1], 2) + 0] = kv[2] + 0 }
}
END {
  # ---- 定锚：整段里与 design_capacity 精确相等且唯一的位置 ----
  n = 0
  if (design != "X" && design > 0)
    for (off in W) if (W[off] == design || W[off] == design * 1000) { n++; dsg = off + 0 }
  if (n != 1) { printf "autooff: ★design 锚点不唯一（%d 处），放弃坐实\n", n > "/dev/stderr"; exit 0 }
  D = dsg - 2632
  LO = -1
  printf "autooff: design 锚点 0x%x，位移 D=%+d(0x%x)\n", dsg, D, D > "/dev/stderr"

  # 基线（十进制）= vcut_lower.c 的 BASE_*，顺序即结构体内先后顺序
  pick("current", 2544, "cur", -1)
  pick("cycle",   2564, "cycle", -1)
  pick("temp",    2568, "temp", -1)
  r = pick("rm",  2588, "rm", -1)
  f = pick("fcc", 2592, "fcc", r >= 0 ? r + 4 : -1)
  if (r >= 0 && f >= 0 && W[f] > 0) { RAWT = int(W[r] * 10000 / W[f]); if (RAWT > 10000) RAWT = 10000; pick("raw_soc", 2600, "raw", -1) }
  else printf "autooff: raw_soc 未坐实（rm/fcc 未坐实，无法计算真值）\n" > "/dev/stderr"
  pick("soc",     2620, "soc", -1)
  R["design"] = dsg

  # 先后顺序校验：坐实的偏移必须严格递增，否则说明有误配，全部作废
  split("current cycle temp rm fcc raw_soc soc design", ORD, " ")
  last = -1
  for (i = 1; i <= 8; i++) {
    if (!(ORD[i] in R)) continue
    if (R[ORD[i]] <= last) { print "autooff: ★坐实结果顺序错乱，全部作废" > "/dev/stderr"; exit 0 }
    last = R[ORD[i]]
  }
  out = ""
  for (i = 1; i <= 8; i++)
    if (ORD[i] in R) out = out sprintf(" ovr_%s=0x%x", ORD[i], R[ORD[i]])
  print substr(out, 2)
}'
