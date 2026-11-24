package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.chatgpt.signInWithChatGpt
import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.net.LocalHosts
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderConnection
import com.wangzan101.mika.settings.ProviderPreset
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.RoleChoices
import com.wangzan101.mika.settings.SignInTarget
import com.wangzan101.mika.settings.configFor
import com.wangzan101.mika.settings.markVerified
import com.wangzan101.mika.settings.openRouterAccount
import com.wangzan101.mika.settings.rememberModels
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SystemCapability
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot

/** Progress of listing models ("Test and save", or refreshing one provider). */
sealed interface ListState {
    data object Idle : ListState
    data object Running : ListState
    data class Failed(val message: String) : ListState
}

/** An account signed in with ChatGPT: [email] comes from the verified ID token (may be empty); [signedIn] is false after signing out. */
data class ChatGptAccount(val email: String, val signedIn: Boolean)

/**
 * The providers other than ChatGPT that sign in with an account: xAI / Grok uses the device code (confirming a short code in the browser), OpenRouter uses the official PKCE authorization (a local callback exchanges the Key).
 * ChatGPT has its own sign-in flow (see [ModelSetupViewModel.signInWithChatGpt]) and appears side by side with these in the UI.
 */
enum class AccountProvider(val target: SignInTarget) {
    Xai(SignInTarget.Xai), OpenRouter(SignInTarget.OpenRouter);

    companion object {
        fun of(target: SignInTarget): AccountProvider? = entries.firstOrNull { it.target == target }
    }
}

/** An account of an account-signed-in provider. [label] is the human-readable account name (email etc., may be empty); [signedIn] is false after signing out. Null when this provider is not connected. */
data class SubscriptionAccount(val label: String, val signedIn: Boolean)

/**
 * An account sign-in in progress or that just failed. [provider] is null when nothing is signing in and there is no failure.
 * During device-code sign-in [userCode] is the short code shown to the user, [openUrl] is the verification page with a verified host, and [expiresAtMs] is when the short code expires.
 */
data class AccountSignInUi(
    val provider: AccountProvider? = null,
    val running: Boolean = false,
    val userCode: String = "",
    val openUrl: String = "",
    val expiresAtMs: Long = 0,
    val failure: String? = null,
)

/**
 * System capabilities account sign-in needs: open the authorization page in the browser, return to Claire after sign-in, and the web page shown in the browser once the local callback arrives.
 * The UI provides them (needs a Context); when tests do not provide them, sign-in is impossible.
 */
interface SignInBrowser {
    fun open(url: String)
    fun returnToApp()
    /** The web page returned to the browser after the callback is handled: [failure] null means sign-in succeeded, otherwise it is the failure reason (the same sentence as in the UI). */
    fun page(failure: String?): String
}

/**
 * The "add provider" form. When [noList] is true the last test listed no models, and [model] can be entered to continue configuring.
 * When [manual] is true the manual model name field is shown as well. [name] is the name of a custom provider (only filled in for custom ones, optional, see [providerName]).
 */
