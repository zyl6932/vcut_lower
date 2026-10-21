// SPDX-License-Identifier: GPL-2.0
/*
 * vcut_lower —— 降低小米 popsicle 的软件关机电压闸值
 *
 * 原理：kprobe hook mca_strategy_fg_comp 的 check_shutdown_delay，
 *       pre-handler 里把 strategy struct 的 vcutoff 三值就地压低。
 *       因为写在函数体比较之前，永远赢过 update_vcutoff_para 的每周期重算。
 *       rmmod 即完全还原，不写任何持久存储。
 *
 * 安全：
 *   - 只放宽【软件】截止；电量计固件与 PMIC 硬件过放保护完全不碰
 *   - 首次命中先做结构体合法性校验，校验不过则永久禁写（armed=0）
 *   - 参数有硬下限 VCUT_FLOOR，拒绝设置到危险电压
 *
 * 偏移来源：静态反汇编 + 步骤0 在真机用 tracefs kprobe_events 只读 dump 坐实
 */
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/kprobes.h>
#include <linux/ptrace.h>
#include <linux/types.h>
#include <linux/version.h>

#define TARGET_SYM "mca_battery_shutdown_check_shutdown_delay"

/* ---- strategy_fg struct 偏移 ----
 * ★2026-09-04 为 OS4.0.0.32 重新坐实（内核版本串仍未变，vendor 模块又换 build）。
 *   ★★这次是【非均匀位移】——首次出现，也正是它把"单一 delta 自解析"打穿了：
 *     mean_volt(0x9fc) 不动，但结构体里插了两处各 4 字节：
 *       (a) raw_soc 附近插 4 字节 → raw_soc/soc/design 各 +4
 *       (b) design 与 vcut 块之间再插 4 字节 → vcut_fw/delay/sw/empty/gate/
 *           fake_soc/vpack 各 +8
 *   自解析用 mean_volt 推 delta 得 0，把 fw/sw/empty 留在旧偏移（0.0.25），
 *   而锚点把 delay/gate/vpack/fake_soc 正确定到 +8 → 二者错位 → validate 失败
 *   → armed=0 → 自动卸载。真机连续 2 次开机不稳定后模块自停用（boot.log 实录）。
 *   独立坐实手段：tracefs 只读密集网格 dump（0x9e0..0xba4 逐字），逐偏移对 sysfs：
 *     0x9fc=4301≈voltage / 0xa04=408=cyclecount / 0xa1c=5501000=rm /
 *     0xa20=7208000=charger_full / 0xa28=7631=raw_soc / 0xa3c=78=capacity /
 *     0xa48=7500000=design / 0xa98=3130=cutoff / 0xaa0=3030=sw / 0xb0c=-1=fake_soc /
 *     0xb98=4278=vpack。
 *   ★对策（resolve_offsets 已随之加固）：vcut 三兄弟 + vbatt_empty 结构上永远
 *     紧贴 delay（fw=delay-4 / sw=delay+4 / empty=delay+8，4 次 build 无一例外），
 *     故改由【锚点 delay】直接推导，不再吃全局 delta。这样即便再来一次非均匀位移，
 *     只要 delay 锚点命中，这 4 个"会被写"的字段就一定跟着对。
 *
 * ★2026-08-29 为 OS4.0.0.25 坐实（内核版本串未变，vendor 模块换了 build）。
 *   struct 整体再后移 +0x10。那次【没有】例外，16 项全部遵守位移。
 *
 *   三重独立证据：
 *   (1) 静态反汇编：vcut_delay/mean_volt/vpack_gate/vpack/current/fake_soc 六项
 *       直接在 check_shutdown_delay 函数体里对上号。
 *   (2) 真机只读 dump 对账 sysfs（2026-08-29，3 次命中完全一致）：
 *       循环 401==cyclecount / 温度 352==temp / rm 3183000==rm
 *       soc 51==capacity / design 7500000 / vcut_fw 3130==cutoff_vol
 *       fake_soc -1==strategy-fg/fake_soc / vpack 3886≈voltage_now
 *   (3) 全模块引用语义交叉印证：
 *       0xaa1 仅 2 处（parse_dt 写 + check 读）→ 正是"DTS 属性存在"布尔
 *       0xa90 被 mca_battery_shutdown_update_vcutoff_para 写 → vcut_fw
 *       0xa9c 被 strategy_fg_record_volt_mean 读 → vbatt_empty
 *       0xb04 被 strategy_fg_set_fake_soc 写 / strategy_fg_ops_get_soc 读 → fake_soc
 *
 *   ★★务必避开 0xaa0：它有 8 处引用（strategy_fg_process_event / fg_update_status /
 *     update_cc_para 等），是高频标志 —— 与 OS4.0.0.20 时代的 0xa90 是同一个坑：
 *     那次"简单位移"得到的正是它，差点写错内存。
 *     本次位移后 gate 正确落在 0xaa1（= 0xa91 + 0x10），0xaa0 仍是被避开的那个。
 *
 *   括号内为 OS4.0.0.20 / 6.12.69-android16-6 时代的旧偏移，仅作追溯。
 *
 * ============================================================================
 * ★★2026-08-29：这些值现在是【运行时可解析的变量】，不再是编译期常量★★
 *
 *   背景：每次 HyperOS 更新都可能换 vendor 模块 build，struct 偏移随之挪位，
 *   模块就失效，必须回到电脑上「反汇编→改偏移→重编→patch CRC→重装」。
 *   这套流程每次都要人肉做一遍，是本模块最大的维护负担。
 *
 *   改法：模块加载时用【语义锚点】从目标函数的机器码里自己解析出偏移
 *   （详见下面的 resolve_offsets()）。上面的常量退化为「编译期基线」：
 *     - 解析成功 -> 用解析出来的偏移（系统更新后自动适配，无需电脑）
 *     - 解析失败 -> 回退到基线，但必须过指令指纹校验（否则拒绝加载）
 *
 *   ★为什么变量仍用全大写：它们原本是 #define，改成变量是为了运行时可覆盖，
 *     保留原名字可以让全部引用点零改动 —— 内核代码，改动面越小越安全。
 *     真正被锚点直接定下来的只有 5 个（mean_volt / vcut_delay / vpack /
 *     vpack_low_en / fake_soc），其余靠位移量 delta 从基线推导。
 * ============================================================================
 */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 12, 0)
static int OFF_CURRENT      = 0x9f0;  /* s32 uA，★正=放电         （0.0.25 同；旧 0x9e0）*/
static int OFF_MEAN_VOLT    = 0x9fc;  /* s32 mV，近5样本均值；电压闸与判空都比它（0.0.25 同）*/
static int OFF_RAW_SOC      = 0xa28;  /* s32 厘百分比 = rm/FCC×10000 （0.0.25 0xa24 → +4）*/
static int OFF_RM_UAH       = 0xa1c;  /* s32 µAh 剩余容量  = fg_master/rm  （0.0.25 同）*/
static int OFF_FCC_UAH      = 0xa20;  /* s32 µAh 满充容量  = charger_full  （0.0.25 同）*/
static int OFF_DESIGN_UAH   = 0xa48;  /* s32 µAh 设计容量  = design_capacity（0.0.25 0xa44 → +4）*/
static int OFF_CYCLE        = 0xa04;  /* s32 循环次数                     （0.0.25 同）*/
static int OFF_TEMP         = 0xa08;  /* s32 0.1 degC                     （0.0.25 同）*/
static int OFF_SOC          = 0xa3c;  /* s32 内部 soc                     （0.0.25 0xa38 → +4）*/
static int OFF_VCUT_FW      = 0xa98;  /* s32 mV                           （0.0.25 0xa90 → +8）*/
static int OFF_VCUT_DELAY   = 0xa9c;  /* s32 mV ← 电压闸：mean_volt <= 此值即关机（0.0.25 0xa94 → +8）*/
static int OFF_VCUT_SW      = 0xaa0;  /* s32 mV ← UI 判空阈值             （0.0.25 0xa98 → +8）*/
static int OFF_VBATT_EMPTY  = 0xaa4;  /* u8  判空标志                     （0.0.25 0xa9c → +8）*/
static int OFF_VPACK_LOW_EN = 0xaa9;  /* u8  support-vpack-low-shutdown（3000mV 硬闸门控）
                                  * ★0.0.25 0xaa1 → +8；不是 0xaa8（8 处引用的高频标志）*/
static int OFF_FAKE_SOC     = 0xb0c;  /* s32 -1=off                       （0.0.25 0xb04 → +8）*/
static int OFF_VPACK        = 0xb98;  /* s32 mV                           （0.0.25 0xb90 → +8）*/

/* 编译期基线的副本：解析出 delta 后要据此推导其余字段，基线本身不能被覆盖 */
static const int BASE_CURRENT      = 0x9f0;
static const int BASE_MEAN_VOLT    = 0x9fc;
static const int BASE_RAW_SOC      = 0xa28;
static const int BASE_RM_UAH       = 0xa1c;
static const int BASE_FCC_UAH      = 0xa20;
static const int BASE_DESIGN_UAH   = 0xa48;
static const int BASE_CYCLE        = 0xa04;
static const int BASE_TEMP         = 0xa08;
static const int BASE_SOC          = 0xa3c;
static const int BASE_VCUT_FW      = 0xa98;
static const int BASE_VCUT_DELAY   = 0xa9c;
static const int BASE_VCUT_SW      = 0xaa0;
static const int BASE_VBATT_EMPTY  = 0xaa4;
static const int BASE_VPACK_LOW_EN = 0xaa9;
static const int BASE_FAKE_SOC     = 0xb0c;
static const int BASE_VPACK        = 0xb98;
#else
/*
 * ★小米 15（dada，SM8750，6.6.118-android15-8，OS4.0.0.22）的基线（2026-10-21 离线逆向）。
 *   相对 popsicle 是非均匀位移（+40..+56），单一 delta 推不对，故 6.6 这份 .ko 直接以 dada 实测为基线：
 *   在 dada 上自解析 delta=0、指纹通过。来源：锚点 A/B/C（B 是第二种写法 cbz + mov#3001/ccmp）、
 *   autooff 唯一命中（cycle/temp/rm/fcc/raw_soc/design）、静态反汇编 strategy_fg_ops_get_soc（soc 0xb94）、
 *   strategy_fg_shutdown（vbatt_empty 0xbfc、mean 0xb64）；fw/sw/empty 照 delay-4/+4/+8。
 */
