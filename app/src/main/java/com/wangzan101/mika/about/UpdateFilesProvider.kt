package com.wangzan101.mika.about

import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/**
 * content:// address for the system installer to use for packages Claire downloaded itself when going through the network proxy
 * (the copy owned by the system download service gets its address from the service).
 * It only covers the update package directory (see res/xml/update_files.xml), is read-only, is not exported, and is granted temporarily for installation.
 */
class UpdateFilesProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read-only")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
