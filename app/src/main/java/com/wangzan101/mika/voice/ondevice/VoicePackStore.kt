package com.wangzan101.mika.voice.ondevice

import android.os.StatFs
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Local state of one voice pack, as shown on its settings card. */
enum class VoicePackStatus {
    NotInstalled,
    Installed,

    /** The current version directory exists but files are missing or have the wrong size; download again. */
    Corrupt,

    /** Only an older version is installed: it still works ([VoicePackStore.installedAny]), the card shows a newer version is available, and the user decides whether to update. */
    UpdateAvailable,
}

/**
 * Voice-pack storage on the device: `<root>/<packId>/<version>/<fileName>`, where root is `filesDir/voice-models` (backups exclude this directory).
 * Downloads and imports first write into `<root>/.staging/<packId>-<version>/`; once everything passes, [commit] renames it into place and deletes old versions.
 * A failure in the middle drops only the staging copy; installed versions are untouched.
 *
 * Loading only checks file sizes and never re-hashes (hundreds of MB is too slow); hashes are verified when a download or import finishes.
 * [freeSpace] is replaceable for tests; by default [StatFs] reports free space on the partition that holds root.
 *
 * Concurrency: [stagingDir], [commit], [abandon], [delete] and [cleanup] are mutually exclusive within one process (a process-level lock, however many instances exist),
 * and all are blocking file operations to call on a background thread. A directory returned by [stagingDir] counts as in use until [commit] or [abandon];
 * [cleanup] never touches staging that is in use. The registry is cleared when the process dies, so the next launch's [cleanup] removes what was left behind.
 * Downloads and imports (Task 7) must therefore create staging, commit or abandon through this class, never by making directories under `.staging` themselves.
 *
 * Deletion never follows symbolic links: the link itself is removed and its target is left alone; anything outside root is never deleted.
 */
