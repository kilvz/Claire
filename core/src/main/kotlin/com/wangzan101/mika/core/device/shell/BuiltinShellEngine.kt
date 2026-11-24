package com.wangzan101.mika.core.device.shell

import com.wangzan101.mika.core.device.adb.AdbConnectionException
import com.wangzan101.mika.core.device.adb.AdbKeyUnreadableException
import com.wangzan101.mika.core.device.adb.AdbPairingException
import com.wangzan101.mika.core.device.adb.AdbServiceTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Platform capabilities the engine needs. Blocking calls all run on the IO dispatcher. */
interface BuiltinShellPlatform {
    fun paired(): Boolean
    fun setPaired(paired: Boolean)
    /** Wireless debugging switch; null when it cannot be read (does not block further progress). */
    fun wirelessDebugging(): Boolean?
    fun wifiConnected(): Boolean
    suspend fun findPairingPort(timeoutMs: Long): Int?
    /** Pairs with the pairing code; throws [AdbPairingException] on failure. */
    fun pair(port: Int, code: String)
    /** Connects to the local adbd and launches the helper (does not wait for handoff); throws [AdbConnectionException] on failure, and [AdbKeyUnreadableException] when the stored key cannot be read. */
    suspend fun launchHelper()
    /** Asks the running helper to revert its changes and exit; does nothing when there is none. */
    fun stopHelper()
    /** Deletes the pairing key and the handoff nonce; also turns off "start with root" (unpairing means stopping phone control, so the watchdog no longer brings the helper back via su). */
    fun forget()
    /** The helper was accepted while unpaired (started by a computer over USB): once it stops, tell the user how to start it again rather than treating it as never set up. Persisted. */
    fun usbStarted(): Boolean
    fun setUsbStarted(on: Boolean)
    /** Whether "start with root" is usable right now (the user's switch, whether su exists, su's last answer); by default this path is unavailable. */
    fun rootStart(): RootStart = RootStart.Off
    /**
     * Launches the helper as the shell identity via su (does not wait for handoff) and returns su's answer: on [SuAnswer.Granted] the helper is already starting and the engine waits for the handoff;
     * null when su can no longer be found on the phone. Throws on failure (treated as an unknown cause).
     */
    suspend fun launchHelperAsRoot(): SuAnswer? = null
    /** Records su's answer, persisted; null means forgetting the last denial (the user tapped retry). */
    fun setRootAnswer(answer: SuAnswer?) {}
    /** The user explicitly tapped retry after a Root failure. Returns true when the platform has started an asynchronous authorization that will notify the engine once done. */
    fun requestRootStart(): Boolean = false
    /** Currently selected launch transport; defaults to [ShellTransport.Auto]. */
    fun transport(): ShellTransport = ShellTransport.Auto
    /** Persists and updates the launch transport selection. */
    fun setTransport(transport: ShellTransport) {}
    /** Current Shizuku install, running and authorization state; unavailable by default. */
    fun shizukuState(): ShizukuPlatformState = ShizukuPlatformState.Unavailable
    /** Launches the helper via Shizuku (does not wait for handoff); returns null on success and the specific cause on failure. */
    suspend fun launchHelperViaShizuku(): BuiltinShellFailure? = BuiltinShellFailure.ShizukuNotRunning
}

/** Watchdog interval for restarting the helper: after the n-th failure wait base·2^(n-1), capped at [maxMs]; after [maxAttempts] consecutive failures it stops and waits for the user or for a switch change. */
data class Backoff(val baseMs: Long = 2_000, val maxMs: Long = 60_000, val maxAttempts: Int = 5) {
    fun delayAfter(failures: Int): Long? {
        if (failures >= maxAttempts) return null
        return (baseMs shl (failures - 1).coerceIn(0, 20)).coerceAtMost(maxMs)
    }
}

object BuiltinShellFailures {
    fun ofPairing(reason: AdbPairingException.Reason) = when (reason) {
        AdbPairingException.Reason.WRONG_CODE -> BuiltinShellFailure.WrongCode
        // The pairing port is unreachable: the pairing window is already closed (the port disappears along with the window)
        AdbPairingException.Reason.CONNECT_FAILED -> BuiltinShellFailure.PairingWindowClosed
        AdbPairingException.Reason.TLS_FAILED, AdbPairingException.Reason.PROTOCOL -> BuiltinShellFailure.Unknown
        AdbPairingException.Reason.NOT_LOCAL -> BuiltinShellFailure.NotLocal
    }

