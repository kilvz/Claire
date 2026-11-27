package com.wangzan101.mika.core.device.shell

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The convention shared by both sides when the helper process hands its Binder to Claire: the provider's method name and Bundle keys, the helper's process name, the launch commands,
 * and the checks Claire has to pass before accepting it.
 */
object HelperHandoff {
    const val SHELL_UID = 2000
    private const val PER_USER_RANGE = 100_000
    private const val FIRST_APP_UID = 10_000
    private const val LAST_APP_UID = 19_999
    const val METHOD_ATTACH = "attach"
    const val HELPER_CLASS = "com.wangzan101.mika.shell.HelperMain"

    // Helper → Claire
    const val KEY_NONCE = "nonce"
    const val KEY_BINDER = "binder"
    const val KEY_UID = "uid"
    const val KEY_PID = "pid"
    const val KEY_APK = "apk"
    /** Record of the helper bringing Claire back up (see [HelperRestoreReport]); absent when it never did. */
    const val KEY_RESTORED_AT = "restoredAt"
    const val KEY_RESTORES = "restores"
    const val KEY_GAVE_UP_AT = "restoreGaveUpAt"

    // Claire → Helper
    const val KEY_ACCEPTED = "accepted"
    const val KEY_NEXT = "next"
    const val KEY_TOKEN = "token"
    const val KEY_REASON = "reason"

    fun authority(packageName: String) = "$packageName.helper"

    /** The helper's process name (app_process's --nice-name); also used to recognize old helper instances. */
    fun processName(packageName: String) = "$packageName:helper"

    /**
     * Command executed by the adbd exec: service (adbd runs it with `sh -c`). As soon as the stream closes, adbd kills the whole group of processes belonging to that sh,
     * so the helper starts a new session with setsid; sh then waits in `read` until the App has received the handoff and closes the stream,
     * so that sh does not exit before setsid takes effect and take the helper down with it.
     *
     * The nonce does not go on the command line (processes that are also shell can read /proc/<pid>/cmdline): after opening the stream the App writes one line, [stdinLine];
     * sh reads it into a variable and passes it to the helper through a pipe using the built-in echo; the helper's stdin is this pipe, not the adb stream.
     * [certPin] is the fingerprint of Claire's signing certificate, not a secret, so it goes in the arguments.
     */
    fun command(apkPath: String, packageName: String, appUid: Int, certPin: String): String =
        "read n; (echo \"\$n\" | CLASSPATH=${quote(apkPath)} setsid app_process /system/bin " +
            "--nice-name=${quote(processName(packageName))} $HELPER_CLASS ${quote(packageName)} $appUid ${quote(certPin)} " +
            ">/dev/null 2>&1 &); read _"

    fun stdinLine(nonce: String): ByteArray = "$nonce\n".toByteArray(Charsets.UTF_8)

    /**
     * Command passed to `su 2000 -c` when starting the helper via su: it first prints one line `id -u`, and only when the App reads 2000 (su agreed and also switched to the shell identity) is the nonce written;
     * after that it is the same as [command] (the nonce goes over stdin, and the App closes the stream after receiving the handoff). When su did not agree there is no line at all, so the nonce is never written out.
     */
    fun rootCommand(apkPath: String, packageName: String, appUid: Int, certPin: String): String =
        "id -u; " + command(apkPath, packageName, appUid, certPin)

    /** File name of the "start with a computer" script, placed in the App's external files directory (a computer reads it as the shell identity over USB debugging). */
    const val USB_SCRIPT = "usb-start.sh"

    /** The primary user's external storage is also called /sdcard for the shell; the command shown to the user uses this short form. For other users the real path is written out. */
    private const val PRIMARY_STORAGE = "/storage/emulated/0/"

    /** The command to run on the computer. [scriptPath] is the script's actual path on the phone. */
    fun usbCommand(scriptPath: String): String =
        "adb shell sh " + (if (scriptPath.startsWith(PRIMARY_STORAGE)) "/sdcard/" + scriptPath.removePrefix(PRIMARY_STORAGE) else scriptPath)

