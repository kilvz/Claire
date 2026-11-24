package com.wangzan101.mika.plugin

import com.wangzan101.mika.core.learn.knowledge.FrontMatter
import com.wangzan101.mika.core.learn.knowledge.GuideInfo
import com.wangzan101.mika.core.learn.skills.PLUGIN_KIND
import com.wangzan101.mika.core.learn.skills.PluginSkills
import java.io.File

/**
 * Reads the skills of enabled plugins from `files/plugins/<id>/skills/<name>/SKILL.md`.
 * A skill's name is `<pluginId>/<name>`; broken skills are skipped so the whole plugin does not disappear.
 * [plugins] is fetched fresh on each call, so a disabled plugin is invisible to the next lookup; at the start of a task
 * use [snapshot] to pin one.
 */
class PluginSkillsProvider(
    private val plugins: () -> List<PluginRecord>,
    private val root: File,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) : PluginSkills {

    private class SkillRef(val pluginId: String, val info: GuideInfo, val markdown: File)

    override fun entries(): List<GuideInfo> = load().map { it.info }

    override fun read(info: GuideInfo, file: String): String? =
        load().firstOrNull { it.info.name == info.name }?.let { readFile(it, file) }

    /** Fixed snapshot taken at the start of a task: enabling/disabling during this task takes effect from the next task. */
    fun snapshot(): PluginSkills {
        val refs = load()
        return object : PluginSkills {
            override fun entries(): List<GuideInfo> = refs.map { it.info }
            override fun read(info: GuideInfo, file: String): String? =
                refs.firstOrNull { it.info.name == info.name }?.let { readFile(it, file) }
        }
    }

    private fun load(): List<SkillRef> {
        val out = mutableListOf<SkillRef>()
        for (record in plugins().filter { it.enabled }) {
            val dir = File(root, record.id)
            val manifest = runCatching { PluginManifest.parse(File(dir, "manifest.json").readText()) }
                .onFailure { log("could not read manifest of ${record.id}", it) }
                .getOrNull() ?: continue
            for (skill in manifest.skills) {
                runCatching { ref(record, dir, skill) }
                    .onFailure { log("could not load skill ${record.id}/${skill.name}", it) }
                    .getOrNull()?.let { out += it }
            }
        }
        return out
    }

    private fun ref(record: PluginRecord, pluginDir: File, skill: PluginSkillDecl): SkillRef? {
        if (!PluginManifest.SKILL_NAME.matches(skill.name)) return null
        if (skill.path != "skills/${skill.name}" || skill.path.contains("..")) return null
        val base = pluginDir.canonicalFile
        val dir = File(base, skill.path).canonicalFile
        if (!dir.path.startsWith(base.path + File.separator)) return null
        val markdown = File(dir, SKILL_FILE)
        if (!markdown.isFile) return null
        // Prevent symlinks from pointing SKILL.md outside the plugin directory
        if (!markdown.canonicalFile.path.startsWith(dir.path + File.separator)) return null
        val (meta, body) = FrontMatter.parse(markdown.readText())
        if (body.isBlank()) return null
        fun one(key: String) = (meta[key] as? String)?.takeIf { it.isNotEmpty() }
        fun many(key: String) = (meta[key] as? List<*>)?.filterIsInstance<String>() ?: listOfNotNull(one(key))
        val apps = many("apps")
        val domains = many("domains")
        return SkillRef(
            pluginId = record.id,
            info = GuideInfo(
                kind = PLUGIN_KIND,
                name = "${record.id}/${skill.name}",
                display = skill.display?.takeIf { it.isNotEmpty() } ?: one("display") ?: skill.name,
                description = one("description").orEmpty(),
                aliases = many("aliases"),
                terms = apps + domains,
                files = listOf(SKILL_FILE),
                apps = apps,
                sites = many("sites") + domains,
            ),
            markdown = markdown,
        )
    }

    private fun readFile(ref: SkillRef, file: String): String? {
        if (file != SKILL_FILE) return null
        return runCatching {
            FrontMatter.parse(ref.markdown.readText()).second.takeIf { it.isNotBlank() }
        }.onFailure { log("could not read ${ref.info.name}", it) }.getOrNull()
    }

    private companion object {
        const val SKILL_FILE = "SKILL.md"
    }
}
