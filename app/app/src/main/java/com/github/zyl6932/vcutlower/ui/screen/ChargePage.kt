package com.github.zyl6932.vcutlower.ui.screen

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.github.zyl6932.vcutlower.app.ChargeLogActivity
import com.github.zyl6932.vcutlower.app.LadderActivity
import com.github.zyl6932.vcutlower.root.Vcut
import com.github.zyl6932.vcutlower.ui.util.FoldUnder
import com.github.zyl6932.vcutlower.ui.util.PageScaffold
import com.github.zyl6932.vcutlower.ui.util.ValueSlider
import com.github.zyl6932.vcutlower.ui.util.nextCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import kotlin.math.roundToInt

/**
 * 充电增强页（功能 → 充电增强），守护进程见 ksumodule/vcut_turbo.sh（由 TurboCharge 并入）。
 *
 * 与电池解容页同一条规则：开关显示实际状态（配置开 且 守护进程在跑），改动即时生效
 * ——写配置后立即重启守护进程。「开关打开」不等于「此刻在生效」：没在充电或护栏超限时
 * 守护进程会撤回，实时状态卡如实显示原因。
 *
 * 实时状态（生效中 / 待命原因 / 两路温度）显示在主页的电池卡片里（ChargeStatus.kt）。
 * 「提升充电电流」按充电阶梯强制（LadderActivity）；护栏滑条改的是 config.sh，写完让守护进程
 * 重新读（≤5 秒生效，不打断当前状态）。带载电压上限 = 阶梯截止线，在阶梯页里拖；23 ℃ 下限固定。
 */
