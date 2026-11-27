package com.wangzan101.mika.cap.phone.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.device.shell.A11yLossRecord
import com.wangzan101.mika.core.device.shell.A11yObservation
import com.wangzan101.mika.core.device.shell.A11yStartCheck
import com.wangzan101.mika.core.device.shell.AccessibilityList
import com.wangzan101.mika.core.device.shell.AccessibilityRepair
import com.wangzan101.mika.core.device.shell.BootRestore
import com.wangzan101.mika.core.device.shell.BuiltinShellControl
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.PermissionCommands
import com.wangzan101.mika.core.device.shell.PermissionResult
import com.wangzan101.mika.core.device.shell.RecoveryCapability
import com.wangzan101.mika.core.device.shell.RecoveryInputs
import com.wangzan101.mika.core.device.shell.RecoveryPermissions
import com.wangzan101.mika.core.device.shell.RecoveryPolicy
import com.wangzan101.mika.core.device.shell.RestoreOutcome
import com.wangzan101.mika.core.device.shell.ShellCommandRunner
import com.wangzan101.mika.core.device.shell.ShellTransport
import com.wangzan101.mika.settings.MikaPause
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.RecoverySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Automatic recovery and those capabilities of the built-in helper. Shell commands run through [runner] (via the helper or a paired connection; not provided when neither exists);
 * capabilities and boot results are stored in [settings]; [control] is the built-in helper itself. Created once by [com.wangzan101.mika.cap.phone.PhoneServices];
 * [start] begins watching the connection when the App starts.
 */
