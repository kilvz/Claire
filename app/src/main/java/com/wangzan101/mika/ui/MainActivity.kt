package com.wangzan101.mika.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.lifecycleScope
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.about.DiagnosticsCollector
import com.wangzan101.mika.about.MikaLinks
import com.wangzan101.mika.about.QrImage
import com.wangzan101.mika.about.ReleaseInfo
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.calls.CallStandbyRecoveryJob
import com.wangzan101.mika.calls.activeVoiceProfile
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.cap.phone.setup.PairingNotifier
import com.wangzan101.mika.cap.web.WebHandover
import com.wangzan101.mika.cap.web.WebSites
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.chat.SharedDraft
import com.wangzan101.mika.chat.SharedDraftImporter
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.knowledge.FileRouteStore
import com.wangzan101.mika.settings.ActivityRecentsSystem
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.RecentsExclusion
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.SystemStatusMonitor
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import com.wangzan101.mika.ui.components.glassSource
import com.wangzan101.mika.ui.components.rememberGlassBackdrop
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff
import com.wangzan101.mika.ui.theme.appearanceDark
import com.wangzan101.mika.ui.theme.systemBarsFor
import com.wangzan101.mika.voicecall.ui.VoiceCallDeps
import com.wangzan101.mika.task.screenConversations
import com.wangzan101.mika.voicecall.ui.VoiceCallBar
import com.wangzan101.mika.voicecall.ui.VoiceCallHost
import com.wangzan101.mika.voicecall.ui.VoiceCallScreen
import com.wangzan101.mika.voicecall.ui.liveCall
import com.wangzan101.mika.voicecall.ui.rememberVoiceCallController
import com.wangzan101.mika.voicecall.VoiceCallService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.about.ui.AboutCopy
import com.wangzan101.mika.about.ui.AboutScreen
import com.wangzan101.mika.about.ui.LicensesScreen
import com.wangzan101.mika.about.ui.UpdateMirrorsScreen
import com.wangzan101.mika.about.ui.UpdateReadyCapsule
import com.wangzan101.mika.about.ui.UpdateSheet
import com.wangzan101.mika.browser.ui.BrowserScreen
import com.wangzan101.mika.browser.ui.WebSitesState
import com.wangzan101.mika.calls.ui.CallModeScreen
import com.wangzan101.mika.calls.ui.CallSettingsScreen
import com.wangzan101.mika.chat.ui.AttachFiles
import com.wangzan101.mika.chat.ui.ChatScreen
import com.wangzan101.mika.chat.ui.ChatWithDrawer
import com.wangzan101.mika.chat.ui.ScreensScreen
import com.wangzan101.mika.chat.ui.ShareContent
import com.wangzan101.mika.chat.ui.SourceScreen
import com.wangzan101.mika.chat.ui.SourceViewModel
import com.wangzan101.mika.chat.ui.draft
import com.wangzan101.mika.chat.ui.isShareIntent
import com.wangzan101.mika.chat.ui.readShare
import com.wangzan101.mika.chat.ui.shareCanRead
import com.wangzan101.mika.files.ui.FileLibraryScreen
import com.wangzan101.mika.memory.ui.MemoryDataScreen
import com.wangzan101.mika.memory.ui.MemoryViewModel
import com.wangzan101.mika.memory.ui.Segment
import com.wangzan101.mika.personal.ui.PersonalAgentScreen
import com.wangzan101.mika.settings.ui.Fix
import com.wangzan101.mika.settings.ui.LanguagePromptDialog
import com.wangzan101.mika.settings.ui.PersonaScreen
import com.wangzan101.mika.settings.ui.PhoneSetupScreen
import com.wangzan101.mika.settings.ui.SettingsModelRoute
import com.wangzan101.mika.settings.ui.SettingsScreen
import com.wangzan101.mika.settings.ui.UsageScreen
import com.wangzan101.mika.settings.ui.models.ImageModelScreen
import com.wangzan101.mika.settings.ui.models.VideoModelScreen
import com.wangzan101.mika.settings.ui.models.ModelSetupScreen
import com.wangzan101.mika.settings.ui.models.ModelsScreen
import com.wangzan101.mika.settings.ui.models.refresh
import com.wangzan101.mika.setup.ui.Onboarding
import com.wangzan101.mika.setup.ui.OnboardingState
import com.wangzan101.mika.setup.ui.OnboardingStep
import com.wangzan101.mika.setup.ui.WelcomeScreen
import com.wangzan101.mika.task.ui.PlansScreen
import com.wangzan101.mika.task.ui.RunTimelineScreen
import com.wangzan101.mika.task.ui.RunTimelineViewModel
import com.wangzan101.mika.task.ui.TaskPlaybackScreen
import com.wangzan101.mika.task.ui.TaskReportScreen
import com.wangzan101.mika.task.ui.TaskReportViewModel
import com.wangzan101.mika.teach.ui.TeachScreen
import com.wangzan101.mika.voice.ui.RealtimeVoiceScreen
import com.wangzan101.mika.voice.ui.SpeechModelScreen
import com.wangzan101.mika.voice.ui.VoiceCatalogScreen
import com.wangzan101.mika.voice.ui.VoiceModelScreen
import com.wangzan101.mika.voice.ui.VoiceServicesScreen

