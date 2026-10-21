package com.github.zyl6932.vcutlower.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.zyl6932.vcutlower.root.Vcut
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

// 充电阶梯页的两张图：可拖动的阶梯（横轴电池电压、纵轴电流）和只读的「功率 / 时间」。
// HyperMusicCover 里没有图表可照抄，按 Miuix 配色手绘。

private const val VMIN = 3600f
private const val VMAX = 4550f
private const val AMAX = 20000f
private const val MA_MIN = 2000
private const val MA_STEP = 100
private const val CUT_MIN = 4300

/**
 * 绘图区：左右上下留出刻度位置；手势和绘制共用同一套换算。
 * 有 [axis]（本机充电记录换算表）时横轴按电量 0~100% 排，否则按电压 3.6~4.55 V。
 */
private class Plot(w: Float, h: Float, d: Density, val axis: Vcut.VSoc?) {
    val l = with(d) { 36.dp.toPx() }
    val r = w - with(d) { 12.dp.toPx() }
    val t = with(d) { 16.dp.toPx() }
    val b = h - with(d) { (if (axis != null) 44 else 28).dp.toPx() }
    val touch = with(d) { 22.dp.toPx() }
    fun x(v: Float) = l + (r - l) * (axis?.let { it.socOf(v) / 100f } ?: ((v - VMIN) / (VMAX - VMIN)))
    fun x(v: Int) = x(v.toFloat())
    fun v(x: Float): Float {
        val f = ((x - l) / (r - l)).coerceIn(0f, 1f)
        return axis?.mvOf(f * 100f) ?: (VMIN + f * (VMAX - VMIN))
    }
    fun y(ma: Int) = b - ma / AMAX * (b - t)
    fun ma(y: Float) = (b - y) / (b - t) * AMAX
}

// 拐点不可拖（2026-10-21 去掉）：实测强制 curr_max 18 A 时电压照样停在原厂 4015 / 4105 拐点、电流按原厂降，
// 原厂快充策略的阶梯不走 curr_max 投票，拐点往右拖不会更晚降档；拐点固定用设备树里的原厂值
private enum class Kind { STEP, CUT }
private class Target(val kind: Kind, val i: Int)

private fun Plot.hit(lad: Vcut.Ladder, p: Offset): Target? {
    if (abs(p.x - x(lad.cut)) < touch && p.y > b - touch * 2) return Target(Kind.CUT, 0)
    var best: Target? = null
    var bestD = touch
    lad.ma.forEachIndexed { i, ma ->
        val x1 = lad.lower(i)?.let { x(it) } ?: l
        val x2 = x(lad.upper(i))
        val d = abs(p.y - y(ma))
        if (p.x in (x1 - touch / 2)..(x2 + touch / 2) && d < bestD) { best = Target(Kind.STEP, i); bestD = d }
    }
    return best
}

private fun Plot.apply(l: Vcut.Ladder, t: Target, p: Offset): Vcut.Ladder = when (t.kind) {
    Kind.STEP -> {
        val ma = ((ma(p.y) / MA_STEP).roundToInt() * MA_STEP).coerceIn(MA_MIN, Vcut.MA_CEIL)
        l.copy(ma = l.ma.toMutableList().also { it[t.i] = ma })
    }
    Kind.CUT -> {
        val lo = maxOf(CUT_MIN, (l.nodes.lastOrNull() ?: 0) + 20)
        l.copy(cut = ((v(p.x) / 5).roundToInt() * 5).coerceIn(lo, Vcut.VBAT_CEIL))
    }
}

/**
 * 可拖动的充电阶梯。圆点上下拖改该档电流，底部的条左右拖改截止（拐点固定，见 [Kind]）；
 * 没按在手柄上的触摸不消费，列表照常滚动。松手才 [onCommit]（写配置）。
 */