    fun ofConnection(reason: AdbConnectionException.Reason) = when (reason) {
        AdbConnectionException.Reason.UNAUTHORIZED -> BuiltinShellFailure.AuthorizationRevoked
        AdbConnectionException.Reason.NOT_LOCAL -> BuiltinShellFailure.NotLocal
        AdbConnectionException.Reason.CONNECT_FAILED,
        AdbConnectionException.Reason.TLS_FAILED,
        AdbConnectionException.Reason.PROTOCOL -> BuiltinShellFailure.ConnectFailed
    }

    /** Whether the watchdog retrying on its own helps: a revoked key, an invalid address, no Wi-Fi, or missing Shizuku/authorization must wait for the user or a switch change. */
    fun retryable(f: BuiltinShellFailure) = f == BuiltinShellFailure.ConnectFailed ||
        f == BuiltinShellFailure.HelperStartFailed || f == BuiltinShellFailure.ShizukuBindFailed ||
        f == BuiltinShellFailure.Unknown
}

/**
 * State and watchdog of the built-in helper. A non-null [helper] means the helper's Binder is in hand (Running);
 * when it is null: if Claire can start it itself (the user enabled "start with root" and it is usable, or pairing has been done) it first waits [graceMs] (a still-alive helper hands off on its own once the Claire process is up),
 * then starts one: first via su (see [BuiltinShellPlatform.rootStart]), and if that fails or is not enabled, via adbd; on failure it retries according to [backoff].
 * When su denies or does not answer, that is recorded and it stops asking on its own, waiting for the user to tap retry.
 * If wireless debugging is off when the helper needs to start, and [autoOn] is present, it first turns it on for the user (conditions and stopping rules are in [WirelessDebuggingAutoOn]).
 */
