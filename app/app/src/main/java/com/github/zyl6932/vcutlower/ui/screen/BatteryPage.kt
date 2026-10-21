package com.github.zyl6932.vcutlower.ui.screen

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.zyl6932.vcutlower.root.Vcut
import com.github.zyl6932.vcutlower.ui.util.PageScaffold
import com.github.zyl6932.vcutlower.ui.util.ValueSlider
import com.github.zyl6932.vcutlower.ui.util.nextCard
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

/** 电压预设：名字 → 电压闸（fw 同值，判空 sw = 闸 - 100） */
private val PRESETS = listOf("保守" to 2950, "中等" to 2800, "激进" to 2620)

/** 需要重新加载或重启才能生效的改动：确认后当场执行；取消则什么都不写，开关保持实际状态。 */
private class Pending(
    val title: String,
    val summary: String,
    val action: String,
    val run: suspend () -> Boolean,
)

/**
 * 电池解容页（功能 → 电池解容）。★开关与实际功能始终一致（2026-10-21）：
 *   - 显示的是正在运行的实际状态（[Vcut.actualOf]），不是 config.sh；
 *   - 能即时生效的（电压、接管电量显示、打开伪装循环、打开抬高满充电压）点了就生效，同时写配置；
 *   - 不能即时生效的（运行模式、关闭伪装循环需重新加载；关闭抬高满充电压需重启）弹确认框，
 *     确认就当场重新加载/重启，取消就不写配置——没有「稍后」，不会出现开关已变、功能没变。
 */
