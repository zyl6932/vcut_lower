# vcut_lower — 源码包

小米 17 Pro Max（popsicle）的 kprobe 内核模块：把软件关机电压降到可调值，
并在内核侧接管电量显示，使百分比全程按同一 mAh 刻度均匀下降。

**⚠️ 当前适配 popsicle + `6.12.69-android16-6-*`（HyperOS OS4.0.0.25）。**
struct 偏移是针对该 build 逆向得到的，其他机型/内核几乎必然不同。

### ★系统更新后通常【不需要】回电脑重编（2026-08-29 起）

模块内置**运行时偏移自解析**：加载时从目标函数的机器码里用语义锚点重新读出
struct 偏移，自己完成适配。vendor 模块换 build 导致 struct 挪位时，模块在
手机上自动自愈。

```
cat /sys/module/vcut_lower/parameters/t_resolved   # 1 = 自解析成功
cat /sys/module/vcut_lower/parameters/t_delta      # 相对编译期基线的位移量
cat /sys/module/vcut_lower/parameters/t_anchors    # 0x1f = 5 个锚点全命中
```

只有在**符号 CRC 变了**（GKI 内核换了）或**解析失败且指纹不符**时才需要回电脑重编，
详见下面「系统更新后怎么办」。

---

### OS4.0.0.20 → OS4.0.0.25 迁移要点（2026-08-29）

内核版本串**一字未变**（`6.12.69-android16-6-gb1493ec68d4a-abogki515789973-4k`），
只有 vendor 模块 `mca_strategy_fg_comp.ko` 换了 build —— 这正是"CRC 照过、偏移挪位"的场景，
由指令指纹校验兜住了（实测 `insmod rc=19` = ENODEV，干净失败，没写错内存）。

| 项 | OS4.0.0.20 | OS4.0.0.25 |
|---|---|---|
| 内核版本串 | `gb1493ec68d4a-abogki515789973` | **不变** |
| strategy struct 偏移 | — | **整体再 +0x10**（本次无例外，16 项全部遵守）|
| vpack 闸门控字节 | `0xa91` | **`0xaa1`**（★不是 `0xaa0`，那是 8 处引用的高频标志）|
| 符号 CRC（7 个） | — | **全部未变** |
| `struct module` 布局 | init@0x188 exit@0x5f8 | **不变** |
| kCFI 类型哈希 | 0xc073bc77 | **不变** |
| 三条指纹指令位置 | +0x07c/0x108/0x10c | **位置不变**，只有 imm12 变了 |

**★改的全是"数据"而不是"程序"**：CRC、`struct module` 布局、kCFI 哈希、甚至指纹指令在
函数里的位置都没动，只有 struct 偏移滚了 +0x10。所以既不需要新内核树，也不用重新取指纹位置 ——
编译、patch_crc、四道验收的流程与上一版完全一致。

### OS3.0 → OS4.0 迁移要点（2026-08-14，历史）

| 项 | OS3.0 / 6.12.23-android16-5 | OS4.0 / 6.12.69-android16-6 |
|---|---|---|
| 出厂关机电压 | 3030 mV | **3130 mV** |
| 3000mV vpack 硬闸 | DTS 未启用（标志=0） | **DTS 已启用（标志=1）** → `kill_vpack_gate` 从保险变必需 |
| strategy struct 偏移 | 见下表"旧" | **整体 +0x1A8** |
| vpack 闸门控字节 | `0x8e8` | **`0xa91`**（★不是位移得到的 `0xa90`，那是另一个被 8 处引用的高频标志）|
| `struct module` 布局 | init@0x188 exit@0x5f8 | **不变** |
| kCFI 类型哈希 | 0xc073bc77 | **不变** |
| vermagic 尾串 | `SMP preempt mod_unload modversions aarch64` | **不变** |
| 需改的符号 CRC | module_layout / param_ops_int | **同样只有这 2 个**（值变了）|

**★因此不需要新内核树**：`same_magic()` 在 MODVERSIONS 下会跳过 vermagic 首段，
7 个导入符号里 5 个 CRC 新旧内核完全相同，`struct module` 布局与 kCFI 哈希也没变 ——
用原来的 6.12.23 树重编 + 改 2 个 CRC 即可。省掉一次完整 GKI 构建。