class MainActivity : ComponentActivity() {
    private lateinit var monitor: SystemStatusMonitor
    internal lateinit var recentsExclusion: RecentsExclusion
        private set
    private var requestedConversation by mutableStateOf<String?>(null)
    private var requestedCalls by mutableStateOf(false)
    private var requestedCallHistoryId by mutableStateOf<String?>(null)
    private var voiceCallSignal by mutableIntStateOf(0)
    private var requestedCallStandby by mutableStateOf(false)
    private var openTeach by mutableStateOf(false)
    private var requestedSharedDraft by mutableStateOf<SharedDraftRequest?>(null)
    private var shareJob: Job? = null
    private var shareSerial = 0L
    private var shareInCurrent = false
    private var shareHandled = false
    private var verifiedShareUris: List<String> = emptyList()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // When the user has chosen "don't ask again" the system stops showing the prompt, so the only way is to enable it in settings
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings()
        monitor.refresh()
    }

    // Android 12 and earlier: apply the language chosen in the app; from 13 on the system applies it itself
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        recentsExclusion = RecentsExclusion(FeatureFlags.get(this), ActivityRecentsSystem(this))
        recentsExclusion.reapply()
        if (savedInstanceState == null) {
            (application as MikaApp).chat.cancelPendingSharedDraft()
        }
        requestedConversation = intent.getStringExtra(EXTRA_OPEN_CONVERSATION)
        requestedCalls = intent.getBooleanExtra(EXTRA_OPEN_CALLS, false)
        requestedCallHistoryId = intent.getStringExtra(EXTRA_CALL_HISTORY_ID)
        takeVoiceCall(intent)
        takeCallStandby(intent)
        takeTeach(intent)
        shareInCurrent = savedInstanceState?.getBoolean(STATE_SHARE_IN_CURRENT, false) ?: false
        shareHandled = savedInstanceState?.getBoolean(STATE_SHARE_HANDLED, false) ?: false
        verifiedShareUris = savedInstanceState?.getStringArrayList(STATE_VERIFIED_SHARE_URIS).orEmpty()
        // An already handled share is not triggered again on rotation/recreation; a genuine cold-start share or an unfinished background share is still accepted.
        if (!shareHandled && (savedInstanceState == null || isShareIntent(intent))) {
            receiveShare(intent, inCurrentConversation = shareInCurrent)
        }
        monitor = SystemStatusMonitor(this, PhoneServices.of(this).shellControl)
        val store = ProviderStore.get(this)
        setContent {
            // Appearance follows settings and switches immediately; the tint of the system bar icons follows the interface
            val dark = appearanceDark()
            LaunchedEffect(dark) { systemBarsFor(dark) }
            MikaTheme(dark = dark, reduceMotion = animationsOff(this)) {
                // A tap or drag outside the input field dismisses the keyboard, uniformly across the whole app
                KeyboardDismissArea(Modifier.fillMaxSize()) {
                    MikaRoot(
                        store, monitor, ::perform, requestedConversation, { requestedConversation = null },
                        requestedCalls, requestedCallHistoryId, { requestedCalls = false; requestedCallHistoryId = null },
                        voiceCallSignal,
                        requestedSharedDraft,
                        onSharedDraftOpened = { accepted, draft ->
                            requestedSharedDraft = null
                            shareHandled = true
                            // After handling, swap the intent out so a later rotation doesn't treat this share as a new one or show the failure toast twice.
                            setIntent(Intent(this@MainActivity, MainActivity::class.java))
                            if (!accepted) {
                                (application as MikaApp).sharedDrafts.discard(draft.attachments)
                                Toast.makeText(this@MainActivity, R.string.share_draft_failed, Toast.LENGTH_SHORT).show()
                            }
                        },
                        openTeach = openTeach,
                        onTeachOpened = { openTeach = false },
                        requestedCallStandby = requestedCallStandby,
                        onCallStandbyOpened = { requestedCallStandby = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Build.VERSION.SDK_INT >= 35) {
            setIntent(intent, runCatching { currentCaller }.getOrNull())
        } else {
            setIntent(intent)
        }
        val app = application as MikaApp
        shareJob?.cancel()
        requestedConversation = intent.getStringExtra(EXTRA_OPEN_CONVERSATION)
        requestedCalls = intent.getBooleanExtra(EXTRA_OPEN_CALLS, false)
        requestedCallHistoryId = intent.getStringExtra(EXTRA_CALL_HISTORY_ID)
        if (requestedConversation != null || intent.getBooleanExtra(EXTRA_OPEN_TEACH, false)) {
            app.chat.cancelPendingSharedDraft()
        }
        takeVoiceCall(intent)
        takeCallStandby(intent)
        takeTeach(intent)
        requestedSharedDraft?.let { app.sharedDrafts.discard(it.draft.attachments) }
        requestedSharedDraft = null
        // Share received while the UI is already alive in the background: put it into the currently open conversation instead of starting a new one.
        shareInCurrent = true
        shareHandled = false
        verifiedShareUris = emptyList()
        receiveShare(intent, inCurrentConversation = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHARE_IN_CURRENT, shareInCurrent)
        outState.putBoolean(STATE_SHARE_HANDLED, shareHandled)
        if (!shareHandled && verifiedShareUris.isNotEmpty()) {
            outState.putStringArrayList(STATE_VERIFIED_SHARE_URIS, ArrayList(verifiedShareUris))
        }
    }

    private fun rejectShare() {
        shareHandled = true
        setIntent(Intent(this, MainActivity::class.java))
        Toast.makeText(this, R.string.share_draft_failed, Toast.LENGTH_SHORT).show()
    }

    private fun receiveShare(intent: Intent, inCurrentConversation: Boolean) {
        val content = readShare(intent) ?: return
        shareJob?.cancel()
        when (content) {
            ShareContent.Rejected -> rejectShare()
            is ShareContent.Text -> requestedSharedDraft = SharedDraftRequest(SharedDraft("", content.text), ++shareSerial, inCurrentConversation)
            is ShareContent.Files -> {
                val uriStrings = content.uris.map { it.toString() }
                if (verifiedShareUris != uriStrings && content.uris.any { !shareCanRead(intent, it, content.clipUris) }) {
                    return rejectShare()
                }
                verifiedShareUris = uriStrings
                shareJob = lifecycleScope.launch {
                    val prepared = SharedDraftImporter(this@MainActivity, (application as MikaApp).sharedDrafts)
                        .attachments(content.text, content.uris, content.type)
                    if (prepared == null) rejectShare()
                    else requestedSharedDraft = SharedDraftRequest(
                        prepared.draft, ++shareSerial, inCurrentConversation,
                        rejected = prepared.failures, overLimit = content.overLimit,
                    )
                }
            }
        }
    }

    private var updateWatch: Job? = null

    override fun onStart() {
        super.onStart()
        // The user previously tapped "quit completely": opening Claire brings it back, and the capsule plus the startup work skipped on quit are made up for here
        com.wangzan101.mika.MikaQuit.resume(this)
        // The owner opened Claire by hand: clear the "actively stopped in the system" record and re-schedule the periodic call-standby check under the existing grant
        CallStandbyRecoveryJob.onOwnerOpenedApp(application as MikaApp)
        monitor.start()
        // Silently check for a new version on every launch (only once if opened repeatedly within two minutes), and stay quiet on failure
        val app = application as MikaApp
        val check = app.updates.autoCheckInBackground()
        updateWatch = lifecycleScope.launch {
            check.join()
            app.automaticUpdates.watch(app.updates.state)
        }
        // The desktop connection is on but the service is gone (stopped by the system, or failed to come back from the background): bring it back while the UI is in the foreground
        (application as MikaApp).mcp.ensureRunning()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        monitor.refresh()
        recentsExclusion.reapply()
        // On returning to the foreground, check auto-archive once under the latest settings (also checked at startup; this covers a process that stays up for a long time)
        (application as MikaApp).autoArchiveNow()
    }

    override fun onStop() {
        updateWatch?.cancel()
        monitor.stop()
        foreground = false
        super.onStop()
    }

    override fun onDestroy() {
        shareJob?.cancel()
        // Private copies not yet bound to a conversation are cleared when this receive ends; accepted drafts are restored by CurrentChat and are not deleted here.
        requestedSharedDraft?.let { (application as MikaApp).sharedDrafts.discard(it.draft.attachments) }
        requestedSharedDraft = null
        if (isFinishing) {
            (application as MikaApp).chat.cancelPendingSharedDraft()
        }
        super.onDestroy()
    }

    private val phone get() = PhoneServices.of(this)

    private fun perform(fix: Fix) {
        when (fix) {
            Fix.OpenAccessibility -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Fix.OpenAppInfo -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            Fix.AllowNotifications -> {
                val needsGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needsGrant) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings()
            }
            Fix.OpenAttentionChannel -> openChannelSettings(TaskService.CHANNEL_ATTENTION)
            Fix.OpenResultChannel -> openChannelSettings(TaskService.CHANNEL_RESULT)
            Fix.OpenProgressChannel -> openChannelSettings(TaskService.CHANNEL_PROGRESS)
            Fix.OpenLiveTaskSettings -> if (Build.VERSION.SDK_INT >= 36) {
                try {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                } catch (_: ActivityNotFoundException) {
                    openNotificationSettings()
                }
            } else openNotificationSettings()
            Fix.OpenPairingChannel -> openChannelSettings(PairingNotifier.ensureChannel(this).id)
            Fix.AddModel -> Unit // the settings page handles it itself
            // Page changes are handled by MikaRoot
            Fix.OpenPhoneSetup, Fix.OpenPhoneOps, Fix.RepairPhoneHelper, Fix.OpenPause -> Unit
            Fix.RetryPhoneHelper -> phone.pairing.scope.launch {
                phone.sharedShell.reconnect()
                phone.shellControl.retry()
            }
            Fix.UnpairPhoneHelper -> phone.pairing.scope.launch { phone.recovery.unpair() }
            Fix.OpenAboutPhone -> openFirst(DeveloperSettings.aboutPhone())
            Fix.OpenDeveloperOptions -> openFirst(DeveloperSettings.developerOptionsPage())
            Fix.OpenUsbDebugging -> openFirst(DeveloperSettings.usbDebuggingPage())
            Fix.OpenWirelessDebugging -> openFirst(DeveloperSettings.wirelessDebuggingPage())
            Fix.OpenWifi -> openFirst(DeveloperSettings.wifiPage())
        }
    }

    private fun appNotificationSettings() =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

    private fun openNotificationSettings() = open(appNotificationSettings())

    /** Opens the settings page of a specific notification channel directly; falls back to the app's general notification page when the system has no such page. */
    private fun openChannelSettings(channelId: String) {
        val channelPage = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        firstThatStarts(listOf(channelPage, appNotificationSettings())) { intent ->
            if (intent.resolveActivity(packageManager) == null) false
            else try { startActivity(intent); true } catch (e: ActivityNotFoundException) { false }
        }
    }

    /** Try them in order and use the first one that opens: not every device's settings pages accept the same entry point. */
    private fun openFirst(intents: List<Intent>) {
        val hit = intents.firstOrNull { it.resolveActivity(packageManager) != null } ?: intents.last()
        open(hit)
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for ${intent.action}", e)
        }
    }

    private fun takeTeach(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_OPEN_TEACH, false)) return
        openTeach = true
        (application as MikaApp).teach.prefill(intent.getStringExtra(EXTRA_TEACH_GOAL))
        intent.removeExtra(EXTRA_OPEN_TEACH)
    }

    /** Direct open from the capsule menu's "voice chat": signal +1 handed to the UI layer; removed after a single read so rotation doesn't trigger it again. */
    private fun takeVoiceCall(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_OPEN_VOICE_CALL, false)) return
        voiceCallSignal++
        intent.removeExtra(EXTRA_OPEN_VOICE_CALL)
    }

    /** Standby notification "handle it" opens the phone page's standby check directly: a one-shot request, cleared after the phone page opens the check page; read once and removed, so rotation does not trigger it again. */
    private fun takeCallStandby(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_OPEN_CALL_STANDBY, false)) return
        requestedCallStandby = true
        intent.removeExtra(EXTRA_OPEN_CALL_STANDBY)
    }

    companion object {
        const val EXTRA_OPEN_CONVERSATION = "open_conversation"
        const val EXTRA_OPEN_CALLS = "open_calls"
        const val EXTRA_CALL_HISTORY_ID = "call_history_id"
        const val EXTRA_OPEN_CALL_STANDBY = "open_call_standby"
        const val EXTRA_OPEN_TEACH = "open_teach"
        const val EXTRA_TEACH_GOAL = "teach_goal"
        const val EXTRA_OPEN_VOICE_CALL = "open_voice_call"
        private const val STATE_SHARE_IN_CURRENT = "share_in_current"
        private const val STATE_SHARE_HANDLED = "share_handled"
        private const val STATE_VERIFIED_SHARE_URIS = "verified_share_uris"
        private const val TAG = "MikaMain"

        /** Whether the main screen (chat) is currently visible in the foreground. Used to decide whether to post a completion notification when a task finishes (#731). */
        @Volatile var foreground: Boolean = false
    }
}

