package com.wangzan101.mika.knowledge

import com.wangzan101.mika.core.screen.map.ScreenGraph
import com.wangzan101.mika.core.screen.map.ScreenGraphCodec
import com.wangzan101.mika.core.screen.map.ScreenMapStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Screen map persistence: `<dir>/knowledge/apps/<app>/screens.json`. Atomic writes (temp file + ATOMIC_MOVE, so the old file stays intact until the new one is in place),
 * read failures/corruption return null instead of throwing (following [com.wangzan101.mika.knowledge.FileRouteStore]). One lock for the whole process.
 */
class FileScreenMapStore(dir: File) : ScreenMapStore {
    private val root = File(dir, "knowledge/apps")

    @Synchronized override fun load(app: String): ScreenGraph? {
        val f = fileOf(app)
        if (!f.isFile) return null
        return runCatching { ScreenGraphCodec.decode(f.readText()) }.getOrNull()
    }

    @Synchronized override fun save(app: String, graph: ScreenGraph) {
        val f = fileOf(app)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(ScreenGraphCodec.encode(graph))
        runCatching {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { tmp.delete() }
    }

    @Synchronized override fun apps(): List<String> =
        root.listFiles { it.isDirectory }?.map { it.name } ?: emptyList()

    private fun fileOf(app: String) = File(root, "${safe(app)}/screens.json")

    companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")
        private fun safe(s: String) = UNSAFE.replace(s, "_").ifEmpty { "_" }.let { if (it == "." || it == "..") "_" else it }
    }
}
