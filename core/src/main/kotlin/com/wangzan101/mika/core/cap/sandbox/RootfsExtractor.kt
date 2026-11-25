package com.wangzan101.mika.core.cap.sandbox

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

data class ExtractReport(
    val files: Int,
    val dirs: Int,
    val symlinks: Int,
    val hardlinksCopied: Int,
    /** At most [RootfsExtractor.MAX_SKIPPED_NAMES] names; see [skippedTotal] for the total. */
    val skipped: List<String>,
    val skippedTotal: Int = skipped.size,
)

class UnsafeArchiveException(msg: String) : Exception(msg)

/**
 * Streams a root filesystem tar.gz into dest.
 *
 * - Symlink targets are kept as they are (even absolute paths, which PRoot interprets relative to the root filesystem).
 * - Apps on Android cannot create hard links, so hard links are copied from the already-extracted target instead.
 * - Device files and FIFOs are skipped and recorded in skipped.
 * - Any entry (and any hard-link target) that, once normalized, is not inside dest, or that would traverse an already-extracted symlink, throws UnsafeArchiveException;
 *   the extracted content must then be deleted by the caller with [deleteTree] — File.deleteRecursively cannot be used, since it follows (absolute) symlinks and deletes outside dest.
 * - More entries than maxEntries, or more total bytes of regular files and hard-link copies than maxBytes, likewise throws UnsafeArchiveException.
 */
object RootfsExtractor {
    const val MAX_SKIPPED_NAMES = 1000

    fun extract(
        tarGz: InputStream,
        dest: File,
        maxBytes: Long = 1L shl 30,
        maxEntries: Int = 200_000,
    ): ExtractReport = extractTar(GzipCompressorInputStream.builder().setInputStream(BufferedInputStream(tarGz)).setDecompressConcatenated(true).get(), dest, maxBytes, maxEntries)

