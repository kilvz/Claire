package com.wangzan101.mika.capsule

import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.task.ConfirmationCopy
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.chat.ui.Exchange
import com.wangzan101.mika.chat.ui.ExchangeStatus
import com.wangzan101.mika.settings.ui.StatusCopy

/**
 * One item inside the card: [kind]/[line] use the same wording as that item on the capsule; [startedAt] is present while running or holding (used to show the elapsed time);
 * [ahead]/[behind] are how many items come before and after it in the takeover queue (the home screen hands over only one app at a time).
 */
data class CardRun(
    val run: CapsuleRun,
    val kind: CapsuleKind,
    val line: String,
    val startedAt: Long? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
)

/**
 * What the card shows right now: the card tells a single item, it is not a list (see [CapsuleCards.of] for how it is picked). If that item is currently operating an app or web page, it is the task card ([Detail]),
 * otherwise it is the conversation card of that conversation ([Chat]); with no task (when the switch is "always show") it is also a conversation card, without a task.
 */
sealed interface CardView {
    /** Task card: the small screen, the steps, what it needs from you, and the buttons. */
    data class Detail(val run: CardRun, val content: CardContent) : CardView

    /**
     * Conversation card: the back-and-forth of the conversation [conversationId] (see [ChatThread]). When it carries [run], this is the conversation that item lives in and [content] is how it looks right now
     * (buttons are pinned below the conversation when an answer or approval is needed). Without a task it is the idle card: it continues the conversation the user is in right now ([current]), or, when nothing is recorded, the most recent
     * one ([title] being its title); with [fresh] it is a new conversation; when there is no conversation at all, [conversationId] is null and only a new conversation can be started.
     */
    data class Chat(
        val conversationId: String?,
        val run: CardRun? = null,
        val content: CardContent? = null,
        val title: String? = null,
        val fresh: Boolean = false,
        /**
         * The user is in this conversation in the App right now (see [CapsuleIdle]): a finished but still lingering item from another conversation does not take the card over.
         * The fallback "continue the most recent conversation" card has this as false.
         */
        val current: Boolean = false,
    ) : CardView {
        /** Which conversation the card lists and which conversation what you send goes to: null for a new conversation on the idle card (the caller starts a new one). */
        val target: String? get() = if (fresh) null else conversationId
    }

    /** The page left running in the background: a live view, how long until it closes automatically, which conversation it came from, "Open conversation" and "Close page". No input field. */
    data class Kept(val page: KeptPage) : CardView
}

/** Who receives what is typed in the card's bottom input field: a steer into the running task, an answer to Claire's question, a continuation in the same conversation, or the start of a conversation while idle. */
enum class CardInputKind { Steer, Answer, Continue, Start }

data class CardInput(val hint: String, val kind: CardInputKind)

/** What the detail view shows according to state; every button maps to an existing action of the task service. */
sealed interface CardContent {
    /** Working: the steps performed (at most [CapsuleCards.STEPS]), the last one being the current step; stop sits to the right of the input field. */
    data class Working(val steps: List<String>) : CardContent

    /**
     * Needs you to take over: [need] is the one-line "Needs you: …", [body] explains why (text written by the model; for payments the item, amount and address are appended);
     * when it is queued behind other takeovers, [canTakeOver] is false and [queueLine] states the order.
     */
    data class Takeover(val need: String, val body: String, val queueLine: String?, val canTakeOver: Boolean) : CardContent

    /** Ask you to approve a single paid action (approval covers this one time only): same wording and identifier [key] as the confirmation card on the chat page. */
    data class Confirm(val title: String, val body: String, val key: Int) : CardContent

    /** Claire asks you something: if [options] are given they are listed as buttons; answering without options means typing inside Claire (input in the card is a later step). */
    data class Question(val prompt: String, val options: List<String>) : CardContent

    /** Holding: why it stopped; a "Continue" button is offered when [resumable]. */
    data class Held(val why: String, val resumable: Boolean) : CardContent

    /** Done / failed / stopped: the answer appears in the back-and-forth of the conversation card. */
    data class Result(val kind: CapsuleKind) : CardContent
}

