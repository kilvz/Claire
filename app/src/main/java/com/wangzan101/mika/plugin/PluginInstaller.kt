package com.wangzan101.mika.plugin

import android.content.ContentResolver
import android.net.Uri
import com.wangzan101.mika.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipFile

/** Code for the reason an installation failed; the UI uses it to show the corresponding text. */
class PluginInstallException(
    val code: String,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: code, cause)

object PluginErrors {
    const val INVALID_PACKAGE = "invalid_package"
    const val UNSAFE_PATH = "unsafe_path"
    const val TOO_LARGE = "too_large"
    const val MISSING_MANIFEST = "missing_manifest"
    const val INVALID_MANIFEST = "invalid_manifest"
    const val IO = "io"
}

/**
 * Safely installs a plugin zip into `files/plugins/<id>/`:
 * guards against zip-slip/absolute paths/backslashes/excessive extraction, first extracts into `.staging`, and swaps it
 * in atomically after validation;
 * when upgrading the same id, the old directory is moved to `.trash` first so the swap can be rolled back on failure.
 */
class PluginInstaller(
    private val filesDir: File,
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val cacheDir: File = File(filesDir.parentFile ?: filesDir, "cache"),
) {
    private val pluginsDir: File get() = File(filesDir, "plugins")

    /** A zip picked via SAF: copy it to the cache first, then go through [installZip]. Copying/parsing happen on the caller's IO thread. */
    fun install(uri: Uri, resolver: ContentResolver): Result<PluginRecord> = runCatching {
        val dir = File(cacheDir, "plugin-install").apply { mkdirs() }
        val temp = File(dir, "${UUID.randomUUID()}.zip")
        try {
            val input = resolver.openInputStream(uri)
                ?: throw PluginInstallException(PluginErrors.IO, "cannot open the selected file")
            input.use { from -> temp.outputStream().use { from.copyTo(it) } }
            if (temp.length() > maxBytes) throw PluginInstallException(PluginErrors.TOO_LARGE, "zip is too large")
            installZipOrThrow(temp)
        } finally {
            temp.delete()
        }
    }

    /** Entry point for tests and internal use: install a local zip file directly. */
    fun installZip(zip: File): Result<PluginRecord> = runCatching { installZipOrThrow(zip) }

    private fun installZipOrThrow(zip: File): PluginRecord {
        pluginsDir.mkdirs()
        val token = UUID.randomUUID().toString()
        val staging = File(File(pluginsDir, STAGING), token)
        val trash = File(File(pluginsDir, TRASH), token)
        var manifest: PluginManifest? = null
        try {
            ZipFile(zip).use { zf ->
                val entries = zf.entries().toList()
                if (entries.isEmpty()) throw PluginInstallException(PluginErrors.MISSING_MANIFEST, "zip is empty")
                if (entries.size > MAX_ENTRIES) throw PluginInstallException(PluginErrors.TOO_LARGE, "too many files")
                for (e in entries) checkName(e.name)
                val manifestEntry = entries.firstOrNull { it.name == MANIFEST }
                    ?: throw PluginInstallException(PluginErrors.MISSING_MANIFEST, "manifest.json missing at the root")
                val text = zf.getInputStream(manifestEntry).use { it.readLimited(MANIFEST_LIMIT) }
                val parsed = try {
                    PluginManifest.parse(text)
                } catch (e: Exception) {
                    throw PluginInstallException(PluginErrors.INVALID_MANIFEST, "manifest.json is not valid", e)
                }
                val problems = parsed.validate(appVersion)
                if (problems.isNotEmpty()) {
                    throw PluginInstallException(problems.first(), "manifest rejected: ${problems.joinToString()}")
                }
                manifest = parsed

                staging.mkdirs()
                val base = staging.canonicalPath + File.separator
                var total = 0L
                for (e in entries) {
                    val name = checkName(e.name)
                    if (e.isDirectory || e.name.endsWith("/")) {
                        File(staging, name).mkdirs()
                        continue
                    }
                    val out = File(staging, name).canonicalFile
                    if (!out.path.startsWith(base)) throw PluginInstallException(PluginErrors.UNSAFE_PATH, "entry escapes the package: ${e.name}")
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { from ->
                        out.outputStream().use { to ->
                            total += copyLimited(from, to, maxBytes - total)
                        }
                    }
                }
            }

            val m = manifest!!
            for (s in m.skills) {
                if (!File(staging, "${s.path}/SKILL.md").isFile) {
                    throw PluginInstallException(PluginErrors.INVALID_MANIFEST, "skill ${s.name} has no SKILL.md")
                }
            }

            val target = File(pluginsDir, m.id)
            val replaced = target.exists()
            if (replaced) {
                trash.parentFile?.mkdirs()
                if (!target.renameTo(trash)) throw PluginInstallException(PluginErrors.IO, "could not move the old version aside")
            }
            if (!staging.renameTo(target)) {
                if (replaced) trash.renameTo(target)
                throw PluginInstallException(PluginErrors.IO, "could not move the new version into place")
            }
            if (trash.exists()) trash.deleteRecursively()
            return PluginRecord(
                id = m.id,
                name = m.name,
                version = m.version,
                enabled = true,
                installedAt = System.currentTimeMillis(),
                skillNames = m.skills.map { it.name },
                description = m.description,
                permissions = m.permissions,
            )
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /** Rejects absolute paths, backslashes, drive letters and `.`/`..` segments; returns the relative path with the trailing `/` removed. */
    private fun checkName(raw: String): String {
        if (raw.isEmpty() || raw.startsWith("/") || raw.startsWith("\\")) {
            throw PluginInstallException(PluginErrors.UNSAFE_PATH, "absolute path: $raw")
        }
        if (raw.contains('\\') || raw.contains(':')) {
            throw PluginInstallException(PluginErrors.UNSAFE_PATH, "unsafe path: $raw")
        }
        val name = raw.removeSuffix("/")
        if (name.isEmpty()) throw PluginInstallException(PluginErrors.UNSAFE_PATH, "empty path")
        val parts = name.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw PluginInstallException(PluginErrors.UNSAFE_PATH, "path escape: $raw")
        }
        return name
    }

    private fun InputStream.readLimited(limit: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw PluginInstallException(PluginErrors.TOO_LARGE, "manifest is too large")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Reads up to [remaining] bytes; exceeding that throws [PluginErrors.TOO_LARGE]. Returns the number of bytes actually read. */
    private fun copyLimited(input: InputStream, output: java.io.OutputStream, remaining: Long): Long {
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > remaining) throw PluginInstallException(PluginErrors.TOO_LARGE, "package is too large")
            output.write(buf, 0, n)
        }
        return total
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 20L * 1024 * 1024
        const val MANIFEST_LIMIT = 256 * 1024
        const val MAX_ENTRIES = 2000
        const val MANIFEST = "manifest.json"
        private const val STAGING = ".staging"
        private const val TRASH = ".trash"
    }
}
