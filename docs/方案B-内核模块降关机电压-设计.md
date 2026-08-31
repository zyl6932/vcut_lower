# 方案B · 内核模块降关机电压到 ~2.6V · 设计与可行性

**设备**：Xiaomi 17 Pro Max popsicle / HyperOS OS3.0.317.0.WPBCNXM / 内核 `6.12.23-android16-5-g75e9b1c7ae7c-abogki463945075-4k` + KernelSU
**目标**：把软件关机电压从当前档(~3030mV)降到 ~2600mV，榨出底部电量。
**日期**：2026-08-04　**状态**：可行性已验证(全绿)，待用户批准进入编码。

---

## 一、为什么是内核模块（前置结论）
纯 sysfs 脚本无效：关机两道闸中，**电压闸**读的是原始电量计电压/温度/循环，`fake_soc`/`fake_temp` 只改显示层，够不着。详见 `工作日志.md` §11/§12、记忆 `shutdown-gates-map`。

---

## 二、关机链路与靶点（反汇编实证）
```
fg_update_status → update_vcutoff_para → update_cc_para
   每周期按[温度[0x850]→剩余量]查 dod_para 表 → 算出 vcutoff_fw/delay/sw
   → 存 strategy_struct[0x8d8/0x8dc/0x8e0]
fg_ui_soc_smooth → check_shutdown_delay
   ★闸: 电压[0x844] < vcutoff_shutdown_delay[0x8dc] + 50 → 关机计数→发uevent→用户态关机
```
- **闸值来源**：`[0x8dc]` 每周期被 update_vcutoff_para 重算覆盖 → **不能只改 [0x8dc]**（会被覆盖）
- **表结构**（`parse_vcutoff_para` 实证）：每张表 kmalloc 成 16 字节/行数组
  ```c
  struct vcutoff_row { s32 remaining; s32 vcutoff_fw; s32 vcutoff_shutdown_delay; s32 vcutoff_sw; }; // 16B
  容器 { s32 size; vcutoff_row *rows; }  由 strategy_struct[0x918](health)/[0x940](spec) 引用
  ```
- dod_para 表当前值（真机读出）：dod_para3 最高档 79→3030/3030/2930 … 65535→3310/3310/3210；dod_para0 最低 79→2650/2600/2500

---

## 三、可行性验证结果（真机 kmod_feasibility.sh，全绿）
| 条件 | 配置/实测 | 结论 |
|---|---|---|
| 未签名模块 | `CONFIG_MODULE_SIG=y` 但 `MODULE_SIG_FORCE` 未设；tainted=4608(已有OOT未签名模块) | ✅ 可 insmod |
| LOCKDOWN | `SECURITY_LOCKDOWN_LSM` 未编；运行态 N | ✅ 无拦截 |
| KPROBES | `CONFIG_KPROBES=y`；`register_kprobe`=T(导出) | ✅ 可 hook |
| 符号可见 | `KALLSYMS_ALL=y`；`update_vcutoff_para`/`fg_update_status`/`check_shutdown_delay` 在 kallsyms | ✅ 可定位 |
| kallsyms_lookup_name | =T **已导出** | ✅ 直接按名解析，无需 kprobe trick |
| 目标模块存活 | `mca_strategy_fg_comp` 已加载(81920) | ✅ |
| 平台设备 | `/sys/devices/platform/soc/soc:strategy-fg` 在 | ✅ drvdata 备选定位 |
| 构建约束 | `MODVERSIONS=y`；vermagic `6.12.23-android16-5-...abogki463945075` | ⚠️ 需匹配 GKI 头+vermagic |

---

## 四、模块设计（推荐：kprobe 即时改闸值）
### 主方案 A：kprobe on `mca_battery_shutdown_check_shutdown_delay`
- pre-handler：`struct = (void*)regs->regs[0]`（x0=strategy_struct）
- 即时覆盖（在函数体比较之前，永远赢过 update_vcutoff_para 的重算）：
  ```c
  *(s32*)(struct+0x8d8) = TARGET_FW;     // vcutoff_fw       e.g. 2650
  *(s32*)(struct+0x8dc) = TARGET_DELAY;  // shutdown_delay   e.g. 2600  (闸: 电压<2650触发)
  *(s32*)(struct+0x8e0) = TARGET_SW;     // vcutoff_sw       e.g. 2550
  ```
- 优点：不碰表、不管重算周期、单点、rmmod 即还原。目标电压可参数化(module_param)。

### 备选方案 B'：一次性改表行（更靠近根因）
- kprobe on `update_vcutoff_para`，首次命中时遍历 `struct[0x918]/[0x940]` → 各温度带 rows[]，把 fw/delay/sw clamp 到 ≤2600，置 done 标志。之后重算天然产出低值。
- 缺点：要正确遍历嵌套结构(temp band 0x20 步长 + rows)，比 A 复杂。

### 配套：UI 显示（两部分，实证于 fg_ui_soc_smooth / record_volt_mean）
UI 底部有两个归 0 机制，需分别对待：

