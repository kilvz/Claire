package com.wangzan101.mika.settings

import com.wangzan101.mika.core.agent.GroupPool
import com.wangzan101.mika.core.agent.PoolMember
import com.wangzan101.mika.core.model.MemberKey
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.AnthropicModel
import com.wangzan101.mika.core.model.KeyPoolManager
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ClientHeaders
import com.wangzan101.mika.core.model.GeminiModel
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.core.model.usage.UsagePurpose
import com.wangzan101.mika.core.model.usage.UsageSource
import com.wangzan101.mika.core.model.usage.metered
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.wangzan101.mika.settings.ProviderStore.Companion.http
import com.wangzan101.mika.settings.ProviderStore.Companion.XAI_ACCOUNT
import com.wangzan101.mika.settings.ProviderStore.Companion.adapterFor
import com.wangzan101.mika.settings.ProviderStore.Companion.get

// Provider storage: builds callable models and fallback chains from roles, configs and model groups
/**
 * The config this role actually uses; null when nothing is selected or the role is unused. The image-seeing flag is derived on the spot rather than read from whatever the stored listing data says:
 * for the vision role it is always true (the user selected it to see images); other roles follow [ModelOptions.seesImages] — what the user said about this model wins,
 * and when nothing was said but vision also uses this config (the same model is selected, or "use the main model"), it likewise counts as able to see, so a screenshot is attached directly instead of making a separate vision call.
 */
fun ProviderStore.configFor(role: ModelRole): ProviderConfig? = configFor(role, _roles.value)

/** Resolve the representative config from an effective role choice; conversation overrides are not written back to global roles. */
internal fun ProviderStore.configFor(role: ModelRole, choices: RoleChoices): ProviderConfig? {
    // Groups give the UI a representative as the "first enabled member"; real routing goes per member in modelFor
    val id = choices.resolveConfig(role)
        ?: choices.resolveGroup(role)?.let(::group)?.enabledMembers?.firstOrNull()?.configId ?: return null
    val cfg = _configs.value.firstOrNull { it.id == id } ?: return null
    val sees = role == ModelRole.Vision || ModelOptions.seesImages(cfg.capabilities.vision,
        provider(cfg.providerId)?.visionOverrides?.get(cfg.model), usedForVision = choices.resolveConfig(ModelRole.Vision) == cfg.id)
    return if (sees == cfg.capabilities.vision) cfg else cfg.copy(capabilities = cfg.capabilities.copy(vision = sees))
}

/** Whether an override can still serve as the main model right now; an invalid override stays in the conversation-side file and only falls back to global during resolution. */
fun ProviderStore.usableMainChoice(choice: ModelChoice?): ModelChoice? = when (choice) {
    is ModelChoice.Model -> choice.takeIf { c ->
        _configs.value.any { it.id == c.configId && it.canUseForChat() && it.capabilities.toolCalls }
    }
    is ModelChoice.Group -> choice.takeIf { groupUsable(it.groupId, ModelRole.Main) }
    null -> null
}

/** The single override resolution entry point shared by the main model, SameAsMain, fallbacks and prompt metadata. */
internal fun ProviderStore.effectiveRoles(mainOverride: ModelChoice? = null): RoleChoices {
    val current = _roles.value
    return when (val usable = usableMainChoice(mainOverride)) {
        is ModelChoice.Model -> current.copy(main = usable.configId, mainGroup = null)
        is ModelChoice.Group -> current.copy(main = null, mainGroup = usable.groupId)
        null -> current
    }
}

/** Returns the provider of the main model when that provider has not passed testing yet; uses the same override resolution as this run. */
fun ProviderStore.untestedMainProvider(mainOverride: ModelChoice? = null): Provider? {
    val choices = effectiveRoles(mainOverride)
    val cfg = configFor(ModelRole.Main, choices) ?: return null
    val candidates = choices.resolveGroup(ModelRole.Main)?.let(::group)?.enabledMembers
        ?.mapNotNull { m -> _configs.value.firstOrNull { it.id == m.configId } } ?: listOf(cfg)
    return candidates.firstNotNullOfOrNull { c -> provider(c.providerId)?.takeIf { it.untested(c.model) } }
}

/**
 * The model used for this role, with metering wrapped around it; null when nothing is selected or the role is unused. Usage is recorded under [role]'s name: vision and light calls that "use the main model"
 * are recorded as vision and light, with the provider and model of the main model's config. This is the only entry point in the app for building callable models (the proactive capability goes through [proactiveModel]).
 * When a model group is selected it returns a [GroupPool]: each member meters its own usage, and the group itself only handles selection and transfer.
 * [session] is the conversation this call belongs to (models built within one conversation all get the same one); for providers that declare a session header, every request carries it.
 */
