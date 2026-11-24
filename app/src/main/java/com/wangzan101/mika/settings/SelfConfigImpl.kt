package com.wangzan101.mika.settings

import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.self.SelfConfig

/**
 * The few settings the model is allowed to change. [FeatureFlags] is the storage; only reversible preferences are handed to the model here:
 *
 * **Not exposed**: models and Keys (swapping out its own brain, spending the user's money), and any data deletion (clearing the workspace, resetting the runtime environment, deleting conversations) — these are irreversible.
 * **Also not exposed**: the container's networking and proxy — once enabled, data may leave the phone, and that boundary must be decided by the user in settings.
 * Everything exposed can be changed back at any time.
 */
class SelfConfigImpl(
    private val flags: FeatureFlags,
) : SelfConfig {
    override fun options(): List<SelfConfig.Option> = listOf(
        SelfConfig.Option(KEY_PACE, "操作节奏", flags.operatingPace().name.lowercase(), listOf("fast", "standard", "human")),
        SelfConfig.Option(KEY_MUTE, "干活时把被操作的 App 静音", onOff(flags.muteTarget()), ON_OFF),
        SelfConfig.Option(KEY_THEME, "外观", flags.themeMode().name.lowercase(), listOf("system", "light", "dark")),
        SelfConfig.Option(KEY_ACCENT, "强调色", flags.themeAccent().name.lowercase(), ACCENTS),
        SelfConfig.Option(KEY_CAPSULE, "悬浮胶囊", flags.capsuleMode().name.lowercase(), listOf("off", "duringtasks", "always")),
        SelfConfig.Option(KEY_KEEP_ON, "干活时屏幕常亮", onOff(flags.keepScreenOn()), ON_OFF),
    )

    override fun set(key: String, value: String): String? {
        val v = value.trim().lowercase()
        fun onOff(): Boolean? = when (v) {
            "on", "true", "yes" -> true
            "off", "false", "no" -> false
            else -> null
        }
        fun bool(write: (Boolean) -> Unit): String? {
            val on = onOff() ?: return ON_OFF_HINT
            write(on)
            return null
        }
        return when (key) {
            KEY_PACE -> {
                val pace = OperatingPace.entries.firstOrNull { it.name.equals(v, true) }
                    ?: return "operating pace is one of fast, standard, human"
                flags.setOperatingPace(pace)
                null
            }
            KEY_MUTE -> bool { flags.setMuteTarget(it) }
            KEY_THEME -> {
                val mode = ThemeMode.entries.firstOrNull { it.name.equals(v, true) }
                    ?: return "appearance is one of system, light, dark"
                flags.setThemeMode(mode)
                null
            }
            KEY_ACCENT -> {
                val accent = ThemeAccent.entries.firstOrNull { it.name.equals(v, true) }
                    ?: return "accent color is one of ${ACCENTS.joinToString(", ")}"
                flags.setThemeAccent(accent)
                null
            }
            KEY_CAPSULE -> {
                val mode = com.wangzan101.mika.capsule.CapsuleMode.entries.firstOrNull { it.name.equals(v, true) }
                    ?: return "capsule is one of off, duringtasks, always"
                flags.setCapsuleMode(mode)
                null
            }
            KEY_KEEP_ON -> bool { flags.setKeepScreenOn(it) }
            else -> "there is no such setting"
        }
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    private companion object {
        val ON_OFF = listOf("on", "off")
        const val ON_OFF_HINT = "value is on or off"
        const val KEY_PACE = "operating_pace"
        const val KEY_MUTE = "mute_apps"
        const val KEY_THEME = "appearance"
        const val KEY_ACCENT = "accent_color"
        val ACCENTS = ThemeAccent.entries.map { it.name.lowercase() }
        const val KEY_CAPSULE = "capsule"
        const val KEY_KEEP_ON = "keep_screen_on"
    }
}
