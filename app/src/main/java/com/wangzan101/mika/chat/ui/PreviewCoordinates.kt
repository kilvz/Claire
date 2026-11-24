package com.wangzan101.mika.chat.ui

import kotlin.math.min

/** Inverse transform of ContentScale.Fit with centering; letterboxing, invalid sizes and touches outside the image are never sent to the device. */
internal fun previewPoint(x: Float, y: Float, viewWidth: Int, viewHeight: Int, imageWidth: Int, imageHeight: Int): Pair<Int, Int>? {
    if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0 || !x.isFinite() || !y.isFinite()) return null
    val scale = min(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
    val left = (viewWidth - imageWidth * scale) / 2f
    val top = (viewHeight - imageHeight * scale) / 2f
    val px = (x - left) / scale
    val py = (y - top) / scale
    if (px < 0 || py < 0 || px >= imageWidth || py >= imageHeight) return null
    return px.toInt() to py.toInt()
}
