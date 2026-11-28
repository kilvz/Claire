package com.wangzan101.mika.plugin

import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.tool.PluginToolSpec
import com.wangzan101.mika.core.learn.skills.PluginSkills
import com.wangzan101.mika.core.tool.Risk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * The single entry point for installed plugins and their enabled state. See `PluginInstaller` for the disk layout:
 * `files/plugins/<id>/` is the package content, and `files/plugins/registry.json` stores only install info and the
 * enabled/disabled switches.
 * At startup it reconciles against the manifests in the directories, and records whose directory has disappeared are
 * dropped automatically.
 */
class PluginRegistry(
    private val filesDir: File,
    private val scope: CoroutineScope,
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val root = File(filesDir, "plugins")
    private val file = File(root, REGISTRY)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val lock = Any()
    private val _plugins = MutableStateFlow<List<PluginRecord>>(emptyList())

    val plugins: StateFlow<List<PluginRecord>> = _plugins.asStateFlow()

    private val skills = PluginSkillsProvider({ _plugins.value }, root, log)

    /** Read-only source for the skill catalog: reads the enabled state fresh on each lookup. */
    val skillsProvider: PluginSkills get() = skills

    init {
        reload()
    }

    /** Pinned at the start of a task; enabling/disabling during this task takes effect from the next task. */
    fun skillsSnapshot(): PluginSkills = skills.snapshot()

    fun enabledPlugins(): List<PluginRecord> = _plugins.value.filter { it.enabled }

    fun record(id: String): PluginRecord? = _plugins.value.firstOrNull { it.id == id }

    fun manifest(id: String): PluginManifest? {
        if (!PluginManifest.ID.matches(id)) return null
        return runCatching { PluginManifest.parse(File(File(root, id), PluginInstaller.MANIFEST).readText()) }
            .onFailure { log("could not read manifest of $id", it) }
            .getOrNull()
    }

    /** Tool specs of enabled plugins (name assembled as `plugin_<id with dots removed>_<tool name>`); the script directory is the plugin directory. */
    fun toolSpecs(): List<PluginToolSpec> = enabledPlugins().flatMap { record ->
        val m = manifest(record.id) ?: return@flatMap emptyList<PluginToolSpec>()
        val dir = pluginDir(record.id) ?: return@flatMap emptyList<PluginToolSpec>()
        m.tools.map { tool ->
            PluginToolSpec(
                name = "plugin_${record.id.replace('.', '_')}_${tool.name}",
                description = tool.description,
                scriptPath = tool.script ?: "",
                scriptDir = dir,
                inputSchema = tool.inputSchema ?: JsonObject(emptyMap()),
                timeoutSec = tool.timeoutSec,
                risk = tool.risk.toRisk(),
            )
        }.distinctBy { it.name }
    }

    /** Rescans the directories and reconciles; called at startup and after installation. */
    fun reload() = synchronized(lock) {
        root.mkdirs()
        val stored = readRegistry()
        val known = stored.associateBy { it.id }
        val found = mutableListOf<PluginRecord>()
        val dirs = root.listFiles()
            ?.filter { it.isDirectory && it.name != STAGING && it.name != TRASH }
            .orEmpty()
            .sortedBy { it.name }
        for (dir in dirs) {
            val manifest = runCatching { PluginManifest.parse(File(dir, PluginInstaller.MANIFEST).readText()) }
                .onFailure { log("could not read ${dir.name}/manifest.json", it) }
                .getOrNull() ?: continue
            // The directory name must match the manifest id, to avoid reading another plugin's data
            if (manifest.id != dir.name) {
                log("ignoring ${dir.name}: manifest id is ${manifest.id}", null)
                continue
            }
            if (manifest.validate(appVersion).isNotEmpty()) continue
            val old = known[manifest.id]
            found += PluginRecord(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                enabled = old?.enabled ?: true,
                installedAt = old?.installedAt ?: dir.lastModified(),
                skillNames = manifest.skills.map { it.name },
                description = manifest.description,
                permissions = manifest.permissions,
            )
        }
        val next = found.sortedBy { it.name.lowercase() }
        _plugins.value = next
        if (next != stored) writeRegistry(next)
    }

    /** Registers after the installer swaps the directory in; an upgrade with the same id keeps the original enabled state and install time. */
    fun install(record: PluginRecord) = synchronized(lock) {
        val old = _plugins.value.firstOrNull { it.id == record.id }
        val merged = record.copy(
            enabled = old?.enabled ?: true,
            installedAt = old?.installedAt ?: record.installedAt,
        )
        val next = (_plugins.value.filter { it.id != merged.id } + merged).sortedBy { it.name.lowercase() }
        _plugins.value = next
        writeRegistry(next)
    }

    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        val next = _plugins.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        _plugins.value = next
        writeRegistry(next)
    }

    /** Deletes the plugin directory and registry entry; user conversations/memory are unaffected. */
    fun uninstall(id: String): Boolean = synchronized(lock) {
        if (!PluginManifest.ID.matches(id)) return@synchronized false
        val next = _plugins.value.filter { it.id != id }
        if (next.size == _plugins.value.size) return@synchronized false
        File(root, id).deleteRecursively()
        _plugins.value = next
        writeRegistry(next)
        true
    }

    fun pluginDir(id: String): File? =
        if (PluginManifest.ID.matches(id) && _plugins.value.any { it.id == id }) File(root, id) else null

    private fun readRegistry(): List<PluginRecord> {
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString<RegistryFile>(file.readText()).plugins }
            .onFailure { log("could not read $REGISTRY", it) }
            .getOrDefault(emptyList())
    }

    private fun writeRegistry(records: List<PluginRecord>) {
        root.mkdirs()
        val tmp = File(root, "$REGISTRY.tmp")
        tmp.writeText(json.encodeToString(RegistryFile(plugins = records)))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot write $REGISTRY" }
        }
    }

    @Serializable
    private data class RegistryFile(val schema: Int = 1, val plugins: List<PluginRecord> = emptyList())

    private companion object {
        const val REGISTRY = "registry.json"
        const val STAGING = ".staging"
        const val TRASH = ".trash"
    }
}

/** Maps a plugin tool's declared risk level (the string in the manifest) to core's [Risk]; unknown ones count as read-only. */
private fun String?.toRisk(): Risk = when (this?.lowercase()) {
    "write" -> Risk.Write
    "external" -> Risk.External
    else -> Risk.Read
}
