package com.wangzan101.mika.cap.phone.display

import kotlin.math.roundToInt

internal data class PhoneViewport(val width: Int, val height: Int, val dpi: Int)

/** Pixel scaling follows the available screen area; a small side panel lowers the display density so apps still get a layout width of at least 320dp. */
internal fun phoneViewport(width: Int, height: Int, deviceDpi: Int): PhoneViewport {
    require(width > 0 && height > 0 && deviceDpi > 0)
    val scale = minOf(1f, 2048f / maxOf(width, height))
    val w = (width * scale).roundToInt().coerceAtLeast(1)
    val h = (height * scale).roundToInt().coerceAtLeast(1)
    val dpi = minOf(deviceDpi * scale, w * 160f / 320f).roundToInt().coerceIn(72, 1000)
    return PhoneViewport(w, h, dpi)
}
