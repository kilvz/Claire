package com.wangzan101.mika.voice

/**
 * Generation tokens for playback requests: every new playback request (begin) invalidates all earlier generations, and so does an explicit stop (invalidate).
 * The file-writing completion callback and the MediaPlayer callbacks of an old request (prepared/completion/error/focus) are first compared with [owns]
 * and dropped outright when they do not match — across screens and fast switching, an old request never leaks into a new one.
 * Read and written on the main thread only.
 */
internal class PlaybackTickets {
    private var generation = 0L

    /** New playback request: invalidates all earlier generations and returns this request's generation. */
    fun begin(): Long = ++generation

    /** Explicit stop: callbacks already issued and still in flight (such as prepare completing) are all invalidated. */
    fun invalidate() {
        generation++
    }

    /** Whether [ticket] is the current generation: only then may it change player state. */
    fun owns(ticket: Long): Boolean = ticket == generation
}
