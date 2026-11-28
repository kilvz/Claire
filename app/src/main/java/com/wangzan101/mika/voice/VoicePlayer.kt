package com.wangzan101.mika.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import com.wangzan101.mika.core.model.StreamingTtsModel
import com.wangzan101.mika.core.model.TtsAudioPart
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File

/** Claire's current read-aloud state. */
sealed interface VoicePlayback {
    data object Idle : VoicePlayback
    data object Loading : VoicePlayback
    data object Playing : VoicePlayback
    data class Error(val message: String) : VoicePlayback
}

/**
 * On-device playback. Streaming PCM data is fed to AudioTrack as it arrives, and synthesized audio written to a cache file goes to MediaPlayer.
 * Only one item plays at a time and the new one replaces the old: every playback takes a generation, and callbacks still in flight from prepare or from an old player are never applied.
 * Requests system audio focus (speech content, transient exclusive); pauses/lowers when a call comes in or another app makes sound, and releases on completion, error or stop.
 */
class VoicePlayer(private val context: Context) {
    private val _state = MutableStateFlow<VoicePlayback>(VoicePlayback.Idle)
    val state: StateFlow<VoicePlayback> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var pcmTrack: PcmAudioTrack? = null
    private var pendingPlayback: Job? = null
    private var file: File? = null
    private var focus: AudioFocusRequest? = null
    private val tickets = PlaybackTickets()
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Plays a piece of synthesized audio; the extension is guessed from [mime] and MediaPlayer decodes by file content. */
    suspend fun play(bytes: ByteArray, mime: String) = loadAndPlay { ticket ->
        prepareFile(ticket, bytes, mime)
    }

    /** Plays a finished clip and waits until it finishes or fails; for audition tests that need to know whether sound really came out and support stopping at any time. */
    suspend fun playAndAwait(bytes: ByteArray, mime: String, onStarted: () -> Unit = {}) = loadAndPlay { ticket ->
        val completion = CompletableDeferred<VoicePlayback>()
        prepareFile(ticket, bytes, mime, onStarted, completion)
        val result = completion.await()
        if (result is VoicePlayback.Error) error(result.message)
        if (tickets.owns(ticket)) _state.value = VoicePlayback.Idle
    }

    /** Streams while synthesizing when the service supports it; other services or speech rates keep using the full audio response. */
    suspend fun play(
        model: TtsModel,
        request: TtsRequest,
        streaming: Boolean,
        onStarted: () -> Unit = {},
    ) = playRequests(model, listOf(request), streaming, onStarted)

    /** Reads the segments of one reply in order. */
    internal suspend fun playRequests(
        model: TtsModel,
        requests: List<TtsRequest>,
        streaming: Boolean,
        onStarted: () -> Unit = {},
    ) = loadAndPlay { ticket ->
        val fallback = if (streaming && model is StreamingTtsModel) {
            val response = playStreams(ticket, model, requests, onStarted)
            drainPcm(if (response == null) VoicePlayback.Idle else VoicePlayback.Loading)
            response ?: return@loadAndPlay
        } else {
            null
        }
        // After a fallback, synthesize segment by segment, taking the next one once the current file finishes playing.
        for (index in (fallback?.index ?: 0)..requests.lastIndex) {
            val audio = if (fallback?.index == index) fallback.response else model.synthesize(requests[index])
            _state.value = VoicePlayback.Loading
            val completion = if (index < requests.lastIndex) CompletableDeferred<VoicePlayback>() else null
            prepareFile(ticket, audio.bytes, audio.mime, { if (index == 0) onStarted() }, completion)
            val result = completion?.await()
            if (result is VoicePlayback.Error) error(result.message)
        }
    }

    private suspend fun loadAndPlay(block: suspend (Long) -> Unit) = withContext(Dispatchers.Main.immediate) {
        stop()
        // Take the generation before suspending: if a new request arrives while synthesizing/writing the file, this generation is invalidated and later callbacks are never applied
        val ticket = tickets.begin()
        val job = currentCoroutineContext().job
        pendingPlayback = job
        _state.value = VoicePlayback.Loading
        try {
            block(ticket)
        } catch (e: CancellationException) {
            if (pendingPlayback === job) release(VoicePlayback.Idle)
            throw e
        } catch (e: Exception) {
            if (pendingPlayback === job) release(VoicePlayback.Error(e.message ?: "cannot play audio"))
            throw e
        } finally {
            if (pendingPlayback === job) pendingPlayback = null
        }
    }

