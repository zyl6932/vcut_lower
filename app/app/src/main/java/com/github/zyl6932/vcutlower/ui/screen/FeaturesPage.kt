package com.github.zyl6932.vcutlower.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.github.zyl6932.vcutlower.app.BatteryActivity
import com.github.zyl6932.vcutlower.app.ChargeActivity
import com.github.zyl6932.vcutlower.ui.util.PageScaffold
import com.github.zyl6932.vcutlower.ui.util.firstCard
import com.github.zyl6932.vcutlower.ui.util.nextCard
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 功能标签页：一张卡一个功能，各自打开一个二级页面。
 * 结构照搬 HyperMusicCover 的 FeatureList：卡片里一行 ArrowPreference，点进去是 Activity——
 * 转场、返回手势和预测性返回动画都由系统提供。
 */
@Composable
fun FeaturesPage(
    isBlurEnabled: Boolean,
    extraBottomPadding: Dp,
) {
    val context = LocalContext.current
    PageScaffold(
        title = "功能",
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
    ) {
        item(key = "features") {
            Column {
                Card(modifier = Modifier.firstCard()) {
                    ArrowPreference(
                        title = "电池解容",
                        summary = "降低关机电压、均匀电量显示、循环与满充电压",
                        onClick = { context.startActivity(Intent(context, BatteryActivity::class.java)) },
                    )
                }
                Card(modifier = Modifier.nextCard()) {
                    ArrowPreference(
                        title = "充电增强",
                        summary = "亮屏快充、提升充电电流，带温度与电量护栏",
                        onClick = { context.startActivity(Intent(context, ChargeActivity::class.java)) },
                    )
                }
            }
        }
    }
}
