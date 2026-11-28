package com.wangzan101.mika.cap.phone.display

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.wangzan101.mika.media.copyStridedFrame
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The virtual display dedicated to the Agent. Frames are output to this process's ImageReader, so
 * screenshots do not depend on the accessibility screenshot API.
 * Frame processing is rate-limited to ≤10 frames/second: when a frame arrives less than 100ms after the
 * last processing, it is deferred until that deadline and the latest frame is taken then,
 * with stale frames in between discarded by acquireLatestImage.
 */
class AgentDisplay(
    private val shell: IMikaShell,
    @Volatile private var bufferWidth: Int,
    @Volatile private var bufferHeight: Int,
    private var dpi: Int,
    private val displayManager: DisplayManager,
) {
    private val thread = HandlerThread("agent-display").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader = ImageReader.newInstance(bufferWidth, bufferHeight, PixelFormat.RGBA_8888, 2)

    val displayId: Int

    // ImageReader uses the physical size of the current buffer; the app's logical coordinates rotate with the virtual display and are independent of the main screen's orientation.
    private val geometry get() = DisplayFrameGeometry(bufferWidth, bufferHeight,
        displayManager.getDisplay(displayId)?.rotation ?: Surface.ROTATION_0)
    val width: Int get() = geometry.width
    val height: Int get() = geometry.height

    private val _frames = MutableStateFlow<Bitmap?>(null)
    /** Live frames: rate-limited to ≤10 frames/second, with a short edge ≤360 for normal preview and ≤720 after the two-pane virtual display resize. */
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

    private val frameLock = Any()
    private var fullFrame: Bitmap? = null // 带行填充的整帧,宽 = rowStride / pixelStride
    private var scratch: ByteBuffer? = null // 缓冲比整帧短时垫用,只在帧线程上动
    private var hasFrame = false
    private var frameGeometry = DisplayFrameGeometry(bufferWidth, bufferHeight, Surface.ROTATION_0)
    private var lastProcessedAt = 0L
    private var pending = false
    /** The virtual display producer's monotonic-clock nanosecond timestamp, published under frameLock together with the full frame and the preview frame. */
    private var frameTimestampNanos = 0L
    @Volatile private var released = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var previewLimit = 360
    internal val previewWidth get() = geometry.let { it.previewSize(it.previewWidth(previewLimit)).first }
    internal val previewHeight get() = geometry.let { it.previewSize(it.previewWidth(previewLimit)).second }

    private val process = Runnable {
        pending = false
        lastProcessedAt = SystemClock.uptimeMillis()
        processLatest()
    }

    init {
        val id = try {
            shell.createDisplay(reader.surface, bufferWidth, bufferHeight, dpi)
        } catch (t: Throwable) {
            closeLocal()
            throw t
        }
        if (id < 0) {
            closeLocal()
            throw IllegalStateException("createDisplay failed")
        }
        displayId = id
        reader.setOnImageAvailableListener({ schedule() }, handler)
        // Frames may already have queued up while the display was being created; get the correct display id first, then process them.
        handler.post { schedule() }
    }

    /** Swaps the receiver on the frame thread while keeping the same displayId and app tasks; on failure the old frames keep being used. */
    suspend fun resize(newWidth: Int, newHeight: Int, newDpi: Int): Boolean = kotlin.coroutines.suspendCoroutine { continuation ->
        if (released) { continuation.resumeWith(Result.success(false)); return@suspendCoroutine }
        val posted = handler.post {
            if (released) { continuation.resumeWith(Result.success(false)); return@post }
            val target = geometry.resized(newWidth, newHeight)
            if (bufferWidth == target.bufferWidth && bufferHeight == target.bufferHeight && dpi == newDpi) {
                continuation.resumeWith(Result.success(true)); return@post
            }
            var next: ImageReader? = null
            val resized = try {
                next = ImageReader.newInstance(target.bufferWidth, target.bufferHeight, PixelFormat.RGBA_8888, 2)
                if (!shell.resizeDisplay(displayId, next.surface, target.bufferWidth, target.bufferHeight, newDpi)) false else {
                    reader.setOnImageAvailableListener(null, null)
                    reader.close()
                    synchronized(frameLock) {
                        previewLimit = 720
                        bufferWidth = target.bufferWidth
                        bufferHeight = target.bufferHeight
                        dpi = newDpi
                        fullFrame?.recycle()
                        fullFrame = null
                        hasFrame = false
                        frameTimestampNanos = 0L
                        _frames.value = null
                    }
                    reader = next
                    next = null
                    reader.setOnImageAvailableListener({ schedule() }, handler)
                    schedule()
                    true
                }
            } catch (_: Exception) { false }
            finally { next?.close() }
            continuation.resumeWith(Result.success(resized))
        }
        if (!posted) continuation.resumeWith(Result.success(false))
    }

    private fun schedule() {
        if (pending || released) return
        pending = true
        val wait = lastProcessedAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
    }

    private fun processLatest() {
        if (released) return
        val image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            // When no new frame is acquired, keep the existing null/freshness protection; leave evidence of the acquisition failure and never pass an old frame off as new.
            Log.w(TAG, "acquireLatestImage failed: ${t.javaClass.simpleName}", t)
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            val capturedGeometry = geometry
            synchronized(frameLock) {
                var bmp = fullFrame
                if (bmp == null || bmp.width != strideWidth) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideWidth, bufferHeight, Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                // 最后一行可能不带填充,缓冲比整块位图短:直接拷会抛异常,copyStridedFrame 先垫等长的缓冲
                scratch = copyStridedFrame(bmp!!, plane.buffer, scratch)
                hasFrame = true
                // Rate limiting or thread scheduling delays consumption, so the moment the bitmap was copied must not be recorded as the frame time.
                frameTimestampNanos = image.timestamp
                frameGeometry = capturedGeometry
                _frames.value = uprightDisplayFrame(bmp, capturedGeometry, capturedGeometry.previewWidth(previewLimit), paint)
            }
        } finally {
            image.close()
        }
    }

    /** The latest full frame as a PNG; null when no frame has been received yet. */
    fun latestFramePng(): ByteArray? {
        val crop = latestFrameBitmap() ?: return null
        return try {
            ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            crop.recycle()
        }
    }

    /** The monotonic-clock nanosecond timestamp of the latest published frame. */
    fun lastFrameAt(): Long? = synchronized(frameLock) {
        frameTimestampNanos.takeIf { !released && it > 0 }
    }

    /** A copy of the frame at its original size for OCR, avoiding PNG encoding and decoding; the caller is responsible for recycling it. */
    fun latestFrameBitmap(): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null) null else uprightDisplayFrame(bmp, frameGeometry, paint = paint)
    }

    fun release() {
        if (released) return
        released = true
        runCatching { shell.releaseDisplay(displayId) }
        closeLocal()
        _frames.value = null
    }

    private fun closeLocal() {
        released = true
        handler.removeCallbacks(process)
        // Finish on the frame thread to avoid racing with a frame currently being processed
        handler.post {
            reader.close()
            synchronized(frameLock) {
                fullFrame?.recycle()
                fullFrame = null
                hasFrame = false
            }
        }
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "AgentDisplay"
        const val FRAME_INTERVAL_MS = 100L
    }
}
