package com.wangzan101.mika.voicecall

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import com.wangzan101.mika.calls.VolcRealtimeClient
import com.wangzan101.mika.voice.PcmRecorder
import com.wangzan101.mika.voice.pcm16MinBuffer
import com.wangzan101.mika.voice.pcm16Track
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The complete abstraction of voice call audio devices: capture, playback, audio focus and call mode.
 * The orchestration layer depends only on this interface; tests use hand-written fakes, and the production implementation is [AndroidVoiceCallAudio].
 */
interface VoiceCallAudio {
    /** Set to true when the system silences the microphone (e.g. taken over by another component). */
    val micSilenced: StateFlow<Boolean>

    /** Capture chunk stream: 16 kHz mono PCM16, about 20 ms per chunk; the channel is closed when capture stops or fails. */
    val captureChunks: ReceiveChannel<ShortArray>

    /** Callback when audio focus is taken away (on any thread); the orchestration layer ends the call on it. */
    var onFocusLost: (() -> Unit)?

    /** Starting a voice call is not allowed while telephony is busy (in a call or ringing). */
    fun isTelephonyBusy(): Boolean

    /** Enter call audio mode and request audio focus; returns false on failure. */
    fun requestCallAudio(): Boolean

    /** Abandon audio focus and restore normal audio mode; safe to call repeatedly. */
    fun abandonCallAudio()

    /**
     * Start capture with the given audio source ([android.media.MediaRecorder.AudioSource]); returns false if initialization fails,
     * and whether to retry with another source is left to the orchestration layer (the fallback chain does not live here).
     */
    fun startCapture(inputSource: Int): Boolean

    /** Stop capture and release the microphone; safe to call repeatedly. */
    fun stopCapture()

    /** Create and start the 24 kHz mono PCM16 playback track; returns false on failure. */
    fun startPlayback(): Boolean

    /** Blocking write of one whole chunk of 24 kHz mono PCM16; throws [IOException] if the track is dead. */
    suspend fun writePlayback(pcm: ByteArray, byteCount: Int)

    /** barge-in: pause/flush/play, dropping the old audio already in the hardware buffer immediately. */
    fun interruptPlayback()

    /** Stop and release the playback track; safe to call repeatedly. */
    fun stopPlayback()
}

/** Production implementation: a thin wrapper around AudioRecord (via [PcmRecorder]), AudioTrack and AudioManager, with no policy of its own. */
@SuppressLint("MissingPermission") // RECORD_AUDIO is requested by the call panel before starting the session
class AndroidVoiceCallAudio(context: Context) : VoiceCallAudio {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val _micSilenced = MutableStateFlow(false)
    override val micSilenced: StateFlow<Boolean> = _micSilenced
    private val noCapture = Channel<ShortArray>(Channel.RENDEZVOUS).also { it.close() }
    override val captureChunks: ReceiveChannel<ShortArray>
        get() = recorder?.chunks ?: noCapture
    @Volatile override var onFocusLost: (() -> Unit)? = null

    private var recorder: PcmRecorder? = null
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private val silencedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var silencedJob: Job? = null

    override fun isTelephonyBusy(): Boolean {
        val mode = manager.mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE
    }

    override fun requestCallAudio(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    onFocusLost?.invoke()
                }
            }
            .build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focusRequest = request
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        return true
    }

    override fun abandonCallAudio() {
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
        manager.mode = AudioManager.MODE_NORMAL
    }

    override fun startCapture(inputSource: Int): Boolean {
        val next = PcmRecorder(streaming = true, unbounded = true, inputSource = inputSource, chunkMs = 20)
        if (!next.start()) return false
        recorder = next
        _micSilenced.value = false
        silencedJob = silencedScope.launch { next.silenced.collect { _micSilenced.value = it } }
        return true
    }

    override fun stopCapture() {
        silencedJob?.cancel()
        silencedJob = null
        recorder?.cancel()
        recorder = null
        _micSilenced.value = false
    }

    override fun startPlayback(): Boolean {
        val minBuffer = pcm16MinBuffer(VolcRealtimeClient.OUTPUT_SAMPLE_RATE)
        if (minBuffer <= 0) return false
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        // Builder swaps the constructor's IllegalArgumentException for UnsupportedOperationException; both count as "failed to start"
        val next = try {
            pcm16Track(attributes, VolcRealtimeClient.OUTPUT_SAMPLE_RATE, maxOf(minBuffer, WRITE_BUFFER_BYTES))
        } catch (_: IllegalArgumentException) {
            return false
        } catch (_: UnsupportedOperationException) {
            return false
        }
        if (next.state != AudioTrack.STATE_INITIALIZED) {
            next.release()
            return false
        }
        return try {
            next.play()
            track = next
            true
        } catch (_: IllegalStateException) {
            next.release()
            false
        }
    }

    override suspend fun writePlayback(pcm: ByteArray, byteCount: Int) {
        val output = track ?: throw IOException("Voice call playback is not started")
        var written = 0
        while (written < byteCount) {
            currentCoroutineContext().ensureActive()
            when (val n = output.write(pcm, written, byteCount - written, AudioTrack.WRITE_NON_BLOCKING)) {
                0 -> delay(2)
                in 1..Int.MAX_VALUE -> written += n
                else -> throw IOException("Voice call playback write failed ($n)")
            }
        }
    }

    override fun interruptPlayback() {
        val output = track ?: return
        try {
            output.pause()
        } catch (_: IllegalStateException) {
            return
        }
        output.flush()
        try {
            output.play()
        } catch (_: IllegalStateException) {
            // The next writePlayback will fail with a track-state error, which the orchestration layer classifies as a playback error
        }
    }

    override fun stopPlayback() {
        track?.let { output ->
            try {
                output.pause()
                output.flush()
            } catch (_: IllegalStateException) {
            }
            output.release()
        }
        track = null
    }

    private companion object {
        const val WRITE_BUFFER_BYTES = VolcRealtimeClient.OUTPUT_SAMPLE_RATE * 2 / 25 // 40ms
    }
}
