package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.model.oauth.OpenRouterAuth
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.voice.SystemSpeech
import com.wangzan101.mika.settings.ChatGptSignIns
import com.wangzan101.mika.i18n.tr
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelContextSettings
import com.wangzan101.mika.settings.DecisionSettings
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.voice.model.VoiceCatalogSnapshot
import com.wangzan101.mika.settings.VoiceSettings
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.ProgressDots
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.settings.ui.JevSettingsScreen
import com.wangzan101.mika.settings.ui.UsageAllowanceCard
import com.wangzan101.mika.settings.ui.UsageCopy
import com.wangzan101.mika.setup.ui.Onboarding
import com.wangzan101.mika.setup.ui.OnboardingStep
import com.wangzan101.mika.ui.NavSlide
import com.wangzan101.mika.ui.Route
import com.wangzan101.mika.ui.openInBrowser
import com.wangzan101.mika.voice.ui.VoiceHubSection
import com.wangzan101.mika.voice.ui.voiceState

/**
 * Model setup: two blocks, roles (main model, vision model, light model) and providers. During first-time onboarding [onboarding] is true
 * (providers come first and the bottom has progress dots and "Next"); Settings › Models ([ModelsScreen]) is this same page with a different title and lead text and no bottom button.
 * Role selection, provider details and a provider's all-models list are sub-pages of this page. During onboarding "Next" is only enabled once the main model is chosen, and tapping it calls [onDone].
 */
