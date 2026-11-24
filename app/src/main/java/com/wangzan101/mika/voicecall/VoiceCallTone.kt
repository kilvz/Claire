package com.wangzan101.mika.voicecall

import com.wangzan101.mika.calls.VolcRealtimeClient
import kotlin.math.sin

/**
 * Connect tone: locally synthesized [FIRST_TONE_HZ]Hz and [SECOND_TONE_HZ]Hz sine waves, [TONE_MS] milliseconds each,
 * with linear fade-in/fade-out to avoid pops. Written directly to the playback track, bypassing the playback queue and not counted in the level.
 */
object VoiceCallTone {
    const val TONE_MS = 65
    const val FIRST_TONE_HZ = 720
    const val SECOND_TONE_HZ = 960
    private const val FADE_MS = 5
    private const val AMPLITUDE = 0.35 * Short.MAX_VALUE

    /** Returns 16-bit mono little-endian PCM: two segments of [TONE_MS] milliseconds each. */
    fun connectTonePcm(sampleRate: Int = VolcRealtimeClient.OUTPUT_SAMPLE_RATE): ByteArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val segmentSamples = sampleRate * TONE_MS / 1000
        val fadeSamples = minOf(sampleRate * FADE_MS / 1000, segmentSamples / 2)
        val pcm = ByteArray(segmentSamples * 2 * 2)
        synth(pcm, 0, segmentSamples, FIRST_TONE_HZ, sampleRate, fadeSamples)
        synth(pcm, segmentSamples, segmentSamples, SECOND_TONE_HZ, sampleRate, fadeSamples)
        return pcm
    }

    private fun synth(pcm: ByteArray, offsetSamples: Int, count: Int, hz: Int, sampleRate: Int, fadeSamples: Int) {
        for (i in 0 until count) {
            val envelope = when {
                fadeSamples <= 0 -> 1.0
                i < fadeSamples -> i.toDouble() / fadeSamples
                i >= count - fadeSamples -> (count - 1 - i).toDouble() / fadeSamples
                else -> 1.0
            }
            val sample = (AMPLITUDE * envelope * sin(2.0 * Math.PI * hz * i / sampleRate)).toInt()
            val at = (offsetSamples + i) * 2
            pcm[at] = (sample and 0xFF).toByte()
            pcm[at + 1] = (sample shr 8 and 0xFF).toByte()
        }
    }
}
