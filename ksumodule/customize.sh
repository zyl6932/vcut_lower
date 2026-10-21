#!/system/bin/sh

ui_print "- VCut Lower v1.9"
ui_print "-   降低软件关机电压 / 电量全程均匀下降"

# ---------- 机型/内核校验 ----------
# ★2026-10-21 起支持「其他机型观测模式」：
#   已验证机型（诊断包经 tools/analyze_diag.py 全部通过、真机跑通）正常生效：
#     popsicle（小米 17 Pro Max）、pandora（小米 17 Pro，2026-10-21 OS4.0.0.38 验证）、
#     pudding（小米 17，2026-10-21 OS4.0.4.0：mca_strategy_fg_comp.ko 与 popsicle 同版本逐字节相同）、
#     nezha（小米 17 Ultra，2026-10-21 OS4.0.4.0 诊断全过、正常模式 armed=1 跑通；原厂关机电压 2850）、
#     myron（REDMI K90 Pro Max，2026-10-21 OS4.0.4.0 诊断全过、观测模式 armed=1，与 17 系列同一套驱动；原厂 3030）；
#   其他机型只要有小米 MCA 电量驱动（目标符号在 kallsyms 里），就以【观测模式】安装：
#   模块只读不写（enable=0），开机自动采集诊断包，发回分析后再决定能否开启。
DEV=$(getprop ro.product.device)
KREL=$(uname -r)
TARGET=mca_battery_shutdown_check_shutdown_delay

ui_print "- 设备: $DEV ($(getprop ro.product.model))"
ui_print "- 内核: $KREL"

[ "$ARCH" = "arm64" ] || abort "! 仅支持 arm64"

if ! grep -qw "$TARGET" /proc/kallsyms; then
  ui_print "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  ui_print "! 未找到小米 MCA 电量驱动（$TARGET）"
  ui_print "! 本模块只能用于带该驱动的小米机型。"
  ui_print "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  abort "! 机型不支持，已中止"
fi

# FG_VERIFIED：伪装电量计循环（写 bq27z561 驱动的 fg_master/cyclecount）"纯 RAM、不碰 DF"
#   是反汇编 17 Pro Max 的 bq27z561.ko 得出的；别的机型要比对过该驱动才算验证，否则新装默认关。
#   （伪装 strategy 循环 FAKE_CYCLE 写的是已坐实偏移的结构体字段，且有 t_cyc_ok 门控，已验证机型照常开）
FG_VERIFIED=0
case "$DEV" in
  popsicle)
    MODE=full; FG_VERIFIED=1
    ui_print "- 机型: 小米 17 Pro Max（已验证）→ 正常模式" ;;
  pandora)
    MODE=full
    ui_print "- 机型: 小米 17 Pro（已验证）→ 正常模式" ;;
  pudding)
    MODE=full
    ui_print "- 机型: 小米 17（已验证）→ 正常模式" ;;
  nezha)
    MODE=full
    ui_print "- 机型: 小米 17 Ultra（已验证）→ 正常模式" ;;
  myron)
    MODE=full
    ui_print "- 机型: REDMI K90 Pro Max（已验证）→ 正常模式" ;;
  *)
    MODE=probe
    ui_print "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
    ui_print "! 机型 $DEV 未经验证 → 安装为【观测模式】"
    ui_print "!   只读不写，不改关机电压，不接管电量显示"
    ui_print "!   开机约 3 分钟后自动生成诊断包："
    ui_print "!   /sdcard/Download/vcut_diag_*.tar.gz"
    ui_print "!   请把它发回给开发者分析。"
    ui_print "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" ;;
esac

case "$KREL" in
  6.12.*-android16-*)
    ui_print "- 内核系列匹配 (android16-6.12)" ;;
  6.6.*-android15-*)
    if [ -f "$MODPATH/vcut_lower_6.6.ko" ]; then
      ui_print "- 内核系列匹配 (android15-6.6，小米 15 等，适配中)"
    else
      ui_print "! android15-6.6 内核：本包未带 6.6 模块，只采集诊断"
    fi ;;
  *)
    ui_print "! 内核不是 android16-6.12 系列，模块大概率加载失败"
    ui_print "! （安全失败：不会加载、不写内核内存；诊断包照常生成）"
    ;;
esac

# ---------- 配置文件放 /data，模块升级不覆盖 ----------
DATA=/data/adb/vcut_lower
mkdir -p "$DATA"
# 默认配置下面会被移走或删掉，充电增强补节要用，先留一份
cp "$MODPATH/config.sh.default" /dev/vcut_def.sh
if [ -f "$DATA/config.sh" ]; then
  ui_print "- 保留已有配置 $DATA/config.sh"
  rm -f "$MODPATH/config.sh.default"