/** Pure logic of the card content: which item to pick, task card or conversation card, what to show for each state, and which steps to take. */
object CapsuleCards {
    /** How many steps to carry while working at most (only to bound memory): that section in the card is as tall as the small screen, and older steps that do not fit are scrolled up into view. */
    const val STEPS = 50

    val QUEUED_AHEAD: String get() = tr(R.string.card_queued_ahead)

    /**
     * Which item to show when expanded (only one, no list, and no switching inside the card; if several things are going on, look at Claire's chat page):
     * first the task of the conversation where something was just sent from the card ([follow]) (in-progress ones first), so the user sees the answer in the card;
     * otherwise the first item in [runs] order — [CapsuleModel.cardRuns] has already ordered the ones that need you (takeover, approval, answer) first, then the in-progress ones,
     * and finally the ones that just finished and have not disappeared yet; if there is none, the page left behind after the task ended is still running in the background ([kept]) and is its card,
     * and if there is none of those either, [idle] (null means collapse). Whether the selected item becomes a task card or a conversation card is decided by [taskCard].
     *
     * When [idle] is the conversation the user is in right now ([CardView.Chat.current]), a result from another conversation that has finished but is still lingering does not take the card over:
     * otherwise, after starting a new conversation on the chat page, opening the capsule would still show the previous conversation's result card.
     */
    fun of(runs: List<CardRun>, follow: String? = null, idle: CardView.Chat? = null, kept: KeptPage? = null): CardView? {
        val pick = follow?.let { f ->
            runs.firstOrNull { it.run.conversationId == f && it.kind.active } ?: runs.firstOrNull { it.run.conversationId == f }
        } ?: runs.firstOrNull()
        // Only override the "finished in another conversation but still lingering" item; when there is no item at all, the left-behind page is still its card (see the kept path)
        val elsewhere = idle?.current == true && pick != null && !pick.kind.active && pick.run.conversationId != idle.conversationId
        if (elsewhere) return idle
        pick ?: return kept?.let(CardView::Kept) ?: idle
        return if (taskCard(pick)) CardView.Detail(pick, content(pick))
            else CardView.Chat(pick.run.conversationId, pick, content(pick))
    }

    /**
     * By the state right now, not by what happened: one that has not finished and is currently operating an app or web page ([CapsuleRun.onScreen]) is a task card;
     * everything else (pure chat, already finished ones, including ones that operated an app and replied once done) is a conversation card.
     */
    fun taskCard(r: CardRun): Boolean = r.kind.active && r.run.onScreen

    val HINT_STEER: String get() = tr(R.string.chat_placeholder_busy)
    val HINT_ANSWER: String get() = tr(R.string.card_hint_answer)
    val HINT_CONTINUE: String get() = tr(R.string.card_hint_continue)
    val HINT_START: String get() = tr(R.string.chat_empty_prompt)

    /** Whether the card has a bottom input field and who it goes to: none when a takeover or approval is needed (deal with that first); an idle card starts a conversation. */
    fun input(card: CardView): CardInput? = when (card) {
        is CardView.Chat -> card.content?.let(::inputFor) ?: CardInput(HINT_START, CardInputKind.Start).takeIf { card.run == null }
        is CardView.Detail -> inputFor(card.content)
        is CardView.Kept -> null
    }

    private fun inputFor(content: CardContent): CardInput? = when (content) {
        is CardContent.Working, is CardContent.Held -> CardInput(HINT_STEER, CardInputKind.Steer)
        is CardContent.Question -> CardInput(HINT_ANSWER, CardInputKind.Answer)
        is CardContent.Result -> CardInput(HINT_CONTINUE, CardInputKind.Continue)
        is CardContent.Takeover, is CardContent.Confirm -> null
    }

    /**
     * Which conversation whatever is typed in the card's input field (along with the captured screen) goes to: for a task card, or a conversation card carrying a task, it is the conversation that item lives in; for an idle card it is the conversation it continues,
     * and null when a new conversation was chosen or there is no conversation at all (the caller starts a new one).
     */
    fun conversationOf(card: CardView): String? = when (card) {
        is CardView.Detail -> card.run.run.conversationId
        is CardView.Chat -> card.run?.run?.conversationId ?: card.target
        is CardView.Kept -> card.page.conversationId
    }

