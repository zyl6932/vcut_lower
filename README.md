# vcut_lower — 源码包

小米 17 Pro Max（popsicle）的 kprobe 内核模块：把软件关机电压降到可调值，
并在内核侧接管电量显示，使百分比全程按同一 mAh 刻度均匀下降。
v1.8 起并入**充电增强**（亮屏快充 + 按充电阶梯提升电流，守护进程 `vcut_turbo.sh`），
并带**配套 App**（`app/`，刷模块时自动安装）。

**⚠️ 当前适配 popsicle + `6.12.69-android16-6-*`（HyperOS OS4.0.4.0，靠运行时自解析；
编译期基线是 OS4.0.0.32）。** 其他机型见 v1.6 观测模式。

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

### v1.9（2026-10-21）：充电阶梯、充电记录、诊断包加充电增强

**充电阶梯**（「提升充电电流」原来是全程强制 18 A）
- 守护进程按**带载电池电压**分档强制 `business-votable/curr_max` 的 `force_val`：
  `LADDER_V`（拐点 mV，逗号分隔）+ `LADDER_MA`（各档 mA，比拐点多一个），截止 = `MAX_VBAT`。
  同一次充电**只降档不回升**（连续 2 次采样越过拐点才换档），拔插重新判定。
- 默认值：`customize.sh` 首次装 v1.9 时读设备树
  `/proc/device-tree/soc/mca_strategy_quick_charge/ffc_para_25_35`（三元组「拐点 拐点前电流 拐点后电流」），
  截止以下的拐点和电流写进 `config.sh`。17 Pro Max 为 `4015,4105` / `18000,12400,10845`。读不到则单档 `FORCE_MA`。
- **固定护栏（不读配置）**：电芯 < 23 ℃ 不强制、交还原厂（回差 1 ℃，只管提升电流；原厂此时只给 4.3~10.8 A，防析锂）；
  带载电压上限钳 ≤ 4495；单档 ≤ 18000 mA。
- App 改阶梯 / 护栏后写 `/dev/vcut_turbo.reload`，守护进程下一轮（≤5 s）重读配置；切开关则重启守护进程。
  `sleep` 改成后台 + `wait`，收到信号立刻退出（原来要睡完 30 s，还会把 `run=off` 写进新进程的状态）。
- ★实测（旧 TurboCharge 的 887 个采样）：强制 18 A 时电压照样停在原厂 4015 / 4105 拐点、电流按原厂降——
  原厂快充策略的阶梯不走 `curr_max` 投票。所以强制能做的是**拿掉 jeita 等投票方的上限**（本机原卡在 8.7 A）
  和**把某档压低**，压不过原厂快充自己的阶梯。App 里拐点因此固定，不可拖。（待 v1.9 充电记录的电流视图再确认）

**充电记录**（重启不丢）
- 每次充电（插上到拔掉）一个文件：`/data/adb/vcut_lower/sessions/<开始 epoch>.log`。
  `D 时间 电压mV 电流µA 电量 电芯温度(0.1℃) 充电电路温度(m℃) 接口温度(m℃) 档位 强制mA`，`E 时间 事件`。
- 充电中先写内存盘 `/dev/vcut_turbo.log`，每 12 轮（约 1 分钟）只把新行追加到 `/data`，拔出再同步；
  不到 1 分钟的丢弃，只留最近 30 次。会话变量在 `/dev/vcut_turbo.sess`，App 切开关重启守护进程时接着记同一次
  （最后一行超过 2 分钟则另起一次）。两个开关都关时守护进程不运行，不记录。

**诊断包**：`collect.sh` 另出 `charge.txt`（控制节点、全部投票项 status、设备树充电表——字符串 `s:` / 大端 u32 `x:`、
全部 thermal_zone、usb、守护进程状态与 turbo.log 尾 200 行）+ 本次 / 历史记录；`analyze_diag.py` 新增 [5] 充电增强，
结论单列（节点齐不齐、默认阶梯是什么、能否开）。★busybox 的 `tr` 不认 `[:print:]`、`od` 不加 `-v` 会把重复行折成 `*`。

