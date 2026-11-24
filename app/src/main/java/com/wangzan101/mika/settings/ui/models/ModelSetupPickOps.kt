package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.OpenAiServiceTier
import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ModelContextSettings
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.choose
import com.wangzan101.mika.settings.configFor
import com.wangzan101.mika.settings.deleteGroup
import com.wangzan101.mika.settings.ensureConfig
import com.wangzan101.mika.settings.ensureConfigId
import com.wangzan101.mika.settings.isMainModel
import com.wangzan101.mika.settings.markTyped
import com.wangzan101.mika.settings.markVerified
import com.wangzan101.mika.settings.saveGroup
import com.wangzan101.mika.settings.setDailyTokenBudget
import com.wangzan101.mika.settings.setFallback
import com.wangzan101.mika.settings.setModelContext
import com.wangzan101.mika.settings.setModelServiceTier
import com.wangzan101.mika.settings.setModelVision
import com.wangzan101.mika.settings.setReasoningEffort
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.TranscribeMethod
import com.wangzan101.mika.voice.model.VoiceAccount
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModelRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.settings.ui.models.ModelSetupViewModel.ProbeOutcome

// Model setup: choosing roles, fallbacks, model groups, voice models and per-model settings
/** Set this provider's default model (see [ModelOptions.defaultMain]) as the main model: test-called first like [pick], with progress in [ModelSetupUi.manualPick]. */
fun ModelSetupViewModel.useAsMain(providerId: String) {
    val p = store.provider(providerId) ?: return
    val options = ModelOptions.ofProvider(p, store.list())
    val m = ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id ?: return
    pick(ModelRole.Main, providerId, m)
}

fun ModelSetupViewModel.setReasoningEffort(role: ModelRole, effort: String?) {
    store.setReasoningEffort(role, effort)
    sync()
}

fun ModelSetupViewModel.setModelContext(configId: String, settings: ModelContextSettings): Boolean {
    val saved = store.setModelContext(configId, settings)
    sync()
    return saved
}

fun ModelSetupViewModel.setDailyTokenBudget(providerId: String, limit: Long?): Boolean {
    val saved = store.setDailyTokenBudget(providerId, limit)
    sync()
    return saved
}

fun ModelSetupViewModel.setModelVision(providerId: String, model: String, sees: Boolean) {
    store.setModelVision(providerId, model, sees)
    sync()
}

fun ModelSetupViewModel.setModelServiceTier(providerId: String, model: String, tier: OpenAiServiceTier?): Boolean {
    val saved = store.setModelServiceTier(providerId, model, tier)
    sync()
    return saved
}

/**
 * Explicitly change the role from "Use as" in the provider details.
 * The main model is test-called first like [pick]; vision and light are also test-called first when manually entered (not in the list) (see [verifyTyped]). Nothing changes on failure;
 * progress and the reason are in [ModelSetupUi.manualPick]. [onDone] is called after a successful save.
 */
fun ModelSetupViewModel.useFromProvider(role: ModelRole, providerId: String, model: String, onDone: () -> Unit = {}) {
    saveModelSelection(
        providerId, model, clearNote = role == ModelRole.Main,
        // The main model is still test-called first; vision and light are only tested when manually entered (not in the list), listed ones are not
        verify = when {
            role == ModelRole.Main -> { p, name -> verifyMain(p, name) }
            else -> { p, name -> if (listed(p, name)) null else verifyTyped(p, name, role) }
        },
        onPicked = onDone,
    ) { provider, name ->
        val caps = ModelOptions.ofProvider(provider, store.list()).firstOrNull { it.id == name }?.capabilities
            ?: ModelOptions.baseOf(provider)
        if (store.use(role, provider.id, name, caps)) null else tr(R.string.model_context_save_failed)
    }
}

/**
 * Save a chat model picked from the list. For the main model this model is test-called first (see [verifyMain]) and only swapped in when it passes;
 * on failure the existing main model stays. Vision and light are not test-called. Manually entered ones go through [pickManual].
 */
