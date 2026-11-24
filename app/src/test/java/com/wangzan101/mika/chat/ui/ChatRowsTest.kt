package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.cap.phone.setup.PhoneSetupPrompt
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRowsTest {
    private fun count(
        exchanges: List<Exchange>,
        phoneSetupRow: Int? = null,
        hasBlocked: Boolean = false,
    ) = chatItemCount(exchanges, kept = null, phoneSetupRow, hasBlocked)

    @Test
    fun emptyAndBlockedStatesHaveTheirActualRows() {
        assertEquals(1, count(emptyList()))
        assertEquals(1, count(emptyList(), hasBlocked = true))
        assertEquals(1, count(emptyList(), phoneSetupRow = -1, hasBlocked = true))
    }

    @Test
    fun appendedTurnAdvancesTheTailBeforeLazyListMeasuresIt() {
        val previous = List(30) { Exchange("问题 $it", emptyList(), ExchangeStatus.Done("回答 $it", null), live = false) }
        val oldTail = count(previous) - 1
        val sent = previous + Exchange("刚发送的新一轮", emptyList(), ExchangeStatus.Working, live = true)
        // A new turn has three items: the user bubble, the workspace and the status line. This target depends only on the current state, regardless of how many items the old layout had.
        assertEquals(oldTail + 3, count(sent) - 1)
    }

    @Test
    fun streamingAnswerAndOptionalRowsStayAlignedWithTheDsl() {
        val working = Exchange("问", emptyList(), ExchangeStatus.Working, live = true, draft = "正在回答")
        assertEquals(3, count(listOf(working)))
        val done = working.copy(status = ExchangeStatus.Done("回答", null), draft = null, runId = "run")
        assertEquals(2, count(listOf(done)))
        assertEquals(3, count(listOf(done), phoneSetupRow = -1))
        assertEquals(4, count(listOf(done), phoneSetupRow = -1, hasBlocked = true))
    }

    @Test
    fun setupLineFollowsTheTurnThatNeededThePhone() {
        val first = Exchange("开应用", emptyList(), ExchangeStatus.Failed("手机操作没准备好"), live = false, runId = "run-1")
        val second = Exchange("聊两句", emptyList(), ExchangeStatus.Done("好的", null), live = false, runId = "run-2")
        val both = listOf(first, second)
        // Attached after the turn that triggered it: one extra row after the first turn, none at the end
        assertEquals(
            listOf("u0", "r0", "ps0", "u1", "r1"),
            chatItemKeys(both, kept = null, phoneSetupRow = phoneSetupRow(PhoneSetupPrompt.Need("c", "run-1"), "c", both), hasBlocked = false),
        )
        // Cannot be matched to a specific turn (the id has not been synced in yet): appended at the end as before
        assertEquals(
            listOf("u0", "r0", "u1", "r1", "phone-setup"),
            chatItemKeys(both, kept = null, phoneSetupRow = phoneSetupRow(PhoneSetupPrompt.Need("c", "run-9"), "c", both), hasBlocked = false),
        )
    }

    @Test
    fun setupLineStaysOutOfOtherConversationsAndEmptyLists() {
        val ex = Exchange("问", emptyList(), ExchangeStatus.Done("答", null), live = false, runId = "run-1")
        val list = listOf(ex)
        // Triggered by another conversation: not shown here
        assertEquals(null, phoneSetupRow(PhoneSetupPrompt.Need("other", "run-1"), "c", list))
        // This conversation with a matching id: follows that turn
        assertEquals(0, phoneSetupRow(PhoneSetupPrompt.Need("c", "run-1"), "c", list))
        // This conversation with a non-matching id or with no record opened: appended at the end
        assertEquals(-1, phoneSetupRow(PhoneSetupPrompt.Need("c", "run-9"), "c", list))
        assertEquals(-1, phoneSetupRow(PhoneSetupPrompt.Need("c", null), "c", list))
        // No trigger and no turn: neither is shown
        assertEquals(null, phoneSetupRow(null, "c", list))
        assertEquals(null, phoneSetupRow(PhoneSetupPrompt.Need("c", "run-1"), "c", emptyList()))
    }

    @Test
    fun keysListRowsInTheSameOrderTheDslEmitsThem() {
        val working = Exchange("问", emptyList(), ExchangeStatus.Working, live = true, draft = "正在回答")
        assertEquals(listOf("u0", "d0", "r0"), chatItemKeys(listOf(working), kept = null, phoneSetupRow = null, hasBlocked = false))

        val steer = SteerNote("s1", "改成靠窗", SteerState.Joined, interrupted = "被打断", finished = "正在改")
        val done = Exchange(
            "买票", emptyList(), ExchangeStatus.Done("完成", null), live = false, runId = "run",
            steers = listOf(steer),
            files = listOf(SharedFile("a.txt", 1L, "a.txt", "content://a")),
        )
        assertEquals(
            listOf("u0", "m0-s1", "r0", "f0"),
            chatItemKeys(listOf(done), kept = null, phoneSetupRow = null, hasBlocked = false),
        )
    }

    @Test
    fun targetKeysPointAtTheRenderedRow() {
        val steer = SteerNote("s1", "改成靠窗", SteerState.Joined, interrupted = "被打断", finished = "正在改")
        val done = Exchange(
            "买票", emptyList(), ExchangeStatus.Done("完成", null), live = false, runId = "run",
            steers = listOf(steer),
            files = listOf(SharedFile("a.txt", 1L, "a.txt", "content://a")),
        )
        val list = listOf(done)
        assertEquals("u0", chatTargetKey(list, ChatTarget.Message(0, null)))
        assertEquals("m0-s1", chatTargetKey(list, ChatTarget.Message(0, 0)))
        assertEquals("u0", chatTargetKey(list, ChatTarget.Reply(0, 0, interrupted = false)))
        assertEquals("u0", chatTargetKey(list, ChatTarget.Reply(0, 0, interrupted = true)))
        assertEquals("f0", chatTargetKey(list, ChatTarget.Files(0)))
        assertEquals("r0", chatTargetKey(list, ChatTarget.Status(0)))
        assertNull(chatTargetKey(list, ChatTarget.Message(1, null)))
    }

    @Test
    fun withdrawnSteerFallsBackToTheTurnStart() {
        val withdrawn = SteerNote("s1", "撤回的话", SteerState.Withdrawn)
        val done = Exchange("问", emptyList(), ExchangeStatus.Done("答", null), live = false, steers = listOf(withdrawn))
        assertEquals("u0", chatTargetKey(listOf(done), ChatTarget.Message(0, 0)))
    }
}
