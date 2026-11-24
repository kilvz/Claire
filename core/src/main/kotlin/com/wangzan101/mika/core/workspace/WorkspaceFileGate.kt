package com.wangzan101.mika.core.workspace

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.UUID

/**
 * Every host-side file that enters or leaves a sandbox directory (workspace, skills directory, root filesystem) passes through here.
 *
 * Commands inside the sandbox run as the same user as Claire and can swap files or directories for symbolic links, hard links or FIFOs at any time, so "check by path, then use by path"
 * cannot stop them. Here every segment is opened relative to the already-open handle of the previous segment, without following links; the final file is opened only once and verified on the handle
 * (its real location is still inside the root directory, it is a regular file, and it has only one hard link), and all subsequent reads and writes go through that same handle. Deletion is verified the same way first.
 *
 * [rel] is a relative path below the root directory (separated by `/`); `..` and null bytes always count as out of bounds. All errors throw [SandboxError], whose messages never contain host paths.
 */
class WorkspaceFileGate(private val sys: FdSys) {

    /** Read: a regular file with exactly one hard link; when [under] is given, the file must sit directly inside `<root>/<under>/`. */
    fun openRead(root: File, rel: String, under: String? = null): GatedFile = open(root, rel, under, OpenHow.READ, createDirs = false)

    /** Create new (an existing name reports file_exists; never overwrites); missing directories are created segment by segment. */
    fun createNew(root: File, rel: String, under: String? = null): GatedFile = open(root, rel, under, OpenHow.CREATE_NEW, createDirs = true)

    /** Write: create if absent; an existing file is truncated only after verification (appends when [append]), so truncation cannot reach another file that was linked in. */
    fun openWrite(root: File, rel: String, append: Boolean): GatedFile {
        val g = open(root, rel, null, if (append) OpenHow.APPEND else OpenHow.WRITE, createDirs = true)
        if (!append) try { g.truncate() } catch (e: IOException) { g.close(); throw e }
        return g
    }

    /** Creates [name] inside `<root>/<dir>/`; on a name clash it tries "name (2).ext" and so on, at most [max] times. Returns the actual name and the handle. */
    fun createNumbered(root: File, dir: String, name: String, max: Int = 999): Pair<String, GatedFile> {
        for (n in 1..max) {
            val candidate = numbered(name, n)
            try {
                return candidate to createNew(root, "$dir/$candidate", under = dir)
            } catch (e: SandboxError) {
                if (e.code != FILE_EXISTS) throw e
            }
        }
        throw SandboxError(FILE_EXISTS, "Too many files with this name.", recoverable = true)
    }

    /** Deletes only after verifying it is a regular file. */
    fun delete(root: File, rel: String, under: String? = null) {
        open(root, rel, under, OpenHow.READ, createDirs = false).use { it.delete() }
    }

    /** Creates the directory (segment by segment, without following links). */
    fun mkdirs(root: File, rel: String) {
        anchored(root, segments(rel), create = true) { _, _ -> }
    }

    /** Names inside the directory; empty when the directory does not exist. */
    fun list(root: File, rel: String): List<String> = try {
        anchored(root, segments(rel), create = false) { _, d -> sys.list(d) }
    } catch (e: SandboxError) {
        if (e.code == FILE_NOT_FOUND) emptyList() else throw e
    }

    /**
     * Replaces [rel] entirely: write a fresh copy in the same directory, then rename it over. If the old entry was a link, FIFO or hard link it is merely replaced; nothing is written into what it pointed at.
     */
    fun replace(root: File, rel: String, bytes: ByteArray, mode: Int = 420 /* 0644 */) {
        val segs = segments(rel)
        if (segs.isEmpty()) throw outside()
        anchored(root, segs.dropLast(1), create = true) { rootReal, dir ->
            val tmp = ".mika-new-" + UUID.randomUUID()
            val f = file(dir, tmp, OpenHow.CREATE_NEW)
            try {
                f.use {
                    verify(it, rootReal, dir, tmp)
                    val buf = ByteBuffer.wrap(bytes)
                    while (buf.hasRemaining()) it.channel().write(buf)
                    sys.chmod(it, mode)
                }
                sys.rename(dir, tmp, segs.last())
            } catch (e: Exception) {
                runCatching { sys.remove(dir, tmp) }
                throw e
            }
        }
    }

