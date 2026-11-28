package com.wangzan101.mika.calls

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.frontdesk.DeskBackground
import com.wangzan101.mika.core.frontdesk.DeskPrompt
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTools
import com.wangzan101.mika.core.frontdesk.deskPhaseOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where the question got through: a notification was posted (also in the conversation), only the conversation was written (the notification could not be posted), or nothing was delivered. */
enum class OwnerReach { Notified, InConversation, Unreachable }

/** Channel for asking the owner during a call: written into the conversation the call belongs to, plus a notification that can be answered directly. */
interface OwnerAsk {
    /** Deliver [question] to the owner; when the owner replies (in the conversation or the notification), call [onReply] with the original text; the first answer wins. */
    fun ask(question: String, onReply: (String) -> Unit): OwnerReach

    /** Withdraw an unanswered question and its notification; late replies are no longer called back afterwards. */
    fun cancel()
}

/**
 * The front desk inside an AI call: the other end is a third party, so it has only one tool, [DeskTools.ASK_OWNER] (ask the owner),
 * with no dispatch, supplement or cancel, and no query tools; the user front desk's tool names are all unsupported here and never go to the user front desk's executor.
 *
 * The owner's replies and the owning task's state are only handed to the model to relay in its own words, never read verbatim to the other party:
 * - Replies use [cue] (a note the model reads and then continues); if not spoken (interrupted), send it again; if it fails twice, keep it and the model gets it as a result on its next ask.
 * - Protocol cannot accept cues ([canCue] false): replies are always kept; the ask result and the prompt both state clearly that replies do not arrive by themselves and another ask is needed to collect them;
 *   asking again while unanswered yields still_waiting (not counted as busy). At hang-up, anything not yet collected goes to [unrelayed] and into the call history.
 * - State: standard calls get it as a trailing note each turn ([Companion.background]); realtime calls write it into the prompt at opening, then push changes as background via [context],
 *   and if pushing is not possible it only comes along in ask results.
 */
