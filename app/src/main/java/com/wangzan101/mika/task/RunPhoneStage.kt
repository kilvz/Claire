package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.cap.phone.AppPacePlan
import com.wangzan101.mika.core.device.shell.AccessibilityRepair
import com.wangzan101.mika.core.device.shell.HelperRestart
import com.wangzan101.mika.settings.ui.StatusCopy
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.os.RemoteException
import android.util.Log
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.AppCopyReader
import com.wangzan101.mika.cap.phone.a11y.CalendarBulkWriter
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.PhoneUnavailable
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.Prerequisites
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.wangzan101.mika.core.cap.web.WEB
import com.wangzan101.mika.task.TaskRun.Companion.SHELL_WAIT_MS
import com.wangzan101.mika.task.TaskRun.Companion.DISPLAY_RETRY_MS
import com.wangzan101.mika.task.TaskRun.Companion.WAITING_DISPLAY
import com.wangzan101.mika.task.TaskRun.Companion.A11Y_WAIT_MS
import com.wangzan101.mika.task.TaskRun.Companion.NO_PHONE_HELPER
import com.wangzan101.mika.task.TaskRun.Companion.NO_PHONE_HELPER_USB
import com.wangzan101.mika.task.TaskRun.Companion.NO_PHONE_HELPER_SHIZUKU
import com.wangzan101.mika.task.TaskRun.Companion.TAG

// Run assembly: attach the phone, prepare the phone ports, lock-screen gate
internal suspend fun TaskRun.assemblePhone(a: RunAssembly): RunPhone {
    var watcher by a::watcher
    var afterTask by a::afterTask
    var accessGuard by a::accessGuard
    var smsPrivacy by a::smsPrivacy
    val appAccess = (service.application as MikaApp).appAccess
    val metrics = phoneRuntime.mainScreenMetrics()
    // In-app interaction pace (Settings › Task run; apps without an override follow the global one; evals and
    // on-device acceptance runs override it via hooks, and when a hook sets a pace, per-app overrides are ignored — evals need determinism)
    val hooked = env.evalHooks.operatingPace
    val pace = AppPacePlan(hooked ?: FeatureFlags.get(service).operatingPace(), if (hooked == null) FeatureFlags.get(service).appPaceOverrides() else emptyMap())
    // Whether the helper is connected; treated as connected until the phone is attached (web and sandbox steps don't look at it)
    val shellUp = MutableStateFlow(true)
    val states = DeviceStateWatcher(service) { id -> phone?.shell?.topTaskId(id) ?: -1 }.also { watcher = it }

    val phoneScreen = LazyPhoneScreen(metrics.widthPixels, metrics.heightPixels,
        listApps = { withContext(Dispatchers.IO) { launchableApps(service.packageManager, service.packageName) } },
        // Apps in another user profile must ask the built-in helper: only ask when the helper is already connected; don't attach it just for that
        appCopies = {
            phoneServices.hub.shell.value?.let { s ->
                withContext(Dispatchers.IO) { runCatching { AppCopyReader.read(s, launchableApps(service.packageManager, service.packageName)) }.getOrNull() }
            }.orEmpty()
        },
        // shareWorkspaceAudio is always supplied to AndroidScreen above, so this capability check stays lazy.
        canShareAudio = true,
        calendarBulkWriter = { events ->
            withContext(Dispatchers.IO) { CalendarBulkWriter.insert(service, events) }
        },
    ) { connectPhone(a, appAccess, metrics, pace, shellUp, states) }
    val accessedPhone = com.wangzan101.mika.core.screen.uistate.AppAccessScreenPort(
        phoneScreen, appAccess,
        packages = { phone?.screen?.accessPackages().orEmpty() },
        protectedUi = { appAccess.sensitiveUi }, ownPackage = service.packageName,
        windowToken = { ScreenAccessibility.windowGeneration() },
    ).also { accessGuard = it }
    val privatePhone = com.wangzan101.mika.core.cap.phone.sms.SmsPrivateScreen(accessedPhone).also { smsPrivacy = it }
    afterTask = phoneScreen.afterTask
    return RunPhone(appAccess, metrics, pace, shellUp, states, phoneScreen, accessedPhone, privatePhone)
}

