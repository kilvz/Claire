package com.wangzan101.mika.cap.phone.display

/** ROTATION_0/90/180/270 map to 0/1/2/3 respectively. The physical buffer never changes; at quarter turns the logical screen swaps width and height. */
internal data class DisplayFrameGeometry(val bufferWidth: Int, val bufferHeight: Int, val rotation: Int) {
    private val sideways get() = rotation == 1 || rotation == 3
    val width: Int get() = if (sideways) bufferHeight else bufferWidth
    val height: Int get() = if (sideways) bufferWidth else bufferHeight

    /** The viewport uses rotated logical coordinates; resizing the buffer keeps the current rotation. */
    fun resized(width: Int, height: Int): DisplayFrameGeometry =
        if (sideways) copy(bufferWidth = height, bufferHeight = width)
        else copy(bufferWidth = width, bufferHeight = height)

    /** Limit the preview by the short side: portrait and landscape then keep a similar pixel count, so landscape does not end up as a single low-resolution strip. */
    fun previewWidth(shortSide: Int): Int =
        if (width > height) maxOf(1, (width.toLong() * shortSide / height).toInt()) else shortSide

    fun previewSize(maxWidth: Int): Pair<Int, Int> {
        val w = minOf(maxWidth, width)
        return w to maxOf(1, (height.toLong() * w / width).toInt())
    }
}
