package com.wangzan101.mika.settings

import android.app.AppOpsManager
import android.app.NotificationManager
import com.wangzan101.mika.task.TaskService
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.device.shell.BuiltinShellControl
import com.wangzan101.mika.core.device.shell.ShizukuPlatformState
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.cap.phone.setup.PairingNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.wangzan101.mika.a11y.MikaAccessibilityService

/**
 * Live aggregation of the system state needed to start a task. The built-in assistant, accessibility and developer switches follow callbacks where available;
 * notification permission has no callback, so [refresh] is called when the UI returns to the foreground.
 */
private const val OP_RESTRICTED_SETTINGS = "android:access_restricted_settings"

class SystemStatusMonitor(context: Context, private val shell: BuiltinShellControl) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow(read())
    val status: StateFlow<SystemStatus> = _status.asStateFlow()

    private var scope: CoroutineScope? = null
    private val a11ySetting = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        app.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, a11ySetting,
        )
        ScreenAccessibility.instance.onEach { refresh() }.launchIn(s)
        // The onboarding page must follow the system switches live: once the user enables them in system settings, returning shows the next step
        app.contentResolver.registerContentObserver(DeveloperSettings.developerOptionsUri, false, a11ySetting)
        app.contentResolver.registerContentObserver(DeveloperSettings.wirelessDebuggingUri, false, a11ySetting)
        app.contentResolver.registerContentObserver(DeveloperSettings.usbDebuggingUri, false, a11ySetting)
        shell.status.onEach { _status.value = read() }.launchIn(s)
        val manager = PhoneServices.of(app).shellManager
        manager.shizuku.state.onEach { _status.value = read() }.launchIn(s)
        manager.transport.onEach { _status.value = read() }.launchIn(s)
        refresh()
    }

    fun stop() {
        scope?.cancel()
        scope = null
        app.contentResolver.unregisterContentObserver(a11ySetting)
    }

    fun refresh() {
        (app as? PhoneServices.Owner)?.phone?.shellManager?.refreshShizukuAsync()
        _status.value = read()
    }

    private fun read(): SystemStatus {
        val shizukuState = (app as? PhoneServices.Owner)?.phone?.shellManager?.shizuku?.state?.value
            ?: ShizukuPlatformState.Unavailable
        return SystemStatus(
            sdkInt = Build.VERSION.SDK_INT,
            builtinShell = shell.status.value,
            accessibility = accessibilityState(),
            userLocked = userLocked(),
            notificationsAllowed = app.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
            attentionLowered = channelLowered(TaskService.CHANNEL_ATTENTION),
            resultLowered = channelLowered(TaskService.CHANNEL_RESULT),
            // Create the channel up front: before pairing it can already be seen whether it is disabled or lowered, and its settings page can be opened directly
            pairingChannel = PairingNotifier.ensureChannel(app).health,
            attentionImportance = channelImportance(TaskService.CHANNEL_ATTENTION),
            resultImportance = channelImportance(TaskService.CHANNEL_RESULT),
            progressImportance = channelImportance(TaskService.CHANNEL_PROGRESS),
            developerOptions = DeveloperSettings.developerOptions(app.contentResolver),
            wirelessDebugging = DeveloperSettings.wirelessDebugging(app.contentResolver),
            wirelessDebuggingMissing = DeveloperSettings.wirelessDebuggingMissing(app.contentResolver),
            developerSettingsMissing = DeveloperSettings.developerOptionsPageMissing(app.packageManager),
            usbDebugging = DeveloperSettings.usbDebugging(app.contentResolver),
            restrictedSettings = restrictedSettings(),
            helperRestart = shell.restart,
            rootStart = shell.rootStart,
            shellTransport = shell.transport,
            shizuku = shizukuState,
            wirelessPaired = shell.paired,
            shizukuStart = shell.shizukuStart,
        )
    }

    private fun channelImportance(id: String): Int? =
        app.getSystemService(NotificationManager::class.java).getNotificationChannel(id)?.importance

    /** The channel exists but its importance is below "pop up" (lowered by the user or the system, which the app cannot change back). */
    private fun channelLowered(id: String) = app.getSystemService(NotificationManager::class.java).getNotificationChannel(id)
        ?.let { it.importance < NotificationManager.IMPORTANCE_HIGH } ?: false

    /**
     * Whether the system will block enabling accessibility. Decided by capability: Android 13 and later have the "restricted settings" permission, and if it can be read and is allowed there is no block;
     * when it cannot be read (the system lacks it or refuses access) the system is assumed to possibly block, which merely offers one extra entry point.
     */
    private fun restrictedSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val mode = runCatching {
            app.getSystemService(AppOpsManager::class.java).checkOpNoThrow(OP_RESTRICTED_SETTINGS, Process.myUid(), app.packageName)
        }.getOrNull()
        return mode != AppOpsManager.MODE_ALLOWED
    }

    private fun accessibilityState(): AccessibilityState {
        if (ScreenAccessibility.instance.value != null) return AccessibilityState.On
        val enabled = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').mapNotNull { ComponentName.unflattenFromString(it.trim()) }
        val me = ComponentName(app, MikaAccessibilityService::class.java)
        return if (me in enabled) AccessibilityState.EnabledNotRunning else AccessibilityState.Off
    }

    /** The device has not been unlocked once since boot: the accessibility service failing to bind then is system behavior, not a fault. */
    private fun userLocked(): Boolean =
        runCatching { app.getSystemService(UserManager::class.java)?.isUserUnlocked }.getOrNull() == false
}