**App 1.9**：充电增强页分「功能 / 提升充电电流 / 通用」三组（FoldUnder 收起）；充电阶梯页（横轴按本机充电记录
换算成电量比例，上下拖电流、拖截止线，右上角恢复默认）；充电记录（列表 → 详情，功率 / 电流视图）。
状态读取改为一次 `grep -H` 读完 sysfs（1.2 s → 66 ms），页面间共享状态缓存。

### v1.7 / v1.8（2026-10-21）：配套 App、并入充电增强

- **App**（`app/`，Compose + Miuix 0.9.4-rc01，包名 `com.github.zyl6932.vcutlower`）：主页 / 功能（电池解容、充电增强）/
  设置 / 关于。界面照搬 HyperMusicCover。原则：开关 = 实际运行状态，能即时生效就当场生效，不能的弹「取消 / 重新加载（或重启）」。
- **充电增强**：用户自己的 TurboCharge 并入为 `ksumodule/vcut_turbo.sh`。A「亮屏快充」= 写
  `xm_power/charger/charger_thermal/wired_thermal_remove` 摘有线热限流；B「提升充电电流」= 强制 `curr_max`。
  护栏：电量 / 带载电压 / 电芯·充电电路·接口温度，带回差。状态写 `/dev/vcut_turbo.state`。
  装 v1.8 时迁移旧 turbo_charge 模块的配置并禁用它。除 popsicle 外默认关。
- ⚠ 刷包后**未重启**期间，App 切开关会用 `/data/adb/modules/vcut_lower/` 里的旧脚本重启守护进程（新脚本在 `modules_update`）。

### v1.6（2026-10-21）：其他机型观测模式

- `customize.sh`：kallsyms 里有 `mca_battery_shutdown_check_shutdown_delay` 即允许安装；
  已验证机型写 `MODE=full`，其他机型写 `MODE=probe`（`/data/adb/vcut_lower/mode.sh`）。
- **已验证机型**：popsicle（17 Pro Max）、pandora（17 Pro，OS4.0.0.38 诊断全过、真机跑通）、
  pudding（17，OS4.0.4.0 诊断全过、观测模式 armed=1，厂商 .ko 与 popsicle 同版本逐字节相同）。
  `FAKE_CYCLE` 写的是已坐实偏移的结构体字段（t_cyc_ok 门控），已验证机型照常开；
  `FAKE_FG_CYCLE` 走 bq27z561 驱动写电量计，"纯 RAM"只在 popsicle 的驱动上反汇编确认过，
  其他机型新装默认 -1，待比对其 bq27z561.ko（collect.sh 已采集）后再放开。
  新增机型：诊断包 analyze_diag 通过 + 对方正常模式跑通后，在 customize.sh 的 case 里加一行。
- 观测模式：`enable=0 soc_ctl=0 kill_vpack_gate=0 fake_cycle=-1`，模块全程只读，
  卸载也不写回（`t_wrote=0`）；每个系统版本开机后自动跑一次 `collect.sh`，
  诊断包在 `/sdcard/Download/vcut_diag_*.tar.gz`。KSU「执行」按钮也可手动采集。
- 分析：`python tools/analyze_diag.py <包>` → CRC（9 个导入符号逐个对原厂模块）、
  离线锚点、字段对账、运行结果、结论。确认可用后让对方在 `config.sh` 加 `MODE=full` 并定电压。
- 只支持 android16-6.12 内核；CRC 只差 module_layout/param_ops_int 时可 patch 出机型专用 .ko，
  其余符号也不同 = 内核换代，需该内核树重编。

### v1.5（2026-10-21）：只读字段也能自愈 —— `autooff.sh`

