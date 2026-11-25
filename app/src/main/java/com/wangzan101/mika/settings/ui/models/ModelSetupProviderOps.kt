package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.accountSession
import com.wangzan101.mika.settings.addProviderKey
import com.wangzan101.mika.settings.chatGptSession
import com.wangzan101.mika.settings.hasKey
import com.wangzan101.mika.settings.listModels
import com.wangzan101.mika.settings.rememberModels
import com.wangzan101.mika.settings.removeProvider
import com.wangzan101.mika.settings.removeProviderKey
import com.wangzan101.mika.settings.signOutAccount
import com.wangzan101.mika.settings.signOutChatGpt
import com.wangzan101.mika.settings.updateProviderKey
import com.wangzan101.mika.voice.model.VoiceModelRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.chat.ui.isActive

// Model setup: refreshing lists, changing Keys and removing providers
/** Lists this provider's models again and records them; records the reason on failure. */
fun ModelSetupViewModel.refresh(providerId: String) {
    val p = store.provider(providerId) ?: return
    if (refreshJobs[providerId]?.isActive == true) return
    _state.update { it.copy(listing = it.listing + (providerId to ListState.Running)) }
    refreshJobs[providerId] = scope.launch {
        val r = listSafely { store.listModels(p) }
        val models: List<ListedModel>? = when (r) {
            is ModelListResult.Ok -> r.models
            is ModelListResult.Failed -> if (r.reason == ListFailure.NotSupported) emptyList() else null
        }
        if (models != null) withContext(io) { store.rememberModels(p.id, models) }
        val after = if (r is ModelListResult.Failed && models == null) ListState.Failed(listMessage(r)) else ListState.Idle
        _state.update { snapshot(it.copy(listing = it.listing + (providerId to after))) }
    }
}

/** When the picker or provider page opens: list providers whose model list has not been remembered yet. */
fun ModelSetupViewModel.ensureListed() {
    _state.value.providers.filter { it.models == null && _state.value.listing[it.id] == null && listable(it) }.forEach { refresh(it.id) }
}

/** Signed-out providers cannot list models; wait until they sign in again. */
internal fun ModelSetupViewModel.listable(p: Provider) = if (p.signIn != null) store.hasKey(p.id) else when (p.kind) {
    ProviderConfig.Kind.ChatGpt -> store.chatGptSession(p.id)?.signedIn == true
    ProviderConfig.Kind.Xai -> store.accountSession(p.id)?.signedIn == true
    ProviderConfig.Kind.OpenCodeGo -> store.accountSession(p.id)?.signedIn == true
    else -> true
}

/**
 * Change the Key, then list the models again with the new Key. Zero-width characters are stripped first; line breaks and other characters that cannot go into a request header mean nothing is saved and no models are listed.
 */
fun ModelSetupViewModel.changeKey(providerId: String, apiKey: String) {
    val k = headerKey(apiKey) ?: return
    if (k.isEmpty()) return
    scope.launch {
        try {
            withContext(io) { store.updateProviderKey(providerId, k) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(listing = it.listing + (providerId to ListState.Failed(listFailureText(e)))) }
            return@launch
        }
        refreshJobs.remove(providerId)?.cancel()
        refresh(providerId)
        sync()
    }
}

fun ModelSetupViewModel.addProviderKey(providerId: String, apiKey: String) {
    val k = headerKey(apiKey) ?: return
    if (k.isEmpty()) return
    scope.launch {
        try {
            withContext(io) { store.addProviderKey(providerId, k) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(listing = it.listing + (providerId to ListState.Failed(listFailureText(e)))) }
            return@launch
        }
        refreshJobs.remove(providerId)?.cancel()
        refresh(providerId)
        sync()
    }
}

fun ModelSetupViewModel.removeProviderKey(providerId: String, keyToRemove: String) {
    scope.launch {
        try {
            withContext(io) { store.removeProviderKey(providerId, keyToRemove) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(listing = it.listing + (providerId to ListState.Failed(listFailureText(e)))) }
            return@launch
        }
        refreshJobs.remove(providerId)?.cancel()
        refresh(providerId)
        sync()
    }
}

/** Preview the voice-model capability references under this provider before deleting it; the delete panel uses it to spell out the impact. */
fun ModelSetupViewModel.providerVoiceUsage(providerId: String): List<com.wangzan101.mika.voice.model.VoiceModelRef> =
    voiceAccounts?.providerVoiceUsage(providerId) ?: emptyList()

/** Delete the provider and its Key; roles using it fall back to the default, and voice models depending on it are cleared as well. */
fun ModelSetupViewModel.removeProvider(providerId: String) {
    scope.launch {
        withContext(io) {
            // For ChatGPT, sign out on the server first, then delete
            when (store.provider(providerId)?.kind) {
                ProviderConfig.Kind.ChatGpt -> store.signOutChatGpt(providerId)
                ProviderConfig.Kind.Xai, ProviderConfig.Kind.OpenCodeGo -> store.signOutAccount(providerId)
                else -> Unit
            }
            // A Key obtained through OpenRouter authorization is deleted along with the provider; the Key on OpenRouter's side must be deleted by the user on the Keys page
            store.removeProvider(providerId)
            voiceAccounts?.deleteProviderWithVoiceUsage(providerId)
        }
        _state.update { snapshot(it.copy(listing = it.listing - providerId)) }
    }
}
