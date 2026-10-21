package com.github.zyl6932.vcutlower.ui.util

// 原样取自 HyperMusicCover（FeaturesPage.kt 的 ValueSlider），只把 label 默认值改成必填

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ValueSlider(
    title: String,
    summary: String? = null,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    detent: Float? = null,
    label: (Float) -> String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        BasicComponent(
            title = title,
            summary = summary,
            enabled = enabled,
            endActions = {
                MiuixText(
                    text = label(value),
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            },
        )
        Slider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            enabled = enabled,
            // Step is what makes a key point produce a tick at all; the default effect only fires
            // at the two ends of the track (SliderHapticEffect.Edge), and those ends keep firing
            // either way, because the edge haptic is played before the key point is looked at.
            hapticEffect = if (detent != null) SliderDefaults.SliderHapticEffect.Step
                           else SliderDefaults.DefaultHapticEffect,
            keyPoints = detent?.let { listOf(it) },
            // The library's magnet would pull the value onto the key point from 2% of the range
            // away, which is a snap rather than a tick, and it would take the values just either
            // side of the detent out of what this slider can be set to. Off, deliberately: the
            // detent is there to be felt, not to stop the finger short.
            magnetThreshold = 0f,
        )
    }
}