    /** Makes [rel] a symbolic link pointing at [target] (left alone if it already is); any previous entry is replaced entirely. */
    fun symlink(root: File, rel: String, target: String) {
        val segs = segments(rel)
        if (segs.isEmpty()) throw outside()
        anchored(root, segs.dropLast(1), create = true) { _, dir ->
            if (sys.readlink(dir, segs.last()) == target) return@anchored
            val tmp = ".mika-link-" + UUID.randomUUID()
            sys.symlink(dir, tmp, target)
            try { sys.rename(dir, tmp, segs.last()) } catch (e: Exception) { runCatching { sys.remove(dir, tmp) }; throw e }
        }
    }

    /**
     * Deletes the whole tree at `<root>/<rel>`: each level is opened relative to the previous level's handle without following links, and a link is deleted as the link itself only.
     * Directories swapped for links during the deletion are not descended into. Read-only directories are made writable first.
     */
    fun deleteTree(root: File, rel: String) {
        val segs = segments(rel)
        if (segs.isEmpty()) throw outside()
        try {
            anchored(root, segs.dropLast(1), create = false) { rootReal, dir -> removeEntry(dir, segs.last(), rootReal, 0) }
        } catch (e: SandboxError) {
            if (e.code != FILE_NOT_FOUND) throw e
        }
    }

    private fun removeEntry(dir: OpenFd, name: String, rootReal: String, depth: Int) {
        val st = sys.lstat(dir, name) ?: return
        if (st.kind == FdStat.Kind.DIR) {
            if (depth > MAX_DEPTH) throw IOException("directory tree too deep to delete")
            // Make the directory writable by name before opening it (without opening it or following links): a read-only directory can then still be opened and deleted instead of stalling on EACCES (#629).
            runCatching { sys.chmodAt(dir, name, 448 /* 0700 */) }
            val sub = try { sys.openDir(dir, name, create = false) } catch (e: FdSysException) { null }
            if (sub != null) sub.use {
                // If it was moved outside the root directory during deletion, do not delete it along with the rest
                if (!inside(it.realPath(), rootReal)) return
                runCatching { sys.chmod(it, 448 /* 0700 */) }
                for (child in sys.list(it)) removeEntry(it, child, rootReal, depth + 1)
            }
        }
        try { sys.remove(dir, name) } catch (e: FdSysException) { if (e.kind != FdSysException.Kind.NOT_FOUND) throw e }
    }

    private fun open(root: File, rel: String, under: String?, how: OpenHow, createDirs: Boolean): GatedFile {
        val segs = segments(rel)
        if (segs.isEmpty()) throw notAFile()
        if (under != null && segs.dropLast(1) != segments(under)) throw outside()
        val rootFd = openRoot(root)
        val rootReal: String
        val dir: OpenFd
        try {
            rootReal = rootFd.realPath()
            dir = walk(rootFd, segs.dropLast(1), createDirs)
        } catch (e: Exception) {
            rootFd.close()
            throw e
        }
        // Afterwards dir is closed by GatedFile; when dir is not the root directory itself, the root handle is closed here
        if (dir !== rootFd) rootFd.close()
        try {
            if (under != null && dir.realPath() != rootReal.trimEnd('/') + "/" + segments(under).joinToString("/")) throw outside()
            val f = file(dir, segs.last(), how)
            try {
                return GatedFile(sys, dir, f, segs.last(), verify(f, rootReal, dir, segs.last()))
            } catch (e: Exception) {
                f.close()
                throw e
            }
        } catch (e: Exception) {
            dir.close()
            throw e
        }
    }

    /** Root handle → opened segment by segment down to the directory [segs] points at; intermediate handles are closed as soon as they are done with. */
    private fun walk(rootFd: OpenFd, segs: List<String>, create: Boolean): OpenFd {
        var cur = rootFd
        for (s in segs) {
            val next = try { sys.openDir(cur, s, create) } catch (e: FdSysException) {
                if (cur !== rootFd) cur.close()
                throw map(e, dirStep = true)
            }
            if (cur !== rootFd) cur.close()
            cur = next
        }
        return cur
    }

    private fun <T> anchored(root: File, segs: List<String>, create: Boolean, block: (String, OpenFd) -> T): T {
        val rootFd = openRoot(root)
        try {
            val rootReal = rootFd.realPath()
            val dir = walk(rootFd, segs, create)
            try {
                if (!inside(dir.realPath(), rootReal) && dir !== rootFd) throw outside()
                return block(rootReal, dir)
            } finally {
                if (dir !== rootFd) dir.close()
            }
        } finally {
            rootFd.close()
        }
    }

    private fun openRoot(root: File): OpenFd = try { sys.openRoot(root) } catch (e: FdSysException) { throw map(e, dirStep = true) }

    private fun file(dir: OpenFd, name: String, how: OpenHow): OpenFd =
        try { sys.openFile(dir, name, how) } catch (e: FdSysException) { throw map(e, dirStep = false) }