@Composable
fun ModelSetupScreen(
    store: ProviderStore, onboarding: Boolean, onBack: () -> Unit, onDone: () -> Unit,
    /** In settings, the "Speech to text" card opens the picker; the onboarding flow does not pass it, so the card does not appear. */
    onOpenSpeech: (() -> Unit)? = null,
    /** In settings, the "Voice model" card opens its settings page; the onboarding flow does not pass it, so the card does not appear. */
    onOpenVoice: (() -> Unit)? = null,
    /** In settings, the "Image model" card opens its settings page; the onboarding flow does not pass it, so the card does not appear. */
    onOpenImage: (() -> Unit)? = null,
    /** In settings, view Token usage; the onboarding flow does not pass it. */
    onOpenUsage: (() -> Unit)? = null,
    /** In settings, open the unified voice model catalog; the onboarding flow does not pass it. */
    onOpenVoiceCatalog: (() -> Unit)? = null,
    /** In settings, open the "Voice call" picker; the onboarding flow does not pass it. */
    onOpenCallMode: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val vm = remember {
        val signIns = (context.applicationContext as? MikaApp)?.chatGptSignIn ?: ChatGptSignIns(scope)
        val voiceModels = com.wangzan101.mika.voice.model.VoiceModelEnvironment.get(context.applicationContext)
        val voiceAccounts = voiceModels.credentialStore?.let {
            com.wangzan101.mika.voice.model.VoiceAccountManager(voiceModels.catalog, it)
        }
        ModelSetupViewModel(
            store, scope, browser = AndroidSignInBrowser(context.applicationContext), signIns = signIns,
            speech = (context.applicationContext as? MikaApp)?.speech,
            systemSpeech = {
                val silent = (context.applicationContext as? MikaApp)?.speech?.systemSilent?.value ?: false
                SystemSpeech.capability(context.applicationContext, silent)
            },
            voiceAccounts = voiceAccounts,
        )
    }
    val ui by vm.state.collectAsState()
    val voiceSettings = if (onOpenVoice != null) (context.applicationContext as? VoiceSettings.Owner)?.voiceSettings else null
    val voiceState = voiceSettings?.state?.collectAsState()?.value
    val voiceChoice = voiceState?.choice
    // Read-aloud/call choices changed on a sub-page must be visible here immediately, so follow the catalog directly instead of waiting for the vm's next sync
    val liveVoiceCatalog = if (onOpenCallMode != null) {
        remember { com.wangzan101.mika.voice.model.VoiceModelEnvironment.get(context) }.catalog.state.collectAsState().value
    } else null
    var picking by rememberSaveable { mutableStateOf<ModelRole?>(null) }
    var pickingFallbackRole by rememberSaveable { mutableStateOf<ModelRole?>(null) }
    var pickingFallbackSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    var groupsOpen by rememberSaveable { mutableStateOf(false) }
    // "" means creating a new group, null means not editing a group
    var groupEditing by rememberSaveable { mutableStateOf<String?>(null) }
    var openProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var allModels by rememberSaveable { mutableStateOf(false) }
    var openDecision by rememberSaveable { mutableStateOf(false) }
    var showProviders by rememberSaveable { mutableStateOf(false) }
    // Account sign-in panel: it can be opened from both the add form and provider details, and closing it mid sign-in does not interrupt the sign-in
    var signInSheet by rememberSaveable { mutableStateOf<SignInKind?>(null) }

    // Account sign-in succeeded: close the panel and open this provider's details (see the account and models, set the main model)
    LaunchedEffect(ui.signedInProvider) {
        val id = ui.signedInProvider ?: return@LaunchedEffect
        signInSheet = null
        if (picking == null && pickingFallbackSlot == null) {
            openProvider = id
            allModels = false
            showProviders = false
        }
        vm.signedInShown()
    }
    // A successful ChatGPT sign-in stays on this page as before (the first time it explains that plan quota is used) and the panel closes
    LaunchedEffect(ui.chatGptAccount?.signedIn, ui.chatGptSignIn) {
        if (signInSheet == SignInKind.ChatGpt && ui.chatGptAccount?.signedIn == true && ui.chatGptSignIn == ListState.Idle) signInSheet = null
    }

    val provider = openProvider?.let { id -> ui.providers.firstOrNull { it.id == id } }
    val role = picking
    val actions = ModelSetupActions(
        selectPreset = vm::selectPreset, setKind = vm::setKind, setBaseUrl = vm::setBaseUrl, setAccountId = vm::setAccountId, setApiKey = vm::setApiKey,
        setModel = vm::setModel, setManual = vm::setManual, setName = vm::setName,
        testAndSave = vm::testAndSave,
        saveUntested = vm::saveWithoutTest,
        openForm = vm::openForm, closeForm = vm::closeForm,
        // Keep showProviders when opening details from the providers page, so Back returns to the providers page and not the model page
        openProvider = {
            if (ui.formOpen) vm.closeForm()
            openProvider = it
            allModels = false
        }, pickRole = { picking = it },
        pickFallback = { pickingFallbackRole = ModelRole.Main; pickingFallbackSlot = it },
        pickRoleFallback = { r, s -> pickingFallbackRole = r; pickingFallbackSlot = s },
        clearFallback = vm::clearFallback,
        openProviders = { showProviders = true },
        openGroups = { groupsOpen = true },
        next = onDone, openSpeech = onOpenSpeech,
        openVoice = onOpenVoice, openUsage = onOpenUsage,
        openVoiceCatalog = onOpenVoiceCatalog, openCallMode = onOpenCallMode,
        openDecision = { openDecision = true }, openSignIn = { signInSheet = it },
        setReasoningEffort = vm::setReasoningEffort, setModelContext = vm::setModelContext,
    )
    // Internal sub-pages: stable identity (data changes do not replay the animation) and a depth level (which decides the slide direction)
    val origin: ModelSubPage = when {
        openDecision -> ModelSubPage.Decision
        groupEditing != null -> ModelSubPage.GroupEdit(groupEditing ?: "")
        groupsOpen -> ModelSubPage.Groups
        provider != null && allModels -> ModelSubPage.AllModels(provider.id, showProviders)
        provider != null -> ModelSubPage.Detail(provider.id, showProviders)
        showProviders -> ModelSubPage.Providers
        pickingFallbackSlot != null -> ModelSubPage.FallbackPick(pickingFallbackRole ?: ModelRole.Main, pickingFallbackSlot!!)
        role != null -> ModelSubPage.RolePick(role)
        else -> ModelSubPage.Main
    }
    val subPage = if (ui.formOpen) ModelSubPage.AddProvider(origin.depth + 1) else origin
    // The old page still renders during the page-change animation: when a provider was just deleted, the details page can still draw from cached data and then exit
    var heldProviders by remember { mutableStateOf(ui.providers) }
    if (ui.providers.isNotEmpty()) heldProviders = ui.providers
    NavSlide(subPage, depth = { it.depth }) { page ->
    when (page) {
        is ModelSubPage.AddProvider -> {
            BackHandler(onBack = vm::closeForm)
            Page(ModelsCopy.ADD_PROVIDER, onBack = vm::closeForm) {
                AddProviderForm(ui, actions, standalone = true)
            }
        }
        is ModelSubPage.Decision -> JevSettingsScreen(remember { DecisionSettings.get(context) }, onBack = { openDecision = false })
        is ModelSubPage.Detail, is ModelSubPage.AllModels -> {
            val pid = if (page is ModelSubPage.Detail) page.id else (page as ModelSubPage.AllModels).id
            val p = ui.providers.firstOrNull { it.id == pid } ?: heldProviders.firstOrNull { it.id == pid }
            if (p != null) {
                LaunchedEffect(p.id) { vm.ensureListed() }
                val accountKind = when {
                    p.kind == ProviderConfig.Kind.Xai -> SignInKind.Xai
                    p.signIn == ProviderStore.OPENROUTER_SIGN_IN -> SignInKind.OpenRouter
                    else -> null
                }
                val detailActions = ProviderDetailActions(
                    refresh = { vm.refresh(p.id) }, changeKey = { vm.changeKey(p.id, it) },
                    addKey = { vm.addProviderKey(p.id, it) },
                    removeKey = { vm.removeProviderKey(p.id, it) },
                    remove = { vm.removeProvider(p.id); openProvider = null; allModels = false },
                    use = { r, model, done -> vm.useFromProvider(r, p.id, model, done) },
                    clearPick = vm::clearManualPick,
                    setVision = { model, sees -> vm.setModelVision(p.id, model, sees) },
                    setServiceTier = { model, tier -> vm.setModelServiceTier(p.id, model, tier) },
                    showAll = { allModels = true },
                    retestModel = { model -> vm.retestModel(p.id, model) },
                    voiceUsage = vm::providerVoiceUsage,
                    // ChatGPT still signs in through the browser directly; xAI and OpenRouter open the explanation panel first
                    signIn = { if (accountKind != null) signInSheet = accountKind else vm.signInWithChatGpt() },
                    signOut = { if (accountKind != null) vm.signOutAccount(p.id) else vm.signOutChatGpt(p.id) },
                    manageUsage = {
                        openInBrowser(context, when (accountKind) {
                            SignInKind.Xai -> ModelsCopy.ACCOUNT_MANAGE_URL
                            SignInKind.OpenRouter -> OpenRouterAuth.KEYS_PAGE
                            else -> ChatGptAuth.USAGE_URL
                        })
                    },
                    useAsMain = { vm.useAsMain(p.id) },
                    setDailyBudget = { vm.setDailyTokenBudget(p.id, it) },
                )
                if (page is ModelSubPage.AllModels) {
                    BackHandler { allModels = false }
                    AllModelsContent(ui, p, detailActions, onBack = { allModels = false })
                } else {
                    BackHandler { openProvider = null }
                    ProviderDetailContent(ui, p, detailActions, onBack = { openProvider = null })
                }
            }
        }
        is ModelSubPage.Groups -> {
            BackHandler { groupsOpen = false }
            ModelGroupsScreen(
                ui, onBack = { groupsOpen = false },
                onNew = { groupEditing = "" },
                onEdit = { groupEditing = it },
            )
        }
        is ModelSubPage.GroupEdit -> {
            BackHandler { groupEditing = null }
            ModelGroupEditorScreen(
                ui, page.id.takeIf { it.isNotEmpty() },
                onBack = { groupEditing = null },
                onSave = { vm.saveGroup(it) },
                onDelete = { vm.deleteGroup(it) },
                ensureConfig = { p, m -> vm.ensureConfig(p, m) },
                onSetContext = vm::setModelContext,
            )
        }
        is ModelSubPage.Providers -> {
            BackHandler { showProviders = false }
            Page(ModelsCopy.PROVIDERS, onBack = { showProviders = false }) {
                ProvidersSection(ui, actions)
                // Each provider's current allowance travels with the provider and is not put on the Token usage page
                UsageAllowanceCard(ui.providers, store, onOpenUrl = { openInBrowser(context, it) })
            }
        }
        is ModelSubPage.FallbackPick -> {
            val role = page.role
            val slot = page.slot
            BackHandler { pickingFallbackSlot = null; pickingFallbackRole = null }
            LaunchedEffect(role, slot) { vm.ensureListed(); vm.clearManualPick() }
            RolePickerContent(
                ui, role,
                RolePickerActions(
                    pick = { pid, model ->
                        vm.pickFallback(role, slot, pid, model) {
                            pickingFallbackSlot = null
                            pickingFallbackRole = null
                        }
                    },
                    pickManual = { pid, model ->
                        vm.pickFallbackManual(role, slot, pid, model) {
                            pickingFallbackSlot = null
                            pickingFallbackRole = null
                        }
                    },
                    addProvider = vm::openForm,
                ),
                onBack = { pickingFallbackSlot = null; pickingFallbackRole = null },
                titleOverride = ModelsCopy.fallbackLabel(role, slot),
                hintOverride = "",
                pickedOverride = { pid, model ->
                    ui.roles.fallbacks(role).getOrNull(slot)?.let { id -> ui.configs.firstOrNull { it.id == id } }
                        ?.let { it.providerId == pid && it.model == model } == true
                },
                exclude = { pid, model ->
                    val primaryCfg = ui.configs.firstOrNull { it.id == ui.roles.resolve(role) }
                    primaryCfg != null && primaryCfg.providerId == pid && primaryCfg.model == model
                },
                clearAction = { vm.clearFallback(role, slot); pickingFallbackSlot = null; pickingFallbackRole = null },
                clearSelected = ui.roles.fallbacks(role).getOrNull(slot) == null,
            )
        }
        is ModelSubPage.RolePick -> {
            val role = page.role
            BackHandler { picking = null }
            LaunchedEffect(role) { vm.ensureListed(); vm.clearManualPick() }
            RolePickerContent(
                ui, role,
                RolePickerActions(
                    pick = { pid, model -> vm.pick(role, pid, model) { picking = null } },
                    pickManual = { pid, model -> vm.pickManual(role, pid, model) { picking = null } },
                    choose = { vm.choose(role, it); picking = null },
                    chooseGroup = { vm.chooseGroup(role, it); picking = null },
                    manageGroups = { picking = null; groupsOpen = true },
                    addProvider = vm::openForm,
                ),
                onBack = { picking = null },
            )
        }
        is ModelSubPage.Main -> {
            if (ui.chatGptWelcome) ChatGptWelcome(vm::dismissChatGptWelcome)
            BackHandler(onBack = onBack)
            ModelSetupContent(ui, onboarding, actions, voiceChoice = voiceChoice, readAloudOn = voiceState?.enabled ?: false,
                liveVoiceCatalog = liveVoiceCatalog, onBack = onBack)
        }
    }
    }

    signInSheet?.let { kind ->
        SignInSheet(
            ui, kind,
            SignInSheetActions(
                start = { k -> k.account?.let(vm::signInWithAccount) ?: vm.signInWithChatGpt() },
                cancel = { k -> if (k.account != null) vm.cancelAccountSignIn() else vm.cancelChatGptSignIn() },
                useApiKey = { k ->
                    signInSheet = null
                    vm.openForm()
                    k.keyPreset?.let(vm::selectPreset)
                },
                dismiss = {
                    signInSheet = null
                    // The failure reason has been seen; a sign-in still in progress is not interrupted
                    if (!ui.accountSignIn.running) vm.dismissAccountFailure()
                },
            ),
        )
    }
}

