package com.wangzan101.mika.core.proactive

/**
 * Confidence tier of a source (app), set by the user per app:
 * - [Low]: only judge and notify, never summarize from it.
 * - [Mid]: summarized into topics, contacts and daily-message summaries (the default); only carried into tasks at the most aggressive level.
 * - [High]: what is summarized from it is carried into tasks once it passes the threshold — at the balanced and aggressive levels (see [LearnLevel.intoTasks]).
 */
enum class SourceTier(val wire: String) {
    Low("low"), Mid("mid"), High("high");

    companion object {
        fun ofWire(s: String?): SourceTier? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * Aggressiveness: one master switch that adjusts a group of thresholds at once. [Conservative] carries nothing into tasks.
 * At every level, Claire never speaks up without the user tapping, and never replies on the user's behalf.
 *
 * - [topicDays]: how many different days a topic must appear on at least before it counts as established.
 * - [surfaceCap]: the maximum number of distinct conversations to notify about per app per day.
 * - [intoTasks]: which source tiers' summarized output (established topics, contacts, sentences in daily summaries) is carried into tasks; empty means none.
 */
enum class LearnLevel(val wire: String, val topicDays: Int, val surfaceCap: Int, val intoTasks: Set<SourceTier>) {
    Conservative("conservative", topicDays = 2, surfaceCap = 10, intoTasks = emptySet()),
    Balanced("balanced", topicDays = 2, surfaceCap = 20, intoTasks = setOf(SourceTier.High)),
    Aggressive("aggressive", topicDays = 1, surfaceCap = Int.MAX_VALUE, intoTasks = setOf(SourceTier.Mid, SourceTier.High));

    /** Whether this topic counts as established at this level. */
    fun established(t: ObservedTopic): Boolean = t.days >= topicDays

    companion object {
        fun ofWire(s: String?): LearnLevel = entries.firstOrNull { it.wire == s } ?: Conservative
    }
}