    private sealed interface StreamEvent {
        data class Pcm(val index: Int, val part: TtsAudioPart.Pcm) : StreamEvent
        data class Fallback(val index: Int, val response: TtsResponse) : StreamEvent
        data class Failed(val error: Exception) : StreamEvent
    }

    private suspend fun playStreams(
        ticket: Long,
        model: StreamingTtsModel,
        requests: List<TtsRequest>,
        onStarted: () -> Unit,
    ): StreamEvent.Fallback? {
        var playingIndex = -1
        var fallback: StreamEvent.Fallback? = null
        streamRequests(model, requests).collect { event ->
            when (event) {
                is StreamEvent.Pcm -> {
                    val part = event.part
                    val current = pcmTrack
                    if (event.index != playingIndex && current != null) {
                        if (current.sampleRate == part.sampleRate && current.channels == part.channels) {
                            current.writeSilence(SEGMENT_PAUSE_MS)
                        } else {
                            drainPcm(VoicePlayback.Loading)
                        }
                    }
                    playPcmChunk(ticket, part) { if (event.index == 0) onStarted() }
                    playingIndex = event.index
                }
                is StreamEvent.Fallback -> fallback = event
                is StreamEvent.Failed -> {
                    pcmTrack?.drain()
                    throw event.error
                }
            }
        }
        return fallback
    }

    private fun streamRequests(model: StreamingTtsModel, requests: List<TtsRequest>): Flow<StreamEvent> =
        flow<StreamEvent> {
            for ((index, request) in requests.withIndex()) {
                var complete: TtsResponse? = null
                var receivedPcm = false
                model.streamAudio(request).collect { part ->
                    check(complete == null) { "unexpected audio after a complete response" }
                    when (part) {
                        is TtsAudioPart.Pcm -> {
                            emit(StreamEvent.Pcm(index, part))
                            receivedPcm = true
                        }
                        is TtsAudioPart.Complete -> {
                            check(!receivedPcm) { "unexpected complete audio after PCM" }
                            complete = part.response
                        }
                    }
                }
                val response = complete
                check(receivedPcm || response != null) { "empty audio stream" }
                if (response != null) {
                    emit(StreamEvent.Fallback(index, response))
                    return@flow
                }
            }
        }.catch { error ->
            // Play the PCM already received, then report the later synthesis failure.
            if (error is CancellationException || error !is Exception) throw error
            emit(StreamEvent.Failed(error))
        }.buffer(PCM_LOOKAHEAD_CHUNKS)

    private suspend fun drainPcm(nextState: VoicePlayback = VoicePlayback.Idle) {
        val audio = pcmTrack ?: return
        audio.drain()
        // Release only the PCM resources: the streaming session may still continue (format change / fallback to the whole clip), so the generation must not be invalidated
        pcmTrack = null
        runCatching { audio.close() }
        focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focus = null
        _state.value = nextState
    }

