package com.wangzan101.mika.settings

import com.wangzan101.mika.core.agent.ContextLimits
import com.wangzan101.mika.core.model.chatgpt.ChatGptSession
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ClientHeaders
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelProtocol
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.OpenAiServiceTier
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A model wiring: which provider it belongs to ([providerId]), the model name and its capabilities. The API type, address and name are copied from the owning provider (used when building the model);
 * the Key is stored on the provider. This is an internal storage layer: a config is created along with the user's choice of "which model for which role" or when a model is added to a group; it is deleted when nobody uses it and it is in no group.
 */
data class ProviderConfig(
    val id: String,
    val kind: Kind,
    val baseUrl: String,
    val model: String,
    val capabilities: Capabilities,
    /** The name shown in the UI. */
    val name: String = "",
    /** The id of the owning [Provider]; when empty, [ProviderStore.save] assigns it to the matching provider by API type, address and name. */
    val providerId: String = "",
) {
    /**
     * API type. [OpenAiResponses] is used when the model list specifies the Responses protocol for a model, by the "OpenAI" preset, and when it is selected while adding a custom provider;
     * [ChatGpt] is the one accessed by signing in with a ChatGPT account and spending plan quota: the credential is the sign-in (see [ChatGptSession]), not an API Key.
     * [Xai] likewise uses account sign-in (device code); the credential is the sign-in rather than an API Key, and it calls xAI's own Responses endpoint.
     */
    enum class Kind {
        OpenAiCompat, Anthropic, Gemini, OpenAiResponses, ChatGpt, Xai;

        /**
         * Which protocol this API type uses for requests. It is used to filter out models that the endpoint explicitly reports as unsupported, see the trial
         * call in [com.wangzan101.mika.settings.ui.models.ModelSetupViewModel]. The two account sign-in providers (ChatGpt, Xai) both use Responses; they do not go through that form's trial
         * call (it was verified at sign-in), so this only needs to be correct and does not affect who is filtered out.
         */
        val protocol: ModelProtocol
            get() = when (this) {
                OpenAiCompat -> ModelProtocol.ChatCompletions
                Anthropic -> ModelProtocol.Messages
                Gemini -> ModelProtocol.GenerateContent
                OpenAiResponses, ChatGpt, Xai -> ModelProtocol.Responses
            }

        companion object {
            /** Which API type's adapter to use for a protocol that a model specifies individually. */
            fun of(protocol: ModelProtocol): Kind = when (protocol) {
                ModelProtocol.ChatCompletions -> OpenAiCompat
                ModelProtocol.Messages -> Anthropic
                ModelProtocol.GenerateContent -> Gemini
                ModelProtocol.Responses -> OpenAiResponses
            }

            /** API types selectable when adding a custom provider (the ones that take an address and an API Key). Responses comes last, matching the order in the README. */
            val withApiKey: List<Kind> = listOf(OpenAiCompat, Anthropic, Gemini, OpenAiResponses)
        }
    }
}

/**
 * A connected provider: name, API type, address; the Key is stored separately, encrypted.
 * [models] is the most recently listed set of models (recorded during "test and save" or a refresh, and stored in the same file as the config);
 * null means it has never been listed, while an empty list means it was listed but the service offers no listing (or has none).
 * [sessionHeader] is the header name it requires for the conversation session ID (from the preset, see [ProviderPreset.sessionHeader]); null when not needed.
 * [verifiedModels] are the model names that received a reply in a trial call on this provider (see [ProviderStore.probe]): for providers without a listing the model name is typed by hand,
 * and names not here have not been verified (nothing saved by older versions is here, since providers without a listing were not trial-called back then).
 * [typedModels] are model names the user typed in and added after a successful trial call (absent from the listing, or the provider cannot list models): they survive a refresh and are marked "typed" when selecting a model.
 * [savedUntested] means the user saved this provider skipping the test (see [ProviderStore.connect]): no models were ever listed and no trial call was made,
 * so [models] is an empty list and [verifiedModels] is empty, but those statements about "this provider offers no listing" do not hold for it.
 * It is cleared once models have been listed successfully or a trial call has passed (see [ProviderStore.rememberModels], [ProviderStore.markVerified]).
 */
