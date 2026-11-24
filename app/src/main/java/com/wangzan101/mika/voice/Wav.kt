package com.wangzan101.mika.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wraps 16-bit mono PCM into a standard WAV (44-byte header + little-endian data) for uploading to provider transcription APIs. */
object Wav {
    const val RATE = 16_000
    private const val HEADER = 44

    fun encode(pcm16: ShortArray, rate: Int = RATE): ByteArray {
        val dataSize = pcm16.size * 2
        val buf = ByteBuffer.allocate(HEADER + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)              // fmt chunk size
        buf.putShort(1)             // PCM
        buf.putShort(1)             // mono
        buf.putInt(rate)
        buf.putInt(rate * 2)        // bytes per second = sample rate × channels × 2 bytes
        buf.putShort(2)             // block alignment
        buf.putShort(16)            // bit depth
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in pcm16) buf.putShort(s)
        return buf.array()
    }

    /** A block of all-zero silence as WAV, used to probe whether a model can transcribe at all (no real recording needed). */
    fun silence(ms: Int, rate: Int = RATE): ByteArray = encode(ShortArray(rate * ms / 1000), rate)
}
