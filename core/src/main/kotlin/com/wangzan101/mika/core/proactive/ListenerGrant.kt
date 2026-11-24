package com.wangzan101.mika.core.proactive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** The result of "authorized by the Claire helper". The UI shows one sentence per category and never the raw command output. */
sealed interface GrantResult {
    data object Granted : GrantResult
    /** Neither the helper nor the paired connection is usable (the command never even started). */
    data object HelperUnavailable : GrantResult
    /** The command ran, yet the system still does not grant access; [output] is the truncated command output, for logs only. */
    data class Refused(val output: String) : GrantResult
    /** The command did not finish within the time limit. */
    data object TimedOut : GrantResult
}

/**
 * Grants "notification access" to Claire's notification listener as the shell user: it runs once only after the user taps the button and confirms, never automatically in the background.
 * Pure logic: running the command and waiting for the system to take effect are both passed in as parameters, so fakes can be used in tests.
 */
object ListenerGrant {
    const val EXEC_TIMEOUT_MS = 10_000L
    const val WAIT_MS = 2_000L
    const val POLL_MS = 200L
    const val OUTPUT_LOG_LIMIT = 120

    /**
     * Builds the grant command. The package name and class name are first validated as a whole against a strict character set (only ASCII letters, digits, `_` and `.`; the class name additionally allows `$`);
     * on failure it throws [IllegalArgumentException], and an unvalidated string is never spliced into a shell command.
     * The component name is then wrapped in single quotes as a whole, so `$` in the class name is not expanded as a variable by sh (validation already guarantees there is no single quote inside).
     */
    fun command(packageName: String, className: String): String {
        require(validPackage(packageName)) { "bad package name" }
        require(validClass(className)) { "bad class name" }
        return "cmd notification allow_listener '$packageName/$className'"
    }

    fun validPackage(s: String): Boolean = valid(s, allowDollar = false)
    fun validClass(s: String): Boolean = valid(s, allowDollar = true)

    // checked character by character instead of with a regex: immune to platform regex dialect differences (such as whether \w matches non-ASCII letters, or whether $ can match before a trailing newline)
    private fun valid(s: String, allowDollar: Boolean): Boolean {
        if (s.isEmpty() || s.length > 255) return false
        if (s.first() == '.' || s.last() == '.') return false
        return s.all { c ->
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || (allowDollar && c == '$')
        }
    }

    /**
     * The whole grant flow: if access already exists it counts as success; otherwise first make sure the listener component is enabled (the system revokes the grant of a disabled component),
     * run the command within [EXEC_TIMEOUT_MS], then check every [POLL_MS] for up to [WAIT_MS] whether the system has applied it; when it has, [onGranted] is called.
     * An exception from [exec] (other than the caller itself being cancelled) is treated as the helper being unavailable.
     */
    suspend fun run(
        command: String,
        exec: suspend (String) -> String,
        authorized: () -> Boolean,
        ensureEnabled: () -> Unit,
        onGranted: () -> Unit,
    ): GrantResult {
        if (authorized()) {
            onGranted()
            return GrantResult.Granted
        }
        ensureEnabled()
        val output = try {
            withTimeoutOrNull(EXEC_TIMEOUT_MS) { exec(command) } ?: return GrantResult.TimedOut
        } catch (e: CancellationException) {
            // rethrow only when the caller itself was cancelled; timeouts and the like inside the command channel (also CancellationException) count as the helper being unavailable
            currentCoroutineContext().ensureActive()
            return GrantResult.HelperUnavailable
        } catch (e: Exception) {
            return GrantResult.HelperUnavailable
        }
        var waited = 0L
        while (true) {
            if (authorized()) {
                onGranted()
                return GrantResult.Granted
            }
            if (waited >= WAIT_MS) break
            delay(POLL_MS)
            waited += POLL_MS
        }
        return GrantResult.Refused(shorten(output))
    }

    /** Keeps the command output on a single line and truncates it, for logs. */
    fun shorten(output: String): String {
        val one = output.trim().replace(Regex("\\s+"), " ")
        return if (one.length <= OUTPUT_LOG_LIMIT) one else one.take(OUTPUT_LOG_LIMIT) + "…"
    }
}
