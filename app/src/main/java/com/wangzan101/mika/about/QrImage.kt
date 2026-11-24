package com.wangzan101.mika.about

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.wangzan101.mika.R

/** The author's WeChat QR code (bundled with the app; replacing the code only means replacing res/drawable-nodpi/wechat_qr.jpg). */
object QrImage {
    val RES = R.drawable.wechat_qr
    private const val FILE_NAME = "Claire-author-wechat.jpg"

    /**
     * Saved into the gallery's Pictures/Claire. Writing a newly created image through MediaStore needs no storage permission (true
     * since Android 10, and Claire supports Android 11 as its minimum).
     * It stays pending until fully written, so other apps never see half an image; the row is deleted on failure.
     */
    fun saveToPictures(context: Context): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Claire")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return false
        return runCatching {
            resolver.openOutputStream(uri)!!.use { out -> context.resources.openRawResource(RES).use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