data class AddForm(
    val preset: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val apiKey: String,
    /**
     * Notice from the most recent Key edit: zero-width characters were removed, or characters that cannot go into a request header (including line breaks) remain.
     * With [ApiKeyText.Notice.IllegalCharacters] nothing can be tested or saved.
     */
    val keyNotice: ApiKeyText.Notice = ApiKeyText.Notice.None,
    val test: ListState = ListState.Idle,
    val model: String = "",
    val noList: Boolean = false,
    val name: String = "",
    val manual: Boolean = false,
) {
    /** Shows the "model name" field: no models were listed, or the user wants to type it. */
    val showModel: Boolean get() = noList || manual

    /** The model name entered; not counted when that field is not shown. */
    val typedModel: String get() = if (showModel) model.trim() else ""

    val isCloudflare: Boolean get() = preset == "Cloudflare Workers AI"

    val accountId: String
        get() = if (baseUrl.contains("/accounts/") && !baseUrl.contains("{account_id}")) {
            baseUrl.substringAfter("/accounts/").substringBefore("/").trim()
        } else ""

    val isCustom: Boolean get() = preset == ProviderPresets.custom.label

    /** A service running on the user's own machine (see [ProviderPreset.selfHosted]): the URL is pre-filled with the local default port and must be editable to point at that machine. */
    val isSelfHosted: Boolean get() = presetFor(preset).selfHosted

    /** The URL must be entered or edited by the user: custom, self-hosted, or presets whose URL contains parameters. Cloudflare shows an Account ID field instead. */
    val urlEditable: Boolean get() = (isCustom || isSelfHosted || presetFor(preset).urlEditable) && !isCloudflare

    val urlProblem: String?
        get() = when {
            baseUrl.isBlank() -> tr(R.string.model_need_url)
            !baseUrl.trim().startsWith("http://") && !baseUrl.trim().startsWith("https://") -> tr(R.string.model_url_scheme)
            baseUrl.contains("{account_id}") -> tr(R.string.model_url_account_id)
            else -> null
        }

    /** A plain http URL that is not local or on the LAN: give a warning without blocking (see [LocalHosts]). */
    val unencryptedWarning: String?
        get() = if (urlProblem == null && LocalHosts.unencryptedRemote(baseUrl)) tr(R.string.model_url_unencrypted) else null

    /** Self-hosted services and custom OpenAI-compatible endpoints (mostly local services) may omit the Key; everything else requires one. */
    val keyRequired: Boolean get() = !isSelfHosted && !(isCustom && kind == ProviderConfig.Kind.OpenAiCompat)

    /** The Key still has characters that cannot go into a request header: neither save action may send requests or store the Key. */
    val keyRejected: Boolean get() = keyNotice == ApiKeyText.Notice.IllegalCharacters

    val canTest: Boolean get() =
        urlProblem == null && !keyRejected && (!keyRequired || apiKey.isNotBlank()) && test != ListState.Running && (!noList || model.isNotBlank())

    /** Can be saved without testing: the URL is well-formed, the Key is present when required and header-safe. Unlike [canTest] it ignores test progress and does not require a model name. */
    val canSaveUntested: Boolean get() =
        urlProblem == null && !keyRejected && (!keyRequired || apiKey.isNotBlank()) && test != ListState.Running

    /** The custom entry is some provider's official URL (host name matches, see [ProviderPresets.forHost]): suggest switching to that preset. */
    val suggestedPreset: ProviderPreset? get() = if (isCustom && urlProblem == null) ProviderPresets.forHost(baseUrl) else null

    /** The URL is exactly [suggestedPreset]'s URL but the chosen protocol differs from its own: this cannot work. */
    val presetKindMismatch: Boolean
        get() = suggestedPreset?.let { s -> ProviderPresets.forBaseUrl(baseUrl.trim()) == s && kind != s.kind } == true

    /** Which protocol kind the model name example should follow: the preset when the URL matches one, otherwise the chosen protocol kind. */
    val exampleKind: ProviderConfig.Kind get() = suggestedPreset?.kind ?: kind

    val defaultName: String
        get() = when {
            isCloudflare && accountId.isNotBlank() -> {
                val shortId = if (accountId.length >= 8) "${accountId.take(4)}…${accountId.takeLast(4)}" else accountId
                "$preset ($shortId)"
            }
            else -> ProviderConnection.nameForUrl(baseUrl)
        }

    /**
     * Name used when saving the provider: presets use the preset name, custom ones the entered name or [defaultName] when empty.
     * Providers are grouped by protocol kind, URL and name, so the same URL under a different name counts as two providers (for example two Keys of the same relay).
     */
    val providerName: String
        get() = when {
            isCustom -> name.trim().ifEmpty { defaultName }
            isCloudflare -> name.trim().ifEmpty { defaultName }
            else -> preset
        }

    /** Header name this provider uses for the session id: the chosen preset's when it declares one; custom entries also use the preset whose URL matches. */
    val sessionHeader: String? get() = presetFor(preset).sessionHeader ?: ProviderPresets.forBaseUrl(baseUrl.trim())?.sessionHeader

    /** Tokens found in this provider's own model names (see [ProviderPreset.familyTokens]): the chosen preset's when given, otherwise those of the preset whose URL matches. */
    val familyTokens: List<String> get() = presetFor(preset).familyTokens.ifEmpty { ModelOptions.familyOf(baseUrl) }

    companion object {
        fun of(p: ProviderPreset) = AddForm(p.label, p.kind, p.baseUrl, "")
        fun presetFor(label: String): ProviderPreset = ProviderPresets.all.firstOrNull { it.label == label } ?: ProviderPresets.custom
    }
}

