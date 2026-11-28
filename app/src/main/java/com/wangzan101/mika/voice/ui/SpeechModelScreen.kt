package com.wangzan101.mika.voice.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.CapabilityTag
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechOptions
import com.wangzan101.mika.voice.SystemSpeech
import com.wangzan101.mika.voice.DoubaoCredentials
import com.wangzan101.mika.voice.DoubaoProtocol
import com.wangzan101.mika.voice.BailianProtocol
import com.wangzan101.mika.voice.BailianRegion
import com.wangzan101.mika.voice.FunAsrProtocol
import com.wangzan101.mika.voice.LiveAsrTransport
import com.wangzan101.mika.voice.probeLiveAsr
import com.wangzan101.mika.voice.model.SttSelection
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelRef
import com.wangzan101.mika.voice.model.VoiceCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.settings.ui.models.ListState
import com.wangzan101.mika.settings.ui.models.ManualModel
import com.wangzan101.mika.settings.ui.models.ModelIdStyle
import com.wangzan101.mika.settings.ui.models.ModelSetupCopy
import com.wangzan101.mika.settings.ui.models.ModelSetupUi
import com.wangzan101.mika.settings.ui.models.ModelSetupViewModel
import com.wangzan101.mika.settings.ui.models.ModelsCopy
import com.wangzan101.mika.settings.ui.models.Page
import com.wangzan101.mika.settings.ui.models.clearSpeechProbe
import com.wangzan101.mika.settings.ui.models.edit
import com.wangzan101.mika.settings.ui.models.ensureListed
import com.wangzan101.mika.settings.ui.models.pick
import com.wangzan101.mika.settings.ui.models.pickSpeech
import com.wangzan101.mika.settings.ui.models.pickSpeechModel
import com.wangzan101.mika.settings.ui.models.pickSttModel

/** Actions on the picker page. */
class SpeechPickActions(
    /** "Phone built-in" or "Off": takes effect immediately and returns. */
    val pick: (SpeechChoice) -> Unit = {},
    /** A catalog model after migration: only the reference is saved, no network access. */
    val pickRef: (VoiceModelRef) -> Unit = {},
    /** (provider id, model name): tests transcription first and only selects it and returns if that works; both list picks and manual entries go through here. */
    val pickModel: (String, String) -> Unit = { _, _ -> },
    /** Opens the Doubao streaming recognition setup page. */
    val openDoubao: () -> Unit = {},
    /** (API Key, App ID, Access Token, billing mode): stores the credentials encrypted and selects it; no network access. */
    val saveDoubao: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    /** Reuses the stored credentials, switches to this billing mode and selects it. */
    val useDoubao: (String) -> Unit = {},
    val clearDoubao: () -> Unit = {},
    /** Opens the setup page for self-hosted Fun-ASR / Bailian. */
    /** Opens the "Qwen live speech recognition" setup page (three tabs: self-hosted, Bailian Beijing, Bailian Singapore). */
    val openFunAsr: () -> Unit = {},
    /** Reuses the previous kind (the self-hosted address, or a Bailian model in some region) and selects it right away. */
    val useFunAsr: () -> Unit = {},
)

/**
 * Settings › Models › Speech to text: carries its own model state and can be entered directly from elsewhere (e.g. the composer's "voice input not available yet").
 * Once chosen (phone built-in, off, or a provider model whose test transcription passed), it returns to the page it was entered from.
 */
