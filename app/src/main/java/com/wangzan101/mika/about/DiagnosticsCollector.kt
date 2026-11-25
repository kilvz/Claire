package com.wangzan101.mika.about

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.AppCopyReader
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.cap.phone.setup.PairingChannel
import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.cap.phone.shell.adb.AndroidAdb
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.device.shell.PairingDiscovery
import com.wangzan101.mika.core.device.shell.PermissionCommands
import com.wangzan101.mika.core.device.shell.SetupInputs
import com.wangzan101.mika.core.device.shell.SetupStep
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.settings.chatGptProvider
import com.wangzan101.mika.settings.configFor
import com.wangzan101.mika.settings.hasKey
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SystemSpeech

/** Setup screen input: built from the system status plus the few things the setup screen records itself (what the user confirmed, skipped or went back to redo). The setup screen and diagnostics share this one. */
fun SystemStatus.setupInputs(
    discovery: PairingDiscovery,
    repair: Boolean,
    accessibilitySkipped: Boolean = false,
    confirmed: Set<SetupStep> = emptySet(),
    revisit: SetupStep? = null,
    stalledMs: Long = 0L,
    usbAsked: Boolean = false,
) = SetupInputs(
    developerOptions = developerOptions, wirelessDebugging = wirelessDebugging,
    status = builtinShell, discovery = discovery, notificationsAllowed = notificationsAllowed,
    pairingChannel = pairingChannel, repair = repair,
    usbDebugging = usbDebugging, accessibility = accessibility == AccessibilityState.On,
    accessibilitySkipped = accessibilitySkipped, restrictedSettings = restrictedSettings,
    confirmed = confirmed, revisit = revisit,
    wirelessMissing = wirelessDebuggingMissing, stalledMs = stalledMs, usbAsked = usbAsked,
    restart = helperRestart, developerSettingsMissing = developerSettingsMissing,
)

/**
 * Gathers a [DiagnosticsSnapshot] from the state already available across the app: system status, built-in helper, automatic recovery,
 * model configuration, run history, update check.
 * It reads from disk (run history), so call it on a background thread. [setup] is the setup screen's current input; when not on the setup
 * screen, one is built from the system status (with no user-confirmed steps).
 */
object DiagnosticsCollector {
    private const val RECENT_RUNS = 3

    fun collect(context: Context, status: SystemStatus, setup: SetupInputs? = null): DiagnosticsSnapshot {
        val app = context.applicationContext as MikaApp
        val phone = PhoneServices.of(app)
        val inputs = setup ?: status.setupInputs(phone.shellControl.pairingDiscovery.value, phone.pairing.repairing.value)
        return DiagnosticsSnapshot(
            generatedAtMs = System.currentTimeMillis(),
            app = DiagnosticsSnapshot.App(BuildConfig.VERSION_NAME, BuildConfig.GIT_HASH, installer(app), language(app)),
            device = DiagnosticsSnapshot.Device(
                Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(),
                sandbox(SandboxState.get(app).status.value),
            ),
            setup = inputs,
            phone = DiagnosticsSnapshot.Phone(
                accessibility = status.accessibility,
                wirelessDebuggingSetting = runCatching {
                    Settings.Global.getString(app.contentResolver, DeveloperSettings.ADB_WIFI_ENABLED)?.let { it == "1" }
                }.getOrNull(),
                connect = phone.shellManager.lastConnectCandidates,
                pairingChannels = PairingChannel.IDS.mapNotNull { id -> channel(app, id)?.let { id to it } }.toMap(),
                attentionChannelImportance = channel(app, TaskService.CHANNEL_ATTENTION),
                wifiConnected = wifi(app),
                lastError = phone.shellManager.engine.lastError,
                appCopies = appCopies(app, phone),
                adbKey = adbKey(app),
                appsAfterTask = com.wangzan101.mika.task.AppsAfterTaskEnd.lastLine,
                helperLaunchStage = phone.shellManager.lastHelperLaunchStage,
                helperHandoffRejection = phone.hub.lastHandoffRejection,
            ),
            recovery = DiagnosticsSnapshot.Recovery(
                consent = phone.recoverySettings.consent.value,
                writeSecureSettings = app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED,
                ignoringBatteryOptimizations = runCatching {
                    app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
                }.getOrNull(),
                lastRestore = phone.recoverySettings.lastRestore.value?.name,
                helperRestores = phone.hub.restores.value,
                lastA11yLoss = phone.recoverySettings.lastA11yLoss.value,
            ),
            models = models(app),
            runs = runs(app),
            update = app.updates.state.value.let { u ->
                DiagnosticsSnapshot.Update(u.status.name, u.latest?.version, u.update?.version)
            },
            speech = speech(app),
        )
    }

    /** When the built-in helper is connected, ask it for its verdict on each other user, then count how many of them can be opened (count only, no package names); null when unavailable. */
    private fun appCopies(app: MikaApp, phone: PhoneServices): String? = runCatching {
        val shell = phone.hub.shell.value ?: return null
        val summary = shell.appCopyUsers()?.getString(ShellService.KEY_COPY_SUMMARY) ?: return null
        val copies = AppCopyReader.read(shell, launchableApps(app.packageManager, app.packageName))
        "$summary; ${copies?.size ?: "?"} app copies"
    }.getOrNull()

