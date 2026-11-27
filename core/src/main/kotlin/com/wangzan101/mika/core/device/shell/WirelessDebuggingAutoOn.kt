package com.wangzan101.mika.core.device.shell

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why turning wireless debugging on for the user stopped; after stopping it only writes again once the Wi-Fi changed, the user turned it on by hand, retry was tapped, or the helper came up. */
enum class AutoOnBlock {
    /** It was written as on and the system switched it back off right away: this Wi-Fi has not been allowed for wireless debugging yet (the system also asks the user whether to allow it). */
    NotAllowedHere,
    /** The system does not allow the write (it throws or returns failure). */
    Refused,
    /** It turned it on for the user [WirelessDebuggingAutoOn.MAX_WRITES] times in a row and the helper still did not come up. */
    GaveUp,
}

/** Whether to turn wireless debugging on for the user right now. */
sealed interface AutoOnGate {
    data object Act : AutoOnGate
    /** Too close to the previous write: come back after [ms]. */
    data class Wait(val ms: Long) : AutoOnGate
    data class Skip(val reason: AutoOnSkip) : AutoOnGate
}

enum class AutoOnSkip {
    /** The user turned off "turn on wireless debugging automatically" in Claire. */
    Declined,
    /** Claire does not hold the permission to modify system settings. */
    NoPermission,
    /** Not connected to Wi-Fi: the system does not turn wireless debugging on without Wi-Fi (a write would be switched back anyway). */
    NoWifi,
    /** It has already stopped, see [AutoOnBlock]. */
    Blocked,
}

/**
 * When the built-in helper needs to start but wireless debugging is off, turns it on for the user (only on, never off). Once the helper is up, wireless debugging is no longer needed,
 * but restarting it (after a phone reboot, after a Claire update, when the helper is gone) depends on wireless debugging; and stock Android turns it off whenever Wi-Fi disconnects and after a reboot.
 *
 * It only writes when the user has not turned off "turn on wireless debugging automatically", Claire itself holds the permission to modify system settings, and Wi-Fi is connected.
 * When the helper needs to start it is of course absent, and running commands over the paired connection requires wireless debugging to be on first, so having the App write directly is the only path; when that is not possible it does nothing and the UI keeps offering the manual way to turn it on.
 *
 * Stock Android behavior it relies on (how AdbDebuggingManager handles `adb_wifi_enabled`):
 * - after the setting is written to 1, the system checks the current Wi-Fi: with no Wi-Fi connection it immediately writes 0 back;
 *   if this network (by BSSID) is not in the "always allow" list it also writes 0 back immediately, while showing a dialog asking the user "Allow wireless debugging on this network?" (with an "always allow" checkbox).
 *   If the user allows it, the system writes 1 back itself and turns it on; if the user declines it stays off.
 * - if the network is in the list: it stays 1, and the wireless debugging service is started afterwards (the port is discovered as usual by the connection step).
 * So after writing it watches [settleMs]: reading it as off within that time means this Wi-Fi has not been allowed yet, so it stops ([AutoOnBlock.NotAllowedHere])
 * and does not keep writing (each write makes the system show the dialog again) until the Wi-Fi changes, the user turns it on by hand, retry is tapped, or the helper comes up ([reset]).
 * Some systems handle this item differently (the write is refused, or it has no effect); it stops the same way without digging into each vendor's implementation.
 * Writes are spaced apart according to [gapAfter]; writing [MAX_WRITES] times in a row without the helper starting also makes it stop.
 */