static int OFF_CURRENT      = 0xb58;
static int OFF_MEAN_VOLT    = 0xb64;
static int OFF_RAW_SOC      = 0xb88;
static int OFF_RM_UAH       = 0xb80;
static int OFF_FCC_UAH      = 0xb84;
static int OFF_DESIGN_UAH   = 0xba0;
static int OFF_CYCLE        = 0xb6c;
static int OFF_TEMP         = 0xb70;
static int OFF_SOC          = 0xb94;
static int OFF_VCUT_FW      = 0xbf0;
static int OFF_VCUT_DELAY   = 0xbf4;
static int OFF_VCUT_SW      = 0xbf8;
static int OFF_VBATT_EMPTY  = 0xbfc;
static int OFF_VPACK_LOW_EN = 0xc05;
static int OFF_FAKE_SOC     = 0xc6c;
static int OFF_VPACK        = 0xcf8;

static const int BASE_CURRENT      = 0xb58;
static const int BASE_MEAN_VOLT    = 0xb64;
static const int BASE_RAW_SOC      = 0xb88;
static const int BASE_RM_UAH       = 0xb80;
static const int BASE_FCC_UAH      = 0xb84;
static const int BASE_DESIGN_UAH   = 0xba0;
static const int BASE_CYCLE        = 0xb6c;
static const int BASE_TEMP         = 0xb70;
static const int BASE_SOC          = 0xb94;
static const int BASE_VCUT_FW      = 0xbf0;
static const int BASE_VCUT_DELAY   = 0xbf4;
static const int BASE_VCUT_SW      = 0xbf8;
static const int BASE_VBATT_EMPTY  = 0xbfc;
static const int BASE_VPACK_LOW_EN = 0xc05;
static const int BASE_FAKE_SOC     = 0xc6c;
static const int BASE_VPACK        = 0xcf8;
#endif

/* ---- 安全红线 ---- */
#define VCUT_FLOOR 2500
#define VCUT_CEIL  3400

/* ---- 可调参数（编译期默认取保守档；实际值由 config.sh 在 insmod 时传入）---- */
static int vcut_fw    = 2900;
static int vcut_delay = 2900;
static int vcut_sw    = 2800;
static int kill_vpack_gate = 1;  /* 把 [0xaa1] 置 0，摘掉 3000mV 硬闸 */
static int enable = 1;           /* 0 = 只观测不改写 */

module_param(vcut_fw, int, 0644);
MODULE_PARM_DESC(vcut_fw, "vcutoff_fw (mV)");
module_param(vcut_delay, int, 0644);
MODULE_PARM_DESC(vcut_delay, "vcutoff_shutdown_delay (mV) - 电压闸阈值");
module_param(vcut_sw, int, 0644);
MODULE_PARM_DESC(vcut_sw, "vcutoff_sw (mV) - UI 判空阈值");
module_param(kill_vpack_gate, int, 0644);
MODULE_PARM_DESC(kill_vpack_gate, "1=摘掉 3000mV vpack 硬闸");
module_param(enable, int, 0644);
MODULE_PARM_DESC(enable, "0=只观测不改写");

/* ---- 只读遥测：供 watchdog 零 I2C 读取（方案C §四之二）---- */
static int t_mean_volt = -1;
static int t_current   = -1;
static int t_vpack     = -1;
static int t_soc       = -1;
static int t_temp      = -1;
static int t_vbatt_empty = -1;
static int t_fake_soc  = -1;
static int t_hits      = 0;
static int armed       = 0;   /* 1 = 校验通过，允许改写 */

/* ★运行时偏移自解析的可观测性（2026-08-29）：
 *   声明在这里是因为 validate() 要用 t_off_src/t_delta 打日志，
 *   而解析主体 resolve_offsets() 定义在文件后部（它需要 kp.addr）。*/
static int t_resolved;		/* 1=运行时解析成功，OFF_* 已被改写 */
static int t_delta   = -99999;	/* 相对编译期基线的位移量 */
static int t_off_src;		/* 1=运行时自解析  2=基线+指纹校验 */
static int t_anchors;		/* 锚点命中位图: bit0=delay bit1=mean
				 * bit2=vpack bit3=gate bit4=fake_soc */
static int t_scan_insns;	/* 实际扫了多少条指令 */

module_param(t_resolved, int, 0444);
module_param(t_delta, int, 0444);
module_param(t_off_src, int, 0444);
module_param(t_anchors, int, 0444);
module_param(t_scan_insns, int, 0444);

/*
 * ★2026-10-21：只读字段的【外部坐实偏移】（由 service.sh 的 autooff.sh 传入）。
 *   current/cycle/temp/rm/fcc/raw_soc/soc/design 在目标函数里没有语义锚点，
 *   原先只能吃全局 delta —— 一遇非均匀位移（§89）就错位。autooff.sh 在 insmod 前
 *   用 tracefs 只读 dump 结构体、逐字对 sysfs 真值，【唯一命中】的字段才传进来。
 *   -1 = 未坐实，沿用 delta 推导。写字段（vcut 簇/gate/fake_soc）仍只认锚点，不受此影响。
 */
static int ovr_current = -1, ovr_cycle = -1, ovr_temp = -1, ovr_rm = -1;
static int ovr_fcc = -1, ovr_raw_soc = -1, ovr_soc = -1, ovr_design = -1;
static int t_ovr;		/* 生效的覆盖位图: bit0=current bit1=cycle bit2=temp bit3=rm
				 * bit4=fcc bit5=raw_soc bit6=soc bit7=design */
static int t_cyc_ok;		/* 1=cycle 偏移有硬证据（坐实或指纹通过），才允许写 fake_cycle */
/*
 * ★2026-10-21：vpack 硬闸门控（OFF_VPACK_LOW_EN）也是会被【写】的字段（kill_vpack_gate、rmmod 写回）。
 *   锚点B 没找到 gate 时它退回 delta 推导——小米 15 的 6.6 驱动非均匀位移，推出来是 0xc11、真值 0xc05，
 *   而 validate 只要求该字节 ≤1，错位置上多半也是 0/1，拦不住，会往无关字节写 0。
 *   所以与 cycle 同一原则：只有锚点直接定到（t_anchors bit3）或指纹证明仍是基线 build 才写；
 *   不写的代价只是 3000mV 硬闸照旧生效（关机点不低于 3000mV），安全。
 */
static int t_gate_ok;
module_param(t_gate_ok, int, 0444);
module_param(ovr_current, int, 0444);
module_param(ovr_cycle, int, 0444);
module_param(ovr_temp, int, 0444);
module_param(ovr_rm, int, 0444);
module_param(ovr_fcc, int, 0444);
module_param(ovr_raw_soc, int, 0444);
module_param(ovr_soc, int, 0444);
module_param(ovr_design, int, 0444);
module_param(t_ovr, int, 0444);
module_param(t_cyc_ok, int, 0444);

module_param(t_mean_volt, int, 0444);
module_param(t_current, int, 0444);
module_param(t_vpack, int, 0444);
module_param(t_soc, int, 0444);
module_param(t_temp, int, 0444);
module_param(t_vbatt_empty, int, 0444);
module_param(t_fake_soc, int, 0444);
module_param(t_hits, int, 0444);
module_param(armed, int, 0444);

/* ---- 用于 rmmod 时还原原始值 ----
 * ★实测教训：update_vcutoff_para 并非每周期重算（update_cc_para 里有
 *   last_temp_index 静态量做门控，只在温度带跃迁时才重算），
 *   所以我们写下的 vcutoff 三值是【粘住的】——rmmod 后不会自己回到原值。
 *   必须由模块自己在 exit 里写回，否则会留下一个无人守护的低关机电压。
 */
static void *saved_struct;
static u8    saved_vpack_en;
static s32   saved_fw, saved_delay, saved_sw;
/* 只读导出原厂电压闸（首次命中通过校验时的快照，0 = 尚未命中）：App 据此估算解锁容量 */
module_param_named(t_orig_delay, saved_delay, int, 0444);
static int   saved_valid;
static int   t_wrote;	/* 1=确实改写过 vcutoff/gate；enable=0 的观测模式下保持 0，卸载时也不写回 */
module_param(t_wrote, int, 0444);

static int restore_on_exit = 1;
module_param(restore_on_exit, int, 0644);
MODULE_PARM_DESC(restore_on_exit, "1=rmmod 时把 vcutoff 三值写回加载前的原值");

/*
 * ================= fail-safe =================
 * 背景：fake_soc != -1 时，① Android 框架看不到 0% 不会关机
 *       ② 内核在 check_shutdown_delay 里读到 fake_soc（[0xb04]）非 -1 就跳过发关机 uevent。
 * 于是 watchdog 若被 SIGKILL（不触发它自己的 trap），两道闸同时失效，
 * 手机会一路放到硬件保护为止 —— 必须由内核侧兜底。
 *
 * 两层：
 *  1) 心跳超时（主力，任何电压下都有效）：watchdog 每 tick 变一次 kick；
 *     长时间不变即判定 watchdog 已死 → 强制 fake_soc = -1，退回原厂行为。
 *  2) 低压兜底：mean_volt <= vcut + lowv_margin 且【连续】lowv_hits 次成立 → 强制 -1。
 *     连续计数是为了防大电流瞬时压降误触发（3A×40mΩ≈120mV）。
 * 计时单位一律用 kprobe 命中次数（约 5Hz），避免依赖 jiffies 等额外符号。
 */
static int kick;                 /* watchdog 每 tick 写入递增值 */
static int kick_timeout = 900;   /* 命中次数；5Hz → 约 180s 无心跳即判死 */
static int lowv_margin  = 30;    /* mV，低于 vcut_delay + 该值即计数 */
static int lowv_hits    = 10;    /* 需连续命中次数（5Hz → 约 2s）*/
static int failsafe_on  = 1;
static int t_failsafe;           /* 只读：0=未触发 1=心跳超时 2=低压兜底 */
static int t_no_kick;            /* 只读：距上次心跳的命中数 */