    private fun installer(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    private fun language(context: Context): String {
        val shown = context.resources.configuration.locales.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag() ?: "?"
        return "${AppLanguage.current(context).name} ($shown)"
    }

    private fun sandbox(s: SandboxStatus): String = when (s) {
        is SandboxStatus.Unsupported -> "Unsupported: ${s.reason}"
        is SandboxStatus.Installing -> "Installing ${s.percent}%"
        is SandboxStatus.Ready -> "Ready"
        is SandboxStatus.Broken -> "Broken: ${s.reason}"
        SandboxStatus.NotInstalled -> "NotInstalled"
    }

    private fun channel(context: Context, id: String): Int? =
        runCatching { context.getSystemService(NotificationManager::class.java).getNotificationChannel(id)?.importance }.getOrNull()

    /** Status only; do not read keys for diagnostics. */
    private fun adbKey(context: Context): String? = runCatching {
        val src = AndroidAdb.identities(context)
        src.state.name + (src.problem?.let { " ($it)" } ?: "")
    }.getOrNull()

    private fun wifi(context: Context): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrNull()

    private fun models(app: MikaApp): DiagnosticsSnapshot.Models {
        val store = app.providers
        fun role(r: ModelRole) = store.configFor(r)?.let { DiagnosticsSnapshot.RoleModel(it.model, it.baseUrl) }
        val chatGpt = store.chatGptProvider()
        return DiagnosticsSnapshot.Models(
            providers = store.providers.value.map { DiagnosticsSnapshot.Provider(it.kind.name, it.baseUrl, it.models?.size) },
            main = role(ModelRole.Main),
            vision = role(ModelRole.Vision),
            light = role(ModelRole.Light),
            chatGptSignedIn = chatGpt != null && store.hasKey(chatGpt.id),
            chatGptLastFailure = app.chatGptSignIn.lastFailure,
            accountLastFailure = app.chatGptSignIn.lastAccountFailure,
        )
    }

    /** Speech-to-text: the choice, the phone's built-in recognition capability and marker, the recognition services registered on the phone (component name, whether exported, required permissions). */
    private fun speech(app: MikaApp): DiagnosticsSnapshot.Speech? = runCatching {
        val settings = app.speech
        val cap = SystemSpeech.capability(app, settings.systemSilent.value)
        val choice = when (val c = settings.choice.value) {
            SpeechChoice.Auto -> "auto"
            SpeechChoice.System -> "system"
            SpeechChoice.Off -> "off"
            is SpeechChoice.Doubao -> "doubao-asr-2.0 / ${c.resourceId}"
            is SpeechChoice.FunAsr -> "funasr-ws @ ${Diagnostics.host(c.url)}"
            is SpeechChoice.Bailian -> "bailian-asr / ${c.model} / ${c.region.name}" + if (c.workspace.isNotEmpty()) " / workspace" else ""
            is SpeechChoice.Provider -> c.model + (app.providers.providers.value.firstOrNull { it.id == c.providerId }?.let { " @ ${Diagnostics.host(it.baseUrl)}" } ?: " (provider removed)")
        }
        DiagnosticsSnapshot.Speech(
            choice = choice,
            systemAvailable = cap.available,
            onDevice = cap.onDevice,
            silent = settings.systemSilent.value,
            remembered = settings.systemService,
            services = SystemSpeech.services(app).map { svc ->
                // Whether the app it belongs to has the record-permission itself: when a recognition service reports
                // missing permission, this is often the missing one, not Claire's
                val mic = when (com.wangzan101.mika.voice.SpeechServiceMic.state(app, svc.component.substringBefore('/'))) {
                    com.wangzan101.mika.voice.ServiceMic.Granted -> "granted"
                    com.wangzan101.mika.voice.ServiceMic.Missing -> "NOT granted"
                    com.wangzan101.mika.voice.ServiceMic.NotRequested -> "not requested"
                    com.wangzan101.mika.voice.ServiceMic.Unknown -> "unknown"
                }
                svc.component + (if (svc.open) "" else " (not exported)") + (svc.permission?.let { " needs $it" } ?: "") + ", its own mic permission: $mic"
            },
            defaultService = com.wangzan101.mika.voice.SpeechServiceMic.defaultService(app)?.flattenToShortString(),
        )
    }.getOrNull()

    /** Recent runs: outcome and step count come from the summary, the model from the overview; goal, answer and the failure's exact wording are never taken. */
    private fun runs(app: MikaApp): List<DiagnosticsSnapshot.Run> = runCatching {
        val store = RunStore(app)
        val models = store.list().take(RECENT_RUNS * 2).associate { it.id to it.model }
        store.metrics.recent(RECENT_RUNS).reversed().map { m ->
            DiagnosticsSnapshot.Run(m.startedAtMs, m.outcome, m.failKind, m.judged, m.steps, models[m.runId])
        }
    }.getOrDefault(emptyList())
}
