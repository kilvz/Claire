package com.wangzan101.mika.core.learn.teach

import com.wangzan101.mika.core.learn.skills.PersonalSkill

/**
 * The process was killed after assembly finished and the draft was stored, but before the session was written to disk:
 * on recovery, find the pending draft of the same trace by `learned_from` instead of generating it again.
 */
object TeachPending {
    fun find(drafts: Map<String, PersonalSkill>, traceId: String): Pair<String, PersonalSkill>? {
        val marker = TeachSkill.PREFIX + traceId
        return drafts.entries.firstOrNull { it.value.learnedFrom == marker }?.toPair()
    }
}