module_param(kick, int, 0644);
MODULE_PARM_DESC(kick, "watchdog 心跳：每 tick 写一个不同的值");
module_param(kick_timeout, int, 0644);
module_param(lowv_margin, int, 0644);
module_param(lowv_hits, int, 0644);
module_param(failsafe_on, int, 0644);
module_param(t_failsafe, int, 0444);
module_param(t_no_kick, int, 0444);

static int last_kick_val;
static int lowv_cnt;
static int failsafe_logged;

/*
 * ============ 方案C：全程均匀电量显示（全部在内核里算）============
 * 搬进内核的理由：用户态 daemon 再怎么监护也有"死掉到被拉起"的空窗，
 * 而这段逻辑是承重的 —— 它一旦失守，fake_soc 卡在非零值会同时导致
 * ①框架看不到 0% 不关机 ②内核跳过发关机 uevent，手机一路放到硬件保护。
 * 内核里跑则与 kprobe 同生命周期，insmod 即生效、rmmod 才失效，没有空窗。
 *
 *   display% = 100 × (rm + QBOT×f(Vocv)) / (FCC + QBOT)
 *   rm       = raw_soc/10000 × FCC        （raw_soc 来自 [0xa24]，厘百分比）
 *   Vocv     = mean_volt + |I|×R_int
 *   f        = 1→0 线性，定义域 [vcut, VTERM]（标定后可换查表）
 *
 * 全程 32 位整数即可：raw(≤10000) × fcc(≤20000) = 2e8 < 2^31，无需 64 位除法。
 *
 * ★★2026-08-05 重大简化：qbot_mah 默认改为 0
 *   我们写 [0xa90] vcutoff_fw 之后，驱动自己把它沿
 *     platform_fg_ops_set_cutoff_voltage → fg_set_one_cutoff_voltage → DF 块 0x4F
 *   传播进了电量计，`fg_master/cutoff_vol` 已经从 3030 变成 2700。
 *   于是 raw_soc【天然就覆盖到 2700mV】，底部容量已经进了电量计自己的账本
 *   （FCC 因此从 6208 一次学习跳到 6837）。此时再补 QBOT = 把底部算两遍。
 *
 *   qbot=0 时公式退化成 100×rm/FCC，FCC 在分子分母里【完全约掉】：
 *       display = raw_soc / 100
 *   附带好处：不再依赖 fcc_mah 这个静态参数，天然免疫 FCC 学习漂移
 *   （实测一天内 6837→6801，±40mAh 级噪声，写死静态值已被证伪）。
 *
 *   若哪天把 vcut_fw 改回 3030（电量计 Term Voltage 也会跟着回去），
 *   再把 qbot_mah 设回实测的 156，底部补偿那套逻辑原样还在。
 */
static int soc_ctl   = 1;      /* 1=由内核接管电量显示 */
/*
 * ★fcc_mah 不再是写死的魔法数字：0 = 每次命中直接从结构体 [0xa20] 实时读。
 *   FCC 是电量计学出来的活值，项目期间实测漂过 6445→6208→6837→6801，
 *   写死静态值已被证伪。只有实时读实在拿不到时才回退到这个参数。
 *   >0 = 强制用该值（调试用）。
 */
static int fcc_mah;            /* 0 = 实时读 [0xa20] */
static int qbot_mah;           /* ★默认 0：电量计已按新截止计量，不能再补一次底部 */
static int vterm     = 3030;   /* 电量计 Term Voltage；★仅 qbot>0 的底部映射用得到 */
static int rint_mohm = 28;     /* 内阻；★同样仅 qbot>0 时才参与运算 */

static int t_fcc = -1;         /* 只读遥测：实时读到的 FCC(mAh) */
static int t_rm  = -1;         /* 只读遥测：实时读到的 rm(mAh) */
module_param(t_fcc, int, 0444);
module_param(t_rm, int, 0444);

/*
 * ★交接防抖（2026-08-04 深放实测暴露的缺陷）
 *
 * 交接 = mean_volt 接近截止时把 fake_soc 写回 -1，让真实 0% 与关机 uevent 一起放出去。
 * 原实现「一次成立立刻交接」有两个问题：
 *   1) 关得早：实测负载尖峰达 5.04A，×28mΩ ≈ 140mV 压降，
 *      一次尖峰就能把 mean 从 2880 打到 2740，而开路电压其实还有 ~2820mV。
 *   2) 行为不确定（更严重）：实测 20:54:28 触发后手机没死 ——
 *      fake_soc=-1 只持续一瞬，框架恰好没在那 200ms 窗口采样，之后又跑了 45 秒。
 *      也就是说关不关机取决于竞态。
 *
 * 修法：要求连续 handoff_hits 次成立才交接。kprobe 约 5Hz，故 5 ≈ 1 秒。
 * 注：mean_volt 本身已是近 5 样本均值（约 1 秒窗口），叠加后等效要求低压持续约 2 秒。
 * 实测那次尖峰跨了约 4 秒日志时间，若仍误触发可把本值调到 10（≈2 秒）。
 */
static int handoff_hits = 5;
static int t_handoff_cnt;      /* 只读遥测 */
static int handoff_cnt;

module_param(handoff_hits, int, 0644);
MODULE_PARM_DESC(handoff_hits, "交接需连续成立的次数（kprobe 约 5Hz，5≈1秒）");
module_param(t_handoff_cnt, int, 0444);
static int t_raw_soc = -1;     /* 只读遥测 */
static int t_disp    = -1;     /* 只读：我们最后写出去的百分比 */

module_param(soc_ctl, int, 0644);
MODULE_PARM_DESC(soc_ctl, "1=内核接管电量显示（方案C）");
module_param(fcc_mah, int, 0644);
module_param(qbot_mah, int, 0644);
module_param(vterm, int, 0644);
module_param(rint_mohm, int, 0644);
module_param(t_raw_soc, int, 0444);
module_param(t_disp, int, 0444);

static int last_disp10 = -1;   /* 上次显示值×10，用于单调钳制 */

/*
 * ============ 伪装循环次数 ============
 * 目的：PowerKeeper 按电池老化（循环次数）下调满充电压，伪装低循环可恢复满充电压
 *       —— 这是【充电侧】的杠杆，与本模块其余部分（放电侧）互补。
 *
 * ★只改 strategy 层 RAM 里的 [0xa04]，【绝不碰电量计 DF】。
 *   bq27z561 里确有 fg_set_cyclecount 写入路径，但按 fg_set_one_cutoff_voltage
 *   的命名规律那是直接写 DF 块的，属安全红线，不走。
 *   RAM 伪装 rmmod 即还原、重启即消失，电量计固件里的真实循环数原封不动。
 *
 * 附带效果：[0xa04] 正是 update_cc_para 选 dod_para 档时读的值，
 *           伪装低循环会让驱动倾向选更低截止的档位（与本模块目标同向）。
 */
static int fake_cycle = -1;    /* -1 = 不伪装；≥0 = 强制为该值 */
static int t_cycle    = -1;    /* 只读遥测：当前 [0xa04] 实际值 */
module_param(fake_cycle, int, 0644);
MODULE_PARM_DESC(fake_cycle, "伪装循环次数写入 strategy RAM [0xa04]；-1=不伪装。不碰电量计 DF");
module_param(t_cycle, int, 0444);

static s32 saved_cycle;

/*
 * ============ 伪装 fg 层循环次数（★这条才影响满充电压）============
 *
 * ★2026-08-05 实测：上面那个 [0xa04] 打错了层。
 *   充电策略读的不是 strategy 结构体，而是 fg 驱动自己的值 ——
 *   `fake_cycle=1` 时 `t_cycle=1`，但 `fg_master/cyclecount` 仍 370、`term_volt` 仍 4445。
 *
 * 老化降压机制（设备树实证）：
 *   smart_charge/enable_fv_dec_by_cc        = 1
 *   mca_strategy_buckchg_class/drop_fv_cycle = 300
 * A/B 实测阶梯（同温同档，只改循环数）：
 *   cyc 1/100 → 4465 | 299/300 → 4455 | 301/400/600 → 4445 | 1000 → 4425
 * 本机真实 370 → 4445，可恢复 +20mV。
 *
 * ★挂点：`strategy_class_fg_ops_get_cyclecount`（mca_strategy_fg_class 导出符号）
 *   反汇编（96 字节）：
 *       mov x1, x0            ← 调用者的 x0 原样转成第二个参数
 *       ldr x0, [x9]          ← 全局 ctx 作第一个参数
 *       blr x8                ← ops->get_cyclecount(ctx, out)
 *   即签名为 `int get_cyclecount(s32 *out)`，值经出参返回，返回码 0 = 成功。
 *   runtime 用 tracefs kretprobe `out=+0($arg1):s32` 独立验证过（读到 out=1，与当时伪装值一致）。
 *   实测调用者：strategy_buckchg_monitor_workfunc（★算满充电压的那个）、
 *               smart_charge_workfunc、batt_psy_get_prop（battery/cycle_count）。
 *
 * ★为什么用 kretprobe 而不是再找一个结构体偏移：
 *   这条路【零 struct 偏移】—— 只依赖函数签名，而签名有反汇编 + runtime 双重实证。
 *   vendor 模块更新导致偏移挪位的整类风险在这里根本不存在。
 *
 * 安全性：只改一个内核栈/模块数据上的 s32，且只在被 hook 的函数刚刚写完它之后覆盖；
 *   电量计 DF 里的真实循环数（qmax_cyclecount=366）原封不动；
 *   rmmod 即刻恢复真值（不像 vcutoff 那样粘住）。
 *   ⚠️但【投票是粘的】：改完要等一次插拔或温度档跃迁，jeita 才会重新投票。
 */
#define CYC_SYM "strategy_class_fg_ops_get_cyclecount"

static int fake_fg_cycle = -1;  /* -1=不伪装；≥0=让上层读到该值 */
static int t_fg_cycle    = -1;  /* 只读遥测：拦截到的【真实】循环数 */
static int t_fg_cyc_hits;       /* 只读遥测：拦截次数 */
static int fg_cyc_armed;        /* 只读：kretprobe 是否挂上 */

