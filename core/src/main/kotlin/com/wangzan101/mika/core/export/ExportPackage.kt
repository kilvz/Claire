package com.wangzan101.mika.core.export

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The step between the staging directory and the final package: manifest, checksums, zip. */
object ExportPackage {
    const val MANIFEST = "manifest.json"

    /** Entries in the staging directory other than the manifest, sorted by path; links are not followed. */
    fun contentEntries(root: File): List<ManifestEntry> = files(root)
        .filter { it.relativeTo(root).invariantSeparatorsPath != MANIFEST }
        .map { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            ManifestEntry(ExportNames.entryPath(path), file.length(), sha256(file))
        }

    fun writeManifest(root: File, manifest: ExportManifest) {
        root.mkdirs()
        File(root, MANIFEST).writeText(ExportManifestJson.encode(manifest))
    }

    /** Zips the whole staging directory; the manifest must already be written. */
    fun zip(root: File, out: File) {
        out.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
            for (file in files(root)) {
                val path = ExportNames.entryPath(file.relativeTo(root).invariantSeparatorsPath)
                zip.putNextEntry(ZipEntry(path))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Single-file output: copies the one file in staging when it is the only entry besides the manifest. */
    fun copySingle(root: File, out: File): Boolean {
        val single = files(root).filter { it.relativeTo(root).invariantSeparatorsPath != MANIFEST }
        if (single.size != 1) return false
        out.parentFile?.mkdirs()
        single.single().copyTo(out, overwrite = true)
        return true
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Collects regular files only; symbolic links are not followed, and directories are not recursed into to loop back. */
    private fun files(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .onEnter { it == root || !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
    }
}
