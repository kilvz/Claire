package com.wangzan101.mika.core.screen.uistate

/**
 * press_key only allows navigation and editing keys. Volume, power/sleep/wake, Home, recents, search/assistant,
 * camera and media keys act on the whole phone rather than on Claire's screen, so they are always rejected.
 * The shell process checks the same table again when it injects a key.
 */
object PressKeys {
    val BY_NAME: Map<String, Int> = linkedMapOf(
        "KEYCODE_DPAD_UP" to 19, "KEYCODE_DPAD_DOWN" to 20, "KEYCODE_DPAD_LEFT" to 21, "KEYCODE_DPAD_RIGHT" to 22,
        "KEYCODE_DPAD_CENTER" to 23, "KEYCODE_ENTER" to 66, "KEYCODE_NUMPAD_ENTER" to 160, "KEYCODE_DEL" to 67,
        "KEYCODE_FORWARD_DEL" to 112, "KEYCODE_TAB" to 61, "KEYCODE_SPACE" to 62, "KEYCODE_ESCAPE" to 111,
        "KEYCODE_PAGE_UP" to 92, "KEYCODE_PAGE_DOWN" to 93, "KEYCODE_MOVE_HOME" to 122, "KEYCODE_MOVE_END" to 123,
        "KEYCODE_BACK" to 4, "KEYCODE_MENU" to 82,
    )
    private val CODES: Set<Int> = BY_NAME.values.toSet()

    fun isAllowed(keyCode: Int): Boolean = keyCode in CODES

    /** A name or a number; returns null when it is not in the allowlist. */
    fun resolve(key: String): Int? {
        val k = key.trim()
        val code = k.toIntOrNull() ?: BY_NAME[k] ?: return null
        return code.takeIf(::isAllowed)
    }
}