fun ModelSetupViewModel.pick(role: ModelRole, providerId: String, model: String, onPicked: () -> Unit = {}) =
    pickRole(role, providerId, model, onPicked, if (role == ModelRole.Main) { p, name -> verifyMain(p, name) } else null)

/**
 * A manually entered model name from the picker: it is test-called once for any role (see [verifyTyped]; [verifyMain] for the main model) and only used when it passes;
 * names not in the list are recorded as manually typed; on failure the reason is shown and the previous selection stays.
 */
fun ModelSetupViewModel.pickManual(role: ModelRole, providerId: String, model: String, onPicked: () -> Unit = {}) =
    pickRole(role, providerId, model, onPicked) { p, name ->
        if (role == ModelRole.Main) verifyMain(p, name) else verifyTyped(p, name, role)
    }

internal fun ModelSetupViewModel.pickRole(
    role: ModelRole, providerId: String, model: String, onPicked: () -> Unit,
    verify: (suspend (Provider, String) -> String?)?,
) {
    saveModelSelection(providerId, model, clearNote = role == ModelRole.Main, onPicked = onPicked, verify = verify) { provider, name ->
        if (use(role, provider, name, ModelOptions.ofProvider(provider, store.list()))) null
        else tr(R.string.model_context_save_failed)
    }
}

/** Save a fallback model picked from the list; it is generally not test-called, with the main-model exception below. Manually entered ones go through [pickFallbackManual]. */
fun ModelSetupViewModel.pickFallback(role: ModelRole, slot: Int, providerId: String, model: String, onPicked: () -> Unit = {}) =
    saveFallback(role, slot, providerId, model, onPicked, verify = if (role != ModelRole.Main) null else { p, name ->
        // Fallback for the main model: test-call once when the provider was saved skipping the test, or the model is not in the list (manually entered)
        if (!p.savedUntested && listed(p, name)) null else verifyTyped(p, name, ModelRole.Main)
    })

/**
 * [model] is in this provider's listed model catalog and Claire can use it ([ListedModel.usable]: entries the catalog marks as only serving other protocols do not count).
 * Unusable ones are treated as manually entered and test-called first — a model that is in the catalog but that Claire will not use must not be silently set as vision/light/fallback.
 */
internal fun ModelSetupViewModel.listed(p: Provider, model: String): Boolean = p.models.orEmpty().any { it.id == model && it.usable }

/** A manually entered fallback model name: test-called once (see [verifyTyped]) and only set when it passes; on failure this slot stays unchanged. */
fun ModelSetupViewModel.pickFallbackManual(role: ModelRole, slot: Int, providerId: String, model: String, onPicked: () -> Unit = {}) =
    saveFallback(role, slot, providerId, model, onPicked) { p, name -> verifyTyped(p, name, role) }

internal fun ModelSetupViewModel.saveFallback(
    role: ModelRole, slot: Int, providerId: String, model: String, onPicked: () -> Unit,
    verify: (suspend (Provider, String) -> String?)?,
) {
    saveModelSelection(providerId, model, clearNote = true, onPicked = onPicked, verify = verify) { provider, name ->
        if (store.configFor(role)?.let { it.providerId == provider.id && it.model == name } == true) {
            return@saveModelSelection tr(if (role == ModelRole.Main) R.string.main_fallback_is_main else R.string.main_fallback_failed)
        }
        val caps = ModelOptions.ofProvider(provider, store.list()).firstOrNull { it.id == name }?.capabilities
            ?: ModelOptions.baseOf(provider)
        val targetCaps = if (role == ModelRole.Vision) caps.copy(vision = true) else caps.copy(toolCalls = true)
        val configId = store.ensureConfigId(provider.id, name, targetCaps)
        if (configId != null && store.setFallback(role, slot, configId)) null
        else tr(R.string.main_fallback_failed)
    }
}

