package com.wangzan101.mika.media

import android.net.Uri

/** A file that can be handed to another app to read: a content URI and a MIME type. */
class ShareableFile(val uri: Uri, val mime: String)
