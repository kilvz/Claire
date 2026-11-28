package com.wangzan101.mika.telegram

import android.content.Context
import android.net.Uri
import com.wangzan101.mika.core.run.Delivery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

/** Reads an image exported by share_file so it can be uploaded. Tests substitute their own reader. */
fun interface TelegramImageReader {
    suspend fun read(image: Delivery.Image): ByteArray
}

/** Opens the MediaStore export recorded by share_file; nothing else on the phone is readable to the channel. */
internal class TelegramImageFiles(context: Context) : TelegramImageReader {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun read(image: Delivery.Image): ByteArray = withContext(Dispatchers.IO) {
        readTelegramExport(image) { uri -> runCatching { resolver.openInputStream(Uri.parse(uri)) }.getOrNull() }
    }
}

/**
 * Only explicit MediaStore exports can be sent. Verify the recorded size and
 * hash before uploading; the Bot API accepts at most 50 MB per file.
 */
internal fun readTelegramExport(image: Delivery.Image, open: (String) -> InputStream?): ByteArray {
    val uri = try { URI(image.uri) } catch (_: Exception) { throw IOException("Invalid image export") }
    if (uri.scheme != "content" || uri.authority != "media" || uri.query != null || uri.fragment != null)
        throw IOException("Not a shared image export")
    if (image.size <= 0 || image.size > MAX_BYTES) throw IOException("Image is too large to send")
    val bytes = (open(image.uri) ?: throw IOException("Image export is unavailable")).use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (out.size().toLong() + count > image.size) throw IOException("Image export changed")
            out.write(buffer, 0, count)
        }
        if (out.size().toLong() != image.size) throw IOException("Image export changed")
        out.toByteArray()
    }
    image.sha256?.let { expected ->
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) throw IOException("Image export changed")
    }
    return bytes
}

/** The Bot API upload limit for files sent through the cloud servers. */
internal const val MAX_BYTES = 50L * 1024 * 1024
