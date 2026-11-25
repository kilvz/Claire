package com.wangzan101.mika.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.telecom.TelecomManager
import com.wangzan101.mika.shell.IMikaShell

/** Manages Claire's own Telecom registration and ongoing-call permission. */
internal class CallTelecomAccess(private val context: Context) {
    private val telecom get() = context.getSystemService(TelecomManager::class.java)
    private val journal = context.getSharedPreferences("mika_call_access", Context.MODE_PRIVATE)
    private val pkg = context.packageName.also { check(it.matches(Regex("[A-Za-z0-9_.]+"))) }
    private val user = Process.myUid() / 100_000
    private val boot get() = Settings.Global.getString(context.contentResolver, Settings.Global.BOOT_COUNT)
    @Volatile private var currentBootPrepared = false

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= 31 && user == 0 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    fun hasPhonePermissions(): Boolean = listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun ready(): Boolean {
        if (!supported || !hasPhonePermissions()) return false
        val preparedHere = currentBootPrepared || boot?.let { it == journal.getString(PREPARED, null) } == true
        return preparedHere && managePermission()
    }

    /** Set up on an earlier boot and never undone: Android dropped it at restart, the user still wants it. */
    fun preparedOnEarlierBoot(): Boolean =
        preparedOnEarlierBoot(journal.getString(PREPARED, null), boot, currentBootPrepared)

    private fun managePermission(): Boolean = Build.VERSION.SDK_INT >= 31 &&
        runCatching { telecom?.hasManageOngoingCallsPermission() == true }.getOrDefault(false)

    private fun packageManagePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.MANAGE_ONGOING_CALLS) == PackageManager.PERMISSION_GRANTED

    fun prepare(shell: IMikaShell): String? {
        if (!supported) return "Phone companion mode requires Android 12 or later, the primary user, and carrier calling support"
        if (!hasPhonePermissions()) return "Allow phone permissions in Claire's system permissions"
        val original = readOp(shell) ?: return "Could not read back the phone permission state"
        val systemDialer = runCatching { telecom?.defaultDialerPackage }.getOrNull()
            ?: return "Could not read back the system default dialer"
        val restoreOp = journal.getString(PREVIOUS_OP, null)
        if (original != "allow") {
            if (!journal.edit().putString(PREVIOUS_OP, original).commit()) return "Could not save the phone permission restore information"
            val change = command(shell, "cmd appops set --user $user $pkg MANAGE_ONGOING_CALLS allow")
            val readBack = if (change == CallTelecomCommandOutcome.OK) readOp(shell) else null
            val failure = if (original == "default" && readBack == "default") {
                prepareDefaultPermission(shell)
            } else {
                change.verificationFailure(readBack, readBack == "allow" && managePermission())
            }
            if (failure != null) {
                rollback(shell)
                return failure
            }
        } else if (!managePermission()) {
            return "This system does not allow Claire to manage carrier calls"
        }
        // No public API lists this test override. Reuse external setup when the allow was
        // already granted independently; only remove a registration made by this runtime.
        if (original != "allow" || restoreOp != null || journal.getBoolean(COMPANION, false)) {
            if (!journal.edit().putBoolean(COMPANION, true).commit()) {
                rollback(shell)
                return "Could not save the phone companion restore information"
            }
            if (command(shell, "cmd telecom add-or-remove-call-companion-app $pkg 1") != CallTelecomCommandOutcome.OK) {
                rollback(shell)
                return "This system did not accept the phone companion registration"
            }
        }
        if (runCatching { telecom?.defaultDialerPackage }.getOrNull() != systemDialer) {
            rollback(shell)
            return "The system default dialer changed; setup stopped"
        }
        if (!journal.edit().putString(PREPARED, boot).commit()) {
            rollback(shell)
            return "Could not save the phone companion configuration"
        }
        currentBootPrepared = true
        return null
    }

    private fun prepareDefaultPermission(shell: IMikaShell): String? {
        // In default mode Android can use the package's grant. Some builds keep the app-op
        // at default after appops set while accepting the grant through Package Manager.
        if (!packageManagePermission()) {
            if (!journal.edit().putBoolean(OWNS_PACKAGE_PERMISSION, true).commit()) return "Could not save the phone permission restore information"
            if (command(shell, "pm grant --user $user $pkg ${Manifest.permission.MANAGE_ONGOING_CALLS}") !=
                CallTelecomCommandOutcome.OK
            ) return "The system did not accept the direct grant of the call-management permission"
        }
        return CallTelecomCommandOutcome.OK.verificationFailure("default", managePermission())
    }

    fun rollback(shell: IMikaShell?): Boolean {
        currentBootPrepared = false
        val disabled = journal.edit().remove(PREPARED).commit()
        val ownsPackagePermission = journal.getBoolean(OWNS_PACKAGE_PERMISSION, false)
        if (!journal.getBoolean(COMPANION, false) && !journal.contains(PREVIOUS_OP) && !ownsPackagePermission) return disabled
        if (shell == null) return false
        var restored = disabled
        if (journal.getBoolean(COMPANION, false)) {
            if (command(shell, "cmd telecom add-or-remove-call-companion-app $pkg 0") == CallTelecomCommandOutcome.OK) {
                if (!journal.edit().remove(COMPANION).commit()) restored = false
            } else restored = false
        }
        val original = journal.getString(PREVIOUS_OP, null)
        if (original != null) {
            val current = readOp(shell)
            val normalized = if (original == "errored") "deny" else original
            // If the user changed the mode themselves after prepare, keep that newer choice.
            val reverted = current != null && (current != "allow" ||
                (command(shell, "cmd appops set --user $user $pkg MANAGE_ONGOING_CALLS $normalized") == CallTelecomCommandOutcome.OK &&
                    readOp(shell) == original))
            if (reverted) {
                if (!journal.edit().remove(PREVIOUS_OP).commit()) restored = false
            } else restored = false
        }
        if (ownsPackagePermission) {
            val revoked = !packageManagePermission() ||
                (command(shell, "pm revoke --user $user $pkg ${Manifest.permission.MANAGE_ONGOING_CALLS}") ==
                    CallTelecomCommandOutcome.OK && !packageManagePermission())
            if (!revoked || !journal.edit().remove(OWNS_PACKAGE_PERMISSION).commit()) restored = false
        }
        return restored
    }

    private fun readOp(shell: IMikaShell): String? =
        runCatching { shell.runCommand("cmd appops get --user $user $pkg MANAGE_ONGOING_CALLS") }
            .getOrNull()?.takeIf { it.getInt("exitCode", -1) == 0 }?.getString("output")
            ?.let(CallPolicy::previousAppOpMode)

    private fun command(shell: IMikaShell, command: String): CallTelecomCommandOutcome {
        val result = runCatching { shell.runCommand(command) }.getOrNull() ?: return CallTelecomCommandOutcome.FAILED
        return CallTelecomCommandOutcome.classify(result.getInt("exitCode", -1), result.getString("output").orEmpty())
    }

    private companion object {
        const val PREPARED = "prepared_boot"
        const val PREVIOUS_OP = "previous_call_op"
        const val COMPANION = "owns_companion"
        const val OWNS_PACKAGE_PERMISSION = "owns_package_call_permission"
    }
}

