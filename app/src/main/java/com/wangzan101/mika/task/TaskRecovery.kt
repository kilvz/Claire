package com.wangzan101.mika.task

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.cap.phone.shell.AppAudioKey
import com.wangzan101.mika.cap.phone.shell.SharedShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** A persisted set of "original values"; a null value means the entry didn't exist originally. [load] returns null when there is no record. */
interface MarkerStore {
    fun load(): Map<String, String?>?
    fun save(values: Map<String, String?>)
    fun clear()
}

/** Stored in SharedPreferences with a synchronous commit: it must hit disk before the system state is changed. */
class PrefsMarker(private val prefs: SharedPreferences, private val name: String) : MarkerStore {
    override fun load(): Map<String, String?>? {
        val keys = prefs.getStringSet("$name.keys", null) ?: return null
        return keys.associateWith { prefs.getString("$name.v.$it", null) }
    }

    override fun save(values: Map<String, String?>) {
        val e = prefs.edit()
        clearInto(e)
        e.putStringSet("$name.keys", values.keys.toSet())
        values.forEach { (k, v) -> e.putString("$name.v.$k", v) }
        e.commit()
    }

    override fun clear() {
        prefs.edit().also { clearInto(it) }.commit()
    }

    private fun clearInto(e: SharedPreferences.Editor) {
        prefs.all.keys.filter { it.startsWith("$name.") }.forEach { e.remove(it) }
    }
}

/** The shell ability needed for muting. The parameter is the mute key (see [AppAudioKey]): the original copy is the package name, while the copy in another user profile carries the user ID. */
interface AudioPort {
    /** The current PLAY_AUDIO mode; null when it can't be read. */
    fun mode(packageName: String): String?
    fun mute(packageName: String): Boolean
    fun setMode(packageName: String, mode: String): Boolean
}

class ShellAudio(private val shell: IMikaShell) : AudioPort {
    override fun mode(packageName: String): String? = runCatching {
        val (pkg, user) = AppAudioKey.parse(packageName) ?: return null
        if (user == null) shell.getAudioMode(pkg) else shell.getAudioModeInUser(pkg, user)
    }.getOrNull()

    override fun mute(packageName: String): Boolean = runCatching {
        val (pkg, user) = AppAudioKey.parse(packageName) ?: return false
        if (user == null) shell.setAudioMuted(pkg, true) else shell.setAudioMutedInUser(pkg, user, true)
    }.getOrDefault(false)

    override fun setMode(packageName: String, mode: String): Boolean = runCatching {
        // Treat a malformed key as already restored: keeping it won't restore anything
        val (pkg, user) = AppAudioKey.parse(packageName) ?: return true
        if (user == null) shell.setAudioMode(pkg, mode) else shell.setAudioModeInUser(pkg, user, mode)
    }.getOrDefault(false)
}

/**
 * Turn off the target app's sound during a task. The original mode is written to a persistent record before muting: the shell service ends
 * together with the Claire process and loses the original values held in memory, so only this record can restore them on the next start.
 */
class AudioMute(private val port: AudioPort, private val store: MarkerStore) {
    @Synchronized
    fun mute(packageName: String): Boolean {
        val saved = store.load().orEmpty()
        if (packageName !in saved) {
            val prior = port.mode(packageName) ?: return false
            store.save(saved + (packageName to prior))
        }
        return port.mute(packageName)
    }

    /** Restore a single app (so it can make sound during a takeover); if it can't be restored it stays in the record. An app that was never muted counts as restored. */
    @Synchronized
    fun restore(packageName: String): Boolean {
        val saved = store.load().orEmpty()
        if (packageName !in saved) return true
        if (!port.setMode(packageName, saved[packageName] ?: "default")) return false
        val left = saved - packageName
        if (left.isEmpty()) store.clear() else store.save(left)
        return true
    }

