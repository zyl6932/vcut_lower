package com.github.zyl6932.vcutlower.root

/**
 * 解锁容量估算（方案 A：按实测放电曲线查表）。
 *
 * 数据来源：docs/深放实测数据.csv，2026-08-04 小米 17 Pro Max 一次深放，平均负载约 1 A，
 * 均值电压 3203 → 2801 mV，按电流积分得到「该电压以下还能放出多少 mAh」。当时 FCC 约 6216 mAh
 * （3200 mV 处 rm=437000 µAh、raw_soc=7.03%）。
 *
 * 局限（界面上标「估算」的原因）：
 *   - 只有这一块电池、约 1 A 负载的一条曲线；轻载实际更多，重载更少；
 *   - 2801 mV 以下没有数据，按线性外推到模块硬下限 2500 mV 处为 0；
 *   - 其他机型 / 电池按 FCC 等比例换算。
 */
object Unlock {

    /** (均值电压 mV, 该电压以下还能放出的 mAh)，电压降序 */
    private val CURVE = listOf(
        3200 to 603, 3130 to 572, 3100 to 423, 3030 to 346, 3000 to 262,
        2950 to 174, 2900 to 153, 2850 to 118, 2801 to 72,
    )
    private const val CURVE_FCC_MAH = 6216

    /** 抬高满充电压（4445 → 4465 mV）实测约多充进的容量，同样按 FCC 换算 */
    private const val FAKE_FG_MAH = 100

    private fun below(mv: Int): Double {
        val first = CURVE.first()
        val last = CURVE.last()
        if (mv >= first.first) return first.second.toDouble()
        if (mv <= last.first) {
            // 曲线末端以下：线性外推到 FLOOR 处为 0
            val span = (last.first - Vcut.FLOOR).coerceAtLeast(1)
            return last.second * ((mv - Vcut.FLOOR).coerceAtLeast(0).toDouble() / span)
        }
        for (i in 0 until CURVE.size - 1) {
            val (v1, m1) = CURVE[i]
            val (v2, m2) = CURVE[i + 1]
            if (mv in v2..v1) return m2 + (m1 - m2) * (mv - v2).toDouble() / (v1 - v2)
        }
        return 0.0
    }

    data class Estimate(val totalMah: Int, val origMv: Int, val newMv: Int, val fgMah: Int)

    /**
     * 原厂电压闸 [origMv] → 当前电压闸 [newMv] 多放出的容量，加上抬高满充电压的部分（[fakeFg] 时）。
     * [fccMah] 为当前满充容量，用来把曲线换算到这块电池；拿不到时按曲线原值。
     */
    fun estimate(origMv: Int, newMv: Int, fakeFg: Boolean, fccMah: Int?): Estimate {
        val scale = if (fccMah != null && fccMah > 1000) fccMah.toDouble() / CURVE_FCC_MAH else 1.0
        val cut = ((below(origMv) - below(newMv)).coerceAtLeast(0.0) * scale)
        val fg = if (fakeFg) FAKE_FG_MAH * scale else 0.0
        return Estimate(
            totalMah = (cut + fg).toInt().roundTo10(),
            origMv = origMv,
            newMv = newMv,
            fgMah = fg.toInt().roundTo10(),
        )
    }

    /** 估算值不该显示出个位精度 */
    private fun Int.roundTo10() = (this + 5) / 10 * 10
}
