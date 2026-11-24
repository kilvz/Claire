package com.wangzan101.mika.voice

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A single push-to-talk session: both engines (the phone's built-in recognition and provider transcription) look the same to the UI:
 * the UI only receives [events], calls [release] on release and [cancel] on cancel, with no need to care which engine is behind it.
 */
sealed interface SpeechEvent {
    /** 0..1, for drawing the waveform. */
    data class Level(val value: Float) : SpeechEvent

    /** Everything heard so far: [tail] is the trailing few characters that may still change, the rest is [stable]. */
    data class Partial(val stable: String, val tail: String) : SpeechEvent

    /** Provider: converting after release. */
    data object Converting : SpeechEvent

    /** Final text; empty string = nothing was heard clearly. */
    data class Final(val text: String) : SpeechEvent

    data class Failed(val why: SpeechFailure) : SpeechEvent
}

sealed interface SpeechFailure {
    data object NoPermission : SpeechFailure
    data object Busy : SpeechFailure
    data object Network : SpeechFailure
    /** The local recorder cannot start (the recording used for provider transcription): it may be held by another app; a recognition service init failure is explained separately. */
    data object MicUnavailable : SpeechFailure
    /**
     * The recognition service reports an audio error (ERROR_AUDIO): even with the recording permission granted nothing may be captured, e.g. when the system-wide microphone toggle is off,
     * or the microphone is switched off separately in the system permission manager. The UI explains where to look based on this.
     */
    data object AudioError : SpeechFailure
    /**
     * The recognition service says it has no permission, and it can be determined that it is itself (another app) that lacks the recording permission, not Claire (see [SpeechServiceMic]).
     * The UI explains this and takes the user to that app's settings page.
     */
    data class ServiceNoPermission(val service: SpeechServiceTarget) : SpeechFailure
    data class Provider(val error: Exception) : SpeechFailure
    data class Unavailable(val why: SpeechEngineSpec.Why) : SpeechFailure
}

interface SpeechSession {
    /** Only one collector; each event is delivered once, and the flow ends after [SpeechEvent.Final]/[SpeechEvent.Failed] or after [cancel]. */
    val events: Flow<SpeechEvent>

    /** Produces text while speaking (true for the system and native streaming ASR, false for whole-file transcription). */
    val live: Boolean

    /**
     * Hold for as long as you like: streaming recognition that sends while speaking and does not buffer the whole recording, not subject to the 60-second push-to-talk cap and with no countdown.
     * Defaults to false (the phone's built-in recognition, whole-segment transcription and the other engines are capped as before).
     */
    val unlimited: Boolean get() = false

    /** Release: wrap up and emit Final. */
    fun release()

    /** Drop it; idempotent. */
    fun cancel()
}

/**
 * Event outlet of a session: unbounded buffer, so nothing is lost if the UI starts collecting a little later; closed after the terminal event is sent.
 * On cancel, everything not yet collected is dropped too — the user no longer wants this attempt, and a late result must not surface.
 */
internal class SessionEvents {
    private val channel = Channel<SpeechEvent>(Channel.UNLIMITED)

    @Volatile
    var cancelled = false
        private set

    @Volatile
    var finished = false
        private set

    val flow: Flow<SpeechEvent> = channel.receiveAsFlow().filter { !cancelled }

    fun send(e: SpeechEvent) {
        if (!finished && !cancelled) channel.trySend(e)
    }

    /** Send the last event and close; does nothing if already finished or cancelled. Returns whether this call actually sent it. */
    @Synchronized
    fun finish(e: SpeechEvent): Boolean {
        if (finished || cancelled) return false
        finished = true
        channel.trySend(e)
        channel.close()
        return true
    }

    /** Returns whether this was the first cancel. */
    @Synchronized
    fun cancel(): Boolean {
        if (cancelled) return false
        cancelled = true
        channel.close()
        return true
    }
}

/** A session unusable from the start (no permission, engine unavailable): emits only a single Failed. */
internal class FailedSession(why: SpeechFailure) : SpeechSession {
    private val out = SessionEvents().also { it.finish(SpeechEvent.Failed(why)) }
    override val events: Flow<SpeechEvent> = out.flow
    override val live = false
    override fun release() {}
    override fun cancel() { out.cancel() }
}
