package com.wangzan101.mika.chat

import kotlinx.serialization.Serializable

/** A file exported to "Downloads/Claire", shown as a file card in the conversation. [uri] is the MediaStore content URI used to open it. */
@Serializable
data class SharedFile(val name: String, val size: Long, val savedAs: String, val uri: String, val sha256: String? = null)
