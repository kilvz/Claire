package com.wangzan101.mika.chat.ui

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.chat.AttachFilesProvider
import com.wangzan101.mika.chat.WorkspaceResolver
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.ui.viewer.FileViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.wangzan101.mika.settings.ui.models.edit
import com.wangzan101.mika.settings.ui.models.use

/** What the conversation page's attachments need to read: name and size of picked files, the camera target, and opening already-sent attachments. All disk reads happen on a background thread. */
object AttachFiles {
    /** Name and size of the picked files (content URI strings); when the name cannot be read the last path segment is used, and an unreadable size becomes 0. */
    suspend fun describe(context: Context, uris: List<String>): List<PendingAttachment> = withContext(Dispatchers.IO) {
        uris.map { s ->
            val uri = Uri.parse(s)
            var name: String? = null
            var size = 0L
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            PendingAttachment(s, name?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "attachment", size)
        }
    }

    /** Where a camera photo goes: a new file under camera/ in the cache (photos older than a day are removed along the way), as the URI handed to the system camera. */
    fun newCameraUri(context: Context): Uri {
        val dir = File(context.cacheDir, "attach/camera").apply { mkdirs() }
        val stale = System.currentTimeMillis() - 24 * 3600_000L
        dir.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        var f = File(dir, "IMG_$stamp.jpg")
        var n = 2
        while (f.exists()) f = File(dir, "IMG_${stamp}_${n++}.jpg")
        return FileProvider.getUriForFile(context, AttachFilesProvider.authority(context.packageName), f)
    }

    /** The photo was not taken (cancelled): delete the empty file. */
    fun discardCamera(context: Context, uri: Uri) {
        val name = uri.lastPathSegment ?: return
        File(context.cacheDir, "attach/camera/$name").takeIf { it.name == name }?.delete()
    }

    /** Turns a workspace path (`/workspace/inbox/…`) into a workspace-relative path; null when it is not inside the workspace. */
    fun relative(path: String): String? =
        path.removePrefix(SandboxPaths.WORKSPACE + "/").takeIf { it != path && it.isNotEmpty() }

    /** Writes the stream to be viewed into viewing/ in the cache (clearing the previous one first) and returns a content URI that [FileViewer] can be granted to open. */
    private suspend fun copyToViewing(context: Context, name: String, copyTo: (File) -> Unit): Uri? {
        val safe = Attachments.safeName(name)
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val root = File(context.cacheDir, "attach/viewing")
                root.deleteRecursively()
                File(root, UUID.randomUUID().toString().take(8) + "/" + safe).apply {
                    parentFile?.mkdirs()
                    copyTo(this)
                }
            }.onFailure {
                Log.w("AttachFiles", "copyToViewing failed name=$safe", it)
            }.getOrNull()
        } ?: return null
        return FileProvider.getUriForFile(context, AttachFilesProvider.authority(context.packageName), file)
    }

    /**
     * Open an already-sent attachment: copy it from the conversation workspace (through the file gate) into viewing/ in the cache (clearing the previous one first),
     * then hand it to the in-app viewer; types the viewer cannot show go to a system app that can open them. Returns false when it cannot be read.
     */
    suspend fun openSent(context: Context, conversationId: String, path: String): Boolean {
        val rel = relative(path) ?: return false
        val name = path.substringAfterLast('/')
        val uri = copyToViewing(context, name) { out ->
            val dirs = SandboxDirs(context)
            dirs.gate.openRead(dirs.workspace(WorkspaceResolver.resolveFor(context, conversationId)), rel).use { g ->
                out.outputStream().use { g.input().copyTo(it) }
            }
        } ?: return false
        FileViewer.open(context, uri, name)
        return true
    }

    /**
     * Open a pending local attachment: for the app's own file URIs, open directly once readability is confirmed; other content URIs are first copied into the cache's viewing/ and then handed to [FileViewer]
     * (temporary grants from the system file picker cannot be forwarded to the viewer page or a third-party app via an Intent again). Returns false when it cannot be read or opened.
     */
    suspend fun openPending(context: Context, uri: String, name: String): Boolean {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()?.takeIf { !it.scheme.isNullOrBlank() } ?: return false
        val ownProvider = parsed.scheme == ContentResolver.SCHEME_CONTENT &&
            parsed.authority == AttachFilesProvider.authority(context.packageName)
        val viewUri = if (ownProvider) {
            val readable = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(parsed)?.use { true } ?: false }.getOrDefault(false)
            }
            if (!readable) return false
            parsed
        } else {
            val mime = runCatching { context.contentResolver.getType(parsed) }.getOrNull()
            val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            val copyName = if ('.' in name || ext.isNullOrBlank()) name else "$name.$ext"
            copyToViewing(context, copyName) { out ->
                val input = context.contentResolver.openInputStream(parsed) ?: error("Cannot open $parsed")
                input.use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
            } ?: return false
        }
        return runCatching {
            FileViewer.open(context, viewUri, name)
            true
        }.onFailure {
            Log.w("AttachFiles", "openPending failed uri=$uri", it)
        }.getOrDefault(false)
    }
}