data class Provider(
    val id: String,
    val name: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val models: List<ListedModel>? = null,
    val sessionHeader: String? = null,
    val verifiedModels: Set<String> = emptySet(),
    val typedModels: Set<String> = emptySet(),
    /**
     * How the Key was obtained: a marker for providers whose key is exchanged for via account authorization (currently only [ProviderStore.OPENROUTER_SIGN_IN]), null for keys entered by the user.
     * A marked provider's details page looks like an account sign-in (sign out, delete the key on the other party's page) rather than "change Key".
     */
    val signIn: String? = null,
    val savedUntested: Boolean = false,
    /** Kept by model name, so it can still be found after switching roles. */
    val contextSettings: Map<String, ModelContextSettings> = emptyMap(),
    /** Whether the user says a given model can see images (by model name): takes precedence over both the listing and the preset defaults, see [ModelOptions.seesImages]. */
    val visionOverrides: Map<String, Boolean> = emptyMap(),
    /** This provider's daily budget of reported tokens summed over all models and roles; null means unlimited. */
    val dailyTokenBudget: Long? = null,
    /** Speed tiers for direct OpenAI and ChatGPT sign-in, stored by model name. */
    val serviceTiers: Map<String, OpenAiServiceTier> = emptyMap(),
    /** Model names the user marked as TTS, independent of chat roles and the provider's model listing. */
    val ttsModels: Set<String> = emptySet(),
) {
    val key: String get() = ProviderConnection.keyOf(kind, baseUrl, name)

    fun supportsServiceTier(model: String, endpoint: ModelEndpoint? = models?.firstOrNull { it.id == model }?.endpoint): Boolean {
        if (kind != ProviderConfig.Kind.OpenAiResponses && kind != ProviderConfig.Kind.ChatGpt) return false
        if (endpoint != null && endpoint.protocol != ModelProtocol.Responses) return false
        val url = (endpoint?.adapterBaseUrl ?: baseUrl).toHttpUrlOrNull()
        return url?.isHttps == true && url.host == "api.openai.com"
    }

    internal fun requestedServiceTier(model: String, endpoint: ModelEndpoint?): OpenAiServiceTier? =
        serviceTiers[model]?.takeIf { supportsServiceTier(model, endpoint) }

    /**
     * [model] has not passed a test on this provider: the provider has no usable listing (it offers none, or none was listed when it was saved skipping the test), the model name was typed by hand, and it is not in
     * [verifiedModels]. The account sign-in providers have fixed model names, so they do not count. The provider page offers "retest" for models in use this way.
     */
    fun untested(model: String): Boolean =
        kind != ProviderConfig.Kind.ChatGpt && kind != ProviderConfig.Kind.Xai && models?.isEmpty() == true && model !in verifiedModels
}

/** OkHttp converts timeouts to Int milliseconds, so the value in seconds must still fit after conversion. */
internal val MODEL_READ_TIMEOUT_SECONDS = 1..(Int.MAX_VALUE / 1_000)

/** Maximum length of each role's fallback chain. Supports a waterfall cascade of up to 8 levels. */
internal const val MAX_MAIN_FALLBACKS = 8
internal const val MAX_FALLBACKS = 8

/**
 * Temporary: until it is verified on a real device that tasks can still be completed after folding, fold the history for every model once every 16 rounds (after exceeding keepRecent);
 * once verified, turn it into a setting or a per-model value and then delete this constant.
 */
internal const val TEMP_COMPACTION_EVERY_ROUNDS = 16

/** An empty window falls back to the selected model's config, an empty trigger point is computed automatically by the core, and an empty read timeout uses the global default (120 seconds). */
data class ModelContextSettings(val windowTokens: Int? = null, val triggerTokens: Int? = null, val readTimeoutSeconds: Int? = null) {
    fun limits(modelWindow: Int): ContextLimits = ContextLimits.of(windowTokens ?: modelWindow, triggerTokens)