fun ProviderStore.modelFor(
    role: ModelRole,
    session: ModelSession = ModelSession.fresh(),
    mainOverride: ModelChoice? = null,
): ChatModel? {
    val choices = effectiveRoles(mainOverride)
    // Whether the group comes from a conversation override or global settings, it must satisfy this purpose (for example Vision needs at least one member that can see images).
    choices.resolveGroup(role)?.let(::group)?.let { group ->
        if (!groupUsable(group, role)) return null
        return groupModelFor(role, group, session, choices)
    }
    val cfg = configFor(role, choices) ?: return null
    val effort = choices.reasoningEffort(role)?.takeIf { ReasoningOptions.of(cfg, listedOf(cfg))?.levels?.contains(it) == true }
    return meteredWith(cfg, role, session, UsagePurpose.of(role), ::checkDailyBudget,
        effortOverride = effort, useRoleEffort = false)
}

/** Build each member in the group's member order; each member's context settings, thinking level and request headers come from its own config. */
internal fun ProviderStore.groupModelFor(
    role: ModelRole,
    group: ModelGroup,
    session: ModelSession,
    choices: RoleChoices = _roles.value,
): ChatModel? {
    val members = group.members.filter { it.enabled }.mapNotNull { m ->
        val cfg = _configs.value.firstOrNull { it.id == m.configId } ?: return@mapNotNull null
        val p = provider(cfg.providerId) ?: return@mapNotNull null
        val effort = memberReasoningEffort(cfg, p, m.reasoningEffort ?: choices.reasoningEffort(role))
        val model = meteredWith(cfg, role, session, UsagePurpose.of(role), ::checkDailyBudget,
            effortOverride = effort, useRoleEffort = false)
        PoolMember(
            key = MemberKey(p.id, cfg.model), model = model, priority = m.priority,
            weight = m.weight.takeIf { it > 0 } ?: 1, costRank = m.costRank, providerName = p.name,
        )
    }
    if (members.isEmpty()) return null
    return GroupPool(
        group, members, health, sticky,
        stickyKey = "${group.id}/${session.id}",
        weightedWeights = weights.forGroup(group.id),
    )
}

/** Passes null when the member does not support the level selected for the role, so a value the provider does not recognize is never sent out. */
internal fun ProviderStore.memberReasoningEffort(cfg: ProviderConfig, p: Provider, effort: String?): String? {
    if (effort == null) return null
    val levels = ReasoningOptions.of(cfg, listedOf(cfg))?.levels ?: return null
    return effort.takeIf { it in levels }
}

/** The config [configId] itself (without deriving the image-seeing flag for a role); null when it does not exist. */
fun ProviderStore.configById(configId: String): ProviderConfig? = _configs.value.firstOrNull { it.id == configId }

/** How this config looks when used for the given role: the image-seeing flag is derived by the role rules. */
internal fun ProviderStore.asRole(cfg: ProviderConfig, role: ModelRole): ProviderConfig {
    val sees = role == ModelRole.Vision || ModelOptions.seesImages(cfg.capabilities.vision,
        provider(cfg.providerId)?.visionOverrides?.get(cfg.model), usedForVision = _roles.value.resolve(ModelRole.Vision) == cfg.id)
    return if (sees == cfg.capabilities.vision) cfg else cfg.copy(capabilities = cfg.capabilities.copy(vision = sees))
}

/**
 * Builds a model for the given role from a config id (for fallback chains): metering and the daily budget check follow the same path as that role.
 * Null when the config does not exist.
 */
fun ProviderStore.modelForRoleConfig(role: ModelRole, configId: String, session: ModelSession = ModelSession.fresh()): ChatModel? {
    val raw = configById(configId) ?: return null
    if (!raw.canUseForChat() && configId !in _roles.value.referenced) return null
    val p = provider(raw.providerId)
    val source = UsageSource(raw.providerId, p?.name ?: raw.name, raw.model, UsagePurpose.of(role))
    val listed = listedOf(raw)
    val settings = p?.contextSettings?.get(raw.model)
    val adjusted = asRole(raw, role)
    val configured = if (settings == null) adjusted.copy(capabilities = adjusted.capabilities.copy(
        compactionEveryRounds = TEMP_COMPACTION_EVERY_ROUNDS,
    )) else adjusted.copy(capabilities = adjusted.capabilities.copy(
        contextTokens = settings.windowTokens ?: adjusted.capabilities.contextTokens,
        compactionTriggerTokens = settings.triggerTokens,
        compactionEveryRounds = TEMP_COMPACTION_EVERY_ROUNDS,
    ))
    val roleEffort = _roles.value.reasoningEffort(role)
    val effort = roleEffort?.takeIf { ReasoningOptions.of(configured, listed)?.levels?.contains(it) == true }
    return modelFactory(configured, apiKey(raw.id), listed?.endpoint, ClientSetup(
        headers(p?.sessionHeader, session), reasoningEffort = effort, thinkingTypes = listed?.thinkingTypes,
        readTimeoutSeconds = settings?.readTimeoutSeconds,
        serviceTier = p?.requestedServiceTier(raw.model, listed?.endpoint),
    )).metered(source, usage, beforeCall = ::checkDailyBudget)
}