    /** Restore every app in the record; any that can't be restored stay in the record to be retried next time. */
    @Synchronized
    fun restoreAll(setMode: (String, String) -> Boolean = port::setMode): Boolean {
        val saved = store.load() ?: return true
        val left = saved.filterNot { (pkg, mode) -> setMode(pkg, mode ?: "default") }
        if (left.isEmpty()) store.clear() else store.save(left)
        return left.isEmpty()
    }
}

/**
 * Encrypted backup of the user's original clipboard text (and of the text Claire put in) while Claire borrows the clipboard to paste: if the shell process is killed together with Claire,
 * the original clipboard held in its memory is lost, and only this backup can return the user's content on the next start. It is encrypted with [cipher] and stored as one string, then deleted after restoring.
 */
class ClipBackup(private val read: () -> String?, private val write: (String?) -> Unit, private val cipher: KeyCipher) {
    fun save(original: String, ours: String) {
        val plain = buildJsonObject { put("original", original); put("ours", ours) }.toString().toByteArray()
        write(Base64.getEncoder().encodeToString(cipher.encrypt(plain)))
    }

    /** (original text, text Claire put in); null when there is no backup or it can't be decrypted (the key has changed). */
    fun load(): Pair<String, String>? = runCatching {
        val data = read() ?: return null
        val o = Json.parseToJsonElement(String(cipher.decrypt(Base64.getDecoder().decode(data)))).jsonObject
        o.getValue("original").jsonPrimitive.content to o.getValue("ours").jsonPrimitive.content
    }.getOrNull()

    fun clear() = write(null)
}

/**
 * System changes left behind when Claire exits abnormally (app muting, the clipboard temporarily swapped while pasting) are restored on the next start.
 * Mute records are kept per task (by [TaskRun.key]), so when several tasks run at once each task records its own packages.
 * Changes made during a task are cleaned up by the task itself; recovery only handles the ones nobody cleaned up: records of tasks still running ([live]) are left
 * untouched, and the clipboard is not restored while any task is running (it may be borrowing the clipboard to paste at that moment).
 * The App creates one instance and provides it through [Owner] ([live] must be shared across tasks); waiting for the helper uses the process-wide [sharedShell].
 */
class TaskRecovery(context: Context, private val sharedShell: SharedShell) {
    private val app = context.applicationContext
    private val clipCipher by lazy { KeystoreKeyCipher(CLIP_KEY_ALIAS) }

    /** Shared with the registration at task start, so the two never interleave. */
    val lock = Mutex()

    /** Tasks still running; empty after the process is killed, so every record falls to recovery. */
    private val live = ConcurrentHashMap.newKeySet<String>()

    fun audioMute(shell: IMikaShell, runKey: String) = AudioMute(ShellAudio(shell), marker(runKey))

    private fun marker(runKey: String) = PrefsMarker(prefs(), "${MUTED}_$runKey")

    /** The names of all existing mute records (including the single legacy one). */
    private fun markerNames(): List<String> =
        prefs().all.keys.filter { it.startsWith(MUTED) && it.endsWith(".keys") }.map { it.removeSuffix(".keys") }

    private fun liveNames() = live.mapTo(HashSet()) { "${MUTED}_$it" }

    fun pending(): Boolean =
        markerNames().any { it !in liveNames() } || (live.isEmpty() && prefs().getBoolean(CLIP_HELD, false))

    /** Task start: first restore leftovers nobody cleaned up, then register as running. */
    suspend fun beginRun(shell: IMikaShell, runKey: String) = lock.withLock {
        withContext(Dispatchers.IO) { recoverWith(shell) }
        live += runKey
    }

    /** Called after task teardown (with its own muting already restored); anything not restored stays in the record for recovery next time. */
    fun endRun(runKey: String) {
        live -= runKey
    }

    /** The clipboard belongs to Claire while pasting; if the process ends meanwhile, the next start makes the shell put the user's original clipboard back. */
    fun markClipHeld(held: Boolean) {
        prefs().edit().putBoolean(CLIP_HELD, held).apply { if (!held) remove(CLIP_BACKUP) }.commit()
    }

