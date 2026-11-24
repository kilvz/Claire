package com.wangzan101.mika.core.learn.skills

import com.wangzan101.mika.core.learn.knowledge.GuideInfo

/** The kind of plugin skills; the name is `<plugin-id>/<skill-name>`, read-only, appearing or disappearing as the plugin is enabled/disabled. */
const val PLUGIN_KIND = "plugin"

/** Read-only skills offered by enabled plugins; the implementation is provided by the app layer and reads the current registry live. */
interface PluginSkills {
    /** Returns only skills of enabled plugins; kind = [PLUGIN_KIND], name = `<pluginId>/<skillName>`. */
    fun entries(): List<GuideInfo>

    /** Read the body of a file (SKILL.md or an attachment, with the front matter stripped); returns null if it cannot be read. */
    fun read(info: GuideInfo, file: String): String?
}