/** Only a fixed diagnostic leaves this classifier; shell output is never retained or displayed. */
internal enum class CallTelecomCommandOutcome {
    OK,
    FAILED,
    SHELL_APP_OPS_DENIED;

    val appOpsFailureMessage: String
        get() = when (this) {
            SHELL_APP_OPS_DENIED -> "The system blocks ADB from changing the call-management permission (MANAGE_APP_OPS_MODES is missing), so phone companion mode cannot be enabled"
            else -> "The system did not grant the call-management permission"
        }

    /** Report the verified post-command state, never raw shell output or a guessed permission. */
    fun verificationFailure(readBack: String?, telecomGranted: Boolean): String? = when {
        this != OK -> appOpsFailureMessage
        readBack == "default" && telecomGranted -> null
        readBack in setOf("default", "ignore", "deny", "errored", "foreground") ->
            "A grant was attempted, but the system still reads back the call-management permission as $readBack, so phone companion mode cannot be enabled"
        readBack != "allow" -> "The grant command ran, but the call-management permission state could not be read back; try again"
        !telecomGranted -> "The call-management permission reads back as allow, but the system call service is still not authorized, so phone companion mode cannot be enabled"
        else -> null
    }

    companion object {
        private val shellAppOpsDenied = Regex(
            """(?im)^\h*(?:java\.lang\.)?SecurityException:\h*uid\h+2000\h+does not have\h+android\.permission\.MANAGE_APP_OPS_MODES\b""",
        )
        private val commandError = Regex(
            """(?im)^\s*(unknown command|error[: ]|command\[.*]\s*:\s*error|exception|permission denial|securityexception)""",
        )

        fun classify(exitCode: Int, output: String): CallTelecomCommandOutcome = when {
            shellAppOpsDenied.containsMatchIn(output) -> SHELL_APP_OPS_DENIED
            exitCode != 0 || commandError.containsMatchIn(output) -> FAILED
            else -> OK
        }
    }
}

/** A setup record from another boot, and none made in this one. */
internal fun preparedOnEarlierBoot(saved: String?, boot: String?, preparedThisBoot: Boolean): Boolean =
    !preparedThisBoot && saved != null && saved != boot