/**
 * Saves a model from the picker or the "Use as" sheet: when [verify] is given it runs first (the test call when setting the main model),
 * then [save]. Progress and the reason are recorded in [ModelSetupUi.manualPick].
 */
internal fun ModelSetupViewModel.saveModelSelection(
    providerId: String, model: String, clearNote: Boolean, onPicked: () -> Unit,
    verify: (suspend (Provider, String) -> String?)? = null,
    save: (Provider, String) -> String?,
) {
    val name = model.trim()
    if (name.isEmpty() || _state.value.manualPick[providerId] == ListState.Running) return
    _state.update { it.copy(manualPick = it.manualPick + (providerId to ListState.Running)) }
    scope.launch {
        val problem = try {
            val checked = store.provider(providerId)
            when {
                checked == null -> tr(R.string.model_context_save_failed)
                else -> verify?.invoke(checked, name)
            } ?: withContext(io) {
                val provider = store.provider(providerId) ?: return@withContext tr(R.string.model_context_save_failed)
                val failure = save(provider, name)
                if (failure == null && provider.models.orEmpty().none { it.id == name }) {
                    store.markTyped(providerId, name)
                }
                failure
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            tr(R.string.model_context_save_failed)
        }
        _state.update {
            snapshot(it.copy(
                manualPick = if (problem == null) it.manualPick - providerId
                    else it.manualPick + (providerId to ListState.Failed(problem)),
                probeNote = if (problem == null && clearNote) null else it.probeNote,
                pickToProbe = if (problem == null && clearNote) null else it.pickToProbe,
            ))
        }
        if (problem == null) onPicked()
    }
}

/** Clears fallback slot [slot] of the given role. */
fun ModelSetupViewModel.clearFallback(role: ModelRole, slot: Int) {
    scope.launch {
        withContext(io) { store.setFallback(role, slot, null) }
        sync()
    }
}

/** Clears main-model fallback slot [slot] (item 3). */
fun ModelSetupViewModel.clearFallback(slot: Int) = clearFallback(ModelRole.Main, slot)

/** Fixed choices for vision and light: "Use main model" or (vision only) "Off". */
fun ModelSetupViewModel.choose(role: ModelRole, choice: RoleChoice) {
    scope.launch {
        withContext(io) { store.choose(role, choice) }
        sync()
    }
}

/** Switch a role to a model group; does nothing when the group is unusable. */
fun ModelSetupViewModel.chooseGroup(role: ModelRole, groupId: String) {
    scope.launch {
        withContext(io) { store.choose(role, RoleChoice.Group(groupId)) }
        sync()
    }
}

/** Save a group (create, or replace the one with the same id); returns false when a member config is missing or a main-model group lacks tool support for every member. */
suspend fun ModelSetupViewModel.saveGroup(group: ModelGroup): Boolean = withContext(io) { store.saveGroup(group) }

suspend fun ModelSetupViewModel.deleteGroup(groupId: String) = withContext(io) { store.deleteGroup(groupId) }

/** Add a "provider + model" as a member while editing a group: reuse an existing config, otherwise create one with the current capabilities. */
suspend fun ModelSetupViewModel.ensureConfig(providerId: String, model: String): String? = withContext(io) {
    val p = store.provider(providerId) ?: return@withContext null
    val caps = ModelOptions.ofProvider(p, store.list()).firstOrNull { it.id == model }?.capabilities
        ?: ModelOptions.baseOf(p)
    store.ensureConfig(providerId, model, caps)
}

/** Choose "built into the phone" or "off"; takes effect immediately. */
fun ModelSetupViewModel.pickSpeech(choice: SpeechChoice) {
    if (choice is SpeechChoice.Provider) return
    if (stt?.migrated == true) {
        when (choice) {
            SpeechChoice.Auto, SpeechChoice.System, SpeechChoice.Off -> {
                if (choice != SpeechChoice.Off) speech?.retrySystem()
                stt.select(choice)
            }
            else -> return
        }
    } else {
        speech?.set(choice)
    }
    _speechProbe.value = emptyMap()
    sync()
}

/** After migration, selecting from the catalog only writes a model reference: no test transcription and no protocol probing. */
fun ModelSetupViewModel.pickSttModel(ref: VoiceModelRef, onPicked: () -> Unit = {}) {
    val controller = stt?.takeIf { it.migrated } ?: return
    controller.select(ref)
    _speechProbe.value = emptyMap()
    sync()
    onPicked()
}

/**
 * After migration, select directly by catalog reference; before migration, no longer probe the interface with a silent test transcription but resolve to an explicit selection (default endpoint) by provider kind,
 * then call [onPicked]. After migration the old selection still carries an explicit [TranscribeMethod], so nothing is re-probed at runtime.
 */
fun ModelSetupViewModel.pickSpeechModel(providerId: String, model: String, onPicked: () -> Unit = {}) {
    val m = model.trim()
    if (m.isEmpty() || _state.value.speechTesting != null) return
    val migrated = stt?.takeIf { it.migrated }
    if (migrated != null) {
        val snapshot = migrated.catalog.state.value
        val matches = snapshot.models.filter { candidate ->
            candidate.id == m && candidate.stt != null && snapshot.services.any { service ->
                service.id == candidate.serviceId && (
                    service.id == "provider:$providerId" ||
                        (service.account as? VoiceAccount.ExistingProvider)?.providerId == providerId
                    )
            }
        }.map { VoiceModelRef(it.serviceId, it.id, VoiceCapability.STT) }
        if (matches.size == 1) pickSttModel(matches.single(), onPicked)
        return
    }
    val settings = speech ?: return
    val p = store.provider(providerId) ?: return
    // Before migration there is no catalog to rely on, so resolve to an explicit selection by provider kind; default endpoint, no more test-transcription probing
    val method = if (p.kind == ProviderConfig.Kind.Gemini) TranscribeMethod.GeminiInline else TranscribeMethod.AudioTranscriptions
    settings.set(SpeechChoice.Provider(p.id, m, method))
    sync()
    onPicked()
}

internal fun ModelSetupViewModel.proxyReason(e: ModelError.Proxy): String = tr(
    if (e.failure == ProxyFailure.AuthFailed) R.string.net_proxy_auth_failed else R.string.net_proxy_unreachable,
)

/** When the picker opens, the previous test-transcription result is discarded. */
fun ModelSetupViewModel.clearSpeechProbe() { _speechProbe.value = emptyMap() }

/** Clears the previous save result when the picker or "Use as" sheet opens; entries still being test-called are kept (they update themselves when done). */
fun ModelSetupViewModel.clearManualPick() = _state.update { it.copy(manualPick = it.manualPick.filterValues { v -> v == ListState.Running }) }

/** Tests only the single model the user confirmed on the provider page. */
fun ModelSetupViewModel.retestModel(providerId: String, model: String) {
    val provider = store.provider(providerId) ?: return
    val key = providerId to model
    if (model !in _state.value.usedChatModels(provider) || _state.value.retest[key] == ListState.Running) return
    _state.update { it.copy(retest = it.retest + (key to ListState.Running)) }
    scope.launch {
        val outcome = probeSaved(provider, model)
        val problem = when {
            outcome is ProbeOutcome.Failed -> outcome.message
            outcome is ProbeOutcome.NoToolCall && store.isMainModel(providerId, model) ->
                tr(R.string.model_probe_no_tools_pick, model)
            else -> null
        }
        if (problem == null) withContext(io) { store.markVerified(providerId, model) }
        _state.update {
            snapshot(it.copy(retest = if (problem == null) it.retest - key else it.retest + (key to ListState.Failed(problem))))
        }
    }
}

internal fun ModelSetupViewModel.use(role: ModelRole, p: Provider, model: String, options: List<ModelOption>): Boolean {
    val caps = options.firstOrNull { it.id == model }?.capabilities ?: ModelOptions.baseOf(p)
    return store.use(role, p.id, model, caps)
}
