package com.wangzan101.mika.cap.phone.shell

import androidx.core.os.BundleCompat
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.core.device.shell.HandoffDiagnostics
import com.wangzan101.mika.core.device.shell.HandoffNonces
import com.wangzan101.mika.core.device.shell.HelperHandoff
import com.wangzan101.mika.core.device.shell.HelperRestoreReport
import com.wangzan101.mika.core.device.shell.NonceSlots
import com.wangzan101.mika.core.device.shell.NonceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.os.ParcelFileDescriptor
import java.io.IOException
import com.wangzan101.mika.shell.IMikaShell

/**
 * The landing point for the built-in helper's Binder inside the Claire process. The helper hands its Binder over
 * through [HandoffProvider], and it is verified and accepted here; a single instance is created by
 * [com.wangzan101.mika.cap.phone.PhoneServices] (the helper recognizes only one landing point per process), and
 * both [BuiltinShellConnection] and the watchdog observe its [shell].
 */
class HelperHub internal constructor(
    context: Context,
    private val directoryStore: com.wangzan101.mika.settings.DeviceDirectoryStore = com.wangzan101.mika.settings.DeviceDirectoryStore.get(context),
    initialShell: IMikaShell? = null,
) {
    private val app = context.applicationContext
    /** The user paused Claire: all hand-offs are rejected, see [offer], [attachShizuku]. Reads the persisted pause flag, freshly on every hand-off. */
    private val pause = com.wangzan101.mika.settings.MikaPause.get(app)

    private val _shell = MutableStateFlow<IMikaShell?>(initialShell)
    /** The helper interface currently in hand; reverts to null once the helper process dies. */
    val shell: StateFlow<IMikaShell?> = _shell.asStateFlow()

    private val _restores = MutableStateFlow<HelperRestoreReport?>(null)
    /** The record reported at handoff of "brought back after Claire was killed" (for diagnostics); null when it never brought Claire back. */
    val restores: StateFlow<HelperRestoreReport?> = _restores.asStateFlow()

    private val handoffDiagnostics = HandoffDiagnostics()

    /** Handoff rejections from this launch attempt; carries no passphrase, Binder, package path or concrete UID. */
    val lastHandoffRejection: String? get() = handoffDiagnostics.lastRejection

    /** Connects this process's WebView debugging socket through the helper; throws IOException when the helper is not running, and returns null when the helper refuses or the connection fails. */
    fun openDevtoolsSocket(endpoint: String): ParcelFileDescriptor? {
        val sh = shell.value ?: throw IOException("phone helper is not running")
        return sh.openDevtoolsSocket(endpoint)
    }

    private val deviceFilesLock = Any()
    private val deviceFilesSession = java.util.UUID.randomUUID().toString()
    private val detachScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Synchronize on every call, including after helper reconnection; never fall back to an old policy. */
    fun syncDeviceFiles(): Boolean = synchronized(deviceFilesLock) {
        val sh = shell.value ?: run { directoryStore.clearSync(); return@synchronized false }
        val snapshot = try { directoryStore.authorizationSnapshot() } catch (_: Exception) {
            directoryStore.clearSync(); return@synchronized false
        }
        syncDeviceFilesLocked(sh, snapshot)
    }

    private fun syncDeviceFilesLocked(sh: IMikaShell, snapshot: com.wangzan101.mika.settings.DeviceDirectoryAuthorizationSnapshot): Boolean {
        return try {
            val result = sh.setDeviceFilePolicy(1, deviceFilesSession, snapshot.revision, snapshot.directories.map { it.path }.toTypedArray(),
                snapshot.directories.map { it.includeSubdirectories }.toBooleanArray())
            val ok = result?.getString("status") == "ok" && result.getInt("protocol") == 1 &&
                result.getString("session") == deviceFilesSession && result.getLong("revision", -1) == snapshot.revision &&
                shell.value === sh && directoryStore.acknowledgeSynced(snapshot.revision)
            if (!ok) directoryStore.clearSync()
            ok
        } catch (_: Exception) { directoryStore.clearSync(); false }
    }

    private inline fun deviceFileCall(block: (IMikaShell) -> Bundle): Bundle = synchronized(deviceFilesLock) {
        val sh = shell.value ?: return@synchronized Bundle().apply { putString("status", "policy_unsynced") }
        val snapshot = try { directoryStore.authorizationSnapshot() } catch (_: Exception) {
            directoryStore.clearSync(); return@synchronized Bundle().apply { putString("status", "policy_unsynced") }
        }
        if (!syncDeviceFilesLocked(sh, snapshot)) return@synchronized Bundle().apply { putString("status", "policy_unsynced") }
        val response = block(sh)
        val revision = try { directoryStore.authorizationSnapshot().revision } catch (_: Exception) { null }
        if (shell.value !== sh || revision != snapshot.revision) {
            BundleCompat.getParcelable(response, "fd", ParcelFileDescriptor::class.java)?.close()
            directoryStore.clearSync()
            Bundle().apply { putString("status", "policy_changed") }
        } else response
    }

    /** Lists a directory with the helper's shell privileges; for the fields and errors see IMikaShell.listFiles. */
    fun listFiles(path: String, afterName: String?, limit: Int): Bundle = deviceFileCall { it.listFiles(path, afterName, limit) }

    fun searchFiles(path: String, name: String?, extension: String?, after: Long, before: Long, depth: Int, entries: Int, timeout: Int): Bundle =
        deviceFileCall { it.searchFiles(path, name, extension, after, before, depth, entries, timeout) }

    /** On success the caller must close the returned fd. */
    fun openFile(path: String): Bundle = deviceFileCall { it.openFile(path) }

    /** The token for this process handed to the helper: as soon as the Claire process dies, the helper learns it from its death notification and reverts the changes Claire left behind. */
    private val token = Binder()
    private val nonces: HandoffNonces by lazy { HandoffNonces(PrefsNonceStore(app), SystemClock::elapsedRealtime, System::currentTimeMillis) }

    /** Called before starting a new helper through adbd: this one-time passphrase is handed to the helper on the standard input of the exec stream. */
    fun issueLaunchNonce(): String {
        handoffDiagnostics.launchStarted()
        return nonces.issueLaunch()
    }

    /** The one-time passphrase written into the "start from computer" script (see [HandoffNonces.usbNonce]); the handoff goes through the same [offer]. */
    fun usbNonce(): String = nonces.usbNonce()

    /** How many times the passphrases have been invalidated as a whole (see [HandoffNonces.resets]): the "start from computer" script is rewritten in step with it. */
    val nonceResets: StateFlow<Int> get() = nonces.resets

    /** Unpair: invalidates all passphrases (a helper still alive is rejected and exits on its next handoff) and also releases the interface in hand. */
    fun forget() {
        nonces.clear()
        handoffDiagnostics.clear()
        synchronized(deviceFilesLock) {
            _shell.value = null
            directoryStore.clearSync()
        }
    }

    /**
     * The helper comes to hand off. Accepted only when it runs as shell (uid 2000), its self-reported uid/pid
     * match the caller, it runs this very APK and the passphrase is correct; then the Binder is called once more
     * to confirm it really lives in the process performing the handoff, and only then is the passphrase consumed
     * and the next one issued.
     */
    @Synchronized
    fun offer(extras: Bundle?, callingUid: Int, callingPid: Int): Bundle {
        val binder = extras?.getBinder(HelperHandoff.KEY_BINDER)
        HelperHandoff.check(
            callingUid, callingPid,
            reportedUid = extras?.getInt(HelperHandoff.KEY_UID, -1) ?: -1,
            reportedPid = extras?.getInt(HelperHandoff.KEY_PID, -1) ?: -1,
            helperApk = extras?.getString(HelperHandoff.KEY_APK),
            appApk = app.applicationInfo.sourceDir,
            hasBinder = binder != null,
        )?.let { return reject(callingUid, it) }
        // While paused, touch neither the token nor Binder: the rejected helper exits by itself, and after resume the watchdog restarts it over the paired connection
        if (pause.paused()) return reject(callingUid, HelperHandoff.Reject.Paused)
        val gate = nonces
        val nonce = extras?.getString(HelperHandoff.KEY_NONCE)
        // If the passphrase is wrong, do not touch the Binder supplied by the other side
        if (!gate.matches(nonce)) return reject(callingUid, HelperHandoff.Reject.BadNonce)
        val shell = IMikaShell.Stub.asInterface(binder!!)
        val ids = runCatching { shell.identity() }.getOrNull()
        if (ids == null || ids.size < 2 || ids[0] != callingUid || ids[1] != callingPid) {
            return reject(callingUid, HelperHandoff.Reject.BinderElsewhere)
        }
        val next = gate.accept(nonce) ?: return reject(callingUid, HelperHandoff.Reject.BadNonce)
        try {
            binder.linkToDeath({ synchronized(deviceFilesLock) { if (_shell.compareAndSet(shell, null)) directoryStore.clearSync() } }, 0)
        } catch (e: Exception) {
            return reject(callingUid, HelperHandoff.Reject.NoBinder)
        }
        // A new helper replaced the old one: the old one is terminated by the new one shortly after, and its death notification is ignored because it is not the current one
        handoffDiagnostics.clear()
        synchronized(deviceFilesLock) {
            directoryStore.clearSync()
            _shell.value = shell
        }
        if (extras?.containsKey(HelperHandoff.KEY_RESTORED_AT) == true) {
            _restores.value = HelperRestoreReport(
                extras.getLong(HelperHandoff.KEY_RESTORED_AT), extras.getInt(HelperHandoff.KEY_RESTORES), extras.getLong(HelperHandoff.KEY_GAVE_UP_AT),
            )
        }
        Log.i(TAG, "helper pid $callingPid handed off")
        return Bundle().apply {
            putBoolean(HelperHandoff.KEY_ACCEPTED, true)
            putString(HelperHandoff.KEY_NEXT, next)
            putBinder(HelperHandoff.KEY_TOKEN, token)
        }
    }

    /**
     * Attach a helper binder obtained from Shizuku's UserService.
     * Verifies the remote identity runs as shell (2000) or root (0) and links death cleanup.
     */
    @Synchronized
    fun attachShizuku(binder: IBinder): IMikaShell? {
        // Pause was checked before start but Binder arrived after the pause: do not accept it; the caller sees null and unbinds
        if (pause.paused()) {
            Log.w(TAG, "shizuku helper refused: Mika is paused")
            return null
        }
        val shell = IMikaShell.Stub.asInterface(binder) ?: return null
        val ids = runCatching { shell.identity() }.getOrNull()
        if (ids == null || ids.size < 2 || (ids[0] != HelperHandoff.SHELL_UID && ids[0] != 0)) {
            Log.w(TAG, "shizuku helper identity rejected: ${ids?.contentToString()}")
            return null
        }
        try {
            binder.linkToDeath(
                {
                    synchronized(deviceFilesLock) {
                        if (_shell.compareAndSet(shell, null)) directoryStore.clearSync()
                    }
                },
                0,
            )
        } catch (_: Exception) {
            return null
        }
        handoffDiagnostics.clear()
        synchronized(deviceFilesLock) {
            directoryStore.clearSync()
            _shell.value = shell
        }
        Log.i(TAG, "shizuku helper pid ${ids[1]} uid ${ids[0]} attached")
        return shell
    }

    /** Detach the Shizuku-backed helper and release any resources it held for Claire. */
    fun detachShizuku(shell: IMikaShell? = null) {
        val target = synchronized(deviceFilesLock) {
            val current = _shell.value
            if (shell == null || current === shell) {
                _shell.value = null
                directoryStore.clearSync()
                current
            } else {
                null
            }
        }
        if (target != null) {
            detachScope.launch { runCatching { target.releaseAll() } }
        }
    }

    private fun reject(callingUid: Int, why: HelperHandoff.Reject): Bundle {
        handoffDiagnostics.reject(why, callingUid)
        Log.w(TAG, "helper handoff rejected: ${handoffDiagnostics.lastRejection}")
        return Bundle().apply {
            putBoolean(HelperHandoff.KEY_ACCEPTED, false)
            putString(HelperHandoff.KEY_REASON, why.name)
        }
    }

    private companion object {
        const val TAG = "MikaHelperHub"
        const val PREFS = "builtin_helper"
    }

    private class PrefsNonceStore(context: Context) : NonceStore {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun load() = NonceSlots(
            prefs.getString("resume", null), prefs.getString("launch", null), prefs.getLong("launchAt", 0),
            prefs.getString("usb", null), prefs.getLong("usbAt", 0),
        )
        // Write synchronously: Claire may be killed right after the helper receives the new passphrase, so the new process must recognize it
        override fun save(slots: NonceSlots) {
            prefs.edit().putString("resume", slots.resume).putString("launch", slots.launch).putLong("launchAt", slots.launchAt)
                .putString("usb", slots.usb).putLong("usbAt", slots.usbAt).commit()
        }
    }
}

/**
 * The entry point for the built-in helper's handoff Binder. It is exported because the shell must be able to
 * reach it, and the manifest additionally requires INTERACT_ACROSS_USERS_FULL (the shell has it, ordinary apps
 * do not), so ordinary apps cannot even obtain the provider; the real gate is [HelperHub.offer].
 * The landing point is the app's single instance ([PhoneServices.of]): the app object is constructed before the
 * provider, but handoff calls may arrive before Application.onCreate or at the same time as it; safety relies on
 * both MikaApp.phone and the passphrase in [HelperHub] being synchronous lazies (whoever arrives first creates
 * it, and it is created only once), and [HelperHub.offer] itself is locked too, so it does not depend on
 * onCreate having finished first.
 */
open class HandoffProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != HelperHandoff.METHOD_ATTACH) return null
        val ctx = context ?: return null
        return PhoneServices.of(ctx).hub.offer(extras, Binder.getCallingUid(), Binder.getCallingPid())
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
