package com.github.zyl6932.vcutlower.ui.screen

import android.content.Intent
import android.os.Process
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.github.zyl6932.vcutlower.R
import com.github.zyl6932.vcutlower.app.ThemeActivity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.github.zyl6932.vcutlower.LogActivity
import com.github.zyl6932.vcutlower.root.Vcut
import com.github.zyl6932.vcutlower.ui.util.PageScaffold
import com.github.zyl6932.vcutlower.ui.util.nextCard
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File

@Composable
fun SettingsPage(
    status: Vcut.Status,
    isBlurEnabled: Boolean,
    extraBottomPadding: Dp,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled = status.root && status.installed

    var collecting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var diagPath by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }   // "reload" / "unload"

    fun share(path: String) = scope.launch {
        File(context.cacheDir, "share").mkdirs()
        val staged = Vcut.stageForShare(path, context.cacheDir.absolutePath, Process.myUid())
        if (staged == null) {
            Toast.makeText(context, "复制诊断包失败", Toast.LENGTH_SHORT).show()
            return@launch
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(staged))
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/gzip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "发送诊断包"))
    }

    PageScaffold(
        title = "设置",
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
    ) {
        item(key = "settings") {
            Column {
                // 取自 HyperMusicCover 设置页「界面」组：主题单独成页（ThemeActivity）
                SmallTitle(text = stringResource(R.string.settings_interface))
                Card(
                    modifier = Modifier.nextCard()
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_theme),
                        summary = stringResource(R.string.settings_theme_summary),
                        onClick = {
                            context.startActivity(Intent(context, ThemeActivity::class.java))
                        }
                    )
                }

                SmallTitle(text = "诊断")
                Card(
                    modifier = Modifier.nextCard()
                ) {
                    ArrowPreference(
                        title = "生成诊断包",
                        summary = "只读采集电池数据与模块状态，用于适配其他机型或排查问题",
                        enabled = enabled,
                        onClick = {
                            collecting = true
                            progress = "采集中，约需 1~2 分钟…"
                            scope.launch {
                                val path = Vcut.collect { line -> if (line.isNotBlank()) progress = line }
                                collecting = false
                                if (path == null) {
                                    Toast.makeText(context, "生成失败", Toast.LENGTH_SHORT).show()
                                } else {
                                    diagPath = path
                                }
                            }
                        },
                    )
                    ArrowPreference(
                        title = "查看日志",
                        summary = "开机日志与内核日志",
                        enabled = status.root,
                        onClick = { context.startActivity(Intent(context, LogActivity::class.java)) },
                    )
                }

                SmallTitle(text = "模块")
                Card(
                    modifier = Modifier.nextCard()
                ) {
                    ArrowPreference(
                        title = "重新加载模块",
                        summary = "按开机流程重新对账、加载并校验",
                        enabled = enabled,
                        onClick = { confirm = "reload" },
                    )
                    ArrowPreference(
                        title = "卸载模块",
                        summary = "立即恢复原厂关机电压与电量显示，重启后按配置重新加载",
                        enabled = enabled && status.loaded,
                        onClick = { confirm = "unload" },
                    )
                }

            }
        }
    }

    // ---- 生成中：不可关闭 ----
    WindowDialog(
        show = collecting,
        title = "生成诊断包",
        summary = progress,
        onDismissRequest = { },
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            InfiniteProgressIndicator()
        }
    }

    // ---- 生成完成 ----
    WindowDialog(
        show = diagPath != null,
        title = "诊断包已生成",
        summary = diagPath.orEmpty(),
        onDismissRequest = { diagPath = null },
    ) {
        val dismiss = LocalDismissState.current
        Row {
            TextButton(modifier = Modifier.weight(1f), text = "关闭", onClick = { dismiss?.invoke() })
            Spacer(Modifier.width(20.dp))
            TextButton(
                modifier = Modifier.weight(1f),
                text = "分享",
                onClick = {
                    val p = diagPath
                    diagPath = null
                    if (p != null) share(p)
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }

    // ---- 重新加载 / 卸载确认 ----
    WindowDialog(
        show = confirm != null,
        title = if (confirm == "unload") "卸载模块" else "重新加载模块",
        summary = if (confirm == "unload") "卸载时模块会写回原厂关机电压并交还电量显示。重启后按配置重新加载。"
        else "约需 30~90 秒，期间关机电压暂时恢复原厂。",
        onDismissRequest = { confirm = null },
    ) {
        val dismiss = LocalDismissState.current
        Row {
            TextButton(modifier = Modifier.weight(1f), text = "取消", onClick = { dismiss?.invoke() })
            Spacer(Modifier.width(20.dp))
            TextButton(
                modifier = Modifier.weight(1f),
                text = "确定",
                onClick = {
                    val what = confirm
                    confirm = null
                    scope.launch {
                        val ok = if (what == "unload") Vcut.unload() else Vcut.reload()
                        if (!ok) Toast.makeText(context, "操作失败", Toast.LENGTH_SHORT).show()
                        onChanged()
                    }
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
