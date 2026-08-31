#!/system/bin/sh
#
# soc_watchdog —— 方案C：全程均匀掉电的电量显示
#
#   display% = 100 × (rm + QBOT × f(Vocv)) / (FCC + QBOT)
#   Vocv     = V + |I| × R_INT
#
# 要点（全部来自实测，见 工作日志 §23-§36）：
#   - 上段 rm>0 时 qbot 恒 = QBOT，只需 raw_soc（缓存节点，108µs）→ 零 I2C，tick 30s
#   - 底部段才需 V/I；优先读内核模块导出的遥测（驱动 5Hz 更新好的缓存值）→ 仍是零 I2C
#   - ★判充放方向用【电流符号】（本机正=放电），不能用 status（输入限流 500mA 会让二者脱节）
#   - ★V ≤ VCUT+50 必须写回 fake_soc=-1，否则 check_shutdown_delay@0x9634 跳过发关机 uevent
#   - ★模块没加载就不化妆（关机点仍是 3030，假装有底部电量会误导）
#
# ★★为什么 watchdog 是承重结构而不是化妆品：
#   关机有两条独立路径 ——
#     (a) 内核电压闸 mean_volt <= vcutoff_delay -> 发 uevent    [内核模块已降到 2700]
#     (b) Android 框架 capacity==0 && !charging -> ShutdownThread   [模块够不着]
#   真实 soc = rm/FCC，而 rm 在电量计 Term Voltage(3030mV) 就归零
#   -> 不做任何事的话，手机在 3030mV 就因 (b) 关机，根本轮不到 (a) 的 2700 生效。
#   watchdog 的作用就是在 3030mV 以下持续写非零 fake_soc 挡住 (b)，
#   直到 V<=VCUT+50 才写回 -1，把真实 0% 与关机 uevent 一起放出去。
#
set -u

SFG=/sys/devices/platform/soc/soc:strategy-fg
FGM=/sys/devices/virtual/xm_power/fg_master
BAT=/sys/class/power_supply/battery
MP=/sys/module/vcut_lower/parameters
CFG=/data/local/tmp/soc_watchdog.conf
LOG=/data/local/tmp/soc_watchdog.csv

# ---- 可标定参数（见 方案C §七；默认为估计值，一次深放后回填）----
QBOT=150        # 底部段总容量 mAh（3030mV -> VCUT）
R_INT=20        # 内阻 mΩ（52%SOC 实测 19.3；底部会涨，标定后回填）
VTERM=3030      # 电量计 Term Voltage，rm 触底点
TICK_TOP=30
TICK_BOT=2
FCC_REFRESH=20  # 每 N 个 tick 刷一次 FCC
VLOG_EVERY=10   # 上段每 N 个 tick 采一次 V/I 记进 CSV（标定 f(V) 用，5 分钟一次）
[ -f "$CFG" ] && . "$CFG"

# ---- 退出时务必交还显示控制权 ----
cleanup() {
    echo -1 > $SFG/fake_soc 2>/dev/null
    echo "[$(date +%H:%M:%S)] watchdog 退出，fake_soc 已还原 -1"
    exit 0
}
trap cleanup EXIT INT TERM HUP

rd() { read -r _v < "$1" 2>/dev/null && echo "$_v" || echo ""; }

last_disp10=-1
fcc=0
n=0
beat=0

# ★I 列单位是【微安 µA】（t_current / current_now 的原始单位），不是 mA。
#   标定 QBOT 时按 mA 积分会差 1000 倍，而 QBOT 是整个显示刻度的基准。
echo "time,raw_soc,rm_mAh,fcc,V_mV,I_uA,Vocv_mV,qbot10,disp10,fake_soc_written,src" > $LOG

