package com.wangzan101.mika.voice.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ui.models.ModelsCopy
import com.wangzan101.mika.ui.components.ActionSheet
import com.wangzan101.mika.ui.components.FallbackChainCard
import com.wangzan101.mika.ui.components.FallbackChainItem
import com.wangzan101.mika.ui.components.SheetAction
import com.wangzan101.mika.voice.model.SttSelection
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.voice.model.VoiceFallbacks
import com.wangzan101.mika.voice.model.VoiceModelRef

/**
 * Fallback chain on the voice input / read-aloud pages, using the same card as chat-model fallback chains
 * ([FallbackChainCard]). Hidden when no head is selected or the head is streaming recognition that writes while you speak
 * (it cannot switch models mid-way). Backups list only eligible models, never the head itself; a voice model bundled with
 * a chat account that is not in the catalog yet is written into the catalog when selected.
 */
@Composable
internal fun VoiceFallbackSection(
    capability: VoiceCapability,
    snapshot: VoiceCatalogSnapshot,
    providers: List<Provider>,
    voiceVm: VoiceCatalogViewModel,
) {
    val selections = snapshot.selections
    val (primary, chain) = when (capability) {
        VoiceCapability.STT -> (selections.stt as? SttSelection.Model)?.ref to selections.sttFallbacks
        VoiceCapability.TTS -> selections.tts to selections.ttsFallbacks
        VoiceCapability.REALTIME -> null to emptyList()
    }
    if (primary == null) return
    val primaryModel = snapshot.models.firstOrNull { it.serviceId == primary.serviceId && it.id == primary.modelId } ?: return
    if (!VoiceFallbacks.eligible(primaryModel, capability)) return

    val options = voiceOptions(capability, snapshot, providers)
    val label = stringResource(if (capability == VoiceCapability.STT) R.string.voice_hub_stt else R.string.voice_hub_tts)
    var picking by rememberSaveable(capability.name) { mutableStateOf<Int?>(null) }

    @Composable
    fun item(ref: VoiceModelRef): FallbackChainItem {
        val option = options.firstOrNull { it.ref == ref }
        return FallbackChainItem(
            name = option?.model?.displayName?.ifBlank { null } ?: option?.model?.id ?: ModelsCopy.UNSET,
            detail = option?.let { voiceSourceLabel(it.source) }.orEmpty(),
            missing = option == null,
        )
    }

    FallbackChainCard(
        title = stringResource(R.string.model_fallback_chain_title, label),
        primary = item(primary),
        fallbacks = chain.map { item(it) },
        max = VoiceFallbacks.MAX,
        tagPrefix = "voice-fallback-${capability.name.lowercase()}",
        icon = ModelsCopy.FALLBACK_ICON,
        removeBody = { model -> stringResource(R.string.model_fallback_remove_body, model, label) },
        footer = stringResource(
            if (capability == VoiceCapability.STT) R.string.voice_fallback_footer_stt else R.string.voice_fallback_footer_tts,
        ),
        onPick = { slot -> picking = slot },
        onRemove = { slot -> voiceVm.catalog.setFallback(capability, slot, null) },
        onMove = { from, to -> voiceVm.catalog.moveFallback(capability, from, to) },
    )

    val slot = picking ?: return
    val candidates = options.filter { it.ref != primary && VoiceFallbacks.eligible(it.model, capability) }
    val actions = if (candidates.isEmpty()) {
        listOf(SheetAction(stringResource(R.string.voice_fallback_none), searchable = false) { picking = null })
    } else {
        candidates.map { option ->
            val name = option.model.displayName.ifBlank { option.model.id }
            SheetAction(stringResource(R.string.voice_fallback_pick_option, name, voiceSourceLabel(option.source))) {
                picking = null
                voiceVm.catalog.setFallback(capability, slot, voiceVm.ensureInCatalog(option))
            }
        }
    }
    ActionSheet(
        title = stringResource(R.string.voice_fallback_pick_title, label),
        actions = actions,
        searchHint = stringResource(R.string.search),
        onDismiss = { picking = null },
    )
}
