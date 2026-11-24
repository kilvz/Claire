package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.ChineseUi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Drawer search: entering a word from the title or a word from the body lists the conversation; clearing it returns to the entry point and the original list; tapping a result opens that conversation.
 * Does not need the on-device task environment.
 */
@RunWith(AndroidJUnit4::class)
class ChatSearchTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ConversationStore(File(context.cacheDir, "chat-search-test-" + UUID.randomUUID()))

    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) {}
        override fun cancel() {}
        override fun resume() {}
        override fun takeOver() {}
        override fun handBack() {}
        override fun finishedMyself() {}
        override fun skip() {}
        override fun answer(text: String) {}
        override fun approve(key: Int) {}
        override fun decline(key: Int) {}
    }

    private fun vm() = ChatViewModel(
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions, store,
        io = Dispatchers.Main.immediate,
    )

    private fun show(vm: ChatViewModel) {
        compose.setContent {
            ChatWithDrawer(
                vm, Prerequisites.Check(emptyList()), reduceMotion = true,
                conversations = { store.list() }, conversationChanges = store.changes, conversationTitle = store::title,
                search = store.search,
                onSettings = {}, reopen = true,
            ) { open ->
                ChatScreen(vm, Prerequisites.Check(emptyList()), MutableStateFlow(null), MutableStateFlow(false), reduceMotion = true,
                    onOpenDrawer = open, openSettings = {})
            }
        }
        compose.waitForIdle()
    }

    private fun awaitRow(title: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("conversation-row") and hasText(title)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun searchesTitleAndBodyClearsAndOpensTheResult() {
        store.append("a", Turn("订票", "验证码一直过不去", "failed"))
        store.append("b", Turn("查天气", "明天晴", "succeeded"))
        val vm = vm()
        show(vm)

        // Title match
        compose.onNodeWithTag("drawer-search").performClick().performTextInput("订票")
        awaitRow("订票")
        compose.onNodeWithText("搜索结果").assertExists()

        // Body match: the title is the ticket-booking one, but the search term comes from the answer
        compose.onNodeWithTag("drawer-search").performTextReplacement("验证码")
        awaitRow("订票")
        compose.onNode(hasTestTag("conversation-row") and hasText("订票"))
            .assert(hasText("验证码", substring = true))

        // Nothing matches
        compose.onNodeWithTag("drawer-search").performTextReplacement("没有这个词")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("没有找到「没有这个词」").fetchSemanticsNodes().isNotEmpty()
        }

        // Clear: the entry point comes back and the search field's clear button disappears
        compose.onNodeWithTag("drawer-search-clear").performClick()
        compose.onNodeWithTag("drawer-search-clear").assertDoesNotExist()
        compose.onNodeWithContentDescription("新对话").assertExists()

        // Search for another conversation, then tap the result to open it
        compose.onNodeWithTag("drawer-search").performTextReplacement("天气")
        awaitRow("查天气")
        compose.onNode(hasTestTag("conversation-row") and hasText("查天气")).performClick()
        compose.waitUntil(5_000) { vm.ui.value.conversationId == "b" }
    }
}