@Composable
fun SpeechModelScreen(
    store: ProviderStore, onBack: () -> Unit, onOpenServices: (() -> Unit)? = null, onOpenVoicePacks: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = (context.applicationContext as? MikaApp)?.speech
    val voiceModels = remember { VoiceModelEnvironment.get(context) }
    var doubaoConfigured by remember { mutableStateOf(settings?.hasDoubaoCredentials == true) }
    // The Doubao setup page is stacked on top of this page; the back key closes it first
    var doubaoOpen by remember { mutableStateOf(false) }
    // Stored content of the three "Qwen live speech recognition" tabs; reread after saving or deleting
    fun funAsrSaved() = settings?.let { s ->
        FunAsrSaved(
            s.funAsrUrl, BailianRegion.entries.filter(s::hasBailianKey).toSet(),
            BailianRegion.entries.associateWith(s::bailianModel), BailianRegion.entries.associateWith(s::bailianWorkspace),
            s.funAsrLanguage, BailianRegion.entries.associateWith(s::bailianLanguage),
        )
    } ?: FunAsrSaved()
    var funAsrSaved by remember { mutableStateOf(funAsrSaved()) }
    var funAsrLast by remember { mutableStateOf(settings?.lastLive) }
    var funAsrOpen by remember { mutableStateOf(false) }
    var liveProbe by remember { mutableStateOf(LiveProbeUi()) }
    // Connect once and run through the opening and closing; only store if it works, and stay on the setup page showing the reason on failure
    fun probeThen(protocol: com.wangzan101.mika.voice.LiveAsrProtocol, url: String, headers: Map<String, String>, save: () -> Unit) {
        if (liveProbe.testing) return
        liveProbe = LiveProbeUi(testing = true)
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { probeLiveAsr(protocol, { LiveAsrTransport().connect(url, headers) }) }.exceptionOrNull()
            }
            liveProbe = if (error == null) {
                runCatching(save).exceptionOrNull()?.let { LiveProbeUi(error = tr(R.string.speech_doubao_save_failed)) } ?: LiveProbeUi()
            } else LiveProbeUi(error = SpeechOptions.probeFailure(error))
        }
    }
    val vm = remember {
        ModelSetupViewModel(
            store, scope,
            speech = settings,
            systemSpeech = {
                val silent = settings?.systemSilent?.value ?: false
                SystemSpeech.capability(context.applicationContext, silent)
            },
            stt = voiceModels.stt,
        )
    }
    val ui by vm.state.collectAsState()
    val probe by vm.speechProbe.collectAsState()
    LaunchedEffect(ui.sttCatalog != null) {
        if (ui.sttCatalog == null) vm.ensureListed()
        vm.clearSpeechProbe()
    }
    val actions = SpeechPickActions(
        pick = { vm.pickSpeech(it); onBack() },
        pickRef = { vm.pickSttModel(it) },
        pickModel = { pid, model -> vm.pickSpeechModel(pid, model, onPicked = onBack) },
        openDoubao = { doubaoOpen = true },
        // After saving, return to the speech-to-text page where it can be seen as selected
        saveDoubao = { key, appId, token, resource ->
            checkNotNull(settings).saveDoubao(DoubaoCredentials(key, appId, token), resource)
            doubaoConfigured = true
            vm.pickSpeech(SpeechChoice.Doubao(resource))
            doubaoOpen = false
        },
        useDoubao = { resource -> vm.pickSpeech(SpeechChoice.Doubao(resource)); doubaoOpen = false },
        clearDoubao = {
            settings?.clearDoubao()
            doubaoConfigured = false
            settings?.let { vm.pickSpeech(it.choice.value) }
            doubaoOpen = false
        },
        openFunAsr = { liveProbe = LiveProbeUi(); funAsrOpen = true },
        // Reuse the previous kind (self-hosted, or a Bailian region); open the setup page when it cannot be used
        useFunAsr = {
            when (val last = settings?.lastLive) {
                is SpeechChoice.FunAsr -> { settings.saveFunAsr(last.url, last.language); vm.pickSpeech(settings.choice.value) }
                is SpeechChoice.Bailian -> { settings.saveBailian("", last); vm.pickSpeech(settings.choice.value) }
                else -> { liveProbe = LiveProbeUi(); funAsrOpen = true }
            }
        },
    )
    fun funAsrDone() {
        funAsrSaved = funAsrSaved(); funAsrLast = settings?.lastLive
        settings?.let { vm.pickSpeech(it.choice.value) }
    }
    when {
        doubaoOpen -> {
            BackHandler { doubaoOpen = false }
            DoubaoSetupPage(ui.speech, doubaoConfigured, actions) { doubaoOpen = false }
        }
        funAsrOpen -> {
            BackHandler { funAsrOpen = false }
            FunAsrSetupPage(
                FunAsrTab.of(ui.speech.takeIf { it is SpeechChoice.FunAsr || it is SpeechChoice.Bailian } ?: funAsrLast), funAsrSaved, liveProbe,
                onTab = { if (!liveProbe.testing) liveProbe = LiveProbeUi() },
                onSaveLocal = { url, language ->
                    probeThen(FunAsrProtocol(language), url, emptyMap()) {
                        checkNotNull(settings).saveFunAsr(url, language)
                        funAsrDone(); funAsrOpen = false
                    }
                },
                onSaveBailian = { input ->
                    val usable = input.key.ifBlank { settings?.bailianKey(input.region).orEmpty() }
                    if (usable.isBlank()) liveProbe = LiveProbeUi(error = tr(R.string.speech_live_auth))
                    else probeThen(BailianProtocol(input.model, input.language), input.region.endpoint(input.workspace), mapOf("Authorization" to "Bearer $usable")) {
                        checkNotNull(settings).saveBailian(input.key, SpeechChoice.Bailian(input.model, input.region, input.workspace, input.language))
                        funAsrDone(); funAsrOpen = false
                    }
                },
                onClearBailian = { region -> settings?.clearBailian(region); funAsrDone() },
            ) { funAsrOpen = false }
        }
        ui.sttCatalog != null -> MigratedSpeechModelContent(ui, actions, onBack, onOpenServices, onOpenVoicePacks)
        else -> SpeechModelContent(ui, probe, actions, onBack, doubaoConfigured, funAsrLast != null)
    }
}

