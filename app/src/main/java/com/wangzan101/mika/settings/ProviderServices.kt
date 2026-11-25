package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.core.model.KeyPoolManager
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GeminiTtsModel
import com.wangzan101.mika.core.model.OpenAiSpeechModel
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.GeminiImageModel
import com.wangzan101.mika.core.model.ImageCapabilities
import com.wangzan101.mika.core.model.ImageModel
import com.wangzan101.mika.core.model.OpenAiImagesModel
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelCatalog
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.search.GoogleGroundingSearch
import com.wangzan101.mika.core.search.WebSearch
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.proactive.TargetedModel
import com.wangzan101.mika.core.proactive.TriageTarget
import com.wangzan101.mika.core.model.usage.DeepSeekPriceTable
import com.wangzan101.mika.core.model.usage.UsagePurpose
import com.wangzan101.mika.core.model.usage.UsageSource
import com.wangzan101.mika.core.model.usage.metered
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit
import com.wangzan101.mika.settings.ProviderStore.GroundingTarget
import com.wangzan101.mika.settings.ProviderStore.Companion.http
import com.wangzan101.mika.settings.ProviderStore.Companion.modelHttp
import com.wangzan101.mika.settings.ProviderStore.Companion.PROBE_ID
import com.wangzan101.mika.settings.ProviderStore.Companion.PROBE_SYSTEM
import com.wangzan101.mika.settings.ProviderStore.Companion.PROBE_TEXT
import com.wangzan101.mika.settings.ProviderStore.Companion.PROBE_TOOL
import com.wangzan101.mika.settings.ProviderStore.Companion.PROBE_MAX_TOKENS
import com.wangzan101.mika.settings.ProviderStore.Companion.adapterFor
import com.wangzan101.mika.settings.ProviderStore.Companion.get

// Provider storage: proactive task metering, search grounding, speech and image models, probing and model listing
/**
 * The model used for proactive features: the light model, or the main model when there is none; usage is recorded under [UsagePurpose.Proactive].
 * Before every request only the proactive feature's own daily budget is checked (see [ProactiveBudget]); the provider's daily budget is neither checked nor consumed.
 */
fun ProviderStore.proactiveModel(session: ModelSession = ModelSession.fresh(), call: ProactiveCall = ProactiveCall.Triage): ChatModel? =
    proactiveTargeted(call, session)?.model

/** The provider and model that [proactiveModel] will actually use (likewise light first, then main); no model is built and no Key is read. The UI uses this to ask the user for confirmation. */
fun ProviderStore.proactiveTarget(): TriageTarget? = proactiveConfig()?.let { (_, cfg) -> targetOf(cfg) }

/**
 * The proactive model (same as [proactiveModel]) together with the provider and model of the config it is built from: both come from a single read of the config.
 * Consent is checked against this target before a request is sent, so a model swapped in the meantime never receives notification text that was approved for a different one.
 * [call] is the kind of request being sent: the output limit follows it (see [ProactiveModelSetup]). It has no default: omitting it would silently apply the short limit to the kinds that reply at length.
 */
fun ProviderStore.proactiveTargeted(call: ProactiveCall, session: ModelSession = ModelSession.fresh()): TargetedModel? =
    proactiveConfig()?.let { (role, cfg) ->
        TargetedModel(targetOf(cfg), meteredWith(cfg, role, session, UsagePurpose.Proactive, ::checkProactiveBudget, call, ::checkProactiveRetry))
    }

/** The config used for proactive features and its role: the light model, or the main model when there is none. Reads the config only once. */
internal fun ProviderStore.proactiveConfig(): Pair<ModelRole, ProviderConfig>? =
    configFor(ModelRole.Light)?.let { ModelRole.Light to it } ?: configFor(ModelRole.Main)?.let { ModelRole.Main to it }

internal fun ProviderStore.targetOf(cfg: ProviderConfig) = TriageTarget(cfg.providerId, provider(cfg.providerId)?.name ?: cfg.name, cfg.model)

internal fun ProviderStore.meteredFor(
    role: ModelRole, session: ModelSession, purpose: UsagePurpose, beforeCall: (UsageSource) -> Unit,
): ChatModel? = configFor(role)?.let { cfg -> meteredWith(cfg, role, session, purpose, beforeCall) }

