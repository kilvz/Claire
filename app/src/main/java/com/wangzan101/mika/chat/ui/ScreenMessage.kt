package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.screen.user.UserScreen
import com.wangzan101.mika.core.screen.user.UserScreenMessage
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The UI wording for a screen the user sent (shared by the conversation page, cards and the screenshot button). */
object ScreenCopy {
    val TAG: String get() = tr(R.string.screen_tag)
    val BLOCKED: String get() = tr(R.string.screen_blocked)
    val CAMERA: String get() = tr(R.string.screen_camera)
    val REMOVE: String get() = tr(R.string.screen_remove)
    val SEND_SCREEN: String get() = tr(R.string.screen_send)

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    fun clock(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = CLOCK.format(Instant.ofEpochMilli(at).atZone(zone))

    /** The line under the screenshot: "your screen right now · Settings · 19:42". */
    fun tag(screen: UserScreen): String = "$TAG · ${screen.app} · ${clock(screen.takenAt)}"

    /** The second line on the attachment strip: "Settings · 19:42". */
    fun detail(screen: UserScreen): String = "${screen.app} · ${clock(screen.takenAt)}"

    /**
     * The phrase used to name the conversation in lists: the accompanying note; when only a screen was sent it is "your screen right now". The quoted lines ([QuoteMessage]) and the page description ([AttachedPage]) do not count.
     * Without a screen, it is returned unchanged.
     */
    fun plain(raw: String): String {
        val text = QuoteMessage.words(AttachedPage.words(raw))
        val p = UserScreenMessage.parse(text)
        return if (!p.hasScreen) text else p.words.ifEmpty { TAG }
    }
}

/** One screen to draw in the UI: its description and thumbnail (null when the image cannot be read). */
class ShownScreen(val screen: UserScreen, val thumb: ImageBitmap?)

/** Reads one screen by conversation and id; null when it cannot be read. UI tests swap in a fake. */
fun interface ScreenShots {
    suspend fun load(conversationId: String, id: String): ShownScreen?
}

/** When provided, it is used to read screens (tests); otherwise the copies stored in the app are read. */
val LocalScreenShots = staticCompositionLocalOf<ScreenShots?> { null }

/** Screens stored in the app: the description is read from the conversation's workspace, the thumbnail is decoded at a reduced size for the requested width, and decoded ones are remembered. */
private class StoredScreenShots(private val context: Context) : ScreenShots {
    override suspend fun load(conversationId: String, id: String): ShownScreen? = withContext(Dispatchers.IO) {
        val key = "$conversationId/$id"
        cache.get(key)?.let { return@withContext it }
        val files = (context.applicationContext as MikaApp).userScreens
        val screen = files.screen(conversationId, id) ?: return@withContext null
        val thumb = if (screen.blocked) null else files.image(conversationId, id)?.let(::screenThumbnail)
        ShownScreen(screen, thumb).also { cache.put(key, it) }
    }

    companion object {
        val cache = LruCache<String, ShownScreen>(24)
    }
}

/** Decoding thumbnails to this width (in pixels) is enough: the widest in the conversation page is 110dp. */
private const val THUMB_PX = 360

/** Decodes a screenshot into a thumbnail (decoding downsampled at [THUMB_PX], never the whole image); null when it cannot be decoded. Call on a background thread. */
fun screenThumbnail(bytes: ByteArray): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= THUMB_PX) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

/** Reads one screen (in the background); null before it has loaded or when it cannot be read. */
@Composable
fun rememberShownScreen(conversationId: String?, id: String): ShownScreen? {
    val context = LocalContext.current
    val shots = LocalScreenShots.current ?: StoredScreenShots(context)
    val shown by produceState<ShownScreen?>(null, conversationId, id) {
        value = conversationId?.let { runCatching { shots.load(it, id) }.getOrNull() }
    }
    return shown
}

/**
 * One message from the user: when it carries a screen, it is a screenshot thumbnail ([thumbWidth] wide) with a line below it reading "your screen right now · App · time", and the accompanying note starts its own bubble underneath ([bubble]);
 * a page that does not allow screenshots is one line saying "not captured"; once the screenshot is gone, only that line remains. Without a screen it is just [bubble].
 */
@Composable
fun UserMessage(conversationId: String?, raw: String, thumbWidth: Dp, modifier: Modifier = Modifier, bubble: @Composable (String) -> Unit) {
    // Asked about a page while on the browser page: the page-description lines are not shown (the conversation page draws "web page · site" separately above); when only selected text comes along, that text is shown
    val text = AttachedPage.parse(raw).let { pg -> pg.words.ifEmpty { pg.page?.selection?.let { "“$it”" }.orEmpty() } }
    val p = UserScreenMessage.parse(text)
    if (!p.hasScreen) {
        bubble(text)
        return
    }
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        p.screens.forEach { id -> ScreenItem(rememberShownScreen(conversationId, id), thumbWidth) }
        if (p.words.isNotEmpty()) bubble(p.words)
    }
}

private val ThumbShape = MikaShapes.Tile
private val TagText = MikaType.Micro

/** One screen: the thumbnail and the line below it; holds its place before it has loaded. */
@Composable
private fun ScreenItem(shown: ShownScreen?, width: Dp) {
    val c = MikaTheme.colors
    val screen = shown?.screen
    if (screen?.blocked == true) {
        Row(
            Modifier.testTag("screen-blocked").background(c.ink.copy(alpha = 0.05f), ThumbShape).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(MikaIcons.Lock, null, Modifier.size(15.dp), tint = c.ink2)
            Text(ScreenCopy.BLOCKED, style = MikaType.Hint, color = c.ink2)
        }
        return
    }
    val tag = screen?.let(ScreenCopy::tag) ?: ScreenCopy.TAG
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = tag }, horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScreenThumb(shown?.thumb, width, Modifier.testTag("screen-thumb"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(MikaIcons.Phone, null, Modifier.size(13.dp), tint = c.ink3)
            Text(tag, Modifier.testTag("screen-tag"), style = TagText, color = c.ink3)
        }
    }
}

/** Screenshot thumbnail: follows the image's aspect ratio, with a thin rounded border; when there is no image, a light grey block of the same size. */
@Composable
fun ScreenThumb(thumb: ImageBitmap?, width: Dp, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val ratio = thumb?.let { (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(0.3f, 1.8f) } ?: DEFAULT_RATIO
    Box(modifier.width(width).aspectRatio(ratio).clip(ThumbShape).border(1.dp, c.glassEdge, ThumbShape).background(c.ink.copy(alpha = 0.05f))) {
        thumb?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}

/** Placeholder aspect ratio based on a typical phone screen, used until the image has loaded. */
private const val DEFAULT_RATIO = 0.46f
