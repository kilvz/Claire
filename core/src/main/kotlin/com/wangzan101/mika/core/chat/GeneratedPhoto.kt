package com.wangzan101.mika.core.chat

/**
 * An image Claire generated and left in the workspace during one round of conversation. The path is a sandbox path `/workspace/out/images/…`;
 * [width]/[height] are 0 when they cannot be read; [prompt] is the prompt given to the model at the time (shown only in the UI); [atMs] is the generation time.
 * Only the path is recorded; the image bytes are not stored in the conversation file.
 */
data class GeneratedPhoto(
    val path: String,
    val width: Int = 0,
    val height: Int = 0,
    val prompt: String? = null,
    val atMs: Long? = null,
)