/** After migration only catalog models are picked; the system health note is kept, and picking a model itself sends no request. */
@Composable
private fun MigratedSpeechModelContent(
    ui: ModelSetupUi, actions: SpeechPickActions, onBack: () -> Unit, onOpenServices: (() -> Unit)?,
    onOpenVoicePacks: (() -> Unit)?,
) {
    val context = LocalContext.current
    val environment = remember { VoiceModelEnvironment.get(context) }
    val voiceVm = rememberVoiceCatalogViewModel(environment)
    // Files may have been deleted elsewhere (or finished downloading): recheck "Needs download" when returning to this page
    val resumed = rememberResumeCount()
    val packsAvailable = remember { environment.hasRunnableVoicePacks() }
    val catalog = requireNotNull(ui.sttCatalog)
    val system = ui.systemSpeech
    val selection = catalog.selections.stt
    val systemSelected = system.usable &&
        (selection == SttSelection.Model(SttSelectionController.SYSTEM_STT_REF) || selection == SttSelection.Auto)
    val c = MikaTheme.colors
    Page(tr(R.string.voice_hub_stt), onBack) {
        Text(
            tr(R.string.speech_hint), Modifier.padding(top = 4.dp, bottom = 12.dp), style = MikaType.Body, color = c.ink2,
        )
        GroupCard {
            RadioRow(
                tr(R.string.speech_system), selected = systemSelected, enabled = system.available,
                onClick = { actions.pick(SpeechChoice.System) }, first = true,
                subtitle = tr(
                    when {
                        !system.available -> R.string.speech_system_none_note
                        !system.responsive -> R.string.speech_system_silent_note
                        system.onDevice -> R.string.speech_system_note
                        else -> R.string.speech_system_note_online
                    },
                ),
                trailing = {
                    when {
                        system.usable -> StatusPill(tr(R.string.speech_tag_ok), PillTone.Ok)
                        system.available -> StatusPill(tr(R.string.speech_tag_silent), PillTone.Neutral)
                        else -> StatusPill(tr(R.string.speech_tag_none), PillTone.Neutral)
                    }
                },
            )
            RadioRow(
                tr(R.string.models_off), selected = selection == SttSelection.Off,
                onClick = { actions.pick(SpeechChoice.Off) }, note = tr(R.string.speech_off_note),
            )
        }
        val choices = voiceOptions(VoiceCapability.STT, catalog, ui.providers).filter { it.source != VoiceSource.Device }
        if (choices.isNotEmpty()) {
            GroupLabel(tr(R.string.voice_choices_label))
            VoiceOptionList(
                choices, (selection as? SttSelection.Model)?.ref, tr(R.string.voice_try_stt), voiceVm,
                onPick = { choice -> actions.pickRef(voiceVm.ensureInCatalog(choice)) },
                onTest = { choice -> voiceVm.tryChoice(choice, "") },
                badge = voicePackBadge(environment, catalog, resumed),
            )
        }
        if (packsAvailable && onOpenVoicePacks != null) {
            GroupCard(Modifier.padding(top = 4.dp)) {
                ListRow(
                    MikaIcons.Download, tr(R.string.voice_packs_title), tr(R.string.voice_packs_entry_sub), first = true,
                    onClick = onOpenVoicePacks, modifier = Modifier.testTag("speech-voice-packs"),
                ) { RowChevron() }
            }
        }
        VoiceFallbackSection(VoiceCapability.STT, catalog, ui.providers, voiceVm)
        onOpenServices?.let { VoiceMoreServices(it) }
        FooterNote(tr(R.string.voice_stt_footer), Modifier.padding(top = 8.dp))
    }
}