/**
 * Builds a model for the main role from a config id (for fallback chains, see [RoleChoices.mainFallbacks]): metering and the daily budget check follow the same path as the main model,
 * with usage recorded under the main model. Null when the config does not exist.
 */
fun ProviderStore.modelForConfig(configId: String, session: ModelSession = ModelSession.fresh()): ChatModel? =
    modelForRoleConfig(ModelRole.Main, configId, session)

/**
 * Returns the fallback models and their provider IDs in the given role's fallback order, so failover can determine budget ownership.
 * Slots whose config is gone or that cannot be built are skipped; a shorter chain still works.
 */
fun ProviderStore.fallbackChain(
    role: ModelRole,
    session: ModelSession = ModelSession.fresh(),
    mainOverride: ModelChoice? = null,
): Pair<List<ChatModel>, List<String?>> {
    val models = ArrayList<ChatModel>()
    val providerIds = ArrayList<String>()
    val choices = effectiveRoles(mainOverride)
    val rolePrimaryId = choices.resolve(role)
    for (id in choices.fallbacks(role)) {
        if (id == rolePrimaryId) continue
        val cfg = configById(id) ?: continue
        val m = modelForRoleConfig(role, id, session) ?: continue
        models += m
        providerIds += cfg.providerId
    }
    return models to providerIds
}

/**
 * Returns the fallback models and their provider IDs in the order of [RoleChoices.mainFallbacks], so failover can determine budget ownership.
 */
fun ProviderStore.mainFallbackChain(
    session: ModelSession = ModelSession.fresh(),
    mainOverride: ModelChoice? = null,
): Pair<List<ChatModel>, List<String?>> = fallbackChain(ModelRole.Main, session, mainOverride)

/** Vision model fallback chain; SameAsMain excludes the main config itself based on this run's effective main model. */
fun ProviderStore.visionFallbackChain(
    session: ModelSession = ModelSession.fresh(),
    mainOverride: ModelChoice? = null,
): List<ChatModel> = fallbackChain(ModelRole.Vision, session, mainOverride).first

/** Light model fallback chain; SameAsMain excludes the main config itself based on this run's effective main model. */
fun ProviderStore.lightFallbackChain(
    session: ModelSession = ModelSession.fresh(),
    mainOverride: ModelChoice? = null,
): List<ChatModel> = fallbackChain(ModelRole.Light, session, mainOverride).first

fun ProviderStore.mainProviderId(mainOverride: ModelChoice? = null): String? =
    configFor(ModelRole.Main, effectiveRoles(mainOverride))?.providerId

/** Re-checks the config and the ledger before every actual request; when the budget is reached no request is sent and no phantom call is recorded. */
internal fun ProviderStore.checkDailyBudget(source: UsageSource) {
    val p = provider(source.providerId) ?: return
    val limit = p.dailyTokenBudget ?: return
    val used = dailyUsage(source.providerId)
    if (used >= limit) throw ModelError.DailyBudgetExceeded(p.name, limit, used, providerId = source.providerId)
}

internal fun ProviderStore.headers(sessionHeader: String?, session: ModelSession) = ClientHeaders.of(userAgent, sessionHeader, session)

/** Headers given to this provider for requests that belong to no conversation (such as speech transcription): the client identifier, plus a fresh session ID when it declares a session header. */
fun ProviderStore.clientHeaders(p: Provider): Map<String, String> = headers(p.sessionHeader, ModelSession.fresh())

/** When the model specifies an endpoint of its own, pick the adapter by its protocol and use its address; otherwise use the config's API kind and address. In both cases the key is the provider's. */
/**
 * Request headers are carried the same way for all four protocols. The reply limit ([ClientSetup.maxOutputTokens]) is only given to Messages (where it is already required) and generateContent;
 * not to Chat Completions (some models accept only `max_tokens`, others only `max_completion_tokens`, and passing the wrong one rejects the whole request,
 * so the connection test would report a false failure), nor to Responses (the ChatGPT plan path does not accept `max_output_tokens`).
 * When a read timeout ([ClientSetup.readTimeoutSeconds]) is configured, the client is derived from it (sharing the connection pool and proxy), and slow local inference can raise it (#295);
 * when it is not configured the global default (120 s) is used. The derivation happens only once, when the model is built, and does not affect other models.
 */
