package com.wangzan101.mika.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Converts between 16-bit mono PCM and standard WAV (44-byte header plus little-endian data) for provider transcription APIs or reading back our own recordings. */
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

    /**
     * Extracts PCM samples (little-endian) from a 16-bit mono WAV. It finds `data` by chunk, so other chunks before it (LIST and so on) are fine;
     * without a `data` chunk it treats everything after the standard 44-byte header as data. It neither resamples nor mixes, and the format must match [encode].
     */
    fun decode(wav: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        val end = wav.size.toLong()
        var start = HEADER.toLong()
        var stop = end
        // Offsets are computed as Long: chunk lengths are unsigned 32-bit, so a huge length in a bad file cannot overflow negative or run out of bounds
        var off = 12L
        while (off + 8 <= end) {
            val id = String(wav, off.toInt(), 4, Charsets.US_ASCII)
            val len = buf.getInt(off.toInt() + 4).toLong() and 0xFFFF_FFFFL
            if (id == "data") {
                start = off + 8
                // A length beyond the file (the length was never written back, or the file was truncated) is treated as reading to the end
                stop = if (start + len > end) end else start + len
                break
            }
            off += 8 + len + (len and 1)
        }
        if (start >= stop) return ShortArray(0)
        val s = start.toInt()
        return ShortArray(((stop - start) / 2).toInt()) { i -> buf.getShort(s + 2 * i) }
    }

    /** A silent all-zero WAV used to probe whether a model can transcribe (no real recording needed). */
    fun silence(ms: Int, rate: Int = RATE): ByteArray = encode(ShortArray(rate * ms / 1000), rate)
}
