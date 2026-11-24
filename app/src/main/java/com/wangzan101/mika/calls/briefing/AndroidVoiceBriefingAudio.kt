package com.wangzan101.mika.calls.briefing

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import com.wangzan101.mika.calls.CallPlaybackQueue
import com.wangzan101.mika.calls.CallInputPacer
import com.wangzan101.mika.calls.VolcRealtimeClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ordinary foreground microphone/speaker only. No telephony source, routing, or call audio API. */
internal class AndroidVoiceBriefingAudio(context: Context) : VoiceBriefingAudio {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val stopped = AtomicBoolean(false)
    private val audioFailed = AtomicBoolean(false)
    private val abandoningFocus = AtomicBoolean(false)
    @Volatile private var failureCallback: ((String) -> Unit)? = null
    private val lock = Any()
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var echo: AcousticEchoCanceler? = null
    private var focus: AudioFocusRequest? = null
    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    private val playbackQueue = CallPlaybackQueue(VOICE_BRIEFING_MAX_PENDING_BYTES)

    @SuppressLint("MissingPermission") // The controller checks permission before and throughout capture.
    override suspend fun start(
        scope: CoroutineScope,
        mayUseAudio: () -> Boolean,
        send: (ByteArray) -> Boolean,
        onFailure: (String) -> Unit,
    ) {
        failureCallback = onFailure
        withContext(Dispatchers.IO) {
            checkGate(mayUseAudio)
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val requestedFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { change ->
                    if (change != AudioManager.AUDIOFOCUS_GAIN && !abandoningFocus.get()) {
                        failAudio("Other audio interrupted the voice briefing; no call was placed. Please give the briefing again")
                    }
                }.build()
            focus = requestedFocus
            if (manager?.requestAudioFocus(requestedFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw VoiceBriefingFailure("The microphone and speaker are not available right now; no call was placed")
            }
            synchronized(lock) {
                checkGate(mayUseAudio)
                val minInput = AudioRecord.getMinBufferSize(INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val minOutput = AudioTrack.getMinBufferSize(OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minInput <= 0 || minOutput <= 0) throw VoiceBriefingFailure("Voice input or output is unavailable on this device; no call was placed")
                recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, INPUT_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minInput, CHUNK_BYTES * 4))
                player = AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(OUTPUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minOutput, 9_600)).build()
                if (recorder!!.state != AudioRecord.STATE_INITIALIZED || player!!.state != AudioTrack.STATE_INITIALIZED) {
                    throw VoiceBriefingFailure("Voice input or output failed to start on this device; no call was placed")
                }
                if (AcousticEchoCanceler.isAvailable()) {
                    echo = AcousticEchoCanceler.create(recorder!!.audioSessionId)?.also { it.enabled = true }
                }
                checkGate(mayUseAudio)
                recorder!!.startRecording()
                player!!.play()
                if (recorder!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw VoiceBriefingFailure("The microphone failed to start recording; no call was placed")
                }
            }
        }
        recordingJob = scope.launch(Dispatchers.IO) {
            var lastFrame = SystemClock.elapsedRealtime()
            val pacer = CallInputPacer()
            val frame = ByteArray(CHUNK_BYTES)
            var filled = 0
            try {
                while (true) {
                    ensureActive()
                    checkGate(mayUseAudio)
                    val count = synchronized(lock) {
                        checkGate(mayUseAudio)
                        if (recorder!!.activeRecordingConfiguration?.isClientSilenced == true) {
                            throw VoiceBriefingFailure("The system stopped providing microphone audio; no call was placed")
                        }
                        recorder!!.read(frame, filled, frame.size - filled, AudioRecord.READ_NON_BLOCKING)
                    }
                    checkGate(mayUseAudio)
                    if (count < 0 || count % 2 != 0) throw VoiceBriefingFailure("Microphone capture failed; no call was placed")
                    if (count == 0) {
                        if (SystemClock.elapsedRealtime() - lastFrame > 1_500) throw VoiceBriefingFailure("Microphone capture stopped; no call was placed")
                        delay(5)
                        continue
                    }
                    filled += count
                    if (filled < frame.size) continue
                    // A normal scheduler pause is not the end of the owner's briefing.
                    // Reuse the call input's absolute media clock: rebase after a delay,
                    // then resume at 20 ms without bursting or adding per-send drift.
                    if (!pacer.send {
                            checkGate(mayUseAudio)
                            send(frame)
                        }) throw VoiceBriefingFailure("Sending voice failed; no call was placed")
                    lastFrame = SystemClock.elapsedRealtime()
                    filled = 0
                }
            } finally { frame.fill(0) }
        }
        playbackJob = scope.launch(Dispatchers.IO) {
            val pcm = ByteArray(CallPlaybackQueue.WRITE_BYTES)
            var block: CallPlaybackQueue.Block? = null
            var offset = 0
            var lastProgress = SystemClock.elapsedRealtime()
            try {
                while (true) {
                    ensureActive()
                    checkGate(mayUseAudio)
                    if (block == null) {
                        block = playbackQueue.take(pcm) ?: break
                        offset = 0
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                    synchronized(lock) {
                        checkGate(mayUseAudio)
                        val current = block!!
                        if (!playbackQueue.isCurrent(current)) {
                            pcm.fill(0)
                            block = null
                        } else {
                            val count = player!!.write(pcm, offset, current.byteCount - offset, AudioTrack.WRITE_NON_BLOCKING)
                            checkGate(mayUseAudio)
                            if (count < 0 || count % 2 != 0) throw VoiceBriefingFailure("Voice playback failed; no call was placed")
                            if (count > 0) {
                                lastProgress = SystemClock.elapsedRealtime()
                                offset += count
                                if (offset == current.byteCount) {
                                    playbackQueue.release(current)
                                    pcm.fill(0)
                                    block = null
                                }
                            } else if (SystemClock.elapsedRealtime() - lastProgress > 2_000) {
                                throw VoiceBriefingFailure("Voice playback stopped responding; no call was placed")
                            }
                        }
                    }
                    delay(5)
                }
            } finally {
                pcm.fill(0)
                block?.let(playbackQueue::release)
            }
        }
    }

