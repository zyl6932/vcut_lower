package com.github.zyl6932.vcutlower

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.github.zyl6932.vcutlower.app.AppNightMode
import com.github.zyl6932.vcutlower.app.AppSettings
import com.github.zyl6932.vcutlower.app.LaunchBackground
import com.github.zyl6932.vcutlower.ui.component.liquid.IosLiquidGlassNavigationBar
import com.github.zyl6932.vcutlower.ui.screen.AboutPageContent
import com.github.zyl6932.vcutlower.ui.screen.FeaturesPage
import com.github.zyl6932.vcutlower.ui.screen.HomePage
import com.github.zyl6932.vcutlower.ui.screen.SettingsPage
import com.github.zyl6932.vcutlower.ui.theme.AppTheme
import com.github.zyl6932.vcutlower.ui.util.PagerNavigationSpringSpec
import com.github.zyl6932.vcutlower.ui.util.isInDarkTheme
import com.github.zyl6932.vcutlower.ui.util.rememberVcutStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 结构取自 HyperMusicCover 的 MainActivity：主题在 ThemeActivity 里改，回到这里时
 * onResume 让 settingsEpoch 变化、重新读一遍；onStop 才应用夜间模式（它会重建 Activity）。
 */
class MainActivity : ComponentActivity() {

    private var settingsEpoch by mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        settingsEpoch++
    }

    override fun onStop() {
        super.onStop()
        AppNightMode.apply(this, AppSettings.load(this).themeMode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false

        val savedSettings = AppSettings.load(this)
        LaunchBackground.peek(this, savedSettings.themeMode)?.let {
            window.setBackgroundDrawable(ColorDrawable(it))
        }
        AppNightMode.apply(this, savedSettings.themeMode)

        setContent {
            val settings = remember(settingsEpoch) { AppSettings.load(this@MainActivity) }
            val themeMode = try {
                ColorSchemeMode.valueOf(settings.themeMode)
            } catch (_: Exception) {
                ColorSchemeMode.System
            }
            AppTheme(themeMode = themeMode) {
                val surface = MiuixTheme.colorScheme.surface
                LaunchedEffect(surface, themeMode) {
                    LaunchBackground.remember(this@MainActivity, themeMode.name, surface.toArgb())
                }
                MainScreen(
                    isFloatingNavbar = settings.isFloatingNavbar,
                    isLiquidGlass = settings.isLiquidGlass,
                    isBlurEnabled = settings.isBlurEnabled,
                )
            }
        }
    }
}