module_param(fake_fg_cycle, int, 0644);
MODULE_PARM_DESC(fake_fg_cycle,
	"伪装 fg 层循环次数（影响满充电压）；-1=不伪装。只改返回值，不碰电量计 DF");
module_param(t_fg_cycle, int, 0444);
module_param(t_fg_cyc_hits, int, 0444);
module_param(fg_cyc_armed, int, 0444);

struct cyc_ctx {
	s32 *out;
};

static int cyc_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct cyc_ctx *c = (struct cyc_ctx *)ri->data;

	c->out = (s32 *)regs->regs[0];
	return 0;
}

static int cyc_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
	struct cyc_ctx *c = (struct cyc_ctx *)ri->data;

	/* 只在被 hook 的函数报成功时才动；失败路径不碰 */
	if (regs_return_value(regs) != 0)
		return 0;
	if (!c->out)
		return 0;
	/* 只写内核地址，挡掉 NULL / 用户态指针 / 明显不合理的值 */
	if ((unsigned long)c->out < 0xffff000000000000UL)
		return 0;

	t_fg_cycle = *c->out;              /* 先留下真值，随时可查 */
	t_fg_cyc_hits++;

	if (fake_fg_cycle < 0 || fake_fg_cycle > 65535)
		return 0;
	*c->out = fake_fg_cycle;
	return 0;
}

static struct kretprobe krp_cyc = {
	.kp.symbol_name  = CYC_SYM,
	.entry_handler   = cyc_entry,
	.handler         = cyc_ret,
	.data_size       = sizeof(struct cyc_ctx),
	.maxactive       = 32,
};

static inline s32 rd32(void *s, unsigned int off)
{
	return *(volatile s32 *)((char *)s + off);
}
static inline void wr32(void *s, unsigned int off, s32 v)
{
	*(volatile s32 *)((char *)s + off) = v;
}

/*
 * 首次命中的合法性校验：如果偏移在这个 build 上对不上，
 * 这些字段几乎不可能同时落在合理区间。校验不过就永久禁写。
 */
static int validate(void *s)
{
	s32 fw = rd32(s, OFF_VCUT_FW);
	s32 dl = rd32(s, OFF_VCUT_DELAY);
	s32 sw = rd32(s, OFF_VCUT_SW);
	s32 mv = rd32(s, OFF_MEAN_VOLT);
	s32 vp = rd32(s, OFF_VPACK);
	s32 soc = rd32(s, OFF_SOC);
	u8  vpen = *(volatile u8 *)((char *)s + OFF_VPACK_LOW_EN);
	/* 容量三兄弟：既做校验，也是新偏移的见证（2026-08-05 实测坐实，单位 µAh）*/
	s32 rmu = rd32(s, OFF_RM_UAH);
	s32 fccu = rd32(s, OFF_FCC_UAH);
	s32 dsgu = rd32(s, OFF_DESIGN_UAH);
	/*
	 * ★2026-08-29 新增：fake_soc 的校验。
	 *   运行时自解析之后，fake_soc 的偏移由【锚点C】（ldr + cmn w?,#1）单独定，
	 *   不参与 delta 推导。而 fake_soc 恰恰是本模块唯一会【主动写】的用户可见字段
	 *   （vcutoff 三值也是写，但它们有区间约束能兜住）。
	 *   锚点万一失效 -> 偏移错 -> 写到 struct 里别的位置，后果严重。
	 *   所以这里必须独立复核：该偏移上的值只能是
	 *     -1（原生未接管）/ 0..100（我们或驱动写的百分比）
	 */
	s32 fsoc = rd32(s, OFF_FAKE_SOC);
	/*
	 * ★2026-10-21 补：raw_soc/current/temp/cycle 原先完全不在校验里。
	 *   raw_soc 直接决定显示、current 决定 IR 补偿与单调方向，错了要拒绝 arm；
	 *   raw_soc 还能和 rm/fcc 交叉验证（它就是 rm/fcc×10000），这是硬约束。
	 *   cycle 只影响伪装循环这一项，区间不对只关掉该功能（t_cyc_ok=0），不拖累主功能。
	 */
	s32 raw = rd32(s, OFF_RAW_SOC);
	s32 cur = rd32(s, OFF_CURRENT);
	s32 tmp = rd32(s, OFF_TEMP);
	s32 cyc = rd32(s, OFF_CYCLE);
	s64 raw_calc;

	if (fw < 2400 || fw > 3600) goto bad;
	if (dl < 2400 || dl > 3600) goto bad;
	if (sw < 2300 || sw > 3600) goto bad;
	if (fw < dl || dl < sw)     goto bad;   /* 应满足 fw >= delay >= sw */
	if (mv < 2000 || mv > 5000) goto bad;
	if (vp < 2000 || vp > 5000) goto bad;
	if (soc < 0   || soc > 100) goto bad;
	if (vpen > 1)               goto bad;
	/* 区间给得宽：只要求"这确实是一组 µAh 容量值"，不绑死本机具体数字 */
	if (dsgu < 1000000 || dsgu > 30000000) goto bad;
	if (fccu < 1000000 || fccu > dsgu)     goto bad;
	if (rmu  < 0       || rmu  > fccu + 500000) goto bad;
	if (fsoc < -1 || fsoc > 100) goto bad;  /* ★见上方说明 */
	if (raw < 0 || raw > 10000) goto bad;
	raw_calc = (s64)rmu * 10000 / fccu;     /* fccu 已保证 ≥ 1000000 */
	if (raw_calc > 10000) raw_calc = 10000; /* 满充时 rm 可略超 fcc，raw 封顶 */
	if (raw - raw_calc > 500 || raw_calc - raw > 500) goto bad;  /* 5% 容差 */
	if (cur < -20000000 || cur > 20000000) goto bad;   /* ±20A，单位 µA */
	if (tmp < -400 || tmp > 900) goto bad;             /* -40~90°C，单位 0.1°C */
	if (cyc < 0 || cyc > 5000) {
		t_cyc_ok = 0;
		pr_warn("vcut_lower: cycle@0x%x=%d 不在 0..5000，关闭伪装循环\n", OFF_CYCLE, cyc);
	}

	pr_info("vcut_lower: 校验通过 fw=%d delay=%d sw=%d mean=%dmV vpack=%dmV soc=%d vpack_gate=%u "
		"rm=%dmAh fcc=%dmAh design=%dmAh fake_soc=%d (偏移源=%s delta=%+d)\n",
		fw, dl, sw, mv, vp, soc, vpen, rmu / 1000, fccu / 1000, dsgu / 1000,
		fsoc, t_off_src == 1 ? "运行时自解析" : "基线+指纹校验", t_delta);
	return 1;
bad:
	pr_err("vcut_lower: ★校验失败，偏移可能不对，永久禁写！"
	       " fw=%d delay=%d sw=%d mean=%d vpack=%d soc=%d vpen=%u rm=%d fcc=%d design=%d fake_soc=%d"
	       " raw_soc=%d cur=%d temp=%d cycle=%d\n",
	       fw, dl, sw, mv, vp, soc, vpen, rmu, fccu, dsgu, fsoc, raw, cur, tmp, cyc);
	return 0;
}

static int clamp_param(int v)
{
	if (v < VCUT_FLOOR) return VCUT_FLOOR;
	if (v > VCUT_CEIL)  return VCUT_CEIL;
	return v;
}

