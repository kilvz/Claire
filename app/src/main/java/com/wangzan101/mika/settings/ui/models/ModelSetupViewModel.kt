package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.settings.ChatGptSignIns
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.SignInTarget
import com.wangzan101.mika.settings.accountSession
import com.wangzan101.mika.settings.chatGptProvider
import com.wangzan101.mika.settings.chatGptSession
import com.wangzan101.mika.settings.hasKey
import com.wangzan101.mika.settings.openRouterAccount
import com.wangzan101.mika.settings.providerKeys
import com.wangzan101.mika.settings.xaiProvider
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechSettings
import com.wangzan101.mika.voice.SystemCapability
import com.wangzan101.mika.voice.model.SttSelectionController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State and actions for model setup. When adding a provider, "Test and save" stores it once models can be listed; if there is no main model yet, the suggested one is test-called and only becomes the main model when it passes.
 * Changing the provider, URL or Key invalidates a test in progress. Roles are chosen from the models remembered across all connected providers; the main model must pass a test call first
 * (ones that already passed are not tested again), while vision, light and fallbacks apply on selection; vision can also be "use main model"/"off", light can be "use main model".
 * Shared by first-time onboarding and Settings › Models.
 */
class ModelSetupViewModel(
    internal val store: ProviderStore,
    internal val scope: CoroutineScope,
    internal val io: CoroutineDispatcher = Dispatchers.IO,
    internal val browser: SignInBrowser? = null,
    /** Where sign-in runs: the UI passes the app-wide one (unaffected by going to the background or a UI rebuild, running a foreground service meanwhile); tests just use [scope]. */
    internal val signIns: ChatGptSignIns = ChatGptSignIns(scope),
    /** The speech-to-text choice; tests and the onboarding flow do not pass it, so the card does not appear. */
    internal val speech: SpeechSettings? = null,
    internal val systemSpeech: () -> SystemCapability = { SystemCapability(available = false, onDevice = false) },
    internal val stt: SttSelectionController? = null,
    /** Account management for the unified voice catalog: deleting a provider also clears the voice models that depend on it; tests and the onboarding flow may omit it. */
    internal val voiceAccounts: com.wangzan101.mika.voice.model.VoiceAccountManager? = null,
) {
    internal val _state = MutableStateFlow(snapshot(ModelSetupUi(AddForm.of(ProviderPresets.all.first()))))
    val state: StateFlow<ModelSetupUi> = _state.asStateFlow()

    internal val _speechProbe = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Provider id -> reason the test transcription failed when selecting its voice model (using the existing wording); cleared when a selection passes or is made again. */
    val speechProbe: StateFlow<Map<String, String>> = _speechProbe.asStateFlow()

    internal var testJob: Job? = null
    internal val refreshJobs = mutableMapOf<String, Job>()

    init {
        // Follow the store when it is changed elsewhere (developer options, etc.)
        scope.launch { merge(store.providers, store.configs, store.roles, store.groups).collect { sync() } }
        speech?.let { s -> scope.launch { s.choice.collect { sync() } } }
        stt?.let { controller -> scope.launch { controller.catalog.state.collect { sync() } } }
        // Sign-in ended elsewhere (cancelled from the notification) or started before a UI rebuild: follow its progress
        scope.launch {
            signIns.running.collect { running ->
                val prompt = signIns.devicePrompt
                val target = signIns.target
                _state.update {
                    when {
                        running && target == SignInTarget.ChatGpt && it.chatGptSignIn == ListState.Idle ->
                            it.copy(chatGptSignIn = ListState.Running)
                        !running && it.chatGptSignIn == ListState.Running -> snapshot(it.copy(chatGptSignIn = ListState.Idle))
                        running && AccountProvider.of(target) != null -> it.copy(
                            accountSignIn = it.accountSignIn.copy(
                                provider = AccountProvider.of(target),
                                running = true,
                                userCode = prompt?.userCode ?: it.accountSignIn.userCode,
                                openUrl = prompt?.openUrl ?: it.accountSignIn.openUrl,
                                expiresAtMs = prompt?.expiresAtMs ?: it.accountSignIn.expiresAtMs,
                            ),
                        )
                        !running && it.accountSignIn.running -> snapshot(it.copy(accountSignIn = AccountSignInUi()))
                        else -> it
                    }
                }
            }
        }
        // The device code short code may arrive after a UI rebuild
        scope.launch {
            signIns.device.collect { prompt ->
                if (prompt == null) return@collect
                val provider = AccountProvider.of(signIns.target) ?: return@collect
                _state.update {
                    it.copy(accountSignIn = it.accountSignIn.copy(
                        provider = provider, running = true,
                        userCode = prompt.userCode, openUrl = prompt.openUrl, expiresAtMs = prompt.expiresAtMs,
                    ))
                }
            }
        }
        // Hand the account sign-in result to the current UI (the sign-in may have been started by the UI before a rebuild)
        scope.launch {
            signIns.outcome.collect { o ->
                if (o == null) return@collect
                val provider = AccountProvider.of(o.target) ?: return@collect
                _state.update {
                    if (o.providerId != null) snapshot(it.copy(accountSignIn = AccountSignInUi(), formOpen = false, signedInProvider = o.providerId))
                    else it.copy(accountSignIn = AccountSignInUi(provider = provider, failure = o.failure))
                }
                signIns.consumeOutcome(o)
            }
        }
    }

    internal fun snapshot(ui: ModelSetupUi): ModelSetupUi {
        val providers = store.providers.value
        return ui.copy(
            providers = providers, configs = store.configs.value, roles = store.roles.value, groups = store.groups.value,
            keyed = providers.filter { store.hasKey(it.id) }.map { it.id }.toSet(),
            providerKeyMap = providers.associate { it.id to store.providerKeys(it.id) },
            chatGptAccount = store.chatGptProvider()?.let { p ->
                store.chatGptSession(p.id).let { ChatGptAccount(it?.email.orEmpty(), it?.signedIn == true) }
            },
            xaiAccount = store.xaiProvider()?.let { p ->
                store.accountSession(p.id).let { SubscriptionAccount(it?.label.orEmpty(), it?.signedIn == true) }
            },
            openRouterAccount = store.openRouterAccount()?.let { p -> SubscriptionAccount("", store.hasKey(p.id)) },
            speech = speech?.choice?.value ?: SpeechChoice.Auto,
            systemSpeech = systemSpeech(),
            sttCatalog = stt?.takeIf { it.migrated }?.catalog?.state?.value,
        )
    }

    internal fun sync() = _state.update(::snapshot)

    // ---------- Add provider ----------

    /**
     * Outcome of a test call: passed, [model] did not call a tool, or failed ([message] is the user-facing reason).
     */
    internal sealed interface ProbeOutcome {
        data object Passed : ProbeOutcome
        data class NoToolCall(val model: String) : ProbeOutcome
        data class Failed(val message: String) : ProbeOutcome
    }

    companion object {
        /** How long a test call for a user-specified model may take at most. */
        const val PROBE_TIMEOUT_MS = 20_000L

        /** Maximum length of a custom provider name (so it fits on one line in the provider list). */
        const val MAX_NAME = 32
    }
}