@Composable
private fun MainScreen(
    isFloatingNavbar: Boolean,
    isLiquidGlass: Boolean,
    isBlurEnabled: Boolean,
) {
    // 各页共用一份模块状态（只在界面可见时轮询）
    val holder = rememberVcutStatus()
    val status = holder.status
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(pageCount = { 4 })
    var selectedIndex by remember { mutableIntStateOf(0) }
    var isNavigating by remember { mutableStateOf(false) }
    var navJob by remember { mutableStateOf<Job?>(null) }
    // 与 HyperMusicCover 相同的四个标签页与图标
    val items = listOf("主页", "功能", "设置", "关于")
    val icons = listOf(MiuixIcons.Home, MiuixIcons.Tune, MiuixIcons.Settings, MiuixIcons.Info)

    // 关于页的流光背景是逐帧动画，只在它是当前页时跑（取自 HyperMusicCover 的 isAboutCurrent）
    val onAboutPage = remember { derivedStateOf { pagerState.currentPage == 3 } }
    val isAboutCurrent: () -> Boolean = remember(onAboutPage) { { onAboutPage.value } }

    LaunchedEffect(pagerState.currentPage) {
        if (!isNavigating && selectedIndex != pagerState.currentPage) selectedIndex = pagerState.currentPage
    }

    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    val navBarMode = if (!isFloatingNavbar) 0 else if (!isLiquidGlass) 1 else 2

    // 点底栏时用 miuix 的弹簧动画滚过去（与 HyperMusicCover 相同）
    fun goToPage(index: Int) {
        if (index == selectedIndex) return
        navJob?.cancel()
        selectedIndex = index
        isNavigating = true
        navJob = scope.launch {
            val myJob = coroutineContext.job
            try {
                pagerState.scroll(MutatePriority.UserInput) {
                    val layoutInfo = pagerState.layoutInfo
                    val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
                    val currentDistanceInPages =
                        index - pagerState.currentPage - pagerState.currentPageOffsetFraction
                    val scrollPixels = currentDistanceInPages * pageSize
                    var previousValue = 0f
                    animate(
                        initialValue = 0f,
                        targetValue = scrollPixels,
                        animationSpec = PagerNavigationSpringSpec,
                    ) { currentValue, _ ->
                        previousValue += scrollBy(currentValue - previousValue)
                    }
                }
                if (pagerState.currentPage != index) {
                    pagerState.scrollToPage(index)
                }
            } finally {
                if (navJob == myJob) {
                    isNavigating = false
                    if (pagerState.currentPage != index) {
                        selectedIndex = pagerState.currentPage
                    }
                }
            }
        }
    }

    BackHandler(enabled = selectedIndex != 0) { goToPage(0) }

    Scaffold(
        bottomBar = {
            BottomNavigationBar(
                mode = navBarMode,
                items = items,
                icons = icons,
                selectedIndex = selectedIndex,
                backdrop = backdrop,
                blurActive = isBlurEnabled,
                onItemSelected = { index -> goToPage(index) },
            )
        }
    ) { globalPadding ->
        val navBarHeight = globalPadding.calculateBottomPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isBlurEnabled) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(surfaceColor)
        ) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                overscrollEffect = null,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> HomePage(status, holder.checked, isBlurEnabled, navBarHeight, onRefresh = holder.refresh)
                    1 -> FeaturesPage(isBlurEnabled, navBarHeight)
                    2 -> SettingsPage(status, isBlurEnabled, navBarHeight, onChanged = holder.refresh)
                    3 -> AboutPageContent(
                        isBlurEnabled = isBlurEnabled,
                        isCurrent = isAboutCurrent,
                    )
                }
            }
        }
    }
}

// ---- 以下原样取自 HyperMusicCover 的 MainActivity ----

@Composable
private fun BottomNavigationBar(
    mode: Int,
    items: List<String>,
    icons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    selectedIndex: Int,
    backdrop: LayerBackdrop?,
    blurActive: Boolean,
    onItemSelected: (Int) -> Unit,
) {
    when (mode) {
        2 -> {
            val navigationItems = remember(items, icons) {
                List(items.size) { i -> NavigationItem(items[i], icons[i]) }
            }
            IosLiquidGlassNavigationBar(
                modifier = Modifier.padding(horizontal = 12.dp),
                items = navigationItems,
                selectedIndex = selectedIndex,
                onItemClick = onItemSelected,
                backdrop = backdrop,
                isBlurActive = blurActive,
            )
        }

        1 -> {
            val floatingBarColor =
                if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer
            val floatingBarShape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius)
            val isDark = isInDarkTheme()
            val floatingHighlight = remember(isDark) {
                if (isDark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
            }
            FloatingNavigationBar(
                modifier = if (blurActive && backdrop != null) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = floatingBarShape,
                        blurRadius = 25f,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(
                                BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(0.4f)),
                            ),
                        ),
                        highlight = floatingHighlight,
                    )
                } else {
                    Modifier
                },
                color = floatingBarColor,
            ) {
                items.forEachIndexed { index, label ->
                    FloatingNavigationBarItem(
                        selected = selectedIndex == index,
                        onClick = { onItemSelected(index) },
                        icon = icons[index],
                        label = label,
                        enabled = true
                    )
                }
            }
        }

        else -> {
            val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
            Box(
                modifier = Modifier
                    .then(
                        if (blurActive && backdrop != null) {
                            Modifier.textureBlur(
                                backdrop = backdrop,
                                shape = RectangleShape,
                                blurRadius = 25f,
                                colors = BlurDefaults.blurColors(
                                    blendColors = listOf(
                                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.5f)),
                                    ),
                                ),
                            )
                        } else {
                            Modifier
                        }
                    )
                    .background(barColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
            ) {
                NavigationBar(color = barColor) {
                    items.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selectedIndex == index,
                            onClick = { onItemSelected(index) },
                            icon = icons[index],
                            label = label,
                            enabled = true
                        )
                    }
                }
            }
        }
    }
}
