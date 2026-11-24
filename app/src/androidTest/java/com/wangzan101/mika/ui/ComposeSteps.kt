package com.wangzan101.mika.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.AppLanguage

/**
 * The UI-test steps that don't depend on state left behind by the previous test: each test opens a new activity, but the previous
 * test's windows (activities, dialogs) take time to leave. Before clicking, wait until what this test wants to click is really on screen
 * and there is exactly one of it, so the click won't hit something in an old window or something not laid out yet.
 */
internal fun ComposeContentTestRule.awaitOne(text: String, inDialog: Boolean = false, timeoutMs: Long = 5_000): SemanticsNodeInteraction {
    val matcher = if (inDialog) hasText(text) and hasAnyAncestor(isDialog()) else hasText(text)
    waitUntil(timeoutMs) { onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
    return onNode(matcher, useUnmergedTree = true)
}

/** A button in a dialog: wait for the dialog to appear, then click. */
internal fun ComposeContentTestRule.clickInDialog(text: String) {
    awaitOne(text, inDialog = true).performClick()
    waitForIdle()
}

/**
 * Type [text] into the input field of the chat page, then tap send. Sending uses the button's own click action rather than a screen coordinate: while typing, the field takes focus and the IME starts to come up,
 * so the input bar moves up with the keyboard; a coordinate tap samples a position mid-animation and often lands outside the input bar — send is missed,
 * the tap is taken as "a tap outside the input area" that dismisses the keyboard, the text stays in the field, and the task never starts. The button name is looked up in the app's current UI language.
 */
internal fun ComposeContentTestRule.typeAndSend(text: String) {
    onNode(hasSetTextAction()).performTextInput(text)
    onNodeWithContentDescription(appString(R.string.chat_send)).performSemanticsAction(SemanticsActions.OnClick)
    waitForIdle()
}

/** A string looked up in the app's current UI language. */
internal fun appString(id: Int): String =
    AppLanguage.resources(InstrumentationRegistry.getInstrumentation().targetContext).getString(id)
