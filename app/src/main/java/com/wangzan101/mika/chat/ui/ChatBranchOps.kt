package com.wangzan101.mika.chat.ui
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.BranchOrigin
import com.wangzan101.mika.chat.RetryAttachmentResult
import com.wangzan101.mika.chat.RetryAttachmentStatus
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.cap.sandbox.AttachmentFailureReason
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.agent.QuoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.screen.user.UserScreenMessage

/** Branch from a turn, and ask again from a single message. */
internal class ChatBranchOps(private val vm: ChatViewModel, private val session: ChatSession) {
    private val _ui = session._ui
    private val deleted get() = vm.deleted
    private val deleting = session.deleting
    private val _branchCopy = session._branchCopy
    private val store = vm.store
    private val activeConversations = vm.activeConversations
    private val clock = vm.clock
    private val branchWorkspace = vm.branchWorkspace
    private val branchTitle = vm.branchTitle
    private val io = vm.io

    /** This conversation on screen cannot be branched right now: not saved yet, a task is running or queued, it was deleted or is being deleted, or the composer is busy. */
    private fun branchBlocked(): Boolean {
        val id = _ui.value.conversationId ?: return true
        return id in activeConversations.value || id in deleted.value || id in deleting || session.busy()
    }

    /** Returns the turn when the long-pressed message [key] can "Branch from here"; the menu shows on this basis and [branchFrom] uses the same check. */
    fun branchTurn(key: String): Int? = branchableTurn(_ui.value.exchanges, key, branchBlocked())

    /** Returns the turn when the long-pressed message [key] can "Ask again from here"; the menu shows on this basis and [branchBefore] uses the same check. */
    fun retryTurn(key: String): Int? = retryableTurn(_ui.value.exchanges, key, branchBlocked())

    /** Clears the notice about the workspace copy from the last branch. */
    fun clearBranchCopy() {
        _branchCopy.value = null
    }

    /**
     * Branch from turn [throughTurn] (0-based, inclusive) of the current conversation: copy the turns and the title/page source, then copy the workspace inbox/, and switch to the new conversation on success.
     * Returns the new conversation id; null when [canBranchAt] does not hold (a task is running or queued, deletion is in progress, the composer is busy, the turn is out of range, or this turn has not finished) or the copy fails.
     * The result of the workspace copy (over the limit, etc.) is stored in [branchCopy] and is not treated as a failure.
     */
    suspend fun branchFrom(throughTurn: Int): String? =
        branchInto(throughTurn, afterTurn = true, allowed = ::canBranchAt)

    /**
     * "Ask again" from the user message in turn [turn] of the current conversation: copy the turns before it and return the new conversation id and the text to prefill, without sending automatically.
     * The original message's attachments/screen are not carried into the composer; [BranchRetry.needsReattach] tells the UI to prompt for re-adding them.
     * Returns null when [canRetryAt] does not hold (same as [branchFrom], plus this turn's user message being empty) or the copy fails.
     */
    suspend fun branchBefore(turn: Int): BranchRetry? {
        val exchanges = _ui.value.exchanges
        val raw = exchanges.getOrNull(turn)?.goal.orEmpty()
        fun attachmentPaths(text: String) = AttachmentLines.parse(QuoteMessage.words(AttachedPage.words(text))).files.map { it.first }
        val paths = attachmentPaths(raw).distinct()
        val historyPaths = exchanges.take(turn.coerceAtLeast(0)).flatMap { attachmentPaths(it.goal) }.filterNot { it in paths }.distinct()
        var copied = emptyList<RetryAttachmentResult>()
        val targetId = branchInto(turn, afterTurn = false, allowed = ::canRetryAt, retryPaths = paths + historyPaths) { copied = it } ?: return null
        val restored = copied.take(paths.size).filter { it.status == RetryAttachmentStatus.COPIED && it.sizeBytes != null }
        val attachments = restored.map {
            PendingAttachment("workspace:$targetId:${it.path}", it.path.substringAfterLast('/'), it.sizeBytes!!,
                workspaceConversationId = targetId, workspacePath = it.path)
        }
        val (accepted, _) = AttachTray.add(emptyList(), attachments)
        val failures = copied.filter { it.status != RetryAttachmentStatus.COPIED || it.sizeBytes == null } +
            restored.drop(accepted.size).map { it.copy(status = RetryAttachmentStatus.OVER_LIMIT) }
        val prefill = retryText(raw)
        session.updateDraft(targetId) {
            it.copy(input = prefill, attachments = accepted,
                rejected = if (_ui.value.conversationId == targetId) emptyList() else failures.map { failure ->
                    AttachmentFailure(failure.path.substringAfterLast('/'), AttachmentFailureReason.IO_ERROR)
                })
        }
        val screen = UserScreenMessage.parse(AttachmentMessage.parse(QuoteMessage.words(AttachedPage.words(raw))).words).hasScreen
        return BranchRetry(targetId, prefill, screen || failures.isNotEmpty(), failures, screenNeedsReattach = screen)
    }

    /**
     * Shared by [branchFrom] and [branchBefore]: when [allowed] holds, create a new conversation, copy up to turn [turn] (inclusive when [afterTurn], otherwise only up to before it),
     * add the suffix to the title, record the branch origin, copy the workspace inbox/, then switch to the new conversation. Returns the new conversation id; null when not allowed or the copy fails, and it does not switch.
     */
    private suspend fun branchInto(
        turn: Int, afterTurn: Boolean, allowed: (List<Exchange>, Int, Boolean) -> Boolean,
        retryPaths: List<String>? = null, onRetryCopied: (List<RetryAttachmentResult>) -> Unit = {},
    ): String? {
        session.refreshParallel()
        _branchCopy.value = null
        val ui = _ui.value
        val sourceId = ui.conversationId ?: return null
        if (!allowed(ui.exchanges, turn, branchBlocked())) return null
        val targetId = store.newConversation()
        val base = withContext(io) { store.title(sourceId) }?.takeIf { it.isNotBlank() }
            ?: ChatCopy.title(ui.exchanges).ifBlank { tr(R.string.chat_new) }
        val origin = BranchOrigin(sourceId, turn, afterTurn, at = clock())
        val created = withContext(io) {
            if (afterTurn) store.branch(sourceId, targetId, turn, branchTitle(base), origin)
            else store.branchBefore(sourceId, targetId, turn, branchTitle(base), origin)
        }
        if (!created) return null
        if (!retryPaths.isNullOrEmpty()) {
            val failed = retryPaths.map { RetryAttachmentResult(it, RetryAttachmentStatus.FAILED) }
            val results = try {
                branchWorkspace?.copyInboxForRetry(sourceId, targetId, retryPaths) ?: failed
            } catch (e: CancellationException) { throw e } catch (e: Exception) { failed }
            onRetryCopied(if (results.map { it.path } == retryPaths) results else failed)
        } else {
            _branchCopy.value = try { branchWorkspace?.copyInbox(sourceId, targetId) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        }
        if (_ui.value.conversationId == sourceId && !branchBlocked()) vm.chats.openConversation(targetId)
        return targetId
    }
}