@Composable
fun ChargePage(
    status: Vcut.Status,
    isBlurEnabled: Boolean,
    onChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = Vcut.turboOf(status)
    val enabled = status.root && status.installed && t.supported
    // 刚点过、守护进程还没确认的开关：先按点的方向显示（[shown]），只锁这一个（[locked]），其他项照常可用。
    // 原来整页 busy，切一个开关时其他项都跟着变灰 1 秒多；root 命令本来就在 RootShell 里排队，不需要锁整页。
    // 另一个开关的显示值也先固定住：重启守护进程时旧进程退出会写一次 run=off，正好读到的话另一个开关会闪成关。
    val shown = remember { mutableStateMapOf<String, Boolean>() }
    val locked = remember { mutableStateListOf<String>() }
    val thermalOn = shown["ENABLE_THERMAL_REMOVE"] ?: t.thermalRemove
    val forceOn = shown["ENABLE_FORCE"] ?: t.force
    // 读回的实际状态与显示一致就不再需要占位
    LaunchedEffect(t.thermalRemove, t.force) {
        for ((k, v) in listOf("ENABLE_THERMAL_REMOVE" to t.thermalRemove, "ENABLE_FORCE" to t.force)) {
            if (shown[k] == v) { shown.remove(k); locked.remove(k) }
        }
    }

    // 预读充电阶梯页要用的原厂表和换算表，点进去时直接画、不用等
    LaunchedEffect(Unit) {
        Vcut.stockCached()
        Vcut.vsocRefresh()
    }

    fun toggle(key: String, on: Boolean) {
        shown["ENABLE_THERMAL_REMOVE"] = thermalOn
        shown["ENABLE_FORCE"] = forceOn
        shown[key] = on
        locked += key
        scope.launch {
            if (!Vcut.setTurbo(key, on)) {
                Toast.makeText(context, "操作失败，请检查 root 权限", Toast.LENGTH_SHORT).show()
            }
            // 新守护进程启动后要跑完第一轮才写出状态（旧进程现在收到信号立刻退出，第一轮不到半秒）
            delay(700)
            onChanged()
            // 兜底：几秒后还没读回（失败或不支持）就撤掉占位，开关回到实际状态
            delay(3000)
            locked.remove(key)
            if (locked.isEmpty()) shown.clear()
        }
    }

    PageScaffold(
        title = "充电增强",
        isBlurEnabled = isBlurEnabled,
        onBack = onBack,
    ) {
        // 布局照 HyperMusicCover 的小窗页（MiniPlayerActivity）：总开关各自一张卡，只依赖它的设置用
        // FoldUnder 收在下面，关掉就收起而不是变灰。两个开关互不依赖（亮屏快充只摘热限流，电流仍按
        // 原厂阶梯；提升电流才用充电阶梯），所以分成两块；电池温度上限两个都管，两个都关才收起。
        val settled = status.root
        fun cfg(k: String, d: Int) = status.cfg[k]?.toIntOrNull() ?: d
        fun commit(k: String, v: Int) {
            scope.launch {
                if (!Vcut.setTurboValues(mapOf(k to "$v"))) {
                    Toast.makeText(context, "操作失败，请检查 root 权限", Toast.LENGTH_SHORT).show()
                }
                onChanged()
            }
        }

        // 分组照电池解容页：SmallTitle 分组标题 + 一张卡。两个开关同一组；依赖提升电流的收在它的组里，
        // 关掉就收起；两项共用的（温度上限、记录——记录由守护进程写，两个都关时它不运行）两个都关才收起
        item(key = "switches") {
            Column {
                SmallTitle(text = "功能")
                Card(modifier = Modifier.nextCard()) {
                    SwitchPreference(
                        title = "亮屏快充",
                        summary = if (!t.supported) "本机没有对应的控制节点，不支持" else null,
                        checked = thermalOn,
                        enabled = enabled && "ENABLE_THERMAL_REMOVE" !in locked,
                        onCheckedChange = { toggle("ENABLE_THERMAL_REMOVE", it) },
                    )
                    SwitchPreference(
                        title = "提升充电电流",
                        summary = if (!t.supported) "本机没有对应的控制节点，不支持" else null,
                        checked = forceOn,
                        enabled = enabled && "ENABLE_FORCE" !in locked,
                        onCheckedChange = { toggle("ENABLE_FORCE", it) },
                    )
                }
            }
        }
        item(key = "force_sub") {
            FoldUnder(forceOn, settled = settled) {
                Column {
                    SmallTitle(text = "提升充电电流")
                    Card(modifier = Modifier.nextCard()) {
                        ArrowPreference(
                            title = "充电阶梯",
                            enabled = enabled,
                            onClick = { context.startActivity(Intent(context, LadderActivity::class.java)) },
                        )
                        Guard(
                            title = "电量上限", summary = "达到后交还原厂，回落 ${cfg("HYST_SOC", 3)} % 后恢复",
                            stored = cfg("MAX_SOC", 80), range = 50f..100f, enabled = enabled,
                            toUi = { it.toFloat() }, fromUi = { it.roundToInt() }, label = { "${it.roundToInt()} %" },
                            onCommit = { commit("MAX_SOC", it) },
                        )
                    }
                }
            }
        }
        item(key = "common") {
            FoldUnder(thermalOn || forceOn, settled = settled) {
                Column {
                    SmallTitle(text = "通用")
                    Card(modifier = Modifier.nextCard()) {
                        Guard(
                            title = "电池温度上限", summary = null,
                            stored = cfg("MAX_BTEMP", 420), range = 35f..48f, enabled = enabled,
                            toUi = { it / 10f }, fromUi = { (it * 2).roundToInt() * 5 }, label = { "%.1f ℃".format(it) },
                            onCommit = { commit("MAX_BTEMP", it) },
                        )
                        ArrowPreference(
                            title = "充电记录",
                            onClick = { context.startActivity(Intent(context, ChargeLogActivity::class.java)) },
                        )
                    }
                }
            }
        }
    }
}

/** 护栏滑条：拖动时显示草稿，松手写配置；[stored] 是 config.sh 里的原始单位 */
@Composable
private fun Guard(
    title: String,
    summary: String?,
    stored: Int,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    toUi: (Int) -> Float,
    fromUi: (Float) -> Int,
    label: (Float) -> String,
    onCommit: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    ValueSlider(
        title = title,
        summary = summary,
        value = toUi(dragging ?: stored).coerceIn(range),
        valueRange = range,
        enabled = enabled,
        label = label,
        onValueChange = { dragging = fromUi(it) },
        onValueChangeFinished = {
            val v = dragging
            dragging = null
            if (v != null && v != stored) onCommit(v)
        },
    )
}
