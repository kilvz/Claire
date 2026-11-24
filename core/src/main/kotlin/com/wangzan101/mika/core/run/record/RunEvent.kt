package com.wangzan101.mika.core.run.record

import com.wangzan101.mika.core.learn.replay.Anchors
import com.wangzan101.mika.core.learn.replay.Selector
import com.wangzan101.mika.core.model.usage.DeepSeekCost
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.wangzan101.mika.core.screen.observation.InputHints
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.screen.observation.Trust

/** One event in a run record; persisted as one line of events.jsonl, with the `type` field distinguishing the kinds. */
@Serializable
sealed interface RunEvent {
    val atMs: Long

    /** The before and after frames of an executed screen action. A missing image stays null rather than being faked with a nearby screenshot; unrelated to re-executing a route. */
    @Serializable @SerialName("task_playback")
    data class TaskPlayback(
        override val atMs: Long, val step: Int, val tool: String, val callId: String,
        val beforeId: Int?, val afterId: Int?, val mark: PlaybackMark? = null,
        val succeeded: Boolean = true, val source: String = "model",
    ) : RunEvent

    /** [origin] is who started this run (lowercase name of `RunOrigin`); not written for runs started on the phone. Both budgets are null when unlimited. */
    @Serializable @SerialName("task_started")
    data class TaskStarted(
        override val atMs: Long, val goal: String, val model: String,
        val origin: String? = null, val stepLimit: Int? = null, val tokenBudget: Long? = null,
    ) : RunEvent

    /** Digest of the system prompt and tool definitions this task sent to the model ([com.wangzan101.mika.core.prompt.PromptVersion]). */
    @Serializable @SerialName("prompt_version")
    data class PromptVersion(override val atMs: Long, val hash: String) : RunEvent

    /** Count or boundary progress; [total] is null when the total is unknown, and [boundary] is the latest confirmed position, date or item. */
    @Serializable
    data class Progress(val name: String, val done: Int? = null, val total: Int? = null, val boundary: String? = null)

    /**
     * [fallback] means the criteria could not be listed and [criteria] is only the user's own words. In-flight progress updates reuse this event too:
     * [progress] is the latest confirmed count/boundary and [complete] means all criteria are satisfied or the count has been reached, which the main loop uses to wrap up.
     */
    @Serializable @SerialName("criteria")
    data class Criteria(
        override val atMs: Long,
        val criteria: List<String>,
        val ambiguities: List<String>,
        val kind: String = "",
        val fallback: Boolean = false,
        val progress: Progress? = null,
        val complete: Boolean = false,
        val confirmedIds: Set<Int>? = null,
    ) : RunEvent

    /** [tool]/[argsJson] is the single call actually executed in this round; the arguments are already redacted. */
    @Serializable @SerialName("model_reply")
    data class ModelReply(
        override val atMs: Long,
        val step: Int,
        val text: String,
        val tool: String?,
        val argsJson: String?,
        val inputTokens: Int?,
        val outputTokens: Int?,
        val cachedTokens: Int?,
        /** From the last request being sent until the reply was fully received; earlier failed requests and backoff are recorded separately in [retryWaitMs]. */
        val latencyMs: Long = 0,
        /** The model actually used for this call; null = the main model. */
        val model: String? = null,
        /** When the main/light model split is in effect: main / light / light_rejected (the light model's reply was not executed and the main model was asked instead); otherwise null. */
        val role: String? = null,
        /** Which model group the member that actually answered belongs to; null for a single model. */
        val group: String? = null,
        /** Time spent before retrying: the failed requests plus backoff waiting. */
        val retryWaitMs: Long = 0,
        /**
         * The moment the reply arrived: the step's description, the action to take or the answer starts being shown in the UI from this point (a reply that carries a call is recorded only after it has been executed, so [atMs] is later than this).
         * Not present for dispatched-away light model replies or in old records.
         */
        val repliedAtMs: Long? = null,
    ) : RunEvent {
        /** Whether this reply really drove this step (dispatched-away light model replies do not count). */
        val acted: Boolean get() = role != "light_rejected"
    }

