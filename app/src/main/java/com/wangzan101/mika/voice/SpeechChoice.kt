package com.wangzan101.mika.voice

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.voice.model.LegacySpeechSnapshot
import com.wangzan101.mika.voice.model.VoiceCredentials
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The user's choice for "speech to text" and how it is stored in settings. Which engine is actually used is decided by [SpeechResolver] together with the device's capabilities. */
sealed interface SpeechChoice {
    /** Never chosen: use the device's built-in recognition when available. */
    data object Auto : SpeechChoice

    /** The device's built-in recognition was chosen explicitly. */
    data object System : SpeechChoice

    /** Voice input is off. */
    data object Off : SpeechChoice

    /** Transcribe with a given provider's given model; [method] decides how the request is made. */
    data class Provider(val providerId: String, val model: String, val method: TranscribeMethod) : SpeechChoice

    /** Native streaming ASR, with credentials stored separately from this non-secret selection. */
    data class Doubao(val resourceId: String = "volc.seedasr.sauc.duration") : SpeechChoice

    /** Self-hosted Fun-ASR realtime service; [url] is the normalized ws(s) address (see [FunAsrProtocol.normalize]) and holds no secret. */
    data class FunAsr(val url: String, val language: AsrLanguage = AsrLanguage.Auto) : SpeechChoice

    /**
     * Alibaba Cloud Bailian realtime speech recognition; the API key is stored encrypted separately per region, so this holds only the model, the region and the business space ID ([workspace],
     * normalized, see [BailianProtocol.workspace]; an empty string means the general endpoint).
     */
    data class Bailian(
        val model: String = BailianProtocol.DEFAULT_MODEL,
        val region: BailianRegion = BailianRegion.Beijing,
        val workspace: String = "",
        val language: AsrLanguage = AsrLanguage.Auto,
    ) : SpeechChoice
}

/**
 * The request shape a provider uses to transcribe audio. The names are persisted, so entries may only be appended, never renamed or removed, otherwise old settings could not be read back.
 * The three with the ChatInputAudio prefix are all the chat API's input_audio, just written differently, and a trial transcription tries each in turn:
 * [ChatInputAudio] puts raw base64 in data with a transcription instruction attached; [ChatInputAudioUri] turns data into a
 * `data:audio/wav;base64,...` data URI (some services accept only that); [ChatInputAudioOnly] also uses a data URI
 * but sends audio only, without the instruction (transcription-only models do not accept text blocks).
 */
enum class TranscribeMethod { AudioTranscriptions, ChatInputAudio, GeminiInline, ChatInputAudioUri, ChatInputAudioOnly }

/**
 * The choice lives in the [FeatureFlags] settings under a single key as one JSON blob; anything unreadable falls back to [SpeechChoice.Auto].
 * It also records "device built-in recognition not responding" ([systemSilent]): when the service never answers during hold-to-talk, only [SILENT_STRIKES] such occurrences in a row are recorded
 * (one hang is usually just a slow cold start of the recognition service, and this path should not be turned off for good because of it); any answer in between resets the count.
 * When the user picks "Auto" or "device built-in" in settings, both the mark and the count are cleared, so phones whose recognition service changed or whose system was updated can try again.
 */
class SpeechSettings(private val prefs: SharedPreferences, private val cipher: KeyCipher = KeystoreKeyCipher("mika-speech-keys")) {
    private val _choice = MutableStateFlow(read())
    val choice: StateFlow<SpeechChoice> = _choice.asStateFlow()

    val hasDoubaoCredentials: Boolean get() = prefs.contains(DOUBAO_KEY)

    /** Save without making a billable network request. Both authentication generations are supported. */
    internal fun saveDoubao(credentials: DoubaoCredentials, resourceId: String) {
        require(credentials.valid && resourceId in DoubaoProtocol.resources)
        val json = buildJsonObject {
            put("apiKey", JsonPrimitive(credentials.apiKey))
            put("appId", JsonPrimitive(credentials.appId))
            put("accessKey", JsonPrimitive(credentials.accessKey))
        }.toString().toByteArray(Charsets.UTF_8)
        val encrypted = Base64.getEncoder().encodeToString(cipher.encrypt(json))
        val selected = SpeechChoice.Doubao(resourceId)
        prefs.edit().putString(DOUBAO_KEY, encrypted).putString(KEY, encode(selected).toString()).apply()
        _choice.value = selected
    }

