package com.wangzan101.mika.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.PageScrolls
import com.wangzan101.mika.ui.components.PlainListTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A page shown through a page switcher returns from a subpage to its previous position: the structure matches the settings page,
 * with the switcher holding [PageScrolls] and each page taking its own from it.
 * Every row on the list page carries a note: in a compact list the note moves below the card as a footnote and appears one frame late,
 * so the first frame of the page is shorter and a carried position near the bottom gets clipped.
 */
@RunWith(AndroidJUnit4::class)
class PageScrollsDeviceTest {
    @get:Rule val compose = createComposeRule()

    private enum class Page { List, Child }

    private var page by mutableStateOf(Page.List)

    @Before fun startOnTheList() {
        page = Page.List
    }

    private fun show(scrolls: @Composable () -> PageScrolls) {
        compose.setContent { Screens(scrolls()) }
    }

    @Composable private fun Screens(scrolls: PageScrolls) {
        PlainListTheme {
            when (page) {
                Page.List -> PageScaffold("列表", onBack = {}, scrollState = scrolls.of(Page.List)) {
                    GroupCard {
                        repeat(ROWS) { i ->
                            key(i) { ListRow(null, "第 $i 行", note = "第 $i 行的说明", onClick = { page = Page.Child }) }
                        }
                    }
                    Text("列表结尾", Modifier.testTag("end"))
                }
                Page.Child -> PageScaffold("子页", onBack = { page = Page.List }) {
                    TextButton(onClick = { page = Page.List }) { Text("返回列表") }
                }
            }
        }
    }

    /** The row's vertical screen coordinate. A row scrolled off screen is clipped to 0, so first confirm it is really on screen, or both sides could be 0 and pass trivially. */
    private fun topOf(row: String): Float =
        compose.awaitOne(row).fetchSemanticsNode().boundsInRoot.top.also { assertTrue("$row 要在屏幕里", it > 0f) }

    private fun roundTrip(row: String) {
        val before = topOf(row)
        compose.awaitOne(row).performClick()
        compose.awaitOne("返回列表").assertIsDisplayed()
        compose.awaitOne("返回列表").performClick()
        compose.waitForIdle()
        assertEquals(before, topOf(row), 1f)
    }

    // Parked at the very bottom: on return the card footnote is not there in the first frame, the carried position is clipped by the smaller limit, and it must wait for the limit to grow back before being restored
    @Test fun aPageScrolledToTheBottomComesBackWhereItWas() {
        show { PageScrolls() }
        compose.onNodeWithTag("end").performScrollTo()
        compose.waitForIdle()
        roundTrip("第 ${ROWS - 1} 行")
    }

    @Test fun aPageScrolledToTheMiddleComesBackWhereItWas() {
        show { PageScrolls() }
        compose.awaitOne("第 ${ROWS / 2} 行").performScrollTo()
        compose.waitForIdle()
        roundTrip("第 ${ROWS / 2} 行")
    }

    // Page rebuild after changing the UI language or having the process reclaimed: positions are saved through PageScrolls.Saver and restored after the rebuild. Parked at the bottom, the first frame after the rebuild is clipped in the same way
    @Test fun positionsComeBackAfterThePageIsRebuilt() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Screens(rememberSaveable(saver = PageScrolls.Saver) { PageScrolls() }) }
        compose.onNodeWithTag("end").performScrollTo()
        compose.waitForIdle()
        val row = "第 ${ROWS - 1} 行"
        val before = topOf(row)

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(before, topOf(row), 1f)
    }

    private companion object {
        /** Long enough to need several screens of scrolling in phone portrait. */
        const val ROWS = 30
    }
}
