package com.wangzan101.mika.mcp.ui

import com.wangzan101.mika.core.cap.mcp.McpCatalog
import com.wangzan101.mika.core.cap.mcp.McpPreset
import com.wangzan101.mika.settings.McpServerConfig
import com.wangzan101.mika.settings.McpServerStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Connection stages of the guided setup. The state machine for catalog browsing and connection orchestration; the UI only reads this state and does not assemble URLs/headers/argv on its own. */
sealed interface McpConnectStage {
    /** Browsing the catalog (or no preset has been opened). */
    data object Browsing : McpConnectStage

    /** A preset has been opened: fill in fields / authorize / confirm the stdio package / wait for the connection. */
    data class Form(
        val preset: McpPreset,
        val values: Map<String, String>,
        val secretValues: Map<String, String>,
        val connecting: Boolean,
        val needsPackageConsent: Boolean,
        val error: String?,
    ) : McpConnectStage

    /** Connected successfully and enabled; tools come from the real tool catalog. */
    data class Connected(val config: McpServerConfig, val tools: List<String>) : McpConnectStage
}

/**
 * Orchestrates a catalog preset into the existing [McpServerStore] connection path: fill in fields → (authorize / confirm the package if needed) → connect and read tools.
 * Secrets only go through the store's encrypted storage and never into any log or plain config outside this state.
 */
class McpConnectorState(
    private val store: McpServerStore,
    private val catalog: McpCatalog,
    private val scope: CoroutineScope,
) {
    private val _stage = MutableStateFlow<McpConnectStage>(McpConnectStage.Browsing)
    val stage: StateFlow<McpConnectStage> = _stage.asStateFlow()

    /** The in-flight connection/authorization job; tests wait for it to settle with join, the UI aborts it with [cancel]. */
    internal var inFlight: Job? = null
        private set

    /** Open a preset, exposing only the fields that preset declares; when stdio includes a package that has not been confirmed yet, enter the pending-confirmation stage. Reopening the same preset keeps uncommitted non-secret drafts and drops secret drafts. */
    fun openPreset(id: String) {
        cancel()
        val preset = catalog.presets.find { it.id == id } ?: return
        val needsConsent = preset.packageSpec?.let { !store.packageAccepted(it) } == true
        val existing = _stage.value as? McpConnectStage.Form
        _stage.value = if (existing?.preset?.id == id) {
            existing.copy(connecting = false, error = null, needsPackageConsent = needsConsent, secretValues = emptyMap())
        } else {
            McpConnectStage.Form(
                preset = preset,
                values = emptyMap(),
                secretValues = emptyMap(),
                connecting = false,
                needsPackageConsent = needsConsent,
                error = null,
            )
        }
    }

    /** Update a field: secret fields and ordinary fields are stored separately, and secrets never enter the plain config. */
    fun updateField(id: String, value: String) {
        val form = _stage.value as? McpConnectStage.Form ?: return
        val isSecret = form.preset.fields.any { it.id == id && it.secret }
        _stage.value = if (isSecret) {
            form.copy(secretValues = form.secretValues + (id to value))
        } else {
            form.copy(values = form.values + (id to value))
        }
    }

    /** Confirm the stdio package (name + exact version + source) and then continue connecting. */
    fun confirmPackage() {
        val form = _stage.value as? McpConnectStage.Form ?: return
        form.preset.packageSpec?.let { store.acceptPackage(it) }
        connect()
    }

    /** Connection entry point for no auth / API key / stdio (with the package already confirmed). For OAuth use [authorize]. */
    fun connect() {
        val form = _stage.value as? McpConnectStage.Form ?: return
        val preset = form.preset
        val spec = preset.packageSpec
        if (spec != null && !store.packageAccepted(spec)) {
            _stage.value = form.copy(needsPackageConsent = true, error = null)
            return
        }
        launch(form) {
            store.connectPreset(preset.id, form.values, form.secretValues).fold(
                onSuccess = { result ->
                    store.presetConfig(preset.id, form.values).fold(
                        onSuccess = { config -> McpConnectStage.Connected(config, result.tools) },
                        onFailure = { form.copy(connecting = false, error = it.message) },
                    )
                },
                onFailure = { form.copy(connecting = false, error = it.message) },
            )
        }
    }

    /** OAuth: after saving the config, go through the existing discovery/registration/PKCE flow; once the callback obtains a token, read the tool catalog automatically. Retry/re-authorization reuse the same config id and do not create duplicate entries. */
    fun authorize(openBrowser: (String) -> Unit) {
        val form = _stage.value as? McpConnectStage.Form ?: return
        val preset = form.preset
        launch(form) {
            val config = store.presetConfig(preset.id, form.values).getOrElse {
                return@launch form.copy(connecting = false, error = it.message)
            }
            val saved = store.savePreset(config).getOrElse {
                return@launch form.copy(connecting = false, error = it.message)
            }
            store.authorize(saved, openBrowser).getOrElse {
                store.setEnabled(saved.id, false)
                return@launch form.copy(connecting = false, error = it.message)
            }
            store.test(saved).fold(
                onSuccess = { McpConnectStage.Connected(saved, it.tools) },
                onFailure = {
                    store.setEnabled(saved.id, false)
                    form.copy(connecting = false, error = it.message)
                },
            )
        }
    }

    /** Retry after a failure: uses the original preset's current fields and confirmed state. */
    fun retry() = connect()

    /**
     * Replaces secrets only for the non-empty entries in [secrets]; empty values keep the previous secret. Only writes to encrypted storage, never to the plain config.
     * Fields not declared in the preset snapshot are ignored, so arbitrary keys cannot be written as secrets.
     */
    fun replaceSecrets(config: McpServerConfig, secrets: Map<String, String>) {
        val declared = config.presetSnapshot?.fields?.filter { it.secret }?.map { it.id }?.toSet()
        secrets.forEach { (fieldId, value) ->
            if (value.isNotBlank() && (declared == null || fieldId in declared)) {
                store.setSecret(config.id, fieldId, value)
            }
        }
    }

    /** Cancel the in-flight connection/download; drafts (non-secret input) stay in the Form. */
    fun cancel() {
        inFlight?.cancel()
        inFlight = null
        val form = _stage.value as? McpConnectStage.Form ?: return
        if (form.connecting) _stage.value = form.copy(connecting = false)
    }

    /** Return to catalog browsing. */
    fun close() {
        cancel()
        _stage.value = McpConnectStage.Browsing
    }

    private fun launch(form: McpConnectStage.Form, block: suspend () -> McpConnectStage) {
        inFlight = scope.launch {
            _stage.value = form.copy(connecting = true, error = null)
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Precheck errors such as missing required fields are also presented as a failure state instead of bubbling up and crashing.
                form.copy(connecting = false, error = e.message ?: e.javaClass.simpleName)
            }
            _stage.value = if (result is McpConnectStage.Form) {
                // Fields remain editable while connecting; when it settles, carry the current draft (non-secret values + secret values + package confirmation state) forward, and do not overwrite edits with the stale snapshot from the moment of the click.
                val current = _stage.value as? McpConnectStage.Form
                result.copy(
                    values = current?.values ?: result.values,
                    secretValues = current?.secretValues ?: result.secretValues,
                    needsPackageConsent = current?.needsPackageConsent ?: result.needsPackageConsent,
                )
            } else {
                result
            }
        }
    }
}
