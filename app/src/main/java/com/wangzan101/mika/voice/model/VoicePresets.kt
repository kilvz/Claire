package com.wangzan101.mika.voice.model

import com.wangzan101.mika.calls.OpenAiCallProtocol
import com.wangzan101.mika.calls.VolcRealtimeConfig
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import com.wangzan101.mika.voice.AsrLanguage
import com.wangzan101.mika.voice.BailianProtocol
import com.wangzan101.mika.voice.BailianRegion
import com.wangzan101.mika.voice.DoubaoProtocol
import com.wangzan101.mika.voice.FunAsrProtocol
import java.net.URI
import java.security.MessageDigest

/**
 * A known kind of voice service and only the fields it needs. [VoicePresets.plan] turns it into ordinary catalog
 * entries, so a preset never adds a code path the custom form does not have.
 */
sealed interface VoicePreset {
    /** Volcano Engine speech: one account (an API key, or App ID + access token) for streaming input and realtime calls. */
    data class Volc(
        val apiKey: String = "",
        val appId: String = "",
        val accessToken: String = "",
        val stt: Boolean = true,
        val sttPlan: VolcSttPlan = VolcSttPlan.Duration,
        val realtime: Boolean = true,
    ) : VoicePreset

    /** Alibaba Cloud Bailian streaming recognition: one API key and a region. */
    data class Bailian(
        val apiKey: String,
        val region: BailianRegion = BailianRegion.Beijing,
        val workspace: String = "",
        val model: String = BailianProtocol.DEFAULT_MODEL,
    ) : VoicePreset

    /**
     * Any service speaking the OpenAI audio APIs; each blank model is left out. [ttsVoice] is the voice the speech
     * model uses until the user picks one (a self-hosted server has its own voice ids and refuses a request without
     * one); [realtimeVoice] is the same for the realtime model.
     */
    data class OpenAiCompatible(
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val sttModel: String = "",
        val ttsModel: String = "",
        val realtimeModel: String = "",
        val ttsVoice: String = "",
        val realtimeVoice: String = "",
    ) : VoicePreset

    /**
     * A self-hosted streaming recognition server on the local network; no account.
     * [language] is what the server is told to expect; [AsrLanguage.Auto] tells it nothing and lets it decide.
     */
    data class SelfHostedStt(val url: String, val language: AsrLanguage = AsrLanguage.Auto) : VoicePreset
}

enum class VolcSttPlan(val resourceId: String) {
    Duration(DoubaoProtocol.DURATION),
    Concurrent(DoubaoProtocol.CONCURRENT),
}

/** What a preset writes: one service, its secret if it has one, and the models it unlocks. */
data class VoicePresetPlan(
    val service: VoiceService,
    val credentials: VoiceCredentials?,
    val models: List<VoiceModel>,
) {
    val refs: List<VoiceModelRef>
        get() = models.flatMap { model -> model.capabilities.map { VoiceModelRef(model.serviceId, model.id, it) } }
}

object VoicePresets {
    const val VOLC_SERVICE = "volc"
    const val VOLC_STT_MODEL = "doubao-streaming"
    private val HTTP_SCHEMES = setOf("http", "https")
    private val SECRET_ID_UNSAFE = Regex("[^A-Za-z0-9._:-]")

    /** Builds the catalog entries; throws [VoiceModelError.IncompleteConfig] naming what is missing. */
    fun plan(preset: VoicePreset): VoicePresetPlan = when (preset) {
        is VoicePreset.Volc -> volc(preset)
        is VoicePreset.Bailian -> bailian(preset)
        is VoicePreset.OpenAiCompatible -> openAiCompatible(preset)
        is VoicePreset.SelfHostedStt -> selfHosted(preset)
    }

    /** Saves a plan; models are replaced in place so re-saving a preset edits it instead of adding copies. */
    fun apply(manager: VoiceAccountManager, catalog: VoiceCatalogStore, plan: VoicePresetPlan): List<VoiceModelRef> {
        manager.saveVoiceAccount(plan.service, plan.credentials)
        val keep = plan.models.map { it.id }.toSet()
        // Fetched and manually typed models are not part of the preset and are kept when the key changes (the next refresh re-fetches them under the new account)
        catalog.state.value.models.filter {
            it.serviceId == plan.service.id && it.id !in keep && it.origin == VoiceModelOrigin.Builtin
        }.forEach { stale ->
            stale.capabilities.forEach { catalog.deleteModel(VoiceModelRef(stale.serviceId, stale.id, it)) }
        }
        plan.models.forEach(catalog::upsertModel)
        return plan.refs
    }

    private fun volc(p: VoicePreset.Volc): VoicePresetPlan {
        val key = p.apiKey.trim()
        val appId = p.appId.trim()
        val token = p.accessToken.trim()
        val credentials = when {
            key.isNotEmpty() -> VoiceCredentials(apiKey = key)
            appId.isNotEmpty() && token.isNotEmpty() -> VoiceCredentials(appId = appId, accessKey = token)
            else -> throw VoiceModelError.IncompleteConfig("Enter an API key, or both App ID and access token")
        }
        if (!p.stt && !p.realtime) throw VoiceModelError.IncompleteConfig("Choose at least one capability")
        val service = VoiceService(VOLC_SERVICE, "Volcano Engine", VoiceAccount.VoiceOwned("preset:$VOLC_SERVICE"))
        val models = buildList {
            if (p.stt) add(
                VoiceModel(
                    VOLC_SERVICE, VOLC_STT_MODEL, "Doubao Streaming",
                    stt = SttBinding(SttProtocol.DoubaoStreaming, resourceId = p.sttPlan.resourceId),
                ),
            )
            if (p.realtime) add(
                VoiceModel(
                    VOLC_SERVICE, VolcRealtimeConfig.MODEL, "Doubao Realtime",
                    realtime = RealtimeBinding(RealtimeProtocol.VolcDuplex, VolcRealtimeProtocol.ENDPOINT, VolcRealtimeConfig.DEFAULT_SPEAKER),
                ),
            )
        }
        return VoicePresetPlan(service, credentials, models)
    }