else
  mv "$MODPATH/config.sh.default" "$DATA/config.sh"
  if [ "$FG_VERIFIED" != "1" ]; then
    sed -i 's/^FAKE_FG_CYCLE=[^ #]*/FAKE_FG_CYCLE=-1/' "$DATA/config.sh"
  fi
  ui_print "- 已写入默认配置 $DATA/config.sh"
fi
# 模式单独存放：机型决定，不随 config.sh 保留（换机/刷机后重新判定）
echo "MODE=$MODE" > "$DATA/mode.sh"
echo 0 > "$DATA/bootfail"

# ---------- 充电增强（TurboCharge 并入，2026-10-21）----------
CFG="$DATA/config.sh"
set_cfg() {
  if grep -q "^$1=" "$CFG"; then sed -i "s/^$1=[^ #]*/$1=$2/" "$CFG"; else echo "$1=$2" >> "$CFG"; fi
}
# 只在第一次装带充电增强的版本时初始化；之后升级不动用户自己的选择
if [ ! -f "$DATA/.turbo_init" ]; then
  # 老配置没有这一节：从默认配置整节补上
  grep -q '^ENABLE_FORCE=' "$CFG" || sed -n '/^# ============ 充电增强/,$p' /dev/vcut_def.sh >> "$CFG"
  # 节点与护栏只在 17 Pro Max 上验证过：其他机型默认关，用户自己在 App 里开
  if [ "$DEV" != "popsicle" ]; then
    set_cfg ENABLE_THERMAL_REMOVE 0
    set_cfg ENABLE_FORCE 0
  fi
  touch "$DATA/.turbo_init"
fi
rm -f /dev/vcut_def.sh

