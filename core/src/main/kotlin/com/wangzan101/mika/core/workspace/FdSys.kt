package com.wangzan101.mika.core.workspace

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Type and identity of the file a handle points at (fstat/lstat). */
data class FdStat(val kind: Kind, val nlink: Long, val size: Long, val dev: Long, val ino: Long) {
    enum class Kind { FILE, DIR, LINK, OTHER }
}

/** A handle obtained from one open; which file it actually is follows from the handle itself only (on Android, read /proc/self/fd/N), without resolving the path again. */
interface OpenFd : Closeable {
    fun realPath(): String
    fun stat(): FdStat
    /** Channel on a file handle (read or write, depending on how it was opened); directories have none. */
    fun channel(): FileChannel
    fun truncate(size: Long)
}

enum class OpenHow { READ, CREATE_NEW, WRITE, APPEND }

/** A system-call error reported by [FdSys], turned into a tool-layer error code by [WorkspaceFileGate]. */
class FdSysException(val kind: Kind, cause: Throwable? = null) : IOException(kind.name, cause) {
    enum class Kind { NOT_FOUND, EXISTS, LINK, NOT_DIR, NOT_FILE }
}

/**
 * The system-call layer. Calls that take a dir are anchored on that already-open directory; [name] is a single-segment name and the last segment does not follow links.
 * The Android implementation lives in the app (open with O_NOFOLLOW + /proc/self/fd/N); [NioFdSys] is a path-based approximation on the JVM, for JVM tests only.
 */
interface FdSys {
    /** The root directory itself (a path supplied by the caller that the sandbox cannot reach); reports LINK when the last segment is a link. */
    fun openRoot(path: File): OpenFd
    fun openDir(dir: OpenFd, name: String, create: Boolean): OpenFd
    fun openFile(dir: OpenFd, name: String, how: OpenHow): OpenFd
    /** Does not follow links; null when it does not exist. */
    fun lstat(dir: OpenFd, name: String): FdStat?
    fun readlink(dir: OpenFd, name: String): String?
    fun list(dir: OpenFd): List<String>
    /** Removes this entry itself (file, link or empty directory); does not follow links. */
    fun remove(dir: OpenFd, name: String)
    /** Rename within the same directory; when [to] already exists (file or link) it is replaced entirely. */
    fun rename(dir: OpenFd, from: String, to: String)
    fun symlink(dir: OpenFd, name: String, target: String)
    fun chmod(fd: OpenFd, mode: Int)
    /** Changes the permissions of a child by name. Used before deleting a tree to make read-only directories writable first (called only for entries that lstat confirms are directories). */
    fun chmodAt(dir: OpenFd, name: String, mode: Int)
}

/**
 * Path-based approximation on the JVM: resolves by path, so there is a gap between the check and the use. Only for JVM unit tests (macOS has no /proc/self/fd);
 * on phones always use the Android implementation in the app.
 */
object NioFdSys : FdSys {
    private class Fd(val path: Path, private val ch: FileChannel?) : OpenFd {
        private val real = path.toRealPath().toString()
        override fun realPath() = real
        override fun stat(): FdStat = statOf(path) ?: throw FdSysException(FdSysException.Kind.NOT_FOUND)
        override fun channel(): FileChannel = ch ?: throw FdSysException(FdSysException.Kind.NOT_FILE)
        override fun truncate(size: Long) { channel().truncate(size) }
        override fun close() { ch?.close() }
    }

    private fun fd(dir: OpenFd) = dir as Fd

