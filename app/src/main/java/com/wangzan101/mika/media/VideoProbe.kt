package com.wangzan101.mika.media

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.wangzan101.mika.core.workspace.GatedFile
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Reads video metadata and frames with MediaMetadataRetriever. Data is read on demand directly from the handle verified by the workspace gate ([GatedSource]),
 * without first copying the whole video to the cache or reading it into memory; the caller uses it inside the gate's shared section and then closes the handle.
 */
internal object VideoProbe {
    class Meta(val durationMs: Long?, val width: Int, val height: Int, val hasAudio: Boolean)

    /** Opens a retriever on [g] and releases it when done; when it cannot be opened, throws the retriever's exception. */
    fun <T> open(g: GatedFile, block: (MediaMetadataRetriever) -> T): T {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(GatedSource(g))
            return block(retriever)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Duration, width/height in display orientation (already swapped by rotation), and whether there is an audio track. */
    fun meta(r: MediaMetadataRetriever): Meta {
        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val (w, h) = if (rotation == 90 || rotation == 270) height to width else width to height
        val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        return Meta(duration, w, h, hasAudio)
    }

    /** Key frame near [atMs], JPEG with long side scaled within [maxSide]; null when unavailable. */
    fun frameJpeg(r: MediaMetadataRetriever, atMs: Long, maxSide: Int): ByteArray? {
        val bitmap = r.getFrameAtTime(atMs.coerceAtLeast(0) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        return try { jpeg(bitmap, maxSide) } finally { bitmap.recycle() }
    }

    /** Key frame near [atMs], long side scaled within [maxSide]; null when unavailable. The caller is responsible for recycle. */
    fun frameBitmap(r: MediaMetadataRetriever, atMs: Long, maxSide: Int): Bitmap? {
        val bitmap = r.getFrameAtTime(atMs.coerceAtLeast(0) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val scaled = scaled(bitmap, maxSide)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** Takes [count] evenly spaced points over 0..[durationMs]; for one frame, takes the middle. */
    fun sampleTimes(durationMs: Long, count: Int): List<Long> =
        if (count <= 1) listOf(durationMs.coerceAtLeast(0) / 2)
        else List(count) { i -> durationMs.coerceAtLeast(0) * i / (count - 1) }

    private fun jpeg(bitmap: Bitmap, maxSide: Int): ByteArray {
        val scaled = scaled(bitmap, maxSide)
        try {
            return ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun scaled(bitmap: Bitmap, maxSide: Int): Bitmap {
        val side = max(bitmap.width, bitmap.height)
        if (side <= maxSide) return bitmap
        val f = maxSide.toDouble() / side
        return Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * f).toInt()), max(1, (bitmap.height * f).toInt()), true)
    }

    /** Reads from the gate handle at a position; closing the retriever does not close the handle (the caller's use closes it). */
    private class GatedSource(private val g: GatedFile) : MediaDataSource() {
        override fun getSize(): Long = g.size

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (position >= g.size) return -1
            val n = g.channel().read(ByteBuffer.wrap(buffer, offset, size), position)
            return if (n <= 0) -1 else n
        }

        override fun close() = Unit
    }
}
