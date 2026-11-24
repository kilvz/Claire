package com.wangzan101.mika.core.device.shell

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runs one command as the shell identity over the paired wireless debugging connection and returns its output. Throws when the connection is lost. */
fun interface ShellCommandRunner {
    suspend fun exec(command: String): String
}

/**
 * The capabilities of phone control: on by default (Claire fills them in itself when a connection is available), all can be turned off in settings, and all are revoked when unpairing.
 * [RestoreAfterReboot] and [AutoWirelessDebugging] use the same permission to modify system settings (both are only used to turn on wireless debugging),
 * so it is only revoked when both are turned off.
 */
enum class RecoveryCapability {
    Accessibility, BatteryWhitelist, RestoreAfterReboot,
    /** Turns wireless debugging on for the user when the built-in helper needs restarting but it is off (see [WirelessDebuggingAutoOn]). */
    AutoWirelessDebugging;

    /** Requires the permission to modify system settings. */
    val writesSecureSettings: Boolean get() = this == RestoreAfterReboot || this == AutoWirelessDebugging
}

/**
 * Two things are recorded separately for each capability: [applied] means Claire has already turned it on in the system for the user and it is in effect right now;
 * [declined] means the user turned it off by hand, so it will not be turned on automatically again. Neither means "untouched", which counts as on by default.
 */
data class RecoveryConsent(
    val applied: Set<RecoveryCapability> = emptySet(),
    val declined: Set<RecoveryCapability> = emptySet(),
) {
    /** Whether the user wants this capability: it is wanted unless turned off by hand (this is what the switch shows). */
    fun wants(c: RecoveryCapability): Boolean = c !in declined

    fun isApplied(c: RecoveryCapability): Boolean = c in applied

    /** Capabilities the user wants that are not in effect yet: filled in as soon as a connection is available. */
    val pending: List<RecoveryCapability> get() = RecoveryCapability.entries.filter { wants(it) && !isApplied(it) }

    fun chose(c: RecoveryCapability, on: Boolean): RecoveryConsent = copy(declined = if (on) declined - c else declined + c)

    fun withApplied(c: RecoveryCapability, on: Boolean): RecoveryConsent = copy(applied = if (on) applied + c else applied - c)
}

interface RecoveryConsentStore {
    val consent: StateFlow<RecoveryConsent>
    fun save(consent: RecoveryConsent)
}

sealed interface PermissionResult {
    data object Done : PermissionResult
    /** Phone control is not set up yet, so there is no usable connection. */
    data object NoRunner : PermissionResult
    data class Failed(val detail: String) : PermissionResult
}

/** The shell commands for these capabilities (written the way stock pm / cmd deviceidle / settings does it). */
object PermissionCommands {
    const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    const val READ_ACCESSIBILITY = "settings get secure enabled_accessibility_services"
    const val EXIT_MARK = "__mika_exit="

    fun accessibilityService(pkg: String): String {
        // The package name (applicationId) may carry a suffix (for side-by-side installed versions), while the service class still lives in the App's namespace: use the short form when the package name is a prefix of the class name, otherwise write the full class name
        val service = "com.wangzan101.mika.a11y.MikaAccessibilityService"
        return "$pkg/${if (service.startsWith("$pkg.")) service.removePrefix(pkg) else service}"
    }
    fun grantWriteSecureSettings(pkg: String) = "pm grant $pkg $WRITE_SECURE_SETTINGS"
    fun revokeWriteSecureSettings(pkg: String) = "pm revoke $pkg $WRITE_SECURE_SETTINGS"
    fun whitelistAdd(pkg: String) = "cmd deviceidle whitelist +$pkg"
    fun whitelistRemove(pkg: String) = "cmd deviceidle whitelist -$pkg"

    /** Writes back the whole accessibility services list. When empty, delete the item and turn off the master switch so that no empty string is left behind. */
    fun writeAccessibility(list: String): List<String> =
        if (list.isEmpty()) listOf("settings delete secure enabled_accessibility_services", "settings put secure accessibility_enabled 0")
        else listOf("settings put secure enabled_accessibility_services ${quote(list)}", "settings put secure accessibility_enabled 1")

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** The exec service does not expose the exit code: append a marker line to the end of the output. When pm or cmd fails it usually just prints a message in the output, so the presence of output alone cannot be used. */
    fun checked(command: String): String = "$command 2>&1; echo $EXIT_MARK\$?"

    fun exitCode(output: String): Int? =
        output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith(EXIT_MARK) }?.removePrefix(EXIT_MARK)?.toIntOrNull()
}