    private fun bailian(p: VoicePreset.Bailian): VoicePresetPlan {
        val key = p.apiKey.trim().ifEmpty { throw VoiceModelError.IncompleteConfig("Enter the API key") }
        val workspace = BailianProtocol.workspace(p.workspace)
            ?: throw VoiceModelError.IncompleteConfig("Business space ID is not valid")
        val model = p.model.trim().ifEmpty { BailianProtocol.DEFAULT_MODEL }
        val id = "bailian:${p.region.name.lowercase()}"
        val service = VoiceService(id, "Bailian ${p.region.name}", VoiceAccount.VoiceOwned("preset:$id"))
        val stt = VoiceModel(
            id, model, model,
            stt = SttBinding(
                SttProtocol.BailianStreaming, endpoint = p.region.endpoint(workspace), region = p.region.name, businessSpace = workspace,
            ),
        )
        return VoicePresetPlan(service, VoiceCredentials(apiKey = key), listOf(stt))
    }

    private fun openAiCompatible(p: VoicePreset.OpenAiCompatible): VoicePresetPlan {
        val base = p.baseUrl.trim().trimEnd('/')
        // Services run locally or on the LAN are usually http only; accept it like when adding a chat provider; the Key is still required and still stored encrypted
        val uri = runCatching { URI(base) }.getOrNull()
            ?.takeIf { it.scheme?.lowercase() in HTTP_SCHEMES && !it.host.isNullOrBlank() }
            ?: throw VoiceModelError.IncompleteConfig("Address must start with http:// or https://")
        // It is unknown what the service would do with query, fragment or username/password, so do not guess and always reject them
        if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) {
            throw VoiceModelError.IncompleteConfig("Address must not contain a query, fragment or user info")
        }
        val key = p.apiKey.trim().ifEmpty { throw VoiceModelError.IncompleteConfig("Enter the API key") }
        // Different ports on the same machine are different services (each stored separately, a later save does not replace an earlier one); without a port, or with the default port, the id is the same as before
        val defaultPort = if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
        val host = uri.host + if (uri.port >= 0 && uri.port != defaultPort) ":${uri.port}" else ""
        val id = "compat:$host${uri.path.orEmpty().trimEnd('/')}"
        val name = p.name.trim().ifEmpty { host }
        val service = VoiceService(id, name, VoiceAccount.VoiceOwned(secretId(id)))
        val stt = p.sttModel.trim()
        val tts = p.ttsModel.trim()
        val realtime = p.realtimeModel.trim()
        if (stt.isEmpty() && tts.isEmpty() && realtime.isEmpty()) throw VoiceModelError.IncompleteConfig("Enter at least one model")
        // Models with the same id merge into one entry, with multiple capabilities on the same model
        val byId = linkedMapOf<String, VoiceModel>()
        fun put(modelId: String, change: (VoiceModel) -> VoiceModel) {
            byId[modelId] = change(byId[modelId] ?: VoiceModel(id, modelId, modelId))
        }
        if (stt.isNotEmpty()) put(stt) { it.copy(stt = SttBinding(SttProtocol.AudioTranscriptions, endpoint = base)) }
        if (tts.isNotEmpty()) put(tts) { it.copy(tts = TtsBinding(TtsProtocol.OpenAiSpeech, endpoint = base, voice = p.ttsVoice.trim())) }
        if (realtime.isNotEmpty()) put(realtime) {
            it.copy(realtime = RealtimeBinding(
                RealtimeProtocol.OpenAiRealtime, realtimeEndpoint(uri), p.realtimeVoice.trim().ifEmpty { OpenAiCallProtocol.VOICE },
            ))
        }
        return VoicePresetPlan(service, VoiceCredentials(apiKey = key), byId.values.toList())
    }

    /**
     * The id used to store this service's Key. Credential ids accept only letters, digits and `._:-`, while a service id made from an address with a path (…/v1) contains `/`:
     * those that can be used directly stay as they are (previously stored Keys are still found); for the rest, other characters are replaced with `_`, truncated, and a digest of the service id appended,
     * so two addresses never map to the same id.
     */
    internal fun secretId(serviceId: String): String {
        val plain = "preset:$serviceId"
        if (VoiceCredentialStore.validSecretId(plain)) return plain
        val digest = MessageDigest.getInstance("SHA-256").digest(serviceId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(12)
        return "preset:" + serviceId.replace(SECRET_ID_UNSAFE, "_").take(120) + "." + digest
    }

    /** The OpenAI realtime socket lives next to the REST API: same host and path, `wss` and `/realtime`. */
    internal fun realtimeEndpoint(base: URI): String =
        "wss://${base.authority}${base.path.orEmpty().trimEnd('/')}/realtime"

    private fun selfHosted(p: VoicePreset.SelfHostedStt): VoicePresetPlan {
        val endpoint = FunAsrProtocol.normalize(p.url) ?: throw VoiceModelError.IncompleteConfig("Address is not valid")
        val host = runCatching { URI(endpoint).authority }.getOrNull().orEmpty().ifEmpty { endpoint }
        val id = "selfhosted:$host"
        val service = VoiceService(id, host, VoiceAccount.None)
        val model = VoiceModel(
            id, "funasr", "Fun-ASR Streaming",
            stt = SttBinding(SttProtocol.FunAsrStreaming, endpoint = endpoint, language = p.language.code),
        )
        return VoicePresetPlan(service, null, listOf(model))
    }
}