static int vcut_pre(struct kprobe *p, struct pt_regs *regs)
{
	void *s = (void *)regs->regs[0];
	int fw, dl, sw;

	if (!s)
		return 0;

	if (t_hits == 0) {
		armed = validate(s);
		if (armed) {
			saved_struct   = s;
			saved_vpack_en = *(volatile u8 *)((char *)s + OFF_VPACK_LOW_EN);
			saved_fw       = rd32(s, OFF_VCUT_FW);
			saved_delay    = rd32(s, OFF_VCUT_DELAY);
			saved_sw       = rd32(s, OFF_VCUT_SW);
			saved_cycle    = rd32(s, OFF_CYCLE);
			saved_valid    = 1;
			pr_info("vcut_lower: 已保存原值 fw/delay/sw = %d/%d/%d vpack_gate=%u（rmmod 时写回）\n",
				saved_fw, saved_delay, saved_sw, saved_vpack_en);
		}
	}
	t_hits++;

	/* 遥测：这些值驱动本就 5Hz 更新好了，导出给 watchdog 用，零额外 I2C */
	t_mean_volt   = rd32(s, OFF_MEAN_VOLT);
	t_current     = rd32(s, OFF_CURRENT);
	t_vpack       = rd32(s, OFF_VPACK);
	t_soc         = rd32(s, OFF_SOC);
	t_temp        = rd32(s, OFF_TEMP);
	t_fake_soc    = rd32(s, OFF_FAKE_SOC);
	t_vbatt_empty = *(volatile u8 *)((char *)s + OFF_VBATT_EMPTY);

	if (!armed)
		return 0;

	/*
	 * ---------------- fail-safe ----------------
	 * ★放在 enable 判断【之前】：enable=0 时我们不改 vcutoff，但 watchdog
	 *   仍可能在写 fake_soc，那种组合同样会堵住关机通道，兜底必须照常生效。
	 * ★参照阈值取【结构体里当前实际的 delay】而不是我们的参数 —— 那才是
	 *   内核真正会用来判定关机的值（enable=0 时它是原厂值）。
	 */
	if (failsafe_on) {
		int ref_dl = rd32(s, OFF_VCUT_DELAY);
		int why = 0;

		/*
		 * 1) 心跳：仅在【用户态接管显示】(soc_ctl=0) 时才需要 ——
		 *    soc_ctl=1 时 fake_soc 由本模块自己管，与 kprobe 同生命周期，
		 *    不存在"管理者死掉"的情形。
		 */
		if (kick != last_kick_val) {
			last_kick_val = kick;
			t_no_kick = 0;
		} else if (t_no_kick < 1000000) {
			t_no_kick++;
		}
		if (!soc_ctl && t_no_kick > kick_timeout)
			why = 1;

		/* 2) 低压兜底：需连续成立，防瞬时压降误触发 */
		if (t_mean_volt > 0 && t_mean_volt <= ref_dl + lowv_margin) {
			if (lowv_cnt < 1000000)
				lowv_cnt++;
		} else {
			lowv_cnt = 0;
		}
		if (lowv_cnt > lowv_hits)
			why = why ? why : 2;

		if (why && rd32(s, OFF_FAKE_SOC) != -1) {
			wr32(s, OFF_FAKE_SOC, -1);
			t_failsafe = why;
			if (!failsafe_logged) {
				failsafe_logged = 1;
				pr_err("vcut_lower: ★fail-safe 触发(%s)，已强制 fake_soc=-1 交还关机通道 "
				       "(mean=%dmV delay=%d no_kick=%d lowv_cnt=%d)\n",
				       why == 1 ? "watchdog 心跳超时" : "低压兜底",
				       t_mean_volt, ref_dl, t_no_kick, lowv_cnt);
			}
		} else if (!why) {
			t_failsafe = 0;
			failsafe_logged = 0;
		}
	}

	if (!enable)
		return 0;

	/* 参数钳到安全区间，并保持 fw >= delay >= sw */
	fw = clamp_param(vcut_fw);
	dl = clamp_param(vcut_delay);
	sw = clamp_param(vcut_sw);
	if (dl > fw) dl = fw;
	if (sw > dl) sw = dl;

	t_wrote = 1;
	wr32(s, OFF_VCUT_FW,    fw);
	wr32(s, OFF_VCUT_DELAY, dl);
	wr32(s, OFF_VCUT_SW,    sw);

	if (kill_vpack_gate && t_gate_ok)
		*(volatile u8 *)((char *)s + OFF_VPACK_LOW_EN) = 0;

	/* ============ 伪装循环次数（仅 strategy RAM，不碰电量计）============ */
	/* ★cycle 没有锚点：偏移没有硬证据（t_cyc_ok=0）时绝不写，否则就是往无关字段写值 */
	t_cycle = rd32(s, OFF_CYCLE);
	if (fake_cycle >= 0 && t_cyc_ok && t_cycle != fake_cycle) {
		wr32(s, OFF_CYCLE, fake_cycle);
		t_cycle = fake_cycle;
	}

	/* ============ 方案C：内核接管电量显示 ============ */
	if (soc_ctl) {
		int raw   = rd32(s, OFF_RAW_SOC);
		int qbot  = qbot_mah;
		int fcc;
		int tot10, rm10, qbot10, disp10, disp;

		t_raw_soc = raw;

		/* ★实时读，不用魔法数字：结构体里的 µAh → mAh */
		t_fcc = rd32(s, OFF_FCC_UAH) / 1000;
		t_rm  = rd32(s, OFF_RM_UAH)  / 1000;

		/*
		 * fcc_mah=0 → 用实时值；实时值不合理（偏移失效/驱动尚未填好）才回退。
		 * 回退目标也不是魔法数字，而是同一结构体里的设计容量。
		 */
		fcc = fcc_mah > 0 ? fcc_mah : t_fcc;
		if (fcc < 500 || fcc > 20000)
			fcc = rd32(s, OFF_DESIGN_UAH) / 1000;
		if (fcc < 500 || fcc > 20000)
			fcc = 0;              /* 彻底读不到：下面走 qbot==0 直通，不依赖 FCC */

		if (qbot < 0 || qbot > 2000) qbot = 0;
		if (fcc == 0)                qbot = 0;   /* 没有可信 FCC 就不做底部补偿 */
		if (raw < 0)     raw = 0;
		if (raw > 10000) raw = 10000;

		/*
		 * ★交还关机通道：mean_volt 已到截止附近就写回 -1，
		 *   让真实 0% 与关机 uevent 一起放出去（check_shutdown_delay 那条判断要求 fake_soc==-1）
		 */
		if (t_mean_volt > 0 && t_mean_volt <= dl + 50) {
			if (handoff_cnt < 1000000)
				handoff_cnt++;
		} else {
			handoff_cnt = 0;
		}
		t_handoff_cnt = handoff_cnt;

		if (handoff_cnt >= handoff_hits) {
			if (rd32(s, OFF_FAKE_SOC) != -1) {
				wr32(s, OFF_FAKE_SOC, -1);
				pr_err("vcut_lower: 交接关机通道 mean=%dmV ≤ %d，连续 %d 次\n",
				       t_mean_volt, dl + 50, handoff_cnt);
			}
			t_disp = -1;
			last_disp10 = -1;
			return 0;
		}

		if (qbot == 0) {
			/*
			 * ★电量计已按 2700mV 计量（cutoff_vol 被驱动传播成 2700），
			 *   raw_soc 本身就是全程同一把尺子 —— FCC 在公式里约掉，直通即可。
			 *   这样也不会因为 fcc_mah 写的是陈旧值而算错。
			 */
			disp10 = raw / 10;
		} else {
			rm10 = (raw * fcc) / 1000;              /* 0.1 mAh */

			if (raw > 0) {
				qbot10 = qbot * 10;                 /* 上段：底部段未动用 */
			} else {
				/* 底部段：IR 补偿后的电压映射 */
				int ai   = t_current < 0 ? -t_current : t_current;
				int vocv = t_mean_volt + (ai / 1000) * rint_mohm / 1000;
				int span = vterm - dl;
				int fperm;

				if (span <= 0) span = 1;
				fperm = 1000 * (vocv - dl) / span;
				if (fperm < 0)    fperm = 0;
				if (fperm > 1000) fperm = 1000;
				qbot10 = qbot * fperm / 100;
			}

			tot10  = (fcc + qbot) * 10;
			if (tot10 <= 0) tot10 = 1;
			disp10 = 1000 * (rm10 + qbot10) / tot10;
		}
		if (disp10 > 1000) disp10 = 1000;
		if (disp10 < 0)    disp10 = 0;

		/* 单调钳制：放电中禁升、充电中禁降（★方向用电流符号，正=放电）*/
		if (last_disp10 >= 0) {
			if (t_current > 0) {
				if (disp10 > last_disp10) disp10 = last_disp10;
			} else {
				if (disp10 < last_disp10) disp10 = last_disp10;
			}
		}
		last_disp10 = disp10;

		disp = (disp10 + 5) / 10;
		if (disp > 100) disp = 100;
		/* ★绝不写 0：框架 capacity==0 && !charging 会立刻关机。
		 *   要关机只走上面 handoff 写 -1 那条路。 */
		if (disp < 1) disp = 1;

		t_disp = disp;
		if (rd32(s, OFF_FAKE_SOC) != disp)
			wr32(s, OFF_FAKE_SOC, disp);
	}

	return 0;
}

static struct kprobe kp = {
	.symbol_name = TARGET_SYM,
	.pre_handler = vcut_pre,
};

/*
 * ============ 指令指纹校验 ============
 * ★为什么需要它：本模块的 struct 偏移是针对特定 vendor 模块 build 逆向出来的。
 *   机型名(popsicle)、内核版本、符号 CRC 都可能不变，而 vendor 模块一更新偏移就挪位 ——
 *   这种情况下 CRC 照过、customize.sh 的机型检查照过，却会写错内存。
 *   而 validate() 那种"看数值合不合理"的检查是启发式的，偏移只挪一点可能蒙混过关。
 *
 * ★AArch64 的 LDR/LDRB(立即数) 把偏移直接编码在 imm12 字段里，
 *   所以校验这几条指令 = 直接验证【代码本身在用哪个偏移】，这是硬证据。
 *
 *   ★2026-09-04 为 OS4.0.0.32 的 vendor 模块重新取指纹。
 *     指令位置仍没变（函数体未重排），gate 与 delay 的 imm12 各 +8（对应 struct
 *     那处 +8 位移），mean_volt 那条一字未动：
 *     probe+0x07c: 0x396aa668 = ldrb w8,[x19,#0xaa9]   imm12=0xaa9, scale 1
 *     probe+0x108: 0xb94a9e68 = ldr  w8,[x19,#0xa9c]   imm12=0x2a7 -> 0x2a7*4=0xa9c
 *     probe+0x10c: 0xb949fe69 = ldr  w9,[x19,#0x9fc]   imm12=0x27f -> 0x27f*4=0x9fc（未变）
 *
 *   旧版（OS4.0.0.25 / 6.12.69-android16-6，仅作追溯）：
 *     probe+0x07c: 0x396a8668 = ldrb w8,[x19,#0xaa1]
 *     probe+0x108: 0xb94a9668 = ldr  w8,[x19,#0xa94]
 *     probe+0x10c: 0xb949fe69 = ldr  w9,[x19,#0x9fc]
 *
 *   旧版（OS4.0.0.20 / 6.12.69-android16-6，仅作追溯）：
 *     probe+0x07c: 0x396a4668 = ldrb w8,[x19,#0xa91]
 *     probe+0x108: 0xb94a8668 = ldr  w8,[x19,#0xa84]
 *     probe+0x10c: 0xb949ee69 = ldr  w9,[x19,#0x9ec]
 *
 *   旧版（OS3.0 / 6.12.23-android16-5，仅作追溯）：
 *     probe+0x090: 0x3963a268 = ldrb w8,[x19,#0x8e8]
 *     probe+0x118: 0xb948de68 = ldr  w8,[x19,#0x8dc]
 *     probe+0x11c: 0xb9484669 = ldr  w9,[x19,#0x844]
 *
 * 本机 CONFIG_OPTPROBES/KPROBES_ON_FTRACE 均未开，arm64 kprobe 只替换第一条指令，
 * 故这些偏移(≥4)不会被探针本身改写。
 */