/** `enabled_accessibility_services` is a colon-separated list of component names; a component name can be written as "package/.Class" or "package/full.Class". */
object AccessibilityList {
    fun entries(raw: String?): List<String> {
        val v = raw?.trim().orEmpty()
        if (v == "null") return emptyList() // `settings get` outputs null for items that were never written
        return v.split(':').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun contains(raw: String?, component: String): Boolean = entries(raw).any { same(it, component) }

    fun with(raw: String?, component: String): String {
        val list = entries(raw)
        return (if (list.any { same(it, component) }) list else list + component).joinToString(":")
    }

    fun without(raw: String?, component: String): String = entries(raw).filterNot { same(it, component) }.joinToString(":")

    private fun same(a: String, b: String) = expand(a) == expand(b)

    private fun expand(c: String): String {
        if ('/' !in c) return c
        val pkg = c.substringBefore('/')
        val cls = c.substringAfter('/')
        return if (cls.startsWith('.')) "$pkg/$pkg$cls" else c
    }
}

/**
 * Turns these capabilities on or revokes them according to the user's choices. All of them need the paired connection (shell identity) to change system settings; a null [runner] means there is no usable connection yet.
 * The user's choice ([RecoveryConsent.declined]) and whether it is in effect in the system ([RecoveryConsent.applied]) are recorded separately:
 * a choice to turn on is recorded even without a connection and filled in later by [applyPending]; when a command fails both are left as they were and the UI shows it honestly.
 * The entry points run serially, so automatic filling in and the user flipping switches do not interleave.
 */
class RecoveryPermissions(
    private val runner: () -> ShellCommandRunner?,
    private val store: RecoveryConsentStore,
    private val packageName: String,
) {
    private val lock = Mutex()
    private val _autoFailed = MutableStateFlow<Set<RecoveryCapability>>(emptySet())

    /** Capabilities that failed during automatic filling in (without bothering the user, only a line under that item on the settings page); removed once they succeed later. */
    val autoFailed: StateFlow<Set<RecoveryCapability>> = _autoFailed.asStateFlow()

    /** The user flips a switch: turning on executes directly with no further confirmation; turning off revokes immediately and records "turned off by hand". */
    suspend fun set(capability: RecoveryCapability, on: Boolean): PermissionResult = lock.withLock {
        val now = store.consent.value
        val shell = runner()
        if (shell == null) {
            return@withLock when {
                // Record the request first and fill it in automatically once a connection is available; the UI prompts to finish setup
                on -> PermissionResult.NoRunner.also { store.save(now.chose(capability, true)) }
                // It was never in effect, so there is nothing to revoke
                !now.isApplied(capability) -> PermissionResult.Done.also { store.save(now.chose(capability, false)) }
                else -> PermissionResult.NoRunner
            }
        }
        val result = run(shell, listOf(capability), on)
        if (result == PermissionResult.Done) {
            store.save(store.consent.value.chose(capability, on).withApplied(capability, on))
            _autoFailed.update { it - capability }
        }
        result
    }

    /**
     * Called when a connection has just become available: turns on the capabilities the user has not turned off and that are not in effect yet. Nothing is shown to the user; failures are recorded in [autoFailed]
     * and retried the next time a connection is available. Without a connection it does nothing.
     */
    suspend fun applyPending(): PermissionResult = lock.withLock {
        val shell = runner() ?: return@withLock PermissionResult.NoRunner
        var failure: PermissionResult? = null
        for (c in store.consent.value.pending) {
            val r = run(shell, listOf(c), on = true)
            if (r == PermissionResult.Done) store.save(store.consent.value.withApplied(c, true))
            else if (failure == null) failure = r
            _autoFailed.update { if (r == PermissionResult.Done) it - c else it + c }
        }
        failure ?: PermissionResult.Done
    }

    /** Keeps watching the built-in helper's status: every time it becomes running (a connection has just become available) it calls [applyPending]; system settings are left alone while the user has explicitly exited. */
    suspend fun applyWhenRunning(status: Flow<BuiltinShellStatus>, active: Flow<Boolean> = flowOf(true)) {
        combine(status, active, ::Pair).collect { (state, allowed) ->
            if (allowed && state == BuiltinShellStatus.Running) applyPending()
        }
    }

    /**
     * Used when unpairing: revokes every capability Claire turned on here, plus the permission to modify system settings (revoked once regardless of the record; revoking a permission that does not exist is harmless).
     * Accessibility or whitelisting the user enabled themselves in the system did not come from here and is left alone. "In effect" is cleared regardless of the outcome: the pairing is gone, so these capabilities stop being in effect;
     * the user's choices are kept: on re-pairing the ones not turned off are turned on automatically as before, and the ones turned off by hand stay off.
     */
    suspend fun revokeAll(): PermissionResult = lock.withLock {
        val now = store.consent.value
        // The permission to modify system settings is shared by two capabilities, so revoking it once is enough: only the RestoreAfterReboot entry is kept
        val granted = now.applied.filterNot { it.writesSecureSettings }.toSet() + RecoveryCapability.RestoreAfterReboot
        store.save(now.copy(applied = emptySet()))
        _autoFailed.value = emptySet()
        val shell = runner() ?: return@withLock PermissionResult.NoRunner
        run(shell, granted.toList(), on = false)
    }

    /**
     * Makes Claire's accessibility service run again (used when the service is not running, see [AccessibilityRepair]), leaving every other service untouched:
     * when Claire is not in the list it is added directly; when it is there but not bound, it is first removed, then after waiting [gapMs] for the system to settle the list is read again and it is added back.
     * Once removed it is always added back, even if cancelled midway, so the service is not left in the off state; if adding back fails, the original list is written back and the failure is still reported. [RecoveryConsent] is not modified: this is not turning a capability on or off.
     */
    suspend fun restartAccessibility(gapMs: Long): PermissionResult = lock.withLock {
        val shell = runner() ?: return@withLock PermissionResult.NoRunner
        val me = PermissionCommands.accessibilityService(packageName)
        try {
            val current = shell.exec(PermissionCommands.READ_ACCESSIBILITY)
            // Not in the list (the system removes it when force-stopped): add it directly; in the list but not bound: remove and add back so the system rebinds it
            if (!AccessibilityList.contains(current, me)) return@withLock runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.with(current, me)))
            val removed = runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.without(current, me)))
            if (removed != PermissionResult.Done) return@withLock removed
            withContext(NonCancellable) {
                delay(gapMs)
                val now = shell.exec(PermissionCommands.READ_ACCESSIBILITY)
                val back = runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.with(now, me)))
                // When adding back fails, write the original list back (along with the master switch): better to return to "present but unbound" than to let Claire disappear from the list;
                // the write-back is only a fallback, and this attempt is still reported as a failure to add back
                // (the raw read includes a newline; it already contains Claire, and with only tidies it into a clean list without changing the content)
                if (back != PermissionResult.Done) runCatching { runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.with(current, me))) }
                back
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Each capability is executed on its own, a failure in one does not block the rest; returns the first failure. */
    private suspend fun run(shell: ShellCommandRunner, capabilities: List<RecoveryCapability>, on: Boolean): PermissionResult {
        var failure: PermissionResult? = null
        for (c in capabilities) {
            val r = try {
                runAll(shell, commands(shell, c, on))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            if (r != PermissionResult.Done && failure == null) failure = r
        }
        return failure ?: PermissionResult.Done
    }

    private suspend fun commands(shell: ShellCommandRunner, c: RecoveryCapability, on: Boolean): List<String> = when (c) {
        RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging -> when {
            on -> listOf(PermissionCommands.grantWriteSecureSettings(packageName))
            // Another capability still needs this permission: only record it as off, do not revoke
            RecoveryCapability.entries.any { it != c && it.writesSecureSettings && store.consent.value.isApplied(it) } -> emptyList()
            else -> listOf(PermissionCommands.revokeWriteSecureSettings(packageName))
        }
        RecoveryCapability.BatteryWhitelist ->
            listOf(if (on) PermissionCommands.whitelistAdd(packageName) else PermissionCommands.whitelistRemove(packageName))
        RecoveryCapability.Accessibility -> {
            val current = shell.exec(PermissionCommands.READ_ACCESSIBILITY)
            val me = PermissionCommands.accessibilityService(packageName)
            PermissionCommands.writeAccessibility(if (on) AccessibilityList.with(current, me) else AccessibilityList.without(current, me))
        }
    }

    private suspend fun runAll(shell: ShellCommandRunner, commands: List<String>): PermissionResult {
        for (cmd in commands) {
            val out = shell.exec(PermissionCommands.checked(cmd))
            if (PermissionCommands.exitCode(out) != 0) return PermissionResult.Failed("$cmd: ${out.trim()}")
        }
        return PermissionResult.Done
    }
}
