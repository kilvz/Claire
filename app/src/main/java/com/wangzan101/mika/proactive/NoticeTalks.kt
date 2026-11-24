package com.wangzan101.mika.proactive

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.core.agent.NoticeTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.proactive.NoticeHint
import com.wangzan101.mika.core.proactive.NoticeHints
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reminder chats: when the user opens a reminder, Claire writes the few notifications it picked as one of her own messages into the chat (for
 * how the message is written see [com.wangzan101.mika.core.proactive.NoticeTalk]; for how that turn is recognized and treated see [NoticeTurn]).
 *
 * - One per day: opening another one the same day continues in the same chat; once the day is over, or the user deleted that chat, a new one
 *   is started. "Which one is today's" is recorded in [ProactivePrefs.hintTalk] (just the chat id and the day).
 * - After writing it, that chat is made "the chat the user is in right now": the floating-window card continues from it, the user replies in
 *   the card, and the task runs in this chat; going back into Claire shows the same chat, where the conversation can continue.
 * - Only at this moment are the notification titles and bodies persisted (in the chat file, unencrypted like other chats): reminders never
 *   opened are not written.
 */
class NoticeTalks(
    private val store: ConversationStore,
    private val prefs: ProactivePrefs,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * The message to write, and the title used when starting a new chat. [shown] is how many entries the message actually lists (the first
     * few of those handed to it): only those are marked as seen. Ones that do not fit in the message must not be counted as seen along with
     * them — the user never saw them, yet the reminder count would be reset and they would be lost forever.
     */
    class Draft(val message: String, val title: String, val shown: Int)

    private val lock = Mutex()

    /**
     * The user opened a reminder: hand the unseen entries in [board] (newest first) to [draft] to write as one message, put it into today's
     * reminder chat, and mark those entries as seen. Returns the id of that chat. When [draft] returns null (nothing to write): write nothing
     * and return today's existing reminder chat (null if there is none).
     *
     * One at a time: on a double tap, or two entries tapped at once, the latter waits until the former has been written and marked as seen, so
     * the same entries are never written twice. Only the entries the message lists are marked as seen ([Draft.shown]; ones that arrived while
     * writing, or that do not fit in the message, do not count — the next tap lists them). The chat file is written on [io]; if it cannot be
     * written it throws as usual, and those entries do not count as seen.
     */
    suspend fun open(board: NoticeHints, nowMs: Long, draft: (List<NoticeHint>) -> Draft?): String? = lock.withLock {
        val fresh = board.state.value.list.filter { !it.seen }
        var shown = 0
        withContext(io) {
            val d = draft(fresh) ?: return@withContext today(nowMs)
            post(d.message, d.title, nowMs).also { shown = d.shown.coerceIn(0, fresh.size) }
        }.also { board.markSeen(fresh.take(shown)) }
    }

    /** Today's reminder chat; null when none was opened, it is already yesterday's, or it was deleted. */
    @Synchronized fun today(nowMs: Long): String? =
        prefs.hintTalk?.takeIf { (id, day) -> day == dayOf(nowMs) && store.turns(id).isNotEmpty() }?.first

    /**
     * Write [message] as a turn from Claire in the reminder chat (with no user utterance), and make this chat the current chat; [title] is
     * only used when starting a new chat. Returns the chat id. If it cannot be written it throws as usual, and the caller decides (those
     * reminders therefore do not count as seen).
     */
    @Synchronized fun post(message: String, title: String, nowMs: Long): String {
        val existing = today(nowMs)
        val id = existing ?: store.newConversation()
        store.append(id, Turn("", message, "succeeded", runId = NoticeTurn.RUN_PREFIX + newId()))
        if (existing == null) {
            // With no user utterance in the first turn a title cannot be derived (automatic naming also only looks at chats with no turns yet): write one directly
            store.setTitle(id, title)
            prefs.hintTalk = id to dayOf(nowMs)
        }
        store.setCurrent(CurrentChat.Open(id))
        return id
    }

    private fun dayOf(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate().toEpochDay()
}
