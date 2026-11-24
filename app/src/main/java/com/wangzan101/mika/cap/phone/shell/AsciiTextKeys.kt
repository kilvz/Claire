package com.wangzan101.mika.cap.phone.shell

/** Result of synchronous keyboard injection and the ASCII range; control characters may trigger other editor actions, so they are not treated as text input. */
internal object AsciiTextKeys {
    const val NOT_SENT = 0
    const val DISPATCHED = 1
    const val UNCONFIRMED = 2

    fun supports(text: String): Boolean = text.length in 1..256 && text.all { it in ' '..'~' }
}