/** Build a model from [cfg] (the config already resolved for [role]) with metering applied. [effortOverride] is the per-member reasoning level within a group. */
internal fun ProviderStore.meteredWith(
    cfg: ProviderConfig, role: ModelRole, session: ModelSession, purpose: UsagePurpose, beforeCall: (UsageSource) -> Unit,
    call: ProactiveCall = ProactiveCall.Triage,
    beforeRetry: (UsageSource, Int) -> Unit = { _, _ -> },
    effortOverride: String? = null,
): ChatModel {
    val p = provider(cfg.providerId)
    val listed = listedOf(cfg)
    val source = UsageSource(cfg.providerId, p?.name ?: cfg.name, cfg.model, purpose,
        deepSeekDirect = DeepSeekPriceTable.isOfficialEndpoint(adapterFor(cfg, listed?.endpoint).second))
    val settings = p?.contextSettings?.get(cfg.model)
    val configured = if (settings == null) cfg.copy(capabilities = cfg.capabilities.copy(
        compactionEveryRounds = TEMP_COMPACTION_EVERY_ROUNDS,
    )) else cfg.copy(capabilities = cfg.capabilities.copy(
        contextTokens = settings.windowTokens ?: cfg.capabilities.contextTokens,
        compactionTriggerTokens = settings.triggerTokens,
        compactionEveryRounds = TEMP_COMPACTION_EVERY_ROUNDS,
    ))
    val roleEffort = effortOverride ?: _roles.value.reasoningEffort(role)
    // Proactive features: reasoning uses the lowest level the provider documents; the two request kinds with short replies get a separate limit (see ProactiveModelSetup)
    val (effort, cap) = if (purpose == UsagePurpose.Proactive) {
        ProactiveModelSetup.of(adapterFor(cfg, listed?.endpoint).first, ReasoningOptions.of(cfg, listed), roleEffort, call)
    } else roleEffort to null
    return modelFactory(configured, apiKey(cfg.id), listed?.endpoint, ClientSetup(
        headers(p?.sessionHeader, session), maxOutputTokens = cap, reasoningEffort = effort,
        thinkingTypes = listed?.thinkingTypes, readTimeoutSeconds = settings?.readTimeoutSeconds,
        serviceTier = p?.requestedServiceTier(cfg.model, listed?.endpoint),
    )).metered(source, usage, beforeRetry = beforeRetry, beforeCall = beforeCall)
}

/**
 * Calls to proactive features check only their own daily budget: the full hard limits (the token limit, and the request-count limit derived from it, see [ProactiveBudget.check]).
 * Enforcing only part of it is handled by [com.wangzan101.mika.proactive.TriageRunner] stopping early on its own, not here.
 */
@Suppress("UNUSED_PARAMETER")
internal fun ProviderStore.checkProactiveBudget(source: UsageSource) = ProactiveBudget.check(proactiveUsage(), proactiveLimit(), proactiveCalls())

/**
 * Before sending another request within the same call: the [started] requests already issued by this call are not yet recorded in the ledger, so they are added to the check.
 * Otherwise, when only the last request's quota remains, a compatibility retry within one call would push past the request limit.
 */
@Suppress("UNUSED_PARAMETER")
internal fun ProviderStore.checkProactiveRetry(source: UsageSource, started: Int) =
    ProactiveBudget.check(proactiveUsage(), proactiveLimit(), proactiveCalls() + started)

/** Returns a stored Gemini Developer API key (`Kind.Gemini` only). */
fun ProviderStore.geminiDeveloperApiKey(): String? {
    return synchronized(this) {
        fun validKey(providerId: String): String? = providerKey(providerId)
            ?.trim()
            ?.takeIf { it.isNotBlank() && ApiKeyText.isHeaderLegal(it) && it.all { ch -> ch.code in 33..126 } }
        val mainCfg = configFor(ModelRole.Main)
        if (mainCfg?.kind == ProviderConfig.Kind.Gemini) {
            validKey(mainCfg.providerId)?.let { return it }
        }
        return _providers.value
            .asSequence()
            .filter { it.kind == ProviderConfig.Kind.Gemini }
            .firstNotNullOfOrNull { validKey(it.id) }
    }
}

