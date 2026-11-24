package com.wangzan101.mika.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextAlign
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.memory.ui.Row

/**
 * Justified alignment of the answer body: the layout must render correctly on a real device, especially for block quotes — around them is a Row
 * sized to its content, and measuring width must not go through a subcomposition (BoxWithConstraints), or the intrinsic measurements are unavailable and it crashes.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownJustifyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val answerStyle = MikaType.Body.copy(textAlign = TextAlign.Justify)

    @Test
    fun quoteInJustifiedAnswerRenders() {
        val quote = "引用里的中文段落也应该正常显示，不能因为量不到宽度就崩掉。"
        compose.setContent {
            MikaTheme {
                MarkdownText(
                    "开头一段足够长的中文，用来确认两端对齐不影响原来的换行。\n\n> $quote\n\n- 列表项一\n- 列表项二",
                    answerStyle,
                )
            }
        }
        compose.onNodeWithText(quote, substring = true).assertIsDisplayed()
        compose.onNodeWithText("列表项二", substring = true).assertIsDisplayed()
    }

    @Test
    fun headingsAndTablesStayUnjustified() {
        val heading = "小标题"
        val cell = "单元格"
        compose.setContent {
            MikaTheme {
                MarkdownText(
                    "## $heading\n\n| 一 | 二 |\n| --- | --- |\n| $cell | 二 |",
                    answerStyle,
                )
            }
        }
        compose.onNodeWithText(heading, substring = true).assertIsDisplayed()
        compose.onNodeWithText(cell, substring = true).assertIsDisplayed()
    }
}