internal fun ProviderStore.buildModel(cfg: ProviderConfig, apiKey: String?, endpoint: ModelEndpoint?, setup: ClientSetup): ChatModel {
    val (kind, adapterBase) = adapterFor(cfg, endpoint)
    // For subscription sign-in use the provider's current address (it may have changed at sign-in); the config stores the one from when it was added.
    // OpenCode Go is the exception: each listed model names the base URL of its own protocol, which the listing already resolved.
    val baseUrl = if (cfg.kind == ProviderConfig.Kind.Xai) {
        provider(cfg.providerId)?.baseUrl?.takeIf { it.isNotBlank() } ?: adapterBase
    } else adapterBase
    // OpenCode Go spends an account sign-in: its token goes into whichever header the listed protocol uses, and a 401 asks for a refresh once.
    val account = if (cfg.kind == ProviderConfig.Kind.OpenCodeGo) accountTokensFor(cfg.providerId) else null
    val freshOnce = AtomicBoolean(false)
    val keySupplier: (suspend () -> String?)? = account?.let { tokens -> { tokens.get(freshOnce.getAndSet(false)) } }
    val onKeyRejected: ((String, Long?, Int) -> Unit)? = account?.let { { _, _, status -> if (status == 401) freshOnce.set(true) } }
    val requestKey = if (account != null) null else apiKey
    val defaultHttp = http
    val http = setup.readTimeoutSeconds
        ?.takeIf { it in MODEL_READ_TIMEOUT_SECONDS }
        ?.let { seconds -> defaultHttp.newBuilder().readTimeout(seconds.toLong(), TimeUnit.SECONDS).build() }
        ?: defaultHttp
    return when (kind) {
        ProviderConfig.Kind.OpenAiCompat ->
            OpenAiCompatModel(
                baseUrl, requestKey, cfg.model, cfg.capabilities, http, endpoint?.reasoningField, setup.headers,
                reasoningEffort = setup.reasoningEffort,
                keySupplier = keySupplier ?: { activeApiKey(cfg.providerId) },
                onKeyRejected = onKeyRejected ?: { key, retryMs, status ->
                    KeyPoolManager.shared.markRejected(cfg.providerId, key, status, retryMs)
                }
            )
        ProviderConfig.Kind.Anthropic -> AnthropicModel(
            baseUrl, requestKey.orEmpty(), cfg.model, cfg.capabilities, http,
            maxTokens = setup.maxOutputTokens,
            headers = setup.headers, reasoningEffort = setup.reasoningEffort, thinkingTypes = setup.thinkingTypes,
            keySupplier = keySupplier, onKeyRejected = onKeyRejected,
        )
        ProviderConfig.Kind.Gemini ->
            GeminiModel(
                baseUrl, requestKey.orEmpty(), cfg.model, cfg.capabilities, http, setup.headers, setup.maxOutputTokens,
                reasoningEffort = setup.reasoningEffort,
                keySupplier = keySupplier ?: { activeApiKey(cfg.providerId) },
                onKeyRejected = onKeyRejected ?: { key, retryMs, status ->
                    KeyPoolManager.shared.markRejected(cfg.providerId, key, status, retryMs)
                }
            )
        ProviderConfig.Kind.OpenAiResponses ->
            ResponsesModel(baseUrl, account ?: BearerToken.fixed(apiKey), cfg.model, cfg.capabilities, http, headers = setup.headers,
                reasoningEffort = setup.reasoningEffort, serviceTier = setup.serviceTier)
        ProviderConfig.Kind.ChatGpt ->
            ResponsesModel(baseUrl, tokensFor(cfg.providerId), cfg.model, cfg.capabilities, http, chatGptPlan = true, headers = setup.headers,
                reasoningEffort = setup.reasoningEffort, serviceTier = setup.serviceTier)
        ProviderConfig.Kind.Xai ->
            ResponsesModel(baseUrl, accountTokensFor(cfg.providerId), cfg.model, cfg.capabilities, http, headers = setup.headers,
                reasoningEffort = setup.reasoningEffort, accountLogin = XAI_ACCOUNT)
        // adapterFor already turned OpenCode Go into one of the four protocols above
        ProviderConfig.Kind.OpenCodeGo -> throw IllegalStateException("OpenCode Go must resolve to a protocol adapter")
    }
}