/**
 * Sub-pages inside the model page: identity uses stable values (provider id, role, slot) so data changes do not replay the page-change animation;
 * [depth] decides the slide direction — main page 0, provider list and the pickers 1, details 1 (entered directly from the main page) or 2 (entered from the provider list), and all models one level deeper.
 */
private sealed class ModelSubPage(val depth: Int) {
    data object Main : ModelSubPage(0)
    data object Decision : ModelSubPage(1)
    data object Providers : ModelSubPage(1)
    data object Groups : ModelSubPage(1)
    /** An empty id means creating a new group. */
    data class GroupEdit(val id: String) : ModelSubPage(2)
    data class AddProvider(val level: Int) : ModelSubPage(level)
    data class Detail(val id: String, val fromProviders: Boolean) : ModelSubPage(if (fromProviders) 2 else 1)
    data class AllModels(val id: String, val fromProviders: Boolean) : ModelSubPage(if (fromProviders) 3 else 2)
    data class FallbackPick(val role: ModelRole, val slot: Int) : ModelSubPage(1)
    data class RolePick(val role: ModelRole) : ModelSubPage(1)
}

/** All actions available on the main page. */
class ModelSetupActions(
    val selectPreset: (String) -> Unit = {},
    val setKind: (ProviderConfig.Kind) -> Unit = {},
    val setBaseUrl: (String) -> Unit = {},
    val setAccountId: (String) -> Unit = {},
    val setApiKey: (String) -> Unit = {},
    val setModel: (String) -> Unit = {},
    /** Turns "enter model name manually" on and off. */
    val setManual: (Boolean) -> Unit = {},
    val setName: (String) -> Unit = {},
    val testAndSave: () -> Unit = {},
    /** Save without testing (see [ModelSetupViewModel.saveWithoutTest]). */
    val saveUntested: () -> Unit = {},
    val openForm: () -> Unit = {},
    val closeForm: () -> Unit = {},
    val openProvider: (String) -> Unit = {},
    val pickRole: (ModelRole) -> Unit = {},
    val pickFallback: (Int) -> Unit = {},
    val pickRoleFallback: (ModelRole, Int) -> Unit = { _, _ -> },
    val clearFallback: (ModelRole, Int) -> Unit = { _, _ -> },
    /** Open the "Providers" page (connected providers plus the add form). */
    val openProviders: () -> Unit = {},
    /** Open the "Speech to text" picker; when null this card is not shown (onboarding flow). */
    val openSpeech: (() -> Unit)? = null,
    /** Open the "Voice model" settings page; when null this card is not shown (onboarding flow). */
    val openVoice: (() -> Unit)? = null,
    /** Open the "Image model" settings page; when null this card is not shown (onboarding flow). */
    val openImage: (() -> Unit)? = null,
    /** Open Token usage; when null the entry point is not shown (onboarding flow). */
    val openUsage: (() -> Unit)? = null,
    /** Open the unified voice model catalog; when null the entry point is not shown (onboarding flow). */
    val openVoiceCatalog: (() -> Unit)? = null,
    /** Open the "Voice call" picker; when null it is not shown (onboarding flow). */
    val openCallMode: (() -> Unit)? = null,
    /** Independent opt-in decision service; the three model roles remain unchanged. */
    val openDecision: (() -> Unit)? = null,
    val next: () -> Unit = {},
    /** Open the explanation panel for signing in to one account. */
    val openSignIn: (SignInKind) -> Unit = {},
    /** Open the model group management page; the onboarding flow does not show the entry point. */
    val openGroups: () -> Unit = {},
    val setReasoningEffort: (ModelRole, String?) -> Unit = { _, _ -> },
    val setModelContext: (String, ModelContextSettings) -> Boolean = { _, _ -> false },
)

