package com.wangzan101.mika.core.agent

/**
 * A video that Claire generated during one conversation turn and left in the workspace. The path is a sandbox path `/workspace/out/video/…`;
 * [durationMs]/[width]/[height] are read from the finished file and are null / 0 when unreadable; [prompt] is the description from that time (UI only);
 * [atMs] is the generation time. Only the path is recorded; video bytes are not stored in the conversation file.
 */
data class GeneratedVideo(
    val path: String,
    val durationMs: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
    val prompt: String? = null,
    val atMs: Long? = null,
)