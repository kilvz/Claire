package com.wangzan101.mika.core.learn.memory

import kotlinx.serialization.Serializable

@Suppress("EnumEntryName")
enum class MemoryKind { fact, preference, procedure }

/** Only procedures can be in pending (awaiting user approval). */
@Suppress("EnumEntryName")
enum class MemoryStatus { active, pending }

/**
 * [type]: `user_said` (carries the user's exact words in [quote]), `manual` (typed by the user in settings), or
 * `inferred` — the user asked Claire to remember it but the content is Claire's own inference (it contains specifics
 * the user never said); in that case [quote] is the sentence where the user asked for it, not the source of the
 * content. This kind only exists when the user has turned on the "let Claire record things by inferring them herself"
 * option (see [MemoryStore.inferenceAllowed]); when handed to tasks and to the UI it is marked as not said by the user.
 */
@Serializable
data class MemorySource(
    val type: String,
    val quote: String? = null,
    val conversationId: String? = null,
    val atMs: Long,
    val channel: String? = null,
)

/** [replaces]: the old content that this update replaced, keeping only one version (the UI shows "originally: …"). */
@Serializable
data class MemoryItem(
    val id: String,
    val kind: MemoryKind,
    val content: String,
    val keywords: List<String>,
    val source: MemorySource,
    val status: MemoryStatus,
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long? = null,
    val useCount: Int = 0,
    val replaces: String? = null,
)