/*
 * ============ ★运行时偏移自解析（2026-08-29）============
 *
 * 目的：系统更新后 vendor 模块换 build、struct 偏移挪位时，模块可以
 *      【自己在手机上】重新解析出偏移并继续工作，不需要回电脑重编。
 *
 * 原理：register_kprobe 之后 kp.addr 就是目标函数的地址，函数体的机器码
 *      里直接编码着 struct 偏移（AArch64 的 ldr 立即数）。我们从这里
 *      扫指令、匹配【语义锚点】，把偏移读回来。
 *
 * ★为什么用语义锚点而不是位置：
 *   位置（如 probe+0x07c）在函数体被重排时立刻失效；而下面的模式是
 *   【驱动源码语义在汇编里的投影】，在已观测的三次 build 变化中全部成立：
 *
 *   锚点A  vcut_delay / mean_volt  —— 源码 `mean_volt <= vcutoff_delay + 50`
 *          ldr  wA, [x19, #IMM_DELAY]
 *          ldr  wB, [x19, #IMM_MEAN]
 *          add  wA, wA, #50            ← 50 是驱动硬编码常量，极稳定
 *          cmp  wB, wA
 *
 *   锚点B  vpack / vpack_low_en  —— 源码 `if (vpack_low_en == 1 && vpack <= 3000)`
 *          ldrb wG, [x19, #IMM_GATE]
 *          cmp  wG, #1
 *          ldr  wZ, [x19, #IMM_VPACK]
 *          cmp  wZ, #3000              ← 0xbb8
 *
 *   锚点C  fake_soc —— 源码 `if (fake_soc == -1)`
 *          ldr  wR, [x19, #IMM_FAKE]
 *          cmn  wR, #1                 ← cmn = 与 -1 比较
 *
 *   其余字段（current/rm/fcc/raw_soc/cycle/temp/soc/design/vcut_fw/
 *   vcut_sw/vbatt_empty）没有独立锚点，用位移量 delta 从编译期基线推导：
 *          delta = 解析出的 mean_volt - BASE_MEAN_VOLT
 *   历史上这 11 项在两次迁移中全部遵守整体位移，无一例外。
 *
 * ★为什么手写解码而不用反汇编库：内核里没有 capstone，也不能引入。
 *   我们只需要解 6 条指令，编码是公开的 ARM ARM 定义，手写既小又可控。
 *   下面每个掩码都对着真机反汇编验算过（例：0xb94b0668 →
 *   imm12=0x2c1, ×4=0xb04, Rn=19, Rt=8，即 ldr w8,[x19,#0xb04]）。
 *
 * ★fail-closed：任一必需锚点缺失/歧义 -> 解析失败 -> 回退到基线，
 *   此时必须通过指令指纹校验才允许加载。解析结果还会被 validate() 复核
 *   （vcutoff 三值区间、容量三兄弟关系等），错了不会写内存。
 */
#define SCAN_MAX_INSN	256	/* 最多扫 256 条指令 = 1KB，函数体已知约 0x33c */
#define STRUCT_REG	19	/* check_shutdown_delay 里 strategy struct 在 x19 */
#define ANCHOR_MARGIN	50	/* delay + 50 */
#define ANCHOR_VPACK_MV	3000	/* 0xbb8 */
#define ANCHOR_WINDOW	10	/* 加载指令与锚点之间允许的最大距离（条）*/

/* ---- AArch64 指令编码（只取需要的 6 条）---- */
#define M_LDR32		0xffc00000	/* ldr  wRt,[xRn,#imm12*4] */
#define V_LDR32		0xb9400000
#define M_LDRB		0xffc00000	/* ldrb wRt,[xRn,#imm12]   */
#define V_LDRB		0x39400000
#define M_ADD32		0xff800000	/* add  wRd,wRn,#imm12     */
#define V_ADD32		0x11000000
#define M_SUBS32	0xff800000	/* cmp  wRn,#imm12 (Rd=ZR) */
#define V_SUBS32	0x71000000
#define M_ADDS32	0xff800000	/* cmn  wRn,#imm12 (Rd=ZR) */
#define V_ADDS32	0x31000000
#define M_SUBR32	0xff000000	/* cmp  wRn,wRm    (Rd=ZR) */
#define V_SUBR32	0x6b000000
#define M_RET		0xfffffc1f	/* ret */
#define V_RET		0xd65f0000
/* ---- 锚点B 的第二种写法（2026-10-21，小米 15 / 6.6 驱动）用到的 3 条 ----
 *   同一句 `if (vpack_low_en == 1 && vpack <= 3000)`，clang 生成：
 *          ldrb wG, [x19, #IMM_GATE] ; ... ; cbz wG, ...      ← 门控（不是 cmp #1）
 *          ldr  wZ, [x19, #IMM_VPACK]
 *          mov  wK, #3001 ; ccmp wZ, wK, #0, eq               ← vpack < 3001（不是 cmp #3000）
 *   中间还夹着 bl，ldr 与比较相距 15 条，故另给窗口 ANCHOR_WINDOW_B。
 *   只在第一种写法没找到时启用，popsicle 系列走原路径不变。 */
#define M_MOVZ32	0xffe00000	/* mov  wRd,#imm16（hw=0）  */
#define V_MOVZ32	0x52800000
#define M_CCMPR32	0xffe00c10	/* ccmp wRn,wRm,#nzcv,cond  */
#define V_CCMPR32	0x7a400000
#define M_CBZ32		0xfe000000	/* cbz/cbnz wRt,label       */
#define V_CBZ32		0x34000000
#define ANCHOR_WINDOW_B	20
#define GATE_BEFORE_VPACK 40	/* gate 的 ldrb 须在 vpack 的 ldr 之前 40 条以内 */
#define REG_ZR		31

static inline int dec_rd(u32 i) { return (int)(i & 0x1f); }
static inline int dec_rn(u32 i) { return (int)((i >> 5) & 0x1f); }
static inline int dec_rm(u32 i) { return (int)((i >> 16) & 0x1f); }
static inline int dec_imm12(u32 i) { return (int)((i >> 10) & 0xfff); }

/*
 * 解析主函数。成功返回 1（并改写全局 OFF_*），失败返回 0（OFF_* 保持基线）。
 * 只读：全程只读内核代码段，不写任何内存。
 * （遥测变量 t_resolved/t_delta/t_off_src/t_anchors/t_scan_insns 声明在文件前部）
 */
