package com.wangzan101.mika.web

import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/**
 * Provides content:// URIs for workspace files to the web page's file chooser. Read-only: no writing, deleting or inserting;
 * covers only the sandbox work/ directory (see res/xml/web_files.xml) and is not exported.
 */
class WebFilesProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read-only")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
