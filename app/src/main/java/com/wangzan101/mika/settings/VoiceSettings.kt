package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.core.model.TtsCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Interface shape of a speech service. Stored by name: entries may only be appended, never renamed or removed. */
enum class TtsProtocol(val wire: String) {
    /** OpenAI Speech:POST /audio/speech。 */
    OpenAiSpeech("openai-speech"),

    /** Gemini TTS:generateContent + responseModalities AUDIO。 */
    GeminiTts("gemini-tts"),

    /** Built-in Android system speech synthesis; used only for catalog capability hints, and the legacy choice never settles on it. */
    AndroidSystem("android-system");

    companion object {
        fun of(wire: String?): TtsProtocol? = entries.firstOrNull { it.wire == wire }
    }
}

internal fun normalizeVoiceSpeed(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0.25f, 4f) else 1f

/** The voice, model and protocol the user chose; the key is still stored per provider by [ProviderStore]. */
data class VoiceChoice(
    val providerId: String,
    val model: String,
    val protocol: TtsProtocol,
    val voice: String = "",
    val speed: Float = 1.0f,
    val streaming: Boolean = false,
)

/**
 * Voice settings. Reading aloud is allowed by default (synthesis only happens when the user taps a button), but there is no automatic reading and no speak tool:
 * [enabled] is the master switch; with [autoRead] on, finished answers are read aloud automatically; with [speakTool] on, the model may speak a sentence on its own;
 * [maxCharsPerSpeak] is the character limit for a single reading, preventing a whole long text from being read out by mistake and burning money.
 */
data class VoiceSettingsState(
    val enabled: Boolean = true,
    val choice: VoiceChoice? = null,
    /** Voice/speed/streaming usage preferences after migration; the model choice lives in the catalog, so only usage preferences remain here. */
    val voice: String = "",
    val speed: Float = 1.0f,
    val streaming: Boolean = false,
    val autoRead: Boolean = false,
    val speakTool: Boolean = false,
    /** Allow saving generated audio into the phone's music library; off by default. */
    val saveEnabled: Boolean = false,
    /** Whether to ask the user before every save; asks by default. */
    val askBeforeSave: Boolean = true,
    val maxCharsPerSpeak: Int = DEFAULT_MAX_CHARS,
) {
    companion object {
        const val DEFAULT_MAX_CHARS = 2000
        const val MAX_CHARS_LIMIT = 20_000
    }
}

class VoiceSettings(private val prefs: SharedPreferences) {
    interface Owner {
        val voiceSettings: VoiceSettings
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<VoiceSettingsState> = _state.asStateFlow()

    /** Usage preferences remain in this store; migration reads but never rewrites them. */
    internal fun legacySnapshot(): VoiceSettingsState = state.value

    /** The legacy version filled in the model directly on the voice page. On first upgrade this choice is kept along with its voice and protocol; the choice is migrated through the unified catalog, and it is no longer filtered by tag here. */
    internal fun reconcileTtsChoice(store: ProviderStore) {
        val choice = state.value.choice
        if (!prefs.getBoolean(KEY_TTS_ROLE_MIGRATED, false)) {
            if (choice != null && store.provider(choice.providerId) != null) {
                val model = choice.model.trim()
                if (model != choice.model) setChoice(choice.copy(model = model))
            }
            prefs.edit().putBoolean(KEY_TTS_ROLE_MIGRATED, true).apply()
        }
    }

    fun setEnabled(on: Boolean) = update { copy(enabled = on) }

    fun setChoice(choice: VoiceChoice?) = update {
        val normalized = choice?.copy(
            speed = normalizeVoiceSpeed(choice.speed),
            streaming = choice.streaming && choice.protocol == TtsProtocol.OpenAiSpeech,
        )
        copy(
            choice = normalized,
            voice = normalized?.voice.orEmpty(),
            speed = normalized?.speed ?: 1f,
            streaming = normalized?.streaming ?: false,
        )
    }

    fun setVoice(voice: String) = update { copy(voice = voice, choice = choice?.copy(voice = voice)) }

    fun setSpeed(speed: Float) = update {
        val normalized = normalizeVoiceSpeed(speed)
        copy(speed = normalized, choice = choice?.copy(speed = normalized))
    }

    fun setStreaming(on: Boolean) = update {
        copy(streaming = on, choice = choice?.copy(streaming = on && choice.protocol == TtsProtocol.OpenAiSpeech))
    }

    fun setAutoRead(on: Boolean) = update { copy(autoRead = on) }

