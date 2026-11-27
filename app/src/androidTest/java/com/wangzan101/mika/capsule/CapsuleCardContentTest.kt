package com.wangzan101.mika.capsule

import com.wangzan101.mika.ChineseUi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.performTouchInput
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.task.ConfirmationCopy
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapsuleCardContentTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = object : CardActions {
        override fun collapse() { calls += "collapse" }
        override fun stop(runKey: String) { calls += "stop $runKey" }
        override fun takeOver(runKey: String) { calls += "takeOver $runKey" }
        override fun resume(runKey: String) { calls += "resume $runKey" }
        override fun answer(runKey: String, text: String) { calls += "answer $runKey $text" }
        override fun approve(runKey: String, requestKey: Int) { calls += "approve $runKey $requestKey" }
        override fun decline(runKey: String, requestKey: Int) { calls += "decline $runKey $requestKey" }
        override fun openMika(conversationId: String) { calls += "open $conversationId" }
        override fun zoom(on: Boolean) { calls += "zoom $on" }
        override fun openInput() { calls += "openInput" }
        override fun draft(text: String) { calls += "draft $text" }
        override fun send() { calls += "send" }
        override fun freshChat(fresh: Boolean) { calls += "fresh $fresh" }
        override fun loadEarlier() { calls += "earlier" }
        override fun captureScreen() { calls += "capture" }
        override fun dropScreen() { calls += "drop" }
        override fun hideCapsule() { calls += "hide" }
        override fun setFollowUpMode(mode: com.wangzan101.mika.chat.ui.FollowUpMode) { calls += "mode:$mode" }
        override fun askQuit() { calls += "askQuit" }
        override fun cancelQuit() { calls += "cancelQuit" }
        override fun quit() { calls += "quit" }
        override fun teach() { calls += "teach" }
        override fun voiceChat() { calls += "voiceChat" }
    }

    private fun run(key: String, state: TaskState, kind: CapsuleKind, line: String = "", startedAt: Long? = null, ahead: Int = 0) =
        CardRun(CapsuleRun(key, "c-$key", state, goal = "买两支牙膏"), kind, line, startedAt, ahead)

    private fun detail(r: CardRun) = CardView.Detail(r, CapsuleCards.content(r))

    private fun show(card: CardView, preview: Bitmap? = null, zoomed: Boolean = false, thread: ChatThread? = null, input: CardInputUi = CardInputUi()) {
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                androidx.compose.runtime.CompositionLocalProvider(com.wangzan101.mika.chat.ui.LocalScreenShots provides shots) {
                    CapsuleCard(card, preview, zoomed, actions, { if (it == null) null else "0:23" }, thread = thread, height = 420.dp, input = input)
                }
            }
        }
    }

    /** Fake screen store: s1 is a screen of the settings page, s2 is a page that does not allow screenshots. */
    private val at = java.time.LocalDateTime.of(2026, 10, 28, 19, 42).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val shots = com.wangzan101.mika.chat.ui.ScreenShots { _, id ->
        when (id) {
            "s1" -> com.wangzan101.mika.chat.ui.ShownScreen(com.wangzan101.mika.core.screen.user.UserScreen("s1", at, "com.android.settings", "设置"),
                Bitmap.createBitmap(90, 200, Bitmap.Config.ARGB_8888).asImageBitmap())
            "s2" -> com.wangzan101.mika.chat.ui.ShownScreen(com.wangzan101.mika.core.screen.user.UserScreen("s2", at, "com.example.bank", "银行", blocked = true), null)
            else -> null
        }
    }

    private fun idleThread(vararg userTexts: String) = CapsuleThreads.build(
        userTexts.mapIndexed { i, t -> PastTurn(com.wangzan101.mika.core.agent.Turn(t, "收到", "succeeded", runId = "r$i")) },
        CapsuleThreads.PAGE, emptyList(),
    )

    private fun footerTextLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun assertEightDpVerticalLinkPadding(text: String) {
        val bounds = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        val textHeight = footerTextLayout(text).size.height
        assertEquals(with(compose.density) { 16.dp.toPx() }, bounds.height - textHeight, 1f)
    }

    @Test fun shortFooterLinksStayOnOneRow() {
        val primary = "A primary link over sixteen chars"
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                Box(Modifier.width(360.dp).testTag("short-footer")) {
                    FooterLinks(actions, primary to { calls += "primary" })
                }
            }
        }

        val first = compose.onNodeWithText(primary).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText(CardCopy.HIDE_CAPSULE).fetchSemanticsNode().boundsInRoot
        val separator = compose.onNodeWithText("·").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("short-footer").fetchSemanticsNode().boundsInRoot
        assertEquals("measured short links stay together despite character count", first.center.y, second.center.y, 1f)
        assertTrue(first.right <= separator.left && separator.right <= second.left)
        assertTrue(first.left >= footer.left && second.right <= footer.right)
        assertEquals(1, footerTextLayout(primary).lineCount)
        assertEquals(1, footerTextLayout(CardCopy.HIDE_CAPSULE).lineCount)
        assertEightDpVerticalLinkPadding(primary)
        assertEightDpVerticalLinkPadding(CardCopy.HIDE_CAPSULE)
        compose.onNodeWithText(primary).performTouchInput { click(center) }
        compose.onNodeWithText(CardCopy.HIDE_CAPSULE).performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf("primary", "hide"), calls) }
    }

    @Test fun longFooterLinksWrapByMeasuredWidth() {
        // Twelve wide glyphs at large font need more width than the narrow card; a character-count rule would keep one row.
        val primary = "WWWWWWWWWWWW"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MikaTheme(dark = false, reduceMotion = true) {
                    Box(Modifier.width(220.dp).testTag("long-footer")) {
                        FooterLinks(actions, primary to { calls += "primary" })
                    }
                }
            }
        }

        val first = compose.onNodeWithText(primary).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText(CardCopy.HIDE_CAPSULE).fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("long-footer").fetchSemanticsNode().boundsInRoot
        assertTrue("second complete link wraps below the first: $first / $second", first.bottom <= second.top + 1f)
        assertTrue(first.left >= footer.left && first.right <= footer.right)
        assertTrue(second.left >= footer.left && second.right <= footer.right)
        val primaryLayout = footerTextLayout(primary)
        val hideLayout = footerTextLayout(CardCopy.HIDE_CAPSULE)
        assertTrue("primary layout: ${primaryLayout.lineCount} lines, ${primaryLayout.size}", primaryLayout.lineCount > 1)
        assertTrue("primary clipped: ${primaryLayout.lineCount} lines, ${primaryLayout.size}", !primaryLayout.hasVisualOverflow)
        assertEquals(1, hideLayout.lineCount)
        val hideGlyphWidth = hideLayout.getLineRight(0) - hideLayout.getLineLeft(0)
        assertTrue("complete hide label fits its measured link: $hideGlyphWidth / ${hideLayout.size.width}", hideGlyphWidth <= hideLayout.size.width + 1f)
        assertEightDpVerticalLinkPadding(primary)
        assertEightDpVerticalLinkPadding(CardCopy.HIDE_CAPSULE)
        compose.onNodeWithText(primary).performTouchInput { click(center) }
        compose.onNodeWithText(CardCopy.HIDE_CAPSULE).performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf("primary", "hide"), calls) }
    }

    @Test fun threadShowsScreenMessageWithTagAndWords() {
        show(CardView.Chat("c1", title = "t"), thread = idleThread("[[screen:s1]]", "[[screen:s1]]\n第三条有没有坑?", "[[screen:s2]]"))
        val tag = "你此刻的屏幕 · 设置 · 19:42"
        // The card rests on the newest lines: the second screen (with its attached note) and the screen that blocks screenshots are in view, while the first screen is above and not laid out yet
        compose.waitUntil(5_000) { compose.onAllNodesWithText(tag, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        compose.onAllNodesWithTag("screen-thumb", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("第三条有没有坑?", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(com.wangzan101.mika.chat.ui.ScreenCopy.BLOCKED, useUnmergedTree = true).assertIsDisplayed()
        // A line carrying a screen does not display the marker line as text
        compose.onAllNodesWithText("[[screen:", substring = true, useUnmergedTree = true).assertCountEquals(0)
        // Scroll up to the first line: only the screen was sent, with no attachment bubble
        compose.onNodeWithTag("capsule-thread").performScrollToIndex(0)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(tag, useUnmergedTree = true)[0].assertIsDisplayed()
        compose.onAllNodesWithText("[[screen:", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun cameraButtonLeftOfInputCapturesScreen() {
        show(CardView.Chat("c1", title = "t"), thread = idleThread())
        compose.onNodeWithTag("capsule-camera").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("capture"), calls) }
        val camera = compose.onNodeWithTag("capsule-camera").fetchSemanticsNode().boundsInRoot
        val field = compose.onNodeWithTag("capsule-input").fetchSemanticsNode().boundsInRoot
        assertTrue("the screenshot button is left of the input field", camera.right <= field.left)
    }

    @Test fun attachmentChipShowsScreenAndCanBeSentOrRemoved() {
        val screen = com.wangzan101.mika.core.screen.user.UserScreen("", at, "com.android.settings", "设置")
        val pending = PendingScreen(com.wangzan101.mika.core.screen.user.CapturedScreen(byteArrayOf(1), screen),
            Bitmap.createBitmap(90, 200, Bitmap.Config.ARGB_8888).asImageBitmap())
        show(CardView.Chat("c1", title = "t"), thread = idleThread(), input = CardInputUi(screen = pending))
        compose.onNodeWithTag("capsule-attachment").assertIsDisplayed()
        compose.onNodeWithText(com.wangzan101.mika.chat.ui.ScreenCopy.TAG, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("设置 · 19:42", useUnmergedTree = true).assertIsDisplayed()
        // With only a screenshot attached and no text, sending directly still works
        compose.onNodeWithTag("capsule-send").performClick()
        compose.onNodeWithTag("capsule-attachment-remove").performClick()
        compose.runOnIdle { assertEquals(listOf("send", "drop"), calls) }
    }

    @Test fun blockedChipSaysNotCaptured() {
        val screen = com.wangzan101.mika.core.screen.user.UserScreen("", at, "com.example.bank", "银行", blocked = true)
        show(CardView.Chat("c1", title = "t"), thread = idleThread(),
            input = CardInputUi(screen = PendingScreen(com.wangzan101.mika.core.screen.user.CapturedScreen(null, screen), null)))
        compose.onNodeWithText(com.wangzan101.mika.chat.ui.ScreenCopy.BLOCKED, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun chat(r: CardRun) = CardView.Chat(r.run.conversationId, r, CapsuleCards.content(r))

    /** [n] rounds of question and answer, with [lines] lines in each answer. */
    private fun history(n: Int, lines: Int = 1) = CapsuleThreads.build(
        (1..n).map { i -> PastTurn(com.wangzan101.mika.core.agent.Turn("问题 $i", (1..lines).joinToString("\n") { "回答 $i 第 $it 行" }, "succeeded", runId = "r$i")) },
        CapsuleThreads.PAGE, emptyList(),
    )

    @Test fun runningShowsStepsElapsedStopAndLink() {
        show(detail(run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "在搜牙膏", startedAt = 0L)))
        compose.onNodeWithText("买两支牙膏").assertIsDisplayed()
        compose.onNodeWithText("在干活 · 0:23").assertIsDisplayed()
        compose.onNodeWithText("在搜牙膏").assertIsDisplayed()
        // Stop sits to the right of the input field (the black stop key); there is no separate text button any more
        compose.onAllNodesWithText(CardCopy.STOP).assertCountEquals(0)
        compose.onNodeWithContentDescription(CardCopy.STOP).performClick()
        compose.onNodeWithText(CardCopy.SEE_PROCESS).performClick()
        compose.onNodeWithContentDescription(CardCopy.COLLAPSE).performClick()
        assertEquals(listOf("stop a", "open c-a", "collapse"), calls)
    }

    @Test fun previewShowsAndZooms() {
        val frame = Bitmap.createBitmap(36, 80, Bitmap.Config.ARGB_8888)
        show(detail(run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "在搜")), preview = frame)
        compose.onNodeWithContentDescription(CardCopy.SCREEN).performClick()
        assertEquals(listOf("zoom true"), calls)
    }

    @Test fun zoomedClosesOnTap() {
        val frame = Bitmap.createBitmap(36, 80, Bitmap.Config.ARGB_8888)
        show(detail(run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "在搜")), preview = frame, zoomed = true)
        compose.onNodeWithTag("capsule-zoom").performClick()
        assertEquals(listOf("zoom false"), calls)
    }

    @Test fun noPreviewWithoutFrames() {
        show(detail(run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "在搜")))
        compose.onAllNodesWithTag("capsule-screen").assertCountEquals(0)
    }

    @Test fun takeoverHasTakeOverAndStop() {
        show(detail(run("a", TaskState.WaitingTakeover("payment", "请输入支付密码"), CapsuleKind.NeedTakeover, "需要你：付款")))
        compose.onNodeWithText("请输入支付密码").assertIsDisplayed()
        compose.onNodeWithText(CardCopy.TAKE_OVER).performClick()
        compose.onNodeWithText(CardCopy.STOP).performClick()
        assertEquals(listOf("takeOver a", "stop a"), calls)
    }

    @Test fun queuedTakeoverCannotTakeOverYet() {
        show(detail(run("a", TaskState.WaitingTakeover("login", "请登录"), CapsuleKind.NeedTakeover, "需要你：登录", ahead = 1)))
        compose.onNodeWithText(CapsuleCards.QUEUED_AHEAD).assertIsDisplayed()
        compose.onAllNodesWithTag("capsule-card").assertCountEquals(1)
        compose.onNodeWithText(CardCopy.TAKE_OVER).assertDoesNotExist()
    }

    // Only operations that spend money need approval: the card offers just "approve once only" and "decline"
    @Test fun confirmOffersOnceOrDecline() {
        val r = run("a", TaskState.Idle, CapsuleKind.NeedConfirm)
        show(CardView.Detail(r, CardContent.Confirm("要你同意:下单", "金额:¥19.9", 9)))
        compose.onNodeWithText(ConfirmationCopy.NOTE).assertIsDisplayed()
        compose.onNodeWithText(CardCopy.APPROVE_ONCE_ONLY).performClick()
        compose.onNodeWithText(CardCopy.DECLINE).performClick()
        assertEquals(listOf("approve a 9", "decline a 9"), calls)
    }

    @Test fun questionOptionsAnswer() {
        show(detail(run("a", TaskState.WaitingInput("要哪个？", listOf("大的", "小的")), CapsuleKind.NeedAnswer)))
        compose.onNodeWithText("小的").performClick()
        assertEquals(listOf("answer a 小的"), calls)
    }

    @Test fun questionInputOpensOnTap() {
        show(detail(run("a", TaskState.WaitingInput("几点出发？"), CapsuleKind.NeedAnswer)))
        compose.onNodeWithTag("capsule-input").performClick()
        assertEquals(listOf("openInput"), calls)
    }

    @Test fun openInputTypesAndSends() {
        var ui by mutableStateOf(CardInputUi(open = true))
        val typing = object : CardActions by actions {
            override fun draft(text: String) { ui = ui.copy(draft = text) }
        }
        val card = detail(run("a", TaskState.Running(1, "n"), CapsuleKind.Running, "在搜"))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, null, false, typing, { null }, input = ui) }
        }
        compose.onNodeWithTag("capsule-field").performTextInput("换成薄荷味")
        compose.onNodeWithContentDescription(CardCopy.SEND).performClick()
        assertEquals("换成薄荷味", ui.draft)
        assertEquals(listOf("send"), calls)
    }

    @Test fun keptDraftShowsClosedAndTypingContinuesAtTheEnd() {
        var ui by mutableStateOf(CardInputUi(draft = "帮我把这段"))
        val typing = object : CardActions by actions {
            override fun draft(text: String) { ui = ui.copy(draft = text) }
        }
        val card = detail(run("a", TaskState.Running(1, "n"), CapsuleKind.Running, "在搜"))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, null, false, typing, { null }, input = ui) }
        }
        compose.onNodeWithText("帮我把这段").assertIsDisplayed()
        ui = ui.copy(open = true)
        compose.onNodeWithTag("capsule-field").performTextInput("话")
        compose.runOnIdle { assertEquals("帮我把这段话", ui.draft) }
        // After spoken text is appended from outside, further typing also lands at the end
        ui = ui.withSpoken("发给小王")
        compose.onNodeWithTag("capsule-field").performTextInput("吧")
        compose.runOnIdle { assertEquals("帮我把这段话发给小王吧", ui.draft) }
    }

    @Test fun noInputWhileTakeoverOrConfirm() {
        show(detail(run("a", TaskState.WaitingTakeover("login", "请登录"), CapsuleKind.NeedTakeover, "需要你：登录")))
        compose.onAllNodesWithTag("capsule-input").assertCountEquals(0)
    }

    @Test fun idleTeachStaysFoldedAboveTheInput() {
        show(CardView.Chat("c1", title = "买牙膏"), thread = history(1))
        compose.onNodeWithText("教 Claire").assertDoesNotExist()
        val shelf = compose.onNodeWithTag("capsule-teach-shelf").assertIsDisplayed().fetchSemanticsNode()
        val input = compose.onNodeWithTag("capsule-input").fetchSemanticsNode()
        assertTrue("the arrow is above the input bar", shelf.boundsInRoot.bottom <= input.boundsInRoot.top)
        with(compose.density) {
            assertTrue("when collapsed only the arrow is left", shelf.boundsInRoot.height <= 32.dp.toPx())
        }
        assertEquals("展开教 Claire", shelf.config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription])
        compose.onNodeWithTag("capsule-teach-shelf").performClick()
        compose.onNodeWithText("教 Claire").assertIsDisplayed().performClick()
        assertEquals(listOf("teach"), calls)
        val opened = compose.onNodeWithTag("capsule-teach-shelf").fetchSemanticsNode()
        val inputAfter = compose.onNodeWithTag("capsule-input").fetchSemanticsNode()
        assertEquals("收起教 Claire", opened.config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription])
        assertTrue("after expanding the arrow is still above the input bar", opened.boundsInRoot.bottom <= inputAfter.boundsInRoot.top)
        compose.onNodeWithTag("capsule-teach-shelf").performClick()
        compose.onNodeWithText("教 Claire").assertDoesNotExist()
    }

    @Test fun idleShelfOffersVoiceChat() {
        show(CardView.Chat("c1", title = "买牙膏"), thread = history(1))
        compose.onNodeWithText("语音聊天").assertDoesNotExist()
        compose.onNodeWithTag("capsule-teach-shelf").performClick()
        compose.onNodeWithText("语音聊天").assertIsDisplayed().performClick()
        assertEquals(listOf("voiceChat"), calls)
    }

    @Test fun runningChatDoesNotShowTeachShelf() {
        val r = run("a", TaskState.Running(1, "n"), CapsuleKind.Running)
        show(chat(r), thread = CapsuleThreads.build(emptyList(), CapsuleThreads.PAGE, listOf(r)))
        compose.onAllNodesWithTag("capsule-teach-shelf").assertCountEquals(0)
        compose.onNodeWithText("教 Claire").assertDoesNotExist()
    }

    @Test fun idleCardContinuesOrStartsFresh() {
        show(CardView.Chat("c1", title = "买牙膏"), thread = history(1))
        compose.onNodeWithText("接着「买牙膏」聊").assertIsDisplayed()
        compose.onNodeWithText("回答 1 第 1 行").assertIsDisplayed()
        compose.onNodeWithText(CapsuleCards.HINT_START).assertIsDisplayed()
        compose.onNodeWithText(CardCopy.NEW_CHAT).performClick()
        assertEquals(listOf("fresh true"), calls)
    }

    @Test fun heldResumesOnlyWhenResumable() {
        show(detail(run("a", TaskState.Held(com.wangzan101.mika.core.agent.GateReason("x", "等你打开定位", resumable = true)), CapsuleKind.Held)))
        compose.onNodeWithText("等你打开定位").assertIsDisplayed()
        compose.onNodeWithText(CardCopy.RESUME).performClick()
        assertEquals(listOf("resume a"), calls)
    }

    @Test fun finishedRunIsAChatCardWithItsConversation() {
        val r = run("a", TaskState.Succeeded("牙膏下单了"), CapsuleKind.Done)
        val past = listOf(PastTurn(com.wangzan101.mika.core.agent.Turn("早", "早上好", "succeeded", runId = "r0")))
        val thread = CapsuleThreads.build(past, CapsuleThreads.PAGE, listOf(r)) { listOf("购物") }
        show(chat(r), thread = thread)
        // The earlier back-and-forth, the line about the operated app and the full answer are all present; no steps and no screen preview
        // The conversation title is the first line and the bubble below is the same text: both are present
        compose.onAllNodesWithText("早").assertCountEquals(2)
        compose.onNodeWithText("✓ 购物 · 办好了").assertIsDisplayed()
        compose.onNodeWithText("牙膏下单了").assertIsDisplayed()
        compose.onAllNodesWithTag("capsule-screen").assertCountEquals(0)
        compose.onNodeWithText(CapsuleCards.HINT_CONTINUE).assertIsDisplayed()
        compose.onNodeWithText(CardCopy.BACK_TO_MIKA).performClick()
        assertEquals(listOf("open c-a"), calls)
    }

    @Test fun chatInputOpensOnTapAndStreamingChatHasStop() {
        val r = run("a", TaskState.Running(1, "n"), CapsuleKind.Running)
        show(chat(r), thread = CapsuleThreads.build(emptyList(), CapsuleThreads.PAGE, listOf(r)))
        compose.onNodeWithTag("capsule-input").performClick()
        compose.onNodeWithContentDescription(CardCopy.STOP).performClick()
        assertEquals(listOf("openInput", "stop a"), calls)
    }

    @Test fun chatQuestionKeepsItsOptionsPinned() {
        val r = run("a", TaskState.WaitingInput("要哪个？", listOf("大的", "小的")), CapsuleKind.NeedAnswer)
        show(chat(r), thread = CapsuleThreads.build(emptyList(), CapsuleThreads.PAGE, listOf(r)))
        compose.onNodeWithText("要哪个？").assertIsDisplayed()
        compose.onNodeWithText("小的").performClick()
        assertEquals(listOf("answer a 小的"), calls)
    }

    @Test fun chatOpensAtTheLatestQuestionAndScrollsUpForEarlier() {
        show(CardView.Chat("c1", title = "问题 1"), thread = history(25, lines = 3))
        // Opens reading from the newest question
        compose.onNodeWithText("问题 25").assertIsDisplayed()
        compose.onNodeWithText("问题 20").assertIsNotDisplayed()
        repeat(30) { compose.onNodeWithTag("capsule-thread").performTouchInput { swipeDown() } }
        compose.onNodeWithText("问题 16").assertIsDisplayed()
        // Scrolled to the top: load more of the earlier history
        compose.runOnIdle { assertTrue("earlier" in calls) }
    }

    @Test fun longTakeoverScrollsWhileButtonsStay() {
        val body = (1..60).joinToString("\n") { "第 $it 行" }
        show(detail(run("a", TaskState.WaitingTakeover("login", body), CapsuleKind.NeedTakeover, "需要你：登录")))
        // The whole explanation is a single text node: judge by height and position — the scroll area is shorter than the full text, so swiping up moves the text while the buttons stay put
        val area = compose.onNodeWithTag("capsule-scroll").fetchSemanticsNode()
        val text = compose.onNodeWithText("第 1 行", substring = true).fetchSemanticsNode()
        org.junit.Assert.assertTrue("capped: ${area.size.height} < ${text.size.height}", area.size.height < text.size.height)
        val before = text.positionInRoot.y
        repeat(3) { compose.onNodeWithTag("capsule-scroll").performTouchInput { swipeUp() } }
        val after = compose.onNodeWithText("第 1 行", substring = true).fetchSemanticsNode().positionInRoot.y
        org.junit.Assert.assertTrue("scrolled: $after < $before", after < before)
        compose.onNodeWithText(CardCopy.TAKE_OVER).assertIsDisplayed()
        compose.onNodeWithText(CardCopy.SEE_PROCESS).assertIsDisplayed()
    }

    @Test fun chatCardIsFixedWhateverTheContent() {
        var card by mutableStateOf<CardView>(chat(run("b", TaskState.Succeeded("好了"), CapsuleKind.Done)))
        var thread by mutableStateOf(history(1))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, null, false, actions, { null }, thread = thread, height = 420.dp) }
        }
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(420.dp)
        // However long the back-and-forth, the height stays the same and it scrolls inside
        thread = history(10, lines = 8)
        compose.waitForIdle()
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(420.dp)
        // Same for an idle card
        card = CardView.Chat(null)
        thread = ChatThread(emptyList())
        compose.waitForIdle()
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(420.dp)
    }

    @Test fun taskCardWrapsItsContentAndTheChatCardDoesNot() {
        val height = 520.dp
        val frame = Bitmap.createBitmap(36, 80, Bitmap.Config.ARGB_8888)
        val r = run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "在搜")
        var card by mutableStateOf<CardView>(CardView.Detail(r, CardContent.Working(listOf("打开购物", "搜索牙膏", "选第一个"))))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, frame, false, actions, { null }, thread = history(1), height = height) }
        }
        val task = compose.onNodeWithTag("capsule-card").fetchSemanticsNode().size.height
        val screen = compose.onNodeWithTag("capsule-screen").fetchSemanticsNode().size.height
        val full = with(compose.density) { height.roundToPx() }
        // Leave room for the height of the guidance/queueing controls so this case stays below the cap: verifies the task card shrinks to its content while the chat card keeps a fixed height
        assertTrue("wraps: $task < $full", task < full)
        assertTrue("fits the preview: $task > $screen", task > screen)
        compose.onNodeWithTag("capsule-screen").assertIsDisplayed()
        compose.onNodeWithText("选第一个").assertIsDisplayed()
        // Switch to a chat card: fixed height
        card = chat(run("a", TaskState.Succeeded("好了"), CapsuleKind.Done))
        compose.waitForIdle()
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(height)
    }

    @Test fun longTaskCardIsCappedAndScrollsWhileButtonsStay() {
        val frame = Bitmap.createBitmap(36, 80, Bitmap.Config.ARGB_8888)
        val body = (1..60).joinToString("\n") { "第 $it 行" }
        show(detail(run("a", TaskState.WaitingTakeover("login", body), CapsuleKind.NeedTakeover, "需要你：登录")), preview = frame)
        // However long the explanation, the task card is at most as tall as the chat card; the text scrolls inside and the buttons and link remain visible
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(420.dp)
        val area = compose.onNodeWithTag("capsule-scroll").fetchSemanticsNode()
        val text = compose.onNodeWithText("第 1 行", substring = true).fetchSemanticsNode()
        assertTrue("capped: ${area.size.height} < ${text.size.height}", area.size.height < text.size.height)
        compose.onNodeWithTag("capsule-screen").assertIsDisplayed()
        compose.onNodeWithText(CardCopy.TAKE_OVER).assertIsDisplayed()
        compose.onNodeWithText(CardCopy.SEE_PROCESS).assertIsDisplayed()
    }

    /** Not being drawn in the list (scrolled out, not laid out) also counts as not visible. */
    private fun offScreen(text: String) {
        val nodes = compose.onAllNodesWithText(text)
        if (nodes.fetchSemanticsNodes().isNotEmpty()) nodes[0].assertIsNotDisplayed()
    }

    private fun working(n: Int) = CardView.Detail(
        run("a", TaskState.Running(3, "n", "tap"), CapsuleKind.Running, "在搜"), CardContent.Working((1..n).map { "第 $it 步" }),
    )

    @Test fun manyStepsFitThePreviewHeightNewestAtTheBottom() {
        val height = 520.dp
        val frame = Bitmap.createBitmap(36, 80, Bitmap.Config.ARGB_8888)
        var card by mutableStateOf<CardView>(working(30))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, frame, false, actions, { null }, height = height) }
        }
        val steps = compose.onNodeWithTag("capsule-steps").fetchSemanticsNode().size.height
        val screen = compose.onNodeWithTag("capsule-screen").fetchSemanticsNode().size.height
        val cardHeight = compose.onNodeWithTag("capsule-card").fetchSemanticsNode().size.height
        // However many steps there are, they are as tall as the screen preview; the height cap leaves enough room for the fixed controls, so the steps do not stretch the card to the cap
        assertTrue("steps fit the preview: $steps <= $screen", steps <= screen + 1)
        assertTrue("card not stretched: $cardHeight < ${with(compose.density) { height.roundToPx() }}", cardHeight < with(compose.density) { height.roundToPx() })
        compose.onNodeWithText("第 30 步").assertIsDisplayed()
        offScreen("第 1 步")
        // A new step arrives while at the bottom: follow it
        card = working(31)
        compose.waitForIdle()
        compose.onNodeWithText("第 31 步").assertIsDisplayed()
        compose.onNodeWithTag("capsule-card").assertHeightIsEqualTo(with(compose.density) { cardHeight.toDp() })
        // Scroll up to the earliest step
        repeat(10) { compose.onNodeWithTag("capsule-steps").performTouchInput { swipeDown() } }
        compose.onNodeWithText("第 1 步").assertIsDisplayed()
        // A new step arrives while browsing: do not drag back to the bottom
        card = working(32)
        compose.waitForIdle()
        compose.onNodeWithText("第 1 步").assertIsDisplayed()
        offScreen("第 32 步")
    }

    @Test fun stepsWithoutPreviewAreCappedAndFewStaysCompact() {
        var card by mutableStateOf<CardView>(working(2))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleCard(card, null, false, actions, { null }, height = 420.dp) }
        }
        val few = compose.onNodeWithTag("capsule-card").fetchSemanticsNode().size.height
        compose.onNodeWithText("第 1 步").assertIsDisplayed()
        card = working(40)
        compose.waitForIdle()
        val many = compose.onNodeWithTag("capsule-card").fetchSemanticsNode().size.height
        val full = with(compose.density) { 420.dp.roundToPx() }
        // With few steps the height follows the content; with many it grows only to the screen-preview height derived from the main screen's aspect ratio, never to the maximum
        assertTrue("few is compact: $few < $many", few < many)
        assertTrue("many is capped: $many < $full", many < full)
        compose.onNodeWithText("第 40 步").assertIsDisplayed()
        offScreen("第 1 步")
    }

    @Test fun chatAnswerRendersMarkdownInsideTheCard() {
        val answer = "## 结果\n\n**牙膏**已下单\n\n| 商品 | 数量 | 单价 | 小计 | 备注 |\n|---|---|---|---|---|\n| 薄荷味牙膏特大号家庭装 | 2 | ¥9.95 | ¥19.9 | 明天送到家门口 |\n\n[订单详情](https://example.com/order)"
        val past = listOf(PastTurn(com.wangzan101.mika.core.agent.Turn("买牙膏", answer, "succeeded", runId = "r1")))
        show(CardView.Chat("c1", title = "买牙膏"), thread = CapsuleThreads.build(past, CapsuleThreads.PAGE, emptyList()))
        // Markup is no longer exposed verbatim
        compose.onNodeWithText("结果").assertExists()
        compose.onNodeWithText("牙膏已下单").assertExists()
        compose.onNodeWithText("薄荷味牙膏特大号家庭装").assertExists()
        compose.onAllNodesWithText("|", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("**", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("订单详情", substring = true).assertCountEquals(1)
        // A wide table scrolls left and right inside its own area; the card does not get wider
        val card = compose.onNodeWithTag("capsule-card").fetchSemanticsNode().boundsInRoot
        val thread = compose.onNodeWithTag("capsule-thread").fetchSemanticsNode().boundsInRoot
        assertTrue("thread inside card: $thread in $card", thread.right <= card.right && thread.left >= card.left)
    }

    @Test fun headerReportsItsHandleAndDraggingItTapsNothing() {
        var handle: android.graphics.Rect? = null
        val card = detail(run("a", TaskState.Running(1, "n"), CapsuleKind.Running, "在搜"))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                CapsuleCard(card, null, false, actions, { null }, height = 420.dp, onHandle = { handle = it })
            }
        }
        compose.runOnIdle { assertTrue("header bounds reported", handle?.isEmpty == false) }
        // Drag on the header (including a drag that starts on the collapse button): nothing collapses and no button is tapped
        compose.onNodeWithTag("capsule-header").performTouchInput { swipeDown() }
        compose.onNodeWithContentDescription(CardCopy.COLLAPSE).performTouchInput { swipe(center, center + Offset(0f, 300f)) }
        compose.runOnIdle { assertEquals(emptyList<String>(), calls) }
    }

    @Test fun steerInputOffersGuidingOrQueueing() {
        show(detail(run("a", TaskState.Running(1, "在搜"), CapsuleKind.Running, "在搜")))
        compose.onNodeWithTag("capsule-follow-up-mode").assertIsDisplayed()
        compose.onNodeWithTag("capsule-follow-up-queue").performClick()
        compose.runOnIdle { assertEquals(listOf("mode:Queue"), calls) }
    }

    @Test fun queuedLineOpensTheChatWhenThereAreQueuedFollowUps() {
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                CapsuleCard(
                    detail(run("a", TaskState.Running(1, "在搜"), CapsuleKind.Running, "在搜")), null, false, actions, { null },
                    height = 420.dp, queuedCount = 2, onQueued = { calls += "openQueued" },
                )
            }
        }
        compose.onNodeWithTag("capsule-queued").assertIsDisplayed().performClick()
        compose.onNodeWithText("排队中 · 2 件", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("openQueued"), calls) }
    }

    /** The power button in the header only asks once and does not quit directly; inside the confirmation card "quit" and "cancel" each take their own path. */
    @Test fun quitIsConfirmedInsideTheCard() {
        show(detail(run("a", TaskState.Running(1, "n"), CapsuleKind.Running, "在搜")))
        compose.onNodeWithContentDescription("完全退出 Claire").performClick()
        compose.runOnIdle { assertEquals(listOf("askQuit"), calls) }
    }

    @Test fun quitCardOffersQuitAndCancel() {
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { QuitCard(actions, 420.dp) }
        }
        compose.onNodeWithText("完全退出 Claire").assertIsDisplayed()
        compose.onNodeWithText("下次打开 Claire 就回来。", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("capsule-quit").performClick()
        compose.onNodeWithTag("capsule-quit-cancel").performClick()
        compose.runOnIdle { assertEquals(listOf("quit", "cancelQuit"), calls) }
    }

    @Test fun chatThreadShowsScrollToBottomWhenAwayFromTailAndReturnsOnClick() {
        val longAnswer = (1..30).joinToString("\n\n") { "胶囊第 $it 段回答内容" } + "\n\n胶囊结尾标记-CAPSULE_END"
        val past = listOf(
            PastTurn(com.wangzan101.mika.core.agent.Turn("第一句", "第一条回复", "succeeded", runId = "r1")),
            PastTurn(com.wangzan101.mika.core.agent.Turn("第二句", longAnswer, "succeeded", runId = "r2")),
        )
        show(CardView.Chat("c1", title = "第二句"), thread = CapsuleThreads.build(past, CapsuleThreads.PAGE, emptyList()))

        // On open the anchor sits at the start of the last question, and the answer is taller than the card, so the scroll-to-bottom button appears right away
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("capsule-scroll-to-bottom").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("capsule-scroll-to-bottom").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("capsule-scroll-to-bottom").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("CAPSULE_END", substring = true).assertIsDisplayed()

        // It appears again after scrolling up through the history
        repeat(3) { compose.onNodeWithTag("capsule-thread").performTouchInput { swipeDown() } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("capsule-scroll-to-bottom").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("capsule-scroll-to-bottom").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("capsule-scroll-to-bottom").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("CAPSULE_END", substring = true).assertIsDisplayed()
    }
}
