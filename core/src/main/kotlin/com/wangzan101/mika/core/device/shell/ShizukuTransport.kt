package com.wangzan101.mika.core.device.shell

/**
 * Which channel the phone's built-in helper is started through.
 * [Auto] prefers a usable Shizuku and falls back to the existing root / wireless debugging when that does not work;
 * [WirelessDebugging] ignores Shizuku and only uses the existing route;
 * [Shizuku] only uses Shizuku and never falls back to root or wireless debugging.
 */
enum class ShellTransport {
    Auto,
    WirelessDebugging,
    Shizuku;

    companion object {
        fun parse(raw: String?): ShellTransport {
            val normalized = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return Auto
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: Auto
        }
    }
}

/** Shizuku state as seen on the platform: whether it is installed, whether the service is running, and whether Claire has been authorized. */
data class ShizukuPlatformState(
    val installed: Boolean = false,
    val running: Boolean = false,
    val permissionGranted: Boolean = false,
) {
    val ready: Boolean get() = installed && running && permissionGranted

    fun failure(): BuiltinShellFailure = when {
        !installed -> BuiltinShellFailure.ShizukuNotInstalled
        !running -> BuiltinShellFailure.ShizukuNotRunning
        !permissionGranted -> BuiltinShellFailure.ShizukuPermissionNeeded
        else -> BuiltinShellFailure.ShizukuBindFailed
    }

    companion object {
        val Unavailable = ShizukuPlatformState()
    }
}