static int resolve_offsets(void *fn)
{
	const u32 *p = (const u32 *)fn;
	int ldr_imm[32];	/* 每个 w 寄存器最近一次 ldr  自 [x19,#imm] 的偏移 */
	int ldr_pos[32];	/* 发生在第几条指令 */
	int ldrb_imm[32];	/* 每个 w 寄存器最近一次 ldrb 自 [x19,#imm] 的偏移 */
	int ldrb_pos[32];
	int movz_imm[32];	/* 每个 w 寄存器最近一次 mov #imm16 的值（锚点B 第二种写法）*/
	int movz_pos[32];
	int mean = -1, delay = -1, vpack = -1, gate = -1, fsoc = -1;
	int vpack_ldr_pos = -1, gate_alt = -1, gate_alt_pos = -1;
	int delay_reg = -1, pend_cmp_reg = -1;
	int anchors = 0;
	int i, r, n = 0;

	if (!p)
		return 0;

	for (r = 0; r < 32; r++) {
		ldr_imm[r] = -1;  ldr_pos[r] = -9999;
		ldrb_imm[r] = -1; ldrb_pos[r] = -9999;
		movz_imm[r] = -1; movz_pos[r] = -9999;
	}

	for (i = 0; i < SCAN_MAX_INSN; i++) {
		u32 ins = *(const volatile u32 *)(p + i);
		int rd, rn, rm, imm;

		/* 遇 ret 收尾：函数体结束，继续扫就越界了 */
		if ((ins & M_RET) == V_RET)
			break;
		n++;

		/* ---- 记录 [x19,#imm] 的加载，供后面的锚点回溯 ---- */
		if ((ins & M_LDR32) == V_LDR32 && dec_rn(ins) == STRUCT_REG) {
			rd = dec_rd(ins);
			ldr_imm[rd] = dec_imm12(ins) * 4;	/* 32 位，scale=4 */
			ldr_pos[rd] = i;
			continue;
		}
		if ((ins & M_LDRB) == V_LDRB && dec_rn(ins) == STRUCT_REG) {
			rd = dec_rd(ins);
			ldrb_imm[rd] = dec_imm12(ins);		/* 字节，scale=1 */
			ldrb_pos[rd] = i;
			continue;
		}

		/* ---- 锚点A1：add wRd,wRn,#50 —— 加数一定是 vcut_delay ---- */
		if ((ins & M_ADD32) == V_ADD32) {
			rd = dec_rd(ins); rn = dec_rn(ins); imm = dec_imm12(ins);
			if (rd == rn && imm == ANCHOR_MARGIN && rd != REG_ZR &&
			    ldr_imm[rd] >= 0 && i - ldr_pos[rd] <= ANCHOR_WINDOW &&
			    delay < 0) {
				delay = ldr_imm[rd];
				delay_reg = rd;
				pend_cmp_reg = rd;	/* 等下面的 cmp w?,w(rd) */
				anchors |= 1;
			}
			continue;
		}

		/* ---- 锚点B 第二种写法的素材：mov wK,#imm16 ---- */
		if ((ins & M_MOVZ32) == V_MOVZ32 && dec_rd(ins) != REG_ZR) {
			rd = dec_rd(ins);
			movz_imm[rd] = (int)((ins >> 5) & 0xffff);
			movz_pos[rd] = i;
			continue;
		}
		/* ---- 门控第二种写法：ldrb wG 之后 3 条以内 cbz/cbnz wG（候选，等 vpack 定了再认）---- */
		if ((ins & M_CBZ32) == V_CBZ32) {
			rn = dec_rd(ins);	/* Rt 在低 5 位 */
			if (vpack < 0 && ldrb_imm[rn] >= 0 && i - ldrb_pos[rn] <= 3) {
				gate_alt = ldrb_imm[rn];
				gate_alt_pos = ldrb_pos[rn];
			}
			continue;
		}
		/* ---- vpack 第二种写法：ccmp wZ,wK（wK = 3001 或 3000）---- */
		if ((ins & M_CCMPR32) == V_CCMPR32) {
			rn = dec_rn(ins); rm = dec_rm(ins);
			if (vpack < 0 && (movz_imm[rm] == ANCHOR_VPACK_MV + 1 || movz_imm[rm] == ANCHOR_VPACK_MV) &&
			    i - movz_pos[rm] <= 4 &&
			    ldr_imm[rn] >= 0 && i - ldr_pos[rn] <= ANCHOR_WINDOW_B) {
				vpack = ldr_imm[rn];
				vpack_ldr_pos = ldr_pos[rn];
				anchors |= 4;
			}
			continue;
		}

		/* ---- 锚点A2：cmp wRn,wRm 且 Rm 是刚加过 50 的寄存器 ----
		 *      Rn 那一侧就是 mean_volt（源码：cmp mean, delay+50）*/
		if ((ins & M_SUBR32) == V_SUBR32 && dec_rd(ins) == REG_ZR) {
			rn = dec_rn(ins); rm = dec_rm(ins);
			if (pend_cmp_reg >= 0 && rm == pend_cmp_reg &&
			    ldr_imm[rn] >= 0 && i - ldr_pos[rn] <= ANCHOR_WINDOW &&
			    mean < 0) {
				mean = ldr_imm[rn];
				anchors |= 2;
				pend_cmp_reg = -1;
			} else if (vpack < 0 &&
				   (movz_imm[rm] == ANCHOR_VPACK_MV + 1 || movz_imm[rm] == ANCHOR_VPACK_MV) &&
				   i - movz_pos[rm] <= 4 &&
				   ldr_imm[rn] >= 0 && i - ldr_pos[rn] <= ANCHOR_WINDOW_B) {
				/* vpack 第二种写法的另一形态：cmp wZ,wK */
				vpack = ldr_imm[rn];
				vpack_ldr_pos = ldr_pos[rn];
				anchors |= 4;
			}
			continue;
		}

		if ((ins & M_SUBS32) == V_SUBS32 && dec_rd(ins) == REG_ZR) {
			rn = dec_rn(ins); imm = dec_imm12(ins);

			/* 锚点B1：cmp wRn,#3000 —— 就是那道 vpack 硬闸 */
			if (imm == ANCHOR_VPACK_MV && ldr_imm[rn] >= 0 &&
			    i - ldr_pos[rn] <= ANCHOR_WINDOW && vpack < 0) {
				vpack = ldr_imm[rn];
				vpack_ldr_pos = ldr_pos[rn];
				anchors |= 4;
			}
			/* 锚点B2：cmp wRn,#1 且刚 ldrb 过 —— 门控布尔
			 * ★注意：这正是"简单位移会写错"的那个字段。历次迁移里
			 *   位移结果总是落在前一个高频标志上（OS4.0 的 0xa90、
			 *   OS4.0.0.25 的 0xaa0 都是 8 处引用），必须用锚点定。*/
			if (imm == 1 && ldrb_imm[rn] >= 0 &&
			    i - ldrb_pos[rn] == 1 && gate < 0) {
				gate = ldrb_imm[rn];
				anchors |= 8;
			}
			continue;
		}

		/* ---- 锚点C：cmn wRn,#1  (= cmp wRn,#-1) —— fake_soc ---- */
		if ((ins & M_ADDS32) == V_ADDS32 && dec_rd(ins) == REG_ZR) {
			rn = dec_rn(ins); imm = dec_imm12(ins);
			if (imm == 1 && ldr_imm[rn] >= 0 &&
			    i - ldr_pos[rn] <= ANCHOR_WINDOW && fsoc < 0) {
				fsoc = ldr_imm[rn];
				anchors |= 16;
			}
			continue;
		}
	}

	/* 门控第二种写法：只在第一种（ldrb + cmp #1）没命中、且候选紧挨在 vpack 加载之前时采用 */
	if (gate < 0 && gate_alt >= 0 && vpack_ldr_pos >= 0 &&
	    vpack_ldr_pos > gate_alt_pos && vpack_ldr_pos - gate_alt_pos <= GATE_BEFORE_VPACK) {
		gate = gate_alt;
		anchors |= 8;
	}

	t_scan_insns = n;
	t_anchors = anchors;

	/* mean_volt 是推 delta 的基准，缺了就整个解析失败 */
	if (mean < 0) {
		pr_err("vcut_lower: ★偏移自解析失败：未定位到 mean_volt"
		       "（锚点A，add wR,wR,#50 模式），扫了 %d 条指令，anchors=0x%x\n",
		       n, anchors);
		return 0;
	}

	/* ---- 用 delta 推导其余字段 ---- */
	{
		int delta = mean - BASE_MEAN_VOLT;

		t_delta = delta;

		OFF_CURRENT      = BASE_CURRENT      + delta;
		OFF_MEAN_VOLT    = BASE_MEAN_VOLT    + delta;
		OFF_RAW_SOC      = BASE_RAW_SOC      + delta;
		OFF_RM_UAH       = BASE_RM_UAH       + delta;
		OFF_FCC_UAH      = BASE_FCC_UAH      + delta;
		OFF_DESIGN_UAH   = BASE_DESIGN_UAH   + delta;
		OFF_CYCLE        = BASE_CYCLE        + delta;
		OFF_TEMP         = BASE_TEMP         + delta;
		OFF_SOC          = BASE_SOC          + delta;
		OFF_VCUT_FW      = BASE_VCUT_FW      + delta;
		OFF_VCUT_DELAY   = BASE_VCUT_DELAY   + delta;
		OFF_VCUT_SW      = BASE_VCUT_SW      + delta;
		OFF_VBATT_EMPTY  = BASE_VBATT_EMPTY  + delta;
		OFF_VPACK_LOW_EN = BASE_VPACK_LOW_EN + delta;
		OFF_FAKE_SOC     = BASE_FAKE_SOC     + delta;
		OFF_VPACK        = BASE_VPACK        + delta;

		/* ---- 锚点直接定下来的 5 项，以锚点为准（可覆盖 delta 推导）---- */
		OFF_MEAN_VOLT = mean;
		if (delay >= 0) OFF_VCUT_DELAY   = delay;
		if (vpack >= 0) OFF_VPACK        = vpack;
		if (gate  >= 0) OFF_VPACK_LOW_EN = gate;
		if (fsoc  >= 0) OFF_FAKE_SOC     = fsoc;

		/* ---- ★2026-09-04 加固：vcut 三兄弟 + vbatt_empty 紧贴 delay 推导 ----
		 *   教训来自 OS4.0.0.32 的【非均匀位移】：mean_volt 不动、delay/gate/
		 *   fake_soc/vpack 各 +8，中间还夹了一处 +4 —— 全局 delta（由 mean 推）
		 *   算成 0，把 fw/sw/empty 留在了旧偏移，validate 直接挂。
		 *   而 fw/delay/sw/empty 在结构体里是驱动 update_vcutoff_para 就地写的
		 *   连续簇，相对间距【跨 4 次 build 恒定】：
		 *     fw=delay-4  sw=delay+4  vbatt_empty=delay+8
		 *   （OS3.0 0x8d8/0x8dc/0x8e0/0x8e4、0.0.20 0xa80.. 、0.0.25 0xa90.. 、
		 *     0.0.32 0xa98/0xa9c/0xaa0/0xaa4 全部吻合）。
		 *   这 4 个恰恰是本模块会【主动写】的字段，用锚点 delay 直接钉死，
		 *   比吃全局 delta 稳得多——只要 delay 锚点命中就一定对。
		 *   （current/cycle/temp/rm/fcc/raw_soc/soc/design 先走 delta，随后由
		 *    apply_overrides() 用 autooff.sh 坐实的 ovr_* 覆盖；cycle 会被写，另由 t_cyc_ok 门控。
		 *    以下为原注释：它们只读，
		 *    且都在 validate 的核对范围内，错了会被 armed=0 兜住。）*/
		if (delay >= 0) {
			OFF_VCUT_FW     = delay - 4;
			OFF_VCUT_SW     = delay + 4;
			OFF_VBATT_EMPTY = delay + 8;
		}

		/* 锚点与 delta 推导不一致时告警：历史上 gate 就有 ±1 的偏差，
		 * 那是"简单位移会写错高频标志"的已知现象，锚点是对的。*/
		if (gate >= 0 && (BASE_VPACK_LOW_EN + delta) != gate)
			pr_warn("vcut_lower: vpack_low_en 锚点 0x%x != 位移推导 0x%x"
				"（历次迁移都有此 ±1 偏差），已取锚点值\n",
				gate, BASE_VPACK_LOW_EN + delta);
		if (fsoc >= 0 && (BASE_FAKE_SOC + delta) != fsoc)
			pr_warn("vcut_lower: fake_soc 锚点 0x%x != 位移推导 0x%x，已取锚点值\n",
				fsoc, BASE_FAKE_SOC + delta);
		if (vpack >= 0 && (BASE_VPACK + delta) != vpack)
			pr_warn("vcut_lower: vpack 锚点 0x%x != 位移推导 0x%x，已取锚点值\n",
				vpack, BASE_VPACK + delta);

		pr_info("vcut_lower: 偏移自解析成功：delta=%+d(0x%x) "
			"mean=0x%x delay=0x%x vpack=0x%x gate=0x%x fake_soc=0x%x "
			"(扫描 %d 条指令, anchors=0x%x)\n",
			delta, delta, OFF_MEAN_VOLT, OFF_VCUT_DELAY, OFF_VPACK,
			OFF_VPACK_LOW_EN, OFF_FAKE_SOC, n, anchors);
		return 1;
	}
}

struct insn_sig {
	unsigned int off;
	u32 expect;
	const char *desc;
};

static const struct insn_sig sigs[] = {
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 12, 0)
	{ 0x07c, 0x396aa668, "ldrb w8,[x19,#0xaa9]" },	/* 0.0.32: gate 0xaa1→0xaa9 */
	{ 0x108, 0xb94a9e68, "ldr  w8,[x19,#0xa9c]" },	/* 0.0.32: delay 0xa94→0xa9c */
	{ 0x10c, 0xb949fe69, "ldr  w9,[x19,#0x9fc]" },	/* mean_volt 0x9fc 未变 */
#else
	/* 小米 15 dada OS4.0.0.22 的 mca_strategy_fg_comp.ko（诊断包离线反汇编）*/
	{ 0x06c, 0x39701668, "ldrb w8,[x19,#0xc05]" },	/* gate */
	{ 0x0e4, 0xb94bf668, "ldr  w8,[x19,#0xbf4]" },	/* delay */
	{ 0x0e8, 0xb94b6669, "ldr  w9,[x19,#0xb64]" },	/* mean_volt */
#endif
};

