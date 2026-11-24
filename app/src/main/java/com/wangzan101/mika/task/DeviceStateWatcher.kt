package com.wangzan101.mika.task

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.view.Display
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Device state: whether the main screen is unusable (screen off or locked), and whether the Agent's app task was moved to the main screen by the user.
 * After the screen turns off the system discards injected touches, so screen-off is treated the same as "locked".
 */
class DeviceStateWatcher(
    context: Context,
    private val topTaskId: (displayId: Int) -> Int,
) {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val keyguard = appContext.getSystemService(KeyguardManager::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = LockState({ LockState.Reading(power.isInteractive, keyguard.isKeyguardLocked, keyguard.isDeviceLocked) }, scope)
    val locked: StateFlow<Boolean> = lock.locked

    // Broadcasts are used only as a "time to look again" signal; the state itself is read from the system every time, never inferred from broadcast order
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            lock.refresh()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        lock.refresh() // changes between before and after registration
    }

    /**
     * Polls the topmost task on both displays: if [taskId] is not topmost on the virtual display but is topmost on the main screen, the user moved it away.
     * Each "moved away" emits only once; after it is moved back, being moved away again emits again. A failed query (shell disconnected) skips that round.
     */
    fun taskMovedAway(displayId: Int, taskId: Int): Flow<Unit> = flow {
        var away = false
        while (true) {
            val now = runCatching {
                isMovedAway(topTaskId(displayId), topTaskId(Display.DEFAULT_DISPLAY), taskId)
            }.getOrNull()
            if (now != null) {
                if (now && !away) emit(Unit)
                away = now
            }
            delay(POLL_MS)
        }
    }

    /** Re-reads immediately as soon as this process has woken up/unlocked, without waiting for the next system broadcast. */
    fun refresh() = lock.refresh()

    fun close() {
        runCatching { appContext.unregisterReceiver(receiver) }
        scope.cancel()
    }

    companion object {
        const val POLL_MS = 500L

        internal fun isMovedAway(agentTop: Int, mainTop: Int, taskId: Int) =
            taskId >= 0 && agentTop != taskId && mainTop == taskId
    }
}

/**
 * Whether the main screen is unusable (screen off or locked). A broadcast triggers one re-read, but on unlock the system sends USER_PRESENT first,
 * while [android.app.KeyguardManager.isKeyguardLocked] only changes once the lock screen has really gone away; at that instant it still reads "locked",
 * and no further broadcast follows. So as long as someone is observing this state and the last read was still "locked" (screen off is a form of locked),
 * it re-reads every [DeviceStateWatcher.POLL_MS] until it reads unlocked. It also keeps re-reading while the screen is off: on some ROMs SCREEN_ON / USER_PRESENT
 * are missed, and if we only waited for broadcasts nothing would trigger a re-read after the screen came on and was unlocked, leaving the task stuck forever at "waiting for you to unlock your phone" (#638).
 * The polling criterion is [Reading.locked] (= screen off or deviceLocked) rather than keyguardLocked: on some ROMs the lock screen goes away
 * before the device-locked state does (keyguardLocked has already turned false while deviceLocked is still true), and watching only keyguardLocked would stop polling early,
 * leaving the task stuck at "waiting for you to unlock your phone" (#590 / #627).
 */
internal class LockState(private val read: () -> Reading, scope: CoroutineScope) {
    data class Reading(val interactive: Boolean, val keyguardLocked: Boolean, val deviceLocked: Boolean) {
        /**
         * Lock detection uses [android.app.KeyguardManager.isDeviceLocked] (whether the device is really locked by PIN/pattern/password),
         * not [android.app.KeyguardManager.isKeyguardLocked]. The latter still reports true after unlocking on some ROMs (e.g. HyperOS),
         * leaving the task stuck at "waiting for you to unlock your phone" (#590). Swipe-to-unlock (no credentials) does not count as "locked", so the task can swipe it away itself.
         * [keyguardLocked] is still kept, used only for telling whether the lock screen is still going away (i.e. whether to keep polling).
         */
        val locked get() = !interactive || deviceLocked
    }

    @Volatile private var last = read()
    private val _locked = MutableStateFlow(last.locked)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun refresh() {
        last = read()
        _locked.value = last.locked
    }

    init {
        scope.launch {
            _locked.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { watched ->
                if (watched) {
                    refresh() // re-read as soon as a subscription starts, so a stale value (from a missed screen-on/unlock) does not survive an extra polling interval
                    while (watched) {
                        delay(DeviceStateWatcher.POLL_MS)
                        if (last.locked) refresh()
                    }
                }
            }
        }
    }
}
