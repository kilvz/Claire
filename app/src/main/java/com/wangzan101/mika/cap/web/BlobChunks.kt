package com.wangzan101.mika.cap.web

/**
 * Blob content (base64) handed back by the page in chunks. Nothing the page reports is trusted: the declared length, the per-chunk length, and the accumulated length.
 * A declared length beyond the base64 limit for [maxBytes] (4 characters per 3 bytes, plus padding allowance) is rejected before allocating;
 * nothing is pre-allocated from the declared length; each chunk carries at most [chunk] characters, and the total never exceeds the declared length.
 */
internal class BlobChunks(private val declared: Int, private val maxBytes: Long, private val chunk: Int = CHUNK) {
    sealed interface Outcome {
        class Ok(val bytes: ByteArray) : Outcome
        class Failed(val error: String) : Outcome
    }

    private val sb = StringBuilder()

    /** Whether the declared length can be accepted; when it cannot, why. */
    fun refusal(): String? = when {
        declared < 0 -> UNREADABLE
        declared > maxBase64(maxBytes) -> WebDownloads.TOO_LARGE
        else -> null
    }

    val received: Int get() = sb.length
    val complete: Boolean get() = sb.length >= declared

    /** Accept the next chunk; returns a failure reason when it is too long or exceeds the declared length. */
    fun add(part: String): String? {
        if (part.length > chunk) return UNREADABLE
        if (sb.length.toLong() + part.length > declared) return UNREADABLE
        sb.append(part)
        return null
    }

    fun finish(): Outcome {
        val bytes = runCatching { java.util.Base64.getDecoder().decode(sb.toString()) }.getOrNull() ?: return Outcome.Failed(UNREADABLE)
        if (bytes.size > maxBytes) return Outcome.Failed(WebDownloads.TOO_LARGE)
        return Outcome.Ok(bytes)
    }

    companion object {
        /** Matches BLOB_CHUNK in page.js. */
        const val CHUNK = 1 shl 20
        const val UNREADABLE = "unreadable"

        fun maxBase64(maxBytes: Long): Long = maxBytes * 4 / 3 + 8
    }
}
