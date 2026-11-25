package com.wangzan101.mika.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.SkinBlur
import com.wangzan101.mika.settings.SkinSpec
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background image: a picture the user picked from the album, stored in the app's private directory `files/skin/background.webp`.
 * On import it is downscaled to the screen size and re-encoded; the original EXIF (shooting location, time, etc.) is not carried over, and the image's dominant color is extracted along the way (see [SkinAccent]).
 * How it is displayed (scrim, blur, position, whether the accent follows the image) lives in [SkinSpec].
 */
object SkinImage {
    private const val DIR = "skin"
    private const val FILE = "background.webp"

    /** Blur is achieved by shrinking very small and scaling back up: the long side is shrunk to this many pixels. Works on Android 11 too, with no extra work when displaying. */
    private const val LIGHT_BLUR_SIDE = 120
    private const val STRONG_BLUR_SIDE = 48

    private fun file(context: Context) = File(File(context.filesDir, DIR), FILE)

    /** Decodes the image, shrinks it to no larger than the screen's long side, and saves it; on success it bumps the version so the UI switches over immediately. Returns false if it can't be read, leaving the existing image untouched. */
    suspend fun import(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val metrics = context.resources.displayMetrics
        val maxSide = max(metrics.widthPixels, metrics.heightPixels)
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, maxSide.toFloat() / max(w, h))
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: IOException) {
            return@withContext false
        } catch (e: RuntimeException) {
            return@withContext false
        }
        val hue = SkinAccent.hue(pixels(shrink(bitmap, 48)))
        val target = file(context)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$FILE.tmp")
        val written = try {
            tmp.outputStream().use { bitmap.compress(lossyWebp(), 85, it) }
        } catch (e: IOException) {
            false
        } finally {
            bitmap.recycle()
        }
        if (!written || !tmp.renameTo(target)) {
            tmp.delete()
            return@withContext false
        }
        val flags = FeatureFlags.get(context)
        // A new image starts centered; scrim, blur and whether the accent follows the image keep the previous choices
        flags.setSkin(flags.skin().copy(version = System.currentTimeMillis(), focus = 0f, hue = hue))
        true
    }

    /** Switch back to a plain color background and delete the stored image. */
    fun clear(context: Context) {
        val flags = FeatureFlags.get(context)
        flags.setSkin(flags.skin().copy(version = 0L, hue = null))
        file(context).delete()
        cached = null
    }

    /** Keep only one decoded image per process (keyed by version and blur level), shared by all pages. */
    @Volatile private var cached: Pair<Pair<Long, SkinBlur>, ImageBitmap>? = null

    fun cachedFor(version: Long, blur: SkinBlur): ImageBitmap? = cached?.takeIf { it.first == version to blur }?.second

    fun load(context: Context, version: Long, blur: SkinBlur): ImageBitmap? {
        if (version == 0L) return null
        cachedFor(version, blur)?.let { return it }
        val full = BitmapFactory.decodeFile(file(context).path) ?: return null
        val shown = when (blur) {
            SkinBlur.Off -> full
            SkinBlur.Light -> shrink(full, LIGHT_BLUR_SIDE).also { full.recycle() }
            SkinBlur.Strong -> shrink(full, STRONG_BLUR_SIDE).also { full.recycle() }
        }
        return shown.asImageBitmap().also { cached = (version to blur) to it }
    }

    /** Scales down in halves until the long side is at most [side]; smoother than scaling in one step, and scaling back up gives a soft blur. */
    private fun shrink(src: Bitmap, side: Int): Bitmap {
        var b = src
        while (max(b.width, b.height) > side) {
            val f = max(0.5f, side.toFloat() / max(b.width, b.height))
            val next = Bitmap.createScaledBitmap(b, (b.width * f).roundToInt().coerceAtLeast(1), (b.height * f).roundToInt().coerceAtLeast(1), true)
            if (b !== src) b.recycle()
            b = next
        }
        return if (b === src) src.copy(Bitmap.Config.ARGB_8888, false) else b
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also {
        b.getPixels(it, 0, b.width, 0, 0, b.width, b.height)
        b.recycle()
    }
}

/**
 * Page background (image + scrim). The page base ([com.wangzan101.mika.ui.components.PageBackground]) draws it;
 * the top bar, the fade under the input bar and other places that must cover scrolling content draw it again at the same position, so it looks continuous with the page base.
 */
@Stable
class SkinBackdrop(image: ImageBitmap) {
    // Dragging the slider only swaps these values without rebuilding the object: the measured page position is kept, so the picture doesn't flicker
    var image by mutableStateOf(image)
    var focus by mutableStateOf(0f)
    var scrim by mutableStateOf(Color.Transparent)

    /** Position and size of the page base within the window, measured by the page base. */
    var pageOrigin by mutableStateOf(Offset.Zero)
    var pageSize by mutableStateOf(IntSize.Zero)

    /** Draws the part of the page background corresponding to this block inside [scope]; [origin] is this block's position in the window. */
    fun draw(scope: DrawScope, origin: Offset) = with(scope) {
        val pw = pageSize.width.toFloat()
        val ph = pageSize.height.toFloat()
        clipRect {
            if (pw > 0f && ph > 0f) {
                // The same placement as an image filling the page (cropping the overflow): centered horizontally, and vertically according to focus
                val scale = max(pw / image.width, ph / image.height)
                val dw = image.width * scale
                val dh = image.height * scale
                val left = (pw - dw) / 2f - (origin.x - pageOrigin.x)
                val top = (ph - dh) * (focus + 1f) / 2f - (origin.y - pageOrigin.y)
                drawImage(
                    image, IntOffset.Zero, IntSize(image.width, image.height),
                    IntOffset(left.roundToInt(), top.roundToInt()), IntSize(dw.roundToInt(), dh.roundToInt()),
                    filterQuality = FilterQuality.High,
                )
            }
            drawRect(scrim)
        }
    }
}

/** Non-null when a background image is present under the page; the top bar and fades use it to decide between a solid color and the background. */
val LocalSkinBackdrop = staticCompositionLocalOf<SkinBackdrop?> { null }

/** How the background image from settings is displayed. Changes take effect immediately without rebuilding the UI. */
@Composable
fun appearanceSkin(): SkinSpec {
    val context = LocalContext.current
    val flags = remember(context) { FeatureFlags.get(context) }
    val spec by remember(flags) { flags.skinChanges() }.collectAsState(flags.skin())
    return spec
}

/** The page background right now; null when no background image is set (or the image can't be read). */
@Composable
fun rememberSkinBackdrop(): SkinBackdrop? {
    val context = LocalContext.current
    val spec = appearanceSkin()
    val image by produceState(SkinImage.cachedFor(spec.version, spec.blur), spec.version, spec.blur) {
        value = withContext(Dispatchers.IO) { SkinImage.load(context, spec.version, spec.blur) }
    }
    val bg = MikaTheme.colors.bg
    val img = image ?: return null
    val backdrop = remember { SkinBackdrop(img) }
    // These values are only read while drawing, and are swapped in directly at composition time
    backdrop.image = img
    backdrop.focus = spec.focus
    backdrop.scrim = bg.copy(alpha = spec.scrim / 100f)
    return backdrop
}

// WEBP_LOSSY exists from Android 11; on earlier systems the old WEBP format is already lossy below quality 100
private fun lossyWebp(): Bitmap.CompressFormat =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
    else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