/** Attach the phone: connect the built-in helper, wait for accessibility, and prepare the virtual display (created only at the first launch_app); if anything is missing, report phone_unavailable. */
internal suspend fun TaskRun.connectPhone(a: RunAssembly, appAccess: com.wangzan101.mika.settings.AppAccessStore, metrics: DisplayMetrics, pace: AppPacePlan, shellUp: MutableStateFlow<Boolean>, states: DeviceStateWatcher): AndroidScreen {
    var connection by a::connection
    var mute by a::mute
    var shellDeath by a::shellDeath
    var shellBinder by a::shellBinder
    var dispatcher by a::dispatcher
    val resources = a.resources
    val side = a.side
    // The version requirement applies only to phone operations; older systems can still chat, use the web and the sandbox, and should not be steered into pairing an unsupported helper.
    if (Build.VERSION.SDK_INT < Prerequisites.MIN_SDK) throw PhoneUnavailable(TaskService.TOO_OLD)
    val conn = connection ?: phoneServices.sharedShell.acquire().also { connection = it }
    val shell = withTimeoutOrNull(SHELL_WAIT_MS) { conn.shell.filterNotNull().first() }
        ?: run {
            phoneServices.setupPrompt.request(conversationId, runState.runId.value)
            // A helper that was only started by a USB command has stopped: say plainly that the command must be run again instead of sending people to look for wireless debugging
            throw PhoneUnavailable(
                when (phoneServices.shellControl.restart) {
                    HelperRestart.UsbCommand -> NO_PHONE_HELPER_USB
                    HelperRestart.Shizuku -> NO_PHONE_HELPER_SHIZUKU
                    HelperRestart.Wireless, HelperRestart.None -> NO_PHONE_HELPER
                },
            )
        }
    // If the service is stuck in "enabled but not running" and is auto-restarting, wait a little longer; continue once it recovers, report the error as usual if it gives up
    val a11y = AccessibilityRepair.awaitService(ScreenAccessibility.instance, phoneServices.recovery.accessibilityRepair.phase, A11Y_WAIT_MS)
        ?: throw PhoneUnavailable(StatusCopy.accessibilityUnavailable(phoneServices.recovery.accessibility.value.enabled))
    recovery.beginRun(shell, key)
    // Only tasks that have used the phone follow the shell's fate: when the shell dies, the virtual display and the apps on it die with it
    shellDeath = IBinder.DeathRecipient {
        shellDied = true
        val running = loop
        if (running != null) running.cancel() else job?.cancel()
    }.also { shell.asBinder().linkToDeath(it, 0) }
    shellBinder = shell.asBinder()
    side.launch { conn.shell.map { it != null }.collect { shellUp.value = it } }
    val agentDisplay = LazyAgentDisplay(metrics.widthPixels, metrics.heightPixels, sizeOf = { it.width to it.height }) {
        suspend fun create() = resources.acquire(Dispatchers.IO, AgentDisplay::release) { AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, service.getSystemService(DisplayManager::class.java)) }
        try {
            create()
        } catch (e: Exception) {
            if (e is CancellationException || e !is IllegalStateException && e !is RemoteException) throw e
            if (!parallel || !service.otherRunHasDisplay(this@connectPhone)) throw e
            // The framework can't create a second display: no more parallelism for the rest of this run; this task queues behind and rebuilds once other tasks have released their displays
            service.taskState.markSecondDisplayFailed()
            publish(TaskState.Held(GateReason.queued(WAITING_DISPLAY)))
            while (service.otherRunHasDisplay(this@connectPhone)) delay(DISPLAY_RETRY_MS)
            create()
        }.also { d ->
            activePhoneDisplay = d
            runState._displayId.value = d.displayId
            runState._pipInput.value = PipInputImpl(shell, d)
            side.launch {
                combine(d.frames, runState._appShown.flow) { frame, shown -> frame to shown }.collect { (frame, shown) ->
                    if (dispatcher?.surface != WEB) {
                        val allowed = frame.takeIf { a.previewAllowed() }
                        if (dispatcher?.surface != WEB) {
                            runState._frames.value = allowed
                            runState._phoneFrames.value = allowed.takeIf { shown }
                        }
                    }
                }
            }
            side.launch {
                appAccess.state.collect {
                    if (dispatcher?.surface != WEB && !a.previewAllowed()) {
                        runState._frames.value = null
                        runState._phoneFrames.value = null
                    }
                }
            }
        }
    }
    val audio = recovery.audioMute(shell, key).also { mute = it }
    val muter = phoneRuntime.appMuter(shell, audio)
    // Names in the claim registry: the copy in another user profile is claimed separately so it doesn't block the original; whether the holding task still uses it is asked of the built-in helper
    fun claimKey(pkg: String, user: Int?) = if (user == null) pkg else "$pkg@$user"
    fun stillUsed(pkg: String, user: Int?): () -> Boolean = {
        runCatching { if (user == null) shell.displayOfPackage(pkg) else shell.displayOfPackageInUser(pkg, user) }.getOrDefault(-1) >= 0
    }
    val screen = AndroidScreen(
        a11y, shell, agentDisplay, { recovery.markClipHeld(it) },
        clipLock = service.clipLock, checkOtherTasks = parallel,
        reserve = { pkg -> service.reservePackage(pkg, key, stillUsed(pkg, null)) },
        reserveCopy = { pkg, user -> service.reservePackage(claimKey(pkg, user), key, stillUsed(pkg, user)) },
        appFree = { pkg, user -> service.packageFree(claimKey(pkg, user), key, stillUsed(pkg, user)) },
        // The model asked to wait: while waiting the UI says "waiting for another task to finish using an app" (still working, not something for you to handle), and the previous line is restored afterwards
        awaitApp = { pkg, user, timeoutMs, free ->
            phoneRuntime.waitingForApp(ConfirmationNotifier.appLabel(service, pkg)) { service.awaitPackage(claimKey(pkg, user), key, timeoutMs, free) }
        },
        clipSaved = { original, ours -> recovery.saveClip(original, ours) },
        handedOver = { handoverRef?.handedOver == true },
        // Typing comes out in bursts; apps that limit browsing speed (human-like/override in settings) type more slowly
        typing = { pkg -> pace.of(pkg).typing() },
        // Mute before launch so the first seconds are silent too; restore if the launch is rejected
        beforeOpen = muter::beforeOpen,
        notOpened = muter::notOpened,
        appAccess = appAccess,
        shareAudioFile = { path -> phoneRuntime.shareWorkspaceAudio(path) },
        shareImageFile = { path -> shareWorkspaceImage(path) },
    )
    val side0 = PhoneSide(shell, a11y, screen, agentDisplay, muter)
    phone = side0
    phoneRuntime.watchTarget(side0, states)
    return screen
}

    // After moving back to the virtual display, wait for the window to appear so the Agent can read the screen next
    internal suspend fun TaskRun.moveTask(taskId: Int, to: Int): Boolean {
        val p = phone ?: return false
        val moved = withContext(Dispatchers.IO) { runCatching { p.shell.moveTask(taskId, to) }.getOrDefault(false) }
        if (moved && to == p.display.current?.displayId) p.screen.awaitContent()
        return moved
    }
    // The app being moved back was already on the virtual display, so the display must exist; without a display there is nothing to move back to
    internal suspend fun TaskRun.moveBackToAgent(taskId: Int): Boolean {
        val id = phone?.display?.current?.displayId ?: return false
        return moveTask(taskId, id)
    }