class BuiltinShellEngine(
    private val platform: BuiltinShellPlatform,
    private val helper: StateFlow<Any?>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val backoff: Backoff = Backoff(),
    private val graceMs: Long = 3_000,
    private val handoffTimeoutMs: Long = 15_000,
    private val discoveryTimeoutMs: Long = 10 * 60_000L,
    private val pairingPortTimeoutMs: Long = 5_000,
    private val autoOn: WirelessDebuggingAutoOn? = null,
) : BuiltinShellControl {
    private val _status = MutableStateFlow(initialStatus())
    override val status: StateFlow<BuiltinShellStatus> = _status.asStateFlow()
    private val _discovery = MutableStateFlow<PairingDiscovery>(PairingDiscovery.Idle)
    override val pairingDiscovery: StateFlow<PairingDiscovery> = _discovery.asStateFlow()

    /** Exception type and message of the most recent pairing or connection failure (for diagnostics, not redacted); cleared once connected. */
    @Volatile var lastError: String? = null
        private set

    private val launchLock = Mutex()
    private var failures = 0
    private var retryJob: Job? = null
    private var discoveryJob: Job? = null
    private var pendingImmediate = false
    private var started = false

    override val paired: Boolean get() = platform.paired()

    override val transport: ShellTransport get() = platform.transport()

    override val shizukuStart: Boolean
        get() = platform.transport() != ShellTransport.WirelessDebugging && platform.shizukuState().ready

    override val restart: HelperRestart get() = when {
        platform.transport() == ShellTransport.Shizuku ||
            (platform.transport() == ShellTransport.Auto &&
                (platform.shizukuState().ready || (!platform.paired() && platform.shizukuState().installed))) -> HelperRestart.Shizuku
        platform.paired() -> HelperRestart.Wireless
        platform.usbStarted() -> HelperRestart.UsbCommand
        else -> HelperRestart.None
    }

    override val rootStart: Boolean get() = platform.rootStart() == RootStart.Ready

    /** Claire can start the helper itself: depending on the current transport, check Shizuku, su or paired wireless debugging. */
    private fun selfStart(): Boolean = when (platform.transport()) {
        ShellTransport.Shizuku -> platform.shizukuState().ready
        ShellTransport.WirelessDebugging -> rootStart || platform.paired()
        ShellTransport.Auto -> platform.shizukuState().ready || rootStart || platform.paired()
    }

    private fun initialStatus(): BuiltinShellStatus = when {
        helper.value != null -> BuiltinShellStatus.Running
        selfStart() -> BuiltinShellStatus.Connecting
        // A helper started by the computer may still be alive; wait for it to hand off
        platform.transport() != ShellTransport.Shizuku && platform.usbStarted() -> BuiltinShellStatus.Connecting
        else -> idleStatus()
    }

    /**
     * Status when Claire cannot start the helper itself and holds no helper: in Shizuku mode it reports which Shizuku step is missing;
     * in automatic mode it also reports honestly when unpaired with Shizuku installed; otherwise it looks at root, computer launch or being unpaired.
     */
    private fun idleStatus(): BuiltinShellStatus = when (platform.transport()) {
        ShellTransport.Shizuku -> BuiltinShellStatus.Failed(platform.shizukuState().failure())
        ShellTransport.WirelessDebugging -> wirelessOrRootIdleStatus()
        ShellTransport.Auto -> {
            if (!platform.paired() && platform.rootStart() == RootStart.Off && !platform.usbStarted() && platform.shizukuState().installed) {
                BuiltinShellStatus.Failed(platform.shizukuState().failure())
            } else {
                wirelessOrRootIdleStatus()
            }
        }
    }

    private fun wirelessOrRootIdleStatus(): BuiltinShellStatus {
        platform.rootStart().failure?.let { return BuiltinShellStatus.Failed(it) }
        return if (platform.usbStarted()) BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped) else BuiltinShellStatus.NotPaired
    }

    /** Starts watching the helper: restart it when it drops. Called only when the feature is enabled. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        var first = true
        scope.launch {
            helper.collect { h ->
                if (h != null) {
                    synchronized(this@BuiltinShellEngine) { failures = 0; retryJob?.cancel(); pendingImmediate = false }
                    lastError = null
                    autoOn?.reset()
                    // It arrived unpaired and without root or Shizuku: it was started by a computer over USB. Record it so that, once it stops, the right guidance can be shown
                    if (!platform.paired() && platform.rootStart() == RootStart.Off && !shizukuStart && platform.transport() != ShellTransport.Shizuku) {
                        platform.setUsbStarted(true)
                    }
                    _status.value = BuiltinShellStatus.Running
                } else if (!selfStart()) {
                    // A helper still alive when the App just started hands off on its own; a drop during use may also mean a new one is being swapped in to hand off:
                    // wait a while before declaring it stopped, so the UI does not flash "stopped"
                    if (platform.transport() != ShellTransport.Shizuku && platform.usbStarted()) {
                        _status.value = BuiltinShellStatus.Connecting
                        schedule(graceMs)
                    } else {
                        _status.value = idleStatus()
                    }
                } else {
                    if (_status.value == BuiltinShellStatus.Running) _status.value = BuiltinShellStatus.Connecting
                    val alreadyImmediate = synchronized(this@BuiltinShellEngine) { pendingImmediate && retryJob?.isActive == true }
                    if (!alreadyImmediate) {
                        schedule(if (first) (if (shizukuStart) 0L else graceMs) else backoff.baseMs)
                    }
                }
                first = false
            }
        }
    }

    /** Someone needs the helper but none is available: if it is not starting and no retry is queued, start one immediately. */
    fun need() {
        if (helper.value != null || !selfStart()) return
        synchronized(this) { if (retryJob?.isActive == true) return }
        if (launchLock.isLocked) return
        schedule(0)
    }

    /**
     * The "start with root" switch or su's answer changed (the user enabled, disabled or re-authorized it in settings or the guide): if the helper is absent, retry from scratch under the new conditions;
     * if Claire can no longer start it itself, update the status accordingly.
     */
    fun rootChanged() {
        if (platform.transport() == ShellTransport.Shizuku) return
        if (helper.value != null) return
        synchronized(this) {
            failures = 0
            retryJob?.cancel()
            retryJob = null
            pendingImmediate = false
        }
        if (selfStart()) schedule(0) else _status.value = idleStatus()
    }

    /** Switches the launch transport: stop the helper on the old transport and reconnect or update the status per the new transport. */
    override fun selectTransport(transport: ShellTransport) {
        if (platform.transport() == transport) return
        platform.setTransport(transport)
        runCatching { platform.stopHelper() }
        synchronized(this) {
            failures = 0
            retryJob?.cancel()
            retryJob = null
            pendingImmediate = false
        }
        if (selfStart()) {
            _status.value = BuiltinShellStatus.Connecting
            schedule(0)
        } else {
            _status.value = idleStatus()
        }
    }

    /** Shizuku state or authorization changed: in wireless-debugging-only mode nothing happens; if a helper is present it keeps running; otherwise try immediately per the new state or update the status accordingly. */
    fun shizukuChanged() {
        if (platform.transport() == ShellTransport.WirelessDebugging) return
        synchronized(this) {
            failures = 0
            retryJob?.cancel()
            retryJob = null
            pendingImmediate = false
        }
        if (helper.value != null) return
        if (selfStart()) schedule(0) else _status.value = idleStatus()
    }

    /**
     * The wireless debugging switch changed: if it was just turned on, retry from scratch. If it was turned off, only update the status and do not turn it on for the user right away (the user may have turned it off by hand); wait until the helper is needed.
     * While Claire itself is writing this switch, do not follow it: the system may switch it back right away, and the writing side decides.
     */
    fun wirelessDebuggingChanged() {
        // Starting the helper via su or pure Shizuku does not look at wireless debugging
        if (helper.value != null || !platform.paired() || rootStart || platform.transport() == ShellTransport.Shizuku) return
        if (autoOn?.writing == true) return
        if (platform.wirelessDebugging() == false) {
            if (!shizukuStart) _status.value = offStatus()
            return
        }
        autoOn?.reset()
        synchronized(this) { failures = 0 }
        schedule(0)
    }

    /**
     * Wi-Fi connected: retry immediately if previously stuck on "no Wi-Fi" (internet access is not required); when wireless debugging can be turned on for the user,
     * also retry immediately if stuck on wireless debugging being off (including the previous network not allowing it). The network changed, so the previous network's denial no longer counts.
     */
    fun wifiAvailable() {
        if (platform.transport() == ShellTransport.Shizuku) return
        autoOn?.reset()
        val s = _status.value
        val off = s == BuiltinShellStatus.WirelessDebuggingOff || s == BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed)
        if (s != BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi) && !(autoOn != null && off)) return
        synchronized(this) { failures = 0 }
        schedule(0)
    }

    /** Status shown while wireless debugging is off: report honestly if the system just switched it back on this Wi-Fi, otherwise it is simply off. */
    private fun offStatus(): BuiltinShellStatus =
        if (autoOn?.blocked?.value == AutoOnBlock.NotAllowedHere) BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed)
        else BuiltinShellStatus.WirelessDebuggingOff

    /** Wireless debugging is off: turn it on for the user when possible and return true to continue connecting if it stays on without being switched back; otherwise set the status and return false. */
    private suspend fun turnOnWirelessDebugging(): Boolean {
        val auto = autoOn ?: return false.also { _status.value = offStatus() }
        when (val g = auto.gate(platform.wifiConnected())) {
            AutoOnGate.Act -> {
                _status.value = BuiltinShellStatus.Connecting
                if (withContext(io) { auto.turnOn() }) return true
            }
            // Too close to the previous write: try again when the time comes
            is AutoOnGate.Wait -> synchronized(this) {
                retryJob = scope.launch {
                    delay(g.ms)
                    launchOnce()
                }
            }
            is AutoOnGate.Skip -> Unit
        }
        _status.value = offStatus()
        return false
    }

    @Synchronized
    private fun schedule(delayMs: Long) {
        pendingImmediate = delayMs == 0L
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            launchOnce()
        }
    }

    private suspend fun launchOnce(): Unit = launchLock.withLock {
        synchronized(this) { pendingImmediate = false }
        if (helper.value != null) return@withLock
        when (platform.transport()) {
            ShellTransport.Shizuku -> {
                val shizuku = platform.shizukuState()
                if (!shizuku.ready) {
                    failed(shizuku.failure())
                    return@withLock
                }
                _status.value = BuiltinShellStatus.Connecting
                val failure = attemptShizuku()
                if (failure == null) {
                    lastError = null
                    _status.value = BuiltinShellStatus.Running
                } else {
                    failed(failure)
                }
                return@withLock
            }
            ShellTransport.Auto -> {
                if (platform.shizukuState().ready) {
                    _status.value = BuiltinShellStatus.Connecting
                    val failure = attemptShizuku()
                    if (failure == null) {
                        lastError = null
                        _status.value = BuiltinShellStatus.Running
                        return@withLock
                    }
                    if (!rootStart && !platform.paired()) {
                        failed(failure)
                        return@withLock
                    }
                }
            }
            ShellTransport.WirelessDebugging -> Unit
        }
        // Try root first: no pairing and no Wi-Fi needed; if that fails and pairing has been done, continue with wireless debugging
        if (rootStart) {
            _status.value = BuiltinShellStatus.Connecting
            val failure = attemptRoot()
            if (failure == null) {
                lastError = null
                _status.value = BuiltinShellStatus.Running
                return@withLock
            }
            if (!platform.paired()) {
                failed(failure)
                return@withLock
            }
        }
        if (!platform.paired()) {
            _status.value = idleStatus()
            return@withLock
        }
        if (platform.wirelessDebugging() == false && !turnOnWirelessDebugging()) return@withLock
        _status.value = BuiltinShellStatus.Connecting
        val failure = attempt()
        if (failure == null) {
            lastError = null
            _status.value = BuiltinShellStatus.Running
            return@withLock
        }
        failed(failure)
    }

    /** Reports a failure; when the watchdog retrying on its own helps, schedules the next one per [backoff]. */
    private fun failed(failure: BuiltinShellFailure) {
        _status.value = BuiltinShellStatus.Failed(failure)
        val next = synchronized(this) { ++failures }
        if (BuiltinShellFailures.retryable(failure)) backoff.delayAfter(next)?.let { d ->
            synchronized(this) {
                retryJob = scope.launch {
                    delay(d)
                    launchOnce()
                }
            }
        }
    }

    /** Launches the helper once via Shizuku and waits for the handoff. */
    private suspend fun attemptShizuku(): BuiltinShellFailure? {
        val failure = try {
            withContext(io) { platform.launchHelperViaShizuku() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = describe(e)
            return BuiltinShellFailure.ShizukuBindFailed
        }
        if (failure != null) {
            lastError = "shizuku: ${failure.name}"
            return failure
        }
        if (withTimeoutOrNull(handoffTimeoutMs) { helper.first { it != null } } != null) return null
        runCatching { platform.stopHelper() }
        lastError = "helper handoff timed out (shizuku)"
        return BuiltinShellFailure.ShizukuBindFailed
    }

    /** Launches the helper once via su and waits for the handoff; su's answer is recorded (after a denial or silence it no longer asks on its own). */
    private suspend fun attemptRoot(): BuiltinShellFailure? {
        val answer = try {
            withContext(io) { platform.launchHelperAsRoot() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = describe(e)
            return BuiltinShellFailure.Unknown
        } ?: return BuiltinShellFailure.RootUnavailable.also { lastError = "su not found" }
        platform.setRootAnswer(answer)
        return when (answer) {
            SuAnswer.Granted ->
                if (withTimeoutOrNull(handoffTimeoutMs) { helper.first { it != null } } != null) null
                else BuiltinShellFailure.HelperStartFailed.also { lastError = "helper handoff timed out (root)" }
            SuAnswer.Denied -> BuiltinShellFailure.RootDenied.also { lastError = "su denied" }
            SuAnswer.NoAnswer -> BuiltinShellFailure.RootNoAnswer.also { lastError = "su did not answer" }
            SuAnswer.WrongUid -> BuiltinShellFailure.RootNoShellUid.also { lastError = "su cannot switch to the shell uid" }
        }
    }

    private suspend fun attempt(): BuiltinShellFailure? = try {
        withContext(io) { platform.launchHelper() }
        if (withTimeoutOrNull(handoffTimeoutMs) { helper.first { it != null } } != null) null
        else BuiltinShellFailure.HelperStartFailed.also { lastError = "helper handoff timed out" }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AdbConnectionException) {
        lastError = describe(e)
        val f = BuiltinShellFailures.ofConnection(e.reason)
        if (f == BuiltinShellFailure.ConnectFailed && !platform.wifiConnected()) BuiltinShellFailure.NoWifi else f
    } catch (e: AdbKeyUnreadableException) {
        lastError = describe(e)
        BuiltinShellFailure.KeyUnreadable
    } catch (e: Exception) {
        lastError = describe(e)
        BuiltinShellFailure.Unknown
    }

    private fun describe(e: Exception): String =
        e.javaClass.simpleName + ((e as? AdbConnectionException)?.reason?.let { "($it)" } ?: (e as? AdbPairingException)?.reason?.let { "($it)" } ?: "") +
            (e.message?.let { ": " + it.take(200) } ?: "")

    override fun startDiscovery() {
        synchronized(this) {
            // Restart even if a search is running: the user is back on pairing, so the search deadline starts now
            discoveryJob?.cancel()
            _discovery.value = PairingDiscovery.Searching
            discoveryJob = scope.launch {
                val port = withContext(io) { platform.findPairingPort(discoveryTimeoutMs) }
                _discovery.value = port?.let { PairingDiscovery.Found(it) } ?: PairingDiscovery.Idle
            }
        }
    }

    override fun stopDiscovery() {
        synchronized(this) {
            discoveryJob?.cancel()
            discoveryJob = null
        }
        _discovery.value = PairingDiscovery.Idle
    }

    override suspend fun startPairing(code: String, port: Int?) {
        _status.value = BuiltinShellStatus.Connecting
        val target = port ?: (_discovery.value as? PairingDiscovery.Found)?.port
            ?: withContext(io) { platform.findPairingPort(pairingPortTimeoutMs) }
        if (target == null) {
            lastError = "pairing port not found"
            _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed)
            return
        }
        val failure = try {
            withContext(io) { platform.pair(target, code) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbPairingException) {
            lastError = describe(e)
            BuiltinShellFailures.ofPairing(e.reason)
        } catch (e: Exception) {
            lastError = describe(e)
            BuiltinShellFailure.Unknown
        }
        if (failure != null) {
            _status.value = BuiltinShellStatus.Failed(failure)
            return
        }
        platform.setPaired(true)
        stopDiscovery()
        retry()
    }

    override suspend fun retry() {
        synchronized(this) {
            failures = 0
            retryJob?.cancel()
            retryJob = null
            pendingImmediate = false
        }
        // The user tapped retry on the su-denied or su-silent prompt: have the platform explicitly request once more, since the unauthorized state will not ask su automatically
        val s = _status.value
        if (platform.transport() != ShellTransport.Shizuku && s is BuiltinShellStatus.Failed && s.reason in ROOT_ASKABLE) {
            if (platform.requestRootStart()) {
                _status.value = BuiltinShellStatus.Connecting
                return
            }
            platform.setRootAnswer(null)
        }
        // The user tapped retry: the previous denial when turning on wireless debugging for the user no longer counts (on a disallowed network the system asks again)
        autoOn?.reset()
        if (helper.value != null) {
            _status.value = BuiltinShellStatus.Running
            return
        }
        launchOnce()
    }

    override suspend fun unpair() {
        synchronized(this) {
            retryJob?.cancel()
            retryJob = null
            pendingImmediate = false
        }
        launchLock.withLock {
            withContext(io) {
                // Forget "started by computer" first: once the helper stops the state is simply not set up, with no prompt to run the command again
                platform.setUsbStarted(false)
                runCatching { platform.stopHelper() }
                platform.forget()
            }
            platform.setPaired(false)
            _status.value = BuiltinShellStatus.NotPaired
        }
    }

    private companion object {
        /** Failures on which tapping retry will ask su again. */
        val ROOT_ASKABLE = setOf(BuiltinShellFailure.RootDenied, BuiltinShellFailure.RootNoAnswer, BuiltinShellFailure.RootNoShellUid)
    }
}