---

## 文件

| 文件 | 说明 |
|---|---|
| `vcut_lower.c` | 内核模块源码（唯一的核心文件）|
| `Makefile` | 外部模块 Makefile |
| `ksumodule/` | KernelSU 模块打包目录（service.sh / customize.sh / action.sh / config）|
| `tools/` | 构建、验收、实测、分析用的脚本 |
| `docs/` | 设计文档与完整工作日志 |

## 构建

需要与设备匹配的 GKI 内核树。要点（详见 `docs/工作日志.md` §37-§57）：

```bash
# 1. 源码分支：与本机 6.12.23-android16-5 精确对应
git fetch --depth 1 origin refs/heads/deprecated/android16-6.12-2025-06

# 2. 用真机 /proc/config.gz 作 .config，不要自作聪明关任何选项
#    ★关掉 DEBUG_INFO 会让 GENDWARFKSYMS 静默回退成 GENKSYMS，CRC 全错
#    ★关掉 DEBUG_INFO_BTF_MODULES 会让 struct module 少 24 字节

# 3. 两个 GKI 专有文件普通 make 没有，需自建：
#    abi_symbollist.raw          <- 从真机 grep ' __ksymtab_' /proc/kallsyms 提取
#    protected_module_names_list <- 空文件即可

# 4. libbpf 两行 const 丢弃需打补丁（仅宿主工具，不影响内核二进制）
#    tools/lib/bpf/libbpf.c: strstr/strchr 加 (char *) 转换

make -j$(nproc) ARCH=arm64 LLVM=-19 HOSTCC=gcc vmlinux modules_prepare
make -C <kdir> M=$PWD ARCH=arm64 LLVM=-19 HOSTCC=gcc modules
```

## ★系统更新后怎么办（决策流程）

**先连上设备看这三个值**，再决定要不要动电脑：

```bash
adb shell "cat /sys/module/vcut_lower/parameters/{t_resolved,t_delta,t_anchors,armed,sig_ok}"
```

| 现象 | 含义 | 该做什么 |
|---|---|---|
| `t_resolved=1` `t_anchors=0x1f` `armed=1` | **自愈成功**，偏移已在手机上重新解析 | **什么都不用做** |
| `t_delta` ≠ 0 | struct 挪位了，模块已自动跟上 | 什么都不用做（可以把新偏移回写进源码，让下次少一次 delta） |
| `insmod rc=8` | GKI 内核换了 → CRC 对不上 | 需回电脑：重编 + 取新 CRC（见下） |
| `insmod rc=19` | **指纹不符且自解析失败** | 需回电脑：目标函数的语义变了，锚点要重找 |
| `t_resolved=0` `sig_ok=1` | 解析失败但基线仍正确 | 能正常用，但自愈能力失效了，建议排查 |

**判定要点的物理含义**：
- `rc=8`（invalid module format）= 内核 ABI 变了 → CRC 问题
- `rc=19`（ENODEV）= 我们自己在 `vcut_init` 里拒绝加载 → 偏移/锚点问题
  这两种失效原因完全不同，看 `boot.log` 里的数字就能立刻区分。

### 自解析是怎么做到的

模块加载时（`register_kprobe` 之后 `kp.addr` 即目标函数地址）扫描函数体机器码，
用**语义锚点**匹配，而不是硬编码指令位置（位置在函数重排时会失效）：

| 锚点 | 汇编模式 | 对应的驱动源码语义 |
|---|---|---|
| A | `ldr wD,[x19,#imm]` … `add wD,wD,#50` … `cmp wM,wD` | `mean_volt <= vcutoff_delay + 50` |
| B | `cmp wZ,#3000` ／ `ldrb wG,[x19,#imm]` + `cmp wG,#1` | `vpack_low_en==1 && vpack<=3000` |
| C | `ldr wR,[x19,#imm]` + `cmn wR,#1` | `fake_soc == -1` |

得到 5 个定值（mean_volt / vcut_delay / vpack / gate / fake_soc）后，
用 `delta = 解析出的 mean_volt − 编译期基线 mean_volt` 推导其余 11 个字段。