@Composable
internal fun LadderChart(
    ladder: Vcut.Ladder,
    stock: List<IntArray>?,
    axis: Vcut.VSoc?,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onChange: (Vcut.Ladder) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(ladder)
    val axisNow by rememberUpdatedState(axis)
    val select by rememberUpdatedState(onSelect)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val primary = MiuixTheme.colorScheme.primary
    val sub = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val handleFill = MiuixTheme.colorScheme.surfaceContainer
    val measurer = rememberTextMeasurer()

    Canvas(
        modifier
            .fillMaxWidth()
            .height(if (axis != null) 256.dp else 240.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val plot = Plot(size.width.toFloat(), size.height.toFloat(), this, axisNow)
                    val target = plot.hit(current, down.position) ?: return@awaitEachGesture
                    down.consume()
                    if (target.kind == Kind.STEP) select(target.i)
                    var moved = false
                    drag(down.id) { c ->
                        c.consume()
                        moved = true
                        change(plot.apply(current, target, c.position))
                    }
                    if (moved) commit()
                }
            }
    ) {
        val p = Plot(size.width, size.height, this, axis)
        val label = TextStyle(fontSize = 11.sp, color = sub)

        // 刻度
        for (a in listOf(0, 6000, 12000, 18000)) {
            drawLine(sub.copy(alpha = if (a == 0) 0.35f else 0.12f), Offset(p.l, p.y(a)), Offset(p.r, p.y(a)), 1f)
            text(measurer, "${a / 1000}", Offset(p.l - 6.dp.toPx(), p.y(a)), label, alignEnd = true)
        }
        text(measurer, "A", Offset(p.l - 6.dp.toPx(), p.t - 8.dp.toPx()), label, alignEnd = true)
        if (axis != null) {
            // 第一行电量刻度，第二行各拐点的电压
            for (pc in listOf(0, 25, 50, 75, 100)) {
                val x = p.l + (p.r - p.l) * pc / 100f
                text(measurer, "$pc%", Offset(x, p.b + 12.dp.toPx()), label,
                    center = pc in 1..99, alignEnd = pc == 100)
            }
            for (n in ladder.nodes) {
                text(measurer, "$n", Offset(p.x(n), p.b + 30.dp.toPx()), label.copy(color = primary), center = true)
            }
            // 灰色禁区的起点（截止线拖不过它）
            text(measurer, "${Vcut.VBAT_CEIL}", Offset(p.x(Vcut.VBAT_CEIL), p.b + 30.dp.toPx()), label, center = true)
        } else {
            for (v in listOf(3600, 3800, 4000, 4200, 4400)) {
                text(measurer, "%.1f".format(v / 1000f), Offset(p.x(v), p.b + 12.dp.toPx()), label, center = true)
            }
            text(measurer, "V", Offset(p.r, p.b + 12.dp.toPx()), label, alignEnd = true)
        }

        // 4495 以上不可用
        drawRect(sub.copy(alpha = 0.08f), Offset(p.x(Vcut.VBAT_CEIL), p.t), Size(p.r - p.x(Vcut.VBAT_CEIL), p.b - p.t))

        // 原厂（灰色虚线）
        if (!stock.isNullOrEmpty()) {
            val sp = Path().apply {
                moveTo(p.l, p.y(stock[0][1]))
                for (r in stock) {
                    lineTo(p.x(r[0]), p.y(r[1]))
                    lineTo(p.x(r[0]), p.y(r[2]))
                }
                lineTo(p.r, p.y(stock.last()[2]))
            }
            drawPath(sp, sub.copy(alpha = 0.7f), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))))
        }

        // 用户阶梯
        val up = Path().apply {
            moveTo(p.l, p.y(ladder.ma[0]))
            ladder.nodes.forEachIndexed { i, n ->
                lineTo(p.x(n), p.y(ladder.ma[i]))
                lineTo(p.x(n), p.y(ladder.ma[i + 1]))
            }
            lineTo(p.x(ladder.cut), p.y(ladder.ma.last()))
        }
        val area = Path().apply {
            addPath(up)
            lineTo(p.x(ladder.cut), p.b)
            lineTo(p.l, p.b)
            close()
        }
        drawPath(area, primary.copy(alpha = 0.10f))
        drawPath(up, primary, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))

        // 截止线
        val cx = p.x(ladder.cut)
        drawLine(primary, Offset(cx, p.t), Offset(cx, p.b), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        text(measurer, "截止 ${ladder.cut}", Offset(cx - 4.dp.toPx(), p.t + 4.dp.toPx()), label.copy(color = primary), alignEnd = true)

        if (!enabled) return@Canvas
        // 手柄
        ladder.ma.forEachIndexed { i, ma ->
            val x1 = ladder.lower(i)?.let { p.x(it) } ?: p.l
            val c = Offset((x1 + p.x(ladder.upper(i))) / 2, p.y(ma))
            val rr = (if (i == selected) 9 else 7).dp.toPx()
            drawCircle(handleFill, rr, c)
            drawCircle(primary, rr, c, style = Stroke(2.5.dp.toPx()))
        }
        val w = 14.dp.toPx(); val h = 24.dp.toPx()
        drawRoundRect(primary, Offset(cx - w / 2, p.b - h - 2.dp.toPx()), Size(w, h), CornerRadius(w / 2))
    }
}

/**
 * 一次充电的记录图，按住拖动看任意时刻，事件处画竖虚线。两种视图：
 *  - 功率：功率（蓝）+ 电量（橙）；
 *  - 电流：实际电流（蓝）+ 提升电流强制的上限（橙虚线，只画强制中的时段）——看原厂快充是不是在
 *    拐点自己降、强制的上限有没有用上。
 */
