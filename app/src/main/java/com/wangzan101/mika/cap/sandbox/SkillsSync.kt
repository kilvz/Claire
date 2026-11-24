package com.wangzan101.mika.cap.sandbox

import androidx.annotation.VisibleForTesting
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.cap.sandbox.RootfsExtractor
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/**
 * Sync the skill scripts bundled with the App (a manifest plus the files it lists) into `files/sandbox/skills/`, which is `/opt/mika/skills` inside the sandbox.
 * That directory is writable from inside the sandbox (PRoot's bind is writable), so every run compares against what is actually on disk: if it matches the bundled copy exactly, nothing is touched;
 * for extra, missing or modified files, or for non-regular entries such as links, a fresh copy is first written alongside and then swapped in as a whole.
 *
 * Concurrent tasks: syncs are serialized by [lock]; at the moment the directory is swapped, a script starting up in another task may fail to find its files and error out (reported as-is),
 * while interpreters already running have long since read their scripts and are unaffected. run_bundled_script also re-verifies byte by byte ([matches]) before running.
 */
object SkillsSync {
    private val lock = Mutex()
    private val packaged by lazy { GuideCatalog.loadBundled().scriptFiles() }

    /** For on-device testing only: temporarily add a test skill. Production code never sets this. */
    @VisibleForTesting
    @Volatile var extraForTests: Map<String, ByteArray> = emptyMap()

    val bundled: Map<String, ByteArray> get() = if (extraForTests.isEmpty()) packaged else packaged + extraForTests

    /**
     * Mutually exclusive with install and reset via [SandboxTree] (during install, this step runs under the permit the install already holds); [proceed] is checked once more after acquiring the permit:
     * if the runtime was reset while waiting for the permit (no longer ready), nothing is written, so a skills directory is not recreated in a directory that was just emptied.
     */
    suspend fun sync(dirs: SandboxDirs, files: Map<String, ByteArray> = bundled, proceed: () -> Boolean = { true }) = dirs.tree.shared { lock.withLock {
        withContext(Dispatchers.IO) {
            if (!proceed()) return@withContext
            if (onDisk(dirs)?.let(::hashOf) == hashOf(files)) return@withContext
            val staging = File(dirs.root, "skills.new")
            RootfsExtractor.deleteTree(staging)
            staging.mkdirs()
            for ((rel, bytes) in files) {
                // Paths come from the bundled manifest; they are still resolved by the rules so nothing can be written outside the directory
                val f = SandboxPaths.resolveHost(staging, rel) ?: continue
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
            }
            // The skills directory is writable from the sandbox: deleting the whole tree must go by handle, without descending into a directory that was swapped for a link midway
            dirs.gate.deleteTree(dirs.root, dirs.skills.name)
            check(staging.renameTo(dirs.skills)) { "could not move synced skills into place" }
            // Older versions detected it via a marker file; that is no longer used
            File(dirs.root, "skills.sha256").delete()
        }
    } }

    /** [rel] on disk (read through the gate, without following links) is byte-for-byte identical to the bundled [expected]. */
    internal fun matches(dirs: SandboxDirs, rel: String, expected: ByteArray): Boolean = try {
        dirs.gate.openRead(dirs.skills, rel).use { g -> g.size == expected.size.toLong() && g.read(0, expected.size).contentEquals(expected) }
    } catch (e: SandboxError) {
        false
    } catch (e: IOException) {
        false
    }

    /** Every file in the directory (relative path → contents); null when there are links, special files, oversized files or unreadable ones (always treated as a mismatch). The directory listing is only used to find names; contents are read through the gate. */
    private fun onDisk(dirs: SandboxDirs): Map<String, ByteArray>? {
        val base = dirs.skills.toPath()
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) return null
        val out = HashMap<String, ByteArray>()
        var total = 0L
        return try {
            Files.walk(base).use { paths ->
                for (p in paths) {
                    if (p == base || Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue
                    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return null
                    val rel = base.relativize(p).joinToString("/")
                    out[rel] = dirs.gate.openRead(dirs.skills, rel).use { g ->
                        total += g.size
                        if (total > MAX_ON_DISK) return null
                        g.read(0, g.size.toInt())
                    }
                }
            }
            out
        } catch (e: SandboxError) {
            null
        } catch (e: IOException) {
            null
        } catch (e: java.io.UncheckedIOException) {
            null
        }
    }

    /** The bundled skill scripts are far smaller than this; large files stuffed in from inside the sandbox are not read into memory and are simply treated as a mismatch that triggers a re-sync. */
    private const val MAX_ON_DISK = 64L shl 20

    internal fun hashOf(files: Map<String, ByteArray>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for ((rel, bytes) in files.toSortedMap()) {
            md.update(rel.toByteArray(Charsets.UTF_8)); md.update(0)
            md.update(bytes.size.toString().toByteArray()); md.update(0)
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
