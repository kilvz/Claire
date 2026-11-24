package com.wangzan101.mika.core.workspace

import java.io.File
import java.nio.file.Files

/** The model only ever sees paths inside the sandbox; normalization and out-of-bounds checks happen here. */
object SandboxPaths {
    const val WORKSPACE = "/workspace"
    const val SKILLS = "/opt/mika/skills"
    /** MCP package cache shared by all workspaces and writable inside the sandbox (written only by commands through a bind mount; not shared through the normalize/isWritable bridge). */
    const val MCP_CACHE = "/opt/mika/mcp-cache"

    /** A single file name: no path separators, control characters, format characters (bidi overrides, zero width and the like, which can make the displayed name differ from the actual one) or line/paragraph separators. */
    fun plainName(n: String): Boolean =
        n.isNotEmpty() && '/' !in n && '\\' !in n && n != "." && n != ".." &&
            n.codePoints().noneMatch(::hiddenChar)

    /** Control characters, format characters (Cf) and U+2028/U+2029. */
    fun hiddenChar(cp: Int): Boolean =
        Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() || cp == 0x2028 || cp == 0x2029

    /** Relative paths are based on [cwd]; returns null when the normalized path is not under [WORKSPACE] or [SKILLS]. */
    fun normalize(path: String, cwd: String = WORKSPACE): String? {
        if (path.isEmpty() || '\u0000' in path) return null
        val parts = ArrayDeque<String>()
        for (seg in (if (path.startsWith("/")) path else "$cwd/$path").split('/')) when (seg) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull() ?: return null
            else -> parts.addLast(seg)
        }
        val n = "/" + parts.joinToString("/")
        return n.takeIf { under(it, WORKSPACE) || under(it, SKILLS) }
    }

    /** Only the workspace is writable; the skills directory is read-only. */
    fun isWritable(normalized: String): Boolean = under(normalized, WORKSPACE)

    /**
     * [sandboxRel] under [root]; symbolic links are checked segment by segment: links pointing outside [root] and links whose target does not exist (where writing would land is unknowable) all yield null,
     * and the final canonicalFile must still be under [root].
     */
    fun resolveHost(root: File, sandboxRel: String): File? {
        val base = root.canonicalFile
        var cur = base
        for (seg in sandboxRel.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") return null
            cur = File(cur, seg)
            val p = cur.toPath()
            if (Files.isSymbolicLink(p)) {
                if (!Files.exists(p)) return null
                cur = cur.canonicalFile
                if (!inside(cur, base)) return null
            }
        }
        return cur.canonicalFile.takeIf { inside(it, base) }
    }

    private fun under(p: String, root: String) = p == root || p.startsWith("$root/")
    private fun inside(f: File, base: File) = f == base || f.path.startsWith(base.path + File.separator)
}
