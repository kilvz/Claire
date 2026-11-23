package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.normalize
import com.wangzan101.mika.core.screen.user.CapturedScreen
import com.wangzan101.mika.core.screen.user.UserScreen
import com.wangzan101.mika.core.screen.user.UserScreenText
import com.wangzan101.mika.media.BitmapImageOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Captures the screen the user is currently looking at on the main display, along with the foreground app and the text on screen (see [UserScreenText]: read the accessibility tree first, and recognize text from the image when that is not enough).
 * When the screen has a screenshot-blocked window (the screenshot fails, or comes out almost entirely black while an app window is present), no image is kept; only which app it was and that it could not be captured is recorded.
 * Whether to hide Claire's own overlays first is up to the caller (see [OverlayWindow.hiddenWhile]); overlays and the IME are never counted when reading the tree anyway.
 */
internal object MainScreenCapture {
    private const val TAG = "MikaScreenCapture"
    private const val JPEG_QUALITY = 88
    /** Maximum time to wait for text recognition: past that, only the text from the tree is used. */
    private const val OCR_WAIT_MS = 4_000L

    sealed interface Result {
        class Captured(val shot: CapturedScreen) : Result
        /** The foreground is Claire itself: do not capture. */
        data object Self : Result
        data object Unavailable : Result
    }

    /** Only inspects the ownership of window roots without traversing text; refuses to share when any ownership is unknown. */
    fun sourcePackages(service: ScreenAccessibility): Set<String>? {
        val windows = service.windowsOn(Display.DEFAULT_DISPLAY).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (windows.isEmpty()) return null
        val packages = windows.map { it.root?.packageName?.toString() }
        if (packages.any { it.isNullOrBlank() }) return null
        return packages.filterNotNull().toSet()
    }

    /** Takes a screenshot (the caller has already hidden overlays). The tree must be read at the same moment as the screenshot: read it right after capturing. */
    suspend fun shoot(service: ScreenAccessibility): Pair<ScreenAccessibility.MainShot, ScreenRead?> {
        val shot = service.takeMainScreenshot()
        val read = runCatching { readTree(service) }.getOrNull()
        return shot to read
    }

    /** Turns the captured image and tree into one screen (on a background thread: encoding, text recognition); [topInset]/[bottomInset] are the pixels taken by the status bar and navigation bar. */
    suspend fun build(
        service: ScreenAccessibility, shot: ScreenAccessibility.MainShot, read: ScreenRead?,
        topInset: Int, bottomInset: Int, appLabel: (String) -> String,
    ): Result = withContext(Dispatchers.Default) {
        val pkg = read?.topPackage
        if (pkg == service.packageName) {
            (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
            return@withContext Result.Self
        }
        val now = System.currentTimeMillis()
        val label = pkg?.let(appLabel)
        when (shot) {
            is ScreenAccessibility.MainShot.Failed -> {
                Log.w(TAG, "screenshot failed: ${shot.code}")
                Result.Unavailable
            }
            ScreenAccessibility.MainShot.Secure -> Result.Captured(CapturedScreen(null, UserScreen("", now, pkg, label, blocked = true)))
            is ScreenAccessibility.MainShot.Ok -> {
                val bmp = shot.bitmap
                try {
                    val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
                    // Before Android 12, screenshot-blocked windows come out black: if an app window is present and the image is almost entirely black, treat it as uncapturable and do not send a black image
                    if (read?.anyAppWindow != false && BitmapImageOps.isMostlyBlack(jpeg)) {
                        return@withContext Result.Captured(CapturedScreen(null, UserScreen("", now, pkg, label, blocked = true)))
                    }
                    val snap = read?.let { snapshotOf(it, bmp.width, bmp.height) }
                    val ocr = if (snap != null && UserScreenText.needsOcr(snap)) recognize(bmp) else null
                    val (lines, fromOcr) = snap?.let {
                        UserScreenText.lines(it, ocr, top = normalize(topInset, bmp.height), bottom = normalize(bmp.height - bottomInset, bmp.height))
                    } ?: (emptyList<com.wangzan101.mika.core.screen.user.ScreenLine>() to false)
                    Result.Captured(CapturedScreen(jpeg, UserScreen("", now, pkg, label, lines = lines, fromOcr = fromOcr)))
                } finally {
                    bmp.recycle()
                }
            }
        }
    }

    /** Reads the app-owned windows on the main display (status bar, navigation bar, IME, and overlays excluded) as elements. */
    private fun readTree(service: ScreenAccessibility): ScreenRead {
        val windows = service.windowsOn(Display.DEFAULT_DISPLAY).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val m = service.resources.displayMetrics
        val size = runCatching { service.overlays().area() }.getOrNull()
        return ScreenReader.read(windows, size?.width ?: m.widthPixels, size?.height ?: m.heightPixels)
    }

    private fun snapshotOf(r: ScreenRead, width: Int, height: Int) = ScreenSnapshot(
        snapshotId = "user-screen", packageName = r.topPackage, elements = r.entries.map { it.element },
        width = width, height = height, hasWindow = r.anyAppWindow, windows = r.windows,
    )

    private suspend fun recognize(bmp: Bitmap) = try {
        withTimeoutOrNull(OCR_WAIT_MS) { PhoneOcr.read(bmp.copy(Bitmap.Config.ARGB_8888, false)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "text recognition failed", e)
        null
    }
}