/**
 * Stateless main page, for easy testing. Changes take effect immediately (a passed test saves the provider, and a role applies as soon as it is chosen),
 * so when entered from settings the page only has Back; during onboarding "Next" sits at the bottom and hides when the keyboard appears to give the form more room.
 */
@Composable
fun ModelSetupContent(
    ui: ModelSetupUi, onboarding: Boolean, actions: ModelSetupActions,
    voiceChoice: VoiceChoice? = null, readAloudOn: Boolean = false,
    liveVoiceCatalog: VoiceCatalogSnapshot? = null, onBack: () -> Unit,
) {
    val c = MikaTheme.colors
    // Once the form is closed (saved after a passed test, or cancelled) nothing on the page needs typing, so dismiss the keyboard too
    val focus = LocalFocusManager.current
    LaunchedEffect(ui.showForm) { if (!ui.showForm) focus.clearFocus() }
    val onPick = actions.pickRole
    val scrollState = rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(initial = 0)
    }
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(if (onboarding) ModelSetupCopy.TITLE else ModelsCopy.TITLE, MikaIcons.Back, onBack, stringResource(R.string.back))
            Column(Modifier.weight(1f).verticalScroll(scrollState).padding(horizontal = 16.dp)) {
                if (onboarding) {
                    Text(
                        ModelSetupCopy.LEAD, Modifier.padding(top = 4.dp, bottom = 12.dp),
                        style = MikaType.Body, color = c.ink2,
                    )
                }
                // Onboarding connects providers before choosing models; settings shows roles first
                if (onboarding) {
                    ProvidersSection(ui, actions)
                    // "Context and compaction" is not offered during onboarding; adjust it in settings later
                    RolesSection(ui, onPick, actions.setReasoningEffort)
                    FooterNote(ModelSetupCopy.ONBOARDING_NOTE)
                } else {
                    // Providers entry — show count + total key count in subtitle
                    val totalKeys = ui.providers.sumOf { p ->
                        ui.providerKeyMap[p.id]?.size ?: 0
                    }
                    val providerSubtitle = when {
                        ui.providers.isEmpty() -> "No providers connected"
                        totalKeys > 1 -> "${ui.providers.size} providers · $totalKeys keys in pool"
                        else -> "${ui.providers.size} provider${if (ui.providers.size > 1) "s" else ""}"
                    }
                    GroupCard {
                        ListRow(MikaIcons.Key, ModelsCopy.PROVIDERS, providerSubtitle, first = true, onClick = actions.openProviders) {
                            RowChevron()
                        }
                    }
                    actions.openUsage?.let { openUsage ->
                        GroupCard {
                            ListRow(MikaIcons.Chart, UsageCopy.TITLE, first = true, onClick = openUsage) { RowChevron() }
                        }
                    }
                    // Note for when the provider was saved but the suggested model failed its test, plus "choose main model" (below the providers group during onboarding)
                    ProbeNote(ui, actions)
                    // After migrating to the voice catalog, the three roles and source accounts are consolidated into one "Voice" group; before migration the old scattered entries remain
                    // The settings page's vm carries no voice controller, so migration is read from the catalog itself
                    val voiceCatalog = liveVoiceCatalog?.takeIf { it.legacyMigrationComplete } ?: ui.sttCatalog
                    val hubOpen = voiceCatalog != null && actions.openSpeech != null && actions.openVoice != null &&
                        actions.openCallMode != null && actions.openVoiceCatalog != null
                    if (voiceCatalog != null && hubOpen) {
                        RolesSection(ui, onPick, actions.setReasoningEffort, actions.setModelContext)
                        ImageModelSection(ui, actions.openImage)
                        VoiceHubSection(
                            voiceCatalog, ui.providers, readAloudOn,
                            onStt = actions.openSpeech!!, onTts = actions.openVoice!!,
                            onCallMode = actions.openCallMode!!, onServices = actions.openVoiceCatalog!!,
                        )
                    } else {
                        RolesSection(ui, onPick, actions.setReasoningEffort, actions.setModelContext, actions.openVoice, voiceChoice)
                        SpeechSection(ui, actions.openSpeech)
                        ImageModelSection(ui, actions.openImage)
                        VoiceCatalogSection(actions.openVoiceCatalog)
                    }
                    GroupCard(Modifier.padding(top = 8.dp)) {
                        ListRow(MikaIcons.Leaf, ModelsCopy.GROUPS, first = true, onClick = actions.openGroups) { RowChevron() }
                    }
                    actions.openDecision?.let { openDecision ->
                        GroupCard {
                            ListRow(MikaIcons.Route, stringResource(R.string.jev_title), stringResource(R.string.jev_row_sub),
                                first = true, onClick = openDecision) { RowChevron() }
                        }
                    }
                    FallbackSection(ui, ModelRole.Main, actions.pickRoleFallback, actions.clearFallback)
                    FallbackSection(ui, ModelRole.Vision, actions.pickRoleFallback, actions.clearFallback)
                    FallbackSection(ui, ModelRole.Light, actions.pickRoleFallback, actions.clearFallback)
                }
                Spacer(Modifier.height(16.dp))
            }
            // Measure the height the keyboard actually occupies; isImeVisible defaults to true before the system first reports, which would hide the button by mistake
            if (onboarding && WindowInsets.ime.getBottom(LocalDensity.current) == 0) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                    ProgressDots(Onboarding.DOTS, Onboarding.dot(OnboardingStep.Model), Modifier.padding(bottom = 14.dp))
                    MikaButton(ModelSetupCopy.NEXT, actions.next, Modifier.fillMaxWidth().testTag("setup-next"), enabled = ui.canNext)
                }
            }
        }
    }
}

