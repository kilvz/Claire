package com.wangzan101.mika.plugin

/**
 * Disclosure shown to the user before installing a plugin: which tools it will get (name → risk level) and whether it
 * will rewrite the system prompt.
 * Plugin scripts run inside the App process, and the UI uses this disclosure to honestly tell the user that they can
 * read conversations, memory and model keys.
 */
data class PluginDisclosure(val tools: List<Pair<String, String>>, val rewritesPrompt: Boolean)

fun PluginManifest.disclosure(): PluginDisclosure = PluginDisclosure(
    tools = tools.map { it.name to (it.risk ?: "read") },
    rewritesPrompt = promptTransform != null,
)

/** Default prompt rules for plugins (persona.conf): all comments, i.e. no prompt change; "Restore defaults" writes this back. */
val DEFAULT_PERSONA_CONF: String = """
    # Prompt rewrite config. Changes take effect on the next task.
    # prefix / suffix: the whole block is placed outside the system prompt (one line each)
    # replace: one per line, format  old text=>new text  (literal match, not a regex)
    # Put a [model name fragment] at the start of a line to apply it only to that model, e.g. [claude]prefix=…
    #
    # Example: conclusion first
    # prefix=Give the conclusion first, then the reasons. When unsure, say so plainly instead of hedging.
    #
    # Example: remove a fixed phrase
    # replace=You are Claire, the user's personal assistant on their phone.=>You are Claire.
    #
    # Example: add a rule
    # suffix=Keep answers to three sentences unless the user wants detail.
""".trimIndent()
