package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.AttributionSource
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import com.wangzan101.mika.core.device.shell.HelperHandoff
import com.wangzan101.mika.core.device.shell.HelperRestorePolicy
import com.wangzan101.mika.core.device.shell.PermissionCommands
import com.wangzan101.mika.core.device.shell.RestoreCommands
import com.wangzan101.mika.core.device.shell.RestoreWish
import java.io.File
import java.lang.reflect.Proxy
import kotlin.system.exitProcess

/**
 * Entry point for the built-in helper process. Claire starts it as shell through local wireless debugging
 * (adbd's exec: service):
 * `CLASSPATH=<base.apk> app_process /system/bin --nice-name=<package>:helper com.wangzan101.mika.shell.HelperMain <package> <Claire's uid> <cert pin>`,
 * reading the one-time passphrase as a single line from standard input (see [HelperHandoff.command]). The
 * "start from computer" script run from a computer over USB debugging starts the same thing with the same
 * command and arguments (see [HelperHandoff.usbScript]); on rooted phones it is also started as shell via
 * `su 2000 -c` (see [HelperHandoff.rootCommand]).
 *
 * All privileged logic lives in [ShellService]; this only hands its Binder to Claire and does not exit after the
 * Claire process ends: it reverts the changes Claire left behind and hands off again once Claire comes back. If
 * Claire has not returned for a while after dying and the wish it handed over ([RestoreWish]) asks to be brought
 * back, its accessibility service is written back into the system list, and then its process is started through
 * the handoff provider (see [HelperRestorePolicy]).
 */