/** Whether photos can be read directly: all, only a partial selection given (Android 14+), or none. */
enum class PhotoAccess { Full, Partial, None }

/** "Recent photos" in the attachment panel: permissions and photos from the media library. */
object RecentPhotos {
    private const val PREFS = "attach"
    private const val ASKED = "photos_asked"

    /** Permissions to request: Android 14+ asks for "selected photos" as well, 13 asks to read photos, and 12 and below ask to read external storage. */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun access(context: Context): PhotoAccess {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.Full
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.Partial
            Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.Full
            else -> PhotoAccess.None
        }
    }

    /** Asked once when the panel is opened for the first time; afterwards only when the user taps "Allow access to photos". */
    fun asked(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)

    fun markAsked(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()

    /** Photos most recently added to the media library, newest first; empty when they cannot be read (no permission). */
    suspend fun query(context: Context, limit: Int = 120): List<PendingAttachment> = withContext(Dispatchers.IO) {
        val base = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE)
        runCatching {
            context.contentResolver.query(base, projection, args, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(base, c.getLong(0)).toString()
                        add(PendingAttachment(uri, c.getString(1) ?: "photo.jpg", if (c.isNull(2)) 0L else c.getLong(2), photo = true))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }
}

/** One image in an attachment: either not yet sent (content URI) or already sent (path inside the conversation workspace). */
sealed interface AttachImage {
    data class Picked(val uri: String) : AttachImage
    data class Sent(val conversationId: String, val path: String) : AttachImage
}

/** Loads a downscaled image with the requested edge length in pixels; null when it cannot be read. UI tests substitute a fake. */
fun interface AttachImages {
    suspend fun load(image: AttachImage, px: Int): ImageBitmap?

    /** Returns an already-decoded image directly (so scrolling back and forth does not flash a placeholder); null when there is none. */
    fun cached(image: AttachImage, px: Int): ImageBitmap? = null
}

/** When provided, it is used to load attachment images (tests); otherwise images are read from the device. */
val LocalAttachImages = staticCompositionLocalOf<AttachImages?> { null }

/** Attachment images on the device: decoded downscaled (never the full image), on a background thread, with decoded results cached by byte size. */
private class StoredAttachImages(private val context: Context) : AttachImages {
    override fun cached(image: AttachImage, px: Int): ImageBitmap? = cache.get("$image@$px")

    override suspend fun load(image: AttachImage, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$image@$px"
        cache.get(key)?.let { return@withContext it }
        val bmp = runCatching {
            when (image) {
                is AttachImage.Picked -> context.contentResolver.loadThumbnail(Uri.parse(image.uri), Size(px, px), null).asImageBitmap()
                is AttachImage.Sent -> sentBytes(image)?.let { decodeSmall(it, px) }
            }
        }.getOrNull()
        bmp?.also { cache.put(key, it) }
    }

    private fun sentBytes(image: AttachImage.Sent): ByteArray? {
        val rel = AttachFiles.relative(image.path) ?: return null
        val dirs = SandboxDirs(context)
        return dirs.gate.openRead(dirs.workspace(WorkspaceResolver.resolveFor(context, image.conversationId)), rel).use { f ->
            if (f.size > MAX_BYTES) null else f.read(0, f.size.toInt())
        }
    }

    companion object {
        /** An image larger than this is not decoded at all (only the placeholder is shown). */
        const val MAX_BYTES = 40L shl 20
        val cache = object : LruCache<String, ImageBitmap>(24 shl 20) {
            override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
        }
    }
}

/** Decodes into a downscaled image whose short edge is at least [px] (subsampling by powers of 2), with the photo orientation corrected from EXIF. */
private fun decodeSmall(bytes: ByteArray, px: Int): ImageBitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
        val short = minOf(info.size.width, info.size.height)
        var sample = 1
        while (short / (sample * 2) >= px) sample *= 2
        d.setTargetSampleSize(sample)
    }.asImageBitmap()

/** Loads an attachment image (in the background); null while it has not loaded yet or when it cannot be read. */
@Composable
fun rememberAttachImage(image: AttachImage, px: Int): ImageBitmap? {
    val context = LocalContext.current
    val given = LocalAttachImages.current
    val images = remember(given, context) { given ?: StoredAttachImages(context.applicationContext) }
    val bmp by produceState(images.cached(image, px), image, px) {
        value = images.cached(image, px)
        if (value == null) value = runCatching { images.load(image, px) }.getOrNull()
    }
    return bmp
}