internal fun ProviderStore.resolveGrounding(): GroundingTarget? {
    val cfg = configFor(ModelRole.Main) ?: return null
    val p = provider(cfg.providerId)
    val providerName = p?.name?.ifBlank { null } ?: ProviderConnection.displayName(cfg)
    val endpoint = listedOf(cfg)?.endpoint
    val (kind, baseUrl) = adapterFor(cfg, endpoint)
    // Grounding needs a Google Developer API key; OpenCode Go's credential is an account token and its gateway does not offer this
    if (cfg.kind == ProviderConfig.Kind.OpenCodeGo) return GroundingTarget.Unsupported(providerName)
    return when (kind) {
        ProviderConfig.Kind.Gemini -> {
            val key = apiKey(cfg.id)?.trim().orEmpty()
            val cleanModel = cfg.model.trim().removePrefix("models/").trim()
            val cleanBase = baseUrl.trim()
            val validUrl = cleanBase.isNotEmpty() &&
                cleanModel.isNotEmpty() &&
                (cleanBase.trimEnd('/') + "/v1beta/models/$cleanModel:generateContent").toHttpUrlOrNull() != null
            if (key.isBlank() || !ApiKeyText.isHeaderLegal(key) || !validUrl) {
                GroundingTarget.Unsupported(providerName)
            } else {
                GroundingTarget.Gemini(providerName, cleanBase, key, cfg.model, p?.sessionHeader)
            }
        }
        else -> GroundingTarget.Unsupported(providerName)
    }
}

/** Whether Google Search grounding is available for the active main model. */
fun ProviderStore.groundingStatus(): GroundingStatus = when (val target = resolveGrounding()) {
    null -> GroundingStatus(available = false, providerName = null)
    is GroundingTarget.Unsupported -> GroundingStatus(available = false, providerName = target.providerName)
    is GroundingTarget.Gemini -> GroundingStatus(available = true, providerName = target.providerName)
}

/** Builds a Google Search grounding client for the main model, or null when unsupported/unconfigured. */
fun ProviderStore.googleGroundingSearch(session: ModelSession = ModelSession.fresh()): WebSearch? =
    when (val target = resolveGrounding()) {
        null, is GroundingTarget.Unsupported -> null
        is GroundingTarget.Gemini -> GoogleGroundingSearch.forGemini(
            baseUrl = target.baseUrl,
            apiKey = target.apiKey,
            model = target.model,
            client = http,
            headers = headers(target.sessionHeader, session),
        )
    }

/**
 * Speech model: the address, Key and headers come from this provider; chat-model metering is not applied (the caller records reading usage separately).
 * Null when the provider does not exist or the address is invalid. Synthesis takes longer than a single sentence, so the read timeout is raised to 120 seconds.
 */
fun ProviderStore.ttsModel(choice: VoiceChoice, capabilities: TtsCapabilities): TtsModel? {
    val p = provider(choice.providerId) ?: return null
    val model = choice.model.trim()
    if (p.baseUrl.trim().toHttpUrlOrNull() == null) return null
    val client = modelHttp.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    val header = headers(p.sessionHeader, ModelSession.fresh())
    val key = plainApiKey(p.id)
    return when (choice.protocol) {
        TtsProtocol.OpenAiSpeech -> OpenAiSpeechModel(p.baseUrl, key, model, capabilities, client, header)
        TtsProtocol.GeminiTts -> GeminiTtsModel(p.baseUrl, key.orEmpty(), model, capabilities, client, header)
        TtsProtocol.AndroidSystem -> null
    }
}

/**
 * Model used for image generation: the address, Key and headers all come from this provider; chat-model metering is not applied (the caller records image usage separately).
 * Null when the provider, model or protocol is missing. Images take longer than a single sentence, so the read timeout is raised to 180 seconds.
 */
