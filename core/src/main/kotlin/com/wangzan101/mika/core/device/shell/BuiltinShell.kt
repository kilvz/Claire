package com.wangzan101.mika.core.device.shell

import kotlinx.coroutines.flow.StateFlow

/**
 * Current state of the built-in helper (the privileged process launched via the device's own "wireless debugging", a computer, or su as the shell identity).
 * The UI, settings page and task entry points look only at this; the pairing, connection and watchdog implementations each write their results as one of these states.
 */
sealed interface BuiltinShellStatus {
    /** Never paired (or pairing was removed): the guide must be walked through. */
    data object NotPaired : BuiltinShellStatus

    /** Paired, but wireless debugging is off: it connects automatically once turned on. */
    data object WirelessDebuggingOff : BuiltinShellStatus

    /** Pairing, connecting, or starting the helper. */
    data object Connecting : BuiltinShellStatus

    data object Running : BuiltinShellStatus

    /** Something went wrong: [reason] says which kind, [fix] is the one-tap repair path. */
    data class Failed(val reason: BuiltinShellFailure, val fix: BuiltinShellFix = reason.defaultFix) : BuiltinShellStatus
}

/** Kinds of failure. The wording lives in the UI layer; this only distinguishes cases where the user has to do something different. */
enum class BuiltinShellFailure(val defaultFix: BuiltinShellFix, val duringPairing: Boolean) {
    /** The pairing code is wrong or has expired. */
    WrongCode(BuiltinShellFix.RePair, duringPairing = true),
    /** The pairing window closed before the code was entered (the pairing port cannot be found). */
    PairingWindowClosed(BuiltinShellFix.RePair, duringPairing = true),
    /** Not connected to Wi-Fi: wireless debugging cannot be turned on or cannot be reached. */
    NoWifi(BuiltinShellFix.OpenWifi, duringPairing = false),
    /** Claire turned wireless debugging on for the user and the system turned it off again right away: this Wi-Fi has not been allowed for wireless debugging yet, so the user must turn it on by hand once and allow it. */
    WifiNotAllowed(BuiltinShellFix.OpenWirelessDebugging, duringPairing = false),
    /** Cannot connect to the device's own wireless debugging. */
    ConnectFailed(BuiltinShellFix.Retry, duringPairing = false),
    /** The ADB target is not this device's own address: a caller error, not a self-recovering network fault. */
    NotLocal(BuiltinShellFix.Retry, duringPairing = false),
    /** The paired authorization is no longer accepted (revoked by the system after long disuse, or deleted by the user in the system). */
    AuthorizationRevoked(BuiltinShellFix.RePair, duringPairing = false),
    /** The pairing key is stored but can no longer be read: the device only accepts that one, so it cannot be swapped silently and pairing must be redone (a new key is only created during pairing). */
    KeyUnreadable(BuiltinShellFix.RePair, duringPairing = false),
    /** Connected, but the helper failed to start or to hand off. */
    HelperStartFailed(BuiltinShellFix.Retry, duringPairing = false),
    /**
     * The helper started by the USB launch command stopped (most likely after a phone reboot) and wireless debugging was never paired, so Claire cannot start it itself:
     * run the launch command again. It does not point to wireless debugging: some phones have no usable wireless debugging at all.
     */
    UsbHelperStopped(BuiltinShellFix.RunUsbCommand, duringPairing = false),
    /** "Start with root" is enabled but su denied: it stops asking on its own and waits for the user to tap retry (retry makes su show the authorization prompt again). */
    RootDenied(BuiltinShellFix.Retry, duringPairing = false),
    /** su did not answer (nobody tapped the authorization prompt): likewise it waits for the user to tap retry. */
    RootNoAnswer(BuiltinShellFix.Retry, duringPairing = false),
    /** "Start with root" is enabled but su can no longer be found on the phone (root was removed): turn this option off, or go pair wireless debugging. */
    RootUnavailable(BuiltinShellFix.RePair, duringPairing = false),
    /** su agreed but cannot switch to the shell identity: such su cannot start the helper, so use wireless debugging or a computer instead. */
    RootNoShellUid(BuiltinShellFix.RePair, duringPairing = false),
    /** Shizuku is not installed on the phone. */
    ShizukuNotInstalled(BuiltinShellFix.Retry, duringPairing = false),
    /** Shizuku is installed but its service is not running (for example not yet activated after a reboot). */
    ShizukuNotRunning(BuiltinShellFix.Retry, duringPairing = false),
    /** Shizuku is running but Claire has not been authorized yet. */
    ShizukuPermissionNeeded(BuiltinShellFix.Retry, duringPairing = false),
    /** The helper was launched via Shizuku but could not be bound or handed off. */
    ShizukuBindFailed(BuiltinShellFix.Retry, duringPairing = false),
    Unknown(BuiltinShellFix.Retry, duringPairing = false),
}