    fun validFor(modelWindow: Int): Boolean = (windowTokens == null || windowTokens > 0) &&
        (triggerTokens == null || (triggerTokens > 0 && triggerTokens == limits(modelWindow).trigger)) &&
        (readTimeoutSeconds == null || readTimeoutSeconds in MODEL_READ_TIMEOUT_SECONDS)
}

/** What a role selected: a concrete model, a model group, same as main, or off (only vision can be off). */
sealed interface RoleChoice {
    /** Use the model config [configId]. */
    data class Model(val configId: String) : RoleChoice
    /** Use the model group [groupId] (routed by group members and policy). */
    data class Group(val groupId: String) : RoleChoice
    data object SameAsMain : RoleChoice
    data object Off : RoleChoice
}

/**
 * What each of the three roles selected; all of it is stated explicitly by the user, with no default assignment inferred from capabilities.
 * [main] is the main model's config id and [mainGroup] the main model group's id, with at most one non-null; both null means nothing is selected yet.
 * Vision is off by default and light defaults to the main model. Every role (Main, Vision, Light) has its own fallback chain (waterfall chain).
 */
data class RoleChoices(
    val main: String? = null,
    val mainGroup: String? = null,
    val vision: RoleChoice = RoleChoice.Off,
    val light: RoleChoice = RoleChoice.SameAsMain,
    /** Fallback config ids for the main model (item 3), in takeover order; whether to switch when the main model fails is decided by Failover's decision table. */
    val mainFallbacks: List<String> = emptyList(),
    /** Fallback config ids for the vision model, in takeover order. */
    val visionFallbacks: List<String> = emptyList(),
    /** Fallback config ids for the light model, in takeover order. */
    val lightFallbacks: List<String> = emptyList(),
    /** Stored per role, so the same model can use different levels in different roles; "same as main" follows the main model's level too. */
    val reasoningEfforts: Map<ModelRole, String> = emptyMap(),
) {
    /** Get the fallback config list for the given role */
    fun fallbacks(role: ModelRole): List<String> = when (role) {
        ModelRole.Main -> mainFallbacks
        ModelRole.Vision -> visionFallbacks
        ModelRole.Light -> lightFallbacks
    }

    /** Update the fallback config list for the given role */
    fun withFallbacks(role: ModelRole, list: List<String>): RoleChoices = when (role) {
        ModelRole.Main -> copy(mainFallbacks = list)
        ModelRole.Vision -> copy(visionFallbacks = list)
        ModelRole.Light -> copy(lightFallbacks = list)
    }

    /** Whether a main model is selected: either a concrete model or a model group counts. */
    val hasMain: Boolean get() = main != null || mainGroup != null

    /** The selection for this role; null when no main model is selected. */
    operator fun get(role: ModelRole): RoleChoice? = when (role) {
        ModelRole.Main -> mainGroup?.let(RoleChoice::Group) ?: main?.let(RoleChoice::Model)
        ModelRole.Vision -> vision
        ModelRole.Light -> light
    }

    /** The config id this role actually uses: "same as main" resolves to the main model's; null for a group, off, or when no main model is selected yet. */
    fun resolveConfig(role: ModelRole): String? = when (val c = get(role)) {
        is RoleChoice.Model -> c.configId
        RoleChoice.SameAsMain -> resolveConfig(ModelRole.Main)
        is RoleChoice.Group, RoleChoice.Off, null -> null
    }

    /** Whether two roles resolve to the same config; they do not count as the same when either role is unconfigured. */
    fun sameModel(first: ModelRole, second: ModelRole): Boolean {
        val a = resolve(first) ?: return false
        return a == resolve(second)
    }

    /** The group id this role actually uses: "same as main" resolves to the main model's; null for a concrete model, off, or when no main model is selected yet. */
    fun resolveGroup(role: ModelRole): String? = when (val c = get(role)) {
        is RoleChoice.Group -> c.groupId
        RoleChoice.SameAsMain -> resolveGroup(ModelRole.Main)
        is RoleChoice.Model, RoleChoice.Off, null -> null
    }

    /** The config ids in use by some role; all other configs are unused and get deleted. */
    val referenced: Set<String> get() = ModelRole.entries.mapNotNull(::resolve).toSet() + mainFallbacks + visionFallbacks + lightFallbacks

    /** For compatibility with older callers: the config id this role actually uses. */
    fun resolve(role: ModelRole): String? = resolveConfig(role)

    /** Change one role; returns null for selections the role does not accept (the main model can only be a concrete model or a group, and light cannot be off). */
    fun with(role: ModelRole, choice: RoleChoice): RoleChoices? = (when (role) {
        ModelRole.Main -> when (choice) {
            is RoleChoice.Model -> copy(main = choice.configId, mainGroup = null)
            is RoleChoice.Group -> copy(main = null, mainGroup = choice.groupId)
            RoleChoice.SameAsMain, RoleChoice.Off -> null
        }
        ModelRole.Vision -> copy(vision = choice)
        ModelRole.Light -> if (choice == RoleChoice.Off) null else copy(light = choice)
    })?.let { next ->
        if (get(role) == choice) next else next.copy(reasoningEfforts = reasoningEfforts - role)
    }

    fun reasoningEffort(role: ModelRole): String? = when (get(role)) {
        is RoleChoice.Model, is RoleChoice.Group -> reasoningEfforts[role]
        RoleChoice.SameAsMain -> reasoningEfforts[ModelRole.Main]
        else -> null
    }

    /**
     * Selections pointing outside [validConfigs] / [validGroups] fall back to the defaults: the main model becomes unselected, vision is off, and light uses the main model.
     * Empty groups, or groups with no usable members, are removed from [validGroups] by the caller.
     */
    fun keepOnly(validConfigs: Set<String>, validGroups: Set<String> = emptySet()): RoleChoices {
        fun RoleChoice.gone() = when (this) {
            is RoleChoice.Model -> configId !in validConfigs
            is RoleChoice.Group -> groupId !in validGroups
            else -> false
        }
        val mainModel = main?.takeIf { it in validConfigs }
        val mainGroupId = mainGroup?.takeIf { it in validGroups }
        return RoleChoices(
            main = mainModel,
            mainGroup = if (mainModel == null) mainGroupId else null,
            vision = if (vision.gone()) RoleChoice.Off else vision,
            light = if (light.gone()) RoleChoice.SameAsMain else light,
            mainFallbacks = mainFallbacks.filter { it in validConfigs },
            visionFallbacks = visionFallbacks.filter { it in validConfigs },
            lightFallbacks = lightFallbacks.filter { it in validConfigs },
            reasoningEfforts = reasoningEfforts.filterKeys { role ->
                when (val c = get(role)) {
                    is RoleChoice.Model -> c.configId in validConfigs
                    is RoleChoice.Group -> c.groupId in validGroups
                    else -> false
                }
            },
        )
    }
}

