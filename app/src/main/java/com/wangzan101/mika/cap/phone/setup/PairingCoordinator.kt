package com.wangzan101.mika.cap.phone.setup

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.core.device.shell.BuiltinShellControl
import com.wangzan101.mika.core.device.shell.PairingCode
import com.wangzan101.mika.settings.MikaPause
import com.wangzan101.mika.settings.ui.PhoneSetupCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The whole process of one pairing: find the pairing window, read the pairing code ourselves when accessibility is on ([PairingCodeReader]),
 * pop a notification with an input field when it cannot be read, hand the code to the built-in helper [control], and show the result.
 * It lives with the process, not with the UI (when the user looks at the pairing code in system settings, Claire's UI is already out of the foreground): created once by [com.wangzan101.mika.cap.phone.PhoneServices].
 */
class PairingCoordinator(context: Context, private val control: BuiltinShellControl) {
    private val app = context.applicationContext
    /** Actions such as pairing, retrying and unpairing also run here: they must finish even after the UI is closed. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watch: Job? = null
    private val deadline = SlidingDeadline(WINDOW_MS) { SystemClock.elapsedRealtime() }
    /** What the notification currently shows; compare before re-alerting, and do not pop the old one again once it has been replaced (e.g. by a just-submitted code). */
    @Volatile private var shown: PairingNotice? = null
    private val _repairing = MutableStateFlow(false)
    /** The user tapped "pair again" and the new pairing has not started yet: even a running helper must wait for the new pairing. */
    val repairing: StateFlow<Boolean> = _repairing
    /**
     * A code was submitted but pairing has not started yet (from [submit] until [pair] sets the status to "pairing"): during this window the notification is always "pairing".
     * [begin] inside [submit] makes the discovery state return to "searching" on the spot; otherwise the notification would compute another one from the pre-submit state
     * ("pairing expired" when pairing has expired, or an input field that rings again when re-entering after a wrong code). Notifications that follow status updates do not end at this point either, see [PairingNotice.follow].
     */
    @Volatile private var submitted = false

    fun startRepair() { _repairing.value = true }

    fun cancelRepair() { _repairing.value = false }

    /** Start one pairing: as soon as the status becomes "pairing", end the wait for a re-pair (so a stale running status cannot decide first that it is already done) and the post-submit wait ([submitted]). */
    suspend fun pair(code: String, port: Int?) = coroutineScope {
        Log.i(TAG, "pairing with ${if (port != null) "port $port" else "the found port"}")
        val job = launch(start = CoroutineStart.UNDISPATCHED) { control.startPairing(code, port) }
        _repairing.value = false
        submitted = false
        job.join()
    }

    /** Start watching for pairing; if already watching, recount the deadline as [WINDOW_MS] from now rather than starting over (so the notification does not pop up from scratch again). */
    @Synchronized
    fun begin() {
        if (MikaPause.get(app).paused()) {
            end()
            return
        }
        deadline.extend()
        control.startDiscovery()
        if (watch?.isActive == true) {
            Log.i(TAG, "already watching; deadline extended")
            return
        }
        Log.i(TAG, "watching for pairing (status ${control.status.value}, accessibility ${ScreenAccessibility.instance.value != null})")
        watch = scope.launch {
            val reader = launch { PairingCodeReader.run(app, control, this@PairingCoordinator) }
            val notices = launch {
                // Each of the three is fed into combine by its own coroutine, and arrival order need not match change order (an earlier "pairing" change may arrive later): always compute from the values at this moment.
                // Reading in reverse write order, seeing the later write guarantees the earlier one is visible too: submit writes submitted before making discovery return to "searching",
                // and pair writes "pairing" before clearing repairing and submitted
                combine(control.status, control.pairingDiscovery, _repairing) { _, _, _ ->
                    val d = control.pairingDiscovery.value
                    val pending = submitted
                    val r = _repairing.value
                    PairingNotice.follow(control.status.value, d, r, pending, paused = MikaPause.get(app).paused())
                }
                    .distinctUntilChanged()
                    .onEach { (notice, done) -> Log.i(TAG, "notice ${notice?.let { it::class.simpleName } ?: "none"} (discovery ${control.pairingDiscovery.value}, status ${control.status.value})${if (done) "; done" else ""}") }
                    .transformWhile { emit(it.first); !it.second }
                    // Drop pending pops and re-alerts as soon as the state changes
                    .collectLatest { notice -> notice?.let { post(it) } }
            }
            if (!deadline.await(notices)) {
                Log.i(TAG, "stopped watching: no pairing within ${WINDOW_MS / 60_000} min")
                notices.cancel()
                cancelNotice()
            }
            reader.cancel()
            control.stopDiscovery()
        }
    }

    /** Post [notice]; if nobody reacts, pop it again after a while, up to a limit. */
    private suspend fun post(notice: PairingNotice) {
        delay(PairingNotice.delayBefore(notice, autoRead = ScreenAccessibility.instance.value != null))
        show(notice)
        var sent = 0
        while (true) {
            delay(PairingNotice.realertAfter(notice, sent) ?: return)
            if (shown != notice) return
            show(notice)
            sent++
        }
    }

    private fun show(notice: PairingNotice) {
        Log.i(TAG, "show notification ${notice::class.simpleName}")
        shown = notice
        PairingNotifier.show(app, notice)
    }

    private fun cancelNotice() {
        shown = null
        PairingNotifier.cancel(app)
    }

    @Synchronized
    fun end() {
        if (watch?.isActive == true) Log.i(TAG, "stopped watching")
        watch?.cancel()
        watch = null
        _repairing.value = false
        control.stopDiscovery()
        cancelNotice()
    }

    /**
     * A code submitted from the notification or the guide page. If it is not 6 digits, show the re-enter hint in place, do not try it, and return null; if it is, switch to "pairing" right away so the input field does not spin forever,
     * and return this pairing (the notification side uses it to wait for completion before ending the broadcast, see [PairingCodeInput]).
     */
    fun submit(raw: String, port: Int?): Job? {
        val code = PairingCode.normalize(raw)
        if (code == null) {
            Log.i(TAG, "entered code is not 6 digits")
            show(PairingNotice.EnterCode(port, PhoneSetupCopy.NOTIFY_CODE_INVALID))
            return null
        }
        submitted = true
        begin()
        show(PairingNotice.Working)
        return scope.launch { pair(code, port) }
    }

    private companion object {
        const val TAG = "MikaPairing"
        /** The pairing window stays open only for a limited time; if pairing has not succeeded this long after the last [begin], stop searching and start again on the next [begin]. */
        const val WINDOW_MS = 10 * 60_000L
    }
}

/**
 * Receiver for the notification's input field. The system may freeze a backgrounded Claire: it thaws it to receive the broadcast and may freeze it again as soon as the broadcast ends,
 * leaving pairing half done. So wait for pairing to finish (or at most [HOLD_MS]) before ending the broadcast; during that time the process counts as handling a broadcast and is not frozen as a background process.
 */
open class PairingCodeInput : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotifier.KEY_CODE)?.toString() ?: return
        val port = intent.getIntExtra(PairingNotifier.EXTRA_PORT, -1).takeIf { it > 0 }
        val pairing = PhoneServices.of(context).pairing
        val job = pairing.submit(code, port) ?: return
        val pending = goAsync()
        pairing.scope.launch {
            try {
                withTimeoutOrNull(HOLD_MS) { job.join() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** A broadcast left unfinished for 10 seconds at the very least counts as unresponsive, so leave headroom. Enough to find the pairing port (5 s) and then pair; if the connection is frozen afterwards it is only later, the pairing is already recorded. */
        const val HOLD_MS = 8_000L
    }
}
