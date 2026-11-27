package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.run.trace.RunTrace
import com.wangzan101.mika.core.action.ConfirmDecision
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelNeed
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.net.RateLimitGovernor
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.parseArgs
import kotlinx.coroutines.CancellationException
import com.wangzan101.mika.core.model.usage.ConversationMeter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One model call: history is append-only and is compacted once only when the estimate crosses the trigger point
 * ([ContextCompressor], the full history stays untouched); transient primary-model failures are retried per [retry];
 * when the context is too long it compacts harder and sends once more; with lightSteps on, work is split between the
 * large and light models via [router] (the light model's failures are not retried — this step asks the primary model
 * directly). [reset] at the start of every task.
 *
 * Both the primary and light models may be model groups ([ModelPool]): a call picks one member by member capability
 * and policy, and on failure moves to the next per [FailoverPolicy]; each member has its own compaction state and
 * usage anchor, and on a member switch [viewFor] strips other models' private data.
 */
internal class ModelCaller(
    private val model: ChatModel,
    private val tools: ToolDispatcher,
    private val router: ModelRouter?,
    private val flags: SpeedFlags,
    private val retry: RetryPolicy,
    private val emit: (RunEvent) -> Unit,
    private val now: () -> Long,
    private val autonomous: Boolean = false,
    private val trace: RunTrace? = null,
    /** Fallbacks for the primary model (item 3): when the primary model fails this step and [Failover] says to switch, they take over in order; none by default, behavior as before. */
    private val fallbacks: List<ChatModel> = emptyList(),
    /** Provider IDs parallel to the primary model chain, used to judge whether the daily budget applies to a fallback. */
    private val chainProviderIds: List<String?> = emptyList(),
) {
    /** One compaction state per model: its own context and its own sent history, so a light-model compaction does not disturb the primary model's request prefix. */
    private val compressors = java.util.IdentityHashMap<ChatModel, ContextCompressor>()
    private fun compressorFor(m: ChatModel) =
        compressors.getOrPut(m) { ContextCompressor(tools.index, structured = flags.structuredCompaction,
            diffScreens = tools.inlineScreen, triggerOverride = m.capabilities.compactionTriggerTokens,
            foldEvery = m.capabilities.compactionEveryRounds) }
    private val compactions = java.util.IdentityHashMap<ChatModel, CompactionState>()
    /** Time already spent waiting for retries in this task (against [RetryPolicy.taskWaitBudgetMs]). */
    private var taskRetryWaitMs = 0L

    /** Primary model chain: [model] plus fallbacks. Once switched over, the rest of this task uses it (same idea as disabling the light model); [reset] returns to the primary. */
    private val chain: List<ChatModel> = listOf(model) + fallbacks
    private var chainIndex = 0

    /** The primary model's original error in this task, and the first error each chain model hit (used for the message when the whole chain fails, MK-1 batch 2); [reset] clears them. */
    private var headError: ModelError? = null
    private val trialLog = ArrayList<Pair<Int, ModelError>>()

    /** Model actually used by the most recent primary-model call: the fallback after a switch; [routed] records against it. */
    var lastMainUsed: ChatModel = model
        private set

    /** This task's system prompt: assembled at start from the same tool list sent to the model ([SystemPrompt.assemble]), unchanged within the task. */
    var systemPrompt: String = ""

    /** Plugin prompt hook: the assembled system prompt passes through it before being sent to the model; when null it is sent as-is. */
    var promptTransform: (suspend (String) -> String)? = null

    /** Time the most recent call spent before its successful request (failed requests, backoff, member switches); measured from the first request actually sent. */
    var lastRetryWaitMs = 0L
        private set

    /** Which model produced each reply already in history (by message identity); used on model switches to strip other models' private data. Unrecorded replies (those in history before the task started) count as the primary model's. */
    private val producers = java.util.IdentityHashMap<Message, ChatModel>()

    /** Replies with real usage (by message identity) and the model that produced them, as anchors for measuring request size ([UsageMeter]); cleared after compaction rewrites the prefix. */
    private val anchors = java.util.IdentityHashMap<Message, Pair<ChatModel, UsageMeter.Anchor>>()
    /** Estimated fixed overhead of each model's most recent request; recorded into the anchor together with usage when a reply enters history. */
    private val lastOverhead = java.util.IdentityHashMap<ChatModel, Int>()

    fun reset(prompt: String) {
        systemPrompt = prompt
        compactions.clear()
        taskRetryWaitMs = 0
        chainIndex = 0
        headError = null
        trialLog.clear()
        lastMainUsed = model
        compressors.values.forEach { it.forget() }
        producers.clear()
        anchors.clear()
        lastOverhead.clear()
    }

    /** A reply entered history: record which model produced it; ones with real usage become anchors for context size. */
    fun noteReply(m: Message, used: ChatModel, usage: Usage?) {
        if (m.role == Role.Assistant) producers[m] = used
        val overhead = lastOverhead[used]
        if (usage != null && overhead != null) anchors[m] = used to UsageMeter.Anchor(usage, overhead)
    }

    /** How large the whole request sent to [using] is: anchor on the latest reply from it carrying real usage, otherwise estimate in full (see [UsageMeter]). */
    private fun requestTokens(messages: List<Message>, using: ChatModel, overhead: Int, scale: Double): Int =
        UsageMeter.of(messages, overhead, scale) { m -> anchors[m]?.takeIf { it.first === using }?.second }

    /** Whether routing is in effect in this task: the switch is on, the light model can take phone steps, and it has not been disabled by an unrecoverable error. */
    private val routing: Boolean get() = flags.lightSteps && router?.active == true

    /** When routing is on, record the model and role of every call in the run log (still recorded after disabling, for comparison). */
    val recordRoles: Boolean get() = flags.lightSteps && router != null

    fun roleOf(m: ChatModel) = if (router?.isLight(m) == true) ROLE_LIGHT else ROLE_MAIN

    /** Which model group the member used by this call belongs to; null for a single model. Falls back to the primary pool when a subtask has no router. */
    fun groupOf(m: ChatModel): String? {
        router?.groupOf(m)?.let { return it }
        val mainPool = model.asPool()
        return if (mainPool === m || mainPool.owns(m)) mainPool.groupName else null
    }

    /**
     * Picks the model per [router]. When the light model errors, makes no tool call (a reply without a call is the
     * final answer and only comes from the primary model), or wants to give up, confirm, take over, ask back, or do
     * something irreversible, its reply is not executed (usage is recorded as [ROLE_LIGHT_REJECTED]), this step asks
     * the primary model instead, and [restarted] reports the new ask time before that.
     * Returns the reply and the model actually used.
     */
    suspend fun routed(
        history: List<Message>, specs: List<ToolSpec>, track: StepTrack, step: Int, restarted: (Long) -> Unit,
        onText: ((String) -> Unit)? = null,
    ): Pair<ModelReply, ChatModel> {
        val r = router?.takeIf { routing } ?: return askRouted(history, specs, step, onText = onText)
        val picked = r.pick(track.context(step, history.any { m -> m.parts.any { it is Part.Image } }).copy(userSpoke = userSpokeSinceReply(history)))
        if (!r.isLight(picked)) return askRouted(history, specs, step, onText = onText)
        // The light model's context may be smaller: its context-too-long report and how much it folded do not affect
        // the primary model (compaction state is tracked per model)
        val asked = now()
        val light = try {
            complete(history, specs, picked.asPool(), RetryPolicy.NONE, step, onText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError.TaskBudgetExceeded) {
            // The whole run shares one budget; after a light-model failure it cannot hand off to the primary model again.
            throw e
        } catch (e: Exception) {
            // Key, quota, model name, or the request itself is wrong: retrying is useless, so the rest of this task's steps use the primary model
            if (e is ModelError.Auth || e is ModelError.IllegalHeader || e is ModelError.QuotaExceeded || e is ModelError.DailyBudgetExceeded || e is ModelError.ModelNotFound ||
                e is ModelError.ModelNotAvailable || e is ModelError.ToolsUnsupported ||
                e is ModelError.ChatGptLimitReached || e is ModelError.ChatGptNotEligible || e is ModelError.SignInExpired || e is ModelError.AccountPlanNotAllowed ||
                e is ModelError.GroupUnavailable || e is ModelError.GroupExhausted ||
                (e is ModelError.Server && e.status in 400..499)) r.disable()
            null
        }
        val first = light?.first?.parts?.firstActionCall()
        if (light != null && first != null && !needsMain(first)) return light
        r.noteTrouble()
        val reply = light?.first
        val used = light?.second
        val text = reply?.parts?.filterIsInstance<Part.Text>()?.joinToString("") { it.text }?.trim().orEmpty()
        emit(RunEvent.ModelReply(
            now(), step, RunEvent.clip(Redactor.text(text)), first?.name, first?.let { Redactor.args(tools.verb(it.name), it.argsJson, secretField = true) },
            reply?.usage?.inputTokens, reply?.usage?.outputTokens, reply?.usage?.cachedInputTokens, now() - asked,
            used?.name ?: picked.name, ROLE_LIGHT_REJECTED, group = r.groupOf(picked),
        ))
        emit(RunEvent.State(now(), "light_to_main", first?.name ?: if (light == null) "light_error" else "no_tool"))
        onText?.invoke("")
        restarted(now())
        return askRouted(history, specs, step, onText = onText)
    }

    /** 最后一条模型回复之后有用户自己的话(插话,见 [Author.User]);开局那句在第一条回复之前,不算。 */
    private fun userSpokeSinceReply(history: List<Message>): Boolean {
        val last = history.indexOfLast { it.role == Role.Assistant }
        return last >= 0 && (last + 1 until history.size).any { history[it].author == Author.User }
    }

    private fun needsMain(call: Part.ToolCall): Boolean =
        ModelRouter.mainOnly(call.name, tools.verb(call.name)) ||
            (!autonomous && ConfirmDecision.mayBeIrreversible(tools, call.name, parseArgs(call.argsJson)))

    /**
     * Asks [using] (the primary model by default): what is sent is a compacted copy of the history as that model sees
     * it ([viewFor]).
     * Asking the primary model goes through the primary chain: when [complete] gives up (retries exhausted or not
     * retryable) and [Failover] says to switch, the next model on the chain is asked; when the chain is exhausted, or
     * [Failover] says not to switch, the error is rethrown as-is with the same failure message as without fallbacks.
     * Asking any other model is still a single send.
     */
    suspend fun ask(history: List<Message>, specs: List<ToolSpec>, step: Int, using: ChatModel = model, onText: ((String) -> Unit)? = null): ModelReply =
        askRouted(history, specs, step, using, onText).first

    /** Like [ask], but returns the model that actually answered (may be any member of a group). Asking the primary model likewise goes through the primary chain. */
    suspend fun askRouted(
        history: List<Message>, specs: List<ToolSpec>, step: Int, using: ChatModel = model, onText: ((String) -> Unit)? = null,
    ): Pair<ModelReply, ChatModel> {
        if (using !== model) return complete(viewFor(history, using), specs, using.asPool(), retry, step, onText)
        // The message when the whole chain fails (MK-1 batch 2): led by the primary model's original error, with
        // where each fallback failed attached (see [terminalError]); if only one model was tried it is handed out
        // as-is, the same as without fallbacks. Each step first records its error into [trialLog]: after a switch,
        // later steps start from the fallback and the primary model's error is no longer at hand, so only that
        // record can reconstruct the whole-chain message.
        while (true) {
            val current = chain[chainIndex]
            try {
                val result = complete(viewFor(history, current), specs, current.asPool(), retry, step, onText)
                lastMainUsed = result.second
                return result
            } catch (e: ModelError) {
                if (trialLog.none { it.first == chainIndex }) trialLog += chainIndex to e
                if (chainIndex == 0 && headError == null) headError = e
                val next = Failover.nextIndex(e, chainIndex, chainProviderIds, chain.size) ?: throw terminalError(e)
                onText?.invoke("")
                emit(RunEvent.State(now(), "model_switch", "${current.name} -> ${chain[next].name} ${RetryPolicy.label(e)}"))
                chainIndex = next
            }
        }
    }

    /** No more switching on the chain: decides which error to hand out — budget errors as-is; a single tried model as-is; the whole chain wrapped as [ModelError.ChainFailed]. */
    private fun terminalError(e: ModelError): ModelError {
        // When the current error is itself a budget error it wins; otherwise an earlier budget error on the chain takes precedence over wrapping (review T-1112-74a8f2 D1): budget errors are not wrapped in ChainFailed, but handed out as-is
        if (e is ModelError.TaskBudgetExceeded || e is ModelError.DailyBudgetExceeded) return e
        trialLog.map { it.second }.firstOrNull {
            it is ModelError.TaskBudgetExceeded || it is ModelError.DailyBudgetExceeded
        }?.let { return it }
        return if (trialLog.size < 2) e
        else ModelError.ChainFailed(headError ?: e, trialLog.map { (index, err) -> chain[index].name to err })
    }

    /**
     * History sent to [to]: replies not produced by [to] lose their thinking content and call-private data (those may
     * only be returned verbatim to the model that produced them).
     * Replies with no recorded source count as the primary model's: kept when sending to the primary model (or a group
     * containing it), stripped when sending to the light model or a fallback. Only field presence matters, regardless
     * of vendor.
     */
    private fun viewFor(history: List<Message>, to: ChatModel): List<Message> {
        if (producers.isEmpty() && to === model) return history
        return history.map { m ->
            val from = producers[m]
            val foreign = m.role == Role.Assistant && (from?.let { !belongsTo(to, it) } ?: !belongsTo(to, model))
            if (!foreign || m.parts.none { it is Part.Thinking || (it is Part.ToolCall && it.opaque != null) }) m
            else m.copy(parts = m.parts.mapNotNull { p ->
                when {
                    p is Part.Thinking -> null
                    p is Part.ToolCall && p.opaque != null -> p.copy(opaque = null)
                    else -> p
                }
            })
        }
    }

    /** Whether [from] is a member of the pool [to]; for a single model, object identity. */
    private fun belongsTo(to: ChatModel, from: ChatModel): Boolean =
        (to as? ModelPool)?.owns(from) ?: (to === from)

    /** When the first request was actually sent in one call; kept across member switches, and wait time is measured from it. */
    private class AttemptClock { var firstAttemptAt: Long? = null }

    /**
     * One call: orders the members per policy and tries them one by one; on success returns the reply and the model
     * actually used.
     * When a group has several candidates, the same member is retried at most once, leaving the chance to the next
     * one; a single model keeps the original retry count.
     */
    private suspend fun complete(
        history: List<Message>, specs: List<ToolSpec>, pool: ModelPool, policy: RetryPolicy, step: Int,
        onText: ((String) -> Unit)? = null,
    ): Pair<ModelReply, ChatModel> {
        lastRetryWaitMs = 0
        // A single-member pool ignores the selection criteria: no need to estimate the whole history for it (expensive in long conversations)
        val candidates = if (pool is SingleMemberPool) pool.members else pool.ordered(needOf(history, specs))
        if (candidates.isEmpty()) throw ModelError.GroupUnavailable(pool.groupName ?: "", "no available members")
        val clock = AttemptClock()
        var last: Throwable? = null
        for ((index, member) in candidates.withIndex()) {
            val using = member.model
            val memberBegan = now()
            try {
                val reply = attemptMember(
                    viewFor(history, using), specs, using, policy, step, onText, clock,
                    maxRetries = if (candidates.size > 1) minOf(policy.maxRetries, 1) else policy.maxRetries,
                )
                pool.noteResult(member, now() - memberBegan, null)
                return reply to using
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelError) {
                last = e
                pool.noteResult(member, now() - memberBegan, e)
                if (!FailoverPolicy.canFailover(e) || index == candidates.lastIndex) throw e
                onText?.invoke("")
                val next = candidates.getOrNull(index + 1)
                emit(RunEvent.State(
                    now(), "model_route",
                    "${pool.groupName ?: "group"} ${member.label} -> ${next?.label ?: "-"} ${RetryPolicy.label(e)}",
                ))
            } catch (e: Exception) {
                last = e
                pool.noteResult(member, now() - memberBegan, e)
                if (index == candidates.lastIndex) throw e
                onText?.invoke("")
            }
        }
        throw last ?: ModelError.GroupUnavailable(pool.groupName ?: "")
    }

    /** One full attempt against [using]: compact, send the request, retry on the same member, compact harder when too long. */
    private suspend fun attemptMember(
        history: List<Message>, specs: List<ToolSpec>, using: ChatModel, policy: RetryPolicy, step: Int,
        onText: ((String) -> Unit)?, clock: AttemptClock, maxRetries: Int,
    ): ModelReply {
        var retries = 0
        val overhead = TokenEstimate.of(systemPrompt) + specs.sumOf { TokenEstimate.of(it.name + it.description + it.parametersJsonSchema) }
        val contextTokens = using.capabilities.contextTokens
        val compressor = compressorFor(using)
        var compaction = compactions[using] ?: CompactionState()
        var overflowRetried = false
        var overflowRewrote = false
        while (true) {
            val current = ContextRecovery.ensure(history, compressor.current(history, contextTokens))
            val currentTokens = requestTokens(current, using, overhead, compressor.scale)
            val raw = compressor.compress(history, contextTokens, currentTokens, compaction.aggressive, overhead, using.capabilities.maxImages)
            if (raw.rewritten) anchors.clear()
            val restored = ContextRecovery.ensure(history, raw.messages)
            val sent = if (restored === raw.messages) raw else raw.copy(messages = restored, estimate = TokenEstimate.of(restored))
            val (next, report) = CompactionPolicy.sent(compaction, sent.foldedSteps)
            if (report) emit(RunEvent.Compressed(now(), sent.foldedSteps))
            compaction = next
            compactions[using] = compaction
            try {
                // When the diff base was trimmed or folded, the next write operation must send the full payload
                if (tools.inlineScreen) tools.setVisibleObservations(visibleReads(sent.messages))
                // If a 429 was hit, wait for the global cooldown window to pass before sending (returns immediately when not cooling down, zero cost)
                RateLimitGovernor.awaitClear(using.name)
                // Measured from the first request sent: failed requests, backoff, and member switches all count as retry wait; preparation before the first send does not
                val sentAt = now()
                lastRetryWaitMs = clock.firstAttemptAt?.let { sentAt - it } ?: 0L
                if (clock.firstAttemptAt == null) clock.firstAttemptAt = sentAt
                suspend fun invokeModel(): ModelReply {
                    val prompt = promptTransform?.invoke(systemPrompt) ?: systemPrompt
                    return if (onText == null) using.complete(prompt, sent.messages, specs)
                    else if (using is StreamingChatModel) using.completeStreaming(prompt, sent.messages, specs, onText)
                    else using.complete(prompt, sent.messages, specs).also { result ->
                        result.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                            .takeIf(String::isNotEmpty)?.let(onText)
                    }
                }
                suspend fun send(): ModelReply =
                    if (currentCoroutineContext()[ConversationMeter] != null && roleOf(using) == ROLE_MAIN)
                        withContext(ConversationMeter.MainRequest(step)) { invokeModel() }
                    else invokeModel()
                val reply = if (trace == null) send() else {
                    val change = when {
                        overflowRewrote -> "overflow_compaction folded=" + raw.foldedSteps
                        raw.rewritten && current != sent.messages -> "history_rewritten folded=" + raw.foldedSteps
                        else -> null
                    }
                    overflowRewrote = false
                    trace.modelCall(step, using, tools::verb, change, sent.messages) { send() }
                }
                return reply.also { r ->
                    RateLimitGovernor.reportSuccess(using.name)
                    lastOverhead[using] = overhead
                    r.usage?.let { compressor.calibrate(sent.estimate + overhead, it.inputTokens) }
                }
            } catch (e: ModelError.ContextTooLong) {
                onText?.invoke("")
                val (after, again) = CompactionPolicy.overflow(compaction, overflowRetried) {
                    compressor.overflow(history, contextTokens, overhead)
                }
                compaction = after
                compactions[using] = compaction
                overflowRetried = true
                if (again) { anchors.clear(); overflowRewrote = true }
                if (!again) throw e
            } catch (e: ModelError) {
                onText?.invoke("")
                if (e is ModelError.RateLimited) {
                    // 429: record into the global cooldown window so other tasks/subagents wait too; with no retry allowance left, hand over to chain switching immediately.
                    val wait = RateLimitGovernor.reportRateLimited(using.name, e.retryAfterMs)
                    val hasFallback = chainIndex + 1 < chain.size && chain[chainIndex] === using
                    val withinRetryLimit = retries < policy.maxRetries &&
                        (!hasFallback || retries < RATE_LIMIT_FAILOVER_RETRIES)
                    val withinWaitLimit = !hasFallback || wait <= RATE_LIMIT_FAILOVER_WAIT_BUDGET_MS
                    if (!withinRetryLimit || !withinWaitLimit || taskRetryWaitMs + wait > policy.taskWaitBudgetMs) throw e
                    retries += 1
                    taskRetryWaitMs += wait
                    emit(RunEvent.State(now(), "model_retry", "${roleOf(using)} rate_limited #$retries ${wait}ms"))
                    continue
                }
                val wait = policy.delayFor(e, retries, taskRetryWaitMs, maxRetries) ?: throw e
                retries += 1
                taskRetryWaitMs += wait
                emit(RunEvent.State(now(), "model_retry", "${roleOf(using)} ${RetryPolicy.label(e)} #$retries ${wait}ms"))
                // Cancelling the task during the wait ends it here
                delay(wait)
            }
        }
    }

    /** What this request requires of a member: tools or not, images or not, estimated size. */
    private fun needOf(history: List<Message>, specs: List<ToolSpec>): ModelNeed {
        val images = history.sumOf { m -> m.parts.count { it is Part.Image } }
        val overhead = TokenEstimate.of(systemPrompt) + specs.sumOf { TokenEstimate.of(it.name + it.description + it.parametersJsonSchema) }
        return ModelNeed(
            toolCalls = specs.isNotEmpty(), vision = images > 0, images = images,
            estimatedTokens = TokenEstimate.of(history) + overhead,
        )
    }

    companion object {
        /** Values for [RunEvent.ModelReply.role]; null when routing is not in effect. */
        const val ROLE_MAIN = "main"
        const val ROLE_LIGHT = "light"
        /** The light model's reply was not executed and this step asked the primary model instead; still a model call, and usage is recorded as usual. */
        const val ROLE_LIGHT_REJECTED = "light_rejected"
        /** With fallbacks available, give the current primary model only one 429 retry, so a single model's cooldown cannot drain the task budget. */
        private const val RATE_LIMIT_FAILOVER_RETRIES = 1
        /** With fallbacks available, switch to a fallback right away when Retry-After exceeds this. */
        private const val RATE_LIMIT_FAILOVER_WAIT_BUDGET_MS = 5_000L
    }
}

/** Reads still fully visible in the sent history (by ID): tool results carrying element lists and action results carrying a screen object. */
internal fun visibleReads(messages: List<Message>): Set<String> {
    val out = HashSet<String>()
    for (m in messages) for (p in m.parts) {
        if (p !is Part.ToolResult) continue
        val obj = try { Json.parseToJsonElement(p.content) as? JsonObject } catch (e: SerializationException) { null } ?: continue
        val shown = (obj["screen"] as? JsonObject) ?: obj.takeIf(ScreenElementTable::hasRows) ?: continue
        (shown["snapshotId"] as? JsonPrimitive)?.contentOrNull?.let(out::add)
    }
    return out
}
