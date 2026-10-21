package com.github.zyl6932.vcutlower.ui.screen

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.github.zyl6932.vcutlower.root.Vcut
import com.github.zyl6932.vcutlower.ui.util.PageScaffold
import com.github.zyl6932.vcutlower.ui.util.nextCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 充电增强页（功能 → 充电增强），守护进程见 ksumodule/vcut_turbo.sh（由 TurboCharge 并入）。
 *
 * 与电池解容页同一条规则：开关显示实际状态（配置开 且 守护进程在跑），改动即时生效
 * ——写配置后立即重启守护进程。「开关打开」不等于「此刻在生效」：没在充电或护栏超限时
 * 守护进程会撤回，实时状态卡如实显示原因。
 *
 * 实时状态（生效中 / 待命原因 / 两路温度）显示在主页的电池卡片里（ChargeStatus.kt）。
 * 护栏调节（电量 / 电压 / 三路温度上限）的界面待定，暂未放出。
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
    var busy by remember { mutableStateOf(false) }
    // 节点与护栏只在 17 Pro Max 上验证过
    val verified = Build.DEVICE == "popsicle"
    val enabled = status.root && status.installed && t.supported && !busy

    fun toggle(key: String, on: Boolean) {
        busy = true
        scope.launch {
            if (!Vcut.setTurbo(key, on)) {
                Toast.makeText(context, "操作失败，请检查 root 权限", Toast.LENGTH_SHORT).show()
            }
            // 新守护进程启动后要跑完第一轮才写出状态
            delay(1500)
            busy = false
            onChanged()
        }
    }

    PageScaffold(
        title = "充电增强",
        isBlurEnabled = isBlurEnabled,
        onBack = onBack,
    ) {
        item(key = "charge") {
            Column {
                SmallTitle(text = "充电增强")
                Card(
                    modifier = Modifier.nextCard()
                ) {
                    SwitchPreference(
                        title = "摘除有线热限流",
                        summary = when {
                            !t.supported -> "本机没有对应的控制节点，不支持"
                            verified -> "亮屏发热时不被砍到 2 A"
                            else -> "亮屏发热时不被砍到 2 A（仅在小米 17 Pro Max 上验证过）"
                        },
                        checked = t.thermalRemove,
                        enabled = enabled,
                        onCheckedChange = { toggle("ENABLE_THERMAL_REMOVE", it) },
                    )
                    SwitchPreference(
                        title = "提升充电电流",
                        summary = when {
                            !t.supported -> "本机没有对应的控制节点，不支持"
                            verified -> "强制提高充电电流上限，绕过 jeita 瓶颈"
                            else -> "强制提高充电电流上限（仅在小米 17 Pro Max 上验证过）"
                        },
                        checked = t.force,
                        enabled = enabled,
                        onCheckedChange = { toggle("ENABLE_FORCE", it) },
                    )
                }
            }
        }
    }
}
