package com.github.zyl6932.vcutlower

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.zyl6932.vcutlower.app.AppSettings
import com.github.zyl6932.vcutlower.root.Vcut
import com.github.zyl6932.vcutlower.ui.theme.AppTheme
import com.github.zyl6932.vcutlower.ui.util.PageScaffold
import com.github.zyl6932.vcutlower.ui.util.firstCard
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 开机日志与内核日志。独立页面而非 pager 里的一页，与 HyperMusicCover 的 LicenseActivity 同理：返回手势由系统提供。 */
class LogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 与 HyperMusicCover 的 LicenseActivity 相同：读一次主题与模糊设置
        val savedSettings = AppSettings.load(this)
        val themeMode = try {
            ColorSchemeMode.valueOf(savedSettings.themeMode)
        } catch (_: Exception) {
            ColorSchemeMode.System
        }
        val isBlurEnabled = savedSettings.isBlurEnabled

        setContent {
            AppTheme(themeMode = themeMode) {
                var text by remember { mutableStateOf("读取中…") }
                var reloadKey by remember { mutableIntStateOf(0) }
                LaunchedEffect(reloadKey) { text = Vcut.logs() }

                PageScaffold(
                    title = "日志",
                    isBlurEnabled = isBlurEnabled,
                    onBack = { finish() },
                    actions = {
                        IconButton(onClick = { reloadKey++ }) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "刷新",
                                tint = MiuixTheme.colorScheme.onBackground,
                            )
                        }
                    },
                ) {
                    item(key = "log") {
                        Card(modifier = Modifier.firstCard()) {
                            SelectionContainer {
                                MiuixText(
                                    text = text,
                                    modifier = Modifier.padding(16.dp),
                                    style = MiuixTheme.textStyles.footnote1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