fun ProviderStore.imageModel(choice: ImageChoice, capabilities: ImageCapabilities): ImageModel? {
    val p = provider(choice.providerId) ?: return null
    if (choice.model.isBlank()) return null
    val client = modelHttp.newBuilder().readTimeout(180, TimeUnit.SECONDS).build()
    val header = headers(p.sessionHeader, ModelSession.fresh())
    val key = plainApiKey(p.id)
    return when (choice.protocol) {
        ImageProtocol.OpenAiImages -> OpenAiImagesModel(p.baseUrl, key, choice.model, capabilities, client, header)
        ImageProtocol.GeminiImage -> GeminiImageModel(p.baseUrl, key.orEmpty(), choice.model, capabilities, client, header)
    }
}

/**
 * After the user confirms testing a specific model, build [model] with the adapter and headers used for real tasks,
 * supply a tool that takes no arguments ([PROBE_TOOL]) and ask the model to call it; the reply is limited to very few tokens (where the protocol allows, see [buildModel]).
 * A tool call in the reply yields [ProbeResult.ToolCalled], a text-only reply yields [ProbeResult.NoToolCall], and a failed request returns the server's error verbatim.
 * Not metered: these few tokens belong to no usage purpose. A fresh session ID is used because this belongs to no conversation. Timeouts are the caller's responsibility.
 */
suspend fun ProviderStore.probe(
    kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, sessionHeader: String?,
    model: String, endpoint: ModelEndpoint?, caps: Capabilities,
    providerId: String = "",
): ProbeResult {
    val probeKey = apiKey?.let { KeyPoolManager.shared.parseKeys(it).firstOrNull() ?: it }
    val cfg = ProviderConfig(PROBE_ID, kind, baseUrl, model, caps.copy(toolCalls = true), providerId = providerId)
    val m = modelFactory(cfg, probeKey, endpoint, ClientSetup(headers(sessionHeader, ModelSession.fresh()), PROBE_MAX_TOKENS))
    return try {
        val reply = m.complete(PROBE_SYSTEM, listOf(Message(Role.User, listOf(Part.Text(PROBE_TEXT)), Author.Mika)), listOf(PROBE_TOOL))
        if (reply.parts.any { it is Part.ToolCall }) ProbeResult.ToolCalled else ProbeResult.NoToolCall
    } catch (e: ModelError) {
        ProbeResult.Failed(e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ProbeResult.Failed(ModelError.BadResponse(e.message.orEmpty(), e))
    }
}

/** This model as last listed by the provider (a separately specified endpoint, documented thinking modes, etc.); looked up every time a model is built, so a re-listing takes effect. */
internal fun ProviderStore.listedOf(cfg: ProviderConfig): ListedModel? =
    provider(cfg.providerId)?.models?.firstOrNull { it.id == cfg.model }

/** List the models available for this provider (see [ModelCatalog]); this belongs to no conversation, so a fresh session ID is used when a session header is declared. */
suspend fun ProviderStore.listModels(kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, sessionHeader: String? = null): ModelListResult {
    val probeKey = apiKey?.let { KeyPoolManager.shared.parseKeys(it).firstOrNull() ?: it }
    return modelLister(kind, baseUrl, probeKey, headers(sessionHeader, ModelSession.fresh()))
}

/** Re-list the models of a connected provider: uses the stored Key; for subscription sign-in it uses the access token (refreshed first when close to expiry). */
suspend fun ProviderStore.listModels(p: Provider): ModelListResult {
    if (p.kind != ProviderConfig.Kind.ChatGpt && p.kind != ProviderConfig.Kind.Xai && p.kind != ProviderConfig.Kind.OpenCodeGo) {
        val key = activeApiKey(p.id) ?: providerKey(p.id)?.let { KeyPoolManager.shared.parseKeys(it).firstOrNull() }
        return listModels(p.kind, p.baseUrl, key, p.sessionHeader)
    }
    val token = try {
        if (p.kind == ProviderConfig.Kind.ChatGpt) tokensFor(p.id).get(fresh = false) else accountTokensFor(p.id).get(fresh = false)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: ModelError.SignInExpired) {
        return ModelListResult.Failed(ListFailure.Auth)
    } catch (e: ModelError) {
        return ModelListResult.Failed(if (e is ModelError.Network || e is ModelError.Proxy) ListFailure.of(e) else ListFailure.Server)
    }
    val base = provider(p.id)?.baseUrl ?: p.baseUrl
    return listModels(p.kind, base, token, p.sessionHeader)
}