    /** [resultJson] includes the guard verdict and is recorded in full; it is truncated with a marker only when it exceeds [MAX_RESULT] characters. */
    @Serializable @SerialName("tool_result")
    data class ToolResult(
        override val atMs: Long,
        val step: Int,
        val tool: String,
        val resultJson: String,
        val changed: Boolean?,
        val screenshotId: Int?,
        /** Tool execution time, including waiting inside the port for the UI to settle; excluding waiting for the user (confirmation, follow-up questions, takeover). */
        val durationMs: Long = 0,
        /** The part of it spent waiting for the UI to settle (a value only when the port can report it), plus time deliberately paused for pacing (an extra pause when flipping to the next page before the current one has been seen long enough). */
        val waitMs: Long = 0,
        /** model | batch | replay | script (an action inside web_script) */
        val source: String = "model",
        /** The id of this model call; routes use it to pair an action with its result. */
        val callId: String? = null,
    ) : RunEvent

    /** Replaying a route, now at step [step]/[total]; [matched] false means control is handed back to the model here. */
    @Serializable @SerialName("replay")
    data class Replay(override val atMs: Long, val routeId: String, val step: Int, val total: Int, val matched: Boolean, val detail: String) : RunEvent

    /** A batch of actions is at step [step]/[total]; when [stopped], [reason] says why it stopped here. */
    @Serializable @SerialName("batch")
    data class Batch(override val atMs: Long, val step: Int, val total: Int, val stopped: Boolean, val reason: String?) : RunEvent

    /**
     * The target element and the before/after anchors of a write action at that time (the element list is incomplete after the tool result was truncated, so routes rely on it); [argsJson] is already redacted.
     * [effective]: ok and the UI changed, or the typed input reads back the same, or the launch succeeded. [callId] is the same as [ToolResult.callId] of the same call;
     * [protected]: the screen before the action was protected by the system's screenshot block.
     */
    @Serializable @SerialName("action_target")
    data class ActionTarget(
        override val atMs: Long,
        val step: Int,
        val tool: String,
        val argsJson: String,
        val target: Selector?,
        val before: Anchors,
        val after: Anchors,
        val effective: Boolean,
        val callId: String? = null,
        val protected: Boolean = false,
        /** model | batch */
        val source: String = "model",
        /** The target and anchors of this step could not be obtained (a sub-step inside a batch); a route cannot have missing steps, so nothing is recorded at all. */
        val incomplete: Boolean = false,
    ) : RunEvent

    /**
     * A saved screen frame (for visualization: review, screenshots in sources): image number [mediaId] of step [step], with [place] being where it was at the time (package name, or "site domain").
     * In old records this is screen_saved (converted to this kind on read).
     */
    @Serializable @SerialName("media")
    data class Media(override val atMs: Long, val mediaId: Int, val step: Int, val place: String?) : RunEvent

    /**
     * An observation recorded in the evidence ledger (see [com.wangzan101.mika.core.screen.observation.Observation]): the text is already redacted and truncated, identical to the entry in the runtime ledger,
     * so evaluation rebuilds the ledger from it verbatim. [id] is the id in the ledger (answer sources point back with it); [kind] is the observation kind and [source] the capability that produced it.
     * [inputs] and [focused] are the text that was in the input fields at the time (used to tell input-field echoes apart; null when reading page content).
     * In old records this is screen_text (which recorded only screen reads and full-page text, converted to this kind on read with [id] 0).
     */
    @Serializable @SerialName("observed")
    data class Observed(
        override val atMs: Long, val step: Int, val text: String, val id: Int = 0, val kind: String = ObsKind.Read.wire,
        val source: String? = null, val place: Place? = null, val title: String? = null,
        val inputs: List<String>? = null, val focused: String? = null, val untrusted: Boolean = false,
    ) : RunEvent {
        /** Converts back into an observation. */
        fun observation() = Observation(ObsKind.ofWire(kind) ?: ObsKind.Read, text, source, place, title,
            trust = if (untrusted) Trust.Untrusted else Trust.Trusted, inputs = inputs?.let { InputHints(it, focused) }, step = step, atMs = atMs, id = id)

        companion object {
            /** An observation recorded in the ledger. */
            fun of(o: Observation) = Observed(o.atMs, o.step, o.text, o.id, o.kind.wire, o.source, o.place, o.title,
                o.inputs?.names, o.inputs?.focused, o.trust == Trust.Untrusted)
        }
    }