object HelperProcess {
    private const val TAG = "MikaHelper"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) {
            Log.e(TAG, "usage: HelperMain <package> <uid> <cert-sha256> (nonce on stdin)")
            exitProcess(2)
        }
        val packageName = args[0]
        val appUid = args[1].toIntOrNull() ?: exitProcess(2)
        val certPin = args[2]
        // Close after reading: standard input is used only to deliver the passphrase
        val launchNonce = runCatching { System.`in`.bufferedReader().readLine()?.trim() }.getOrNull()
        runCatching { System.`in`.close() }
        if (launchNonce.isNullOrEmpty()) {
            Log.e(TAG, "no nonce on stdin")
            exitProcess(2)
        }
        // ShellContext needs a main-thread Looper when it builds its own ActivityThread
        Looper.prepareMainLooper()
        val service = ShellService(allowedUid = appUid)
        // Restore sound first for apps that were killed while muted last time and never got restored
        service.recoverMuted()
        val helper = Helper(service, packageName, appUid, certPin)
        helper.start(launchNonce)
        Looper.loop()
    }

    private class Helper(
        private val service: ShellService,
        private val packageName: String,
        private val appUid: Int,
        private val certPin: String,
    ) {
        private val context: Context = ShellContext.create()
        private val authority = HelperHandoff.authority(packageName)
        private val userId = appUid / PER_USER_RANGE
        /** Handoff and Claire death handling are both queued on this single thread, so they do not interleave and do not block the main thread. */
        private val worker = Handler(HandlerThread("helper-handoff").apply { start() }.looper)
        /** The passphrase for the next handoff (issued by Claire when it accepted the last one). */
        private var nonce: String? = null
        /** The token for the Claire process handed to us; null means Claire is not around right now. */
        private var appToken: IBinder? = null
        private var retrying = false
        /** The wish Claire handed over last; a freshly started helper does nothing. */
        private var wish = RestoreWish.NONE
        private val restorePolicy = HelperRestorePolicy()
        /** Incremented on every Claire death: if Claire comes back and dies again during the wait, the earlier wait is invalidated. */
        private var deaths = 0

        init {
            service.onRestoreWish = { flags ->
                worker.post {
                    val next = RestoreWish.fromFlags(flags)
                    if (next != wish) Log.i(TAG, "restore wish: $next")
                    wish = next
                }
            }
        }

        fun start(launchNonce: String) = worker.post {
            when (deliver(launchNonce)) {
                Outcome.Accepted -> {
                    // Claire accepted this one: any helper instances started earlier (or left over) should go away
                    killOtherInstances()
                    watchUid()
                }
                Outcome.Rejected -> exitProcess(0)
                Outcome.Failed -> exitProcess(1)
            }
        }

        private enum class Outcome { Accepted, Rejected, Failed }

        /** Obtains Claire's provider through ActivityManager (the same approach as `content call`) and hands over the Binder plus the self-reported uid/pid. */
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun deliver(n: String?): Outcome {
            if (n == null) return Outcome.Rejected
            // The package name and uid may have been taken over by another app after a reinstall: if the signature does not match, do not hand over the Binder; revert the changes and exit
            if (!signedAsPinned()) {
                Log.e(TAG, "$packageName is missing or not signed with the pinned certificate; exiting")
                runCatching { service.releaseAll() }
                exitProcess(0)
            }
            val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
            val token = Binder()
            val holder = try {
                am.javaClass.getMethod(
                    "getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
                    IBinder::class.java, String::class.java,
                ).invoke(am, authority, userId, token, TAG)
            } catch (t: Throwable) {
                Log.w(TAG, "getContentProviderExternal failed", t)
                return Outcome.Failed
            } ?: return Outcome.Failed
            try {
                // An authority with the same name can belong to only one package; verify it anyway, so the Binder is not given to an app other than Claire
                val info = holder.javaClass.getField("info").get(holder) as? ProviderInfo
                if (info == null || info.packageName != packageName || info.applicationInfo?.uid != appUid) {
                    Log.e(TAG, "provider $authority does not belong to $packageName/$appUid")
                    return Outcome.Rejected
                }
                val provider = holder.javaClass.getField("provider").get(holder)!!
                val extras = Bundle().apply {
                    putString(HelperHandoff.KEY_NONCE, n)
                    putBinder(HelperHandoff.KEY_BINDER, service)
                    putInt(HelperHandoff.KEY_UID, Process.myUid())
                    putInt(HelperHandoff.KEY_PID, Process.myPid())
                    putString(HelperHandoff.KEY_APK, System.getProperty("java.class.path"))
                    restorePolicy.report(System.currentTimeMillis()).takeUnless { it.empty }?.let { r ->
                        putLong(HelperHandoff.KEY_RESTORED_AT, r.lastRestoredAtMs)
                        putInt(HelperHandoff.KEY_RESTORES, r.recent)
                        putLong(HelperHandoff.KEY_GAVE_UP_AT, r.gaveUpAtMs)
                    }
                }
                val call = provider.javaClass.methods.first {
                    it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java
                }
                val caller = AttributionSource.Builder(Process.myUid()).setPackageName(ShellContext.SHELL_PACKAGE).build()
                val result = call.invoke(provider, caller, authority, HelperHandoff.METHOD_ATTACH, null, extras) as Bundle?
                if (result?.getBoolean(HelperHandoff.KEY_ACCEPTED) != true) {
                    Log.w(TAG, "handoff rejected: ${result?.getString(HelperHandoff.KEY_REASON)}")
                    return Outcome.Rejected
                }
                val appBinder = result.getBinder(HelperHandoff.KEY_TOKEN) ?: return Outcome.Rejected
                nonce = result.getString(HelperHandoff.KEY_NEXT)
                appToken = appBinder
                try {
                    appBinder.linkToDeath({ worker.post { onAppDied(appBinder) } }, 0)
                } catch (t: Throwable) {
                    worker.post { onAppDied(appBinder) }
                }
                Log.i(TAG, "handed off to $packageName")
                return Outcome.Accepted
            } catch (t: Throwable) {
                Log.w(TAG, "handoff failed", t)
                return Outcome.Failed
            } finally {
                runCatching {
                    am.javaClass.getMethod(
                        "removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType,
                    ).invoke(am, authority, token, userId)
                }
            }
        }

        /** The Claire process is gone: the displays it created, its mutes and clipboard changes are reverted along with it (as when the service ends), and the helper itself stays. */
        private fun onAppDied(token: IBinder) {
            if (appToken !== token) return
            appToken = null
            runCatching { service.releaseAll() }.onFailure { Log.w(TAG, "cleanup failed", it) }
            if (!installed()) {
                Log.i(TAG, "$packageName is gone; exiting")
                exitProcess(0)
            }
            Log.i(TAG, "$packageName died; waiting for it to come back")
            val death = ++deaths
            worker.postDelayed({ if (death == deaths) maybeRestore() }, HelperRestorePolicy.GRACE_MS)
        }

        /**
         * Claire died a while ago and has not come back: decide whether to bring it back based on the wish it last
         * handed over. If so, first write back the accessibility service (the system removes it on a force-stop),
         * then hand off again: obtaining Claire's provider makes the system start its process (providers are not
         * subject to background start restrictions and show no interface), the Application is created as usual,
         * and the capsule and the other services come up with it. The shell can obtain any app's provider (the
         * same path the `content` command uses).
         */
        private fun maybeRestore() {
            val now = System.currentTimeMillis()
            when (val d = restorePolicy.decide(wish, appBack = appToken != null || retrying, now, forceStopped = forceStopped())) {
                HelperRestorePolicy.Decision.Restore -> Unit
                HelperRestorePolicy.Decision.AppBack -> return
                else -> {
                    Log.i(TAG, "not restoring $packageName: $d")
                    return
                }
            }
            Log.i(TAG, "restoring $packageName (${restorePolicy.report(now).recent} in the last window)")
            if (wish.accessibility) restoreAccessibility()
            retrying = true
            redeliver(0)
        }

        /** A force-stop is recorded by the system as the stopped flag; when reading fails, do not guess and keep the existing abnormal-recovery path. */
        private fun forceStopped(): Boolean = runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            info.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED != 0
        }.getOrElse { t ->
            Log.w(TAG, "cannot read stopped state of $packageName", t)
            false
        }

        /** Appends Claire's accessibility service back into the system list and turns on the master switch; other services are untouched. Failures are only logged, and Claire is still started (it repairs this itself once it is up). */
        private fun restoreAccessibility() {
            val me = PermissionCommands.accessibilityService(packageName)
            val current = service.runCommand(PermissionCommands.READ_ACCESSIBILITY).getString("output")
            for (cmd in RestoreCommands.accessibility(current, me)) {
                val r = service.runCommand(cmd)
                if (r.getInt("exitCode", -1) != 0) {
                    Log.w(TAG, "restore accessibility: '$cmd' failed: ${r.getString("output")?.trim()}")
                    return
                }
            }
            Log.i(TAG, "accessibility service of $packageName written back")
        }

        @SuppressLint("DiscouragedPrivateApi")
        private fun installed(): Boolean = runCatching {
            context.packageManager.javaClass.getMethod("getPackageUidAsUser", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(context.packageManager, packageName, 0, userId)
            true
        }.getOrElse { t ->
            // Exit only when the package is gone; when reflection is unavailable the state cannot be seen, so treat it as still present
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            cause !is android.content.pm.PackageManager.NameNotFoundException
        }

        /**
         * Watches Claire's uid: when the process comes back up (from not existing to any state) the system reports
         * it once, and the Binder is handed over again at that point. It uses ActivityManager's uid importance
         * listener (needs PACKAGE_USAGE_STATS, which the shell has); the callback runs on a binder thread.
         * When registration fails, Claire's side covers it: if no handoff arrives it starts another helper through
         * adbd, and the new one ends this one once accepted.
         */
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun watchUid() {
            runCatching {
                // Build the ActivityManager with a shell-package context: at registration the system checks permissions against the caller's package name
                val manager = ActivityManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
                    .apply { isAccessible = true }.newInstance(context, worker)
                val listenerClass = Class.forName("android.app.ActivityManager\$OnUidImportanceListener")
                val listener = Proxy.newProxyInstance(HelperProcess::class.java.classLoader, arrayOf(listenerClass)) { proxy, method, a ->
                    when (method.name) {
                        "onUidImportance" -> { onUidImportance(a[0] as Int, a[1] as Int); null }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === a?.getOrNull(0)
                        "toString" -> "MikaHelperUidListener"
                        else -> null
                    }
                }
                ActivityManager::class.java.getMethod("addOnUidImportanceListener", listenerClass, Int::class.javaPrimitiveType)
                    .invoke(manager, listener, ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
            }.onFailure { Log.w(TAG, "cannot watch $packageName; relying on the app to restart the helper", it) }
        }

        private fun onUidImportance(uid: Int, importance: Int) {
            if (uid != appUid || importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE) return
            worker.post { if (appToken == null && !retrying) redeliver(0) }
        }

        /** Hands off again; while the provider is temporarily unavailable, retry a few times with a delay, and if that still fails, wait for the next time Claire comes up. */
        private fun redeliver(attempt: Int) {
            if (appToken != null) return
            when (deliver(nonce)) {
                Outcome.Accepted -> retrying = false
                // Claire no longer accepts this helper (reinstalled, unpaired, or already replaced by a new one): exit
                Outcome.Rejected -> exitProcess(0)
                Outcome.Failed -> {
                    retrying = attempt + 1 < RETRY_DELAYS_MS.size
                    if (retrying) worker.postDelayed({ redeliver(attempt + 1) }, RETRY_DELAYS_MS[attempt + 1])
                }
            }
        }

        /** The currently installed [packageName] still has this uid and a signing certificate pin identical to the one Claire passed at startup. Anything unclear counts as a mismatch. */
        @SuppressLint("DiscouragedPrivateApi")
        private fun signedAsPinned(): Boolean = runCatching {
            val pm = context.packageManager
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val info = if (userId == 0) pm.getPackageInfo(packageName, flags)
            else pm.javaClass.getMethod("getPackageInfoAsUser", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(pm, packageName, flags, userId) as PackageInfo
            val signers = info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
            info.applicationInfo?.uid == appUid && HelperHandoff.certPin(signers) == certPin
        }.getOrElse { t ->
            Log.w(TAG, "cannot read signing info of $packageName", t)
            false
        }

        /** Only processes with the same uid and exactly the same process name are matched: other app_process instances must not be hit by mistake. */
        private fun killOtherInstances() {
            val me = Process.myPid()
            val name = HelperHandoff.processName(packageName)
            File("/proc").listFiles()?.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                if (pid == me) return@forEach
                val cmdline = runCatching { File(dir, "cmdline").readBytes() }.getOrNull() ?: return@forEach
                val argv0 = String(cmdline.takeWhile { it != 0.toByte() }.toByteArray())
                if (argv0 == name) {
                    Log.i(TAG, "stopping older helper $pid")
                    Process.killProcess(pid)
                }
            }
        }

        companion object {
            private const val PER_USER_RANGE = 100_000
            private val RETRY_DELAYS_MS = longArrayOf(0, 1_000, 3_000, 10_000)
        }
    }
}
