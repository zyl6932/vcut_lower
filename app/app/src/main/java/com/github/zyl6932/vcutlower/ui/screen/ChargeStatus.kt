package com.github.zyl6932.vcutlower.ui.screen

import com.github.zyl6932.vcutlower.root.Vcut

// 充电增强实时状态的文字格式化（主页电池卡片使用）

internal fun turboStateText(t: Vcut.Turbo): String = when {
    !t.supported -> "本机不支持"
    !t.thermalRemove && !t.force -> "未开启"
    t.activeA || t.activeB -> "生效中：" + listOfNotNull(
        "亮屏快充".takeIf { t.activeA },
        (if (t.stepMa > 0) "已提升电流（第 ${t.step} 档 %.1f A）".format(t.stepMa / 1000f) else "已提升电流")
            .takeIf { t.activeB },
    ).joinToString("、")
    t.why.isNotEmpty() -> prettyWhy(t.why)
    else -> "待命"
}

/** 守护进程沿用 TurboCharge 的日志写法（电芯 470 / 充电电路 79193 / vbat 4490mV），这里换成带单位的说法 */
internal fun prettyWhy(why: String): String = why
    .replace(Regex("""电芯 (\d+)""")) { "电芯 %.1f ℃".format(it.groupValues[1].toInt() / 10f) }
    .replace(Regex("""充电电路 (\d+)""")) { "充电电路 %.1f ℃".format(it.groupValues[1].toInt() / 1000f) }
    .replace(Regex("""接口 (\d+)""")) { "接口 %.1f ℃".format(it.groupValues[1].toInt() / 1000f) }
    .replace(Regex("""vbat (\d+)mV""")) { "电压 ${it.groupValues[1]} mV" }
    .replace("SOC ", "电量 ")