internal suspend fun TaskRun.assembleGate(ph: RunPhone): TaskGate {
    val shellUp = ph.shellUp
    val states = ph.states
    val app = service.application as MikaApp
    // WeChat uses only the independent authorization of the currently bound session; the Channel origin and the existing confirmation/handover rules are still kept.
    // UI tasks follow the on-device switch (rechecked on every decision); scheduled tasks need either the remote or the local switch.
    val unlockAuthorized = taskUnlockAuthorization(provenance.origin, channelPeer,
        localUnlockEnabled = { app.deviceAccess.settings.localEnabled }) { peer ->
        app.wechat.store.canUnlock(peer)
    }
    // On-device tasks (UI/scheduled) use the local path when the local switch is on; remote origins always take the remote path
    fun useLocalUnlock() = (provenance.origin == RunOrigin.Ui || provenance.origin == RunOrigin.Trigger) &&
        app.deviceAccess.settings.localEnabled
    var unlockWasLocal = false
    val taskGate = TaskGate(states.locked, shellUp,
        refreshLocked = states::refresh,
        unattendedUnlock = unlockAuthorized?.let { authorized ->
            {
                // Re-locking uses the accessibility global action and the guard uses notifications; if either is missing the "lock it back" obligation can't be honoured, so don't unlock
                if (ScreenAccessibility.instance.value == null) {
                    states.refresh()
                    "accessibility_unavailable"
                } else if (!service.startUnlockGuard()) {
                    states.refresh()
                    "notification_unavailable"
                } else {
                    unlockWasLocal = useLocalUnlock()
                    val result = try {
                        if (unlockWasLocal) app.deviceAccess.access.unlockLocal(authorized)
                        else app.deviceAccess.access.unlock(authorized)
                    } catch (e: CancellationException) {
                        // After cancellation the system may still complete already-injected input: switch to a persistent warning that asks the user to check the lock screen
                        service.notifyRelockFailed()
                        throw e
                    } catch (e: Exception) {
                        service.notifyRelockFailed()
                        throw e
                    }
                    // Attach the re-lock obligation only when the unlock really happened; if it wasn't locked (the user was using it), remove the guard just attached
                    if (result.performedUnlock) service.noteTaskUnlock() else service.clearUnlockGuardIfSafe()
                    states.refresh()
                    Log.i(TAG, "${provenance.origin} unlock finished: ${result.status}")
                    result.status
                }
            }
        },
        // A task running on a local authorization (one stored on this device) is not killed after an unlock failure: it waits for the user to unlock manually instead
        recoverUnlockFailure = { unlockAuthorized != null },
        onRecoveryFailure = { status -> service.notifyUnlockRecoveryNeeded(conversationId, status) },
        unlockFailureMessage = {
            tr(when {
                provenance.origin == RunOrigin.Channel -> R.string.wechat_unlock_failed
                // UI tasks only ever take the local path; scheduled tasks get guidance for the path actually taken this time
                provenance.origin == RunOrigin.Ui || unlockWasLocal -> R.string.device_local_unlock_failed
                else -> R.string.trigger_unlock_failed
            })
        },
    ) {
        val t = phone?.screen?.target?.value
        t != null && t.taskId >= 0 && moveBackToAgent(t.taskId)
    }
    gate = taskGate
    return taskGate
}