/** Text for the model setup page (onboarding) and the add-provider form, as pure functions for easy testing. Role wording lives in [ModelsCopy]. */
object ModelSetupCopy {
    val TITLE: String get() = tr(R.string.model_setup_title)
    val LEAD: String get() = tr(R.string.model_setup_lead)
    val ONBOARDING_NOTE: String get() = tr(R.string.model_setup_onboarding_note)
    val CANCEL: String get() = tr(R.string.cancel)
    val KIND: String get() = tr(R.string.model_kind)
    val BASE_URL: String get() = tr(R.string.model_base_url)
    val NAME: String get() = tr(R.string.model_provider_name)
    val NAME_HINT: String get() = tr(R.string.model_provider_name_hint)
    const val API_KEY = "API Key"
    val KEY_HINT: String get() = tr(R.string.model_key_hint)
    val KEY_OPTIONAL: String get() = tr(R.string.model_key_optional)
    /** The large Key input: several keys can be pasted at once, separated by commas or newlines, and they rotate automatically. */
    val KEY_POOL_HINT: String get() = tr(R.string.model_key_pool_hint)
    val KEY_INVISIBLE_REMOVED: String get() = tr(R.string.model_key_invisible_removed)
    val KEY_ILLEGAL: String get() = tr(R.string.model_key_illegal_chars)
    val KEY_WEBSITE: String get() = tr(R.string.model_key_website)
    val TEST_AND_SAVE: String get() = tr(R.string.model_test_save)
    val TESTING: String get() = tr(R.string.model_testing)
    val PICK_MAIN: String get() = tr(R.string.model_pick_main)
    val SELF_HOSTED_NOTE: String get() = tr(R.string.model_self_hosted_note)
    val MODEL_NAME: String get() = tr(R.string.model_manual_name)
    val MANUAL_ON: String get() = tr(R.string.model_manual_on)
    val MANUAL_OFF: String get() = tr(R.string.model_manual_off)
    val SAVE_UNTESTED: String get() = tr(R.string.model_save_untested)
    val SAVE_UNTESTED_TITLE: String get() = tr(R.string.model_save_untested_title)
    val SAVE_UNTESTED_BODY: String get() = tr(R.string.model_save_untested_body)
    val SAVE_UNTESTED_CONFIRM: String get() = tr(R.string.model_save_untested_confirm)

