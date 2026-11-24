package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.ActionKind
import com.wangzan101.mika.core.frontdesk.DeskEvent
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.i18n.ChineseTexts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeskNotifierTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val texts = ChineseTexts()

    @Test fun sinkAppendsTurnAndNotifies() = runBlocking {
        val store = ConversationStore(tmp.newFolder())
        store.append("p", Turn("帮我订票", "好的", "desk", deskTasks = listOf("c1")))
        val notices = mutableListOf<DeskNotice>()
        val notifier = DeskNotifier(store) { notices += it }

        notifier.record("p", DeskReport(DeskEvent.Succeeded("c1", "订好了明早 8 点的票"), "订票"))

        val last = store.turns("p").last()
        assertEquals(DeskNotifier.STATUS, last.status)
        assertEquals("", last.userText)
        assertEquals(listOf("c1"), last.deskTasks)
        // On success, the raw result the backend gave is written: no task name added, no truncation
        assertEquals("订好了明早 8 点的票", last.outcome)
        val notice = notices.single()
        assertEquals("p", notice.conversationId)
        assertEquals("订票", notice.title)
        assertFalse(notice.needsYou)
    }

    // Needs an answer, confirmation, or takeover: the task itself sends a "needs you" notification with buttons; the front-desk fallback writes no line (the chat page shows a problem card at the end of the conversation) and sends no second one.
    @Test fun needsYouWritesNoRowAndNoSecondNotification() = runBlocking {
        val store = ConversationStore(tmp.newFolder())
        val notices = mutableListOf<DeskNotice>()
        val notifier = DeskNotifier(store) { notices += it }
        notifier.record("p", DeskReport(DeskEvent.NeedsAnswer("c1", "要几点的车?", null), "订票"))
        notifier.record("p", DeskReport(DeskEvent.NeedsAction("c1", ActionKind.Confirm), "订票"))
        notifier.record("p", DeskReport(DeskEvent.NeedsAction("c1", ActionKind.Takeover), "订票"))
        assertTrue(store.turns("p").isEmpty())
        assertTrue(notices.isEmpty())
    }

    // Prerequisites unmet: the task sends no separate reminder, and this front-desk line is the "needs you".
    @Test fun prerequisiteStillNotifiesAsNeedsYou() = runBlocking {
        val store = ConversationStore(tmp.newFolder())
        val notices = mutableListOf<DeskNotice>()
        DeskNotifier(store) { notices += it }.record("p", DeskReport(DeskEvent.NeedsAction("c1", ActionKind.Prerequisite), "订票"))
        assertTrue(notices.single().needsYou)
    }

    // Every event has a non-empty line (including queue position).
    @Test fun everyEventHasCopy() {
        val events = listOf(
            DeskEvent.Started("c"), DeskEvent.Queued("c", 2), DeskEvent.Progress("c", "打开日历"),
            DeskEvent.NeedsAnswer("c", "哪天?", listOf("今天", "明天")),
            DeskEvent.NeedsAction("c", ActionKind.Confirm),
            DeskEvent.NeedsAction("c", ActionKind.Takeover),
            DeskEvent.NeedsAction("c", ActionKind.Prerequisite),
            DeskEvent.Succeeded("c", "好了"), DeskEvent.Failed("c", "没网"), DeskEvent.Cancelled("c"),
        )
        val lines = events.map(DeskNotifier::line)
        lines.forEach { assertTrue(it.isNotBlank()) }
        assertTrue(lines[1].contains("2"))
        assertEquals(events.size, lines.toSet().size)
    }

    // When the page is not present, queued, started, and progress write no line and send no notification (the chat page's progress bar reads the task's live state).
    @Test fun queueStartAndProgressWriteNothing() = runBlocking {
        val store = ConversationStore(tmp.newFolder())
        val notices = mutableListOf<DeskNotice>()
        val notifier = DeskNotifier(store) { notices += it }
        notifier.record("p", DeskReport(DeskEvent.Queued("c1", 1), "查天气"))
        notifier.record("p", DeskReport(DeskEvent.Started("c1"), "查天气"))
        notifier.record("p", DeskReport(DeskEvent.Progress("c1", "打开天气"), "查天气"))
        assertTrue(store.turns("p").isEmpty())
        assertTrue(notices.isEmpty())
    }

    // From start to finish only the closing row remains in the conversation: success is the raw text, failure is a "this item: ..." sentence.
    @Test fun onlyTheEndingIsWritten() = runBlocking {
        val store = ConversationStore(tmp.newFolder())
        val notifier = DeskNotifier(store) { }
        notifier.record("p", DeskReport(DeskEvent.Started("c1"), "订票"))
        notifier.record("p", DeskReport(DeskEvent.NeedsAnswer("c1", "要几点的车?", null), "订票"))
        notifier.record("p", DeskReport(DeskEvent.Progress("c1", "填乘车人"), "订票"))
        notifier.record("p", DeskReport(DeskEvent.Succeeded("c1", "订好了"), "订票"))
        notifier.record("p", DeskReport(DeskEvent.Failed("c2", "没网"), "查汇率"))
        val rows = store.turns("p").map { it.outcome }
        assertEquals(2, rows.size)
        assertEquals("订好了", rows[0])
        assertTrue(rows[1], rows[1].contains("查汇率") && rows[1].contains("没网"))
    }

    // Notification text is plain: Markdown markers in the task answer do not appear verbatim in the notification; the row written back to the conversation keeps them (the chat page renders it).
    @Test fun notificationTextHasNoMarkdown() = runBlocking {
        val store = ConversationStore(tmp.newFolder())
        val notices = mutableListOf<DeskNotice>()
        DeskNotifier(store) { notices += it }
            .record("p", DeskReport(DeskEvent.Succeeded("c1", "安卓版本号是 **14**,见 `关于手机`"), "查看安卓版本号"))
        val text = notices.single().text
        assertTrue(text, text.contains("安卓版本号是 14,见 关于手机"))
        assertFalse(text, text.contains("*") || text.contains("`"))
        assertTrue(store.turns("p").last().outcome.contains("**14**"))
    }
}
