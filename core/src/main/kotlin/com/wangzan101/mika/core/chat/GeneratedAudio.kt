package com.wangzan101.mika.core.chat

/**
 * A piece of audio Claire generated and left in the workspace during one round of conversation. The path is a sandbox path `/workspace/out/audio/…`;
 * [durationMs] is null when it cannot be read; [text] is the script synthesized at the time (shown only in the UI); [atMs] is the generation time.
 * Only the path is recorded; the audio bytes are not stored in the conversation file.
 */
data class GeneratedAudio(
    val path: String,
    val durationMs: Long? = null,
    val text: String? = null,
    val atMs: Long? = null,
)
