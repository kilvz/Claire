package com.wangzan101.mika.settings

/** How much the background image is blurred. */
enum class SkinBlur {
    Off, Light, Strong;

    companion object {
        fun parse(s: String?): SkinBlur = entries.firstOrNull { it.name == s } ?: Light
    }
}

/**
 * Settings for the background image (skin). The image itself lives in `files/skin/` (see `ui/theme/SkinImage.kt`); this only controls how it is displayed.
 *
 * - [version]: 0 means no image (solid background); every image change bumps it, and the UI re-reads the image based on it.
 * - [scrim]: the page background scrim laid over the image, in percent, clamped to [MIN_SCRIM]–[MAX_SCRIM], which keeps text legible.
 * - [focus]: which part of the image is revealed when cropping, -1 topmost, 0 centered, 1 bottommost.
 * - When [accentFromImage] is on and a color was sampled during import ([hue], hue 0–360), the accent color follows the image instead of the one chosen in settings.
 */
data class SkinSpec(
    val version: Long = 0L,
    val scrim: Int = DEFAULT_SCRIM,
    val blur: SkinBlur = SkinBlur.Light,
    val focus: Float = 0f,
    val accentFromImage: Boolean = true,
    val hue: Float? = null,
) {
    val active: Boolean get() = version != 0L

    /** The image's hue when it should currently drive the accent color, otherwise null. */
    val accentHue: Float? get() = hue.takeIf { active && accentFromImage }

    companion object {
        const val MIN_SCRIM = 75
        const val MAX_SCRIM = 92
        const val DEFAULT_SCRIM = 82
    }
}
