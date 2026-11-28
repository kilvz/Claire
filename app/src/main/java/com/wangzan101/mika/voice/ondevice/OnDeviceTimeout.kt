package com.wangzan101.mika.voice.ondevice

import com.wangzan101.mika.voice.Wav

/** Time budget for on-device recognition: [FACTOR] times the audio duration, at least [MIN_MS]; loading a model is budgeted separately by [LOAD_TIMEOUT_MS]. */
object OnDeviceTimeout {
    const val MIN_MS = 30_000L
    const val FACTOR = 3
    const val LOAD_TIMEOUT_MS = 60_000L

    fun budgetMs(samples: Int, rate: Int = Wav.RATE): Long =
        maxOf(MIN_MS, samples.toLong() * 1000L * FACTOR / rate)
}