    /** Progress of one actual tool call. Read-type calls may be shown merged, action-type ones one by one; [obsIds] point to the text read in this step and [title] is the title of the page that was read. */
    @Serializable @SerialName("activity")
    data class Activity(
        override val atMs: Long,
        val step: Int,
        val kind: String,
        val label: String,
        val target: String? = null,
        val effect: String? = null,
        val obsIds: List<Int> = emptyList(),
        val source: String = "model",
        val callId: String? = null,
        val title: String? = null,
        /** This call did not succeed ([effect] is an error code, shown to developers only). */
        val failed: Boolean = false,
        /** The irreversible-action check recognized this as an outward action (send, submit); when explaining afterwards what was done after a stop, it is used to count how many were sent. */
        val outward: Boolean = false,
        /** The external MCP connector this call went through (the name from configuration); null when MCP was not used. */
        val mcpConnector: String? = null,
    ) : RunEvent

    @Serializable @SerialName("state")
    data class State(override val atMs: Long, val name: String, val detail: String) : RunEvent

    @Serializable @SerialName("confirmation")
    data class Confirmation(
        override val atMs: Long,
        val action: String,
        val target: String?,
        val content: String?,
        val amount: String?,
        val decision: String?,
    ) : RunEvent

    /**
     * The approval verdict for an outward, irreversible or money-spending action (decided by code, not self-reported by the model). [source] is who started it (model / batch / script / replay);
     * [payment] true means this step costs money (when asking, only it requires the user's consent); [decision] is allowed / required / mismatch / denied.
     */
    @Serializable @SerialName("approval")
    data class Approval(
        override val atMs: Long, val step: Int, val source: String, val tool: String, val action: String, val matched: String,
        val risk: String, val payment: Boolean = false, val decision: String,
    ) : RunEvent

    @Serializable @SerialName("takeover")
    data class Takeover(override val atMs: Long, val reason: String, val outcome: String?, val durationMs: Long?, val inputCount: Int?) : RunEvent

    @Serializable @SerialName("barrier")
    data class Barrier(override val atMs: Long, val kind: String) : RunEvent

    /**
     * Literal check of the final answer (the answer of step [step]): [claims] is the number of checkable facts in the answer, and [unmatched] are the ones not found in the material that was read;
     * [outward] means this task did something outward or irreversible (and a verification model was therefore called in separately).
     */
    @Serializable @SerialName("answer_check")
    data class AnswerCheck(override val atMs: Long, val step: Int, val claims: Int, val unmatched: List<String>, val outward: Boolean = false) : RunEvent

    /** The few spots in the handed-over answer that still did not match after being returned once and were marked unconfirmed. */
    @Serializable @SerialName("unconfirmed")
    data class Unconfirmed(override val atMs: Long, val step: Int, val items: List<String>) : RunEvent

    /** Answer sources that code found in the evidence ledger (app or web page, which step, the screenshot of that step); the "Sources" UI uses it. */
    @Serializable @SerialName("sources")
    data class Sources(override val atMs: Long, val items: List<com.wangzan101.mika.core.safety.verify.AnswerSource>) : RunEvent

    /** [step]: which step's final answer was verified (in old records this was the finish call; this event may be recorded before or after that reply); absent in old records. */
    @Serializable @SerialName("verifier")
    data class Verifier(override val atMs: Long, val verdict: String, val detailJson: String, val step: Int? = null) : RunEvent

    /** The items actually listed in this task's memory block (recorded by code, not self-reported by the model). */
    @Serializable @SerialName("memory_used")
    data class MemoryUsed(override val atMs: Long, val ids: List<String>) : RunEvent

    /** The agent saved one item with memory_save; [status] is active / pending. */
    @Serializable @SerialName("memory_saved")
    data class MemorySaved(override val atMs: Long, val id: String, val kind: String, val status: String) : RunEvent

    /** A personal skill that was used: [how] = viewed (the agent looked at it with skill_read); old records also have injected (the full text used to be injected at task start), which counts as used on read as well. */
    @Serializable @SerialName("skill_used")
    data class SkillUsed(override val atMs: Long, val name: String, val how: String) : RunEvent

    @Serializable @SerialName("compressed")
    data class Compressed(override val atMs: Long, val foldedSteps: Int) : RunEvent

    /** [outcome]: succeeded / unverified / failed / cancelled / error; [kind] is the failure category (lowercase name of FailKind), null otherwise, and absent in old records. */
    @Serializable @SerialName("task_ended")
    data class TaskEnded(override val atMs: Long, val outcome: String, val answerOrReason: String, val kind: String? = null) : RunEvent

