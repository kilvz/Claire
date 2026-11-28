package com.wangzan101.mika.core.cap.sandbox

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxPaths

/** Rules for saving to "Downloads/Claire"; shared by the share_file tool and `mika share` inside the sandbox, both equally strict. */
object ShareRules {
    const val MAX_BYTES = 100L shl 20

    /**
     * [path] is a sandbox path (relative paths are based on /workspace) and must be inside the workspace, resolved through [SandboxPort.hostPath] (link escapes become null),
     * a regular file (symlinks are not followed), and at most 100 MB; [name] defaults to the file's own name and must be a single file name without control characters.
     * Returns the host file and the save name; throws [SandboxError] when the rules are not met.
     */
    fun check(sandbox: SandboxPort, workspace: String, path: String, name: String?): Pair<File, String> {
        val p = SandboxPaths.normalize(path)?.takeIf(SandboxPaths::isWritable)
            ?: throw SandboxError("path_outside_workspace", "Only files inside /workspace can be shared.", recoverable = true)
        val f = sandbox.hostPath(workspace, p, forWrite = true)
            ?: throw SandboxError("path_outside_workspace", "Only files inside /workspace can be shared; links that lead outside are refused.", recoverable = true)
        val given = name?.trim() ?: f.name
        if (!plainName(given)) throw SandboxError("invalid_arguments", "name: must be a plain file name without control characters", recoverable = true)
        val saveAs = fitName(given)
        val hp = f.toPath()
        if (!Files.exists(hp, LinkOption.NOFOLLOW_LINKS)) throw SandboxError("file_not_found", "$p does not exist.", recoverable = true)
        if (!Files.isRegularFile(hp, LinkOption.NOFOLLOW_LINKS)) throw SandboxError("not_a_file", "$p is not a regular file.", recoverable = true)
        val size = Files.size(hp)
        if (size > MAX_BYTES) throw SandboxError("file_too_large", "$p is ${size shr 20} MB; at most 100 MB can be shared.", recoverable = true)
        return f to saveAs
    }

    fun plainName(n: String): Boolean = SandboxPaths.plainName(n)

    /** File names are capped at 255 bytes (UTF-8), leaving room for the system's " (n)" suffix. */
    const val NAME_MAX_BYTES = 240

    /** Truncates by UTF-8 bytes (and optionally code point count), keeping the extension (dropped when the extension itself is too long), never splitting a character. */
    fun fitName(name: String, maxBytes: Int = NAME_MAX_BYTES, maxChars: Int = Int.MAX_VALUE): String {
        fun bytes(t: String) = t.toByteArray(Charsets.UTF_8).size
        fun chars(t: String) = t.codePointCount(0, t.length)
        if (bytes(name) <= maxBytes && chars(name) <= maxChars) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot) else ""
        val keepExt = ext.isNotEmpty() && bytes(ext) <= 32 && chars(ext) < maxChars
        val base = if (keepExt) name.substring(0, dot) else name
        val roomBytes = maxBytes - if (keepExt) bytes(ext) else 0
        val roomChars = if (maxChars == Int.MAX_VALUE) Int.MAX_VALUE else maxChars - if (keepExt) chars(ext) else 0
        val sb = StringBuilder()
        var used = 0
        var count = 0
        var i = 0
        while (i < base.length && count < roomChars) {
            val cp = base.codePointAt(i)
            val n = bytes(String(Character.toChars(cp)))
            if (used + n > roomBytes) break
            sb.appendCodePoint(cp)
            used += n
            count++
            i += Character.charCount(cp)
        }
        return sb.toString() + if (keepExt) ext else ""
    }

    fun hiddenChar(cp: Int): Boolean = SandboxPaths.hiddenChar(cp)
}
