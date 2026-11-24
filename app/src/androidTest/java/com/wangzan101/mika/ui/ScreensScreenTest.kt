package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.chat.ui.ScreenRow
import com.wangzan101.mika.chat.ui.ScreensContent
import com.wangzan101.mika.chat.ui.ScreensCopy

/** The "background screens" page: empty state, one row per screen, and opening a small preview (fake data only, no real screens created). */
@RunWith(AndroidJUnit4::class)
class ScreensScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(rows: List<ScreenRow>) = compose.setContent {
        MikaTheme {
            ScreensContent(rows, onBack = { calls += "back" }, onOpenConversation = { calls += "open $it" })
        }
    }

    private fun row(displayId: Int, key: String = "k$displayId") =
        ScreenRow(key, "conv-$key", "打开设置看一下电池", displayId, TaskState.Running(1, "点了一下"), foreground = "设置")

    @Test fun emptyPageSaysNoTaskIsRunning() {
        show(emptyList())
        compose.onNodeWithText(ScreensCopy.EMPTY).assertIsDisplayed()
    }

    /** One card per task: what it is doing, which app it is using, and its state are all present; tapping the row returns to the conversation it belongs to. */
    @Test fun oneCardPerTaskOpensItsConversation() {
        show(listOf(row(12), row(-1, "queued")))
        compose.onNodeWithText("正在用设置").assertIsDisplayed()
        compose.onNodeWithText("还没打开 App").assertIsDisplayed()
        compose.onAllNodesWithText("打开设置看一下电池").assertCountEquals(2)
        compose.onAllNodesWithText("正在执行").assertCountEquals(2)
        compose.onNodeWithText("正在用设置").performClick()
        assertEquals(listOf("open conv-k12"), calls)
    }

    /** The entry whose screen is not built yet has no "view screen": there is nothing to show, so it does not offer an entry point that does nothing when tapped. */
    @Test fun aScreenThatIsNotBuiltYetHasNoPreview() {
        show(listOf(row(12), row(-1, "queued")))
        compose.onNodeWithTag("screens-preview-queued").assertDoesNotExist()
        compose.onNodeWithTag("screens-preview-k12").performScrollTo().assertIsDisplayed()
    }

    /** Tap to expand, tap again to collapse: the wording on the button changes accordingly. */
    @Test fun previewToggles() {
        show(listOf(row(12)))
        compose.onNodeWithTag("screens-preview-k12").performScrollTo().performClick()
        compose.onNodeWithText(ScreensCopy.HIDE_PREVIEW).assertExists()
        compose.onNodeWithTag("screens-preview-k12").performClick()
        compose.onNodeWithText(ScreensCopy.PREVIEW).assertExists()
        assertEquals(emptyList<String>(), calls)
    }
}
