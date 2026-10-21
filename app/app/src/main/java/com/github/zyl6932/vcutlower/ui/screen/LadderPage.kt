package com.github.zyl6932.vcutlower.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.zyl6932.vcutlower.root.Vcut
import com.github.zyl6932.vcutlower.ui.util.PageScaffold
import com.github.zyl6932.vcutlower.ui.util.firstCard
import com.github.zyl6932.vcutlower.ui.util.nextCard
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 充电增强 → 充电阶梯：横轴按电量比例（换算表来自本机充电记录）、纵轴电流的阶梯，拖动即改，
 * 松手写配置、守护进程 5 秒内按新阶梯强制；虚线是本机设备树里的原厂 FFC 表（25~35 ℃），只作参考。
 * 右上角「恢复默认」= 改回那张原厂表（弹窗确认）。截止线就是带载电压上限 MAX_VBAT（≤4495），
 * 23 ℃ 温度下限固定在守护进程里，这里不可改。充电记录（功率 / 时间）在充电增强页底部，见 ChargeLogPage。
 */
@Composable
fun LadderPage(
    status: Vcut.Status,
    ready: Boolean,
    isBlurEnabled: Boolean,
    onChanged: () -> Unit,
    onBack: () -> Unit,
) {
    var resetAsk by remember { mutableStateOf(false) }
    PageScaffold(
        title = "充电阶梯",
        isBlurEnabled = isBlurEnabled,
        onBack = onBack,
        actions = {
            IconButton(onClick = { resetAsk = true }) {
                Icon(
                    imageVector = MiuixIcons.Reset,
                    contentDescription = "恢复默认",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    ) {
        ladderItems(status, ready, onChanged, resetAsk) { resetAsk = false }
    }
}

// ---------------- 编辑 ----------------

private fun androidx.compose.foundation.lazy.LazyListScope.ladderItems(
    status: Vcut.Status,
    ready: Boolean,
    onChanged: () -> Unit,
    resetAsk: Boolean,
    onResetDone: () -> Unit,
) {
    item(key = "ladder") { LadderEditor(status, ready, onChanged, resetAsk, onResetDone) }
}

/**
 * [ready] = 本页的状态已经读到过一次（新 Activity 开始时是空状态）；
 * [resetAsk] = 右上角「恢复默认」被点了，在这里弹确认框（存阶梯的逻辑在这里）
 */
@Composable
private fun LadderEditor(
    status: Vcut.Status,
    ready: Boolean,
    onChanged: () -> Unit,
    resetAsk: Boolean,
    onResetDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = Vcut.turboOf(status)
    val saved = Vcut.ladderOf(status)
    // 拖动中 / 刚写入还没读回来时显示草稿；读回的配置与草稿一致就丢掉草稿
    var draft by remember { mutableStateOf<Vcut.Ladder?>(null) }
    LaunchedEffect(saved) { if (draft == saved) draft = null }
    val ladder = draft ?: saved
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val sel = selected.coerceIn(0, ladder.ma.lastIndex)

    // 充电增强页已经预读进缓存（Vcut.stockCache / vsocCache），从那里进来就直接画；后台再刷新换算表
    var stockT by remember { mutableStateOf(Vcut.stockCache) }
    var axis by remember { mutableStateOf(Vcut.vsocCache) }
    var loaded by remember { mutableStateOf(Vcut.stockLoaded && Vcut.vsocLoaded) }
    LaunchedEffect(Unit) {
        stockT = Vcut.stockCached()
        axis = Vcut.vsocRefresh()
        loaded = true
    }
    // 虚线与「按原厂值」用同一张表：原厂 FFC（25~35 ℃），也是安装时默认阶梯的来源。原来虚线跟着原厂
    // 此刻用的表走，电量 ≥95% 或温度换档时切成普通表，和按钮对不上；而阶梯只在 23~48 ℃、电量上限以下
    // 生效，要比的本来就是 FFC 表
    val stock = stockT?.let { it.name to it.table }

    val editable = status.root && status.installed && t.supported && status.cfg.isNotEmpty()
    // 状态、原厂表、换算表都到齐再画：原来先按空状态画一遍（1 档、截止 4460、「未打开」），
    // 0.3 秒后读到配置重画，换算表到了横轴又从电压跳成电量，进页面时会切换两下
    if (!ready || !loaded) return

    fun save(l: Vcut.Ladder) {
        draft = l
        scope.launch {
            if (!Vcut.setLadder(l)) {
                draft = null
                Toast.makeText(context, "操作失败，请检查 root 权限", Toast.LENGTH_SHORT).show()
            }
            onChanged()
        }
    }

    fun mid(i: Int) = ((ladder.lower(i) ?: 3750) + ladder.upper(i)) / 2
    fun range(i: Int) = ladder.lower(i)?.let { "$it–${ladder.upper(i)} mV" } ?: "< ${ladder.upper(i)} mV"
    /** 该档大约负责的电量段（按本机充电记录换算） */
    fun socRange(i: Int) = axis?.let { a ->
        val lo = ladder.lower(i)?.let { a.socOf(it.toFloat()).toInt() } ?: 0
        " · 约 $lo–${a.socOf(ladder.upper(i).toFloat()).toInt()}%"
    } ?: ""
    fun amps(ma: Int) = "%.1f".format(ma / 1000f)

    Column {
        if (!t.force) {
            Card(modifier = Modifier.firstCard()) {
                BasicComponent(
                    title = "「提升充电电流」未打开",
                    summary = "阶梯可以先调好，打开后按它强制电流",
                )
            }
        }

        // 选中档的读数
        Card(modifier = if (t.force) Modifier.firstCard() else Modifier.nextCard()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Summary("${range(sel)}${socRange(sel)}")
                    stock?.let { Summary("原厂 ${amps(Vcut.stockAt(it.second, mid(sel)))} A") }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    MiuixText(
                        text = "${amps(ladder.ma[sel])} A",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Summary("≈ ${(ladder.ma[sel] / 1000f * mid(sel) / 1000f).toInt()} W", Modifier.padding(bottom = 5.dp))
                }
                if (t.activeB && t.step > 0) Summary("正在强制：第 ${t.step} 档 ${amps(t.stepMa)} A")
            }
        }

        Card(modifier = Modifier.nextCard()) {
            Column(Modifier.padding(top = 8.dp)) {
                LadderChart(
                    ladder = ladder,
                    stock = stock?.second,
                    axis = axis,
                    selected = sel,
                    enabled = editable,
                    onSelect = { selected = it },
                    onChange = { draft = it },
                    onCommit = { draft?.let(::save) },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Summary("蓝线 = 你的阶梯")
                    Summary("虚线 = " + (stock?.first?.let(::tableName) ?: "原厂（未读到）"))
                }
            }
        }
    }

    // 取自 HyperMusicCover 的确认框写法（BatteryPage 同款）：会覆盖现有阶梯，先确认
    val stockL = stock?.let { stockLadder(it.second, ladder.cut) }
    WindowDialog(
        show = resetAsk,
        title = "恢复默认",
        summary = stockL?.let {
            "改回本机原厂值：" + it.ma.joinToString(" / ") { ma -> amps(ma) } + " A，拐点 " +
                it.nodes.joinToString(" / ") + " mV。截止线不变。"
        } ?: "没有读到本机的原厂表，无法恢复。",
        onDismissRequest = onResetDone,
    ) {
        val dismiss = LocalDismissState.current
        Row {
            TextButton(
                modifier = Modifier.weight(1f),
                text = "取消",
                onClick = { dismiss?.invoke() },
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                modifier = Modifier.weight(1f),
                text = "恢复",
                enabled = editable && stockL != null,
                onClick = {
                    onResetDone()
                    stockL?.let(::save)
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 原厂 FFC 表 → 阶梯：截止以下的拐点，电流取表里的值 */
private fun stockLadder(table: List<IntArray>, cut: Int): Vcut.Ladder {
    val rows = table.filter { it[0] < cut }
    return Vcut.Ladder(
        nodes = rows.map { it[0] },
        ma = listOf(table[0][1].coerceAtMost(Vcut.MA_CEIL)) + rows.map { it[2].coerceAtMost(Vcut.MA_CEIL) },
        cut = cut,
    )
}

/** ffc_para_25_35 → 原厂 FFC 表（25~35 ℃） */
private fun tableName(key: String): String {
    // 电芯厂商可在中间或末尾：ffc_para_atl_23_35 / ffc_para_25_35_atl
    val m = Regex("""(ffc|normal)_para_(?:[a-z]+_)?(\d+)_(\d+)(?:_[a-z]+)?""").matchEntire(key) ?: return key
    val kind = if (m.groupValues[1] == "ffc") "FFC" else "普通"
    return "原厂$kind（${m.groupValues[2]}~${m.groupValues[3]} ℃）"
}

@Composable
internal fun Summary(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    MiuixText(
        text = text,
        modifier = modifier,
        style = MiuixTheme.textStyles.body2,
        color = color ?: MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}