/**
 * All state shared by model setup (onboarding) and Settings › Models: connected providers, the add-provider form and the three roles.
 * [providers], [configs] and [roles] are snapshots of [ProviderStore], [keyed] holds the ids of providers with a stored Key;
 * [listing] is the list-refresh progress per provider (keyed by provider id).
 */
data class ModelSetupUi(
    val form: AddForm,
    val formOpen: Boolean = false,
    val providers: List<Provider> = emptyList(),
    val configs: List<ProviderConfig> = emptyList(),
    val roles: RoleChoices = RoleChoices(),
    val groups: List<ModelGroup> = emptyList(),
    val keyed: Set<String> = emptySet(),
    val providerKeyMap: Map<String, List<String>> = emptyMap(),
    val listing: Map<String, ListState> = emptyMap(),
    /** The ChatGPT provider's account; null when that provider does not exist. */
    val chatGptAccount: ChatGptAccount? = null,
    /** Progress of the ChatGPT sign-in. */
    val chatGptSignIn: ListState = ListState.Idle,
    /** Shows "using your ChatGPT plan" once after the first successful sign-in; dismissed by tapping "Got it". */
    val chatGptWelcome: Boolean = false,
    /** ChatGPT did not confirm the revocation when signing out (the local token was already cleared). */
    val chatGptRevokeUnconfirmed: Boolean = false,
    /** The connected Grok subscription; null when that provider does not exist. */
    val xaiAccount: SubscriptionAccount? = null,
    /** The provider connected through OpenRouter account authorization; null when there is none. */
    val openRouterAccount: SubscriptionAccount? = null,
    /** Sign-in progress for xAI and OpenRouter. */
    val accountSignIn: AccountSignInUi = AccountSignInUi(),
    /** Id of the provider that just signed in with an account: the UI opens its details page (see the account and models, set the main model) and clears it once opened. */
    val signedInProvider: String? = null,
    /**
     * Note shown when a provider is saved: there is no main model yet, the suggested one failed its test call (see [ModelSetupViewModel.testAndSave]),
     * or this provider was saved skipping the test (see [ModelSetupViewModel.saveWithoutTest]). Cleared when a main model is chosen.
     */
    val probeNote: String? = null,
    /** Id of the provider just saved whose suggested model failed its test: the UI offers an entry point to "choose main model"; cleared once one is chosen. */
    val pickToProbe: String? = null,
    /**
     * Progress of saving a model from the picker or the "Use as" sheet, keyed by provider id. Setting the main model test-calls first (see [ModelSetupViewModel.pick]),
     * which shows as [ListState.Running]; a failure is [ListState.Failed].
     */
    val manualPick: Map<String, ListState> = emptyMap(),
    /** Test progress on the provider page, keyed by provider id and model name. */
    val retest: Map<Pair<String, String>, ListState> = emptyMap(),
    /** The current speech-to-text choice and this phone's built-in recognition capability (used by the role card and the picker). */
    val speech: SpeechChoice = SpeechChoice.Auto,
    val systemSpeech: SystemCapability = SystemCapability(available = false, onDevice = false),
    /** The voice catalog after migration; null means the old settings still apply. */
    val sttCatalog: VoiceCatalogSnapshot? = null,
    /** Id of the provider currently test-transcribing a short recording; null when none. */
    val speechTesting: String? = null,
) {
    /** The form is always expanded while there are no providers. */
    val showForm: Boolean get() = formOpen || providers.isEmpty()

    /** "Next" is enabled once the main model is chosen. */
    val canNext: Boolean get() = roles.hasMain

    /** The config this role actually uses (the main model's for "use main model"; the first enabled member for a group). */
    fun configFor(role: ModelRole): ProviderConfig? =
        (roles.resolveConfig(role) ?: groups.firstOrNull { it.id == roles.resolveGroup(role) }?.enabledMembers?.firstOrNull()?.configId)
            ?.let { id -> configs.firstOrNull { it.id == id } }

    /** The config for a config id; group members and the picker both use it. */
    fun configOf(id: String): ProviderConfig? = configs.firstOrNull { it.id == id }

    /** Whether the group can serve this role (the main model requires every member to support tools; vision requires at least one that can see images). */
    fun usable(group: ModelGroup, role: ModelRole): Boolean =
        group.usableFor(role) { id -> configOf(id)?.capabilities }

    fun providerOf(cfg: ProviderConfig): Provider? = providers.firstOrNull { it.id == cfg.providerId }

    /** Models selectable for chat roles. The provider details also show TTS models. */
    fun options(p: Provider): List<ModelOption> = ModelOptions.ofProvider(p, configs)

    /** All models of one provider, with those in use first and the rest keeping their original order. */
    fun sortedOptions(p: Provider): List<ModelOption> = ModelOptions.ofProvider(p, configs, includeTts = true)
        .sortedBy { if (rolesOf(p.id, it.id).isNotEmpty()) 0 else 1 }

    /** Roles that actually use this model (including those following along via "use main model"), in main, vision, light order. */
    fun rolesOf(providerId: String, model: String): List<ModelRole> =
        ModelRole.entries.filter { role -> configFor(role)?.let { it.providerId == providerId && it.model == model } == true }

    /** Roles that actually use this provider. */
    fun rolesOf(p: Provider): List<ModelRole> = ModelRole.entries.filter { configFor(it)?.providerId == p.id }

    /** Chat models in use by this provider, including the fallbacks of each role. */
    fun usedChatModels(p: Provider): List<String> =
        roles.referenced.mapNotNull { id -> configs.firstOrNull { it.id == id && it.providerId == p.id }?.model }
            .distinct().filter { it.isNotBlank() }

    /** Manually entered models that are in use, have no model list and are unverified. */
    fun unverified(p: Provider): List<String> = usedChatModels(p).filter(p::untested)

    /**
     * This provider was saved skipping the test (see [ModelSetupViewModel.saveWithoutTest]): models were never listed and none has passed a test.
     * The provider page thus says "not tested yet" rather than a blanket "no model list offered" — the latter would be wrong for it.
     * Once models have been listed or one has passed a test, this no longer applies (see [ProviderStore.rememberModels], [ProviderStore.markVerified]).
     */
    fun untested(p: Provider): Boolean = p.savedUntested

    /** This account-signed-in provider has been signed out (ChatGPT and xAI tokens cleared, OpenRouter Key deleted). */
    fun signedOut(p: Provider): Boolean = when {
        p.kind == ProviderConfig.Kind.ChatGpt -> chatGptAccount?.signedIn != true
        p.kind == ProviderConfig.Kind.Xai -> xaiAccount?.signedIn != true
        p.signIn != null -> p.id !in keyed
        else -> false
    }

    /** Whether this role explicitly selected this model ("use main model" does not count). */
    fun picked(role: ModelRole, providerId: String, model: String): Boolean {
        val id = (roles[role] as? RoleChoice.Model)?.configId ?: return false
        return configs.firstOrNull { it.id == id }?.let { it.providerId == providerId && it.model == model } == true
    }
}