/**
 * Opening web links (GitHub, X, documentation and so on): Claire has its own browser, and web pages always open in a new tab there;
 * only non-web links (or when the built-in browser is not ready yet) are handed to the system.
 */
internal fun openInBrowser(context: Context, url: String) {
    val web = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
    if (web && InAppLinks.open?.invoke(url) == true) return
    openInSystemBrowser(context, url)
}

/** Entry point for opening a web page in the built-in browser, attached by the main UI when it is shown. */
internal object InAppLinks {
    @Volatile var open: ((String) -> Boolean)? = null
}

/** Always open with the system browser: sign-in authorization pages need to return to Claire, so they must use the system browser. */
internal fun openInSystemBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, AboutCopy.NO_BROWSER, Toast.LENGTH_SHORT).show()
    }
}

/** Tries [candidates] in order and stops at the first one that [start] succeeds for; returns false if none work. Not every device has every system settings page, so this is how we fall back to a generic page. */
internal fun <T> firstThatStarts(candidates: List<T>, start: (T) -> Boolean): Boolean = candidates.any(start)

private data class SharedDraftRequest(
    val draft: SharedDraft,
    val serial: Long,
    val inCurrentConversation: Boolean = false,
    val rejected: List<AttachmentFailure> = emptyList(),
    val overLimit: Int = 0,
)

/**
 * Page switching: the chat page is the root, and entry points such as the drawer and settings push pages onto [NavStack]; both the back key and the on-page back button go through [NavStack.back].
 * Pages come and go only through callbacks and know nothing about each other.
 */