@Composable
internal fun PowerChart(
    samples: List<Vcut.Sample>,
    events: List<Vcut.Event>,
    t0: Int,
    picked: Int,
    current: Boolean,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pick by rememberUpdatedState(onPick)
    val primary = MiuixTheme.colorScheme.primary
    val second = Color(0xFFE07B00)
    val sub = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val measurer = rememberTextMeasurer()

    val span = maxOf(600, (samples.lastOrNull()?.t ?: t0) - t0)
    // 纵轴：功率按 20 W 取整，电流按 5 A 取整（含强制上限）
    val yMax = if (current) {
        val peak = samples.maxOfOrNull { maxOf(amps(it), it.ma / 1000f) } ?: 0f
        maxOf(10f, ceil(peak / 5f) * 5f)
    } else {
        maxOf(20f, ceil((samples.maxOfOrNull { watts(it) } ?: 0f) / 20f) * 20f)
    }
    fun value(s: Vcut.Sample) = if (current) amps(s) else watts(s)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .pointerInput(samples) {
                if (samples.isEmpty()) return@pointerInput
                fun idx(x: Float): Int {
                    val l = 36.dp.toPx(); val r = size.width - 36.dp.toPx()
                    val t = t0 + (x - l) / (r - l) * span
                    return samples.indices.minByOrNull { abs(samples[it].t - t) } ?: 0
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pick(idx(down.position.x))
                    drag(down.id) { c ->
                        c.consume()
                        pick(idx(c.position.x))
                    }
                }
            }
    ) {
        val l = 36.dp.toPx(); val r = size.width - 36.dp.toPx()
        val top = 12.dp.toPx(); val b = size.height - 24.dp.toPx()
        fun x(t: Int) = l + (t - t0).toFloat() / span * (r - l)
        fun yv(v: Float) = b - v / yMax * (b - top)
        fun ys(s: Int) = b - s / 100f * (b - top)
        val label = TextStyle(fontSize = 11.sp, color = sub)

        for (k in 0..4) {
            val y = top + (b - top) * k / 4
            drawLine(sub.copy(alpha = if (k == 4) 0.35f else 0.12f), Offset(l, y), Offset(r, y), 1f)
        }
        text(measurer, "${yMax.roundToInt()}", Offset(l - 6.dp.toPx(), top), label.copy(color = primary), alignEnd = true)
        text(measurer, "0", Offset(l - 6.dp.toPx(), b), label, alignEnd = true)
        text(measurer, if (current) "A" else "W", Offset(l - 6.dp.toPx(), (top + b) / 2), label.copy(color = primary), alignEnd = true)
        if (!current) {
            text(measurer, "100%", Offset(r + 4.dp.toPx(), top), label.copy(color = second))
            text(measurer, "0", Offset(r + 4.dp.toPx(), b), label.copy(color = second))
        }
        text(measurer, "0", Offset(l, b + 12.dp.toPx()), label, center = true)
        text(measurer, "${span / 60} 分钟", Offset(r, b + 12.dp.toPx()), label, alignEnd = true)

        for (e in events) {
            if (e.t <= t0) continue
            drawLine(sub.copy(alpha = 0.4f), Offset(x(e.t), top), Offset(x(e.t), b), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
        }
        if (samples.isEmpty()) return@Canvas

        val pv = Path()
        samples.forEachIndexed { i, s ->
            if (i == 0) pv.moveTo(x(s.t), yv(value(s))) else pv.lineTo(x(s.t), yv(value(s)))
        }
        val area = Path().apply {
            addPath(pv); lineTo(x(samples.last().t), b); lineTo(x(samples.first().t), b); close()
        }
        if (current) {
            // 强制上限：只连强制中的相邻点，没强制（交还原厂）的时段断开
            val pf = Path()
            var on = false
            for (s in samples) {
                if (s.ma > 0) {
                    val y = yv(s.ma / 1000f)
                    if (on) pf.lineTo(x(s.t), y) else pf.moveTo(x(s.t), y)
                    on = true
                } else on = false
            }
            drawPath(pf, second, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        } else {
            val ps = Path()
            samples.forEachIndexed { i, s -> if (i == 0) ps.moveTo(x(s.t), ys(s.soc)) else ps.lineTo(x(s.t), ys(s.soc)) }
            drawPath(ps, second, style = Stroke(2.dp.toPx()))
        }
        drawPath(area, primary.copy(alpha = 0.10f))
        drawPath(pv, primary, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))

        val s = samples[if (picked < 0) samples.lastIndex else picked.coerceAtMost(samples.lastIndex)]
        val hx = x(s.t)
        drawLine(sub, Offset(hx, top), Offset(hx, b), 1f)
        drawCircle(primary, 4.5.dp.toPx(), Offset(hx, yv(value(s))))
        if (current) {
            if (s.ma > 0) drawCircle(second, 4.5.dp.toPx(), Offset(hx, yv(s.ma / 1000f)))
        } else {
            drawCircle(second, 4.5.dp.toPx(), Offset(hx, ys(s.soc)))
        }
    }
}

/** 充电功率 W：current_now 充电为负 */
internal fun watts(s: Vcut.Sample): Float = maxOf(0f, s.mv / 1000f * (-s.ua) / 1_000_000f)

/** 充电电流 A（充电为正） */
internal fun amps(s: Vcut.Sample): Float = maxOf(0f, -s.ua / 1_000_000f)

private fun DrawScope.text(
    m: TextMeasurer, s: String, at: Offset, style: TextStyle,
    alignEnd: Boolean = false, center: Boolean = false,
) {
    val r = m.measure(s, style)
    val x = when {
        alignEnd -> at.x - r.size.width
        center -> at.x - r.size.width / 2f
        else -> at.x
    }
    drawText(r, topLeft = Offset(x, at.y - r.size.height / 2f))
}