锚点只覆盖 5 个字段，其余 8 个只读字段（current/cycle/temp/rm/fcc/raw_soc/soc/design）
原先吃全局 delta，遇到 §89 那种非均匀位移就 armed=0。现在 `service.sh` 在 insmod 前跑
`autooff.sh`：tracefs 只读 dump 结构体 0x9c0..0xac0，逐字段在基线 -0x20..+0x40 窗口里
对 sysfs 真值，**唯一命中**才以 `ovr_*` 参数传给模块，坐实结果还要满足原有先后顺序。
对不上的字段沿用 delta，validate 仍是最后一道关（新增 raw_soc≈rm/fcc 交叉校验、current/temp 区间）。

```
cat /sys/module/vcut_lower/parameters/t_ovr     # 坐实位图 bit0..7 = current cycle temp rm fcc raw_soc soc design
cat /sys/module/vcut_lower/parameters/t_cyc_ok  # 1 = cycle 偏移有硬证据，才会写 fake_cycle
grep autooff /data/adb/vcut_lower/boot.log      # 每个字段的对账过程
```

★顺带修了一个安全漏洞：`fake_cycle` 往无锚点、无校验的 `OFF_CYCLE` 写值。现在只有
cycle 偏移被 sysfs 坐实（`t_ovr` bit1）或指纹通过时才写（`t_cyc_ok=1`）。
`autooff.sh` 里的基线必须与 `vcut_lower.c` 的 `BASE_*` 同步。

---

### OS4.0.0.25 → OS4.0.0.32 迁移要点（2026-09-04）

内核版本串**仍一字未变**（`6.12.69-android16-6-gb1493ec68d4a-abogki515789973-4k`），
又只是 vendor 模块 `mca_strategy_fg_comp.ko` 换了 build。**但这次是【非均匀位移】——
首次出现，也正是它把"单一 delta 自解析"打穿了**：模块 `insmod rc=0`（CRC 照过）却
`armed=0`（validate 失败）自动卸载，连续 2 次开机不稳定后自停用（boot.log 实录）。

| 项 | OS4.0.0.25 | OS4.0.0.32 |
|---|---|---|
| 内核版本串 | `gb1493ec68d4a-abogki515789973` | **不变** |
| 位移方式 | 整体 +0x10（均匀） | **非均匀**：插了两处各 4 字节 |
| mean_volt | 0x9fc | **0x9fc（不动）** |
| raw_soc / soc / design | 0xa24 / 0xa38 / 0xa44 | **0xa28 / 0xa3c / 0xa48（各 +4）** |
| vcut_fw/delay/sw/empty | 0xa90/0xa94/0xa98/0xa9c | **0xa98/0xa9c/0xaa0/0xaa4（各 +8）** |
| vpack 闸门控字节 | `0xaa1` | **`0xaa9`**（★不是 `0xaa8`，那是 8 处引用的高频标志）|
| fake_soc / vpack | 0xb04 / 0xb90 | **0xb0c / 0xb98（各 +8）** |
| 符号 CRC（含 module_layout/param_ops_int）| — | **全部未变**（内核未换，patch 值照旧）|
| `struct module` 布局 / kCFI 哈希 | init@0x188 exit@0x5f8 / 0xc073bc77 | **不变** |
| 三条指纹指令位置 | +0x07c/0x108/0x10c | **位置不变**，gate/delay 的 imm12 各 +8，mean 那条不变 |

**★为什么自解析这次没兜住**：resolve_offsets 只从 mean_volt 推一个全局 delta，
mean 没动 → delta=0 → 把 fw/sw/empty 留在了旧偏移，而锚点把 delay/gate/fake_soc/vpack
正确定到 +8 → 二者错位 → validate 失败。**已加固**：vcut 三兄弟 + vbatt_empty
在结构体里永远紧贴 delay（fw=delay-4 / sw=delay+4 / empty=delay+8，跨 4 次 build 恒定），
改由锚点 delay 直接推导，不再吃全局 delta —— 这 4 个"会被写"的字段今后只要 delay
锚点命中就一定对。坐实手段：tracefs 只读密集网格 dump（`tools/gridump` 思路，逐字对 sysfs）。

