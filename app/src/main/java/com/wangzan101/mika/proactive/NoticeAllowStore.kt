package com.wangzan101.mika.proactive

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/**
 * Which Apps' notifications the proactive capability reads: a package-name whitelist in its own JSON file, replaced atomically as a whole.
 * Empty by default: nothing is read.
 *
 * If the file cannot be read (corrupt, or written by a newer version) it counts as empty — reading less is preferred; but that does not mean
 * the user selected none: [readable] is false then, and nothing else may use this empty list to delete what has already been accumulated (see
 * LearnRunner.poke). The next time the user changes the list it is written as usual, and it becomes readable afterwards.
 *
 * When it cannot be written (storage full or the like), it errs toward reading less: removing an App still takes effect in memory (it is no
 * longer read for this run), adding one does not; both honestly report that it was not saved ([setAllowed] returns false) and nothing is
 * thrown. A "remove" that was not saved must be redone after a restart, see [AllowEdits].
 */
class NoticeAllowStore(private val file: File) {
    /** Whether the list really was read (no file counts as read: it is simply empty). */
    @Volatile var readable: Boolean = true
        private set

    private val mutable = MutableStateFlow(load())
    val state: StateFlow<Set<String>> = mutable.asStateFlow()

    fun current(): Set<String> = mutable.value

    /**
     * The in-memory list has fewer entries than the file (some "remove" was not saved): on the next list change, write again regardless of
     * whether anything changed. The page uses this to ask the user to choose again — the prompt stays as long as the list is not written,
     * regardless of whether the latest change succeeded.
     */
    @Volatile var dirty = false
        private set

    /**
     * Whether this App's notifications are read. Returns whether it has been saved (when the list already looks like this and the file
     * matches, nothing is written and it counts as saved). When it was not saved: a removal still takes effect in memory, an addition does
     * not (an App already on the list is unaffected and still counts as saved).
     */
    @Synchronized fun setAllowed(pkg: String, allowed: Boolean): Boolean {
        val now = current()
        val next = if (allowed) now + pkg else now - pkg
        if (next == now && !dirty) return true
        if (persist(next)) return true
        if (!allowed) {
            mutable.value = next
            dirty = true
            return false
        }
        // What failed to be written is only the retry of other Apps' pending "remove": this App is already on the list, and in the file too
        return next == now
    }

    @Synchronized fun clear(): Boolean {
        if (persist(emptySet())) return true
        mutable.value = emptySet()
        dirty = true
        return false
    }

    /** Write the whole list; only replace the in-memory copy once it succeeds. Returns false when it cannot be written (storage full, directory not writable), leaving memory untouched. */
    private fun persist(next: Set<String>): Boolean {
        try {
            file.parentFile?.mkdirs()
            val json = buildJsonObject {
                put("version", 1)
                put("allowed", JsonArray(next.sorted().map(::JsonPrimitive)))
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            // Flush to disk before replacing: a power cut never leaves an empty file in place of the original list
            java.io.FileOutputStream(tmp).use { out ->
                out.write(json.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.io.IOException) {
            return false
        } catch (e: SecurityException) {
            return false
        }
        readable = true
        dirty = false
        mutable.value = next
        return true
    }

    private fun load(): Set<String> {
        if (!file.exists()) return emptySet()
        return runCatching {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            require(o["version"]?.jsonPrimitive?.int == 1)
            o.getValue("allowed").jsonArray.map { it.jsonPrimitive.content }.toSet()
        }.getOrElse {
            readable = false
            emptySet()
        }
    }
}

/** Apps the user chose "do not read" for while the list file could not be written (package names only): kept in preferences and applied first after a restart. */
interface AllowRevokes {
    var allowRevoked: Set<String>
}

/**
 * Changing the list takes two steps: write the list file; when a "do not read" could not be written, record this App in [AllowRevokes].
 * Without recording, it would not be read for this run, but it stays in the file and reading resumes after the process restarts, without the
 * user knowing.
 *
 * This recording is best effort: the list usually cannot be written because storage is full, in which case the preferences usually cannot be
 * written either (whether that write succeeded is unknown here). The real fallback is the page's "not saved, please choose again" — it stays
 * as long as the list is not written ([NoticeAllowStore.dirty]).
 */
internal object AllowEdits {
    /** Whether this App's notifications are read: returns whether the list file was saved (see [NoticeAllowStore.setAllowed]). */
    fun set(store: NoticeAllowStore, revokes: AllowRevokes, pkg: String, allowed: Boolean): Boolean {
        val saved = store.setAllowed(pkg, allowed)
        val pending = revokes.allowRevoked
        val next = when {
            !allowed && !saved -> pending + pkg
            saved -> pending - pkg
            else -> pending
        }
        if (next != pending) revokes.allowRevoked = next
        return saved
    }

    /** On startup: first remove the "do not read" entries that were not saved last time from the in-memory list, then try to write it again; ones that succeed are no longer recorded. Call before reading any notification. */
    fun replay(store: NoticeAllowStore, revokes: AllowRevokes) {
        val pending = revokes.allowRevoked
        if (pending.isEmpty()) return
        val left = pending.filterNot { store.setAllowed(it, false) }.toSet()
        if (left != pending) revokes.allowRevoked = left
    }
}
