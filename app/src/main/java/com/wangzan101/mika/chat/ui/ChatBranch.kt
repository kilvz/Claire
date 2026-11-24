package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.screen.user.UserScreenMessage

/**
 * Whether turn [turn] (0-based) can be used as a branch point right now: the turn is in range, that turn has finished, and the conversation is not blocked ([blocked]: a task is running or queued,
 * deletion is in progress, the composer is busy, etc., computed by the caller). Whether the menu shows "Branch from here" and whether tapping it does anything both depend only on this.
 */
internal fun canBranchAt(exchanges: List<Exchange>, turn: Int, blocked: Boolean): Boolean =
    !blocked && turn in exchanges.indices && exchanges[turn].status.branchable

/** Whether turn [turn] can "Ask again from here" right now: it can be branched ([canBranchAt]) and the user's message in that turn is not empty. Both the menu and the action depend only on this. */
internal fun canRetryAt(exchanges: List<Exchange>, turn: Int, blocked: Boolean): Boolean =
    canBranchAt(exchanges, turn, blocked) && exchanges[turn].goal.isNotBlank()

/** The turn number in a message key `<prefix><turn>`; null for messages of another kind. */
private fun turnOf(key: String, prefix: String): Int? =
    if (key.startsWith(prefix)) key.removePrefix(prefix).toIntOrNull() else null

/**
 * Whether the long-pressed message can be a branch point: returns the turn only for a Claire reply (`r<turn>`) when [canBranchAt] holds, otherwise null.
 * The user's own messages, steers, status cards and progress lines do not count.
 */
internal fun branchableTurn(exchanges: List<Exchange>, key: String, blocked: Boolean): Int? =
    turnOf(key, "r")?.takeIf { canBranchAt(exchanges, it, blocked) }

/** This turn has finished (succeeded, failed or was stopped all count); running, waiting for you and queued do not. */
internal val ExchangeStatus.branchable: Boolean
    get() = when (this) {
        is ExchangeStatus.Done, is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> true
        else -> false
    }

/** Result of "Ask again from here": the new conversation id, the prefilled text, and whether the original message carried attachments/screen that must be re-added. */
data class BranchRetry(
    val conversationId: String,
    val prefill: String,
    val needsReattach: Boolean,
    val failures: List<com.wangzan101.mika.chat.RetryAttachmentResult> = emptyList(),
    val screenNeedsReattach: Boolean = false,
)

/**
 * Which of the user's own messages was long-pressed to "Ask again from here": returns the turn for `u<turn>` when [canRetryAt] holds, otherwise null.
 * Steers (`m…`), replies and status cards do not count.
 */
internal fun retryableTurn(exchanges: List<Exchange>, key: String, blocked: Boolean): Int? =
    turnOf(key, "u")?.takeIf { canRetryAt(exchanges, it, blocked) }

/** The text from the original message that can be asked again: strips the page note, quotes, attachment lines and the screen line; empty when only attachments/screen were sent. */
internal fun retryText(raw: String): String =
    UserScreenMessage.parse(AttachmentMessage.parse(QuoteMessage.words(AttachedPage.words(raw))).words).words.trim()

/** The original message carried attachments or screen content: they are not carried over when asking again, so the UI uses this to prompt for re-adding them. */
internal fun needsReattach(raw: String): Boolean {
    val attached = AttachmentMessage.parse(QuoteMessage.words(AttachedPage.words(raw)))
    return attached.hasAttachments || UserScreenMessage.parse(attached.words).hasScreen
}