    internal fun doubaoCredentials(): DoubaoCredentials? = runCatching {
        val encrypted = prefs.getString(DOUBAO_KEY, null) ?: return null
        val obj = Json.parseToJsonElement(String(cipher.decrypt(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)).jsonObject
        DoubaoCredentials(
            obj["apiKey"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            obj["appId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            obj["accessKey"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        ).takeIf { it.valid }
    }.getOrNull()

    fun clearDoubao() {
        prefs.edit().remove(DOUBAO_KEY).apply()
        if (_choice.value is SpeechChoice.Doubao) set(SpeechChoice.Off)
    }

    /** The self-hosted Fun-ASR service address used last time, so switching to another engine and back needs no retyping. */
    val funAsrUrl: String? get() = prefs.getString(FUNASR_URL, null)?.let(FunAsrProtocol::normalize)

    /** The recognition language chosen last time for the self-hosted service. */
    val funAsrLanguage: AsrLanguage get() = AsrLanguage.of(prefs.getString(FUNASR_LANGUAGE, null))

    /** Selects this self-hosted Fun-ASR service (the address goes through [FunAsrProtocol.normalize] first). */
    fun saveFunAsr(url: String, language: AsrLanguage = AsrLanguage.Auto) {
        val choice = SpeechChoice.FunAsr(requireNotNull(FunAsrProtocol.normalize(url)), language)
        prefs.edit().putString(FUNASR_URL, choice.url).putString(FUNASR_LANGUAGE, language.code).putString(KEY, encode(choice).toString())
            .putString(LIVE_LAST, encode(choice).toString()).apply()
        _choice.value = choice
    }

    /** Whether a Bailian API key has been stored for this region (Beijing and Singapore keys are not interchangeable, so each is stored separately). */
    fun hasBailianKey(region: BailianRegion): Boolean = prefs.contains(bailianKeyName(region))

    /** Stores this region's Bailian API key encrypted and selects it; when [apiKey] is empty, the one already stored for this region is kept (only the model changes). No network access. */
    internal fun saveBailian(apiKey: String, choice: SpeechChoice.Bailian) {
        require(BailianProtocol.validModel(choice.model) && BailianProtocol.workspace(choice.workspace) == choice.workspace)
        val edit = prefs.edit()
        if (apiKey.isNotBlank()) {
            val encrypted = Base64.getEncoder().encodeToString(cipher.encrypt(apiKey.trim().toByteArray(Charsets.UTF_8)))
            edit.putString(bailianKeyName(choice.region), encrypted)
        } else require(hasBailianKey(choice.region))
        val json = encode(choice).toString()
        edit.putString(KEY, json).putString(LIVE_LAST, json).putString(bailianModelName(choice.region), choice.model)
            .putString(bailianWorkspaceName(choice.region), choice.workspace)
            .putString(bailianLanguageName(choice.region), choice.language.code).apply()
        _choice.value = choice
    }

    /** The Bailian model used last time for this region; the default when there is none. */
    fun bailianModel(region: BailianRegion): String =
        prefs.getString(bailianModelName(region), null)?.takeIf(BailianProtocol::validModel) ?: BailianProtocol.DEFAULT_MODEL

    /** The recognition language chosen last time for this region. */
    fun bailianLanguage(region: BailianRegion): AsrLanguage = AsrLanguage.of(prefs.getString(bailianLanguageName(region), null))

    /** The business space ID entered last time for this region; an empty string when never entered. */
    fun bailianWorkspace(region: BailianRegion): String =
        prefs.getString(bailianWorkspaceName(region), null)?.let(BailianProtocol::workspace).orEmpty()

    internal fun bailianKey(region: BailianRegion): String? = runCatching {
        val encrypted = prefs.getString(bailianKeyName(region), null) ?: return null
        String(cipher.decrypt(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8).takeIf(String::isNotBlank)
    }.getOrNull()

    /** Deletes this region's Bailian key; if that region is in use, voice input is turned off. */
    fun clearBailian(region: BailianRegion) {
        prefs.edit().remove(bailianKeyName(region)).remove(bailianModelName(region)).remove(bailianWorkspaceName(region)).remove(bailianLanguageName(region)).apply()
        if ((_choice.value as? SpeechChoice.Bailian)?.region == region) set(SpeechChoice.Off)
    }

    /**
     * Which kind the "Qwen realtime speech recognition" row last selected (self-hosted, or Bailian in a given region); tapping the circle reuses it.
     * Null when that kind is no longer usable (the address cannot be read, the key was deleted) or was never chosen; the UI then sends the user to the settings page instead.
     */
    val lastLive: SpeechChoice? get() = runCatching {
        // When the last-used kind was never recorded, the one in use is it
        val stored = prefs.getString(LIVE_LAST, null)?.let { decode(Json.parseToJsonElement(it).jsonObject) } ?: _choice.value
        when (val c = stored) {
            is SpeechChoice.FunAsr -> c
            is SpeechChoice.Bailian -> c.takeIf { hasBailianKey(it.region) }
            else -> null
        }
    }.getOrNull()

    /** Whether this row counts as configured: there is a kind that can be reused. */
    val hasLive: Boolean get() = lastLive != null

    /** Reads all still-addressable legacy choices without changing the rollback store. */
    internal fun legacySnapshot(): LegacySpeechSnapshot {
        val selected = _choice.value
        val storedDoubao = doubaoCredentials()
        check(!hasDoubaoCredentials || storedDoubao != null) { "Legacy Doubao credentials are unreadable" }
        val doubao = storedDoubao?.let { VoiceCredentials(it.apiKey, it.appId, it.accessKey) }
        val bailianCredentials = mutableMapOf<BailianRegion, VoiceCredentials>()
        val bailianChoices = BailianRegion.entries.mapNotNull { region ->
            val active = (selected as? SpeechChoice.Bailian)?.takeIf { it.region == region }
            val hasKey = hasBailianKey(region)
            val key = if (hasKey) bailianKey(region) else null
            check(!hasKey || key != null) { "Legacy Bailian credentials are unreadable" }
            if (key != null) bailianCredentials[region] = VoiceCredentials(apiKey = key)
            if (active == null && !hasKey) return@mapNotNull null
            active ?: SpeechChoice.Bailian(
                model = bailianModel(region),
                region = region,
                workspace = bailianWorkspace(region),
                language = bailianLanguage(region),
            )
        }
        val savedFunAsr = funAsrUrl?.let { SpeechChoice.FunAsr(it, funAsrLanguage) }
        return LegacySpeechSnapshot(
            selection = selected,
            savedChoices = (listOfNotNull(savedFunAsr) + bailianChoices + listOfNotNull(lastLive)).distinct(),
            doubaoCredentials = doubao,
            bailianCredentials = bailianCredentials,
        )
    }

    private val _systemSilent = MutableStateFlow(prefs.getBoolean(SILENT, false))
    val systemSilent: StateFlow<Boolean> = _systemSilent.asStateFlow()

    fun set(choice: SpeechChoice) {
        // Both of these end up using the device's built-in recognition: the user chose it deliberately, so give it another chance
        val retry = choice == SpeechChoice.System || choice == SpeechChoice.Auto
        val edit = prefs.edit().putString(KEY, encode(choice).toString())
        if (retry) edit.remove(SILENT).remove(STRIKES)
        edit.apply()
        _choice.value = choice
        if (retry) _systemSilent.value = false
    }

    /** When retrying system recognition, only the health mark is cleared; the legacy choice stays read-only after migration. */
    fun retrySystem() {
        prefs.edit().remove(SILENT).remove(STRIKES).apply()
        _systemSilent.value = false
    }

    /** The device's built-in recognition gave no callback at all this time: it is recorded as not responding only at the [SILENT_STRIKES]-th time in a row. */
    fun noteSystemSilent() {
        val strikes = prefs.getInt(STRIKES, 0) + 1
        val edit = prefs.edit().putInt(STRIKES, strikes)
        if (strikes >= SILENT_STRIKES) edit.putBoolean(SILENT, true)
        edit.apply()
        if (strikes >= SILENT_STRIKES) _systemSilent.value = true
    }

    /**
     * A named recognition service that worked when tried (the system default did not answer but this one did); it is tried first next time. Null when there is none, or when the system default answers.
     * See [SystemSpeech.attempts].
     */
    val systemService: String? get() = prefs.getString(SERVICE, null)

    /** The device's built-in recognition answered this time ([service] is the one that answered, null means the system default): the earlier non-response count is void and the answering service is remembered. */
    fun noteSystemAnswered(service: String? = null) {
        val edit = prefs.edit()
        var changed = false
        if (prefs.getInt(STRIKES, 0) != 0) { edit.remove(STRIKES); changed = true }
        if (service != systemService) {
            if (service == null) edit.remove(SERVICE) else edit.putString(SERVICE, service)
            changed = true
        }
        if (changed) edit.apply()
    }

    private fun read(): SpeechChoice {
        val raw = prefs.getString(KEY, null) ?: return SpeechChoice.Auto
        return runCatching { decode(Json.parseToJsonElement(raw).jsonObject) }.getOrNull() ?: SpeechChoice.Auto
    }

    private fun encode(choice: SpeechChoice): JsonObject = buildJsonObject {
        when (choice) {
            SpeechChoice.Auto -> put(TYPE, JsonPrimitive("auto"))
            SpeechChoice.System -> put(TYPE, JsonPrimitive("system"))
            SpeechChoice.Off -> put(TYPE, JsonPrimitive("off"))
            is SpeechChoice.Doubao -> {
                put(TYPE, JsonPrimitive("doubao"))
                put("resourceId", JsonPrimitive(choice.resourceId))
            }
            is SpeechChoice.FunAsr -> {
                put(TYPE, JsonPrimitive("funasr"))
                put("url", JsonPrimitive(choice.url))
                if (choice.language != AsrLanguage.Auto) put("language", JsonPrimitive(choice.language.code))
            }
            is SpeechChoice.Bailian -> {
                put(TYPE, JsonPrimitive("bailian"))
                put("model", JsonPrimitive(choice.model))
                put("region", JsonPrimitive(choice.region.name))
                if (choice.workspace.isNotEmpty()) put("workspace", JsonPrimitive(choice.workspace))
                if (choice.language != AsrLanguage.Auto) put("language", JsonPrimitive(choice.language.code))
            }
            is SpeechChoice.Provider -> {
                put(TYPE, JsonPrimitive("provider"))
                put("providerId", JsonPrimitive(choice.providerId))
                put("model", JsonPrimitive(choice.model))
                put("method", JsonPrimitive(choice.method.name))
            }
        }
    }

    /** Missing fields, unknown types and nonexistent method names all return null, and the caller falls back to auto. */
    private fun decode(obj: JsonObject): SpeechChoice? = when (obj[TYPE]?.jsonPrimitive?.contentOrNull) {
        "auto" -> SpeechChoice.Auto
        "system" -> SpeechChoice.System
        "off" -> SpeechChoice.Off
        "doubao" -> obj["resourceId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it in DoubaoProtocol.resources }?.let { SpeechChoice.Doubao(it) }
        "funasr" -> obj["url"]?.jsonPrimitive?.contentOrNull?.let(FunAsrProtocol::normalize)
            ?.let { SpeechChoice.FunAsr(it, AsrLanguage.of(obj["language"]?.jsonPrimitive?.contentOrNull)) }
        "bailian" -> {
            val model = obj["model"]?.jsonPrimitive?.contentOrNull?.takeIf(BailianProtocol::validModel)
            val region = BailianRegion.entries.firstOrNull { it.name == obj["region"]?.jsonPrimitive?.contentOrNull }
            val workspace = BailianProtocol.workspace(obj["workspace"]?.jsonPrimitive?.contentOrNull.orEmpty())
            val language = AsrLanguage.of(obj["language"]?.jsonPrimitive?.contentOrNull)
            if (model == null || region == null || workspace == null) null else SpeechChoice.Bailian(model, region, workspace, language)
        }
        "provider" -> {
            val id = obj["providerId"]?.jsonPrimitive?.contentOrNull
            val model = obj["model"]?.jsonPrimitive?.contentOrNull
            val method = TranscribeMethod.entries.firstOrNull { it.name == obj["method"]?.jsonPrimitive?.contentOrNull }
            if (id == null || model == null || method == null) null else SpeechChoice.Provider(id, model, method)
        }
        else -> null
    }

    companion object {
        private const val KEY = "speech_choice"
        private const val DOUBAO_KEY = "speech_doubao_credentials_encrypted"
        private const val FUNASR_URL = "speech_funasr_url"
        private const val LIVE_LAST = "speech_live_last"

        private fun bailianKeyName(region: BailianRegion) = "speech_bailian_key_encrypted_${region.name.lowercase()}"
        private fun bailianModelName(region: BailianRegion) = "speech_bailian_model_${region.name.lowercase()}"
        private fun bailianWorkspaceName(region: BailianRegion) = "speech_bailian_workspace_${region.name.lowercase()}"
        private fun bailianLanguageName(region: BailianRegion) = "speech_bailian_language_${region.name.lowercase()}"
        private const val FUNASR_LANGUAGE = "speech_funasr_language"
        private const val TYPE = "type"
        private const val SILENT = "speech_system_silent"
        private const val STRIKES = "speech_system_silent_strikes"
        private const val SERVICE = "speech_system_service"

        /** Only this many consecutive non-responses are recorded. */
        const val SILENT_STRIKES = 2

        fun get(context: Context) = SpeechSettings(
            context.applicationContext.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE),
        )
    }
}
