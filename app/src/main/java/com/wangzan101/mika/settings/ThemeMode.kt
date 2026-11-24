package com.wangzan101.mika.settings

/** UI color scheme: follow the system (default), always light, or always dark. */
enum class ThemeMode {
    System, Light, Dark;

    companion object {
        fun parse(s: String?): ThemeMode = entries.firstOrNull { it.name == s } ?: System
    }
}
