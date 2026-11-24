package com.wangzan101.mika.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.wangzan101.mika.settings.ThemeAccent

/**
 * All the colors the interface uses. Pages always take them from [MikaTheme.colors] and never hardcode color values.
 * Solid colors are used only for emphasis (primary buttons, selection, switches, user messages) and status (success, needs-you, error); icon tiles are always neutral.
 */
@Immutable
class MikaColors(
    /** Page background. */
    val bg: Color,
    /** Cards, groups. */
    val surface: Color,
    /** Light fill: secondary buttons, segmented control background, code block background. */
    val fill: Color,
    /** Three levels of text: primary, secondary, tertiary. */
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    /** Between primary and tertiary: text slightly lighter than the body color (used for interjections rendered as small text). */
    val inkSoft: Color,
    /** Dividers. */
    val hair: Color,
    /** Card border. */
    val edge: Color,
    val accent: Color,
    val accentSoft: Color,
    /** Text and icons on top of the accent color. */
    val onAccent: Color,
    val ok: Color,
    val okSoft: Color,
    /** "Needs you": [warn] is used for text, [warnSolid] for dots and borders. */
    val warn: Color,
    val warnSolid: Color,
    val warnSoft: Color,
    val bad: Color,
    val badSoft: Color,
    /** Background of icon tiles. */
    val neutralSoft: Color,
    /** Background of Claire's answers: a gray slightly darker than the page background, distinguishing it from the user's blue bubble. */
    val answer: Color,
    /** Gray pills and switches that are off. */
    val greySoft: Color,
    /** Background and border of floating glass (the input bar, buttons floating above content). */
    val glass: Color,
    val glassEdge: Color,
    /** Card shadow; in dark mode cards rely only on the border, so this is transparent. */
    val cardShadow: Color,
    /** Shadow of floating elements. */
    val floatShadow: Color,
    /** The switch thumb. */
    val thumb: Color,
    /**
     * Category colors for charts (e.g. one per service provider), taken in a fixed order; anything beyond them falls back to [seriesOther].
     * Only graphics are colored with these; text always uses one of the three ink levels.
     */
    val series: List<Color>,
    val seriesOther: Color,
    /** Chart grid lines. */
    val grid: Color,
    val isDark: Boolean,
) {
    /** Swap in a different accent color, leaving everything else unchanged; the soft fill keeps its original opacity (10% in light, 16% in dark). */
    fun withAccent(accent: Color, onAccent: Color) = MikaColors(
        bg = bg, surface = surface, fill = fill, ink = ink, ink2 = ink2, ink3 = ink3, inkSoft = inkSoft, hair = hair, edge = edge,
        accent = accent, accentSoft = accent.copy(alpha = if (isDark) 0x29 / 255f else 0x1A / 255f), onAccent = onAccent,
        ok = ok, okSoft = okSoft, warn = warn, warnSolid = warnSolid, warnSoft = warnSoft, bad = bad, badSoft = badSoft,
        neutralSoft = neutralSoft, answer = answer, greySoft = greySoft, glass = glass, glassEdge = glassEdge,
        cardShadow = cardShadow, floatShadow = floatShadow, thumb = thumb, series = series, seriesOther = seriesOther,
        grid = grid, isDark = isDark,
    )
}

