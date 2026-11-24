package com.wangzan101.mika.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

/**
 * A one-shot facade over the system [TextToSpeech]. Every synthesis creates a new engine and [shutdown]s it when done,
 * so the engine is modeled as an injectable interface here: JVM tests drive the synthesis state machine with a fake engine, and only real devices touch [TextToSpeech].
 * The interface uses only JVM types (Locale/File/functions), callbacks are plain lambdas, and Android listeners never appear in the interface.
 */
interface SystemTtsEngine {
    /** Initialize asynchronously; [onInit] is called back once when done (TextToSpeech.SUCCESS or TextToSpeech.ERROR). */
    fun init(onInit: (status: Int) -> Unit)

    /** Returns TextToSpeech.LANG_AVAILABLE / LANG_MISSING_DATA / LANG_NOT_SUPPORTED. */
    fun setLanguage(locale: Locale): Int

    fun setSpeechRate(rate: Float): Int

    /** Register synthesis progress callbacks; [onError] carries only the utteranceId, and the real engine normalizes error codes itself. */
    fun setOnUtteranceProgressListener(onDone: (utteranceId: String) -> Unit, onError: (utteranceId: String) -> Unit)

    /** Synthesize into [file]; returns TextToSpeech.SUCCESS or TextToSpeech.ERROR. */
    fun synthesizeToFile(text: String, file: File, utteranceId: String): Int

    fun stop()

    fun shutdown()
}

/** Real implementation: each instance owns its own [TextToSpeech], and the caller guarantees it is shut down after one synthesis. */
class AndroidSystemTtsEngine(context: Context) : SystemTtsEngine {
    private val context = context.applicationContext
    private var tts: TextToSpeech? = null

    override fun init(onInit: (status: Int) -> Unit) {
        tts = TextToSpeech(context) { status -> onInit(status) }
    }

    override fun setLanguage(locale: Locale): Int = tts?.setLanguage(locale) ?: TextToSpeech.ERROR

    override fun setSpeechRate(rate: Float): Int = tts?.setSpeechRate(rate) ?: TextToSpeech.ERROR

    override fun setOnUtteranceProgressListener(onDone: (String) -> Unit, onError: (String) -> Unit) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) = onDone(utteranceId)
            override fun onError(utteranceId: String) = onError(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = onError(utteranceId)
        })
    }

    override fun synthesizeToFile(text: String, file: File, utteranceId: String): Int =
        tts?.synthesizeToFile(text, null, file, utteranceId) ?: TextToSpeech.ERROR

    override fun stop() {
        runCatching { tts?.stop() }
    }

    override fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
    }
}