**为什么 gate 必须用锚点而不能用位移**：位移结果总是落在前一个**高频标志**上
（OS4.0 时代的 `0xa90`、OS4.0.0.25 的 `0xaa0` 都是 8 处引用），写上去就是改无关状态。

**fail-closed**：锚点缺失/歧义 → 解析失败 → 回退到编译期基线，此时必须通过指令指纹校验
才允许加载；两者都没有 → 拒绝加载。解析结果还会被 `validate()` 复核（vcutoff 三值区间、
容量三兄弟关系、fake_soc 取值范围），错了就不会写内存。

内核里没有反汇编器，所以手写解码了需要的 6 条 AArch64 指令编码，每个掩码都对着真机反汇编验算过。

## 加载前必须做的两件事

### 1. 改写 2 个符号 CRC

```bash
# ★OS4.0 / 6.12.69-android16-6 的值（从新固件库存模块 __versions 抽取，
#   406 个模块中取值唯一）：
python tools/patch_crc.py vcut_lower.ko vcut_lower_patched.ko \
    module_layout=797f2b3e param_ops_int=d0f4936b

# 旧值（OS3.0 / 6.12.23-android16-5，仅作追溯）：
#     module_layout=e976b219 param_ops_int=fc32a8b7
```

**为什么要改**：`CONFIG_MODULE_FORCE_LOAD` 未设，`insmod --force` 和删 `__versions`
两条路都不通，改 CRC 是唯一可行方式。
**依据**：已用设备自己的 BTF（`/sys/kernel/btf/vmlinux`）在**字段级**证明相关结构体完全一致 ——
`struct module` 83 行全同、`init@0x188`/`exit@0x5f8` 与 donor 精确一致、
可达闭包 1593 个类型 0 处不同。CRC 只是断言，改它不改变任何运行行为。
剩余差异只存在于 BTF 看不见的 DWARF 层（BTF 不编码对齐/限定符，且全内核去重）。

### 2. 过四道验收关卡

```bash
python tools/verify_ko.py vcut_lower_patched.ko donor/mibbr.ko vcut_pre
```

四项全 PASS 才允许 insmod：vermagic 尾串 / 符号 CRC / kCFI 类型哈希 / struct module 布局
（后者比对 `init`/`exit` 重定位偏移，**不能用段大小** —— 64 字节对齐会掩盖 24 字节差异）。

## 模块参数

```
enable=1                    0=只观测不改写
vcut_fw / vcut_delay / vcut_sw    关机电压三值（mV），硬下限 2500
kill_vpack_gate=1           摘掉 3000mV vpack 硬闸
soc_ctl=1                   内核接管电量显示
fcc_mah=6208                电量计满充容量
qbot_mah=156                底部段容量（2026-08-04 深放实测）
rint_mohm=28                内阻（底部段实测 27.9）
handoff_hits=5              交接防抖，约 1 秒 @5Hz
fake_cycle=-1               伪装循环，-1=关；只改 strategy RAM，不碰电量计 DF
restore_on_exit=1           rmmod 时写回原值（★必须，vcutoff 是粘住的）
```

只读遥测：`armed sig_ok t_hits t_raw_soc t_disp t_mean_volt t_current t_cycle
t_failsafe t_handoff_cnt t_vbatt_empty`

## 已知问题 / 后续

- **`f(V)` 仍是线性近似**。实测低压段线性高估余量达 37 个百分点（Vocv 2863 时
  实测剩余 11.8%，线性算 49.3%），应换实测查表。
- **「2700」是带载电压不是开路电压**。内核电压闸比较不做 IR 补偿的均值电压，
  重负载（实测峰值 5A，压降 ~140mV）下会在开路电压还有 ~2820mV 时到闸。
  想榨到真正的开路 2.7V 需息屏轻载放电。
- **分档实测未做完**。设计建议 2900→2800→2700 每档一次完整放电，目前直接跑了 2700。

## 安全红线

只放宽**软件**截止，电量计固件与 PMIC 硬件过放保护完全不碰。
反复深放到 2.6~2.7V 会加速老化、有析锂风险。低温不深放。