enum class BuiltinShellFix {
    /** Connect once more with the paired key. */
    Retry,
    /** Go through the pairing step again. */
    RePair,
    /** Go to the system Wi-Fi settings. */
    OpenWifi,
    /** Turn wireless debugging on by hand in developer options. */
    OpenWirelessDebugging,
    /** Run the USB launch command again (the UI shows the command and a copy button). */
    RunUsbCommand,
}

/**
 * What brings the helper back after it stops depends on how it was started last time. This decides where to point the user when it stops and what the success page and settings page say.
 */
enum class HelperRestart {
    /** Not set up yet. */
    None,
    /** Wireless debugging has been paired: Claire restarts it through that; after a phone reboot, just turn wireless debugging on (when it can be turned on for the user, even that step is unnecessary). */
    Wireless,
    /**
     * Only ever started by the USB launch command, never paired wireless debugging: Claire cannot start it itself, so once it stops (for example after a phone reboot) the command must be run again.
     * Some phones have no usable wireless debugging and this is the only path, so in this case it does not prompt to turn wireless debugging on.
     */
    UsbCommand,
    /** Starting the helper via Shizuku: no wireless debugging or computer command needed. */
    Shizuku,
}

/** Result of finding the pairing port announced by the "Pair device with pairing code" window (local mDNS). */
sealed interface PairingDiscovery {
    data object Idle : PairingDiscovery
    data object Searching : PairingDiscovery
    data class Found(val port: Int) : PairingDiscovery
}

/**
 * The interface the built-in helper exposes to the UI. The implementation handles pairing (finding the port via mDNS, the 6-digit code), connecting to the local adbd, launching and watching the helper;
 * every result is reflected through [status], and the actions themselves do not throw.
 */
interface BuiltinShellControl {
    val status: StateFlow<BuiltinShellStatus>
    val pairingDiscovery: StateFlow<PairingDiscovery>

    /** Wireless debugging has been paired (Claire can start the helper and run commands through it itself). The helper may also have been started by a computer over USB, so running does not mean paired. */
    val paired: Boolean get() = status.value != BuiltinShellStatus.NotPaired

    /** What brings the helper back after it stops (see [HelperRestart]). */
    val restart: HelperRestart get() = when {
        status.value == BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped) -> HelperRestart.UsbCommand
        paired -> HelperRestart.Wireless
        else -> HelperRestart.None
    }

    /** The user enabled "start with root" and it is usable right now: the watchdog starts the helper via su first (while the status is connecting, the UI says it is starting with root). */
    val rootStart: Boolean get() = false

    /** Prefer starting the helper via Shizuku right now (not in wireless-debugging-only mode and Shizuku is ready). */
    val shizukuStart: Boolean get() = false

    /** Currently selected launch transport. */
    val transport: ShellTransport get() = ShellTransport.Auto

    /** Switches the launch transport. */
    fun selectTransport(transport: ShellTransport) {}

    /** Starts looking for the pairing port (the user is on their way to open the pairing window). Calling it repeatedly is harmless: if a search is running, the timer restarts from now. */
    fun startDiscovery()
    fun stopDiscovery()

    /** Pairs with the 6-digit pairing code, then connects and starts the helper on success. When [port] is null, uses the discovered pairing port. */
    suspend fun startPairing(code: String, port: Int? = null)

    /** Reconnects with the paired key and starts the helper. */
    suspend fun retry()

    /** Deletes the pairing key, stops the helper and revokes the helper's authorization for Claire; the status is then [BuiltinShellStatus.NotPaired]. */
    suspend fun unpair()
}

/** Pairing code: 6 digits. The user may include spaces, dashes or full-width digits, which are all normalized away; returns null when it is not 6 digits. */
object PairingCode {
    const val LENGTH = 6

    /** The various dashes an input method may produce: half-width, full-width, hyphen, en dash, em dash. */
    private const val DASHES = "-－‐‑–—"

    fun normalize(input: String): String? {
        val digits = input.filterNot { it.isWhitespace() || it in DASHES }
            .map { c -> if (c in '０'..'９') '0' + (c - '０') else c }
            .joinToString("")
        return digits.takeIf { it.length == LENGTH && it.all { c -> c in '0'..'9' } }
    }

    /** Manually entered port: 1–65535. */
    fun port(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it in 1..65535 }
}