/** A stateless picker page for tests: a note at the top, "phone built-in" and "off", then radio rows grouped by provider. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeechModelContent(
    ui: ModelSetupUi, probe: Map<String, String>, actions: SpeechPickActions, onBack: () -> Unit, doubaoConfigured: Boolean = false,
    funAsrConfigured: Boolean = false,
) {
    val c = MikaTheme.colors
    val system = ui.systemSpeech
    val choice = ui.speech
    // With no choice made, the phone's built-in is in use, so "no choice" still marks it as selected
    // When it was recorded as unresponsive it is not marked selected; the row stays tappable, and picking it again clears the mark and retries
    val systemSelected = system.usable && (choice == SpeechChoice.System || choice == SpeechChoice.Auto)
    Page(tr(R.string.role_speech), onBack) {
        Text(
            tr(R.string.speech_hint), Modifier.padding(top = 4.dp, bottom = 12.dp), style = MikaType.Body, color = c.ink2,
        )
        GroupCard {
            RadioRow(
                tr(R.string.speech_system), selected = systemSelected, enabled = system.available,
                onClick = { actions.pick(SpeechChoice.System) }, first = true,
                subtitle = tr(
                    when {
                        !system.available -> R.string.speech_system_none_note
                        !system.responsive -> R.string.speech_system_silent_note
                        system.onDevice -> R.string.speech_system_note
                        else -> R.string.speech_system_note_online
                    },
                ),
                trailing = {
                    when {
                        system.usable -> StatusPill(tr(R.string.speech_tag_ok), PillTone.Ok)
                        system.available -> StatusPill(tr(R.string.speech_tag_silent), PillTone.Neutral)
                        else -> StatusPill(tr(R.string.speech_tag_none), PillTone.Neutral)
                    }
                },
            )
            RadioRow(
                tr(R.string.models_off), selected = choice == SpeechChoice.Off, onClick = { actions.pick(SpeechChoice.Off) },
                note = tr(R.string.speech_off_note),
            )
        }
        LiveRows(choice, doubaoConfigured, funAsrConfigured, actions)
        SpeechOptions.groups(ui.providers).forEach { g ->
            val p = g.provider
            val testing = ui.speechTesting == p.id
            GroupLabel(ModelsCopy.providerName(p))
            // A manually entered model that is in use is not in the list, so a row is added and marked selected to show which one is in use
            val picked = (choice as? SpeechChoice.Provider)
                ?.takeIf { it.providerId == p.id }?.model
            val rows = if (picked != null && picked !in g.models) g.models + picked else g.models
            GroupCard {
                rows.forEachIndexed { i, id ->
                    RadioRow(
                        id, selected = id == picked, onClick = { actions.pickModel(p.id, id) }, first = i == 0,
                        titleStyle = ModelIdStyle, enabled = ui.speechTesting == null,
                        trailing = { CapabilityTag(tr(R.string.speech_tag_speech)) },
                    )
                }
                // Models missing from the list (incomplete list, or the vendor gives none) can be typed in
                Box(Modifier.testTag("speech-manual-${p.name}")) {
                    ManualModel(p, if (testing) ListState.Running else null, first = rows.isEmpty()) { actions.pickModel(p.id, it) }
                }
            }
            when {
                probe[p.id] != null -> {
                    // With a long list the reason falls off screen and tapping looks like nothing happened: scroll it into view when it appears
                    val why = probe.getValue(p.id)
                    val into = remember { BringIntoViewRequester() }
                    LaunchedEffect(why) { into.bringIntoView() }
                    FooterNote(why, Modifier.bringIntoViewRequester(into).testTag("speech-pick-failed"), color = c.bad)
                }
                testing && rows.isNotEmpty() -> FooterNote(ModelSetupCopy.TESTING, Modifier.testTag("speech-pick-running"))
            }
        }
        FooterNote(tr(R.string.speech_footer), Modifier.padding(top = 8.dp))
    }
}

/**
 * The "live speech services" group: one row each for Doubao and "Qwen live speech recognition" (self-hosted, Bailian Beijing or Bailian Singapore, tabbed inside its setup page).
 * When not configured, tapping the row opens the setup page; once configured, tapping the circle uses it (Fun-ASR reuses the previous kind), and "Settings" on the right opens it for editing or removal.
 * Credentials are stored separately from chat providers, models are not listed and no test transcription is run.
 */
