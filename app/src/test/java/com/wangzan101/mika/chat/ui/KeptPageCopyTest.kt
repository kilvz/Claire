package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The kept-page row under a turn on the chat page: still open, closed (with or without a reason), and the wording for the remaining time. */
class KeptPageCopyTest {
    @get:Rule val texts = ChineseTexts()

    private val page = KeptPage(1, "c1", "r1", "播放课程", "course.example.com", null, null, 0, 7_200_000)
    private fun ex(runId: String?, kept: String? = null, closed: String? = null) =
        Exchange("播放课程", emptyList(), ExchangeStatus.Done("好了", null), live = false, runId = runId, keptPage = kept, keptPageClosed = closed)

    @Test fun rowFollowsTheLivePageAndTheTurnRecord() {
        assertEquals(KeptRow.Live(page), KeptRow.of(ex("r1", kept = "course.example.com"), page))
        // Right after wrap-up, while that turn has not read back its kept-page record yet, it still shows the page kept at this moment
        assertEquals(KeptRow.Live(page), KeptRow.of(ex("r1"), page))
        assertEquals(KeptRow.Closed("expired"), KeptRow.of(ex("r1", kept = "course.example.com", closed = "expired"), null))
        // A page kept by another turn is not attached under this turn
        assertEquals(KeptRow.Closed(null), KeptRow.of(ex("r0", kept = "a.example.com"), page))
        assertNull(KeptRow.of(ex("r2"), page))
        assertNull(KeptRow.of(ex(null), null))
    }
}
