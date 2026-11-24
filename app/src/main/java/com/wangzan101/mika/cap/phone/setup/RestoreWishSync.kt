package com.wangzan101.mika.cap.phone.setup

import android.util.Log
import com.wangzan101.mika.cap.phone.shell.HelperHub
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.core.device.shell.A11yObservation
import com.wangzan101.mika.core.device.shell.RecoveryCapability
import com.wangzan101.mika.core.device.shell.RecoveryConsent
import com.wangzan101.mika.core.device.shell.RestoreWish
import com.wangzan101.mika.core.device.shell.RestoreWishSender
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

/**
 * Hands "should Claire be started again after it is killed" ([RestoreWish]) to the built-in helper: whenever the capsule mode, whether a task is running, "turn on accessibility for me",
 * whether Claire's accessibility service is in the system list, or whether the user chose a full quit changes, or the helper changes, hand it over again through [IMikaShell.setRestoreWish].
 * The helper cannot read Claire's settings and, once Claire is dead, acts only on the copy handed over last.
 */
class RestoreWishSync(
    private val hub: HelperHub,
    private val capsuleMode: Flow<CapsuleMode>,
    private val taskRunning: Flow<Boolean>,
    private val consent: Flow<RecoveryConsent>,
    private val accessibility: Flow<A11yObservation>,
    /** The user chose "fully quit": even if the capsule is set to "always show" and a task is still running, the helper must not start Claire again. */
    private val quit: Flow<Boolean>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sender = RestoreWishSender<IMikaShell> { shell, wish ->
        runCatching { shell.setRestoreWish(wish.flags) }
            .onFailure { Log.w(TAG, "cannot hand the restore wish to the helper", it) }
            .isSuccess
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        var lastOn: Boolean? = null
        // A Claire service disappearing from the list is only handed over after a while: when a process is force-stopped the system removes it first, and that must not change the wish;
        // only when it stays absent (the user turned it off in system settings) do we stop writing it back on their behalf
        val accessibilityOn = accessibility.map { it.enabled }.distinctUntilChanged().mapLatest { on ->
            if (!on && lastOn == true) delay(OFF_SETTLE_MS)
            lastOn = on
            on
        }
        val wishes = combine(capsuleMode, taskRunning, consent, accessibilityOn, quit) { mode, busy, c, on, quit ->
            RestoreWish.of(mode == CapsuleMode.Always && !quit, busy && !quit, c.wants(RecoveryCapability.Accessibility), on, quit)
        }.distinctUntilChanged()
        scope.launch { combine(hub.shell, wishes, ::Pair).collect { (shell, wish) -> sender.update(shell, wish) } }
    }

    private companion object {
        const val TAG = "MikaRestoreWish"
        const val OFF_SETTLE_MS = 3_000L
    }
}
