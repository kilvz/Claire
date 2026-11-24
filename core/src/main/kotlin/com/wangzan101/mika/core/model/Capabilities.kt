package com.wangzan101.mika.core.model

data class Capabilities(
    val toolCalls: Boolean,
    val vision: Boolean,
    val contextTokens: Int,
    val reasoning: Boolean,
    /**
     * Visual grounding accuracy (boxing elements on a screenshot) has been verified. For unverified models, the points they box need separate corroboration before find_visually hands them out.
     */
    val groundingVerified: Boolean = false,
    /** How the server reuses identical prompt prefixes (saves money, speeds things up); the adapter uses it to decide whether the request carries cache markers. */
    val promptCaching: PromptCaching = PromptCaching.Implicit,
    /** Per-request image limit explicitly published by the provider; do not guess when unknown. */
    val maxImages: Int = Int.MAX_VALUE,
    /** Optional local compaction trigger, measured in tokens for the whole request. */
    val compactionTriggerTokens: Int? = null,
    /** Triggers compaction by round-trip count: once live tool round trips exceed keepRecent + this value, compact (null = do not trigger by round count, size only). */
    val compactionEveryRounds: Int? = null,
)

/**
 * [Implicit]: the server automatically reuses identical prefixes, nothing must be marked in the request. [Explicit]: where the cache ends must be marked on messages (cache_control),
 * unmarked content is not reused. [None]: no reuse.
 */
enum class PromptCaching(val wire: String) {
    Implicit("implicit"), Explicit("explicit"), None("none");

    companion object {
        fun of(wire: String?): PromptCaching = entries.firstOrNull { it.wire == wire } ?: Implicit
    }
}

enum class ModelRole { Main, Vision, Light }
