package com.github.zyl6932.vcutlower.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.github.zyl6932.vcutlower.ui.screen.ChargePage
import com.github.zyl6932.vcutlower.ui.theme.AppTheme
import com.github.zyl6932.vcutlower.ui.util.rememberVcutStatus
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/** 功能 → 充电增强。独立 Activity，与 HyperMusicCover 功能页的二级页面相同（系统转场与返回手势）。 */
class ChargeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 与 LicenseActivity 相同：读一次主题与模糊设置
        val savedSettings = AppSettings.load(this)
        val themeMode = try {
            ColorSchemeMode.valueOf(savedSettings.themeMode)
        } catch (_: Exception) {
            ColorSchemeMode.System
        }

        setContent {
            AppTheme(themeMode = themeMode) {
                val holder = rememberVcutStatus()
                ChargePage(
                    status = holder.status,
                    isBlurEnabled = savedSettings.isBlurEnabled,
                    onChanged = holder.refresh,
                    onBack = { finish() },
                )
            }
        }
    }
}
