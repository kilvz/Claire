package com.wangzan101.mika.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Records 16 kHz mono 16-bit PCM. After start it updates [level] about every [chunkMs] milliseconds (0..1, normalized per chunk by RMS),
 * reads [android.media.AudioRecordingConfiguration.isClientSilenced] once per chunk to update [silenced] (true when the system mutes the microphone, e.g. while another component is using it);
 * stop returns all PCM captured so far; recording stops automatically once [maxMs] is reached, and a later stop still returns the captured content.
 * The caller checks the recording permission first; when the microphone cannot be obtained here it returns false. stop blocks briefly (waiting for the read loop to exit), so do not call it on the main thread.
 */
class PcmRecorder(
    private val maxMs: Int = 60_000,
    private val streaming: Boolean = false,
    /** When streaming, keeps no full recording and records for any duration (no [maxMs] buffer is reserved and it does not auto-stop at the limit); [stop] returns empty. Used for sessions without a cap. */
    private val unbounded: Boolean = false,
    internal val inputSource: Int = MediaRecorder.AudioSource.MIC,
    chunkMs: Int = 50,
    /** How many chunks can queue while a streaming session is still opening (a recognizer load, a connection); 100 chunks is five seconds. */
    bufferChunks: Int = 100,
) : StreamingRecorder {
    internal val chunkSamples: Int = Wav.RATE * chunkMs / 1000
    // At most [bufferChunks] chunks while a connection is opening. Never silently drop a sample.
    private val audio = Channel<ShortArray>(bufferChunks)
    override val chunks: ReceiveChannel<ShortArray> = audio
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level
    private val _silenced = MutableStateFlow(false)
    val silenced: StateFlow<Boolean> = _silenced

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var record: AudioRecord? = null
    private var job: Job? = null
    private var pcm = ShortArray(0)
    private var size = 0

    @SuppressLint("MissingPermission") // The caller checks the permission before start
    override fun start(): Boolean {
        synchronized(lock) {
            if (record != null) return false
            val minBuf = AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return false
            val chunk = chunkSamples
            val rec = try {
                AudioRecord(
                    inputSource, Wav.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, chunk * 2 * 4),
                )
            } catch (_: SecurityException) {
                return false
            } catch (_: IllegalArgumentException) {
                return false
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            try {
                rec.startRecording()
            } catch (_: IllegalStateException) {
                rec.release()
                return false
            }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                return false
            }
            pcm = ShortArray(if (unbounded && streaming) 0 else Wav.RATE * maxMs / 1000)
            size = 0
            _level.value = 0f
            record = rec
            job = scope.launch { readLoop(rec, chunk) }
            return true
        }
    }

    private fun readLoop(rec: AudioRecord, chunk: Int) {
        val buf = ShortArray(chunk)
        while (scope.isActive) {
            val n = rec.read(buf, 0, chunk)
            if (n <= 0) {
                synchronized(lock) {
                    if (streaming && record === rec) audio.close(IOException("Audio capture failed"))
                }
                break // Read failed or recording was already stopped
            }
            var sum = 0.0
            for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
            val lvl = (sqrt(sum / n) / 8000.0).coerceIn(0.0, 1.0).toFloat()
            val full = synchronized(lock) {
                if (record !== rec) return // Already stopped/cancelled
                // Write only inside the lock and after confirming it has not been stopped, so a cancel that just reset the level is not overwritten by this chunk's volume
                _level.value = lvl
                // Since API 29; keep the previous value when the configuration is occasionally unavailable
                rec.activeRecordingConfiguration?.let { _silenced.value = it.isClientSilenced }
                val take = if (unbounded && streaming) n else minOf(n, pcm.size - size)
                if (!(unbounded && streaming)) {
                    System.arraycopy(buf, 0, pcm, size, take)
                    size += take
                }
                if (streaming && audio.trySend(buf.copyOf(take)).isFailure) {
                    audio.close(IOException("Audio stream buffer exceeded"))
                    return@synchronized true
                }
                !(unbounded && streaming) && size >= pcm.size
            }
            if (full) break
        }
        // Auto-stop when full: release the microphone and leave the captured content for a later stop to take
        synchronized(lock) {
            if (record === rec) {
                release(rec)
                record = null
                _level.value = 0f // No longer recording, so the waveform must not stay at the last chunk's level
            }
        }
        if (streaming) audio.close()
    }

    /** Stops and returns all PCM recorded so far; safe when not recording (including after it already auto-stopped). */
    override fun stop(): ShortArray {
        val (rec, j) = synchronized(lock) {
            val r = record
            record = null
            if (r != null) release(r)
            r to job.also { job = null }
        }
        // Wait for the read loop to exit so the PCM taken afterwards no longer changes
        if (rec != null) runBlocking { j?.join() }
        if (streaming) audio.close()
        return synchronized(lock) {
            val out = pcm.copyOf(size)
            pcm = ShortArray(0)
            size = 0
            _level.value = 0f
            out
        }
    }

    /** Abandons this recording and drops the captured content; safe to call repeatedly. */
    override fun cancel() {
        synchronized(lock) {
            record?.let { release(it) }
            record = null
            job?.cancel()
            job = null
            pcm = ShortArray(0)
            size = 0
            _level.value = 0f
            audio.cancel()
        }
    }

    private fun release(rec: AudioRecord) {
        try { rec.stop() } catch (_: IllegalStateException) {}
        rec.release()
    }
}
