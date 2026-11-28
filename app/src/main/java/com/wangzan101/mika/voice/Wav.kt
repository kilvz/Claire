package com.wangzan101.mika.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Converts between 16-bit mono PCM and standard WAV (44-byte header + little-endian data): for uploading to a vendor transcription API, or reading back a local recording. */
object Wav {
    const val RATE = 16_000
    private const val HEADER = 44 // standard header length, see [Pcm16.wav]

    fun encode(pcm16: ShortArray, rate: Int = RATE): ByteArray = Pcm16.wav(Pcm16.toBytes(pcm16), rate)

    /**
     * Extracts the PCM samples from a 16-bit mono WAV (little-endian). It scans chunks for `data`, so other chunks before it (LIST and the like) still read;
     * when there is no `data` chunk it treats the bytes after the standard 44-byte header as the data. No resampling or mixing; the format must match [encode].
     */
    fun decode(wav: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        val end = wav.size.toLong()
        var start = HEADER.toLong()
        var stop = end
        // Offsets are computed as Long: chunk lengths are unsigned 32-bit, so a huge length in a corrupt file must not overflow negative or go out of bounds
        var off = 12L
        while (off + 8 <= end) {
            val id = String(wav, off.toInt(), 4, Charsets.US_ASCII)
            val len = buf.getInt(off.toInt() + 4).toLong() and 0xFFFF_FFFFL
            if (id == "data") {
                start = off + 8
                // A length past the end of the file (length never written back while recording, or a truncated file) is read up to the end of the file
                stop = if (start + len > end) end else start + len
                break
            }
            off += 8 + len + (len and 1)
        }
        if (start >= stop) return ShortArray(0)
        return Pcm16.toSamples(wav.copyOfRange(start.toInt(), stop.toInt()))
    }

    /** An all-zero silent WAV, used to probe whether a model can transcribe (no real recording needed). */
    fun silence(ms: Int, rate: Int = RATE): ByteArray = encode(ShortArray(rate * ms / 1000), rate)
}
