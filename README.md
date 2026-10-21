<div align="center">

# VCut Lower

### "让小米手机把电池用到底"

[![HyperOS](https://img.shields.io/badge/HyperOS-4-FF6900)](https://hyperos.mi.com/)
[![Android](https://img.shields.io/badge/Android-15%20%7C%2016-3DDC84?logo=android&logoColor=white)](https://www.android.com/)
[![KernelSU](https://img.shields.io/badge/KernelSU-Module-blue)](https://kernelsu.org/)

</div>

---

## 简介

VCut Lower 是一个给**小米 / REDMI 澎湃 OS 4（HyperOS 4）** 机型用的 KernelSU 模块，外加一个配套 App。

起因很简单：我的 17 Pro Max 电量还剩一截就关机了。原厂关机电压定在 3.1 V 上下，可电池在这之下其实还能放出不少电——我自己深放实测，原厂关机点以下还能放出**将近 600 mAh**。这个模块做的事情，就是把这条软件关机线往下挪，把这部分容量还给你。

- **降低关机电压**：默认从原厂的 2.85~3.25 V（看机型）降到 2.62 V，App 里可以随时调
- **电量全程均匀**：电量显示改由模块接管，从 100% 到 1% 用同一把尺子，不会在末尾突然掉电
- **充电增强**：亮屏快充、按充电阶梯提升电流，带温度和电压护栏
- **配套 App**：所有开关、状态、充电记录都在里面，刷模块时自动安装

> 非官方模块，与小米无关联。它只放宽**软件**层面的关机电压，电量计固件和 PMIC 硬件的过放保护一概不碰。

## 功能概览

- **电池解容**
  - 关机电压：保守 / 中等 / 激进三档预设，或者 2500~3400 mV 之间自己拖
  - 解锁容量：首页显示比原厂多用到的 mAh。优先用真实放电数据算，电量用到原厂关机点附近之后会越来越准
  - 内核接管电量显示，开关都能当场生效
  - 伪装循环次数：系统会按循环次数逐步压低满充电压，伪装成新电池就能多充进一点（抬高满充电压目前只在 17 Pro Max 上默认开）
- **充电增强**
  - 亮屏快充：亮屏、发热时不再被砍到很低的电流
  - 提升充电电流：按电池电压分档（充电阶梯），默认值直接读本机设备树里的原厂表，各档电流和截止线可以拖
  - 充电记录：每次充电自动保存，看功率曲线、电流曲线
- **诊断包**：一键导出，新机型适配、排查问题都靠它

> 功能还在持续调整，具体以 App 里实际看到的为准。

## 支持机型

| 机型 | 代号 | 原厂关机电压 |
|---|---|---|
| 小米 17 Pro Max | popsicle | 3130 mV |
| 小米 17 Pro | pandora | 3130 mV |
| 小米 17 | pudding | 3030 mV |
| 小米 17 Ultra | nezha | 2850 mV |
| REDMI K90 Pro Max | myron | 3030 mV |
| 小米 15 | dada | 3050 mV |
| 小米 15 Pro | haotian | 3250 mV |

原厂关机电压是测试时读到的值，会随系统版本、温度变化。

**其他机型**：只要用的是小米 MCA 电量驱动，就能装，但会以**观测模式**运行——模块只读不写，什么都不改，开机后自动生成一份诊断包放在 `/sdcard/Download/`。把它发给我，分析通过、你那边跑通之后，就能加进上面的名单。

## 安装

1. 已解锁 Bootloader，装好 **KernelSU**（目前只在 KernelSU 上测试过）
2. 在 KernelSU 里刷入模块 zip，重启
3. 第一次打开「VCut Lower」App 时，在 KernelSU 的超级用户里允许它

配置文件在 `/data/adb/vcut_lower/config.sh`，升级模块不会覆盖。

## 兼容性与已知问题

- **只支持 HyperOS 4**：17 系列和 K90 Pro Max 是 Android 16（6.12 内核），15 系列是 Android 15（6.6 内核），模块会按内核自动选
- **系统更新后一般不用等新版**：模块加载时会从原厂驱动的机器码里重新找它要用的位置，大多数小版本更新能自己适配；内核本身换了才需要重新编译
- **开机后的前半分钟**：模块还没接管时显示的是系统原生电量，接管后会换成模块的读数，高电量时可能看到跳几个百分点
- **提升充电电流**要靠系统里的 `curr_max` 投票节点，17 系列有；15 系列没有这个节点，目前只能用亮屏快充

## 免责声明

本模块仅供学习、研究与个人使用。使用本模块即表示你理解并接受：

- **降低关机电压意味着更深的放电**。反复把电池放到 2.6~2.7 V 会加快老化，低温下尤其不要深放。电池已经明显老化的，请用保守档
- 模块会修改内核里电量驱动的运行状态，可能导致电量显示异常或系统不稳定。连续两次开机不稳定时模块会自己停用，KernelSU 的安全模式也能一键禁用全部模块
- 充电增强会放宽原厂的部分充电限制。温度、电压护栏写死在代码里，但仍可能增加发热
- 因使用本模块造成的设备故障、电池损耗或数据丢失，作者不承担责任
- 有问题请通过 [Issues](https://github.com/zyl6932/vcut_lower/issues) 联系

## 开源致谢

- [HyperMusicCover](https://github.com/zyl6932/HyperMusicCover) — 配套 App 的界面框架、主题和大部分组件直接沿用
- [miuix](https://github.com/compose-miuix-ui/miuix) — App 使用的 Compose 组件
- [KernelSU](https://github.com/tiann/KernelSU) — 模块的运行环境
- [Android Common Kernel](https://android.googlesource.com/kernel/common/) — 编译内核模块用的 GKI 内核源码

想看实现细节、各版本改动和构建方法，见 [docs/开发说明.md](./docs/开发说明.md)。

---

<p align="center">Made with kprobes & Jetpack Compose · VCut Lower</p>
