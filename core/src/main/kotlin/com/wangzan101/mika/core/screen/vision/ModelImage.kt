package com.wangzan101.mika.core.screen.vision

import com.wangzan101.mika.core.model.Part
import kotlinx.coroutines.CancellationException
import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Images sent to any model (screen reads, screenshots and look-backs of the main model, the vision model, verifiers) all use this single encoding:
 * JPEG with the long side capped at [MAX_SIDE] and quality [QUALITY]. A full-resolution PNG costs thousands of tokens and megabytes in one image, whereas screen reads rely on the control tree,
 * so the frame only needs to show the layout and text clearly. Which frames may not be sent (protected pages, payment pages) is decided by the caller; this only deals with encoding.
 */
object ModelImage {
    const val MAX_SIDE = 1280
    const val QUALITY = 80

    /**
     * Encodes one image to send to a model. When [ops] is null or the image cannot be decoded, the bytes are sent as-is (mime detected from the file header), with the size included when it can be read.
     * [screenshotId]: which screen read the frame came from; null when it is not a screen frame.
     */
    fun part(img: ByteArray, ops: ImageOps?, screenshotId: Int? = null): Part.Image {
        val jpeg = ops?.let { o -> attempt { o.toJpeg(img, MAX_SIDE, QUALITY) } }
        val out = jpeg ?: img
        val size = ops?.let { o -> attempt { o.size(out) } } ?: pngSize(out)
        return Part.Image(
            Base64.getEncoder().encodeToString(out), if (jpeg != null) "image/jpeg" else mimeOf(img),
            size?.first ?: 0, size?.second ?: 0, screenshotId,
        )
    }

    /** The size after scaling to [MAX_SIDE] (same algorithm as [ImageOps.toJpeg]); for estimation. */
    fun scaled(width: Int, height: Int): Pair<Int, Int> {
        val f = MAX_SIDE.toDouble() / max(width, height)
        if (f >= 1.0) return width to height
        return max(1, (width * f).roundToInt()) to max(1, (height * f).roundToInt())
    }

    private inline fun <T> attempt(block: () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    private fun mimeOf(b: ByteArray): String = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        b.size >= 4 && String(b, 0, 4, Charsets.ISO_8859_1) == "GIF8" -> "image/gif"
        else -> "image/png"
    }

    /** Reads width and height directly from the PNG's IHDR; null when it is not a PNG. */
    private fun pngSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 24 || b[0] != 0x89.toByte() || b[1] != 'P'.code.toByte() || b[2] != 'N'.code.toByte() || b[3] != 'G'.code.toByte()) return null
        fun int(at: Int) = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
        return int(16) to int(20)
    }
}
