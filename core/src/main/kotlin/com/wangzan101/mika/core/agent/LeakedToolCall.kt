package com.wangzan101.mika.core.agent

/**
 * Tool calls leaked into the reply body: some inference services occasionally fail to parse the model's tool calls,
 * so the call comes back verbatim as text. Such a reply is not an answer and must not be handed to the user.
 *
 * Only the common tool-call markers are recognized, and one of Claire's tool names (whole word) must appear after
 * the marker; merely talking about these formats in an answer does not count.
 * Detection only: calls in the text are neither parsed nor executed.
 */
internal object LeakedToolCall {
    /** Markers models commonly use when writing tool calls. */
    private val MARKERS = listOf(
        "<tool_call>", "<function=",
        "<|python_tag|>",
        "[TOOL_CALLS]",
        "<｜tool▁calls▁begin｜>", "<｜tool▁call▁begin｜>",
        "<|tool_calls_section_begin|>", "<|tool_call_begin|>",
    )

    /**
     * Returns the name of the tool from [toolNames] whose call leaked into [text]; null when there is none.
     * Only tool names appearing after the first call marker are considered, and the earliest one wins: words such as
     * wait or find that share a tool's name but appear before the marker do not count.
     */
    fun find(text: String, toolNames: Collection<String>): String? {
        val start = MARKERS.mapNotNull { m -> text.indexOf(m).takeIf { it >= 0 } }.minOrNull() ?: return null
        return toolNames.filter { it.isNotEmpty() }
            .mapNotNull { name -> wholeWord(name).find(text, start)?.let { name to it.range.first } }
            .minByOrNull { it.second }?.first
    }

    private fun wholeWord(name: String) = Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])")
}