@Composable
private fun LiveRows(choice: SpeechChoice, doubao: Boolean, funAsr: Boolean, actions: SpeechPickActions) {
    GroupLabel(tr(R.string.speech_doubao_group))
    GroupCard {
        val plan = (choice as? SpeechChoice.Doubao)?.resourceId ?: DoubaoProtocol.DURATION
        val doubaoSelected = doubao && choice is SpeechChoice.Doubao
        LiveServiceRow(
            tr(R.string.speech_doubao_model),
            if (doubaoSelected) tr(R.string.speech_doubao_configured, tr(planName(plan))) else tr(R.string.speech_doubao_note),
            selected = doubaoSelected, configured = doubao, first = true, tag = "speech-doubao-edit",
            onPick = { actions.pick(SpeechChoice.Doubao(plan)) }, onOpen = actions.openDoubao,
        )
        val using = when (choice) {
            is SpeechChoice.FunAsr -> funAsrTabName(FunAsrTab.Local) + " · " + FunAsrProtocol.hostOf(choice.url)
            is SpeechChoice.Bailian -> funAsrTabName(FunAsrTab.of(choice)) + " · " + choice.model
            else -> null
        }
        LiveServiceRow(
            tr(R.string.speech_funasr_service), using ?: tr(R.string.speech_funasr_choose_note),
            selected = using != null, configured = funAsr, first = false, tag = "speech-funasr-edit",
            onPick = actions.useFunAsr, onOpen = actions.openFunAsr,
        )
    }
}

private fun planName(resource: String): Int =
    if (resource == DoubaoProtocol.CONCURRENT) R.string.speech_doubao_concurrent else R.string.speech_doubao_duration

/**
 * The Doubao streaming recognition setup page: enter credentials (the new console's API Key, or the legacy App ID + Access Token) and pick a billing mode.
 * Saving does no network access; stored credentials are not filled back in and the input fields do not enter a recoverable state. Changing only the billing mode without re-entering credentials reuses the stored ones.
 */
@Composable
fun DoubaoSetupPage(choice: SpeechChoice, configured: Boolean, actions: SpeechPickActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    var legacy by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var resource by remember { mutableStateOf((choice as? SpeechChoice.Doubao)?.resourceId ?: DoubaoProtocol.DURATION) }
    var failed by remember { mutableStateOf(false) }
    val credentials = DoubaoCredentials(if (legacy) "" else key.trim(), if (legacy) appId.trim() else "", if (legacy) token.trim() else "")
    val unchanged = key.isBlank() && appId.isBlank() && token.isBlank()
    Page(tr(R.string.speech_doubao_model), onBack) {
        Text(tr(R.string.speech_doubao_setup_hint), Modifier.padding(top = 4.dp, bottom = 4.dp), style = MikaType.Body, color = c.ink2)
        GroupLabel(tr(R.string.speech_doubao_credentials))
        SegmentedControl(
            listOf(tr(R.string.speech_doubao_new), tr(R.string.speech_doubao_legacy)), if (legacy) 1 else 0,
            onSelect = { legacy = it == 1; key = ""; appId = ""; token = "" },
            Modifier.padding(bottom = 8.dp),
        )
        GroupCard {
            if (legacy) {
                InputRow("App ID", appId, { appId = it }, tr(R.string.speech_doubao_enter_app), first = true, labelWidth = 110.dp)
                InputRow("Access Token", token, { token = it }, tr(R.string.speech_doubao_enter_key), secret = true, labelWidth = 110.dp)
            } else {
                InputRow("API Key", key, { key = it }, tr(R.string.speech_doubao_enter_key), first = true, secret = true, labelWidth = 110.dp)
            }
        }
        GroupLabel(tr(R.string.speech_doubao_plan))
        GroupCard {
            RadioRow(
                tr(R.string.speech_doubao_duration), selected = resource == DoubaoProtocol.DURATION, first = true,
                onClick = { resource = DoubaoProtocol.DURATION }, note = tr(R.string.speech_doubao_duration_note),
            )
            RadioRow(
                tr(R.string.speech_doubao_concurrent), selected = resource == DoubaoProtocol.CONCURRENT,
                onClick = { resource = DoubaoProtocol.CONCURRENT }, note = tr(R.string.speech_doubao_concurrent_note),
            )
        }
        FooterNote(tr(R.string.speech_doubao_privacy), Modifier.padding(top = 8.dp))
        MikaButton(
            tr(R.string.speech_doubao_save),
            onClick = {
                failed = runCatching {
                    if (configured && unchanged) actions.useDoubao(resource)
                    else actions.saveDoubao(credentials.apiKey, credentials.appId, credentials.accessKey, resource)
                }.isFailure
                key = ""; appId = ""; token = ""
            },
            Modifier.fillMaxWidth().padding(top = 8.dp).testTag("speech-doubao-save"),
            enabled = credentials.valid || configured && unchanged,
        )
        if (failed) FooterNote(tr(R.string.speech_doubao_save_failed), Modifier.padding(top = 8.dp), color = c.bad)
        if (configured) {
            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                Text(
                    tr(R.string.speech_doubao_remove),
                    Modifier.clip(MikaShapes.Pill).clickable(onClick = actions.clearDoubao).padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("speech-doubao-remove"),
                    style = MikaType.Body, color = c.bad,
                )
            }
        }
    }
}