    /** Example given when entering a model name manually, per protocol kind (each provider formats model names differently). */
    fun modelExample(kind: ProviderConfig.Kind): String = tr(
        when (kind) {
            ProviderConfig.Kind.Gemini -> R.string.model_example_gemini
            ProviderConfig.Kind.Anthropic -> R.string.model_example_anthropic
            ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.ChatGpt -> R.string.model_example_openai
            ProviderConfig.Kind.Xai -> R.string.model_example_grok
            ProviderConfig.Kind.OpenAiCompat -> R.string.model_example_compat
        },
    )

/** Suggestion when the custom URL is some provider's official URL; adds a sentence when the protocol does not match that provider. */
    fun presetSuggestion(f: AddForm): String {
        val preset = f.suggestedPreset ?: return ""
        val match = tr(R.string.model_preset_match, preset.label)
        return if (!f.presetKindMismatch) match
        else match + tr(R.string.sentence_sep) + tr(R.string.model_preset_kind_mismatch, preset.label, ModelsCopy.kindLabel(preset.kind), ModelsCopy.kindLabel(f.kind))
    }

    fun usePreset(label: String) = tr(R.string.model_use_preset, label)
    val NEXT: String get() = tr(R.string.next)

    fun failed(reason: String) = tr(R.string.model_failed, reason)

    /** Model capability tags: (text, whether to use the accent color). Tagged "unconfirmed" when the API did not report it, the user did not say, and it was inferred from the preset. For reference only; it never blocks a choice. */
    fun tags(o: ModelOption, forVision: Boolean = false): List<Pair<String, Boolean>> = buildList {
        val caps = o.capabilities
        val visionKnown = o.visionKnown || o.visionByUser != null
        if (o.typed) add(tr(R.string.cap_typed) to false)
        if (forVision) {
            add((if (visionKnown) tr(R.string.cap_vision) else tr(R.string.cap_vision_unconfirmed)) to false)
        } else {
            when {
                !caps.toolCalls -> add(tr(R.string.cap_no_tools) to false)
                o.toolCallsKnown -> add(tr(R.string.cap_tools) to true)
                else -> add(tr(R.string.cap_tools_unconfirmed) to false)
            }
            if (caps.vision && visionKnown) add(tr(R.string.cap_vision) to false)
        }
        if (caps.reasoning) add(tr(R.string.cap_reasoning) to false)
        if (o.contextKnown) add(ModelOptions.contextLabel(caps.contextTokens) to false)
    }
}