class PhoneDesk(
    private val ownerTask: () -> StateFlow<TaskState>?,
    private val ask: OwnerAsk,
    private val cue: (text: String, ended: (spoken: Boolean) -> Unit) -> Boolean = { _, _ -> false },
    private val context: (text: String) -> Boolean = { false },
    /** Whether this call's protocol can accept cues ([cue]); when it cannot, replies do not arrive by themselves and the model must ask again to collect them. See [canCue]. */
    private val canCue: Boolean = true,
    private val languageTag: String? = null,
    private val unrelayed: (reply: String) -> Unit = {},
) {
    private val lock = Any()
    private var pending = false
    private var closed = false
    /** The owner has replied but this sentence could not yet be handed to the model. */
    private var held: String? = null
    private val ended = CompletableDeferred<Unit>()

    suspend fun execute(name: String, args: JsonObject): JsonObject {
        if (name != DeskTools.ASK_OWNER) return status("unsupported")
        val question = (args["question"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.take(MAX_QUESTION_CHARS)
        if (question.isNullOrEmpty()) return status("invalid_arguments")
        synchronized(lock) {
            if (closed) return unavailable()
            held?.let { reply ->
                held = null
                return result("owner_replied", "The owner has answered. Relay it to the caller in your own words; never read it out verbatim.") {
                    put("reply", reply)
                }
            }
            if (pending) {
                // When cues cannot be accepted, asking again means collecting the reply: if still unanswered it is not busy
                return if (canCue) result("busy", "Still waiting for the owner. Ask the caller to hold on a moment, or offer to get back to them.")
                else result("still_waiting", "No answer from the owner yet. Ask the caller to hold on a moment, or offer to get back to them; " +
                    "call ask_owner again a little later to collect the answer.")
            }
            pending = true
        }
        // Only the reply to this particular ask is accepted: once answered, no more are taken
        var answered = false
        val reach = ask.ask(question) { reply ->
            val relay = synchronized(lock) {
                if (closed || answered) return@synchronized false
                answered = true
                pending = false
                true
            }
            if (relay && reply.isNotBlank()) relay(reply.trim(), attemptsLeft = 2)
        }
        return when (reach) {
            OwnerReach.Unreachable -> {
                synchronized(lock) { pending = false }
                unavailable()
            }
            OwnerReach.Notified -> result("asked", "Tell the caller you are checking and will reply shortly. " + collectHint())
            OwnerReach.InConversation -> result("asked", "The owner may not see this right away. Tell the caller you have " +
                "passed the question on; if no answer comes during this call, say the owner will get back to them. " + collectHint())
        }
    }

    /** How a reply arrives: with cues it is delivered as a note; without them the model must ask again to collect it. */
    private fun collectHint() = if (canCue) "The owner's answer will reach you as a note; relay it in your own words."
    else "The owner's answer will NOT arrive by itself: call ask_owner again to collect it when the caller is waiting " +
        "or after a short while, then relay it in your own words."

    /** The current state of the task this call belongs to, as one line; null when there is no owning task. */
    fun ownerTaskLine(): String? = renderOwnerTask(ownerTask()?.value, languageTag)

    /**
     * Realtime call: the opening state is already in the prompt, so when the state changes afterwards (changes at the
     * level of working > finished, not every step) push it as a background note without making the model speak for it.
     * Returns only when [close] is called.
     */
    suspend fun pushOwnerTaskChanges() {
        val flow = ownerTask() ?: return
        val cueMark = cueMarkFor(languageTag)
        coroutineScope {
            val watcher = launch {
                flow.map { renderOwnerTask(it, languageTag) }.distinctUntilChanged().drop(1).collect { line ->
                    if (line != null && !isClosed()) context(cueMark + line)
                }
            }
            ended.await()
            watcher.cancel()
        }
    }

    /** Call ended: withdraw pending questions and notifications so late replies are no longer relayed; replies not yet handed to the model go into the call history. */
    fun close() {
        val left = synchronized(lock) {
            if (closed) return
            closed = true
            pending = false
            held.also { held = null }
        }
        ask.cancel()
        ended.complete(Unit)
        left?.let(unrelayed)
    }

    private fun isClosed() = synchronized(lock) { closed }

    private fun relay(reply: String, attemptsLeft: Int) {
        if (attemptsLeft <= 0) return hold(reply)
        if (isClosed()) return unrelayed(reply)
        if (!canCue) return hold(reply)
        val accepted = cue(replyCue(reply, languageTag)) { spoken -> if (!spoken) relay(reply, attemptsLeft - 1) }
        // Protocol has no cue input: resending is pointless, so keep it for the next ask
        if (!accepted) hold(reply)
    }

    private fun hold(reply: String) {
        val stored = synchronized(lock) {
            if (closed) false else { held = reply; true }
        }
        if (!stored) unrelayed(reply)
    }

    private fun status(value: String) = buildJsonObject { put("status", value) }

    private fun result(status: String, next: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("status", status)
        extra()
        put("next", next)
        ownerTaskLine()?.let { put("owner_task", it) }
    }

    private fun unavailable() = result("unavailable",
        "The owner can't be reached right now. Don't commit to anything; offer to get back to the caller later.")

    companion object {
        /** Start of a cue: tells the model this is not something the other party said and must not be read aloud. */
        const val CUE_MARK = "[A note from Claire, not something the other party said; do not read it aloud] "
        const val OWNER_TASK_PREFIX = "The task this call belongs to: "
        const val MAX_QUESTION_CHARS = 300

        /** The cue prefix; the fork speaks English only, so the language tag is accepted for signature compatibility. */
        fun cueMarkFor(@Suppress("UNUSED_PARAMETER") languageTag: String? = null): String = CUE_MARK

        /** The cue that hands the owner's reply to the model. */
        fun replyCue(reply: String, @Suppress("UNUSED_PARAMETER") languageTag: String? = null): String =
            CUE_MARK + "The owner has replied to your question: \"" + reply + "\". Briefly relay it to the other party in your own words."

        /** Trailing note for standard calls: reads [owner]'s current state before each model call, staying out of the system prompt (preserving the cached prefix). */
        fun background(owner: StateFlow<TaskState>, @Suppress("UNUSED_PARAMETER") languageTag: String? = null): DeskBackground =
            DeskBackground { renderOwnerTask(owner.value) }

        fun renderOwnerTask(state: TaskState?, @Suppress("UNUSED_PARAMETER") languageTag: String? = null): String? = state?.let {
            OWNER_TASK_PREFIX + when (deskPhaseOf(it)) {
                DeskTaskPhase.Running -> "in progress"
                DeskTaskPhase.Queued -> "queued"
                DeskTaskPhase.NeedsYou -> "waiting for the owner"
                DeskTaskPhase.Done -> "done"
                DeskTaskPhase.Failed -> "didn't work"
                DeskTaskPhase.Cancelled -> "cancelled"
            }
        }

        /**
         * The call's system prompt: the existing call prompt ([phonePrompt], including call rules and this task) first, then the front desk note.
         * [openingOwnerTask] is only given for realtime calls (the task state at opening); standard calls carry it in the trailing note, keeping the prompt byte-for-byte unchanged.
         */
        fun systemPrompt(
            phonePrompt: String,
            task: CallTaskBrief?,
            openingOwnerTask: String?,
            canCue: Boolean = true,
            @Suppress("UNUSED_PARAMETER") languageTag: String? = null,
        ): String {
            val brief = buildString {
                append(task?.let { "Goal: " + it.goal } ?: "No specific goal was given for this call.")
                task?.questions?.takeIf { it.isNotEmpty() }?.let { append("\nRequired questions: ").append(it.joinToString(", ")) }
                openingOwnerTask?.let { append('\n').append(it) }
                append('\n').append(if (canCue) RELAY_BY_CUE else RELAY_BY_ASKING)
            }
            return phonePrompt + "\n\n" + DeskPrompt.phone(brief, POLICY)
        }

        private const val POLICY = "Follow the call rules and authorization boundaries above; where anything conflicts with them, apply the stricter reading."

        /**
         * Whether this call can hand a note to the model to continue speaking: standard calls (recorded as a model history turn) and realtime protocols that can add system text items (add one, then ask it to reply) can;
         * the duplex conversation protocol only accepts verbatim `speech_text_buffer` text, so it cannot. Kept consistent with each session's sendCue.
         */
        fun canCue(standard: Boolean, protocol: CallVoiceProtocol): Boolean = standard || protocol == CallVoiceProtocol.OPENAI_REALTIME

        /** Calls that accept cues: replies arrive by themselves. */
        const val RELAY_BY_CUE = "The owner's reply will arrive here as a note starting with \"[A note from Claire...]\": relay it to the other party in your own words; do not read the note out."

        /** Calls that cannot accept cues: replies do not arrive by themselves, so ask again to collect them. */
        const val RELAY_BY_ASKING = "The owner's reply will NOT arrive here by itself: after asking, once the other party is waiting or after a short while, " +
            "call ask_owner again to collect it; if the result is owner_replied, relay it to the other party in your own words; if it is still_waiting, ask them to hold on or say the owner will get back to them."
    }
}