    /** Same as [extract], but the input is an already-decompressed tar stream (the caller handles xz and the like). */
    fun extractTar(
        input: InputStream,
        dest: File,
        maxBytes: Long = 1L shl 30,
        maxEntries: Int = 200_000,
    ): ExtractReport {
        val root = dest.toPath().toAbsolutePath().normalize()
        Files.createDirectories(root)
        var files = 0
        var dirs = 0
        var symlinks = 0
        var hardlinks = 0
        val skipped = mutableListOf<String>()
        var skippedTotal = 0
        var entries = 0
        var bytes = 0L
        fun skip(name: String) {
            skippedTotal++
            if (skipped.size < MAX_SKIPPED_NAMES) skipped += name
        }
        fun addBytes(n: Long, name: String) {
            bytes += n
            if (bytes > maxBytes) throw UnsafeArchiveException("archive exceeds $maxBytes bytes at: $name")
        }

        TarArchiveInputStream(BufferedInputStream(input)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (++entries > maxEntries) throw UnsafeArchiveException("archive has more than $maxEntries entries")
                val rel = relativeName(entry.name)
                if (rel.isEmpty()) continue // "./" is dest itself
                val target = resolveInside(root, rel, entry.name)
                when {
                    entry.isDirectory -> {
                        removeIfLinkOrFile(target, dirOk = true)
                        Files.createDirectories(target)
                        setMode(target, entry.mode, isDir = true)
                        dirs++
                    }
                    entry.isSymbolicLink -> {
                        prepareParent(target)
                        removeIfLinkOrFile(target, dirOk = false)
                        Files.createSymbolicLink(target, linkTarget(target, entry.linkName))
                        symlinks++
                    }
                    entry.isLink -> {
                        val linkRel = relativeName(entry.linkName)
                        val source = resolveInside(root, linkRel, entry.linkName)
                        if (linkRel.isEmpty() || !Files.exists(source, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                            skip(rel)
                            continue
                        }
                        val attrs = Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        if (attrs.isRegularFile) addBytes(attrs.size(), rel)
                        prepareParent(target)
                        removeIfLinkOrFile(target, dirOk = false)
                        // NOFOLLOW_LINKS: if the target is a symlink, copy the link itself rather than what it points to
                        Files.copy(source, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                        hardlinks++
                    }
                    // Placed before isFile: Commons Compress may also report isFile as true for device entries
                    entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> skip(rel)
                    entry.isFile -> {
                        // The library only reads as many bytes as the entry declares, so capping by the declared size is enough
                        addBytes(entry.size, rel)
                        prepareParent(target)
                        removeIfLinkOrFile(target, dirOk = false)
                        // CREATE_NEW: if a symlink appears along the path, fail rather than follow it and write
                        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { tar.copyTo(it) }
                        setMode(target, entry.mode, isDir = false)
                        files++
                    }
                    else -> skip(rel) // any other unrecognized type
                }
            }
        }
        return ExtractReport(files, dirs, symlinks, hardlinks, skipped, skippedTotal)
    }

    /**
     * Deletes the whole tree, never following symlinks (the link itself is deleted, what it points to is untouched); before deleting, every directory is made owner-readable/writable/traversable,
     * otherwise the contents of a read-only directory cannot be deleted. Does nothing when dest does not exist.
     */
    fun deleteTree(dest: File) {
        val root = dest.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        // No FOLLOW_LINKS passed: symlinks (including when root itself is a link) are handed to visitFile as ordinary files
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val perms = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS).toMutableSet()
                perms += listOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                Files.setPosixFilePermissions(dir, perms)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun linkTarget(at: Path, linkName: String): Path =
        try {
            at.fileSystem.getPath(linkName)
        } catch (e: InvalidPathException) {
            throw UnsafeArchiveException("invalid symlink target: ${e.message}")
        }

    /** Strips leading "./" and trailing "/"; absolute paths are rejected outright. */
    private fun relativeName(name: String): String {
        if (name.startsWith("/")) throw UnsafeArchiveException("absolute path in archive: $name")
        var n = name
        while (n.startsWith("./")) n = n.substring(2)
        if (n == ".") n = ""
        return n.trimEnd('/')
    }

    private fun resolveInside(root: Path, rel: String, original: String): Path {
        val p = try {
            root.resolve(rel).normalize()
        } catch (e: InvalidPathException) {
            throw UnsafeArchiveException("invalid path in archive: ${e.message}")
        }
        if (!p.startsWith(root)) throw UnsafeArchiveException("path escapes destination: $original")
        // If any intermediate component is an already-extracted symlink, writing there could land outside dest
        var cur = root
        for (part in root.relativize(p).toList().dropLast(1)) {
            cur = cur.resolve(part)
            if (Files.isSymbolicLink(cur)) throw UnsafeArchiveException("path goes through a symlink: $original")
        }
        return p
    }

    private fun prepareParent(target: Path) {
        target.parent?.let { Files.createDirectories(it) }
    }

    private fun removeIfLinkOrFile(target: Path, dirOk: Boolean) {
        if (Files.isSymbolicLink(target)) {
            Files.delete(target)
        } else if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!dirOk) Files.delete(target) // a non-empty directory throws IOException, left to the caller
        } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(target)
        }
    }

    /** Only the 0777 bits are taken; the owner can always read and write, and directories are always traversable, otherwise nothing could be written or deleted afterwards. */
    private fun setMode(target: Path, tarMode: Int, isDir: Boolean) {
        var m = (tarMode and 0x1ff) or 0x180
        if (isDir) m = m or 0x40
        Files.setPosixFilePermissions(target, toPerms(m))
    }

    private val permBits = listOf(
        0x100 to PosixFilePermission.OWNER_READ,
        0x80 to PosixFilePermission.OWNER_WRITE,
        0x40 to PosixFilePermission.OWNER_EXECUTE,
        0x20 to PosixFilePermission.GROUP_READ,
        0x10 to PosixFilePermission.GROUP_WRITE,
        0x8 to PosixFilePermission.GROUP_EXECUTE,
        0x4 to PosixFilePermission.OTHERS_READ,
        0x2 to PosixFilePermission.OTHERS_WRITE,
        0x1 to PosixFilePermission.OTHERS_EXECUTE,
    )

    private fun toPerms(mode: Int): Set<PosixFilePermission> =
        permBits.filter { (bit, _) -> mode and bit != 0 }.mapTo(mutableSetOf()) { it.second }
}
