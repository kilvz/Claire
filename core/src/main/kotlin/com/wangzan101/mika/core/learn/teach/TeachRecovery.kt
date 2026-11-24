package com.wangzan101.mika.core.learn.teach

/**
 * How to carry on with sessions on disk after a process restart.
 *
 * Collecting and pausing cannot resume collecting after a restart, so they become stopped.
 * A session that was already summarizing but whose draft was not stored yet must go back to summarizing and assemble again: drafts and failure reasons may only be written while summarizing,
 * so stopping at summarized would leave this demonstration stuck at "could not write a draft", and a draft, even if written, would not be shown.
 */
object TeachRecovery {
    fun afterLoad(session: TeachSession): TeachSession = when {
        session.phase == TeachPhase.Recording || session.phase == TeachPhase.Paused ->
            session.copy(phase = TeachPhase.Interrupted)
        session.phase == TeachPhase.Reviewing && session.draftId == null ->
            session.copy(phase = TeachPhase.Drafting)
        else -> session
    }
}
