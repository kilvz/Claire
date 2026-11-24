package com.wangzan101.mika.core.screen.visual

import kotlin.math.abs

/**
 * A frame reduced to a grid of luma cells ([cols]×[rows], row-major): one taken before and one after an action, as corroborating evidence when judging the effect (the UI state is what mainly matters).
 * Sampling goes only through a pixel-reading function and depends on no Android classes (so it can be tested on the JVM); the port samples the bitmap it already holds and does not encode images.
 */
class FrameGrid(val cols: Int, val rows: Int, val luma: IntArray) {
    init { require(luma.size == cols * rows) }

    companion object {
        private const val SAMPLES = 2

        /** Cuts a [width]×[height] frame into cells, taking the average luma of 2×2 points per cell; [pixel] returns the ARGB at (x, y). Null for frames that are too small. */
        fun sample(width: Int, height: Int, pixel: (Int, Int) -> Int): FrameGrid? {
            val cols = FrameTuning.GRID_COLS; val rows = FrameTuning.GRID_ROWS
            val cw = width / cols
            val ch = height / rows
            if (cw == 0 || ch == 0) return null
            val out = IntArray(cols * rows)
            for (gy in 0 until rows) for (gx in 0 until cols) {
                var sum = 0
                for (sy in 0 until SAMPLES) for (sx in 0 until SAMPLES) {
                    sum += luma(pixel(gx * cw + (2 * sx + 1) * cw / (2 * SAMPLES), gy * ch + (2 * sy + 1) * ch / (2 * SAMPLES)))
                }
                out[gy * cols + gx] = sum / (SAMPLES * SAMPLES)
            }
            return FrameGrid(cols, rows, out)
        }

        private fun luma(p: Int) = ((p shr 16 and 0xff) * 3 + (p shr 8 and 0xff) * 6 + (p and 0xff)) / 10
    }
}

/** Between two frames: the fraction of cells that changed across the whole screen [whole], and within the target region [region] (null when no region was given). */
data class FrameChange(val whole: Double, val region: Double?) {
    /** Enough cells changed to be noticeable (a blinking cursor or a one-spot ripple does not count). */
    val visible: Boolean get() = whole >= FrameTuning.FRAME_VISIBLE || (region ?: 0.0) >= FrameTuning.REGION_VISIBLE
    /** A large area of the whole screen changed. */
    val large: Boolean get() = whole >= FrameTuning.FRAME_LARGE
}

/** A coarse comparison of the frames before and after an action: a cell counts as changed when its luma difference exceeds [FrameTuning.CELL_DELTA]. */
object FrameDiff {
    /** [roi] is the target region (0–999 normalized [l,t,r,b]); null when a frame is missing or the two grids differ in size. */
    fun compare(before: FrameGrid?, after: FrameGrid?, roi: List<Int>? = null): FrameChange? {
        if (before == null || after == null || before.cols != after.cols || before.rows != after.rows) return null
        val cols = before.cols; val rows = before.rows
        var changed = 0
        var inRegion = 0; var regionChanged = 0
        val box = roi?.takeIf { it.size == 4 }?.let { r ->
            // Expand outward by half a cell so small controls do not fall between cells
            val l = (r[0] * cols / 1000.0 - 0.5).toInt().coerceIn(0, cols - 1)
            val t = (r[1] * rows / 1000.0 - 0.5).toInt().coerceIn(0, rows - 1)
            val rr = (r[2] * cols / 1000.0 + 0.5).toInt().coerceIn(l, cols - 1)
            val b = (r[3] * rows / 1000.0 + 0.5).toInt().coerceIn(t, rows - 1)
            intArrayOf(l, t, rr, b)
        }
        for (y in 0 until rows) for (x in 0 until cols) {
            val i = y * cols + x
            val d = abs(before.luma[i] - after.luma[i]) > FrameTuning.CELL_DELTA
            if (d) changed++
            if (box != null && x in box[0]..box[2] && y in box[1]..box[3]) {
                inRegion++
                if (d) regionChanged++
            }
        }
        return FrameChange(changed.toDouble() / (cols * rows), box?.let { regionChanged.toDouble() / inRegion.coerceAtLeast(1) })
    }
}
