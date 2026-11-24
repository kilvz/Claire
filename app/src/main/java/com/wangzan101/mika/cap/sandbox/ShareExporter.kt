package com.wangzan101.mika.cap.sandbox

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.workspace.GatedFile
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.cap.sandbox.ShareRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

class FileTooLarge(val bytes: Long) : Exception("file is larger than ${ShareRules.MAX_BYTES shr 20} MB")

/** Save a file from the workspace to the phone's "Downloads/Claire" (MediaStore, no storage permission required). */
class ShareExporter(private val context: Context) {
    /**
     * [file] has already been verified by the gate (opened once without following links, and confirmed by handle to be inside its own workspace, a regular file with exactly one hard link);
     * here the size is additionally checked to be at most 100 MB, and then the copy is made from that same handle. If the file is swapped for a link or another file after the check, nothing outside can still be read.
     * Returns the exported file, whose savedAs is "Download/Claire/<actual name>" (the system appends a sequence number on a name collision); Claire's private paths are never handed out.
     */
    suspend fun export(file: GatedFile, requestedName: String): SharedFile = withContext(Dispatchers.IO) {
        if (!ShareRules.plainName(requestedName)) throw SandboxError("invalid_arguments", "name: must be a plain file name", recoverable = true)
        val name = ShareRules.fitName(requestedName)
        if (ShareRules.refusedType(name) || ShareRules.refusedType(file.name)) {
            throw SandboxError("share_refused_type", "App installation packages cannot be shared.", recoverable = false)
        }
        if (file.size > ShareRules.MAX_BYTES) throw FileTooLarge(file.size)
        copyOut(file.input(), name)
    }

    private suspend fun copyOut(input: InputStream, name: String): SharedFile {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/Claire/")
            mimeOf(name)?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    // Once the session is closed (task cancelled, command finished) stop writing
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    size += n
                    // The file grew after the check: apply the same limit
                    if (size > ShareRules.MAX_BYTES) throw FileTooLarge(size)
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                }
            }
            currentCoroutineContext().ensureActive()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        val actual = displayName(uri) ?: name
        return SharedFile(actual, size, "Download/Claire/$actual", uri.toString(), digest.digest().joinToString("") { "%02x".format(it) })
    }

    /**
     * Delete unfinished entries this App left behind in "Downloads/Claire" (being killed mid-write leaves an IS_PENDING shell). Called at startup.
     */
    fun cleanPending() {
        val resolver = context.contentResolver
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("${android.os.Environment.DIRECTORY_DOWNLOADS}/Claire/%", context.packageName))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val ids = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }.orEmpty()
        for (id in ids) runCatching { resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null) }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun mimeOf(name: String): String? =
        name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
}