    override fun enqueue(pcm: ByteArray) = synchronized(lock) {
        if (stopped.get()) return@synchronized
        // Bound actual PCM duration (including the writer's in-flight block), not the
        // provider's packet count. Short replies can arrive much faster than real-time.
        if (playbackQueue.offer(pcm) == null) throw VoiceBriefingFailure("The voice playback buffer is full; no call was placed")
    }

    override fun interrupt(): Long = synchronized(lock) {
        playbackQueue.invalidate()
        val track = player ?: return@synchronized 0L
        if (stopped.get()) return@synchronized 0L
        track.pause()
        val heardFrames = runCatching { track.playbackHeadPosition.toLong() and 0xffff_ffffL }.getOrDefault(0L)
        track.flush()
        track.play()
        heardFrames
    }

    override fun stopImmediately() {
        stopped.set(true)
        synchronized(lock) {
            playbackQueue.close()
            runCatching { recorder?.stop() }
            runCatching { player?.pause(); player?.flush() }
        }
        recordingJob?.cancel()
        playbackJob?.cancel()
    }

    override suspend fun close() {
        stopImmediately()
        recordingJob?.cancelAndJoin()
        playbackJob?.cancelAndJoin()
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                runCatching { echo?.release() }; echo = null
                runCatching { recorder?.release() }; recorder = null
                runCatching { player?.release() }; player = null
            }
            // Ignore notifications caused by our own deliberate focus release. Stopping media
            // earlier is not failure suppression: an external loss during cleanup still revokes
            // pending authorization until this explicit abandonment point.
            abandoningFocus.set(true)
            focus?.let { runCatching { manager?.abandonAudioFocusRequest(it) } }
            focus = null
        }
    }

    private fun checkGate(gate: () -> Boolean) {
        requireVoiceBriefingAudioActive(stopped.get(), gate)
    }

    private fun failAudio(message: String) {
        if (!audioFailed.compareAndSet(false, true)) return
        try {
            failureCallback?.invoke(message)
        } finally {
            stopImmediately()
        }
    }

    private companion object {
        const val INPUT_RATE = VolcRealtimeClient.INPUT_SAMPLE_RATE
        const val OUTPUT_RATE = VolcRealtimeClient.OUTPUT_SAMPLE_RATE
        const val CHUNK_BYTES = VolcRealtimeClient.INPUT_CHUNK_BYTES
    }
}

// A bounded 576 KB accommodates ordinary model replies delivered in a burst. The shared
// queue includes in-flight PCM in this budget and invalidates old blocks on interruption.
internal const val VOICE_BRIEFING_MAX_PENDING_BYTES = VolcRealtimeClient.OUTPUT_SAMPLE_RATE * 2 * 12
