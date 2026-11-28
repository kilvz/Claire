package com.wangzan101.mika.voice.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.VoiceSettings
import com.wangzan101.mika.ui.components.ActionSheet
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.SheetAction
import com.wangzan101.mika.ui.components.SheetTrailing
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.voice.SystemTtsDiscovery
import com.wangzan101.mika.voice.SystemTtsEngineInfo
import com.wangzan101.mika.voice.SystemTtsVoiceInfo
import com.wangzan101.mika.voice.model.TtsSelectionController
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import java.util.Locale

/**
 * Changing the engine used for system read-aloud: first write the engine into the catalog, and only after it succeeds clear the voice back to default (a voice name is only valid within one engine);
 * when the write fails, engine and voice both stay as they were. Re-selecting the current engine does not touch the voice.
 */
internal fun chooseSystemTtsEngine(tts: TtsSelectionController, settings: VoiceSettings, engine: String): Boolean {
    val changed = try {
        tts.setSystemEngine(engine)
    } catch (e: Exception) {
        // Catalog unreadable, unwritable (the check in VoiceCatalogStore.commit) or system read-aloud not registered: change nothing
        return false
    }
    if (changed) settings.setVoice("")
    return changed
}

/** Text for each item in the engine sheet: engines with the same name get the package name appended so it is clear which one it is. */
internal fun engineLabels(engines: List<SystemTtsEngineInfo>): Map<String, String> {
    val duplicated = engines.groupBy { it.label }.filterValues { it.size > 1 }.keys
    return engines.associate { it.packageName to if (it.label in duplicated) "${it.label} (${it.packageName})" else it.label }
}

/**
 * The "Engine" and "Voice" rows of built-in system read-aloud. The engine column lists speech synthesis engines registered on the phone, the voice column lists voices installed for the selected engine,
 * both queried in the background, showing only "Default" when not found. Rows and sheets only recognise names from public APIs and do not special-case any vendor's engine.
 * When [onAudition] is given, each voice item in the sheet has an audition button; playback belongs to the caller, and when the sheet closes [onVoicesClosed] is called to stop it.
 */
@Composable
internal fun SystemTtsRows(
    voiceModels: VoiceModelEnvironment,
    settings: VoiceSettings,
    onChanged: () -> Unit,
    onAudition: ((SystemTtsVoiceInfo) -> Unit)? = null,
    onVoicesClosed: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by settings.state.collectAsState()
    val catalog by voiceModels.catalog.state.collectAsState()
    val engine = remember(catalog) { voiceModels.tts.systemEngine() }
    val engines by produceState(emptyList<SystemTtsEngineInfo>()) { value = SystemTtsDiscovery.engines(context) }
    val voices by produceState<List<SystemTtsVoiceInfo>?>(null, engine) {
        value = null
        val preferred = AppLanguage.resolved(context).speechTag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
        value = SystemTtsDiscovery.voices(context, engine, preferred)
    }
    var enginesOpen by remember { mutableStateOf(false) }
    var voicesOpen by remember { mutableStateOf(false) }
    val labels = engineLabels(engines)

    ListRow(
        null, tr(R.string.voice_tts_engine), first = true, onClick = { enginesOpen = true },
        modifier = Modifier.testTag("tts-engine"),
    ) {
        RowValue(if (engine.isBlank()) tr(R.string.voice_engine_default) else labels[engine] ?: engine)
        RowChevron()
    }
    if (enginesOpen) {
        val actions = listOf(SheetAction(tr(R.string.voice_engine_default)) {
            if (chooseSystemTtsEngine(voiceModels.tts, settings, "")) onChanged()
        }) + engines.map { info ->
            SheetAction(labels[info.packageName] ?: info.label) {
                if (chooseSystemTtsEngine(voiceModels.tts, settings, info.packageName)) onChanged()
            }
        }
        ActionSheet(tr(R.string.voice_tts_engine), actions) { enginesOpen = false }
    }

    val current = voices?.firstOrNull { it.name == state.voice }
    ListRow(null, tr(R.string.voice_voice), onClick = { voicesOpen = true }, modifier = Modifier.testTag("tts-voice")) {
        RowValue(if (state.voice.isBlank()) tr(R.string.voice_voice_default) else current?.displayName ?: state.voice)
        RowChevron()
    }
    if (voicesOpen) {
        val actions = listOf(SheetAction(tr(R.string.voice_voice_default), searchable = false) {
            settings.setVoice("")
            onChanged()
        }) + voices.orEmpty().map { voice ->
            SheetAction(
                voice.displayName,
                trailing = onAudition?.let { play -> SheetTrailing(MikaIcons.Sound, tr(R.string.voice_audition, voice.displayName)) { play(voice) } },
            ) {
                settings.setVoice(voice.name)
                onChanged()
            }
        }
        ActionSheet(tr(R.string.voice_voice), actions, searchHint = tr(R.string.search)) {
            voicesOpen = false
            onVoicesClosed()
        }
    }
}
