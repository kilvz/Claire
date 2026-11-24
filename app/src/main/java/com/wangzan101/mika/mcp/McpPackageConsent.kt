package com.wangzan101.mika.mcp

import com.wangzan101.mika.core.cap.mcp.McpPackageSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Consent for stdio package downloads: an acceptance is bound to "package name + exact version + source" (data class equality, including the ecosystem), so a change to any one of them requires consent again.
 * The consent is a persistent user decision: when [file] is passed it is written to disk and stays valid after the app is reinstalled; when it is not, it is kept in the process only (for tests).
 */
class McpPackageConsent(private val file: File? = null) {
    private val accepted = mutableSetOf<McpPackageSpec>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        file?.let { load(it) }
    }

    fun accepted(spec: McpPackageSpec): Boolean = spec in accepted

    fun accept(spec: McpPackageSpec) {
        accepted += spec
        file?.let { save(it) }
    }

    fun clear() {
        accepted.clear()
        file?.let { save(it) }
    }

    private fun load(f: File) {
        if (!f.isFile) return
        runCatching { json.decodeFromString<List<McpPackageSpec>>(f.readText()) }.getOrNull()
            ?.let { accepted += it }
    }

    private fun save(f: File) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json.encodeToString(accepted.toList()))
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
