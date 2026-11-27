package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.core.agent.GroupWeights
import com.wangzan101.mika.core.agent.ModelHealthRegistry
import com.wangzan101.mika.core.agent.StickyTable
import com.wangzan101.mika.core.model.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.model.chatgpt.ChatGptTokens
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ClientHeaders
import com.wangzan101.mika.core.model.ModelCatalog
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.model.oauth.AccountTokens
import com.wangzan101.mika.core.model.oauth.OpenCodeAuth
import com.wangzan101.mika.core.model.oauth.OpenRouterAuth
import com.wangzan101.mika.core.model.oauth.XaiAuth
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import com.wangzan101.mika.core.model.usage.UsageSink
import com.wangzan101.mika.core.model.usage.metered
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient

/**
 * Storage of providers and model configs, plus role selection. The config is stored as a JSON file, and API Keys are encrypted by [KeyCipher] and stored in a separate file, one per provider.
 *
 * Users manage three layers: which providers are connected, which model or model group each role ([RoleChoices]) uses, and which models a model group contains.
 * Model configs ([ProviderConfig]) are an internal layer: they are created on the spot when a model is selected or added to a group, and deleted when neither a role nor a group uses them anymore
 * (see [prune]; Keys are stored per provider, so deleting a config leaves the Key alone). Selecting a concrete model sets the corresponding capability flags to true (see [choose]).
 *
 * Legacy files: when they only hold model configs and Keys stored by config id, reading them groups them into providers by [ProviderConnection.keyOf],
 * takes the Key from any entry in that group, and clears the old per-config-id Keys (see [migrate]); roles only recorded explicit selections,
 * so on load the assignment actually in effect back then is written out once (see [migrateRoles]). Already-migrated data is left alone; running it repeatedly has no side effects.
 *
 * Models used for calls only leave through [modelFor], always with metering applied (see [metered]), recording every call to [usage]:
 * so no caller has to account for its own calls or their counts.
 */
