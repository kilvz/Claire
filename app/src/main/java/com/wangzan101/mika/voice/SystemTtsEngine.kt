package com.wangzan101.mika.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

/** Result of setting a voice by name: applied, this engine does not have this voice (or the voice data is not installed), engine error. */
enum class SystemVoiceResult { Applied, NotOffered, Failed }

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

    /** Sets the voice by [android.speech.tts.Voice.getName]. */
    fun setVoice(voiceName: String): SystemVoiceResult

    /** Registers the synthesis progress callback; [onError] only carries the utteranceId, and the real engine normalises error codes itself. */
    fun setOnUtteranceProgressListener(onDone: (utteranceId: String) -> Unit, onError: (utteranceId: String) -> Unit)

    /** Synthesize into [file]; returns TextToSpeech.SUCCESS or TextToSpeech.ERROR. */
    fun synthesizeToFile(text: String, file: File, utteranceId: String): Int

    fun stop()

    fun shutdown()
}

/**
 * Real implementation: each instance holds its own [TextToSpeech], and the caller guarantees it is shut down right after one synthesis.
 * An empty [enginePackage] uses the phone's default engine; when the specified engine is not installed, [TextToSpeech] itself connects to the default engine,
 * and when it connects but initialisation fails, this falls back to the default engine and tries once more.
 */
class AndroidSystemTtsEngine(
    context: Context,
    private val enginePackage: String? = null,
) : SystemTtsEngine {
    private val context = context.applicationContext
    private var tts: TextToSpeech? = null

    override fun init(onInit: (status: Int) -> Unit) {
        val engine = enginePackage?.takeIf { it.isNotBlank() }
        if (engine == null) {
            tts = TextToSpeech(context) { status -> onInit(status) }
            return
        }
        tts = TextToSpeech(context, { status ->
            if (status == TextToSpeech.SUCCESS) {
                onInit(status)
            } else {
                runCatching { tts?.shutdown() }
                tts = TextToSpeech(context) { fallback -> onInit(fallback) }
            }
        }, engine)
    }

    override fun setLanguage(locale: Locale): Int = tts?.setLanguage(locale) ?: TextToSpeech.ERROR

    override fun setVoice(voiceName: String): SystemVoiceResult {
        val t = tts ?: return SystemVoiceResult.Failed
        return try {
            val voice = t.voices?.firstOrNull { it.name == voiceName && !it.notInstalled() }
                ?: return SystemVoiceResult.NotOffered
            if (t.setVoice(voice) == TextToSpeech.SUCCESS) SystemVoiceResult.Applied else SystemVoiceResult.Failed
        } catch (e: RuntimeException) {
            // When the engine process dies, getVoices/setVoice may throw directly
            SystemVoiceResult.Failed
        }
    }

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

/** The engine says this voice's data has not been downloaded to the phone yet. */
internal fun android.speech.tts.Voice.notInstalled(): Boolean =
    features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true