class BuiltinShellRecovery(
    context: Context,
    private val runner: ShellCommandRunner,
    private val settings: RecoverySettings,
    private val control: BuiltinShellControl,
) {
    private val app = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    val permissions: RecoveryPermissions by lazy {
        // 助手在运行(不论怎么起的)或配对过,命令就有路可走
        RecoveryPermissions({ runner.takeIf { control.status.value == BuiltinShellStatus.Running || control.paired } }, settings, app.packageName,
            paused = MikaPause.get(app)::paused)
    }

    /**
     * Turn the accessibility service back on automatically when it is not running; the result ([AccessibilityRepair.last]) gives the settings page something to report.
     * When the App itself holds the permission to modify system settings (granted by "restore automatically after reboot") it writes directly without waiting for the helper; only otherwise does it go through the helper.
     */
    val accessibilityRepair = AccessibilityRepair(
        restart = { gap -> if (canWriteSecure()) restartDirectly(gap) else permissions.restartAccessibility(gap) },
        consent = { settings.consent.value },
        direct = ::canWriteSecure,
        allowed = { !FeatureFlags.get(app).quitRequested() },
        paused = MikaPause.get(app)::paused,
    )

    private fun canWriteSecure() =
        app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** The same steps as [RecoveryPermissions.restartAccessibility], except the App writes the system settings itself: add it if absent; if present but not bound, remove it and add it back. */
    private suspend fun restartDirectly(gapMs: Long): PermissionResult = withContext(Dispatchers.IO) {
        val cr = app.contentResolver
        val me = PermissionCommands.accessibilityService(app.packageName)
        fun read() = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        fun write(list: String) {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.ifEmpty { null })
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (list.isEmpty()) 0 else 1)
        }
        try {
            val current = read()
            if (AccessibilityList.contains(current, me)) {
                write(AccessibilityList.without(current, me))
                withContext(NonCancellable) {
                    delay(gapMs)
                    // 去掉之后、加回之前用户暂停了 Mika:不加回,暂停本来就要无障碍关着
                    if (MikaPause.get(app).paused()) return@withContext
                    // 加回失败时把原来的列表(连同总开关)写回,不让 Mika 从列表里消失;这次仍按加回失败报
                    try {
                        write(AccessibilityList.with(read(), me))
                    } catch (e: Exception) {
                        runCatching { write(current.orEmpty()) }
                        throw e
                    }
                }
            } else {
                if (MikaPause.get(app).paused()) return@withContext PermissionResult.Failed("paused")
                write(AccessibilityList.with(current, me))
            }
            if (MikaPause.get(app).paused()) PermissionResult.Failed("paused") else PermissionResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Every time the helper becomes running (just paired, restarted after dropping, already paired when the App starts, reconnected by boot restore), apply the capabilities that are on by default,
     * that the user has not turned off and that are not in effect yet. All those paths end in the same state, so it is handled only here rather than called separately from each place.
     * The accessibility service is watched at the same time: when it is not running and there is a way to write system settings (the App has the permission or the helper is running), turn it on automatically.
     */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        // 完全退出或暂停了 Mika 期间都不动系统设置;恢复时这两个流会重新判断
        val active = combine(FeatureFlags.get(app).quitChanges(), MikaPause.get(app).state) { quit, paused -> !quit && !paused }
        scope.launch { permissions.applyWhenRunning(control.status, active) }
        val service = accessibility
        // Look once at startup: after a force-stop the system removes the service from the list, or leaves it listed but never binds it; record that for diagnostics
        val initial = service.value
        val seen = settings.accessibilitySeen
        scope.launch {
            val connected = withTimeoutOrNull(A11yStartCheck.CONNECT_WAIT_MS) { service.first { it.connected } } != null
            A11yStartCheck.loss(initial, connected, seen)?.let {
                settings.recordA11yLoss(A11yLossRecord(it, System.currentTimeMillis()))
            }
        }
        scope.launch { service.collect { if (it.connected) settings.accessibilitySeen = true } }
        scope.launch { accessibilityRepair.watch(service, control.status, userUnlocked, active) }
    }

    /**
     * Whether the user has unlocked since boot ([UserManager.isUserUnlocked]): before unlocking the accessibility service cannot bind anyway, so automatic repair holds off;
     * on [Intent.ACTION_USER_UNLOCKED] it is read once more so automatic repair can re-evaluate. One instance for the whole process.
     */
    private val userUnlocked: StateFlow<Boolean> by lazy {
        fun read() = runCatching { app.getSystemService(UserManager::class.java)?.isUserUnlocked }.getOrNull() != false
        callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { trySend(read()) }
            }
            val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
            trySend(read()) // changes between registration and the first send
            awaitClose { runCatching { app.unregisterReceiver(receiver) } }
        }.stateIn(scope, SharingStarted.Eagerly, read())
    }

    /** Whether Claire is in the system list (following system settings changes) and whether the service instance is connected (following connects and disconnects). One instance for the whole process. */
    val accessibility: StateFlow<A11yObservation> by lazy { serviceObservations() }

    private fun serviceObservations(): StateFlow<A11yObservation> {
        val cr = app.contentResolver
        val me = PermissionCommands.accessibilityService(app.packageName)
        fun enabled() = AccessibilityList.contains(Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), me)
        val listChanges = callbackFlow {
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) { trySend(Unit) }
            }
            cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
            send(Unit)
            awaitClose { cr.unregisterContentObserver(observer) }
        }
        return combine(listChanges, ScreenAccessibility.instance) { _, service -> A11yObservation(enabled(), service != null) }
            .stateIn(scope, SharingStarted.Eagerly, A11yObservation(enabled(), ScreenAccessibility.instance.value != null))
    }

    /** Unpair: revoke the authorizations while the connection is still there, then remove the pairing. */
    suspend fun unpair() {
        val a11yApplied = settings.consent.value.isApplied(RecoveryCapability.Accessibility)
        val result = permissions.revokeAll()
        // With the connection already gone, the accessibility service can at least disable itself; the battery allowlist and the modify-system-settings permission can only be revoked through the connection
        if (result != PermissionResult.Done && a11yApplied) {
            runCatching { ScreenAccessibility.instance.value?.disableSelf() }
        }
        control.unpair()
    }

    /** After boot, following the active "restore automatically after reboot", turn wireless debugging back on and restart the helper; returns null when nothing needs doing. */
    suspend fun restoreAfterBoot(): RestoreOutcome? {
        // 用户暂停了 Mika:开机也不替他打开无线调试、不重起助手
        if (MikaPause.get(app).paused()) return null
        // 没配对过(助手是电脑经 USB 起的):Mika 自己起不来助手,不去动无线调试;
        // 用 root 启动、Shizuku 模式或 Shizuku 已就绪时,也不去动无线调试
        if (!control.paired || control.rootStart || control.shizukuStart || control.transport == ShellTransport.Shizuku) return null
        val holds = app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        when (BootRestore.gate(Build.VERSION.SDK_INT, settings.consent.value, holds)) {
            BootRestore.Gate.Skip -> return null
            BootRestore.Gate.PermissionMissing -> return RestoreOutcome.PermissionMissing.also(settings::recordRestore)
            BootRestore.Gate.Act -> Unit
        }
        val cr = app.contentResolver
        // Capability check: a rejected write (throwing / returning false), or reading back something other than 1 after writing, both mean this phone does not support it
        val written = DeveloperSettings.turnOnWirelessDebugging(cr) && DeveloperSettings.wirelessDebugging(cr)
        if (!written) return RestoreOutcome.SettingUnavailable.also(settings::recordRestore)

        val outcome = withTimeoutOrNull(BOOT_BUDGET_MS) {
            RecoveryPolicy.recover(
                inputs = {
                    RecoveryInputs(
                        paired = control.paired,
                        // Off a trusted Wi-Fi network the system flips the switch back: re-read every round and stop once it has been flipped back
                        wirelessDebugging = DeveloperSettings.wirelessDebugging(cr),
                        status = control.status.value,
                    )
                },
                attempt = { control.retry() },
            )
        }
        val result = if (outcome?.recovered == true) RestoreOutcome.Restored else RestoreOutcome.NotRecovered
        return result.also(settings::recordRestore)
    }

    private companion object {
        /** About a minute is available after goAsync in a boot broadcast; leave headroom. */
        const val BOOT_BUDGET_MS = 50_000L
    }
}
