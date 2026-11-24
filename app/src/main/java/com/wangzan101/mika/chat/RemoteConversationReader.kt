package com.wangzan101.mika.chat

import com.wangzan101.mika.core.remote.RemoteCompanion
import com.wangzan101.mika.core.remote.RemoteConversation
import com.wangzan101.mika.core.remote.RemoteConversationPage
import com.wangzan101.mika.core.remote.RemoteRunning
import com.wangzan101.mika.core.remote.RemoteRunningView
import com.wangzan101.mika.core.remote.RemoteText
import com.wangzan101.mika.core.remote.RemoteTurn
import com.wangzan101.mika.task.TaskRun

/**
 * The phone side read by the web console: conversations are read live from [ConversationStore] and running tasks live from the task center, with no separate copy stored.
 * Read-only; sending, dispatching, and answering still go through the existing task API entry points.
 */
class RemoteConversationReader(
    private val store: ConversationStore,
    private val runs: () -> List<TaskRun>,
) : RemoteCompanion {

    override fun conversations(limit: Int): List<RemoteConversation> =
        store.listForConsole(limit).map { s ->
            RemoteConversation(
                id = s.id,
                title = clip(s.title, RemoteCompanion.MAX_TITLE),
                updatedAtMs = s.updatedAt,
                pinnedAtMs = s.pinnedAt,
                turnCount = s.turnCount,
            )
        }

    override fun conversation(id: String, from: Int, limit: Int): RemoteConversationPage? {
        if (!store.exists(id)) return null
        val all = store.turns(id)
        val title = store.title(id) ?: all.firstOrNull()?.userText ?: id
        val start = if (from < 0) (all.size - limit).coerceAtLeast(0) else from
        val turns = all.drop(start).take(limit).mapIndexed { offset, t ->
            val user = RemoteText.clip(t.userText, RemoteCompanion.MAX_USER_TEXT)
            val outcome = RemoteText.clip(t.outcome, RemoteCompanion.MAX_OUTCOME)
            val note = t.note?.let { RemoteText.clip(it, RemoteCompanion.MAX_NOTE) }
            val steers = t.steers.take(RemoteCompanion.MAX_STEERS).map { RemoteText.clip(it, RemoteCompanion.MAX_STEER) }
            RemoteTurn(
                index = start + offset,
                userText = user.text,
                outcome = outcome.text,
                status = t.status,
                note = note?.text,
                runId = t.runId,
                steers = steers.map { it.text },
                truncated = user.truncated || outcome.truncated || note?.truncated == true || steers.any { it.truncated },
            )
        }
        return RemoteConversationPage(id, RemoteText.clip(title, RemoteCompanion.MAX_TITLE).text, all.size, start, turns)
    }

    override fun running(): List<RemoteRunning> = runs().take(RemoteCompanion.MAX_RUNNING).map { run ->
        RemoteRunningView.of(run.conversationId, run.goal, run.state.value, run.runId.value)
    }

    private fun clip(text: String, max: Int): String = RemoteText.clip(text, max).text
}
