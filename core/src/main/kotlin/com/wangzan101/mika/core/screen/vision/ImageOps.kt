package com.wangzan101.mika.core.screen.vision

/**
 * Image processing interface: the input is encoded image bytes (screenshot PNG etc.) and the output is likewise encoded bytes.
 * Box coordinates [box] are [left, top, right, bottom], normalized to 0–999, on the same scale as the bounds of screen-read elements.
 */
interface ImageOps {
    fun size(img: ByteArray): Pair<Int, Int>

    /** 8×8 grayscale average hash; the smaller the [hamming] distance between two frames, the more alike they are. */
    fun aHash(img: ByteArray): Long

    /** Compresses before sending to a model: the long side is capped at [maxSide]. */
    fun toJpeg(img: ByteArray, maxSide: Int = 1280, quality: Int = 80): ByteArray

    /** Draws a red frame [expandPx] outside [box]. */
    fun drawFrame(img: ByteArray, box: IntArray, expandPx: Int = 3, strokePx: Int = 4): ByteArray

    fun crop(img: ByteArray, box: IntArray): ByteArray

    fun scale(img: ByteArray, factor: Double): ByteArray

    /** Per-pixel ARGB, row-major. */
    fun pixels(img: ByteArray): IntArray

    /** Almost entirely black: this is what a protected page looks like when captured. */
    fun isMostlyBlack(img: ByteArray): Boolean
}

fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