    /** Verify on the handle: the real location is exactly that name in the directory it was opened through, inside the root directory; it is a regular file; it has only one hard link. */
    private fun verify(f: OpenFd, rootReal: String, dir: OpenFd, name: String): FdStat {
        val real = f.realPath()
        if (!inside(real, rootReal) || real != dir.realPath().trimEnd('/') + "/" + name) throw outside()
        val st = f.stat()
        if (st.kind != FdStat.Kind.FILE) throw notAFile()
        if (st.nlink != 1L) throw SandboxError(LINKED_FILE, "The file has more than one hard link, so Claire does not use it; copy it (cp) and use the copy.", recoverable = true)
        return st
    }

    private fun map(e: FdSysException, dirStep: Boolean): SandboxError = when (e.kind) {
        FdSysException.Kind.NOT_FOUND -> SandboxError(FILE_NOT_FOUND, "The file does not exist.", recoverable = true)
        FdSysException.Kind.EXISTS -> SandboxError(FILE_EXISTS, "A file with this name already exists.", recoverable = true)
        FdSysException.Kind.NOT_DIR -> SandboxError(FILE_NOT_FOUND, "A folder on this path is not a folder.", recoverable = true)
        FdSysException.Kind.LINK ->
            if (dirStep) SandboxError(NOT_A_FILE, "A folder on this path is a link; Claire's file tools do not follow links. Use the real path.", recoverable = true)
            else notAFile()
        FdSysException.Kind.NOT_FILE -> notAFile()
    }

    private fun outside() = SandboxError(OUTSIDE, "The file is outside the allowed folder.", recoverable = true)
    private fun notAFile() = SandboxError(NOT_A_FILE, "Not a regular file (a directory, link or special file).", recoverable = true)

    companion object {
        const val FILE_NOT_FOUND = "file_not_found"
        const val FILE_EXISTS = "file_exists"
        const val NOT_A_FILE = "not_a_file"
        const val LINKED_FILE = "linked_file"
        const val OUTSIDE = "path_outside_workspace"
        private const val MAX_DEPTH = 256

        /** The segments below the root directory; `..` and null bytes are out of bounds. */
        fun segments(rel: String): List<String> {
            if ('\u0000' in rel) throw SandboxError(OUTSIDE, "The path is not allowed.", recoverable = true)
            return rel.split('/').filter { it.isNotEmpty() && it != "." }.onEach {
                if (it == "..") throw SandboxError(OUTSIDE, "The path is not allowed.", recoverable = true)
            }
        }

        /** "a.csv" → "a (2).csv"; a name without an extension simply gets the counter appended. */
        fun numbered(name: String, n: Int): String {
            if (n <= 1) return name
            val dot = name.lastIndexOf('.')
            return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
        }

        internal fun inside(path: String, root: String): Boolean = path == root || path.startsWith(root.trimEnd('/') + "/")
    }
}

/** A file verified by [WorkspaceFileGate]; reads and writes all go through this handle. [stat] is the one taken at verification. */
class GatedFile internal constructor(
    private val sys: FdSys,
    private val dir: OpenFd,
    private val fd: OpenFd,
    val name: String,
    val stat: FdStat,
) : Closeable {
    val size: Long get() = stat.size

    fun channel() = fd.channel()

    /** Reads from the beginning (or from the current position); closing the stream does not close the handle. */
    fun input(): InputStream = object : java.io.FilterInputStream(Channels.newInputStream(fd.channel())) { override fun close() = Unit }

    fun output(): OutputStream = object : java.io.FilterOutputStream(Channels.newOutputStream(fd.channel())) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    /** Reads at most [len] bytes starting at [offset], stopping at the end of the file. */
    fun read(offset: Long, len: Int): ByteArray {
        val buf = ByteBuffer.allocate(len)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = fd.channel().read(buf, pos)
            if (n < 0) break
            pos += n
        }
        return buf.array().copyOf(buf.position())
    }

    fun write(bytes: ByteArray) {
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) fd.channel().write(buf)
    }

    fun currentSize(): Long = fd.channel().size()

    internal fun truncate() = fd.truncate(0)

    /** Deletes this file: only if this name in the directory still refers to the same file (device + inode); if it has been swapped for something else, nothing is done. */
    fun delete() {
        val now = sys.lstat(dir, name) ?: return
        if (now.dev != stat.dev || now.ino != stat.ino || now.kind != FdStat.Kind.FILE) return
        try { sys.remove(dir, name) } catch (e: FdSysException) { if (e.kind != FdSysException.Kind.NOT_FOUND) throw e }
    }

    override fun close() {
        runCatching { fd.close() }
        runCatching { dir.close() }
    }
}