    private fun attrs(p: Path): BasicFileAttributes? =
        try { Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) } catch (e: IOException) { null }

    private fun statOf(p: Path): FdStat? {
        val a = try { Files.readAttributes(p, "unix:mode,nlink,size,dev,ino", LinkOption.NOFOLLOW_LINKS) } catch (e: IOException) { return null }
        val mode = (a["mode"] as Int) and 0xF000
        val kind = when (mode) { 0x8000 -> FdStat.Kind.FILE; 0x4000 -> FdStat.Kind.DIR; 0xA000 -> FdStat.Kind.LINK; else -> FdStat.Kind.OTHER }
        return FdStat(kind, (a["nlink"] as Number).toLong(), (a["size"] as Number).toLong(), (a["dev"] as Number).toLong(), (a["ino"] as Number).toLong())
    }

    private fun dirAt(p: Path): Fd {
        val a = attrs(p) ?: throw FdSysException(FdSysException.Kind.NOT_FOUND)
        if (a.isSymbolicLink) throw FdSysException(FdSysException.Kind.LINK)
        if (!a.isDirectory) throw FdSysException(FdSysException.Kind.NOT_DIR)
        return Fd(p, null)
    }

    override fun openRoot(path: File): OpenFd = dirAt(path.toPath().toAbsolutePath())

    override fun openDir(dir: OpenFd, name: String, create: Boolean): OpenFd {
        val p = fd(dir).path.resolve(name)
        if (create && attrs(p) == null) runCatching { Files.createDirectory(p) }
        return dirAt(p)
    }

    override fun openFile(dir: OpenFd, name: String, how: OpenHow): OpenFd {
        val p = fd(dir).path.resolve(name)
        val a = attrs(p)
        if (a?.isSymbolicLink == true) throw FdSysException(if (how == OpenHow.CREATE_NEW) FdSysException.Kind.EXISTS else FdSysException.Kind.LINK)
        // NIO cannot open with O_NONBLOCK: reject FIFOs and the like by type up front, so that reads and writes do not hang
        if (a != null && !a.isRegularFile) throw FdSysException(if (how == OpenHow.CREATE_NEW) FdSysException.Kind.EXISTS else FdSysException.Kind.NOT_FILE)
        val opts = when (how) {
            OpenHow.READ -> setOf(StandardOpenOption.READ)
            OpenHow.CREATE_NEW -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)
            OpenHow.WRITE -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            OpenHow.APPEND -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        val ch = try {
            FileChannel.open(p, opts + LinkOption.NOFOLLOW_LINKS)
        } catch (e: FileAlreadyExistsException) {
            throw FdSysException(FdSysException.Kind.EXISTS, e)
        } catch (e: NoSuchFileException) {
            throw FdSysException(FdSysException.Kind.NOT_FOUND, e)
        }
        return Fd(p, ch)
    }

    override fun lstat(dir: OpenFd, name: String): FdStat? = statOf(fd(dir).path.resolve(name))

    override fun readlink(dir: OpenFd, name: String): String? =
        runCatching { Files.readSymbolicLink(fd(dir).path.resolve(name)).toString() }.getOrNull()

    override fun list(dir: OpenFd): List<String> = Files.list(fd(dir).path).use { s -> s.map { it.fileName.toString() }.toList() }

    override fun remove(dir: OpenFd, name: String) {
        try { Files.delete(fd(dir).path.resolve(name)) } catch (e: NoSuchFileException) { throw FdSysException(FdSysException.Kind.NOT_FOUND, e) }
    }

    override fun rename(dir: OpenFd, from: String, to: String) {
        val d = fd(dir).path
        Files.move(d.resolve(from), d.resolve(to), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun symlink(dir: OpenFd, name: String, target: String) {
        Files.createSymbolicLink(fd(dir).path.resolve(name), java.nio.file.Paths.get(target))
    }

    override fun chmod(fd: OpenFd, mode: Int) {
        Files.setPosixFilePermissions(fd(fd).path, perms(mode))
    }

    override fun chmodAt(dir: OpenFd, name: String, mode: Int) {
        Files.setPosixFilePermissions(fd(dir).path.resolve(name), perms(mode))
    }

    private fun perms(mode: Int) = java.nio.file.attribute.PosixFilePermissions.fromString(
        (0..8).joinToString("") { i -> if (mode and (1 shl (8 - i)) != 0) "rwx"[i % 3].toString() else "-" },
    )
}