# 充电阶梯（v1.9）：第一次装带阶梯的版本时，从本机设备树读原厂 FFC 表（25~35℃）作默认值。
#   表是三元组「拐点电压 拐点前电流 拐点后电流」；截止(≤4495)以上的拐点不算。读不到就不配阶梯（全程 FORCE_MA）。
# 阶梯为空也重新读：17 Ultra 第一次装 v1.9 时没找到带后缀的表，写了空阶梯又打了初始化标记
if [ ! -f "$DATA/.ladder_init" ] || ! grep -q '^LADDER_V=[0-9]' "$CFG"; then
  # 表名各机型不一样（ffc_para_25_35 / ffc_para_25_35_atl / ffc_para_atl_23_35……），照原厂驱动查索引：
  #   batt_para = 三元组「编号 电芯厂商 总表名」，按 fg_master/cell_vendor 选总表（没有匹配取第一家）；
  #   总表每 9 个一行「温度下限 温度上限 - - 最大电流 普通表 FFC表 - stage表」，取覆盖 25℃ 那行的 FFC 表。
  #   查不到再退回按名字找。
  QD=/proc/device-tree/soc/mca_strategy_quick_charge
  CV=$(cat /sys/devices/virtual/xm_power/fg_master/cell_vendor 2>/dev/null)
  pick_ffc() {
    _bt=; _first=
    set -- $(tr '\0' ' ' < "$QD/batt_para" 2>/dev/null)
    while [ $# -ge 3 ]; do
      [ -z "$_first" ] && _first=$3
      [ "$2" = "$CV" ] && _bt=$3
      shift 3
    done
    [ -n "$_bt" ] || _bt=$_first
    [ -n "$_bt" ] && [ -f "$QD/$_bt" ] || return 1
    set -- $(tr '\0' ' ' < "$QD/$_bt")
    while [ $# -ge 9 ]; do
      if [ "$1" -le 25 ] 2>/dev/null && [ "$2" -gt 25 ] 2>/dev/null && [ "$7" != null ]; then echo "$7"; return 0; fi
      shift 9
    done
    return 1
  }
  QC=$QD/$(pick_ffc)
  [ -f "$QC" ] || QC=$QD/ffc_para_25_35
  [ -f "$QC" ] || QC=$QD/ffc_para_25_35_$CV
  ui_print "- 原厂 FFC 表：${QC##*/}"
  lv=; lm=
  if [ -f "$QC" ]; then
    set -- $(tr '\0' ' ' < "$QC")
    [ $# -ge 3 ] && lm=$2
    while [ $# -ge 3 ]; do
      [ "$1" -lt 4495 ] 2>/dev/null && { lv="$lv${lv:+,}$1"; lm="$lm,$3"; }
      shift 3
    done
  fi
  if [ -n "$lv" ]; then
    set_cfg LADDER_V "$lv"; set_cfg LADDER_MA "$lm"
    ui_print "- 充电阶梯：已按本机原厂 FFC 表设置（拐点 $lv mV，电流 $lm mA）"
  else
    set_cfg LADDER_V ""; set_cfg LADDER_MA ""
    ui_print "- 充电阶梯：本机设备树里没有原厂 FFC 表，按单档 FORCE_MA 运行"
  fi
  touch "$DATA/.ladder_init"
fi
# 带载电压上限超过 4495 的老配置钳回来（守护进程也会钳）
v=$(sed -n 's/^MAX_VBAT=\([0-9]*\).*/\1/p' "$CFG" | head -1)
[ -n "$v" ] && [ "$v" -gt 4495 ] && set_cfg MAX_VBAT 4495

# 旧 turbo_charge 模块：迁移配置、停掉守护进程、禁用，避免两个守护进程抢同一组节点
OLD_TC=/data/adb/modules/turbo_charge
if [ -d "$OLD_TC" ] && [ ! -f "$OLD_TC/disable" ]; then
  if [ -f /data/adb/turbo_charge/config.sh ]; then
    for k in ENABLE_THERMAL_REMOVE ENABLE_FORCE FORCE_MA MAX_SOC MAX_VBAT MAX_BTEMP MAX_CTHERM MAX_CONN \
             HYST_SOC HYST_VBAT HYST_BTEMP HYST_CTHERM HYST_CONN; do
      v=$(sed -n "s/^$k=\([^ #]*\).*/\1/p" /data/adb/turbo_charge/config.sh | head -1)
      [ -n "$v" ] && set_cfg "$k" "$v"
    done
    ui_print "- 已迁移 TurboCharge 的配置（护栏数值保持你原来的设置）"
  fi
  # 旧守护进程收到 TERM 会先撤回 A/B（它的 trap 里 all_off），再退出
  pkill -f "$OLD_TC/turbo_charge.sh" 2>/dev/null
  touch "$OLD_TC/disable"
  ui_print "- 已禁用旧的 TurboCharge 模块（充电增强已并入本模块，重启后生效）"
fi

set_perm "$MODPATH/service.sh"   0 0 0755
set_perm "$MODPATH/action.sh"    0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/autooff.sh"   0 0 0755
set_perm "$MODPATH/collect.sh"   0 0 0755
set_perm "$MODPATH/vcut_turbo.sh" 0 0 0755
set_perm "$MODPATH/vcut_lower.ko" 0 0 0644
[ -f "$MODPATH/vcut_lower_6.6.ko" ] && set_perm "$MODPATH/vcut_lower_6.6.ko" 0 0 0644
set_perm "$MODPATH/module.prop"  0 0 0644
set_perm_recursive "$DATA" 0 0 0700 0600

# ---------- 配套 App ----------
# pm（system_server）读不到 modules_update 目录，先复制到 /data/local/tmp 再装
if [ -f "$MODPATH/vcutlower.apk" ]; then
  cp "$MODPATH/vcutlower.apk" /data/local/tmp/vcutlower.apk
  chmod 0644 /data/local/tmp/vcutlower.apk
  if pm install -r /data/local/tmp/vcutlower.apk >/dev/null 2>&1; then
    ui_print "- 已安装配套 App「VCut Lower」"
    ui_print "-   首次打开前请在 KernelSU → 超级用户 中允许它"
    rm -f "$MODPATH/vcutlower.apk"
  else
    ui_print "! 配套 App 自动安装失败，可手动安装："
    ui_print "!   /data/adb/modules/vcut_lower/vcutlower.apk"
    set_perm "$MODPATH/vcutlower.apk" 0 0 0644
  fi
  rm -f /data/local/tmp/vcutlower.apk
fi

ui_print " "
ui_print "- 安装完成，重启后生效"
ui_print "- 配置: $DATA/config.sh"
ui_print "- 日志: $DATA/boot.log"
ui_print "- 状态: 在 KernelSU 里点模块的「执行」查看"
ui_print " "
ui_print "! 安全说明："
ui_print "!  连续 2 次开机不稳定会自动停用本模块"
ui_print "!  KernelSU 音量下键安全模式可禁用全部模块"
ui_print "!  rmmod vcut_lower 即完全还原"
ui_print " "
ui_print "! ★电池风险（务必知悉）："
ui_print "!  本模块只放宽【软件】关机电压，电量计固件与"
ui_print "!  PMIC 硬件过放保护完全不碰。但反复深放到"
ui_print "!  2.6~2.7V 会加速电池老化、有析锂风险。"
ui_print "!  低温环境不要深放。默认 2620 已较激进，"
ui_print "!  求稳可在 config.sh 里调高（如 2800/2800/2700）。"
ui_print "!  风险自负。"