class WirelessDebuggingAutoOn(
    /** Whether the user wants this capability (has not turned off "turn on wireless debugging automatically" in Claire). */
    private val wants: () -> Boolean,
    /** Claire itself holds the permission to modify system settings. */
    private val canWrite: () -> Boolean,
    /** The system's wireless debugging switch; null when it cannot be read. */
    private val read: () -> Boolean?,
    /** Writes the wireless debugging switch as on; returns false or throws when the system does not allow the write. */
    private val write: () -> Boolean,
    /** 把无线调试开关写成关闭:起助手时替用户打开过、随后用户暂停了 Mika,就关回去。写不了返回 false。 */
    private val writeOff: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val settleMs: Long = SETTLE_MS,
    private val pollMs: Long = POLL_MS,
) {
    private val _blocked = MutableStateFlow<AutoOnBlock?>(null)
    /** Why it stopped; null when it has not stopped. Provides a line for the settings page and the status. */
    val blocked: StateFlow<AutoOnBlock?> = _blocked.asStateFlow()

    /** Currently writing and waiting for the system to settle: switch changes during this time are judged here, so others must not follow them. */
    @Volatile var writing = false
        private set

    private var writes = 0
    private var lastWriteAt = 0L

    @Synchronized
    fun gate(wifi: Boolean): AutoOnGate {
        if (_blocked.value == null && writes >= MAX_WRITES) _blocked.value = AutoOnBlock.GaveUp
        return decide(wants(), canWrite(), wifi, _blocked.value, writes, now() - lastWriteAt)
    }

    /** Writes it on and waits for the system to settle. Returns true if the switch stays on; returns false when it was refused or switched back, recording the reason and stopping. */
    suspend fun turnOn(): Boolean {
        synchronized(this) {
            writing = true
            writes++
            lastWriteAt = now()
        }
        try {
            val accepted = runCatching(write).getOrDefault(false)
            if (!accepted || read() == false) return false.also { block(if (accepted) AutoOnBlock.NotAllowedHere else AutoOnBlock.Refused) }
            var waited = 0L
            while (waited < settleMs) {
                delay(pollMs)
                waited += pollMs
                if (read() == false) return false.also { block(AutoOnBlock.NotAllowedHere) }
            }
            return true
        } finally {
            writing = false
        }
    }

    /** 把替用户打开的无线调试关回去(用户在连接途中暂停了 Mika)。 */
    fun turnOff(): Boolean = canWrite() && runCatching(writeOff).getOrDefault(false)

    /** 换了 Wi-Fi、用户亲手打开了无线调试或点了重试、助手起来了:从头算。 */
    @Synchronized
    fun reset() {
        writes = 0
        lastWriteAt = 0L
        _blocked.value = null
    }

    private fun block(reason: AutoOnBlock) {
        _blocked.value = reason
    }

    companion object {
        /** How long to watch after writing for the system switching it back; stock Android handles the setting change immediately and switches it back within a few hundred milliseconds. */
        const val SETTLE_MS = 3_000L
        const val POLL_MS = 250L
        const val MAX_WRITES = 3
        const val BASE_GAP_MS = 10_000L
        const val MAX_GAP_MS = 5 * 60_000L

        /** The minimum wait before the next write after [writes] writes: none for the first, then 10 seconds, 20 seconds… capped at 5 minutes. */
        fun gapAfter(writes: Int): Long =
            if (writes <= 0) 0L else (BASE_GAP_MS shl (writes - 1).coerceAtMost(20)).coerceAtMost(MAX_GAP_MS)

        fun decide(wants: Boolean, canWrite: Boolean, wifi: Boolean, blocked: AutoOnBlock?, writes: Int, sinceLastMs: Long): AutoOnGate {
            if (!wants) return AutoOnGate.Skip(AutoOnSkip.Declined)
            if (!canWrite) return AutoOnGate.Skip(AutoOnSkip.NoPermission)
            if (blocked != null || writes >= MAX_WRITES) return AutoOnGate.Skip(AutoOnSkip.Blocked)
            if (!wifi) return AutoOnGate.Skip(AutoOnSkip.NoWifi)
            val gap = gapAfter(writes)
            return if (sinceLastMs < gap) AutoOnGate.Wait(gap - sinceLastMs) else AutoOnGate.Act
        }
    }
}
