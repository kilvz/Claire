package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.ManualControl

/** The UI depends only on this run's control entry point; device tests can substitute the input port. */
internal interface ScreenControl {
    val key: String
    val manualControl: ManualControl
    suspend fun beginScreenControl(): Boolean
    suspend fun continueAfterScreenControl()
    suspend fun resizeScreen(width: Int, height: Int): Boolean
    suspend fun touchScreen(width: Int, height: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, swipe: Boolean): Boolean
    suspend fun screenBack(width: Int, height: Int): Boolean
    suspend fun screenText(width: Int, height: Int, text: String): Boolean
}

/** The phone preview is scaled, while the input port uses full virtual-display pixels; the web goes through the same ratio conversion. */
internal fun controlPoint(x: Int, y: Int, previewWidth: Int, previewHeight: Int, inputWidth: Int, inputHeight: Int): Pair<Int, Int> {
    require(previewWidth > 0 && previewHeight > 0 && inputWidth > 0 && inputHeight > 0)
    require(x in 0 until previewWidth && y in 0 until previewHeight)
    return (x.toLong() * inputWidth / previewWidth).toInt() to (y.toLong() * inputHeight / previewHeight).toInt()
}
