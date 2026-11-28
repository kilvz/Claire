package com.wangzan101.mika.core.device.shell

/** The pairing code and port read from the system pairing window; null when not read or ambiguous. */
data class PairingScreenReading(val code: String?, val port: Int?)

/** Pairs using the code that was read; when [port] is null, uses the discovered pairing port. */
data class PairingAttempt(val code: String, val port: Int?)

/**
 * Recognizes the pairing code and "IP:port" from all the text of a window. It only looks at the shape of the text, not at view ids, the UI language, or any particular layout:
 * the pairing code is a whole piece of text of exactly 6 digits (spaces may appear in between); the port is the number after the colon in "IPv4:port" or "[IPv6]:port".
 * If two different codes or ports appear in the same window it counts as not read, preferring to fall back to manual entry by the user.
 */
object PairingScreen {
    private val CODE = Regex("""\d{6}""")
    private val IPV4_PORT = Regex("""(?<![\w.:])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3}):(\d{1,5})(?![\w.:])""")
    private val IPV6_PORT = Regex("""\[[0-9A-Fa-f]*:[0-9A-Fa-f:.]*(?:%[\w.]+)?]:(\d{1,5})(?![\w.:])""")

    fun read(texts: List<String>): PairingScreenReading {
        val codes = texts.mapNotNull { t -> t.filterNot { it.isWhitespace() }.takeIf { CODE.matches(it) } }.toSet()
        val ports = texts.flatMap(::ports).toSet()
        return PairingScreenReading(codes.singleOrNull(), ports.singleOrNull())
    }

    private fun ports(text: String): List<Int> {
        val v4 = IPV4_PORT.findAll(text)
            .filter { m -> (1..4).all { m.groupValues[it].toInt() <= 255 } }
            .map { it.groupValues[5] }
        val v6 = IPV6_PORT.findAll(text).map { it.groupValues[1] }
        return (v4 + v6).mapNotNull { p -> p.toIntOrNull()?.takeIf { it in 1..65535 } }.toList()
    }

    /**
     * Whether to pair with the code that was read: there is a code, there is a port (read on screen, or the pairing window has been found), the code has not been tried,
     * and the helper is waiting to pair (not pairing already, not already done). After a wrong code the same code is not retried; it waits for the pairing window to show a new code.
     */
    fun attempt(
        reading: PairingScreenReading,
        portFound: Boolean,
        status: BuiltinShellStatus,
        tried: Set<String>,
        paused: Boolean = false,
    ): PairingAttempt? {
        if (paused) return null
        val code = reading.code ?: return null
        if (code in tried) return null
        if (reading.port == null && !portFound) return null
        val waiting = when (status) {
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff -> true
            is BuiltinShellStatus.Failed -> status.reason.duringPairing || status.fix == BuiltinShellFix.RePair
            // The user paused Claire: do not pair with the code read from the screen.
            BuiltinShellStatus.Connecting, BuiltinShellStatus.Running, BuiltinShellStatus.Paused -> false
        }
        return if (waiting) PairingAttempt(code, reading.port) else null
    }
}