@Composable
private fun MikaRoot(
    store: ProviderStore,
    monitor: SystemStatusMonitor,
    activityFix: (Fix) -> Unit,
    requestedConversation: String?,
    onConversationOpened: () -> Unit,
    requestedCalls: Boolean,
    requestedCallHistoryId: String?,
    onCallsOpened: () -> Unit,
    voiceCallSignal: Int,
    sharedDraft: SharedDraftRequest?,
    onSharedDraftOpened: (Boolean, SharedDraft) -> Unit,
    openTeach: Boolean,
    onTeachOpened: () -> Unit,
    requestedCallStandby: Boolean = false,
    onCallStandbyOpened: () -> Unit = {},
) {
    val context = LocalContext.current
    var focusedCallHistoryId by remember { mutableStateOf<String?>(null) }
    val personalStart = remember { FeatureFlags.get(context).personalStart() }
    val nav = rememberNavStack(if (personalStart && requestedConversation == null) Route.Personal else Route.Chat)
    var filesFromDrawer by rememberSaveable { mutableStateOf(false) }
    var filesConversation by rememberSaveable { mutableStateOf<String?>(null) }
    // When entered from "re-pair" on the settings page, go back to the pairing step first
    var setupRepair by rememberSaveable { mutableStateOf(false) }
    // Which onboarding step is in progress; null when not in the onboarding flow. The model setup page returns to wherever it came from
    var onboarding by rememberSaveable { mutableStateOf<OnboardingStep?>(null) }
    var openPhoneOps by rememberSaveable { mutableStateOf(false) }
    var openPause by rememberSaveable { mutableStateOf(false) }
    val onFix: (Fix) -> Unit = { fix ->
        when (fix) {
            Fix.AddModel -> nav.push(Route.Models)
            Fix.OpenPhoneSetup, Fix.RepairPhoneHelper -> {
                setupRepair = fix == Fix.RepairPhoneHelper
                nav.push(Route.PhoneSetup)
            }
            Fix.OpenPhoneOps -> {
                openPhoneOps = true
                nav.push(Route.Settings)
            }
            // "Pause Claire" is a page in settings: open it directly.
            Fix.OpenPause -> {
                openPause = true
                nav.push(Route.Settings)
            }
            else -> activityFix(fix)
        }
    }
    var runId by rememberSaveable { mutableStateOf<String?>(null) }
    // "Run details" was opened from the call page: on back, call this to bring the call page back; null when opened from elsewhere
    var reportRunId by rememberSaveable { mutableStateOf<String?>(null) }
    var playbackScreenshotId by rememberSaveable { mutableStateOf<Int?>(null) }
    var sourceRunId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceLegacyText by rememberSaveable { mutableStateOf<String?>(null) }
    val status by monitor.status.collectAsState()
    val roles by store.roles.collectAsState()
    val hasMain = roles.hasMain
    val check = Prerequisites.check(status, hasMain)
    val app = context.applicationContext as MikaApp
    val personasOn = FeatureFlags.get(context).persona()
    // Call entry point: a handset button sits permanently in the input bar; as soon as the signal rises the call page appears (via the direct-open extra or the button)
    var localVoiceCall by remember { mutableIntStateOf(0) }
    val voiceCallScope = rememberCoroutineScope()
    val voiceCallResumed = remember { MutableStateFlow(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { voiceCallResumed.value = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { voiceCallResumed.value = false }
    val voiceCallDeps = remember(app) {
        VoiceCallDeps(
            context = context,
            scope = voiceCallScope,
            appScope = app.appScope,
            profile = { app.calls.activeVoiceProfile() },
            credentials = { app.calls.voiceProfiles.credentials(it) },
            foregroundResumed = voiceCallResumed,
            desks = app.desks,
            conversationId = { app.chat.ui.value.conversationId },
            newConversation = { app.chat.claimConversation() },
            deskParent = { ConversationStore.shared(context).deskParent(it) },
            turns = { ConversationStore.shared(context).turns(it) },
            // Save as you go during the call, invoked from the app-level scope: file IO is dispatched to IO
            appendTurn = { id, turn -> withContext(Dispatchers.IO) { ConversationStore.shared(context).append(id, turn) } },
            recordCall = { id, call -> withContext(Dispatchers.IO) { ConversationStore.shared(context).recordCall(id, call) } },
            attachService = { VoiceCallService.attach(context, it) },
            persona = { com.wangzan101.mika.frontdesk.deskPersona(app) },
            openConversation = { id ->
                app.chat.openConversation(id)
                nav.reset()
            },
            // Run details sits on top of the call page, so back returns to the call page
            openRun = { id ->
                runId = id
                nav.push(Route.Run)
            },
            calls = { ConversationStore.shared(context).observeCalls(it) },
            // Tap an entry in call history: jump to the start of that call in chat (the call keeps going, with the "In call" bar at the top)
            openCallRecord = { id, callId ->
                app.chat.openConversationAt(id, com.wangzan101.mika.chat.ui.ChatTarget.Call(callId))
                nav.reset()
            },
            liveRun = { id -> app.taskState.runs.value.firstOrNull { it.conversationId == id }?.runId?.value },
            screens = app.taskState.screenConversations(app.appScope),
            frames = { id -> app.taskState.runs.value.firstOrNull { it.conversationId == id }?.frames },
            meter = { ConversationStore.shared(context).meter(it) },
        )
    }
    val voiceCall = rememberVoiceCallController(voiceCallDeps)
    // A newer version was found: the sidebar, the settings home, and the about page all show a dot based on it
    val updateState by app.updates.state.collectAsState()
    val autoUpdate by app.automaticUpdates.enabled.collectAsState()
    val readyUpdate by app.automaticUpdates.ready.collectAsState()
    val mirrorEnabled by app.updateMirrors.enabled.collectAsState()
    val mirrors by app.updateMirrors.mirrors.collectAsState()
    val mirrorScope = rememberCoroutineScope()
    LaunchedEffect(requestedConversation) {
        if (requestedConversation != null) {
            if (requestedConversation == "personal-main") nav.show(Route.Personal)
            else {
                app.chat.openConversation(requestedConversation)
                nav.reset()
            }
            onConversationOpened()
        }
    }
    LaunchedEffect(requestedCalls, requestedCallHistoryId) {
        if (requestedCalls) {
            focusedCallHistoryId = requestedCallHistoryId
            nav.push(Route.Calls)
            onCallsOpened()
        }
    }
    // Only brings the phone page to the top; the request is cleared only when the phone page actually opens the check page (onCallStandbyOpened)
    LaunchedEffect(requestedCallStandby) {
        if (requestedCallStandby) {
            focusedCallHistoryId = null
            nav.bringToTop(Route.Calls)
        }
    }
    LaunchedEffect(sharedDraft) {
        val draft = sharedDraft?.draft
        if (draft != null) {
            val accepted = app.chat.openSharedDraft(
                draft,
                inCurrentConversation = sharedDraft.inCurrentConversation,
                rejected = sharedDraft.rejected,
                overLimit = sharedDraft.overLimit,
            )
            if (accepted) nav.reset()
            onSharedDraftOpened(accepted, draft)
        }
    }
    // Consume self-operation requests submitted from tasks or internal notification entry points.
    suspend fun openSelfFile(file: com.wangzan101.mika.self.SelfFileRequest) {
        if (!AttachFiles.openSent(context, file.conversationId, file.path)) {
            Toast.makeText(context, R.string.self_file_failed, Toast.LENGTH_SHORT).show()
        }
    }
    fun openSelfUrl(url: String, newTab: Boolean = false, show: Boolean = true) {
        nav.push(Route.Browser)
        val browser = UserBrowser.of(context)
        val error = if (newTab) browser.openInNewTab(url, select = show) else browser.open(url)
        error?.let { Toast.makeText(context, R.string.self_browser_failed, Toast.LENGTH_SHORT).show() }
    }
    fun openSelfLogin(host: String) {
        nav.push(Route.Browser)
        UserBrowser.of(context).openFavorite(com.wangzan101.mika.browser.FavoriteSite(host, loggedIn = false))
            ?.let { Toast.makeText(context, R.string.self_browser_failed, Toast.LENGTH_SHORT).show() }
    }
    // Web links in settings, about and the guide all open in the built-in browser; pages opened this way return to the previous page when you go back past the start
    var browserReturn by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val open: (String) -> Boolean = { url -> browserReturn = true; openSelfUrl(url, newTab = true); true }
        InAppLinks.open = open
        onDispose { if (InAppLinks.open === open) InAppLinks.open = null }
    }
    val selfRequest by app.selfUi.request.collectAsState()
    LaunchedEffect(selfRequest?.id) {
        val r = selfRequest ?: return@LaunchedEffect
        when {
            r.url != null -> openSelfUrl(r.url, r.newTab, r.show)
            r.loginHost != null -> openSelfLogin(r.loginHost)
            r.file != null -> openSelfFile(r.file)
            r.screen != null -> nav.push(r.screen.route())
        }
        app.selfUi.consumed(r.id)
    }
    val release = remember { Build.VERSION.RELEASE }
    // "Take a look" tapped on a result card: open the skills segment
    val memoryRuntime = remember { MemoryRuntime.of(context) }
    val phoneSetup = remember { PhoneServices.of(context).setupPrompt }
    val openMemory by memoryRuntime.openRequest.collectAsState()
    LaunchedEffect(openMemory) { if (openMemory != null) nav.push(Route.Memory) }
    LaunchedEffect(openTeach) {
        if (openTeach) {
            nav.push(Route.Teach)
            onTeachOpened()
        }
    }
    // First launch: welcome → model setup → phone operations; skip the first two steps when a model is already configured, and skip the last step when phone operations are ready or the guide has been seen
    val onboardingState = remember { OnboardingState(context) }
    fun onboardingInputs() = Onboarding.Inputs(
        hasMainModel = store.roles.value.hasMain,
        modelSetupDone = onboardingState.modelSetupDone(),
        phoneNeeded = status.builtinShell != BuiltinShellStatus.Running && status.sdkInt >= Prerequisites.MIN_SDK,
        phoneGuideSeen = phoneSetup.guideSeen(),
    )
    fun goOnboarding(step: OnboardingStep?) {
        onboarding = step
        when (step) {
            // Each onboarding step sits directly one layer above the chat page; when done it returns to the chat page
            OnboardingStep.Welcome -> nav.show(Route.Welcome)
            OnboardingStep.Model -> nav.show(Route.ModelSetup)
            OnboardingStep.Phone -> { setupRepair = false; nav.show(Route.PhoneSetup) }
            null -> nav.reset()
        }
    }
    LaunchedEffect(Unit) {
        if (onboarding == null && requestedConversation == null && nav.current != Route.Personal) {
            Onboarding.start(onboardingInputs())?.let(::goOnboarding)
        }
    }
    val phoneSetupNeed by phoneSetup.needed.collectAsState()
    // When a page has its own back handling (sub-pages inside settings, the drawer), it gets the event first; here we only go back one page
    BackHandler(enabled = nav.canGoBack) { nav.back() }
    val back: () -> Unit = { nav.back() }
    // Pages keep their list position and input content when leaving the composition tree: pages still in the back stack restore as they were, popped pages discard it and start over next time;
    // the conversation page and the personal assistant are root pages switched back and forth, and are always kept
    val screenStates = rememberPageStates(keep = { it == Route.Chat.name || it == Route.Personal.name })
    val sidebarPage = nav.current in setOf(Route.Chat, Route.Personal, Route.Plans, Route.Memory, Route.Browser)
    // When the call page is not on top, an "In call · Back to call" bar at the top; the page underneath starts below it
    val liveCall = voiceCall.liveCall()
    val callBar = liveCall?.takeIf { nav.current != Route.VoiceCall }
    Column(Modifier.fillMaxSize()) {
    callBar?.let { VoiceCallBar(it) { nav.bringToTop(Route.VoiceCall) } }
    Box(Modifier.weight(1f).fillMaxWidth().then(if (callBar != null) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
    AdaptiveWindow(navigation = sidebarPage) {
        val page: @Composable (() -> Unit) -> Unit = { openDrawer ->
    // Global page switches use a slide push transition; inside pages always use route, not nav.current: during the transition the old page is still rendering, and reading live state would change its appearance instantly
    NavSlide(nav.stack, depth = { it.size }, states = screenStates, pageKey = { it.last().name }) { stack ->
    val route = stack.last()
    when (route) {
        Route.PhoneSetup -> PhoneSetupScreen(status, setupRepair, onFix, onboarding = onboarding == OnboardingStep.Phone) {
            phoneSetup.markGuideSeen()
            if (onboarding == OnboardingStep.Phone) goOnboarding(null) else nav.back()
        }
        Route.Welcome -> WelcomeScreen { goOnboarding(OnboardingStep.Model) }
        Route.ModelSetup -> {
            val inOnboarding = onboarding == OnboardingStep.Model
            ModelSetupScreen(
                store, onboarding = inOnboarding,
                onBack = { if (inOnboarding) goOnboarding(OnboardingStep.Welcome) else nav.back() },
                onDone = {
                    if (inOnboarding) {
                        onboardingState.markModelSetupDone()
                        goOnboarding(Onboarding.next(OnboardingStep.Model, onboardingInputs()))
                    } else nav.back()
                },
            )
        }
        Route.Chat, Route.Personal -> {
            // The conversation page is visible again (returned to foreground, came back from another page): add turns stored elsewhere and attach tasks started elsewhere
            LifecycleStartEffect(Unit) {
                app.chat.refresh()
                onStopOrDispose {}
            }
            // While the chat page is visible, front desk reports are spoken in the conversation; on leaving or going to the background they go back to the fallback (notifications)
            LifecycleStartEffect(route) {
                app.chat.pageShown(route == Route.Chat)
                onStopOrDispose { app.chat.pageShown(false) }
            }
            Box(Modifier.fillMaxSize()) {
                    val readyRelease = readyUpdate?.takeIf { autoUpdate && it.apkUrl == updateState.update?.apkUrl }
                    val updateBackdrop = if (readyRelease != null) rememberGlassBackdrop() else null
                    Box(Modifier.fillMaxSize().glassSource(updateBackdrop)) {
                        if (route == Route.Personal) PersonalAgentScreen(
                            runtime = app.personal,
                            voice = app.voice,
                            onOpenDrawer = openDrawer,
                            onOpenTask = { id -> app.chat.openPersonalTask(id); nav.reset() },
                            memoryRuntime = memoryRuntime,
                            onModels = { nav.push(Route.Models) },
                            onPickSpeech = { nav.push(Route.SpeechModel) },
                            onOpenVoicePacks = { nav.push(Route.VoicePacks) },
                        ) else ChatScreen(
                            vm = app.chat,
                            check = check,
                            frames = app.taskState.frames,
                            webOpen = app.tasks.webScreenOpen,
                            pipInput = app.taskState.pipInput,
                            screens = remember(app) { app.taskState.screenConversations(app.appScope) },
                            pipSoundOn = app.taskState.pipSoundOn,
                            pipOperateEnabled = FeatureFlags.get(context).pipOperate(),
                            reduceMotion = MikaTheme.reduceMotion,
                            onOpenDrawer = openDrawer,
                            openSettings = { nav.push(Route.Settings) },
                            openUsage = { nav.push(Route.Usage) },
                            onOpenBrowser = { nav.push(Route.Browser) },
                            openRun = { runId = it; nav.push(Route.Run) },
                            phoneSetupNeed = (if (status.builtinShell != BuiltinShellStatus.Running) phoneSetupNeed else null),
                            openPhoneSetup = { onFix(Fix.OpenPhoneSetup) },
                            voice = app.voice,
                            openSpeechModel = { nav.push(Route.SpeechModel) },
                            openVoicePacks = { nav.push(Route.VoicePacks) },
                            onOpenTeach = { hint ->
                                app.teach.prefill(hint)
                                nav.push(Route.Teach)
                            },
                            // The entry point is permanent: when credentials aren't configured or the protocol is incompatible it opens the gated page (explanation + go configure) instead of silently disappearing
                            onVoiceCall = { localVoiceCall++ },
                            openDeskRun = { id, phase ->
                                voiceCallScope.launch {
                                    val live = app.taskState.runs.value.firstOrNull { it.conversationId == id }?.runId?.value
                                    val run = withContext(Dispatchers.IO) {
                                        com.wangzan101.mika.voicecall.VoiceCallTaskLink.runFor(phase, live) { ConversationStore.shared(context).turns(id) }
                                    }
                                    if (run == null) app.chat.openConversation(id) else { runId = run; nav.push(Route.Run) }
                                }
                            },
                            openCallSettings = { nav.push(Route.CallMode) },
                        )
                    }
                    var installUpdate by remember { mutableStateOf<ReleaseInfo?>(null) }
                    readyRelease?.let { release ->
                        UpdateReadyCapsule(
                            release,
                            onInstall = {
                                installUpdate = release
                                app.automaticUpdates.dismiss(release)
                            },
                            onTimeout = { app.automaticUpdates.dismiss(release) },
                            onDisable = { app.automaticUpdates.setEnabled(false) },
                            backdrop = updateBackdrop,
                            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 54.dp),
                        )
                    }
                    installUpdate?.let { release ->
                        UpdateSheet(release, BuildConfig.VERSION_NAME, BuildConfig.BETA,
                            onOpenUrl = { openInBrowser(context, it) }, onDismiss = { installUpdate = null })
                    }
                }
        }
        Route.Settings -> {
            val autonomous by app.approvals.autonomous.collectAsState()
            // Settings uses a plain list: light grey page, pure white groups, icons without a backing block
            com.wangzan101.mika.ui.components.PlainListTheme { SettingsScreen(store, status, release, onFix, onBack = back, onOpenMemory = { nav.push(Route.Memory) },
                onOpenModels = { nav.push(Route.Models) },
                onOpenBrowser = { nav.push(Route.Browser) },
                onOpenFiles = { filesFromDrawer = false; filesConversation = app.chat.ui.value.conversationId; nav.push(Route.Files) },
                onOpenPersonal = { nav.show(Route.Personal) },
                onOpenPlans = { nav.push(Route.Plans) },
                onOpenPersonas = if (personasOn) ({ nav.push(Route.Persona) }) else null,
                autonomous = autonomous, onAutonomous = app.approvalControl::setAutonomous,
                updateVersion = updateState.update?.version, onOpenAbout = { nav.push(Route.About) },
                onOpenVoiceCatalog = { nav.push(Route.VoiceServices) },
                mcpServers = app.mcpServers,
                openPhoneOps = openPhoneOps, onPhoneOpsOpened = { openPhoneOps = false },
                openPause = openPause, onPauseOpened = { openPause = false },
                onOpenModelRoute = { route ->
                    nav.push(when (route) {
                        SettingsModelRoute.Usage -> Route.Usage
                        SettingsModelRoute.Speech -> Route.SpeechModel
                        SettingsModelRoute.Voice -> Route.VoiceModel
                        SettingsModelRoute.CallMode -> Route.CallMode
                        SettingsModelRoute.VoiceServices -> Route.VoiceServices
                        SettingsModelRoute.Image -> Route.ImageModel
                        SettingsModelRoute.Video -> Route.VideoModel
                    })
                }) }
        }
        // Standalone pages entered from settings also use the simple list style
        Route.Models -> com.wangzan101.mika.ui.components.PlainListTheme { ModelsScreen(store, onBack = back, onOpenSpeech = { nav.push(Route.SpeechModel) }, onOpenVoice = { nav.push(Route.VoiceModel) }, onOpenImage = { nav.push(Route.ImageModel) }, onOpenVideo = { nav.push(Route.VideoModel) }, onOpenUsage = { nav.push(Route.Usage) }, onOpenVoiceCatalog = { nav.push(Route.VoiceServices) }, onOpenCallMode = { nav.push(Route.CallMode) }) }
        Route.VoiceModel -> com.wangzan101.mika.ui.components.PlainListTheme { VoiceModelScreen(store, onBack = back, onOpenServices = { nav.push(Route.VoiceServices) }) }
        Route.VoiceCall -> VoiceCallScreen(voiceCall, onGone = { nav.leave(Route.VoiceCall) })
        Route.CallMode -> com.wangzan101.mika.ui.components.PlainListTheme { CallModeScreen(onBack = back, onOpenStt = { nav.push(Route.SpeechModel) }, onOpenTts = { nav.push(Route.VoiceModel) }, onOpenRealtime = { nav.push(Route.RealtimeVoice) }) }
        Route.RealtimeVoice -> com.wangzan101.mika.ui.components.PlainListTheme { RealtimeVoiceScreen(store, onBack = back, onOpenCalls = { nav.push(Route.Calls) }, onOpenServices = { nav.push(Route.VoiceServices) }) }
        Route.VoiceServices -> com.wangzan101.mika.ui.components.PlainListTheme { VoiceServicesScreen(store, onBack = back, onOpenCustom = { nav.push(Route.VoiceCatalog) }) }
        Route.VoiceCatalog -> com.wangzan101.mika.ui.components.PlainListTheme { VoiceCatalogScreen(com.wangzan101.mika.voice.model.VoiceModelEnvironment.get(context), onBack = back) }
        Route.ImageModel -> com.wangzan101.mika.ui.components.PlainListTheme { ImageModelScreen(store, onBack = back) }
        Route.VideoModel -> com.wangzan101.mika.ui.components.PlainListTheme { VideoModelScreen(store, onBack = back) }
        Route.Persona -> com.wangzan101.mika.ui.components.PlainListTheme { if (personasOn) PersonaScreen(app.personas, onBack = back) else LaunchedEffect(Unit) { nav.back() } }
        Route.Usage -> com.wangzan101.mika.ui.components.PlainListTheme { UsageScreen(app.usage, store, onBack = back) }
        Route.SpeechModel -> com.wangzan101.mika.ui.components.PlainListTheme { SpeechModelScreen(store, onBack = back, onOpenServices = { nav.push(Route.VoiceServices) }, onOpenVoicePacks = { nav.push(Route.VoicePacks) }) }
        Route.VoicePacks -> com.wangzan101.mika.ui.components.PlainListTheme { com.wangzan101.mika.voice.ui.VoicePacksScreen(onBack = back) }
        Route.About -> com.wangzan101.mika.ui.components.PlainListTheme { AboutScreen(
            versionName = BuildConfig.VERSION_NAME, build = BuildConfig.GIT_HASH, beta = BuildConfig.BETA,
            update = updateState,
            issueUrl = remember {
                MikaLinks.issueUrl(
                    MikaLinks.Device(
                        Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                        BuildConfig.VERSION_NAME, BuildConfig.GIT_HASH,
                    ),
                )
            },
            onCheck = app.updates::checkInBackground,
            autoUpdate = autoUpdate,
            onAutoUpdate = app.automaticUpdates::setEnabled,
            mirrorEnabled = mirrorEnabled,
            mirrorCount = mirrors.size,
            onMirrorEnabled = { on ->
                mirrorScope.launch {
                    val error = withContext(Dispatchers.IO) { app.updateMirrors.setEnabled(on) }
                    if (error != null) Toast.makeText(context, R.string.about_mirrors_save_failed, Toast.LENGTH_SHORT).show()
                }
            },
            onOpenMirrors = { nav.push(Route.UpdateMirrors) },
            onOpenUrl = { openInBrowser(context, it) },
            onSaveQr = { QrImage.saveToPictures(context) },
            onOpenLicense = { nav.push(Route.License) },
            onBack = back,
            diagnostics = {
                Diagnostics.render(DiagnosticsCollector.collect(context, status))
            },
        ) }
        Route.UpdateMirrors -> com.wangzan101.mika.ui.components.PlainListTheme { UpdateMirrorsScreen(app.updateMirrors, onBack = back) }
        Route.License -> com.wangzan101.mika.ui.components.PlainListTheme { LicensesScreen(onBack = back) }
        Route.Browser -> com.wangzan101.mika.ui.components.PlainListTheme { BrowserScreen(remember { UserBrowser.of(context) }, onBack = { browserReturn = false; back() }, returnOnClose = browserReturn) }
        Route.Files -> com.wangzan101.mika.ui.components.PlainListTheme { FileLibraryScreen(app.chat, filesConversation, filesFromDrawer, onBack = back, onAdded = { id ->
            app.chat.openConversation(id)
            val opened = app.chat.ui.value.conversationId == id
            if (opened) nav.reset()
            opened
        }, onOpenSource = { id, target ->
            app.chat.openConversationAt(id, target)
            val opened = app.chat.ui.value.conversationId == id
            if (opened) nav.reset()
            opened
        }) }
        Route.Memory -> com.wangzan101.mika.ui.components.PlainListTheme {
            val registry = remember { WebSites.registry(context) }
            val sites by registry.entries.collectAsState()
            val webSites = WebSitesState(
                sites = sites,
                onOpenLogin = { WebHandover.showLogin(context, it) },
                onLogout = { WebSites.login(context).logout(it) },
                onLogoutAll = { WebSites.logoutAll(context) },
            )
            val routeStore = remember { FileRouteStore.shared(context) }
            var routeCount by remember { mutableStateOf<Int?>(null) }
            // The first memory fetch has to read and decrypt from disk, so it runs on a background thread; the page's coroutine is tied to this screen
            val vmScope = rememberCoroutineScope()
            LaunchedEffect(Unit) { routeCount = withContext(Dispatchers.IO) { runCatching { routeStore.count() }.getOrNull() } }
            val vm by produceState<MemoryViewModel?>(null) {
                value = withContext(Dispatchers.IO) {
                    val bundled = GuideCatalog.loadBundled()
                    MemoryViewModel(
                        memoryRuntime.store(), memoryRuntime.settings(), memoryRuntime.skills(),
                        vmScope, memoryRuntime.file().brokenNotice,
                        wipe = { memoryRuntime.scope.launch(Dispatchers.IO) { memoryRuntime.wipeAll() } },
                        onForgotten = { id -> memoryRuntime.scope.launch(Dispatchers.IO) { memoryRuntime.stripMemory(setOf(id)) } },
                        runnableScripts = bundled.runnableScripts(),
                        appDrafts = memoryRuntime.appKnowledge(),
                        builtinSkillNames = bundled.items.map { it.name }.toSet(),
                        onSkillRetired = { app.teach.forget(it) },
                    )
                }
            }
            vm?.let { m ->
                // Entered from "take a look" on a result card: show the skills segment directly
                LaunchedEffect(m) {
                    val open = memoryRuntime.openRequest.value
                    if (open != null) m.select(if (open.draftId != null) Segment.Pending else Segment.Skills)
                }
                MemoryDataScreen(
                    m, webSites, routeCount,
                    onClearRoutes = {
                        vmScope.launch { routeCount = withContext(Dispatchers.IO) { routeStore.clear(); routeStore.count() } }
                    },
                    onBack = { memoryRuntime.requestOpen(null); nav.back() },
                )
            }
        }
        Route.Run -> {
            val id = runId
            val vm = remember(id) { id?.let { RunTimelineViewModel(
                    RunStore(context), it, resolveMemory = app.chat::usedMemoryTexts,
                    alive = { run -> app.taskState.runs.value.any { r -> r.runId.value == run } },
                ) } }
            if (vm != null) RunTimelineScreen(vm, back) else LaunchedEffect(Unit) { nav.back() }
        }
        Route.Report, Route.Playback -> {
            val id = reportRunId
            val vm = remember(id) { id?.let { TaskReportViewModel(RunStore(context), it) } }
            if (vm == null) LaunchedEffect(Unit) { nav.back() }
            else if (route == Route.Report) TaskReportScreen(vm, back,
                onReplay = { playbackScreenshotId = it; nav.push(Route.Playback) },
                onTimeline = { runId = id; nav.push(Route.Run) })
            else TaskPlaybackScreen(vm, playbackScreenshotId, back)
        }
        Route.Source -> {
            val vm = remember(sourceRunId, sourceLegacyText) {
                SourceViewModel(RunStore(context), sourceRunId, sourceLegacyText) { ConfirmationNotifier.appLabel(context, it) }
            }
            SourceScreen(vm, back)
        }
        Route.Plans -> com.wangzan101.mika.ui.components.PlainListTheme { PlansScreen(context, onBack = back, onOpenConversation = {
            app.chat.openConversation(it)
            nav.reset()
        }) }
        Route.Teach -> TeachScreen(
            app.teach,
            onBack = { nav.back() },
        )
        Route.Screens -> ScreensScreen(app.taskState, onBack = back, onOpenConversation = {
            app.chat.openConversation(it)
            nav.reset()
        })
        Route.Calls -> CallSettingsScreen(app.calls, onBack = back, initialHistoryCallId = focusedCallHistoryId,
            openStandbyChecklist = requestedCallStandby, onStandbyChecklistOpened = onCallStandbyOpened,
            onOpenVoiceCatalog = { nav.push(Route.VoiceServices) })
    }
    }
        }
        val permanent = LocalAdaptiveLayout.current.hasNavigation
        if (nav.current == Route.Chat || nav.current == Route.Personal || (permanent && sidebarPage)) {
            fun openFromSidebar(route: Route) {
                if (permanent && nav.current != Route.Chat && nav.current != Route.Personal) nav.reset()
                nav.pushFromDrawer(route)
            }
            ChatWithDrawer(
                vm = app.chat,
                check = check,
                reduceMotion = MikaTheme.reduceMotion,
                conversations = { ConversationStore.shared(context).list(includeArchived = true) },
                conversationChanges = ConversationStore.shared(context).changes,
                conversationTitle = { ConversationStore.shared(context).title(it) },
                search = ConversationStore.shared(context).search,
                onSettings = { openFromSidebar(Route.Settings) },
                onScreens = { openFromSidebar(Route.Screens) },
                onAbout = { openFromSidebar(Route.About) },
                updateAvailable = AboutCopy.drawerDot(updateState),
                reopen = nav.reopenDrawer,
                onReopened = nav::drawerReopened,
                onRegularChat = nav::reset,
                resetKey = nav.resetSerial,
                onPersonal = { nav.show(Route.Personal) },
                onPlans = { openFromSidebar(Route.Plans) },
                onMemory = { openFromSidebar(Route.Memory) },
                onBrowser = { openFromSidebar(Route.Browser) },
                onTeach = { app.teach.prefill(""); openFromSidebar(Route.Teach) },
                onCalls = { openFromSidebar(Route.Calls) },
                frames = if (nav.current == Route.Chat) app.taskState.frames else null,
                operationRun = app.taskState::run,
                chatActive = nav.current == Route.Chat || nav.current == Route.Personal,
                content = page,
            )
        } else page({})
    }
    }
    }

    // The call host lives outside navigation: the call page is one page in the page stack, and leaving it lets the call keep going (the service keeps it alive), ready to return to anytime
    VoiceCallHost(voiceCall, voiceCallSignal + localVoiceCall, showPage = { nav.bringToTop(Route.VoiceCall) }, hidePage = { nav.leave(Route.VoiceCall) }, onGoConfigure = { gate ->
        // A standard call is missing its listen and speak models, so go to "Call mode"; a realtime call is missing credentials, so go to the voice settings on the calls page
        nav.push(if (gate == com.wangzan101.mika.voicecall.ui.VoiceCallGateStage.NeedListenSpeak) Route.CallMode else Route.Calls)
    })

    var showLanguagePrompt by rememberSaveable { mutableStateOf(!AppLanguage.hasPrompted(context)) }
    if (showLanguagePrompt && onboarding == null) {
        LanguagePromptDialog(
            initial = AppLanguage.English,
            onConfirm = { lang ->
                showLanguagePrompt = false
                AppLanguage.markPrompted(context)
                AppLanguage.set(context, lang)
            },
            onDismiss = {
                showLanguagePrompt = false
                AppLanguage.markPrompted(context)
                AppLanguage.set(context, AppLanguage.English)
            },
        )
    }
}

/** Claire top-level pages a self tool can open; only these pages are exposed, not the permission/root/developer sub-pages. */
private fun com.wangzan101.mika.core.self.SelfScreen.route(): Route = when (this) {
    com.wangzan101.mika.core.self.SelfScreen.Chat -> Route.Chat
    com.wangzan101.mika.core.self.SelfScreen.Settings -> Route.Settings
    com.wangzan101.mika.core.self.SelfScreen.Memory -> Route.Memory
    com.wangzan101.mika.core.self.SelfScreen.Plans -> Route.Plans
    com.wangzan101.mika.core.self.SelfScreen.Browser -> Route.Browser
    com.wangzan101.mika.core.self.SelfScreen.Models -> Route.Models
    com.wangzan101.mika.core.self.SelfScreen.Usage -> Route.Usage
    com.wangzan101.mika.core.self.SelfScreen.Screens -> Route.Screens
    com.wangzan101.mika.core.self.SelfScreen.About -> Route.About
}
