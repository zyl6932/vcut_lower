package com.github.zyl6932.vcutlower.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.github.zyl6932.vcutlower.ui.screen.LadderPage
import com.github.zyl6932.vcutlower.ui.theme.AppTheme
import com.github.zyl6932.vcutlower.ui.util.rememberVcutStatus
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/** 充电增强 → 充电阶梯。与 ChargeActivity 相同的二级页面写法。 */
class LadderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val savedSettings = AppSettings.load(this)
        val themeMode = try {
            ColorSchemeMode.valueOf(savedSettings.themeMode)
        } catch (_: Exception) {
            ColorSchemeMode.System
        }

        setContent {
            AppTheme(themeMode = themeMode) {
                val holder = rememberVcutStatus()
                LadderPage(
                    status = holder.status,
                    ready = holder.checked,
                    isBlurEnabled = savedSettings.isBlurEnabled,
                    onChanged = holder.refresh,
                    onBack = { finish() },
                )
            }
        }
    }
}