class VoicePackStore(
    private val root: File,
    private val freeSpace: (File) -> Long = { StatFs(it.path).availableBytes },
) {
    private val staging get() = File(root, STAGING)

    /** The current version when it is complete (every file present and matching the manifest size), otherwise null. */
    fun installed(entry: VoicePackEntry): InstalledVoicePack? {
        val dir = versionDir(entry.id, entry.version)
        return if (complete(entry, dir)) InstalledVoicePack(entry, dir) else null
    }

    /**
     * The copy that can be used: the current version when complete, otherwise the newest installed older version.
     * Older versions have no size list, so only the presence of files (by their current manifest names) is checked.
     */
    fun installedAny(entry: VoicePackEntry): InstalledVoicePack? =
        installed(entry) ?: olderVersions(entry).firstOrNull { (_, dir) -> filesPresent(entry, dir) }
            ?.let { (_, dir) -> InstalledVoicePack(entry, dir) }

    fun status(entry: VoicePackEntry): VoicePackStatus = when {
        installed(entry) != null -> VoicePackStatus.Installed
        versionDir(entry.id, entry.version).exists() -> VoicePackStatus.Corrupt
        installedAny(entry) != null -> VoicePackStatus.UpdateAvailable
        else -> VoicePackStatus.NotInstalled
    }

    /** The staging directory for this pack and version: always a fresh empty directory (leftovers from an unfinished download are removed) and registered as in use. */
    fun stagingDir(entry: VoicePackEntry): File = synchronized(lock) {
        val dir = stagingFor(entry)
        deleteInside(dir)
        if (!dir.mkdirs()) throw IOException("cannot create $dir")
        active += dir.canonicalPath
        dir
    }

    /** The path [stagingDir] will return; computed only, without creating or registering anything. Used when a download must build each file's target path up front. */
    fun stagingPath(entry: VoicePackEntry): File = stagingFor(entry)

    /** A failed or cancelled download/import: delete this pack/version staging and release the in-use registration. */
    fun abandon(entry: VoicePackEntry) = synchronized(lock) {
        val dir = stagingFor(entry)
        active -= dir.canonicalPath
        deleteInside(dir)
    }

    /**
     * Renames the finished [stagingDir] to `<id>/<version>/` to make it live, then deletes the pack's other versions.
     * Throws [IOException] when staging is incomplete or a size does not match, changing nothing; a rename failure also throws [IOException] and keeps the previous version.
     *
     * When the same version is downloaded again, the installed copy is moved aside to `<id>/<version>.previous` and deleted after the new rename succeeds.
     * It is moved inside the pack directory rather than staging so that whichever step is killed, the next [cleanup] can recover a usable copy
     * (if the new one never arrived, the old one is moved back).
     */
    fun commit(entry: VoicePackEntry, stagingDir: File): InstalledVoicePack = synchronized(lock) {
        if (!complete(entry, stagingDir)) throw IOException("voice pack ${entry.id} staging is incomplete")
        val target = versionDir(entry.id, entry.version)
        target.parentFile?.mkdirs()
        val previous = asideFor(target)
        deleteInside(previous)
        if (target.exists() && !target.renameTo(previous)) throw IOException("cannot move aside $target")
        if (!stagingDir.renameTo(target)) {
            if (previous.exists()) previous.renameTo(target)
            throw IOException("cannot move $stagingDir to $target")
        }
        active -= stagingDir.canonicalPath
        target.parentFile?.listFiles()?.filter { it != target }?.forEach { deleteInside(it) }
        InstalledVoicePack(entry, target)
    }

    /** Deletes every version of this pack. */
    fun delete(id: String) = synchronized(lock) {
        deleteInside(File(root, safeId(id)))
    }

    /**
     * Called once at app start (on a background thread): removes unused staging leftovers, packs no longer in the manifest,
     * and every version of each pack except "the copy that can be used".
     * First it moves any `<version>.previous` left by a killed [commit] back into place (unused when the new copy is already there).
     * When no copy is usable it keeps the current version directory (shown as "damaged" with a prompt to download again) and deletes the rest.
     */
    fun cleanup(catalog: VoicePackCatalog) = synchronized(lock) {
        if (!root.isDirectory) return@synchronized
        staging.listFiles()?.filter { it.canonicalPath !in active }?.forEach { deleteInside(it) }
        if (staging.list()?.isEmpty() == true) staging.delete()
        root.listFiles()?.forEach { idDir ->
            if (idDir.name == STAGING) return@forEach
            val entry = catalog.entry(idDir.name)
            if (entry == null || isLink(idDir) || !idDir.isDirectory) {
                deleteInside(idDir)
                return@forEach
            }
            restoreAside(idDir)
            val keep = installedAny(entry)?.dir ?: versionDir(entry.id, entry.version)
            idDir.listFiles()?.filter { it != keep }?.forEach { deleteInside(it) }
        }
    }

    /** Free bytes on the partition that holds root; a download or import requires at least the pack size plus [SPACE_MARGIN]. */
    fun freeBytes(): Long {
        root.mkdirs()
        return freeSpace(root)
    }

    private fun stagingFor(entry: VoicePackEntry) = File(staging, "${safeId(entry.id)}-${entry.version}")

    private fun versionDir(id: String, version: Int) = File(File(root, safeId(id)), version.toString())

    private fun asideFor(target: File) = File(target.parentFile, target.name + ASIDE)

    /** The original place of `<version>.previous` is gone (killed after commit moved the old copy aside): move it back. */
    private fun restoreAside(idDir: File) {
        idDir.listFiles()?.filter { it.name.endsWith(ASIDE) && !isLink(it) }?.forEach { aside ->
            val target = File(idDir, aside.name.removeSuffix(ASIDE))
            if (!target.exists() && !isLink(target)) aside.renameTo(target)
        }
    }

    /** Version directories older than the current one, newest first. Names that are not pure digits, such as `.previous`, do not count. */
    private fun olderVersions(entry: VoicePackEntry): List<Pair<Int, File>> =
        File(root, safeId(entry.id)).listFiles().orEmpty()
            .mapNotNull { dir -> dir.name.toIntOrNull()?.takeIf { it < entry.version && dir.isDirectory }?.let { it to dir } }
            .sortedByDescending { it.first }

    private fun complete(entry: VoicePackEntry, dir: File): Boolean =
        dir.isDirectory && entry.files.all { f -> File(dir, f.name).let { it.isFile && it.length() == f.size } }

    private fun filesPresent(entry: VoicePackEntry, dir: File): Boolean =
        dir.isDirectory && entry.files.all { File(dir, it.name).isFile }

    private fun isLink(f: File) = Files.isSymbolicLink(f.toPath())

    /**
     * Deletes [f]: for a symbolic link only the link itself; anything outside root is left alone; directories are removed level by level without following links
     * (`File.deleteRecursively` would follow a link to a directory and delete its contents).
     */
    private fun deleteInside(f: File) {
        val path = f.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
            return
        }
        val base = root.canonicalPath + File.separator
        if (!f.canonicalPath.startsWith(base)) return
        // walkFileTree does not follow links by default: a link is passed to visitFile as an ordinary entry and only the link is deleted
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** A pack id used as a directory name: same rules as [VoicePackCatalog.isSafeName], and it must not collide with the staging directory. */
    private fun safeId(id: String): String {
        require(VoicePackCatalog.isSafeName(id) && id != STAGING) { "invalid voice pack id '$id'" }
        return id
    }

    companion object {
        /** Extra free space required beyond the pack size. */
        const val SPACE_MARGIN = 50L * 1024 * 1024
        private const val STAGING = ".staging"
        private const val ASIDE = ".previous"

        /** Process-wide: instances created by startup cleanup and by downloads are mutually exclusive too. */
        private val lock = Any()

        /** Staging directories in use (canonical paths); [stagingDir] registers and [commit]/[abandon] release them. Read and written only under [lock]. */
        private val active = HashSet<String>()
    }
}
