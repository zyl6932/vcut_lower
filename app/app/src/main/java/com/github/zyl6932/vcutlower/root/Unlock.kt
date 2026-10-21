package com.github.zyl6932.vcutlower.root

import kotlin.math.abs

/**
 * 解锁容量估算。按可信度依次取：
 *
 *  ① 深放实测：某轮放电跨越原厂电压闸后一路放到关机（最低均值电压到了我们的闸附近），
 *     跨越后按电流积分的放电量就是实际多放出的容量。
 *  ② 跨越点 rm（2026-10-21 起的主力）：放电中均值电压首次 ≤ 原厂电压闸 —— 原厂此刻就会关机 ——
 *     那一刻电量计的 rm。电量计截止电压已经跟到我们的闸，rm 就是「放到新截止还剩多少」，
 *     由电量计按自己的阻抗模型算，自带负载、温度、老化修正。取最近 3 次平均。
 *  ③ 都没有：按实测放电曲线查表（下面 CURVE，最早的方案 A）。
 *
 * ①② 来自 vcut_lower.c 的 unlock_track（模块参数 t_cross_* / t_below_*），由 service.sh 每分钟
 * 落盘到 unlock.log / unlock.cur；只用原厂闸、我们的闸都和现在一致的记录（换了电压就作废）。
 */
object Unlock {

    /** 一轮跨越记录（unlock.log 的一行，或本次加载的模块参数） */
    data class Rec(
        val rm: Int, val mv: Int, val ma: Int, val temp: Int,
        val stock: Int, val delay: Int, val below: Int, val minMv: Int,
    )

    /** unlock.log 一行：t=… rm=… mv=… ma=… temp=… stock=… delay=… below=… minmv=… */
    fun parse(line: String): Rec? {
        val m = line.split(' ').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        fun i(k: String) = m[k]?.toIntOrNull()
        return Rec(
            rm = i("rm") ?: return null, mv = i("mv") ?: 0, ma = i("ma") ?: 0, temp = i("temp") ?: 0,
            stock = i("stock") ?: return null, delay = i("delay") ?: return null,
            below = i("below") ?: 0, minMv = i("minmv") ?: 0,
        )
    }

    /** 本次加载的模块里还没落盘的那一轮（比 unlock.cur 新，最多差一分钟） */
    fun live(s: Vcut.Status): Rec? {
        if ((s.pi("t_cross_seq") ?: 0) <= 0) return null
        return Rec(
            rm = s.pi("t_cross_rm") ?: return null, mv = s.pi("t_cross_mv") ?: 0,
            ma = s.pi("t_cross_ma") ?: 0, temp = s.pi("t_cross_temp") ?: 0,
            stock = s.pi("t_cross_stock") ?: return null, delay = s.pi("t_cross_delay") ?: return null,
            below = s.pi("t_below_mah") ?: 0, minMv = s.pi("t_below_min_mv") ?: 0,
        )
    }

    /** (均值电压 mV, 该电压以下还能放出的 mAh)，电压降序。2026-08-04 小米 17 Pro Max 一次 ~1A 深放 */
    private val CURVE = listOf(
        3200 to 603, 3130 to 572, 3100 to 423, 3030 to 346, 3000 to 262,
        2950 to 174, 2900 to 153, 2850 to 118, 2801 to 72,
    )
    private const val CURVE_FCC_MAH = 6216

    /** 抬高满充电压（4445 → 4465 mV）实测约多充进的容量，按 FCC 换算 */
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
     * [recs] 为实测记录（旧 → 新）；[fccMah] 为当前满充容量，曲线查表和满充电压那部分按它换算。
     */
    fun estimate(origMv: Int, newMv: Int, fakeFg: Boolean, fccMah: Int?, recs: List<Rec> = emptyList()): Estimate {
        val scale = if (fccMah != null && fccMah > 1000) fccMah.toDouble() / CURVE_FCC_MAH else 1.0
        // 原厂闸随温度会被原厂重算（小米 15），留 30mV 余量；我们的闸必须一致
        // 本次加载的实时记录与 unlock.cur 是同一轮（跨越时的值相同，只有放电量更新）：留后出现的那条
        val match = recs.asReversed().distinctBy { listOf(it.rm, it.mv, it.ma, it.temp, it.stock, it.delay) }.asReversed()
            .filter { abs(it.stock - origMv) <= 30 && it.delay == newMv && it.rm in 1..5000 }
        val deep = match.takeLast(5).lastOrNull { it.below > 0 && it.minMv in 1..(newMv + 100) }
        val cut = when {
            newMv >= origMv -> 0.0
            deep != null -> deep.below.toDouble()
            match.isNotEmpty() -> match.takeLast(3).map { it.rm }.average()
            else -> (below(origMv) - below(newMv)).coerceAtLeast(0.0) * scale
        }
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
