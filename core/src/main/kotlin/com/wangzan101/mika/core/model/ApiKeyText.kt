package com.wangzan101.mika.core.model

import okhttp3.Request

/**
 * How a pasted API key is accepted.
 *
 * OkHttp 4.12's `Headers.checkValue` accepts only tabs and U+0020..U+007E. `\n`, `\r` and other control characters below 0x20,
 * as well as any character above 0x7E (Chinese, emoji, full-width symbols), make building the Request throw [IllegalArgumentException],
 * and this happens before the request is sent, so the caller's network try/catch does not cover it.
 *
 * Invisible characters such as zero-width ones and BOMs are simply removed, and the rest can still be used as an ordinary key. Newlines (`\n`, `\r`) and other characters that do not fit in a request header
 * are left as they are and marked as [Notice.IllegalCharacters] by [Accepted.notice]; the caller must not send or save them.
 */
object ApiKeyText {
    enum class Notice {
        /** Nothing was removed, and all characters fit in a request header. */
        None,
        /** Only invisible characters such as zero-width ones and BOMs were removed; what remains is a usable key. */
        InvisibleRemoved,
        /** Still contains characters that do not fit in a request header (including newlines). These characters were not changed. */
        IllegalCharacters,
    }

    data class Accepted(val text: String, val notice: Notice)

    /**
     * Accept a key from one input (a paste or a character-by-character edit): remove invisible zero-width characters and BOMs, leave the other characters unchanged.
     * `\n` and `\r` stay in the text and, like Chinese and emoji, count as not fitting in a request header.
     * Leading and trailing whitespace is kept and handled by the `trim()` at save time; ordinary spaces in the middle are also kept and used as-is when asking the provider.
     */
    fun accept(raw: String): Accepted {
        var removedInvisible = false
        val kept = StringBuilder(raw.length)
        for (c in raw) {
            if (isInvisible(c)) removedInvisible = true else kept.append(c)
        }
        val text = kept.toString()
        val notice = when {
            !isHeaderLegal(text) -> Notice.IllegalCharacters
            removedInvisible -> Notice.InvisibleRemoved
            else -> Notice.None
        }
        return Accepted(text, notice)
    }

    /**
     * Whether all of [value] can go into an OkHttp header value. The empty string is fine; tabs and U+0020..U+007E are fine;
     * other control characters and non-ASCII are not. It checks the whole value that will go into the header (including the `Bearer ` prefix).
     */
    fun isHeaderLegal(value: String): Boolean = value.all { c -> c == '\t' || c.code in 0x20..0x7E }

    /** Zero-width characters, BOMs, and formatting characters that change text direction. These are often brought in by pasting and are not part of the key itself. */
    private fun isInvisible(c: Char): Boolean = when (c) {
        '\u061C', // ARABIC LETTER MARK
        '\u180E', // MONGOLIAN VOWEL SEPARATOR
        '\uFEFF', // BOM / ZERO WIDTH NO-BREAK SPACE
        -> true
        in '\u200B'..'\u200F', // ZWSP, ZWNJ, ZWJ, LRM, RLM
        in '\u202A'..'\u202E', // bidirectional embedding / override
        in '\u2060'..'\u2069', // word joiner, invisible operators, bidirectional isolates
        -> true
        else -> false
    }
}

/**
 * Put [value] into the header named [name]. Throws [ModelError.IllegalHeader] when it is illegal, without calling OkHttp
 * (the message of the [IllegalArgumentException] it throws contains the raw header value and would write the key into the logs).
 */
fun Request.Builder.headerOrReject(name: String, value: String): Request.Builder {
    if (!ApiKeyText.isHeaderLegal(value)) throw ModelError.IllegalHeader(name)
    return header(name, value)
}