    /**
     * The launch script a computer runs over USB debugging: it starts the same helper with the same arguments as [command], with the nonce replaced by [nonce] (written into the script, see [HandoffNonces.usbNonce]).
     * As soon as the adb shell session ends (the command finishes, the cable is unplugged), adbd kills the whole group of processes belonging to that session: the helper uses nohup to ignore the hangup signal, then setsid to start a new session, launched inside a background subshell
     * (once the subshell exits it is owned by init), with stdout and stderr discarded so it is no longer attached to the adb stream; the script then waits a while for setsid to take effect,
     * and if the helper process is still there it says "starting", and if not (most likely the nonce expired or Claire did not accept it) it asks the user to open Claire and run it again.
     * On most phones the helper keeps working after the cable is unplugged this way; some systems stop the helper when the cable is unplugged, in which case plugging the cable back in and running this command again restores it. The script does not guarantee it works after unplugging.
     * It only uses the stock Android sh and commands found in toybox. It does not start when not running as the shell identity (not run through adb): Claire only accepts helpers handed over by shell.
     */
    fun usbScript(apkPath: String, packageName: String, appUid: Int, certPin: String, nonce: String, scriptPath: String): String {
        val name = quote(processName(packageName))
        val again = usbCommand(scriptPath)
        return """
            |#!/system/bin/sh
            |# Written by Claire. Starts Claire's phone helper from a computer: $again
            |if [ "${'$'}(id -u)" != "$SHELL_UID" ]; then
            |  echo "Run this from a computer over USB: $again"
            |  exit 1
            |fi
            |if [ ! -f ${quote(apkPath)} ]; then
            |  echo "Claire was updated or reinstalled. Open Claire once, then run this command again."
            |  exit 1
            |fi
            |n=${quote(nonce)}
            |(echo "${'$'}n" | CLASSPATH=${quote(apkPath)} nohup setsid app_process /system/bin --nice-name=$name $HELPER_CLASS ${quote(packageName)} $appUid ${quote(certPin)} >/dev/null 2>&1 &)
            |sleep 2
            |if pidof $name >/dev/null 2>&1; then
            |  echo "Claire helper starting... Open Claire to confirm phone control is ready."
            |  echo "On most phones it keeps running after you unplug the cable. After a phone restart, or if phone control stopped after unplugging, plug in and run this command again."
            |else
            |  echo "Claire helper did not start. Open Claire once, then run this command again."
            |  exit 1
            |fi
            |""".trimMargin()
    }

    /**
     * When to rewrite the "start with a computer" script: when the helper comes or goes, and when the nonces are invalidated wholesale ([HandoffNonces.resets], e.g. unpairing);
     * the emitted value is whether the helper is present right now. In the latter case the helper may never be present, and without rewriting on it the script would keep an invalidated nonce.
     */
    fun usbScriptWrites(helper: Flow<Any?>, resets: Flow<Int>): Flow<Boolean> = combine(helper, resets) { h, _ -> h != null }

    /**
     * The script that should be written right now: when the helper is connected ([connected]) write [usbStub] without a nonce and do not request one; otherwise write [usbScript] with a fresh nonce.
     * [certPin] and [nonce] are only obtained when a script with a nonce is to be written.
     */
    fun usbFile(
        connected: Boolean, apkPath: String, packageName: String, appUid: Int, certPin: () -> String, nonce: () -> String, scriptPath: String,
    ): String =
        if (connected) usbStub(packageName, scriptPath)
        else usbScript(apkPath, packageName, appUid, certPin(), nonce(), scriptPath)

    /** The script swapped in once the helper is connected: no nonce, just one message. If the process is gone (e.g. the phone rebooted and Claire has not been opened yet) it asks the user to open Claire first. */
    fun usbStub(packageName: String, scriptPath: String): String {
        val name = quote(processName(packageName))
        return """
            |#!/system/bin/sh
            |# Written by Claire. The phone helper is connected, so this file holds no start code.
            |if pidof $name >/dev/null 2>&1; then
            |  echo "Claire helper is already running. Nothing to do."
            |else
            |  echo "Claire helper is not running. Open Claire once, then run this command again: ${usbCommand(scriptPath)}"
            |  exit 1
            |fi
            |""".trimMargin()
    }

    /** SHA-256 fingerprint of the signing certificate (DER); with multiple signers they are sorted and joined by commas. Returns null when there is no certificate. */
    fun certPin(certs: List<ByteArray>?): String? {
        if (certs.isNullOrEmpty()) return null
        return certs.map { c -> MessageDigest.getInstance("SHA-256").digest(c).joinToString("") { "%02x".format(it) } }
            .sorted().joinToString(",")
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    enum class CallerClass { Root, Shell, ShellOtherUser, OrdinaryApp, Other }

    /** Only keeps the caller class needed for diagnosing handoffs, without writing the concrete UID into diagnostics. */
    fun callerClass(uid: Int): CallerClass {
        val appId = uid % PER_USER_RANGE
        return when {
            uid == 0 -> CallerClass.Root
            uid == SHELL_UID -> CallerClass.Shell
            appId == SHELL_UID -> CallerClass.ShellOtherUser
            appId in FIRST_APP_UID..LAST_APP_UID -> CallerClass.OrdinaryApp
            else -> CallerClass.Other
        }
    }

    enum class Reject {
        /** The caller is not shell (uid 2000). */
        NotShell,
        /** The uid/pid the helper reports do not match the caller seen on the Binder. */
        IdMismatch,
        /** The helper is not running the currently installed APK (Claire was updated): have it exit and start a new one. */
        StaleApk,
        NoBinder,
        BadNonce,
        /** The process holding the Binder is not the one handing off. */
        BinderElsewhere,
        /** 用户暂停了 Mika:不收任何助手(包括暂停前起的、晚到的交接),助手被拒后自己退出。 */
        Paused,
    }

    /** Checks that can be done without touching the Binder; returns null when they pass. The nonce is verified separately by [HandoffNonces]. */
    fun check(
        callingUid: Int, callingPid: Int, reportedUid: Int, reportedPid: Int,
        helperApk: String?, appApk: String, hasBinder: Boolean,
    ): Reject? = when {
        callingUid != SHELL_UID -> Reject.NotShell
        reportedUid != callingUid || reportedPid != callingPid -> Reject.IdMismatch
        helperApk != appApk -> Reject.StaleApk
        !hasBinder -> Reject.NoBinder
        else -> null
    }
}

/** Handoff rejections of the current launch attempt; the previous reason is no longer shown after a new launch, a successful handoff, or unpairing. */
class HandoffDiagnostics {
    @Volatile var lastRejection: String? = null
        private set

