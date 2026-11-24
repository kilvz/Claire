package com.wangzan101.mika.cap.phone.a11y

/**
 * Freshness of the Agent screen picture (frame time see [AgentDisplay.lastFrameAt]): after launching an app, the
 * picture handed to the model must come from a frame rendered after that launch. Compares the virtual-display
 * producer's timestamp with the same-origin monotonic clock (nanoseconds) recorded at launch, so old frames that
 * were queued before the launch but read after it are not let through.
 * The wait budget is timed separately in milliseconds.
 * Clock, sleeping and the latest frame time are all injected by the caller so this can be tested on the JVM
 * (same as [StillFrameRead]).
 */
internal object FrameFreshness {
    /**
     * At launch wrap-up, the upper bound for waiting for the first frame after this launch: the first cold-start
     * frame and the splash animation are almost always within it.
     */
    const val FRAME_WAIT_MS = 1_500L
    const val POLL_MS = 60L

    /** Returns true once there is a frame later than [since]; returns false if [timeoutMs] passes with none (or with no frame at all). */
    suspend fun awaitNewFrame(
        now: () -> Long, sleep: suspend (Long) -> Unit, frameAt: () -> Long?, since: Long, timeoutMs: Long = FRAME_WAIT_MS,
    ): Boolean {
        val deadline = now() + timeoutMs
        while (true) {
            if ((frameAt() ?: 0L) > since) return true
            if (now() >= deadline) return false
            sleep(minOf(POLL_MS, deadline - now()))
        }
    }

    /**
     * Whether the current latest frame may be handed to the model right now: [Fresh] yes; [Guarded] means no
     * new frame since the launch, so the old frame is not served.
     */
    fun serve(frameAt: () -> Long?, since: Long?): Serve = when {
        since == null || (frameAt() ?: 0L) > since -> Serve.Fresh
        else -> Serve.Guarded
    }

    enum class Serve { Fresh, Guarded }
}
