package com.wangzan101.mika.chat

import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/**
 * content:// addresses used for conversation page attachments (see res/xml/attach_files.xml); not exported, granted only temporarily when in use:
 * - camera/: photos taken by the system camera are written here (the camera app is granted write access to this single file while shooting) and copied into the conversation workspace when sent;
 * - viewing/: a copy made from the workspace when an already-sent photo or file is opened, handed to the App's viewer (or another app the user picks) to open read-only.
 * The workspace itself is not exposed. Read-only except for camera/; deletion and insertion are not allowed.
 */
class AttachFilesProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r" && uri.pathSegments.firstOrNull() != CAMERA) throw SecurityException("read-only")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** Matches the name in attach_files.xml: the first path segment of the address. */
        const val CAMERA = "camera"

        fun authority(packageName: String) = "$packageName.attachfiles"
    }
}