    /** Which item the stop button to the right of the input field stops while the task is still going (working, Claire asking you, holding); null at other times. */
    fun stopKey(card: CardView): String? {
        val (run, content) = when (card) {
            is CardView.Detail -> card.run to card.content
            is CardView.Chat -> (card.run ?: return null) to (card.content ?: return null)
            is CardView.Kept -> return null
        }
        val live = content is CardContent.Working || content is CardContent.Question || content is CardContent.Held
        return run.run.key.takeIf { live }
    }

    fun content(r: CardRun): CardContent {
        val ex = r.run.exchange
        return when (val s = r.run.state) {
            is TaskState.WaitingTakeover -> CardContent.Takeover(
                need = r.line,
                body = listOfNotNull(s.message.trim().ifEmpty { null }, StatusCopy.paymentLines(s.item, s.amount, s.address)).joinToString("\n"),
                queueLine = when {
                    r.ahead > 0 -> QUEUED_AHEAD
                    r.behind > 0 -> trPlural(R.plurals.capsule_behind, r.behind)
                    else -> null
                },
                canTakeOver = r.ahead == 0 && r.kind == CapsuleKind.NeedTakeover,
            )
            is TaskState.WaitingConfirmation -> (ex?.status as? ExchangeStatus.NeedsConfirmation)
                ?.let { CardContent.Confirm(it.title, it.body, it.key) }
                // Before this exchange has been derived (right after following starts), use the package name instead of the app name
                ?: CardContent.Confirm(
                    ConfirmationCopy.title(s.request), ConfirmationCopy.detail(s.request, s.request.app),
                    ConfirmationNotifier.requestKey(s),
                )
            is TaskState.WaitingInput -> CardContent.Question(s.prompt.trim(), s.options.orEmpty().filter(String::isNotBlank))
            is TaskState.Held -> CardContent.Held(s.reason.why.trim().ifEmpty { tr(R.string.capsule_hold) }, s.reason.resumable)
            is TaskState.Succeeded -> CardContent.Result(CapsuleKind.Done)
            is TaskState.Failed -> CardContent.Result(CapsuleKind.Failed)
            TaskState.Cancelled -> CardContent.Result(CapsuleKind.Cancelled)
            else -> CardContent.Working(steps(ex, r.line))
        }
    }

    /** The most recent [STEPS] steps (oldest first): what was actually done (same wording as the steps on the chat page), with the current line appended when it differs from the last step. */
    fun steps(ex: Exchange?, line: String): List<String> {
        val done = ex?.steps.orEmpty().map { it.text }
        val all = if (line.isNotBlank() && done.lastOrNull() != line) done + line else done
        return all.takeLast(STEPS)
    }

    /** The small text at the top of the card says what state this item is in. */
    fun status(kind: CapsuleKind): String = when (kind) {
        CapsuleKind.HandedOver -> tr(R.string.capsule_handed_over)
        CapsuleKind.NeedTakeover -> tr(R.string.takeover_title)
        CapsuleKind.NeedConfirm -> tr(R.string.chat_wait_approval)
        CapsuleKind.NeedAnswer -> tr(R.string.chat_wait_answer)
        CapsuleKind.NeedStep -> tr(R.string.takeover_title)
        CapsuleKind.Held -> tr(R.string.capsule_hold)
        CapsuleKind.Running -> tr(R.string.capsule_running)
        CapsuleKind.Done -> tr(R.string.capsule_done)
        CapsuleKind.Failed -> tr(R.string.capsule_failed)
        CapsuleKind.Cancelled -> tr(R.string.capsule_stopped)
        CapsuleKind.KeptPage -> tr(R.string.kept_capsule)
        CapsuleKind.Notice -> tr(R.string.notice_hint_title)
        CapsuleKind.Idle -> "Claire"
    }

    /** Whether the virtual-screen small view is shown next to this content (and only if that item really has a screen): shown while working, when you need to take over, and while holding. */
    fun showsScreen(content: CardContent): Boolean =
        content is CardContent.Working || content is CardContent.Takeover || content is CardContent.Held
}
