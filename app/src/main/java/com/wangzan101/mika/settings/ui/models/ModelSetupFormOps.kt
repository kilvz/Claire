package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.KeyPoolManager
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.ProbeResult
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.connect
import com.wangzan101.mika.settings.isMainModel
import com.wangzan101.mika.settings.listModels
import com.wangzan101.mika.settings.markTyped
import com.wangzan101.mika.settings.markVerified
import com.wangzan101.mika.settings.probe
import com.wangzan101.mika.settings.plainApiKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.wangzan101.mika.settings.ui.models.ModelSetupViewModel.ProbeOutcome
import com.wangzan101.mika.settings.ui.models.ModelSetupViewModel.Companion.PROBE_TIMEOUT_MS
import com.wangzan101.mika.settings.ui.models.ModelSetupViewModel.Companion.MAX_NAME

// Model setup: add-provider form, test-and-save, and model probing
fun ModelSetupViewModel.openForm() = _state.update { it.copy(formOpen = true) }

fun ModelSetupViewModel.closeForm() {
    testJob?.cancel()
    _state.update { it.copy(formOpen = false, form = AddForm.of(ProviderPresets.default)) }
}

fun ModelSetupViewModel.selectPreset(label: String) {
    val p = AddForm.presetFor(label)
    if (p.label == _state.value.form.preset) return
    val form = _state.value.form
    edit { AddForm.of(p).copy(apiKey = form.apiKey, keyNotice = form.keyNotice) }
}

// After changing the protocol or URL, a previous "no models listed" no longer applies and the list must be fetched again
fun ModelSetupViewModel.setKind(kind: ProviderConfig.Kind) = edit { it.copy(kind = kind, noList = false) }

fun ModelSetupViewModel.setBaseUrl(url: String) = edit { it.copy(baseUrl = url, noList = false) }

fun ModelSetupViewModel.setAccountId(input: String) {
    val trimmed = input.trim()
    val accountId = when {
        trimmed.contains("/accounts/") -> trimmed.substringAfter("/accounts/").substringBefore("/").trim()
        trimmed.contains("dash.cloudflare.com/") -> trimmed.substringAfter("dash.cloudflare.com/").substringBefore("/").trim()
        else -> trimmed
    }
    val newUrl = if (accountId.isBlank()) {
        "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1"
    } else {
        "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/v1"
    }
    setBaseUrl(newUrl)
}

/** Pasting or typing a Key: strip zero-width characters; keep line breaks and other characters that cannot go into a request header as-is and flag them immediately. */
fun ModelSetupViewModel.setApiKey(key: String) {
    val accepted = ApiKeyText.accept(key)
    edit { it.copy(apiKey = accepted.text, keyNotice = accepted.notice) }
}

fun ModelSetupViewModel.setModel(model: String) = edit { it.copy(model = model) }

/** "Enter model name manually": shows the model name field when on; clears what was entered when turned off (when no models were listed the field always stays). */
fun ModelSetupViewModel.setManual(on: Boolean) = edit { it.copy(manual = on, model = if (on || it.noList) it.model else "") }

// Truncate by character (code point) so emoji are never cut in half
fun ModelSetupViewModel.setName(name: String) = edit {
    val n = name.codePointCount(0, name.length)
    it.copy(name = if (n <= MAX_NAME) name else name.substring(0, name.offsetByCodePoints(0, MAX_NAME)))
}

/** Connection details changed: invalidate the running test and the previous failure reason. */
internal fun ModelSetupViewModel.edit(change: (AddForm) -> AddForm) {
    testJob?.cancel()
    _state.update { it.copy(form = change(it.form).copy(test = ListState.Idle)) }
}

