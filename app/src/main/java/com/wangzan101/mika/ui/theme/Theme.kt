package com.wangzan101.mika.ui.theme

import android.content.Context
import android.provider.Settings
import android.view.View
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ThemeAccent
import com.wangzan101.mika.settings.ThemeMode

/** Motion: durations and curves. When system animations are off ([MikaTheme.reduceMotion]) it settles instantly. */
object MikaMotion {
    const val PAGE_MS = 220
    const val SWITCH_MS = 200
    /** Deceleration curve: starts fast, lands slow. */
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    fun <T> page(reduceMotion: Boolean): AnimationSpec<T> = if (reduceMotion) snap() else tween(PAGE_MS, easing = Decelerate)
    fun <T> switch(reduceMotion: Boolean): AnimationSpec<T> = if (reduceMotion) snap() else tween(SWITCH_MS, easing = Decelerate)
}

/** True when the system has "Remove animations" on (animator duration scale is 0). */
fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

val LocalReduceMotion = staticCompositionLocalOf { false }

object MikaTheme {
    val colors: MikaColors
        @Composable @ReadOnlyComposable get() = LocalMikaColors.current

    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

/**
 * The appearance from settings: when set to follow the system, use the system; when forced light/dark, obey the setting. A settings change (from the settings page or a background task) switches immediately without rebuilding the UI,
 * so pages, input and scroll positions are all preserved.
 */
@Composable
fun appearanceDark(): Boolean {
    val context = LocalContext.current
    val flags = remember(context) { FeatureFlags.get(context) }
    val mode by remember(flags) { flags.themeModeChanges() }.collectAsState(flags.themeMode())
    return when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

/** The accent color from settings (see [ThemeAccent]); like the appearance, changes apply immediately without rebuilding the UI. */
@Composable
fun appearanceAccent(): ThemeAccent {
    val context = LocalContext.current
    val flags = remember(context) { FeatureFlags.get(context) }
    val accent by remember(flags) { flags.themeAccentChanges() }.collectAsState(flags.themeAccent())
    return accent
}

/** Status bar and navigation bar icons match the interface's light/dark mode: when the UI is forced light but the system is dark, the icons must also be dark to be visible. */
fun ComponentActivity.systemBarsFor(dark: Boolean) {
    val transparent = android.graphics.Color.TRANSPARENT
    // On older systems the navigation bar can't be fully transparent, so a translucent layer is placed underneath; the values match the enableEdgeToEdge defaults
    val lightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
    val darkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    enableEdgeToEdge(
        statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
        navigationBarStyle = if (dark) SystemBarStyle.dark(darkScrim) else SystemBarStyle.light(lightScrim, darkScrim),
    )
}

/**
 * Light/dark mode follows the appearance from settings ([appearanceDark]), and the accent follows the accent from settings ([appearanceAccent]; when the background image is set to follow the image, the image's dominant color is used);
 * at the same time, the colors of Material components (dialogs, text fields, bottom sheets) are matched.
 */
@Composable
fun MikaTheme(
    dark: Boolean = appearanceDark(),
    accent: ThemeAccent = appearanceAccent(),
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    // When the background image is set to "accent follows image", the image's dominant color replaces the accent chosen in settings
    val imageHue = appearanceSkin().accentHue
    val colors = remember(dark, accent, imageHue) {
        val base = palette(dark, accent)
        if (imageHue == null) base else SkinAccent.tone(imageHue, dark).let { (color, on) -> base.withAccent(color, on) }
    }
    val materialColors = remember(colors) { scheme(colors) }
    val layoutDirection = if (LocalContext.current.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalMikaColors provides colors,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = materialColors) {
            CompositionLocalProvider(LocalContentColor provides colors.ink, content = content)
        }
    }
}

private fun scheme(c: MikaColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.accent,
        secondary = c.accent, onSecondary = c.onAccent,
        background = c.bg, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink, surfaceVariant = c.fill, onSurfaceVariant = c.ink2,
        surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface, surfaceTint = c.surface,
        outline = c.ink3, outlineVariant = c.hair,
        // Text on the error color doesn't follow the accent (the dark graphite accent has dark text on it)
        error = c.bad, onError = Color.White,
    )
}
