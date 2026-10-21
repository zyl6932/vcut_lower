package com.github.zyl6932.vcutlower.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.github.zyl6932.vcutlower.ui.screen.ChargeLogDetail
import com.github.zyl6932.vcutlower.ui.screen.ChargeLogPage
import com.github.zyl6932.vcutlower.ui.theme.AppTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * 充电增强 → 充电记录。与 ChargeActivity 相同的二级页面写法；不带参数是列表，
 * 带 [EXTRA_ID]（一次充电的开始时间）是那一次的详情——再开一层 Activity，系统转场与返回手势照常。
 */
class ChargeLogActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_LIVE = "live"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val savedSettings = AppSettings.load(this)
        val themeMode = try {
            ColorSchemeMode.valueOf(savedSettings.themeMode)
        } catch (_: Exception) {
            ColorSchemeMode.System
        }
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        val live = intent.getBooleanExtra(EXTRA_LIVE, false)

        setContent {
            AppTheme(themeMode = themeMode) {
                if (id != 0L) {
                    ChargeLogDetail(
                        id = id,
                        live = live,
                        isBlurEnabled = savedSettings.isBlurEnabled,
                        onBack = { finish() },
                    )
                } else {
                    ChargeLogPage(
                        isBlurEnabled = savedSettings.isBlurEnabled,
                        onOpen = { s ->
                            startActivity(
                                Intent(this, ChargeLogActivity::class.java)
                                    .putExtra(EXTRA_ID, s.id)
                                    .putExtra(EXTRA_LIVE, s.live)
                            )
                        },
                        onBack = { finish() },
                    )
                }
            }
        }
    }
}
