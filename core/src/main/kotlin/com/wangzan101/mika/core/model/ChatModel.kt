package com.wangzan101.mika.core.model

interface ChatModel {
    val capabilities: Capabilities

    /** Model name, used for logging only. */
    val name: String get() = ""

    /** Throws [ModelError] on failure. */
    suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply
}

/** Models that support incremental text; implementations that do not keep using [ChatModel.complete]. */
interface StreamingChatModel : ChatModel {
    /** [onText] receives the full visible text generated so far for this request; it excludes the model's private reasoning and tool arguments. */
    suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply
}
