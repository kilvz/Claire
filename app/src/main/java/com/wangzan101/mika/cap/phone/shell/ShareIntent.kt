package com.wangzan101.mika.cap.phone.shell

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/** Text share: Android's ACTION_SEND, type text/plain, text in EXTRA_TEXT, delivered to a single package only. */
object ShareIntent {
    const val TYPE = "text/plain"

    fun of(packageName: String, text: String): Intent = Intent(Intent.ACTION_SEND).setType(TYPE).putExtra(Intent.EXTRA_TEXT, text)
        .setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** File share: ACTION_SEND + EXTRA_STREAM, delivered to a single package only; the content URI is granted for the recipient to read. */
    fun ofFile(packageName: String, uri: Uri, mime: String, text: String?): Intent = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .setPackage(packageName)
        .apply {
            if (!text.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, text)
            setClipData(ClipData.newRawUri(null, uri))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /**
     * Arguments for `am start` when the system APIs are unavailable (not interpreted by a shell; the text is
     * passed as-is as a single argument). [component] is the short name of the share-receiving activity, used
     * when the package has only one, targeting it directly; when there are several, only the package is
     * constrained and the system asks which one to use.
     */
    fun amArgs(displayId: Int, packageName: String, text: String, component: String?): List<String> =
        listOf("am", "start", "--display", "$displayId", "-a", Intent.ACTION_SEND, "-t", TYPE, "--es", Intent.EXTRA_TEXT, text) +
            (component?.let { listOf("-n", it) } ?: listOf("-p", packageName))

    /** `am start` arguments for a file share: EXTRA_STREAM is passed via `--eu`, and the recipient may read this content URI. */
    fun amArgsFile(displayId: Int, packageName: String, uri: Uri, mime: String, text: String?, component: String?): List<String> =
        listOf("am", "start", "--display", "$displayId", "-a", Intent.ACTION_SEND, "-t", mime,
            "--eu", Intent.EXTRA_STREAM, uri.toString(), "--grant-read-uri-permission") +
            (text?.takeIf { it.isNotBlank() }?.let { listOf("--es", Intent.EXTRA_TEXT, it) } ?: emptyList()) +
            (component?.let { listOf("-n", it) } ?: listOf("-p", packageName))
}
