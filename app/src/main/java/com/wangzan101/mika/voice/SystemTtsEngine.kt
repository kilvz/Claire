package com.wangzan101.mika.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

/** 按名字设音色的结果:用上了、这个引擎没有这个音色(或音色数据没装)、引擎出错。 */
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

    /** 按 [android.speech.tts.Voice.getName] 设音色。 */
    fun setVoice(voiceName: String): SystemVoiceResult

    /** 注册合成进度回调;[onError] 只带 utteranceId,错误码由真实引擎自行归一。 */
    fun setOnUtteranceProgressListener(onDone: (utteranceId: String) -> Unit, onError: (utteranceId: String) -> Unit)

    /** Synthesize into [file]; returns TextToSpeech.SUCCESS or TextToSpeech.ERROR. */
    fun synthesizeToFile(text: String, file: File, utteranceId: String): Int

    fun stop()

    fun shutdown()
}

/**
 * 真实实现:每个实例持有自己的 [TextToSpeech],调用方保证一次合成用完即关。
 * [enginePackage] 为空用手机的默认引擎;指定的引擎没装时 [TextToSpeech] 自己会连默认引擎,
 * 连上了却初始化失败时这里再退回默认引擎试一次。
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
            // 引擎进程死掉时 getVoices/setVoice 可能直接抛
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

/** 引擎说这个音色的数据还没下载到手机上。 */
internal fun android.speech.tts.Voice.notInstalled(): Boolean =
    features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true
