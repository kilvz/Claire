package com.wangzan101.mika.media

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * Copies one ImageReader frame (RGBA_8888) into a bitmap [target] allocated with the row stride (rowStride / pixelStride).
 * The last row may carry no alignment padding, making the buffer shorter than the whole bitmap: pad into a buffer of the needed length before copying so copyPixelsFromBuffer does not throw.
 * [scratch] is the padding buffer from last time; the one used now (possibly new) is returned for the caller to keep and pass back with the next frame.
 */
fun copyStridedFrame(target: Bitmap, src: ByteBuffer, scratch: ByteBuffer?): ByteBuffer? {
    src.rewind()
    val needed = target.byteCount
    if (src.remaining() >= needed) {
        target.copyPixelsFromBuffer(src)
        return scratch
    }
    val s = scratch?.takeIf { it.capacity() == needed } ?: ByteBuffer.allocateDirect(needed)
    s.clear()
    s.put(src)
    s.rewind()
    target.copyPixelsFromBuffer(s)
    return s
}
