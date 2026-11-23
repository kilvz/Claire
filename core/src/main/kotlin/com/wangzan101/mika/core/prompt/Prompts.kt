package com.wangzan101.mika.core.prompt

import java.util.concurrent.ConcurrentHashMap

/**
 * Prompts shipped with the app, under the resource directory prompts/ (layout in prompts/README.md):
 * system/ identity (L0), capabilities/ one section per capability (L1), tools/ one note per tool,
 * feedback/ what tools and the main loop report back to the model, roles/ internal roles (criteria,
 * verification, description, grounding, ...).
 *
 * The model always receives English prompts, whatever the UI language; UI translations live in
 * res/values-* and never change what is sent to the model.
 *
 * {{name}} placeholders are filled by the caller; a missing value is an error rather than sending the
 * raw placeholder to the model.
 */
object Prompts {
    private val cache = ConcurrentHashMap<String, String>()

    /** Raw resource text, trimmed. */
    fun read(path: String): String = cache.getOrPut(path) {
        checkNotNull(load(path)) { "missing prompts/$path" }
    }

    private fun load(path: String): String? =
        Prompts::class.java.getResourceAsStream("/prompts/$path")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.trim()

    fun system(name: String): String = read("system/$name.md")

    fun capability(name: String): String = read("capabilities/$name.md")

    fun role(name: String): String = read("roles/$name.md")

    /** Short note for an entry tool while its group is collapsed (prompts/entries/<tool>.md). */
    fun entry(tool: String): String = read("entries/$tool.md")

    /** One tool's note for the model (prompts/tools/<file>.md), placeholders filled. */
    fun tool(file: String, vararg vars: Pair<String, Any?>): String = fill(read("tools/$file.md"), vars.toMap())

    /** Single pass: values (on-screen text, user words) keep any {{...}} they contain verbatim. */
    fun fill(template: String, vars: Map<String, Any?>): String = PLACEHOLDER.replace(template) { m ->
        val k = m.groupValues[1]
        check(k in vars) { "unfilled placeholder {{$k}} in: ${template.take(80)}" }
        vars[k].toString()
    }

    private val PLACEHOLDER = Regex("\\{\\{([a-z0-9_]+)\\}\\}")
}