class ProviderStore(
    dir: File,
    internal val cipher: KeyCipher,
    internal val usage: UsageSink = UsageSink { _, _ -> },
    /** Queried live before each request for this provider's reported tokens for the current local day; the daily limit is disabled by default. */
    internal val dailyUsage: (String) -> Long = { 0L },
    internal val newId: () -> String = { UUID.randomUUID().toString() },
    /** The User-Agent sent to model services; the app sends `Claire/<version>`. */
    internal val userAgent: String = "Claire",
    /** Model listing: API type, address, Key, and the headers sent with every request. */
    internal val modelLister: suspend (ProviderConfig.Kind, String, String?, Map<String, String>) -> ModelListResult = ::listAvailable,
    /** The authorization service for "sign in with ChatGPT" (exchange tokens, refresh, revoke). */
    val chatGptAuth: ChatGptAuth = defaultChatGptAuth,
    /** xAI subscription sign-in (device code, refresh). */
    val xaiAuth: XaiAuth = defaultXaiAuth,
    /** OpenCode Go subscription sign-in (device code, refresh). */
    val openCodeAuth: OpenCodeAuth = defaultOpenCodeAuth,
    /** OpenRouter account authorization (exchanges for a Key). */
    val openRouterAuth: OpenRouterAuth = defaultOpenRouterAuth,
    /** Proactive tokens reported today (summed over all providers), its daily budget, and the number of requests issued today; read live before each request. */
    internal val proactiveUsage: () -> Long = { 0L },
    internal val proactiveLimit: () -> Long = { ProactiveBudget.DEFAULT_DAILY_LIMIT },
    internal val proactiveCalls: () -> Long = { 0L },
    /** 角色和组以外仍拥有配置的外部引用；组合根只提供 id 集，不让设置层依赖聊天或界面。 */
    internal val externalConfigReferences: () -> Set<String> = { emptySet() },
    /**
     * Builds a callable model: config, Key, the endpoint the provider listing assigns to this model individually (null when absent, in which case the config's API type and address are used), headers, etc.
     * When null, the built-in adapter is used (tests substitute a fake one).
     */
    modelFactory: ((ProviderConfig, String?, ModelEndpoint?, ClientSetup) -> ChatModel)? = null,
) {
    internal val modelFactory: (ProviderConfig, String?, ModelEndpoint?, ClientSetup) -> ChatModel = modelFactory ?: ::buildModel
    /** One per ChatGPT provider: only one token exchange runs at a time. */
    internal val chatGptTokens = mutableMapOf<String, ChatGptTokens>()
    /** One per subscription account; as with ChatGPT, only one token exchange runs at a time. */
    internal val accountTokens = mutableMapOf<String, AccountTokens>()
    internal val configFile = File(dir, CONFIG_FILE)
    internal val keyFile = File(dir, KEY_FILE)
    internal val chatGptHostFile = File(dir, CHATGPT_HOST_FILE)
    internal val json = Json { ignoreUnknownKeys = true }

    internal val _providers = MutableStateFlow<List<Provider>>(emptyList())
    /** Connected providers, in the order they were connected. */
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    // Changing a Key may leave the provider list unchanged, so the quota page uses revision numbers to invalidate stale results.
    internal val _providerKeyRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    internal val providerKeyRevisions = _providerKeyRevisions.asStateFlow()

    internal val _configs = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val configs: StateFlow<List<ProviderConfig>> = _configs.asStateFlow()

    internal val _roles = MutableStateFlow(RoleChoices())
    /** What each of the three roles selected; every config referenced is present in [configs]. */
    val roles: StateFlow<RoleChoices> = _roles.asStateFlow()

    internal val _groups = MutableStateFlow<List<ModelGroup>>(emptyList())
    /** Model groups, in creation/save order. */
    val groups: StateFlow<List<ModelGroup>> = _groups.asStateFlow()

    /** Health state of group members, shared within the process: one cooldown per model per provider is enough. */
    internal val health = ModelHealthRegistry()
    /** The member pinned per conversation, in-process only; cleared on restart. */
    internal val sticky = StickyTable()
    /** Accumulated weighted round-robin counters, shared per group; carried over when a pool is recreated for each task. */
    internal val weights = GroupWeights()

    /** Provider id → encrypted Key. */
    internal var keys: Map<String, String> = emptyMap()

    init {
        dir.mkdirs()
        load()
    }

    fun list(): List<ProviderConfig> = _configs.value

    fun provider(id: String): Provider? = _providers.value.firstOrNull { it.id == id }

    internal fun ProviderConfig.canUseForChat(): Boolean = model.isNotBlank()

    internal sealed interface GroundingTarget {
        val providerName: String

        class Gemini(
            override val providerName: String,
            val baseUrl: String,
            val apiKey: String,
            val model: String,
            val sessionHeader: String?,
        ) : GroundingTarget

        class Unsupported(override val providerName: String) : GroundingTarget
    }

    internal fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    internal fun JsonObject.positiveInt(k: String) = (this[k] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    internal fun JsonObject.positiveLong(k: String) = (this[k] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }

    /** The Application that provides the process-wide instance. */
    interface Owner {
        val providers: ProviderStore
    }

    /**
     * Save the model role selected by the user.
     */
    @Synchronized
    fun use(role: ModelRole, providerId: String, model: String, caps: Capabilities): Boolean {
        val p = provider(providerId) ?: return false
        val name = model.trim()
        if (name.isEmpty()) return false
        val existing = _configs.value.firstOrNull { it.providerId == p.id && it.model == name }
        val config = existing ?: ProviderConfig(newId(), p.kind, p.baseUrl, name, caps, p.name, p.id)
        val selected = if (role == ModelRole.Main) config.copy(capabilities = config.capabilities.copy(toolCalls = true)) else config
        var roles = _roles.value.with(role, RoleChoice.Model(config.id)) ?: return false
        roles.main?.let { roles = roles.copy(mainFallbacks = roles.mainFallbacks - it) }
        val configs = if (existing == null) _configs.value + selected else _configs.value.map {
            if (it.id == existing.id) selected else it
        }
        // 没有用途、组或对话引用的配置删掉(和 [prune] 一样)
        val used = roles.referenced + _groups.value.flatMap { g -> g.members.map { it.configId } } + externalConfigReferences()
        return saveRoleSelection(_providers.value, configs.filter { it.id in used }, roles)
    }

    companion object {
        internal const val CONFIG_FILE = "providers.json"
        internal const val KEY_FILE = "provider-keys.json"
        internal const val CHATGPT_HOST_FILE = "chatgpt-host.json"

        /** The legacy context default the presets used; after a preset changes its default, configs still holding this value are updated by [repairStaleContextDefaults]. */
        internal const val LEGACY_DEFAULT_CONTEXT_TOKENS = 64_000

        /** The `use` of each role in `uses`: a concrete model (with `config` stored separately), a model group (with `group` stored separately), same as main, or none. Legacy files only contain `roles`. */
        internal const val USE_MODEL = "model"
        internal const val USE_GROUP = "group"
        internal const val USE_MAIN = "main"
        internal const val USE_NONE = "none"

        // Every client that talks to a provider is built through NetProxy: it honors Settings › Network proxy, while localhost and LAN addresses (local models) connect directly
        internal val http by lazy { NetProxy.shared.client(OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS)) }

        /** Connection pool shared with chat models; other requests to the same provider (such as speech transcription) use it too. */
        val modelHttp: OkHttpClient get() = http

        /** Used for model listing and account sign-in token exchange: a shorter read timeout, likewise routed through the network proxy. */
        internal val shortHttp by lazy { NetProxy.shared.client(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS)) }

        internal val catalog by lazy { ModelCatalog(shortHttp) }

        internal val defaultChatGptAuth by lazy { ChatGptAuth(shortHttp) }
        internal val defaultOpenRouterAuth by lazy { OpenRouterAuth(shortHttp) }
        internal val defaultXaiAuth by lazy { XaiAuth(shortHttp) }
        internal val defaultOpenCodeAuth by lazy { OpenCodeAuth(shortHttp) }

        /** The ChatGPT provider's name (a brand name, not translated with the UI language). */
        const val CHATGPT_NAME = "ChatGPT"
        /** The name of the provider signed in with an xAI account (a brand name, not translated with the UI language). It is not the same as the "xAI" preset that takes a Key. */
        const val XAI_NAME = "xAI / Grok"
        /** The name of the provider signed in with an OpenCode Go account; distinct from the "OpenCode Go" preset that takes a Key. */
        const val OPENCODE_NAME = "OpenCode Go"
        /** The provider authorized with an OpenRouter account: the name matches the preset, and [Provider.signIn] is set to this value. */
        const val OPENROUTER_NAME = "OpenRouter"
        const val OPENROUTER_SIGN_IN = "openrouter"
        /** How this provider's sign-in is referred to in error messages. */
        internal const val XAI_ACCOUNT = "xAI"
        internal const val OPENCODE_ACCOUNT = "OpenCode Go"

        /**
         * Trial call: the temporary config id, the system prompt, the sentence, the trial tool, and the reply limit.
         * The limit leaves room for one tool call plus the thinking of models that must think first (Messages and generateContent count thinking toward the limit).
         */
        internal const val PROBE_ID = "probe"
        internal const val PROBE_SYSTEM = "This is a connection test. Call the get_time tool now. Do not answer with text."
        internal const val PROBE_TEXT = "What time is it? Use the get_time tool."
        internal val PROBE_TOOL = ToolSpec("get_time", "Returns the current time.", """{"type":"object","properties":{}}""")
        internal const val PROBE_MAX_TOKENS = 1024

        /**
         * The list-models endpoint: OpenAI-compatible, Responses, ChatGPT, xAI and OpenCode Go all use `GET {base}/models`
         * (Bearer; for subscription sign-in [apiKey] is the access token). [headers] are sent with every request (see [ClientHeaders]).
         */
        suspend fun listAvailable(kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, headers: Map<String, String>): ModelListResult =
            when (kind) {
                ProviderConfig.Kind.OpenAiCompat, ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.ChatGpt,
                ProviderConfig.Kind.Xai, ProviderConfig.Kind.OpenCodeGo ->
                    catalog.openAiCompat(baseUrl, apiKey, headers)
                ProviderConfig.Kind.Anthropic -> catalog.anthropic(baseUrl, apiKey.orEmpty(), headers)
                ProviderConfig.Kind.Gemini -> catalog.gemini(baseUrl, apiKey.orEmpty(), headers)
            }

        /**
         * Which adapter to use for calling this model and which Base URL to hand it. Both model construction ([buildModel]) and reasoning levels ([ReasoningOptions]) follow it.
         * xAI subscription sign-in always uses this provider's endpoint and does not follow a different protocol written in the model list, so the subscription is never redirected to another endpoint.
         * OpenCode Go is the opposite: its list assigns each model a protocol and base URL, which are followed, falling back to Chat Completions when a model names none.
         */
        internal fun adapterFor(cfg: ProviderConfig, endpoint: ModelEndpoint?): Pair<ProviderConfig.Kind, String> = when {
            cfg.kind == ProviderConfig.Kind.Xai -> ProviderConfig.Kind.Xai to cfg.baseUrl
            cfg.kind == ProviderConfig.Kind.OpenCodeGo ->
                endpoint?.let { ProviderConfig.Kind.of(it.protocol) to it.adapterBaseUrl }
                    ?: (ProviderConfig.Kind.OpenAiCompat to cfg.baseUrl)
            endpoint == null -> cfg.kind to cfg.baseUrl
            else -> ProviderConfig.Kind.of(endpoint.protocol) to endpoint.adapterBaseUrl
        }

        /** The production instance (created by the app): the config lives under files/settings, Keys are encrypted with the Android keystore, and usage is recorded to [usage]. */
        fun create(
            context: Context, usage: UsageSink, userAgent: String, dailyUsage: (String) -> Long = { 0L },
            proactiveUsage: () -> Long = { 0L }, proactiveLimit: () -> Long = { ProactiveBudget.DEFAULT_DAILY_LIMIT },
            proactiveCalls: () -> Long = { 0L }, externalConfigReferences: () -> Set<String> = { emptySet() },
        ): ProviderStore =
            ProviderStore(File(context.applicationContext.filesDir, "settings"), KeystoreKeyCipher(), usage,
                dailyUsage = dailyUsage, userAgent = userAgent, proactiveUsage = proactiveUsage, proactiveLimit = proactiveLimit,
                proactiveCalls = proactiveCalls, externalConfigReferences = externalConfigReferences)

        /** The instance shared within the app (provided through [Owner]). */
        fun get(context: Context): ProviderStore = (context.applicationContext as? Owner)?.providers
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement ProviderStore.Owner to give the model settings")
    }
}