    /**
     * Totals of metered model calls during this run (see [com.wangzan101.mika.core.model.usage.RunUsageTally]).
     * [calls] is the number of outer logical calls, including side calls for intent detection, verification, image reading, compression and so on, plus outer-level failure retries;
     * [httpCalls] is the number of inference requests actually handed to the HTTP client, plus compatibility resends inside the adapter. Old records lack this field and it cannot be inferred from [calls].
     * [unreported] / [httpUnreported] are, respectively, the number of logical calls with no reported usage and the number of actual requests with no corresponding final usage; tokens for failed requests are not estimated.
     * One is recorded at wrap-up, after [TaskEnded]; judging the outcome after the answer was handed over and memory extraction are not included (the Token Usage page counts them both).
     */
    @Serializable @SerialName("model_calls")
    data class ModelCalls(
        override val atMs: Long, val calls: Int, val unreported: Int, val inputTokens: Long, val outputTokens: Long, val cachedTokens: Long,
        val httpCalls: Int? = null, val httpUnreported: Int? = null,
        val deepSeekCost: DeepSeekCost? = null,
    ) : RunEvent

    /**
     * After the answer is handed to the user, a separately invoked model judges how this run turned out against the result the user wanted (see [com.wangzan101.mika.core.agent.OutcomeJudge]):
     * [outcome] is done / partial / not_possible / unknown (judging failed); [reason] says in one sentence what was missing. Recorded after [TaskEnded]; absent in old records.
     */
    @Serializable @SerialName("outcome")
    data class OutcomeJudged(override val atMs: Long, val outcome: String, val reason: String = "", val model: String? = null) : RunEvent

    companion object {
        const val MAX_FIELD = 2000

        /** Upper bound for a tool result: far larger than one screen's element list, it only guards against abnormally large output blowing up the record file and memory. */
        const val MAX_RESULT = 100_000

        val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

        fun encode(e: RunEvent): String = json.encodeToString(serializer(), e)

        /** One record line; events from before the rename (screen_text, screen_saved) are converted to their current shape before reading. */
        fun decode(line: String): RunEvent = json.decodeFromJsonElement(serializer(), LegacyEvents.upgrade(json.parseToJsonElement(line)))

        /** The place written into run records: an app is its package name, a site is "site domain" (the same form as in past records). */
        fun placeLabel(p: Place?): String? = p?.let { if (it.kind == com.wangzan101.mika.core.screen.observation.PlaceKind.Site) "网站 ${it.name}" else it.name }

        fun clip(s: String): String = if (s.length <= MAX_FIELD) s else s.take(MAX_FIELD) + "…(truncated)"

        fun clipResult(s: String): String =
            if (s.length <= MAX_RESULT) s else s.take(MAX_RESULT) + "…(truncated, was ${s.length} characters)"
    }
}

/** The contents of run.json: a summary of one run, accumulated from events. */
@Serializable
data class RunSummary(
    val id: String,
    val goal: String,
    val model: String = "",
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val outcome: String? = null,
    val answerOrReason: String? = null,
    val criteria: List<String> = emptyList(),
    val verdict: String? = null,
    /** The outcome judged after the answer was handed over ([RunEvent.OutcomeJudged.outcome]); null when it was never judged (failed, cancelled, old record). */
    val judged: String? = null,
    val steps: Int = 0,
    /** The step limit of this run (see [RunEvent.TaskStarted.stepLimit]); null in old records or when unlimited. */
    val stepLimit: Int? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
    /** Who started it (see [RunEvent.TaskStarted.origin]); empty for runs started on the phone. */
    val origin: String? = null,
) {
    /** Folds one event into the summary. */
    fun with(e: RunEvent): RunSummary = when (e) {
        is RunEvent.TaskStarted -> copy(model = e.model, origin = e.origin, stepLimit = e.stepLimit)
        is RunEvent.Criteria -> copy(criteria = e.criteria)
        is RunEvent.ModelReply -> copy(
            steps = maxOf(steps, e.step),
            inputTokens = inputTokens + (e.inputTokens ?: 0),
            outputTokens = outputTokens + (e.outputTokens ?: 0),
            cachedTokens = cachedTokens + (e.cachedTokens ?: 0),
        )
        is RunEvent.Verifier -> copy(verdict = e.verdict)
        is RunEvent.TaskEnded -> copy(endedAtMs = e.atMs, outcome = e.outcome, answerOrReason = e.answerOrReason)
        is RunEvent.OutcomeJudged -> copy(judged = e.outcome)
        else -> this
    }
}