    @Synchronized fun launchStarted() { lastRejection = null }

    @Synchronized fun clear() { lastRejection = null }

    @Synchronized fun reject(why: HelperHandoff.Reject, callingUid: Int) {
        lastRejection = if (why == HelperHandoff.Reject.NotShell) {
            "${why.name}(caller=${HelperHandoff.callerClass(callingUid).name})"
        } else why.name
    }
}

/**
 * The persisted nonces: [resume] is used for the next handoff of the running helper, [launch] is used for a new helper just started from adbd,
 * [launchAt] is when [launch] was issued (milliseconds since boot); [usb] is written into the "start with a computer" script,
 * and [usbAt] is when it was issued (wall-clock milliseconds: the script has to keep working across reboots).
 */
data class NonceSlots(
    val resume: String? = null, val launch: String? = null, val launchAt: Long = 0,
    val usb: String? = null, val usbAt: Long = 0,
)

interface NonceStore {
    fun load(): NonceSlots
    fun save(slots: NonceSlots)
}

/**
 * A single-use handoff nonce generated by Claire. Once any one of them is used, all of them are invalidated and a new [NonceSlots.resume] is issued to the accepted helper;
 * it is stored so that after the Claire process is killed, the new process still recognizes an alive helper that re-handoffs with it.
 * [clock] measures the launch nonce's age (since boot), [wallClock] measures the age of the "start with a computer" nonce.
 */
class HandoffNonces(
    private val store: NonceStore,
    private val clock: () -> Long,
    private val wallClock: () -> Long = clock,
    private val random: () -> String = ::randomNonce,
) {
    @Synchronized
    fun issueLaunch(): String = random().also { store.save(store.load().copy(launch = it, launchAt = clock())) }

    /**
     * The nonce written into the "start with a computer" script. If the one at hand is still fresh (more than half its lifetime remains) keep using it, so it is not swapped while the computer is running the script;
     * otherwise issue a new one. Like the other nonces it is invalidated after a single use (see [accept]).
     */
    @Synchronized
    fun usbNonce(): String {
        val s = store.load()
        val age = wallClock() - s.usbAt
        if (s.usb != null && age in 0..USB_TTL_MS / 2) return s.usb
        return random().also { store.save(s.copy(usb = it, usbAt = wallClock())) }
    }

    @Synchronized
    fun matches(nonce: String?): Boolean {
        if (nonce == null) return false
        val s = store.load()
        val age = clock() - s.launchAt
        // The launch nonce is only valid within the handoff wait window; unused ones expire and become void
        val launch = s.launch?.takeIf { age in 0..LAUNCH_TTL_MS }
        // The nonce in the script waits for the user to plug in and run the command, so it is given longer; expired ones or a clock going backwards do not count
        val usb = s.usb?.takeIf { wallClock() - s.usbAt in 0..USB_TTL_MS }
        return same(nonce, s.resume) or same(nonce, launch) or same(nonce, usb)
    }

    /** If the nonce matches, consumes it and returns the newly issued nonce; otherwise returns null. */
    @Synchronized
    fun accept(nonce: String?): String? {
        if (!matches(nonce)) return null
        return random().also { store.save(NonceSlots(resume = it)) }
    }

    private val _resets = MutableStateFlow(0)
    /** The number of times the nonces were invalidated wholesale ([clear]): when it changes, any nonce already written out (such as the one in the script) can no longer be used. */
    val resets: StateFlow<Int> = _resets.asStateFlow()

    @Synchronized
    fun clear() {
        store.save(NonceSlots())
        _resets.value++
    }

    private fun same(a: String, b: String?) =
        b != null && MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        /** Covers the total duration of the App holding the stream open waiting for the handoff plus the engine waiting one more round. */
        const val LAUNCH_TTL_MS = 30_000L
        /**
         * Lifetime of the "start with a computer" nonce: after the script is written, the user may plug in and run the command some time later (or run it once more after a reboot).
         * Claire rewrites the script every time it is opened, when it cannot see the helper, and when the guide page is opened, so it is swapped once half of its life is used.
         */
        const val USB_TTL_MS = 24 * 60 * 60_000L
        private val rng = SecureRandom()
        fun randomNonce(): String = ByteArray(32).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