    private suspend fun playPcmChunk(ticket: Long, chunk: TtsAudioPart.Pcm, onStarted: () -> Unit) {
        val first = pcmTrack == null
        val audio = pcmTrack ?: PcmAudioTrack(attributes, chunk.sampleRate, chunk.channels).also { track ->
            pcmTrack = track
            check(requestFocus { change ->
                // Once the generation is invalidated or a new track is used, audio focus callbacks on the old track are ignored
                if (pcmTrack === track && tickets.owns(ticket)) {
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> stop()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> track.pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> track.setVolume(0.2f)
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            track.setVolume(1f)
                            track.resume()
                        }
                    }
                }
            }) { "audio focus is not available" }
            track.resume()
        }
        check(audio.sampleRate == chunk.sampleRate && audio.channels == chunk.channels) { "PCM format changed during playback" }
        audio.write(chunk.bytes)
        if (first) {
            _state.value = VoicePlayback.Playing
            onStarted()
        }
    }

    private suspend fun prepareFile(
        ticket: Long,
        bytes: ByteArray,
        mime: String,
        onStarted: () -> Unit = {},
        completion: CompletableDeferred<VoicePlayback>? = null,
    ) {
        fun finishSegment(result: VoicePlayback) {
            release(if (completion != null && result == VoicePlayback.Idle) VoicePlayback.Loading else result)
            completion?.complete(result)
        }

        val dir = File(context.cacheDir, "voice")
        val f = File(dir, "tts-${System.nanoTime()}.${extensionOf(mime)}")
        try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                f.writeBytes(bytes)
            }
            // A new request arrived while writing the file: this generation is invalidated, no player is created and the state is untouched (the file is cleaned up by finally)
            if (!tickets.owns(ticket)) return
            file = f
            val mp = MediaPlayer()
            // When setting the data source throws, mp has not been handed to the player yet; without releasing it here the native player leaks
            try {
                mp.apply {
                    setAudioAttributes(attributes)
                    setDataSource(f.absolutePath)
                    setOnPreparedListener { prepared ->
                        if (!tickets.owns(ticket) || player !== prepared) return@setOnPreparedListener
                        if (requestFocus(ticket, prepared)) {
                            _state.value = VoicePlayback.Playing
                            onStarted()
                            prepared.start()
                        } else {
                            finishSegment(VoicePlayback.Error("audio focus is not available"))
                        }
                    }
                    setOnCompletionListener { if (tickets.owns(ticket) && player === it) finishSegment(VoicePlayback.Idle) }
                    setOnErrorListener { failed, what, extra ->
                        if (tickets.owns(ticket) && player === failed) finishSegment(VoicePlayback.Error("player error $what/$extra"))
                        true
                    }
                }
            } catch (e: Throwable) {
                mp.release()
                throw e
            }
            player = mp
            mp.prepareAsync()
        } catch (e: CancellationException) {
            if (tickets.owns(ticket)) release(VoicePlayback.Idle)
            throw e
        } catch (e: Exception) {
            // When an old request whose generation was invalidated fails, finish silently without overwriting the new request's state
            if (tickets.owns(ticket)) finishSegment(VoicePlayback.Error(e.message ?: "cannot play audio"))
        } finally {
            // Temporary files that never landed (superseded by a new request, cancelled midway) are not left on disk
            if (file !== f) f.delete()
        }
    }

    /** Stop and release. */
    fun stop() = finish(VoicePlayback.Idle)

    fun fail(message: String) = finish(VoicePlayback.Error(message))

    private fun requestFocus(ticket: Long, mp: MediaPlayer): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                // When the focus callback belongs to an old player, neither generation nor instance matches, so it is ignored
                if (!tickets.owns(ticket) || player !== mp) return@setOnAudioFocusChangeListener
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (mp.isPlaying) mp.pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mp.setVolume(0.2f, 0.2f)
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        mp.setVolume(1f, 1f)
                        if (!mp.isPlaying) runCatching { mp.start() }
                    }
                }
            }
            .build()
        focus = req
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun requestFocus(onFocusChange: (Int) -> Unit): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { onFocusChange(it) }
            .build()
        focus = req
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun finish(state: VoicePlayback) {
        val job = pendingPlayback
        pendingPlayback = null
        job?.cancel()
        release(state)
    }

    private fun release(state: VoicePlayback) {
        // Invalidate the current generation: callbacks still in flight from prepare and callbacks on the old player are dropped from now on
        tickets.invalidate()
        val oldPlayer = player
        player = null
        runCatching { oldPlayer?.release() }
        runCatching { pcmTrack?.close() }
        pcmTrack = null
        focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focus = null
        file?.delete()
        file = null
        _state.value = state
    }

    private fun extensionOf(mime: String): String = when (mime.substringAfter('/', "").lowercase()) {
        "mpeg", "mp3" -> "mp3"
        "ogg", "opus" -> "ogg"
        "aac" -> "aac"
        "flac" -> "flac"
        "wav", "x-wav" -> "wav"
        else -> "wav"
    }

    private companion object {
        // At most 8 KiB per chunk, roughly 2.2 seconds of lookahead at 44.1 kHz mono.
        const val PCM_LOOKAHEAD_CHUNKS = 24
        const val SEGMENT_PAUSE_MS = 90
    }
}