@Composable
fun BatteryPage(
    status: Vcut.Status,
    isBlurEnabled: Boolean,
    onChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val a = Vcut.actualOf(status)
    // 拖动滑块时的电压，松手才写入；为 null 时显示实际生效的值
    var dragging by remember { mutableStateOf<Int?>(null) }
    // 正在执行的操作（如抬高满充电压要断充约 11 秒），期间整页不可操作
    var busy by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    // 电量计接口只在 17 Pro Max 的 bq27z561 驱动上逆向确认过"纯 RAM"
    val fgVerified = Build.DEVICE == "popsicle"
    val enabled = a.editable && busy == null
    val voltageEnabled = enabled && a.full

    fun run(label: String, op: suspend () -> Boolean) {
        busy = label
        scope.launch {
            if (!op()) Toast.makeText(context, "操作失败，请检查 root 权限", Toast.LENGTH_SHORT).show()
            busy = null
            onChanged()
        }
    }

    // 页面级的说明：模块不在可操作状态时，开关为什么是灰的
    val notice = when {
        busy != null -> busy
        status.reloading -> "正在重新加载模块，完成后可操作"
        !status.loaded -> "模块未加载，开关显示的是配置文件中的值"
        !a.editable -> "模块尚未通过校验，暂不可操作"
        else -> null
    }

    PageScaffold(
        title = "电池解容",
        isBlurEnabled = isBlurEnabled,
        onBack = onBack,
    ) {
        item(key = "features") {
            Column {
                SmallTitle(text = "运行模式")
                Card(
                    modifier = Modifier.nextCard()
                ) {
                    WindowDropdownPreference(
                        title = "运行模式",
                        summary = notice ?: if (a.full) "正常：按下面的设置降低关机电压"
                        else "观测：只读不写，关机电压保持原厂",
                        items = listOf("正常", "观测"),
                        selectedIndex = if (a.full) 0 else 1,
                        enabled = enabled,
                        onSelectedIndexChange = { i ->
                            val full = i == 0
                            if (full != a.full) pending = Pending(
                                title = if (full) "切换到正常模式" else "切换到观测模式",
                                summary = "运行模式要重新加载模块才能生效，约需 30~90 秒，期间关机电压暂时恢复原厂。",
                                action = "重新加载",
                                run = { Vcut.applyWithReload(mapOf("MODE" to if (full) "full" else "probe")) },
                            )
                        },
                    )
                }

                SmallTitle(text = "关机电压")
                Card(
                    modifier = Modifier.nextCard()
                ) {
                    val delay = dragging ?: a.delay
                    val presetIndex = PRESETS.indexOfFirst { it.second == a.delay }
                        .let { if (it < 0) PRESETS.size else it }
                    WindowDropdownPreference(
                        title = "预设",
                        summary = if (a.full) "电池老化明显时请用保守档" else "观测模式下关机电压保持原厂",
                        items = PRESETS.map { "${it.first} ${it.second} mV" } + "自定义",
                        selectedIndex = presetIndex,
                        enabled = voltageEnabled,
                        onSelectedIndexChange = { i ->
                            if (i < PRESETS.size) run("正在应用…") { Vcut.setVoltage(PRESETS[i].second) }
                        },
                    )
                    ValueSlider(
                        title = "关机电压闸",
                        summary = "均值电压 ≤ 此值即关机，判空阈值 ${(delay - 100).coerceAtLeast(Vcut.FLOOR)} mV",
                        value = delay.toFloat(),
                        valueRange = Vcut.FLOOR.toFloat()..Vcut.CEIL.toFloat(),
                        enabled = voltageEnabled,
                        detent = 3000f,
                        label = { "${it.roundToInt()} mV" },
                        onValueChange = { dragging = (it / 10f).roundToInt() * 10 },
                        onValueChangeFinished = {
                            val v = dragging
                            dragging = null
                            if (v != null && v != a.delay) run("正在应用…") { Vcut.setVoltage(v) }
                        },
                    )
                }

                SmallTitle(text = "功能")
                Card(
                    modifier = Modifier.nextCard()
                ) {
                    SwitchPreference(
                        title = "接管电量显示",
                        summary = "百分比按同一 mAh 刻度均匀下降，到截止附近才交还关机",
                        checked = a.socCtl,
                        enabled = enabled,
                        onCheckedChange = { on -> run("正在应用…") { Vcut.setSocCtl(on) } },
                    )
                    SwitchPreference(
                        title = "伪装循环次数",
                        summary = if (a.fakeCyclePaused) "已暂停：本次开机循环偏移未确认，重启后恢复"
                        else "只改 strategy 内存，不碰电量计固件",
                        checked = a.fakeCycle,
                        enabled = enabled,
                        onCheckedChange = { on ->
                            pending = when {
                                // 参数已开、只是偏移没确认：再写参数没用，要重启重新确认
                                on && a.fakeCyclePaused -> Pending(
                                    title = "恢复伪装循环次数",
                                    summary = "本次开机没能确认循环次数在结构体里的位置，为安全起见没有写入。重启手机后会重新确认。",
                                    action = "重启",
                                    run = { Vcut.applyWithReboot(mapOf("FAKE_CYCLE" to "1")) },
                                )
                                on -> null.also { run("正在应用…") { Vcut.enableFakeCycle() } }
                                else -> Pending(
                                    title = "关闭伪装循环次数",
                                    summary = "要重新加载模块才能把循环次数写回真实值，约需 30~90 秒，期间关机电压暂时恢复原厂。",
                                    action = "重新加载",
                                    run = { Vcut.applyWithReload(mapOf("FAKE_CYCLE" to "-1")) },
                                )
                            }
                        },
                    )
                    SwitchPreference(
                        title = "抬高满充电压",
                        summary = if (fgVerified) "抬回被老化策略压低的满充电压"
                        else "写电量计接口，仅在小米 17 Pro Max 上验证过",
                        checked = a.fakeFg,
                        enabled = enabled,
                        onCheckedChange = { on ->
                            if (on) {
                                run("正在应用，会短暂断开充电约 11 秒…") { Vcut.enableFakeFg() }
                            } else {
                                pending = Pending(
                                    title = "关闭抬高满充电压",
                                    summary = "电量计里的伪装循环数只有重启才能清除，需要立即重启手机。",
                                    action = "重启",
                                    run = { Vcut.applyWithReboot(mapOf("FAKE_FG_CYCLE" to "-1")) },
                                )
                            }
                        },
                    )
                }

                // 写法取自 HyperMusicCover 捐赠页底部的说明文字
                MiuixText(
                    text = "本模块只放宽软件关机电压，电量计固件与 PMIC 硬件过放保护完全不碰。" +
                        "反复深放会加速电池老化、有析锂风险，低温不要深放。",
                    modifier = Modifier.padding(horizontal = 28.dp).padding(bottom = 12.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    // 取自 HyperMusicCover 首页的重启确认框；只有「取消」和立即执行，没有「稍后」
    val p = pending
    WindowDialog(
        show = p != null,
        title = p?.title.orEmpty(),
        summary = p?.summary.orEmpty(),
        onDismissRequest = { pending = null },
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
                text = p?.action.orEmpty(),
                onClick = {
                    pending = null
                    if (p != null) run("正在${p.action}…", p.run)
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
