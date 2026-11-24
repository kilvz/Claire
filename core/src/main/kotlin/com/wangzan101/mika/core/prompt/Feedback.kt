package com.wangzan101.mika.core.prompt

/**
 * The feedback text given to the model in tool results and in the main loop (hints, error explanations, reminders, titles of reference material)
 * all lives in .md files under prompts/feedback/: each entry starts with a line "## key", and below it up to the next entry is the raw
 * text (may span multiple lines; leading and trailing blank lines do not count); {{name}} is filled in by the caller.
 */
object Feedback {
    /** Feedback files; keys are unique across all files. */
    val FILES = listOf("tools", "loop")
    private val HEADING = Regex("^## ([a-z0-9_.]+)$")

    /** Feedback entries (key -> raw text): computed on every access, not cached — if the prompt language changes, the next lookup follows it. */
    private val entries: Map<String, String>
        get() {
            val out = LinkedHashMap<String, String>()
            for (f in FILES) parse(Prompts.read("feedback/$f.md")).forEach { (k, v) -> check(out.put(k, v) == null) { "duplicate feedback key $k" } }
            return out
        }

    /** The entries in one feedback file (key -> raw text), in order of appearance; explanatory text before the first entry does not count. */
    fun parse(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var key: String? = null
        val body = mutableListOf<String>()
        fun flush() { key?.let { out += it to body.joinToString("\n").trim('\n') }; body.clear() }
        for (line in text.lines()) {
            val m = HEADING.matchEntire(line)
            if (m != null) { flush(); key = m.groupValues[1] } else if (key != null) body += line
        }
        flush()
        return out
    }

    operator fun get(key: String): String = Prompts.fill(raw(key), emptyMap())

    fun format(key: String, vararg vars: Pair<String, Any?>): String = Prompts.fill(raw(key), vars.toMap())

    fun has(key: String): Boolean = key in entries

    /** The entries whose key starts with [prefix], with the prefix removed from the key (e.g. explanations given per error code). */
    fun section(prefix: String): Map<String, String> =
        entries.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }

    val keys: Set<String> get() = entries.keys

    private fun raw(key: String): String = checkNotNull(entries[key]) { "missing feedback $key" }
}