val LightColors = MikaColors(
    bg = Color(0xFFF7F7F8),
    surface = Color(0xFFFFFFFF),
    fill = Color(0x0A000000),
    ink = Color(0xFF1C1C1E),
    ink2 = Color(0xFF5F6068),
    ink3 = Color(0xFF9A9BA3),
    inkSoft = Color(0xFF4A4A50),
    hair = Color(0x12000000),
    edge = Color(0x0D000000),
    accent = Color(0xFF0A7CFF),
    accentSoft = Color(0x1A0A7CFF),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF1F9D55),
    okSoft = Color(0x1F1F9D55),
    warn = Color(0xFFB86E00),
    warnSolid = Color(0xFFFF9F0A),
    warnSoft = Color(0x1FFF9F0A),
    bad = Color(0xFFE5483D),
    badSoft = Color(0x1AE5483D),
    neutralSoft = Color(0x0B000000),
    answer = Color(0x09000000),
    greySoft = Color(0x1F767680),
    glass = Color(0xB8FFFFFF),
    glassEdge = Color(0x0F000000),
    cardShadow = Color(0xFF000000),
    floatShadow = Color(0xFF000000),
    thumb = Color(0xFFFFFFFF),
    series = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A)),
    seriesOther = Color(0xFFB4B5BC),
    grid = Color(0x0F000000),
    isDark = false,
)

val DarkColors = MikaColors(
    bg = Color(0xFF0E0E10),
    surface = Color(0xFF1A1A1D),
    fill = Color(0x0FFFFFFF),
    ink = Color(0xFFF2F2F4),
    ink2 = Color(0xFFA4A5AD),
    ink3 = Color(0xFF6C6D75),
    inkSoft = Color(0xFFC6C6CD),
    hair = Color(0x14FFFFFF),
    edge = Color(0x0FFFFFFF),
    accent = Color(0xFF3D97FF),
    accentSoft = Color(0x293D97FF),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF3CCF7A),
    okSoft = Color(0x243CCF7A),
    warn = Color(0xFFFFB340),
    warnSolid = Color(0xFFFF9F0A),
    warnSoft = Color(0x29FF9F0A),
    bad = Color(0xFFFF6961),
    badSoft = Color(0x24FF6961),
    neutralSoft = Color(0x12FFFFFF),
    answer = Color(0x0DFFFFFF),
    greySoft = Color(0x38767680),
    glass = Color(0xB81A1A1D),
    glassEdge = Color(0x14FFFFFF),
    cardShadow = Color.Transparent,
    floatShadow = Color(0xFF000000),
    thumb = Color(0xFFFFFFFF),
    series = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70)),
    seriesOther = Color(0xFF5A5B62),
    grid = Color(0x12FFFFFF),
    isDark = true,
)

/** Full color set for a given light/dark mode and accent. Blue is [LightColors] / [DarkColors] themselves; other accents only replace the three accent fields. */
fun palette(dark: Boolean, accent: ThemeAccent): MikaColors {
    val base = if (dark) DarkColors else LightColors
    if (accent == ThemeAccent.Blue) return base
    val (color, on) = accentTone(accent, dark)
    return base.withAccent(color, on)
}

/**
 * Values of an accent color in light / dark mode, along with the text and icon color on top of it.
 * Text contrast is no lower than the default blue, and contrast with the page background is no lower than 3:1 (see the `AccentPaletteTest`).
 */
fun accentTone(accent: ThemeAccent, dark: Boolean): Pair<Color, Color> = when (accent) {
    ThemeAccent.Blue -> (if (dark) DarkColors else LightColors).let { it.accent to it.onAccent }
    ThemeAccent.Indigo -> Color(if (dark) 0xFF7D7AFF else 0xFF5856D6) to Color.White
    ThemeAccent.Purple -> Color(if (dark) 0xFFBF5AF2 else 0xFF9B3FD9) to Color.White
    ThemeAccent.Pink -> Color(if (dark) 0xFFFF4F80 else 0xFFE0306A) to Color.White
    ThemeAccent.Teal -> Color(if (dark) 0xFF1E9E97 else 0xFF0A8F8A) to Color.White
    // Graphite in dark mode is light gray, so it pairs with dark text
    ThemeAccent.Graphite -> if (dark) Color(0xFFE5E5EA) to Color(0xFF1C1C1E) else Color(0xFF3A3A3C) to Color.White
}

/** Falls back to light colors when not wrapped in [MikaTheme] (e.g. in UI tests). */
val LocalMikaColors = staticCompositionLocalOf { LightColors }