**★指纹/CRC 均按同一流程**：编译（用原 6.12.23 树，内核未换）→ `patch_crc`
（值不变：`module_layout=797f2b3e param_ops_int=d0f4936b`，已对两个新固件库存模块
`donor/audio_pkt_dlkm.os4032.ko` 字段级复核）→ 四道验收 → 安装。

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
| `ksumodule/` | KernelSU 模块打包目录（service.sh / customize.sh / action.sh / config；`vcut_turbo.sh` 充电增强守护进程；`collect.sh` 诊断包；`autooff.sh` 只读字段对账）|
| `app/` | 配套 App（Compose + Miuix）|
| `tools/` | 构建、验收、实测、分析用的脚本（`pack_module.py` 打包、`analyze_diag.py` 分析诊断包）|
| `docs/` | 设计文档与完整工作日志 |

## 构建

**App 与打包**（只改了脚本或 App 时不用重编 .ko）：

```bash
cd app && ./gradlew --offline assembleRelease     # 签名复用 HMC 的 release.jks（app/keystore.properties，已 gitignore）
python tools/pack_module.py                        # → build/vcut_lower-<version>.zip（含 vcutlower.apk）
# 装机：adb push 后 su -c /data/adb/ksud module install <zip>，重启生效
```

**内核模块**需要与设备匹配的 GKI 内核树。要点（详见 `docs/工作日志.md` §37-§57）：

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

## 充电增强配置（`/data/adb/vcut_lower/config.sh`）

```
ENABLE_THERMAL_REMOVE=1     亮屏快充（摘有线热限流）
ENABLE_FORCE=1              提升充电电流（按阶梯强制 curr_max）
LADDER_V=4015,4105          拐点（mV），安装时取设备树原厂 FFC 表
LADDER_MA=18000,12400,10845 各档电流（mA，≤18000）
MAX_VBAT=4460               截止 = 带载电压上限（≤4495）
MAX_SOC=80                  电量上限，只管提升电流
MAX_BTEMP=420               电芯温度上限（0.1℃），两项都管
MAX_CTHERM=70000 MAX_CONN=45000   充电电路 / 接口温度上限（m℃）
HYST_*                      各护栏回差
```

日志 `/data/adb/vcut_lower/turbo.log`；实时状态 `/dev/vcut_turbo.state`；充电记录 `/data/adb/vcut_lower/sessions/`。

## 已知问题 / 后续

- **`f(V)` 仍是线性近似**。实测低压段线性高估余量达 37 个百分点（Vocv 2863 时
  实测剩余 11.8%，线性算 49.3%），应换实测查表。
- **「2700」是带载电压不是开路电压**。内核电压闸比较不做 IR 补偿的均值电压，
  重负载（实测峰值 5A，压降 ~140mV）下会在开路电压还有 ~2820mV 时到闸。
  想榨到真正的开路 2.7V 需息屏轻载放电。
- **分档实测未做完**。设计建议 2900→2800→2700 每档一次完整放电，目前直接跑了 2700。
- **充电阶梯的实际效果待实测**：要一次电量 < 90% 的充电，用充电记录的电流视图对比「强制上限」和「实际电流」，
  确认原厂快充在拐点自己降档的结论。
- 充电增强的节点与护栏只在 17 Pro Max 上验证过；其他机型靠诊断包 [5] 判断。

## 安全红线

只放宽**软件**截止，电量计固件与 PMIC 硬件过放保护完全不碰。
反复深放到 2.6~2.7V 会加速老化、有析锂风险。低温不深放。

充电增强只改投票上限与热限流开关，不碰充电芯片寄存器、不改设备树（2026-10-21 核实本机当前 dtbo 与原厂镜像逐字节一致、vbmeta 签名吻合）。
低于 23 ℃ 不提升电流、带载电压 ≤ 4495、单档 ≤ 18 A 写死在守护进程里，配置改不动。