    fun setSpeakTool(on: Boolean) = update { copy(speakTool = on) }

    fun setSaveEnabled(on: Boolean) = update { copy(saveEnabled = on) }

    fun setAskBeforeSave(on: Boolean) = update { copy(askBeforeSave = on) }

    fun setMaxChars(n: Int) = update { copy(maxCharsPerSpeak = n.coerceIn(1, VoiceSettingsState.MAX_CHARS_LIMIT)) }

    /** Capabilities for the current protocol. Voices are unrestricted (validated by the provider); OpenAI supports several containers, while Gemini is normalized back to WAV here. */
    fun capabilities(): TtsCapabilities? {
        val choice = _state.value.choice ?: return null
        return capabilities(choice.protocol)
    }

    /** After migration, capabilities follow the catalog model's protocol; the character limit still comes from the usage preference here. */
    fun capabilities(protocol: TtsProtocol): TtsCapabilities = when (protocol) {
        TtsProtocol.OpenAiSpeech -> TtsCapabilities(
            voices = null,
            maxChars = _state.value.maxCharsPerSpeak,
            formats = setOf("wav", "mp3", "opus", "aac", "flac", "pcm"),
        )
        TtsProtocol.GeminiTts -> TtsCapabilities(
            voices = null,
            maxChars = _state.value.maxCharsPerSpeak,
            formats = setOf("wav"),
        )
        TtsProtocol.AndroidSystem -> TtsCapabilities(
            voices = null,
            maxChars = _state.value.maxCharsPerSpeak,
            formats = setOf("wav"),
        )
    }

    private fun update(block: VoiceSettingsState.() -> VoiceSettingsState) {
        val next = _state.value.block()
        prefs.edit().putString(KEY, encode(next).toString()).apply()
        _state.value = next
    }

    private fun read(): VoiceSettingsState {
        val raw = prefs.getString(KEY, null) ?: return VoiceSettingsState()
        return runCatching { decode(Json.parseToJsonElement(raw).jsonObject) }.getOrNull() ?: VoiceSettingsState()
    }

    private fun encode(state: VoiceSettingsState): JsonObject = buildJsonObject {
        put("enabled", state.enabled)
        put("autoRead", state.autoRead)
        put("speakTool", state.speakTool)
        put("saveEnabled", state.saveEnabled)
        put("askBeforeSave", state.askBeforeSave)
        put("maxCharsPerSpeak", state.maxCharsPerSpeak)
        put("voice", state.voice)
        put("speed", state.speed)
        put("streaming", state.streaming)
        state.choice?.let { c ->
            put("providerId", c.providerId)
            put("model", c.model)
            put("protocol", c.protocol.wire)
        }
    }

    private fun decode(obj: JsonObject): VoiceSettingsState {
        fun bool(key: String, fallback: Boolean) = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: fallback
        val providerId = (obj["providerId"] as? JsonPrimitive)?.contentOrNull
        val model = (obj["model"] as? JsonPrimitive)?.contentOrNull
        val protocol = TtsProtocol.of((obj["protocol"] as? JsonPrimitive)?.contentOrNull)
        val voice = (obj["voice"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val speed = normalizeVoiceSpeed((obj["speed"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: 1f)
        val streaming = bool("streaming", false)
        val choice = if (providerId.isNullOrBlank() || model.isNullOrBlank() || protocol == null) null
        else VoiceChoice(
            providerId = providerId,
            model = model,
            protocol = protocol,
            voice = voice,
            speed = speed,
            streaming = protocol == TtsProtocol.OpenAiSpeech && streaming,
        )
        return VoiceSettingsState(
            enabled = bool("enabled", true),
            choice = choice,
            voice = voice,
            speed = speed,
            streaming = streaming,
            autoRead = bool("autoRead", false),
            speakTool = bool("speakTool", false),
            saveEnabled = bool("saveEnabled", false),
            askBeforeSave = bool("askBeforeSave", true),
            maxCharsPerSpeak = ((obj["maxCharsPerSpeak"] as? JsonPrimitive)?.intOrNull ?: VoiceSettingsState.DEFAULT_MAX_CHARS)
                .coerceIn(1, VoiceSettingsState.MAX_CHARS_LIMIT),
        )
    }

    companion object {
        private const val KEY = "voice_settings"
        private const val KEY_TTS_ROLE_MIGRATED = "voice_tts_role_migrated"

        fun get(context: Context): VoiceSettings =
            (context.applicationContext as? Owner)?.voiceSettings
                ?: error("the Application must implement VoiceSettings.Owner")
    }
}