    private fun clipBackup() = ClipBackup(
        { prefs().getString(CLIP_BACKUP, null) },
        { v -> prefs().edit().apply { if (v == null) remove(CLIP_BACKUP) else putString(CLIP_BACKUP, v) }.commit() },
        clipCipher,
    )

    /** Back up after the clipboard swap succeeds; if encryption fails no backup is kept (only losing the restore after the process is killed). */
    fun saveClip(original: String, ours: String) {
        runCatching { clipBackup().save(original, ours) }.onFailure { Log.w(TAG, "clipboard backup not saved", it) }
    }

    /** Called while [lock] is already held. */
    fun recoverWith(shell: IMikaShell) {
        val records = markerNames().associateWith { PrefsMarker(prefs(), it) }
        if (!restoreRuns(records, ShellAudio(shell), liveNames())) Log.w(TAG, "muted apps not restored yet")
        if (live.isNotEmpty()) return
        // If the shell process is still alive, restore the original clipboard it remembers (it does nothing when the user has already replaced the clipboard);
        // if the shell was killed too, restore from the encrypted backup; both steps only swap the clipboard while it still belongs to Claire
        val inShell = runCatching { shell.restoreClip() }.getOrDefault(false)
        val fromBackup = restoreClip(clipBackup()) { o, u -> shell.restoreClipText(o, u) }
        if (inShell && fromBackup) markClipHeld(false)
    }

    /**
     * Called at startup: if there are leftover changes, restore them once the built-in helper connects; if it doesn't connect, leave them for next time.
     * Waiting for the helper holds no lock, so new tasks don't have to wait along; once the lock is acquired, check once more whether anything is left.
     */
    suspend fun recover() {
        if (!pending()) return
        val connection = sharedShell.acquire()
        try {
            val shell = withTimeoutOrNull(SHELL_WAIT_MS) { connection.shell.filterNotNull().first() } ?: return
            lock.withLock { if (pending()) recoverWith(shell) }
        } finally {
            sharedShell.release()
        }
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** An Application that provides the process-wide instance. */
    interface Owner {
        val taskRecovery: TaskRecovery
    }

    companion object {
        private const val TAG = "MikaTask"
        private const val PREFS = "task_recovery"
        private const val SHELL_WAIT_MS = 60_000L
        private const val CLIP_HELD = "clip_held"
        private const val CLIP_BACKUP = "clip_backup"
        private const val CLIP_KEY_ALIAS = "mika-clip-backup"
        /** Each task's mute record is named muted_<task key>; the legacy version had a single one named muted, and it is restored as well. */
        private const val MUTED = "muted"

        fun of(context: Context): TaskRecovery = (context.applicationContext as? Owner)?.taskRecovery
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement TaskRecovery.Owner to give the task recovery")

        /** Restore every mute in [records] (record name → record) that doesn't belong to [live]; returns true when all were restored. */
        fun restoreRuns(records: Map<String, MarkerStore>, port: AudioPort, live: Set<String>): Boolean {
            // The same original value for the same package and user is only tried once. If a different original value turns up, restoration still proceeds in record order.
            val attempts = HashMap<String, Pair<String, Boolean>>()
            return records.filterKeys { it !in live }.values.map { store ->
                AudioMute(port, store).restoreAll { pkg, mode ->
                    val previous = attempts[pkg]
                    if (previous?.first == mode) previous.second
                    else port.setMode(pkg, mode).also { attempts[pkg] = mode to it }
                }
            }.all { it }
        }

        /** Return the user's original clipboard from the backup; the backup is deleted only once it has been restored, the user has copied something else, or there is no backup, and it is kept for next time when it can't be read. */
        fun restoreClip(backup: ClipBackup, restore: (original: String, ours: String) -> Boolean): Boolean {
            val (original, ours) = backup.load() ?: return true.also { backup.clear() }
            return runCatching { restore(original, ours) }
                .onSuccess { finished -> if (finished) backup.clear() }
                .getOrDefault(false)
        }
    }
}
