package com.github.zyl6932.vcutlower.app
import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Settings that belong to the app itself - how the UI looks.
 * (VCut Lower: HyperMusicCover 的语言字段已去掉，本 App 只有中文)
 *
 * The module's own settings (cover bias, clock scale, ...) deliberately do NOT live here: they
 * belong to the hook inside SystemUI, which persists them itself and is asked for them through
 * [ModuleBridge]. Keeping a second copy here would just create two truths to disagree.
 */
data class AppSettings(
    val themeMode: String = "System",
    val isFloatingNavbar: Boolean = false,
    val isLiquidGlass: Boolean = false,
    val isBlurEnabled: Boolean = true,
) {
    fun toJson(): String = JSONObject().apply {
        put("themeMode", themeMode)
        put("isFloatingNavbar", isFloatingNavbar)
        put("isLiquidGlass", isLiquidGlass)
        put("isBlurEnabled", isBlurEnabled)
    }.toString(2)

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_FLOATING_NAVBAR = "floating_navbar"
        private const val KEY_LIQUID_GLASS = "liquid_glass"
        private const val KEY_BLUR_ENABLED = "blur_enabled"

        fun fromJson(json: String): AppSettings = try {
            val obj = JSONObject(json)
            AppSettings(
                themeMode = obj.optString("themeMode", "System"),
                isFloatingNavbar = obj.optBoolean("isFloatingNavbar", false),
                isLiquidGlass = obj.optBoolean("isLiquidGlass", false),
                isBlurEnabled = obj.optBoolean("isBlurEnabled", true),
            )
        } catch (_: Exception) {
            AppSettings()
        }

        fun load(context: Context): AppSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return AppSettings(
                themeMode = prefs.getString(KEY_THEME_MODE, "System") ?: "System",
                isFloatingNavbar = prefs.getBoolean(KEY_FLOATING_NAVBAR, false),
                isLiquidGlass = prefs.getBoolean(KEY_LIQUID_GLASS, false),
                isBlurEnabled = prefs.getBoolean(KEY_BLUR_ENABLED, true),
            )
        }

        fun save(context: Context, settings: AppSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_THEME_MODE, settings.themeMode)
                putBoolean(KEY_FLOATING_NAVBAR, settings.isFloatingNavbar)
                putBoolean(KEY_LIQUID_GLASS, settings.isLiquidGlass)
                putBoolean(KEY_BLUR_ENABLED, settings.isBlurEnabled)
            }
        }

        fun importFromJson(context: Context, json: String): AppSettings {
            val settings = fromJson(json)
            save(context, settings)
            return settings
        }
    }
}
