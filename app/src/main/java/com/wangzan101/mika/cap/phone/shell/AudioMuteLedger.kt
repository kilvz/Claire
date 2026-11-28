package com.wangzan101.mika.cap.phone.shell

import com.wangzan101.mika.core.workspace.AtomicFiles
import java.io.File

/**
 * Apps muted by Claire → their PLAY_AUDIO mode before muting, also persisted to a file: if the built-in helper
 * process is killed while muted or the phone reboots, the next start restores the sound from this record.
 * Entries are written when muting and deleted after a successful restore; when file I/O fails it falls back to
 * keeping the record in memory only. Only packages in the record are restored; values set by others are never
 * touched. For the record keys see [AppAudioKey]: the copy in another user profile is a separate entry.
 */
class AudioMuteLedger(private val file: File?) {
    private val modes = LinkedHashMap<String, String>()

    init {
        val text = runCatching { file?.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()
        modes.putAll(parse(text))
    }

    fun contains(packageName: String): Boolean = packageName in modes
    operator fun get(packageName: String): String? = modes[packageName]
    fun packages(): List<String> = modes.keys.toList()

    fun put(packageName: String, mode: String) {
        modes[packageName] = mode
        save()
    }

    fun remove(packageName: String) {
        if (modes.remove(packageName) != null) save()
    }

    /** Restores every package in the record to [restoreMode]; an entry is deleted only when [write] returns true, and failures are kept for the next attempt. Returns whether everything was restored. */
    fun restoreAll(write: (packageName: String, mode: String) -> Boolean): Boolean {
        packages().forEach { pkg -> if (write(pkg, restoreMode(modes.getValue(pkg)))) remove(pkg) }
        return modes.isEmpty()
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            if (modes.isEmpty()) {
                f.delete()
            } else {
                if (!AtomicFiles.write(f, format(modes), durable = false, move = AtomicFiles.Move.Rename)) error("rename failed")
            }
        }
    }

    companion object {
        private val LINE = Regex("""([A-Za-z0-9_.]+(?:@[0-9]+)?) ([a-z_]+)""")

        /**
         * The mode written back on restore. A mode that was explicitly allow/ignore/deny etc. is written back
         * as-is; one that was never set (parsed as default) is written as allow: in Android the audio system
         * only plays sound when PLAY_AUDIO is allow, an explicit default leaves the app silent forever, and
         * since the default the system picks when unset is already allow, writing allow also brings `appops get`
         * back to "not set". A default in the record can only have two sources: it was unset before muting, or
         * an old version corrupted it and it was recorded that way; changing either to allow does no harm.
         */
        fun restoreMode(prior: String?): String = if (prior.isNullOrEmpty() || prior == "default") "allow" else prior

        /** Output of `appops get <pkg> PLAY_AUDIO`: "PLAY_AUDIO: ignore; time=..." → "ignore"; "No operations." → null (never set). */
        fun parseSetMode(output: String): String? = Regex("""PLAY_AUDIO:\s*([a-z_]+)""").find(output)?.groupValues?.get(1)

        /** Distinguishes "not set" (implicitly allow) from an explicit default when reading back. */
        internal fun readMode(output: String): String? = parseSetMode(output)
            ?: Regex("""(?m)^Default mode: ([a-z_]+)$""").find(output)?.groupValues?.get(1)
            ?: "allow".takeIf { output.trim() == "No operations." }

        internal fun format(modes: Map<String, String>): String = modes.entries.joinToString("") { "${it.key} ${it.value}\n" }

        internal fun parse(text: String): Map<String, String> =
            text.lineSequence().mapNotNull { LINE.matchEntire(it.trim())?.destructured }
                .associate { (pkg, mode) -> pkg to mode }
    }
}

/**
 * The key of one mute entry. PLAY_AUDIO is set separately per "package + user": the original copy (in the user
 * profile Claire runs in) is just the package name, while the copy installed in another user profile (clone,
 * work profile) is "package@user id"; the two record their own original value and are restored separately.
 */
object AppAudioKey {
    private val KEY = Regex("""([A-Za-z0-9_.]+)(?:@([0-9]+))?""")

    /** A null [user] means the original copy. */
    fun of(packageName: String, user: Int?): String = if (user == null) packageName else "$packageName@$user"

    /** Splits back into (package name, user); the user is null for the original copy. Returns null when the format is invalid. */
    fun parse(key: String): Pair<String, Int?>? =
        KEY.matchEntire(key)?.destructured?.let { (pkg, user) -> pkg to user.takeIf { it.isNotEmpty() }?.toIntOrNull() }
}