while :; do
    # ---------- 0) 安全闸：模块未加载 / 未生效就不化妆 ----------
    #   ★必须同时要求 enable=1：enable=0 时 vcutoff 还是原厂 3030，
    #     这时化妆 = 关机点仍在 3030 却假装底部有电，会误导
    vcut=$(rd $MP/vcut_delay)
    armed=$(rd $MP/armed)
    en=$(rd $MP/enable)
    if [ -z "$vcut" ] || [ "$armed" != "1" ] || [ "$en" != "1" ]; then
        [ "$last_disp10" != "-1" ] && echo -1 > $SFG/fake_soc 2>/dev/null
        last_disp10=-1
        sleep $TICK_TOP
        continue
    fi

    # ---------- 0b) 心跳：告诉模块 watchdog 还活着 ----------
    #   模块发现 kick 长时间不变即判定我们已死，强制 fake_soc=-1 交还关机通道
    beat=$(( (beat + 1) % 1000000 ))
    echo $beat > $MP/kick 2>/dev/null

    # ---------- 1) 便宜的缓存读 ----------
    raw=$(rd $SFG/raw_soc)          # = rm/FCC × 10000，缓存
    [ -z "$raw" ] && { sleep $TICK_TOP; continue; }

    if [ "$fcc" = "0" ] || [ $((n % FCC_REFRESH)) -eq 0 ]; then
        f=$(rd $FGM/charger_full)   # µAh，贵(2ms) 但很少变
        [ -n "$f" ] && fcc=$((f / 1000))
    fi
    [ "$fcc" = "0" ] && { sleep $TICK_TOP; continue; }
    n=$((n + 1))

    # ---------- 2) 分段 ----------
    if [ "$raw" -gt 0 ]; then
        # 上段：qbot 恒等于 QBOT，计算上不需要 V/I
        tick=$TICK_TOP
        qbot10=$((QBOT * 10))
        vocv=0; src=top
        # ★但仍每 VLOG_EVERY 个 tick 采一次 V/I 记进 CSV：
        #   这是标定 f(V) 曲线（方案C §七）唯一的数据来源，
        #   开销 ~2ms/5min 可忽略。不采的话整条放电曲线就丢了。
        if [ $((n % VLOG_EVERY)) -eq 0 ]; then
            vv=$(rd $MP/t_mean_volt); ii=$(rd $MP/t_current)
            if [ -z "$vv" ] || [ "$vv" -le 0 ]; then
                vv=$(rd $BAT/voltage_now); vv=$((vv / 1000)); ii=$(rd $BAT/current_now)
            fi
            v=$vv; i=$ii
        else
            v=0; i=0
        fi
    else
        # 底部段：需要 V/I 做 IR 补偿后的电压映射
        tick=$TICK_BOT
        v=$(rd $MP/t_mean_volt); i=$(rd $MP/t_current); src=mod
        if [ -z "$v" ] || [ "$v" -le 0 ]; then          # 模块遥测不可用则回退 sysfs
            vv=$(rd $BAT/voltage_now); ii=$(rd $BAT/current_now)
            v=$((vv / 1000)); i=$ii; src=sysfs
        fi
        ai=$i; [ "$ai" -lt 0 ] && ai=$((-ai))
        vocv=$((v + ai * R_INT / 1000000))              # I 是 µA，R 是 mΩ -> mV
        span=$((VTERM - vcut))
        [ "$span" -le 0 ] && span=1
        fperm=$(( 1000 * (vocv - vcut) / span ))
        [ "$fperm" -lt 0 ]    && fperm=0
        [ "$fperm" -gt 1000 ] && fperm=1000
        qbot10=$(( QBOT * fperm / 100 ))
    fi

    # ---------- 3) 交还关机通道（硬性）----------
    if [ "$src" != "top" ] && [ "$v" -gt 0 ] && [ "$v" -le $((vcut + 50)) ]; then
        echo -1 > $SFG/fake_soc 2>/dev/null
        echo "$(date +%H:%M:%S),$raw,,$fcc,$v,$i,$vocv,,,-1,handoff" >> $LOG
        last_disp10=-1
        sleep $TICK_BOT
        continue
    fi

    # ---------- 4) 唯一的显示公式 ----------
    rm10=$(( raw * fcc / 1000 ))                        # 十分之一 mAh
    tot10=$(( (fcc + QBOT) * 10 ))
    disp10=$(( 1000 * (rm10 + qbot10) / tot10 ))        # 十分之一 %
    [ "$disp10" -gt 1000 ] && disp10=1000
    [ "$disp10" -lt 0 ]    && disp10=0

    # ---------- 5) 单调 + 限速 ----------
    if [ "$last_disp10" -ge 0 ]; then
        # ★方向用电流符号：本机 正=放电
        if [ "$src" = "top" ]; then
            cur=$(rd $MP/t_current); [ -z "$cur" ] && cur=0
        else
            cur=$i
        fi
        if [ "$cur" -gt 0 ]; then
            [ "$disp10" -gt "$last_disp10" ] && disp10=$last_disp10   # 放电禁升
        else
            [ "$disp10" -lt "$last_disp10" ] && disp10=$last_disp10   # 充电禁降
        fi
        d=$((disp10 - last_disp10)); [ "$d" -lt 0 ] && d=$((-d))
        if [ "$d" -gt 10 ]; then                                      # 限速 1%/tick
            [ "$disp10" -gt "$last_disp10" ] && disp10=$((last_disp10 + 10)) \
                                             || disp10=$((last_disp10 - 10))
        fi
    fi

    # ---------- 6) 只在整数变化时才写 ----------
    disp=$(( (disp10 + 5) / 10 ))
    [ "$disp" -gt 100 ] && disp=100
    # ★★绝不写 0：Android 框架 capacity==0 && !charging 就会立刻关机
    #   （真实 soc 在 3030mV 就归零，这正是模块单独用不管用的原因）
    #   要关机时走 step 1 的 handoff：写回 -1，让真实的 0% 和关机 uevent 一起放出去
    [ "$disp" -lt 1 ]   && disp=1
    prev=$(rd $SFG/fake_soc)
    if [ "$prev" != "$disp" ]; then
        echo "$disp" > $SFG/fake_soc 2>/dev/null
    fi
    last_disp10=$disp10

    echo "$(date +%H:%M:%S),$raw,$((rm10/10)),$fcc,$v,$i,$vocv,$qbot10,$disp10,$disp,$src" >> $LOG
    sleep "$tick"
done
