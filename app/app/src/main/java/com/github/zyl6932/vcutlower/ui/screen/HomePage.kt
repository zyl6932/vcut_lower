package com.github.zyl6932.vcutlower.ui.screen

import android.os.Build
import android.provider.Settings
import androidx.compose.ui.res.stringResource
import com.github.zyl6932.vcutlower.R
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.zyl6932.vcutlower.root.Vcut
import com.github.zyl6932.vcutlower.root.Unlock
import com.github.zyl6932.vcutlower.root.Vcut.Health
import com.github.zyl6932.vcutlower.ui.util.PageScaffold
import com.github.zyl6932.vcutlower.ui.util.firstCard
import com.github.zyl6932.vcutlower.ui.util.isInDarkTheme
import com.github.zyl6932.vcutlower.ui.util.nextCard
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun HomePage(
    status: Vcut.Status,
    checked: Boolean,
    isBlurEnabled: Boolean,
    extraBottomPadding: Dp,
    onRefresh: () -> Unit,
) {
    // 设备信息三行取自 HyperMusicCover 首页：只问一次，不随刷新重算
    val context = LocalContext.current
    val deviceModel = remember { Build.MODEL.ifEmpty { "未知" } }
    val deviceName = remember(context, deviceModel) {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: deviceModel
    }
    var hyperOSVersion by remember { mutableStateOf("加载中…") }
    LaunchedEffect(Unit) {
        hyperOSVersion = withContext(Dispatchers.IO) { SystemVersion.hyperOs("未知") }
    }

    PageScaffold(
        title = "主页",
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
    ) {
        item(key = "status") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .firstCard()
            ) {
                val darkTheme = isInDarkTheme()
                val dynamicColor = MiuixTheme.isDynamicColor
                val h = status.health
                // 观测模式与等待校验不是故障，和运行中一样用绿卡，只是字不同
                val ok = h == Health.RUNNING || h == Health.PROBE || h == Health.WAITING || h == Health.RELOADING
                val statusColor = if (!ok) {
                    when {
                        dynamicColor -> MiuixTheme.colorScheme.errorContainer
                        darkTheme -> Color(0xFF3D1C1C)
                        else -> Color(0xFFFDE8E8)
                    }
                } else {
                    when {
                        dynamicColor -> MiuixTheme.colorScheme.secondaryContainer
                        darkTheme -> Color(0xFF1A3825)
                        else -> Color(0xFFDFFAE4)
                    }
                }
                val iconTint = if (!ok) {
                    if (dynamicColor) MiuixTheme.colorScheme.error.copy(alpha = 0.8f)
                    else Color(0xFFDC3545)
                } else {
                    if (dynamicColor) MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
                    else Color(0xFF36D167)
                }
                val titleText = when (h) {
                    Health.NO_ROOT -> "未获得 root 权限"
                    Health.NOT_INSTALLED -> "模块未安装"
                    Health.DISABLED -> "模块已停用"
                    Health.RELOADING -> "正在重新加载"
                    Health.NOT_LOADED -> "模块未加载"
                    Health.WAITING -> "等待首次校验"
                    Health.INVALID -> "校验未通过"
                    Health.PROBE -> "观测模式"
                    Health.RUNNING -> "运行中"
                }
                val lineTwo = when {
                    !checked -> "正在检查…"
                    h == Health.NO_ROOT -> "请在 KernelSU 管理器 → 超级用户 中允许本应用"
                    h == Health.NOT_INSTALLED -> "请先在 KernelSU 中刷入 vcut_lower 模块"
                    h == Health.DISABLED -> "开机不稳定已自动停用，可在「设置」中重新加载"
                    h == Health.RELOADING -> "约需 30~90 秒，完成后自动刷新"
                    h == Health.NOT_LOADED -> "可在「设置」中重新加载"
                    h == Health.WAITING -> "目标函数约 10 秒调用一次，稍候自动完成"
                    h == Health.INVALID -> "偏移与当前系统不符，已拒绝写入"
                    h == Health.PROBE -> "只读不写，关机电压保持原厂"
                    else -> status.version
                }
                // 左下角写名字，与 HyperMusicCover 状态卡相同（stringResource(R.string.app_name)）
                val workingMode = if (ok) stringResource(R.string.app_name) else null

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.defaultColors(color = statusColor),
                        onClick = onRefresh,
                        showIndication = true,
                        pressFeedbackType = PressFeedbackType.Tilt
                    ) {
                        Box {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset(27.dp, 31.dp),
                                contentAlignment = Alignment.BottomEnd
                            ) {
                                Icon(
                                    modifier = Modifier.size(110.dp),
                                    imageVector = if (!ok) Icons.Rounded.ErrorOutline
                                    else Icons.Rounded.CheckCircleOutline,
                                    tint = iconTint,
                                    contentDescription = null
                                )
                            }
                            if (workingMode != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp, 10.dp),
                                    contentAlignment = Alignment.BottomStart,
                                ) {
                                    MiuixText(
                                        text = workingMode,
                                        style = MiuixTheme.textStyles.body1,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp, 14.dp),
                                contentAlignment = Alignment.TopStart,
                            ) {
                                Column {
                                    MiuixText(
                                        text = titleText,
                                        style = MiuixTheme.textStyles.title3,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(1.dp))
                                    MiuixText(
                                        text = lineTwo,
                                        style = MiuixTheme.textStyles.body2,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "battery") {
            Card(modifier = Modifier.nextCard()) {
                Column {
                    BasicComponent(title = "解锁容量", summary = unlockText(status))
                    BasicComponent(title = "显示电量", summary = status.displaySoc?.let { "$it%" } ?: "--")
                    BasicComponent(title = "均值电压", summary = status.meanMv?.let { "$it mV" } ?: "--")
                    BasicComponent(title = "电池温度", summary = status.tempC?.let { "%.1f ℃".format(it) } ?: "--")
                    BasicComponent(title = "充电增强", summary = turboStateText(Vcut.turboOf(status)))
                }
            }
        }

        item(key = "device") {
            Card(modifier = Modifier.nextCard()) {
                Column {
                    BasicComponent(title = "设备名称", summary = deviceName)
                    BasicComponent(title = "设备型号", summary = deviceModel)
                    BasicComponent(title = "系统版本", summary = hyperOSVersion)
                    BasicComponent(title = "内核版本", summary = status.kernel.ifEmpty { "--" })
                    BasicComponent(title = "模块版本", summary = status.version.ifEmpty { "未安装" })
                }
            }
        }
    }
}

/**
 * 解锁容量（见 [Unlock]）：有跨越原厂电压闸的实测记录用实测，没有按放电曲线估算。
 * 原厂电压闸取模块保存的原值（t_orig_delay），当前值取模块正在生效的电压闸。
 */
private fun unlockText(s: Vcut.Status): String {
    val a = Vcut.actualOf(s)
    val orig = s.pi("t_orig_delay")?.takeIf { it > 0 }
    return when {
        !s.loaded -> "--"
        !a.full -> "观测模式下未解锁"
        orig == null -> "等待模块首次校验…"
        else -> {
            val e = Unlock.estimate(
                orig, a.delay, a.fakeFg, s.fi("charger_full")?.div(1000),
                s.unlock + listOfNotNull(Unlock.live(s)),
            )
            "估算约增加 ${e.totalMah} mAh"
        }
    }
}

// 原样取自 HyperMusicCover 首页
private object SystemVersion {
    /** ro.* properties are the only place the HyperOS version is written down. */
    private fun prop(key: String): String = try {
        @Suppress("PrivateApi")
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, key, "") as? String).orEmpty()
    } catch (_: Throwable) {
        ""
    }

    /**
     * The incremental alone: it already starts with the release name (OS4.0.0.35.XPBCNXM), so
     * printing the name in front of it just stutters - "OS4.0 · OS4.0.0.35.XPBCNXM".
     */
    fun hyperOs(fallback: String): String {
        val incremental = prop("ro.mi.os.version.incremental").ifEmpty { Build.DISPLAY }
        if (incremental.isNotEmpty()) return incremental
        val name = prop("ro.mi.os.version.name").ifEmpty { prop("ro.miui.ui.version.name") }
        return name.ifEmpty { fallback }
    }
}