static int sig_ok;
static int force;
module_param(sig_ok, int, 0444);
MODULE_PARM_DESC(sig_ok, "只读：指令指纹校验结果");
module_param(force, int, 0444);
MODULE_PARM_DESC(force, "1=指纹不符也强行加载（仅调试用，有写错内存风险）");

static int check_insn_sig(void)
{
	const u32 *base = (const u32 *)kp.addr;
	int i, bad = 0;

	if (!base) {
		pr_err("vcut_lower: kp.addr 为空，无法做指纹校验\n");
		return 0;
	}
	for (i = 0; i < ARRAY_SIZE(sigs); i++) {
		u32 got = *(const volatile u32 *)((const char *)base + sigs[i].off);

		if (got != sigs[i].expect) {
			pr_warn("vcut_lower: 指令指纹不符 +0x%03x 实际=0x%08x 期望=0x%08x (%s)\n",
			       sigs[i].off, got, sigs[i].expect, sigs[i].desc);
			bad++;
		}
	}
	if (bad) {
		pr_warn("vcut_lower: %d/%d 条指纹不符 —— vendor 模块不是编译期基线 build；"
			"自解析成功则以自解析为准，两者都失败才拒绝加载\n",
			bad, (int)ARRAY_SIZE(sigs));
		return 0;
	}
	pr_info("vcut_lower: 指令指纹校验通过（%d 条），偏移 0x9fc/0xa9c/0xaa9 与代码一致\n",
		(int)ARRAY_SIZE(sigs));
	return 1;
}

/*
 * 应用 autooff.sh 坐实的只读字段偏移（见 ovr_* 声明处）。
 * 只做形式检查（4 字节对齐、落在结构体已知区域内）；数值是否合理由 validate() 复核。
 */
static void apply_overrides(void)
{
	static const struct {
		int *ovr, *off, bit;
		const char *name;
	} t[] = {
		{ &ovr_current, &OFF_CURRENT,    0, "current" },
		{ &ovr_cycle,   &OFF_CYCLE,      1, "cycle"   },
		{ &ovr_temp,    &OFF_TEMP,       2, "temp"    },
		{ &ovr_rm,      &OFF_RM_UAH,     3, "rm"      },
		{ &ovr_fcc,     &OFF_FCC_UAH,    4, "fcc"     },
		{ &ovr_raw_soc, &OFF_RAW_SOC,    5, "raw_soc" },
		{ &ovr_soc,     &OFF_SOC,        6, "soc"     },
		{ &ovr_design,  &OFF_DESIGN_UAH, 7, "design"  },
	};
	int i;

	for (i = 0; i < ARRAY_SIZE(t); i++) {
		int v = *t[i].ovr;

		if (v < 0)
			continue;
		if ((v & 3) || v < 0x400 || v > 0x1000) {
			pr_err("vcut_lower: ovr_%s=0x%x 不合法（需 4 字节对齐且在 0x400..0x1000），忽略\n",
			       t[i].name, v);
			continue;
		}
		if (*t[i].off != v)
			pr_info("vcut_lower: ovr_%s: 0x%x -> 0x%x（sysfs 坐实）\n",
				t[i].name, *t[i].off, v);
		*t[i].off = v;
		t_ovr |= 1 << t[i].bit;
	}
}

static int __init vcut_init(void)
{
	int ret = register_kprobe(&kp);

	if (ret < 0) {
		pr_err("vcut_lower: register_kprobe(%s) 失败 %d\n", TARGET_SYM, ret);
		return ret;
	}

	/*
	 * ★两级校验（2026-08-29 改为"运行时解析优先"）：
	 *
	 *   1) 运行时解析（resolve_offsets）：从目标函数机器码里重新读回偏移。
	 *      成功 -> 系统更新换了 vendor build 也能自动适配，不需要回电脑重编。
	 *   2) 指令指纹校验（check_insn_sig）：验证基线偏移与代码一致。
	 *      这是【硬证据】—— 直接比对代码里编码的立即数，不是启发式。
	 *
	 *   决策表：
	 *     解析成功 + 指纹通过 -> 用解析值（应与基线相同，delta=0）
	 *     解析成功 + 指纹不符 -> ★自愈成功：新 build，用解析值
	 *     解析失败 + 指纹通过 -> 用基线（指纹已证明基线正确）
	 *     解析失败 + 指纹不符 -> 拒绝加载（没有任何一种证据支持偏移正确）
	 *
	 *   ★最后一行是安全底线：偏移写错意味着写错内核内存，后果远比加载失败严重。
	 */
	t_resolved = resolve_offsets((void *)kp.addr);
	sig_ok = check_insn_sig();

	if (t_resolved)
		t_off_src = 1;
	else if (sig_ok)
		t_off_src = 2;

	if (!t_resolved && !sig_ok && !force) {
		unregister_kprobe(&kp);
		pr_err("vcut_lower: 已放弃加载 —— 偏移自解析失败(%d)且指令指纹不符，"
		       "没有任何证据支持偏移正确（如确知在做什么，可用 force=1 覆盖）\n",
		       t_resolved);
		return -ENODEV;
	}
	if (!sig_ok && !t_resolved)
		pr_err("vcut_lower: ★force=1，指纹不符且自解析失败仍继续 —— 风险自负\n");

	apply_overrides();
	/* cycle 是唯一会被写的无锚点字段：要么 sysfs 坐实，要么指纹证明仍是基线 build */
	t_cyc_ok = (t_ovr & 2) || sig_ok;
	t_gate_ok = (t_anchors & 8) || sig_ok;
	if (kill_vpack_gate && !t_gate_ok)
		pr_warn("vcut_lower: vpack_low_en 偏移 0x%x 无硬证据（gate 锚点未命中且指纹不符），"
			"不摘 3000mV 硬闸（关机点不会低于 3000mV）\n", OFF_VPACK_LOW_EN);
	if (fake_cycle >= 0 && !t_cyc_ok)
		pr_warn("vcut_lower: cycle 偏移 0x%x 无硬证据（未坐实且指纹不符），本次不写 fake_cycle\n",
			OFF_CYCLE);

	/*
	 * ★fg 循环数 kretprobe：失败【不】中止加载 ——
	 *   它只是充电侧的加成（+20mV 满充电压），而本模块承重的是放电侧那套。
	 *   挂不上就退化成"没有这项功能"，绝不能因此让关机电压闸也失效。
	 */
	ret = register_kretprobe(&krp_cyc);
	if (ret < 0) {
		fg_cyc_armed = 0;
		pr_err("vcut_lower: register_kretprobe(%s) 失败 %d —— 伪装 fg 循环数功能不可用"
		       "（不影响关机电压与电量显示）\n", CYC_SYM, ret);
	} else {
		fg_cyc_armed = 1;
		pr_info("vcut_lower: 已挂载 %s（kretprobe），fake_fg_cycle=%d\n",
			CYC_SYM, fake_fg_cycle);
	}

	pr_info("vcut_lower: 已挂载 %s，目标 fw/delay/sw = %d/%d/%d，enable=%d kill_vpack_gate=%d\n",
		TARGET_SYM, vcut_fw, vcut_delay, vcut_sw, enable, kill_vpack_gate);
	return 0;
}

static void __exit vcut_exit(void)
{
	unregister_kprobe(&kp);

	/*
	 * ★循环数不需要"写回"：我们从没改过任何存储，只是在返回路径上覆盖返回值。
	 *   unregister 之后上层立刻读到真值 —— 这点和粘住的 vcutoff 完全不同。
	 *   ⚠️但已经投出去的 term_volt 票是粘的，要等下一次插拔/温度档跃迁才回落。
	 */
	if (fg_cyc_armed) {
		unregister_kretprobe(&krp_cyc);
		fg_cyc_armed = 0;
		pr_info("vcut_lower: 已摘除 %s（拦截 %d 次，最后见到的真实循环数 = %d）\n",
			CYC_SYM, t_fg_cyc_hits, t_fg_cycle);
	}

	/*
	 * ★必须主动写回：vcutoff 三值不会被驱动自动重算回来（见文件头注释）。
	 * unregister_kprobe 已返回，此处不会与 pre-handler 竞争。
	 */
	if (saved_valid && saved_struct) {
		/* ★把电量显示交还内核：否则 fake_soc 会卡在我们最后写的值上，
		 *   模块都不在了却仍堵着关机 uevent —— 和 vcutoff 粘住是同一类问题 */
		if (rd32(saved_struct, OFF_FAKE_SOC) != -1)
			wr32(saved_struct, OFF_FAKE_SOC, -1);

		/* ★2026-10-21：从没写过（观测模式）就什么都不写回 —— 写回的"原值"
		 *   是首次命中时的快照，期间驱动若自己更新过，写回反而是改写 */
		if (!t_wrote) {
			pr_info("vcut_lower: 已卸载，命中 %d 次（观测模式，未改写任何值）\n", t_hits);
			return;
		}
		if (t_gate_ok)	/* 与写入同一门控：没写过就不写回 */
			*(volatile u8 *)((char *)saved_struct + OFF_VPACK_LOW_EN) = saved_vpack_en;
		if (restore_on_exit) {
			wr32(saved_struct, OFF_VCUT_FW,    saved_fw);
			wr32(saved_struct, OFF_VCUT_DELAY, saved_delay);
			wr32(saved_struct, OFF_VCUT_SW,    saved_sw);
			if (fake_cycle >= 0 && t_cyc_ok)
				wr32(saved_struct, OFF_CYCLE, saved_cycle);
			pr_info("vcut_lower: 已卸载，命中 %d 次；已写回 fw/delay/sw = %d/%d/%d，cycle = %d，vpack_gate = %u\n",
				t_hits, saved_fw, saved_delay, saved_sw, saved_cycle, saved_vpack_en);
			return;
		}
	}
	if (!saved_valid)
		pr_info("vcut_lower: 已卸载，命中 %d 次（从未通过校验，未改写任何值）\n", t_hits);
	else
		pr_info("vcut_lower: 已卸载，命中 %d 次，vpack_gate 还原为 %u（未写回 vcutoff）\n",
			t_hits, saved_vpack_en);
}

module_init(vcut_init);
module_exit(vcut_exit);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Lower software shutdown voltage thresholds (popsicle)");