**(1) vbatt_empty 强制 0%——kprobe 自动搞定，无需额外工作**
- `record_volt_mean`：`[0x844]=近5样本均值电压`；当 `mean_volt ≤ vcutoff_sw[0x8e0]` 连续>2次 → `[0x8e4]=1` → fg_ui_soc_smooth 里 `set ui_soc=0 by vbatt_empty`
- **UI判空阈值 = vcutoff_sw[0x8e0]，正是本模块 kprobe 覆盖的三值之一** → 把 [0x8e0]=2500 后，vbatt_empty 不再于 3.0V 触发。**shutdown闸 + UI判空 同一 kprobe 解决**。

**(2) 自然百分比在 3030mV 触底——需 fake_soc 控制器补底部化妆**
- 真实 soc[0x880] = 映射(rsoc=rm/FCC)，rm 在电量计 Term Voltage(3030) 处到 0 → 3030mV 以下 rsoc=0
- 不处理的话：手机会在"0%"状态从 3030 一路跑到 2600（能用但难看）
- **fake_soc 控制器**（KernelSU service watchdog，不进内核模块）：
  ```
  每秒采样 voltage_now:
    若 内部真实soc > 0 → fake_soc = -1（透传，正常显示）
    若 真实soc==0 且 V>2600 → synthetic = round( (V-2600)/(3030-2600) * 5 )，clamp[0,5]，写 fake_soc
    若 V<=2600 → fake_soc=0（此时电压闸也正好关机）
  ```
- fake_soc 覆盖链已确认：ops_get_soc 返回 [0x880] 或 fake_soc[0x94c]，capacity 跟随 fake_soc
- 注意：check_shutdown_delay 里 fake_soc≠-1 时会跳过发 shutdown_delay uevent（0x9634），所以底部用 fake_soc 期间关机由电压闸的其他路径/vbatt 兜底——分档实测时重点观察这一段能否正常在 2.6V 关机。

---

## 五、必须处理的残留闸（实现前确认）
1. **硬编码 3000mV 强关**：`check_shutdown_delay@0x94dc cmp #0xbb8(3000)`，仅当 `struct[0x8e8]==1`(vpack_inside_fg_absent 异常路径)生效。→ 实现第一步先 dump `[0x8e8]`，正常应为 0；若为 1，pre-handler 里同时处理。
2. **heavy_load 支路**：`[0x838] ≥ 2000001` 且 `[0x844] < [0x8dc]+50` 才计数 → 主闸仍是 [0x8dc]，改 A 覆盖即可。
3. **[0x844] 电压单位/来源**：check 里与 [0x8dc](mV) 同比较，应为 mV 级 pack 电压(来自 platform_fg_ops_get_volt)。

---

## 六、实现步骤（编码阶段，需用户批准写操作）
0. **只读探针先行**：先做一个"只 dump 不改"的 kprobe 模块，命中 check_shutdown_delay 时 printk 出 `[0x844]/[0x8d8]/[0x8dc]/[0x8e0]/[0x8e8]` 实际值 → **验证静态反汇编出的偏移在本 build 上正确**（偏移来自逆向，必须运行时坐实再写）。
1. 确认偏移无误后，改为写入 pre-handler（方案A），目标 2600mV，module_param 可调。
2. 打包 KernelSU 模块：post-fs-data/service 里 `insmod`；配 fake_soc watchdog 服务。
3. 分阶段实测：先设 2900 → 2800 → 2700 → 2600，每档观察一次完整放电，记录能否稳定到点关机、有无异常。
4. 卸载/回滚：`rmmod` 即恢复原逻辑；模块不改任何持久存储。

---

## 七、安全红线（务必遵守）
- **只放宽软件截止，保留电量计固件 + PMIC 硬件过放保护**（模块不碰电量计 DF、不碰硬件 OVP/UVP）。
- 反复深放到 2.6V 加速老化、有析锂/鼓包风险；`min_life_vol=2609` 仅表示到过一次，非可反复。
- 建议目标不低于 2.6V；低温时电池不宜深放，watchdog/参数应考虑温度（冷时回到高截止）。
- 偏移来自静态逆向，**写之前必须用只读探针在真机坐实**（步骤0），严禁盲写内核内存。

---

## 附：关键偏移速查（strategy_fg struct，来自 mca_strategy_fg_comp.ko 逆向）
| 偏移 | 含义 |
|---|---|
| 0x844 | **近5样本平均电压(mV)** — 电压闸与 vbatt_empty 都比它 |
| 0x838 | heavy_load 判定值(阈 2000001) |
| 0x8e4 | **vbatt_empty 标志**(==1→UI强制0%)，由 mean_volt≤vcutoff_sw[0x8e0] 置位 |
| 0x84c | 循环次数(选档用，来自原始电量计) |
| 0x850 | 温度(°C，=原始温度−补偿[0x858]，选档用) |
| 0x880 | 内部 soc |
| 0x8c8 | probe 传入的 device/psy 指针 |
| 0x8d8 / 0x8dc / 0x8e0 | vcutoff_fw / shutdown_delay / sw（重算结果，★kprobe覆盖点）|
| 0x8e8 | vpack_inside_fg_absent 标志(==1 触发3000硬闸) |
| 0x918 / 0x940 | health / spec 表容器 {size, vcutoff_row*} |
| 0x94c | fake_soc(-1=off) |
| 0x950 | fake_temp(仅 sysfs 回显) |