/**
 * Extras supplied when building a model client: [headers] are sent with every request (client identifier, session ID, see [ClientHeaders]);
 * [maxOutputTokens] limits the reply length (used for connection tests), null means the adapter default; [reasoningEffort] is the reasoning intensity selected for this role;
 * [thinkingTypes] are the thinking modes the model listing says this model accepts (see [ListedModel.thinkingTypes]), null when not stated;
 * [readTimeoutSeconds] is this config's own read timeout, raised for slow local inference (#295), null uses the global default (120 seconds).
 */
data class ClientSetup(
    val headers: Map<String, String> = emptyMap(),
    val maxOutputTokens: Int? = null,
    val reasoningEffort: String? = null,
    val thinkingTypes: List<String>? = null,
    val readTimeoutSeconds: Int? = null,
    val serviceTier: OpenAiServiceTier? = null,
)

/** The result of the "test and save" trial call (see [ProviderStore.probe]). */
sealed interface ProbeResult {
    /** The model called the trial tool: the endpoint works and the model does call tools. */
    data object ToolCalled : ProbeResult
    /** The endpoint works but the model only replied with text and called no tool: Claire works through tools, so this model cannot serve as the main model. */
    data object NoToolCall : ProbeResult
    /** The request failed; [error] carries the server's error. */
    data class Failed(val error: ModelError) : ProbeResult
}
