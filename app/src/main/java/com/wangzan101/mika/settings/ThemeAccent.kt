package com.wangzan101.mika.settings

/**
 * Accent colors (primary buttons, selections, switches, user messages, links): blue by default, freely combinable with light or dark ([ThemeMode]).
 * Green, orange and red are not offered: they already mean success, needs your attention, and error. The values for each color are in `palette` in `ui/theme/Colors.kt`.
 */
enum class ThemeAccent {
    Blue, Indigo, Purple, Pink, Teal, Graphite;

    companion object {
        fun parse(s: String?): ThemeAccent = entries.firstOrNull { it.name == s } ?: Blue
    }
}
