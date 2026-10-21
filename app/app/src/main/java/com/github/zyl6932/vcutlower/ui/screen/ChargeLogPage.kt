package com.github.zyl6932.vcutlower.ui.screen

import android.widget.Toast
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.github.zyl6932.vcutlower.root.Vcut
import com.github.zyl6932.vcutlower.ui.util.PageScaffold
import com.github.zyl6932.vcutlower.ui.util.firstCard
import com.github.zyl6932.vcutlower.ui.util.nextCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 充电增强 → 充电记录。守护进程每次充电（插上到拔掉）存一个文件到 /data/adb/vcut_lower/sessions，
 * 重启不丢、只留最近 30 次（见 vcut_turbo.sh 的 sess_*）。列表 → 点一次进详情（[ChargeLogDetail]）。
 * 只有开着「亮屏快充」或「提升充电电流」时守护进程才运行、才有记录。
 */
@Composable
fun ChargeLogPage(
    isBlurEnabled: Boolean,
    onOpen: (Vcut.Session) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Vcut.Session>?>(null) }
    var asking by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, reload) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                list = Vcut.sessions()
                delay(5000)
            }
        }
    }
    val sessions = list.orEmpty()
    val live = sessions.firstOrNull { it.live }

    PageScaffold(
        title = "充电记录",
        isBlurEnabled = isBlurEnabled,
        onBack = onBack,
        actions = {
            IconButton(onClick = { asking = true }, enabled = sessions.any { !it.live }) {
                Icon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = "清空记录",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    ) {
        item(key = "list") {
            Card(modifier = Modifier.firstCard()) {
                when {
                    list == null -> BasicComponent(title = "读取中…")
                    sessions.isEmpty() -> BasicComponent(
                        title = "还没有充电记录",
                        summary = "开着「亮屏快充」或「提升充电电流」时，每次充电自动记录，保留最近 30 次",
                    )
                    else -> sessions.forEach { s ->
                        ArrowPreference(
                            title = if (s.live) "充电中 · ${clock(s.start)} 开始" else dateTime(s.start),
                            // 只格式化数值本身：整句 format 时「97% →」里的 % 会被当成格式符（打开即崩）
                            summary = "${duration(s.end - s.start)} · ${s.soc0}% → ${s.soc1}% · 峰值 ${"%.0f".format(s.peakW)} W",
                            onClick = { onOpen(s) },
                        )
                    }
                }
            }
        }
    }

    // 取自 HyperMusicCover 的确认框写法（BatteryPage 同款）
    WindowDialog(
        show = asking,
        title = "清空记录",
        summary = "删除全部 ${sessions.count { !it.live }} 次充电记录" + (if (live != null) "（正在充的这次保留）" else "") + "。",
        onDismissRequest = { asking = false },
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
                text = "清空",
                onClick = {
                    asking = false
                    scope.launch {
                        if (!Vcut.clearSessions(live?.id)) {
                            Toast.makeText(context, "操作失败，请检查 root 权限", Toast.LENGTH_SHORT).show()
                        }
                        reload++
                    }
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 一次充电的详情：功率 / 电流两种视图的图、摘要、档位与护栏事件。正在充的每 5 秒刷新 */
@Composable
fun ChargeLogDetail(
    id: Long,
    live: Boolean,
    isBlurEnabled: Boolean,
    onBack: () -> Unit,
) {
    var log by remember { mutableStateOf<Pair<List<Vcut.Sample>, List<Vcut.Event>>?>(null) }
    var picked by remember { mutableIntStateOf(-1) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do {
                log = Vcut.sessionLog(id, live)
                if (live) delay(5000)
            } while (live)
        }
    }
    val (samples, events) = log ?: (emptyList<Vcut.Sample>() to emptyList())

    PageScaffold(
        title = if (live) "充电中" else dateTime(id),
        isBlurEnabled = isBlurEnabled,
        onBack = onBack,
    ) {
        item(key = "detail") {
            Column {
                if (samples.isEmpty()) {
                    Card(modifier = Modifier.firstCard()) {
                        BasicComponent(title = if (log == null) "读取中…" else "这次充电没有采样")
                    }
                    return@Column
                }
                val t0 = minOf(samples.first().t, events.firstOrNull()?.t ?: Int.MAX_VALUE)
                val s = samples[if (picked < 0) samples.lastIndex else picked.coerceAtMost(samples.lastIndex)]
                fun mmss(t: Int) = "%d:%02d".format((t - t0) / 60, (t - t0) % 60)

                // 按住图拖动时的读数
                Card(modifier = Modifier.firstCard()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Summary("${mmss(s.t)} · ${"%.2f".format(s.mv / 1000f)} V · " +
                            if (s.ma > 0) "第 ${s.step} 档 强制 %.1f A".format(s.ma / 1000f) else "原厂控制")
                        Row(verticalAlignment = Alignment.Bottom) {
                            MiuixText(
                                text = if (tab == 0) "%.1f W".format(watts(s)) else "%.1f A".format(amps(s)),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Summary(
                                (if (tab == 0) "%.1f A".format(amps(s)) else "%.1f W".format(watts(s))) +
                                    " · 电量 ${s.soc} % · ${"%.1f".format(s.btemp / 10f)} ℃",
                                Modifier.padding(bottom = 5.dp),
                            )
                        }
                    }
                }

                TabRow(
                    tabs = listOf("功率", "电流"),
                    selectedTabIndex = tab,
                    onTabSelected = { tab = it },
                    modifier = Modifier.nextCard(),
                )
                Card(modifier = Modifier.nextCard()) {
                    Column(Modifier.padding(top = 8.dp)) {
                        PowerChart(
                            samples = samples,
                            events = events,
                            t0 = t0,
                            picked = picked,
                            current = tab == 1,
                            onPick = { picked = if (it == samples.lastIndex) -1 else it },
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
                            Summary(if (tab == 0) "功率" else "实际电流", color = MiuixTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                            Summary(if (tab == 0) "电量" else "强制上限", color = Color(0xFFE07B00))
                        }
                    }
                }

                SmallTitle(text = "摘要")
                Card(modifier = Modifier.nextCard()) {
                    BasicComponent(title = "时长", summary = duration((samples.last().t - t0).toLong()))
                    BasicComponent(title = "电量", summary = "${samples.first().soc} % → ${samples.last().soc} %")
                    BasicComponent(
                        title = "功率",
                        summary = "峰值 %.1f W · 平均 %.1f W".format(
                            samples.maxOf { watts(it) }, samples.map { watts(it) }.average(),
                        ),
                    )
                    BasicComponent(title = "最高电芯温度", summary = "%.1f ℃".format(samples.maxOf { it.btemp } / 10f))
                    BasicComponent(title = "各档时长", summary = stepTimes(samples))
                }

                if (events.isNotEmpty()) {
                    SmallTitle(text = "档位 / 护栏")
                    Card(modifier = Modifier.nextCard()) {
                        events.forEach { e ->
                            BasicComponent(
                                title = prettyWhy(e.text),
                                endActions = { Summary(mmss(e.t)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 各档（含交还原厂的时段）累计时长；相邻采样隔太久（重启、守护进程停过）的那段不计 */
private fun stepTimes(samples: List<Vcut.Sample>): String {
    val sec = LinkedHashMap<String, Long>()
    for (i in 0 until samples.lastIndex) {
        val dt = (samples[i + 1].t - samples[i].t).toLong()
        if (dt <= 0 || dt > 30) continue
        val k = if (samples[i].ma > 0) "第 ${samples[i].step} 档" else "原厂"
        sec[k] = (sec[k] ?: 0) + dt
    }
    if (sec.isEmpty()) return "--"
    return sec.entries.sortedBy { it.key }.joinToString(" · ") { "${it.key} ${duration(it.value)}" }
}

private fun duration(sec: Long): String {
    val m = (sec / 60).coerceAtLeast(0)
    return if (m < 60) "$m 分钟" else "${m / 60} 小时 ${m % 60} 分"
}

private fun dateTime(epoch: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(epoch * 1000))

private fun clock(epoch: Long): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(epoch * 1000))
