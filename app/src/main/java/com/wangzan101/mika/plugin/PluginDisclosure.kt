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
    # 提示词改写配置。改完下一次任务生效。
    # prefix / suffix:整段包在系统提示词外面(各一行)
    # replace:一行一条,格式  旧文字=>新文字  (字面匹配,不是正则)
    # 行首加 [模型名片段] 只对那个模型生效,例如 [claude]prefix=…
    #
    # 例:先给结论
    # prefix=先给结论,再给理由。不确定的事直接说不确定,不要绕。
    #
    # 例:去掉某句固定话术
    # replace=You are Claire, the user's personal assistant on their phone.=>You are Claire.
    #
    # 例:追加一条规矩
    # suffix=回答尽量控制在三句话内,除非用户要细节。
""".trimIndent()