/**
 * Test and save: if models can be listed, store this provider and its list (when it cannot, ask the user to enter a model manually, or use [saveWithoutTest] to save it anyway).
 * When a main model already exists, only list the models and send no chat request. When there is no main model yet (first run, first provider), pick a candidate: use the manually entered model name if there is one,
 * otherwise follow [ModelOptions.defaultMain], and failing that use the first chat model (ones marked TTS excluded); make it call a tool once (see [verifyMain]) and
 * use it as the main model if that passes; if it fails, the provider is still saved without a main model, the reason is explained, and an entry point for "choose main model" is offered ([ModelSetupUi.pickToProbe]).
 */
fun ModelSetupViewModel.testAndSave() {
    val f = _state.value.form
    if (!f.canTest) return
    val key = headerKey(f.apiKey) ?: return rejectKey()
    testJob?.cancel()
    _state.update { it.copy(form = it.form.copy(test = ListState.Running), probeNote = null, pickToProbe = null) }
    testJob = scope.launch {
        val baseUrl = f.baseUrl.trim()
        val typed = f.typedModel
        val models = when (val r = listSafely { store.listModels(f.kind, baseUrl, key.ifEmpty { null }, f.sessionHeader) }) {
            is ModelListResult.Ok -> r.models
            is ModelListResult.Failed -> {
                if (r.reason != ListFailure.NotSupported) {
                    _state.update { it.copy(form = it.form.copy(test = ListState.Failed(listMessage(r)), manual = true)) }
                    return@launch
                }
                emptyList()
            }
        }
        if (models.isEmpty()) {
            _state.update { it.copy(form = it.form.copy(test = ListState.Idle, noList = true)) }
            return@launch
        }
        val saved = try {
            withContext(io) {
                store.connect(
                    f.providerName, f.kind, baseUrl, key, models, f.sessionHeader,
                    typed = setOfNotNull(typed.takeIf { it.isNotEmpty() && models.none { model -> model.id == it } }),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(form = it.form.copy(test = ListState.Failed(listFailureText(e)))) }
            return@launch
        }
        var note: String? = null
        if (!store.roles.value.hasMain) {
            val options = ModelOptions.ofProvider(saved, store.list())
            // If the manually entered model is one the list says Claire cannot use (not in options), do not test it
            val candidate = typed.takeIf { t -> t.isNotBlank() && (models.none { it.id == t } || options.any { it.id == t }) }
                ?: ModelOptions.defaultMain(options, f.familyTokens) ?: options.firstOrNull()?.id
            if (candidate != null) {
                val problem = verifyMain(saved, candidate)
                if (problem == null) {
                    withContext(io) { store.provider(saved.id)?.let { use(ModelRole.Main, it, candidate, options) } }
                } else {
                    note = tr(R.string.model_saved_no_main, problem)
                }
            }
        }
        _state.update {
            snapshot(it.copy(
                form = AddForm.of(ProviderPresets.default), formOpen = false,
                probeNote = note, pickToProbe = saved.id.takeIf { note != null },
            ))
        }
    }
}

/**
 * Save without testing: store this provider using only the entered protocol, URL and Key, without listing models or making a test call (see [AddForm.canSaveUntested]).
 * The stored provider carries [Provider.savedUntested], and the provider page offers an entry point to refresh the catalog.
 *
 * When a model name was entered manually ([AddForm.typedModel]), it is only recorded as manually typed — so it is not lost when the list is refreshed, even if the list does not contain it or this provider cannot list models at all.
 *
 * The list argument is an empty list ("listed, but none found") rather than null: together with [Provider.savedUntested] this distinguishes
 * the two cases — there really is no list, or this time no listing was attempted.
 */
fun ModelSetupViewModel.saveWithoutTest() {
    val f = _state.value.form
    if (!f.canSaveUntested) return
    val key = headerKey(f.apiKey) ?: return rejectKey()
    testJob?.cancel()
    _state.update { it.copy(form = it.form.copy(test = ListState.Running), probeNote = null, pickToProbe = null) }
    testJob = scope.launch {
        val baseUrl = f.baseUrl.trim()
        val typed = f.typedModel
        try {
            withContext(io) {
                store.connect(
                    f.providerName, f.kind, baseUrl, key.ifEmpty { null }, emptyList(), f.sessionHeader, untested = true,
                ).also { p -> if (typed.isNotEmpty()) store.markTyped(p.id, typed) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(form = it.form.copy(test = ListState.Failed(listFailureText(e)))) }
            return@launch
        }
        _state.update {
            snapshot(it.copy(
                form = AddForm.of(ProviderPresets.default), formOpen = false,
                probeNote = tr(R.string.model_saved_untested, f.providerName),
            ))
        }
    }
}

/**
 * The Key to put into the request header: sanitized once more (zero-width removed) and trimmed. A line break counts as not header-safe.
 * Returns null while characters that cannot go into a header remain; the caller must then neither send requests nor save.
 */
internal fun ModelSetupViewModel.headerKey(raw: String): String? {
    val accepted = ApiKeyText.accept(raw)
    if (accepted.notice == ApiKeyText.Notice.IllegalCharacters) return null
    val key = accepted.text.trim()
    if (key.isEmpty()) return ""
    val parsed = KeyPoolManager.shared.parseKeys(key)
    return if (parsed.isNotEmpty() && parsed.all { ApiKeyText.isHeaderLegal(it) }) key else null
}

/** The Key on the form cannot go into a request header: flag it in red, stay on the form and send no request. */
internal fun ModelSetupViewModel.rejectKey() {
    _state.update { it.copy(form = it.form.copy(keyNotice = ApiKeyText.Notice.IllegalCharacters, test = ListState.Idle)) }
}

/** Lists models. If a header value still makes OkHttp throw, this is turned into a failed result so the exception cannot escape the coroutine and crash the process. Messages never include the raw header. */
internal suspend fun ModelSetupViewModel.listSafely(block: suspend () -> ModelListResult): ModelListResult = try {
    withContext(io) { block() }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    listFailure(e)
}

internal fun ModelSetupViewModel.listFailure(e: Exception): ModelListResult.Failed = when (e) {
    is ModelError.IllegalHeader, is IllegalArgumentException -> ModelListResult.Failed(ListFailure.IllegalKey)
    else -> ModelListResult.Failed(ListFailure.of(e), e.message.orEmpty())
}

internal fun ModelSetupViewModel.listMessage(r: ModelListResult.Failed): String =
    if (r.reason == ListFailure.IllegalKey) tr(R.string.model_key_illegal_chars) else r.message

/** A user-facing line for failures other than listing. When a header value is illegal, the raw exception text (which may contain the Key) is not exposed. */
internal fun ModelSetupViewModel.listFailureText(e: Exception): String = when (e) {
    is ModelError.IllegalHeader, is IllegalArgumentException -> tr(R.string.model_key_illegal_chars)
    else -> e.message.orEmpty()
}

/**
 * Make [model] call a tool once, going through the same model-construction path as a real task (same request headers and session id): a wrong Key, a missing header the provider requires,
 * a wrong model name, or a model that cannot call tools shows up here instead of at the first task. No reply within [PROBE_TIMEOUT_MS] counts as a failure and carries the server error as-is;
 * when the model is not found, it says which one it was and gives a model name example for interfaces of kind [exampleKind].
 */
internal suspend fun ModelSetupViewModel.probeModel(
    kind: ProviderConfig.Kind, baseUrl: String, key: String?, sessionHeader: String?,
    model: String, endpoint: ModelEndpoint?, caps: Capabilities, exampleKind: ProviderConfig.Kind,
    providerId: String = "",
): ProbeOutcome {
    val result = try {
        withContext(io) {
            withTimeout(PROBE_TIMEOUT_MS) {
                store.probe(kind, baseUrl, key, sessionHeader, model, endpoint, caps, providerId)
            }
        }
    } catch (e: TimeoutCancellationException) {
        return ProbeOutcome.Failed(tr(R.string.model_probe_timeout, model, (PROBE_TIMEOUT_MS / 1000).toInt()))
    }
    return when (result) {
        ProbeResult.ToolCalled -> ProbeOutcome.Passed
        ProbeResult.NoToolCall -> ProbeOutcome.NoToolCall(model)
        is ProbeResult.Failed -> when (val e = result.error) {
            is ModelError.ModelNotAvailable -> {
                val detail = e.detail.ifEmpty { e.message.orEmpty() }
                ProbeOutcome.Failed(tr(R.string.model_probe_not_available, model, detail))
            }
            is ModelError.ToolsUnsupported -> ProbeOutcome.Failed(tr(R.string.model_probe_no_tools_pick, model))
            is ModelError.ModelNotFound -> ProbeOutcome.Failed(
                tr(R.string.model_probe_not_found, model, ModelSetupCopy.modelExample(exampleKind)),
            )
            // A problem in the proxy leg: say it is the proxy, not that the model or provider is unusable
            is ModelError.Proxy -> ProbeOutcome.Failed(tr(R.string.model_probe_failed, model, proxyReason(e)))
            is ModelError.IllegalHeader -> ProbeOutcome.Failed(tr(R.string.model_key_illegal_chars))
            else -> ProbeOutcome.Failed(tr(R.string.model_probe_failed, model, e.message.orEmpty()))
        }
    }
}

/**
 * Test-call [model] on an already connected provider: using the stored Key and request headers, with capabilities from the existing config or the preset.
 */
internal suspend fun ModelSetupViewModel.probeSaved(p: Provider, model: String): ProbeOutcome {
    val stored = store.provider(p.id)
        ?: return ProbeOutcome.Failed(tr(R.string.model_probe_not_found, model, ModelSetupCopy.modelExample(p.kind)))
    val options = ModelOptions.ofProvider(stored, store.list())
    val caps = options.firstOrNull { it.id == model }?.capabilities ?: ModelOptions.baseOf(stored)
    val listed = stored.models?.firstOrNull { it.id == model }
    val example = ProviderPresets.forHost(stored.baseUrl)?.kind ?: stored.kind
    return probeModel(
        stored.kind, stored.baseUrl, store.plainApiKey(p.id), stored.sessionHeader, model, listed?.endpoint, caps, example,
        providerId = stored.id,
    )
}

/**
 * Gate before setting the main model: make [model] call a tool once; replying without calling a tool also counts as a failure (the main model must be able to call tools).
 * [Provider.verifiedModels] only says it has replied and is not a reason to skip; a model that is already the main model is not tested again.
 * On success it is marked as verified. Returns the failure reason, or null when it passed.
 */
internal suspend fun ModelSetupViewModel.verifyMain(p: Provider, model: String): String? {
    // verifiedModels only says it has replied (transcription and tool-less replies count too) and does not prove it can call tools; only a model that already is the main model is skipped
    if (store.isMainModel(p.id, model)) return null
    return when (val outcome = probeSaved(p, model)) {
        ProbeOutcome.Passed -> {
            withContext(io) { store.markVerified(p.id, model) }
            null
        }
        is ProbeOutcome.NoToolCall -> tr(R.string.model_probe_no_tools_pick, model)
        is ProbeOutcome.Failed -> outcome.message
    }
}

/**
 * For a manually entered model name from the picker, vision, light and fallback also test-call it first (the main model goes through [verifyMain]): a typo shows up here instead of at a failed task.
 * Replying without calling a tool also counts as a failure, except for vision (vision does not rely on tools). On success (including a reply-only vision test) it is marked as verified. Returns the failure reason, or null when it passed.
 */
internal suspend fun ModelSetupViewModel.verifyTyped(p: Provider, model: String, role: ModelRole): String? =
    when (val outcome = probeSaved(p, model)) {
        ProbeOutcome.Passed -> {
            withContext(io) { store.markVerified(p.id, model) }
            null
        }
        is ProbeOutcome.NoToolCall -> if (role == ModelRole.Vision) {
            withContext(io) { store.markVerified(p.id, model) }
            null
        } else tr(R.string.model_probe_no_tools_pick, model)
        is ProbeOutcome.Failed -> outcome.message
    }
