package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GroupStrategy
import com.wangzan101.mika.core.model.MemberKey
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelGroup
import com.wangzan101.mika.core.model.ModelNeed
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.net.ProxyFailure
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

/** Whether a failed call should switch members, and how long that member should cool down. */
object FailoverPolicy {
    /**
     * Whether switching members can solve it. Rate limiting, 5xx, network, auth, quota, model missing/unavailable,
     * unsupported tools, and expired sign-in all switch; content refusal, context too long, a malformed request, and
     * parse failures do not (see the retry classification in [ModelErrors.transient]).
     */
    fun canFailover(e: Throwable): Boolean = when (e) {
        is ModelError.RateLimited, is ModelError.Network, is ModelError.StreamCut -> true
        is ModelError.Proxy -> e.failure == ProxyFailure.Unreachable
        is ModelError.Server -> e.status >= 500 || e.status == 429
        is ModelError.Auth, is ModelError.QuotaExceeded, is ModelError.ModelNotFound, is ModelError.ModelNotAvailable,
        is ModelError.ToolsUnsupported, is ModelError.ChatGptLimitReached, is ModelError.ChatGptNotEligible,
        is ModelError.SignInExpired, is ModelError.AccountPlanNotAllowed, is ModelError.DailyBudgetExceeded -> true
        else -> false
    }

    /** How long this member cools down after [e]. Consecutive failures double it; rate limiting follows Retry-After; auth/quota classes get longer. */
    fun cooldownMs(e: Throwable, failures: Int): Long = when (e) {
        is ModelError.RateLimited -> (e.retryAfterMs ?: 30_000L).coerceIn(5_000L, 30 * 60_000L)
        is ModelError.Auth, is ModelError.QuotaExceeded, is ModelError.SignInExpired,
        is ModelError.ChatGptLimitReached, is ModelError.ChatGptNotEligible, is ModelError.AccountPlanNotAllowed,
        is ModelError.DailyBudgetExceeded ->
            5 * 60_000L
        else -> (30_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(10 * 60_000L)
    }
}

/** A member's health over the recent period; in memory only, cleared on process restart. */
data class MemberHealth(
    val consecutiveFailures: Int = 0,
    val calls: Long = 0,
    val failures: Long = 0,
    val cooldownUntilMs: Long = 0,
    val ewmaLatencyMs: Double = 0.0,
    val latencySamples: Int = 0,
    val lastError: String? = null,
) {
    /** Lower is better; members without samples rank behind, and the strategy's exploration probability decides whether to try them. */
    fun score(): Double {
        if (latencySamples == 0) return Double.MAX_VALUE
        // Only consecutive failures count: one success resets it, so an early failure cannot permanently drag the score down
        return ewmaLatencyMs * (1 + 2.0 * consecutiveFailures)
    }
}

/** Health state per member, shared by [MemberKey]: the same model from the same provider cools down once no matter how many configs or groups reference it. */
class ModelHealthRegistry(private val now: () -> Long = System::currentTimeMillis) {
    private val states = HashMap<MemberKey, MemberHealth>()

    @Synchronized
    fun snapshot(key: MemberKey): MemberHealth = states[key] ?: MemberHealth()

    /** Available once the cooldown has ended (or if it never failed). After a cooldown ends one request is let through; a success restores it naturally. */
    @Synchronized
    fun available(key: MemberKey): Boolean = now() >= (states[key]?.cooldownUntilMs ?: 0)

    @Synchronized
    fun noteSuccess(key: MemberKey, latencyMs: Long) {
        val old = states[key] ?: MemberHealth()
        val samples = old.latencySamples + 1
        val ewma = if (old.latencySamples == 0) latencyMs.toDouble() else old.ewmaLatencyMs * 0.7 + latencyMs * 0.3
        states[key] = old.copy(
            consecutiveFailures = 0, calls = old.calls + 1, cooldownUntilMs = 0,
            ewmaLatencyMs = ewma, latencySamples = samples,
        )
    }

    @Synchronized
    fun noteFailure(key: MemberKey, error: Throwable) {
        val old = states[key] ?: MemberHealth()
        val unhealthy = FailoverPolicy.canFailover(error)
        // Content refusal, context too long, or a malformed request does not mean the member is broken: only the count is recorded, not consecutive failures/cooldown/latency score
        val failures = if (unhealthy) old.consecutiveFailures + 1 else old.consecutiveFailures
        val cooldown = if (unhealthy) now() + FailoverPolicy.cooldownMs(error, failures) else old.cooldownUntilMs
        states[key] = old.copy(
            consecutiveFailures = failures, calls = old.calls + 1, failures = old.failures + 1,
            cooldownUntilMs = cooldown, lastError = error::class.simpleName,
        )
    }

    @Synchronized
    fun reset() {
        states.clear()
    }
}

/** Pins the same conversation to one member; LRU capped at [limit], cleared on process restart. */
class StickyTable(private val limit: Int = 512) {
    private val map = object : LinkedHashMap<String, MemberKey>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MemberKey>): Boolean = size > limit
    }

    @Synchronized
    fun get(key: String): MemberKey? = map[key]

    @Synchronized
    fun put(key: String, value: MemberKey) {
        map[key] = value
    }

    @Synchronized
    fun remove(key: String) {
        map.remove(key)
    }
}

/** One member in a pool: the real model plus metadata used for selection. */
data class PoolMember(
    val key: MemberKey,
    val model: ChatModel,
    val priority: Int = 0,
    val weight: Int = 1,
    val costRank: Int? = null,
    /** The provider's display name; used only in the run log, never for selection. */
    val providerName: String = "",
) {
    /** Label in the run log: provider · model. */
    val label: String get() = if (providerName.isEmpty()) key.model else "$providerName · ${key.model}"
}

/**
 * A set of models one call can choose from. A single model is also wrapped as a pool (with one member), so
 * ModelCaller has only one path; [GroupPool] is the implementation with multiple members, health, and stickiness.
 */
interface ModelPool {
    val members: List<PoolMember>
    val groupName: String?

    /** The capabilities the pool as a whole promises callers; used by callers that do not go through ModelCaller. */
    val capabilities: Capabilities

    /** Orders the attempt sequence by this request's needs and the strategy; an empty list means no member is available. */
    fun ordered(need: ModelNeed): List<PoolMember>

    /** Result of one attempt: [error] is null on success; used to update health and stickiness. */
    fun noteResult(member: PoolMember, latencyMs: Long?, error: Throwable?)

    /** Whether [model] is a member of this pool (used to tell whether a reply came from the light model). */
    fun owns(model: ChatModel): Boolean
}

/** Single-member pool: behavior is exactly as it was before groups existed. */
class SingleMemberPool(override val members: List<PoolMember>) : ModelPool {
    constructor(model: ChatModel) : this(listOf(PoolMember(MemberKey("", model.name), model)))

    override val groupName: String? = null
    override val capabilities: Capabilities get() = members.first().model.capabilities

    override fun ordered(need: ModelNeed): List<PoolMember> = members

    override fun noteResult(member: PoolMember, latencyMs: Long?, error: Throwable?) = Unit

    override fun owns(model: ChatModel): Boolean = members.any { it.model === model }
}

/** Any ChatModel can be used as a pool; GroupPool is itself a pool. */
fun ChatModel.asPool(): ModelPool = if (this is ModelPool) this else SingleMemberPool(this)

/** Current accumulator values for weighted round-robin, shared per group: each task builds a new pool, and the accumulators must not be lost along with it. Cleared on process restart. */
class GroupWeights {
    private val perGroup = HashMap<String, HashMap<MemberKey, Int>>()

    @Synchronized
    fun forGroup(groupId: String): MutableMap<MemberKey, Int> = perGroup.getOrPut(groupId) { HashMap() }
}

/**
 * The pool built from a model group. Besides [ModelPool], it also implements [ChatModel]/[StreamingChatModel] for
 * side calls that bypass [ModelCaller] (title generation, classification, review, image reading); that path does its
 * own light failover and takes no part in context compaction.
 *
 * [stickyKey] is the "group + conversation" key; when null there is no stickiness (one-off calls). [weightedWeights]
 * is supplied per group by [GroupWeights].
 */
class GroupPool(
    private val group: ModelGroup,
    override val members: List<PoolMember>,
    private val health: ModelHealthRegistry,
    private val stickyTable: StickyTable,
    private val stickyKey: String?,
    private val weightedWeights: MutableMap<MemberKey, Int> = HashMap(),
    private val random: Random = Random.Default,
    private val now: () -> Long = System::currentTimeMillis,
) : ModelPool, ChatModel, StreamingChatModel {

    override val groupName: String get() = group.name.ifBlank { group.id }

    override val name: String get() = groupName

    override val capabilities: Capabilities = Capabilities(
        toolCalls = members.isNotEmpty() && members.all { it.model.capabilities.toolCalls },
        // The prompt shape is decided by the front-most member: if the primary member cannot see images, screenshots stay off, avoiding a permanent switch to a fallback member after the first image
        vision = (members.minByOrNull { it.priority })?.model?.capabilities?.vision == true,
        contextTokens = members.minOfOrNull { it.model.capabilities.contextTokens } ?: 0,
        reasoning = members.any { it.model.capabilities.reasoning },
        groundingVerified = members.isNotEmpty() && members.all { it.model.capabilities.groundingVerified },
        promptCaching = members.firstOrNull()?.model?.capabilities?.promptCaching ?: com.wangzan101.mika.core.model.PromptCaching.Implicit,
        maxImages = members.maxOfOrNull { it.model.capabilities.maxImages } ?: 0,
        compactionTriggerTokens = null,
    )

    private val orderIndex: Map<MemberKey, Int> = members.withIndex().associate { it.value.key to it.index }
    private val lock = Any()

    override fun ordered(need: ModelNeed): List<PoolMember> = synchronized(lock) {
        // Members whose window is too small are not excluded outright: ModelCaller compacts before sending. The sticky
        // member especially must be kept, or a long conversation would silently switch from the primary member to a
        // fallback with a larger window.
        val relaxed = eligible(need.copy(estimatedTokens = 0))
        if (relaxed.isEmpty()) return emptyList()
        val strict = eligible(need)
        val stickyMember = if (group.sticky && stickyKey != null) {
            relaxed.firstOrNull { it.key == stickyTable.get(stickyKey) }
        } else null
        val pool = if (stickyMember != null) relaxed else if (strict.isNotEmpty()) strict else relaxed
        val first = stickyMember ?: when (group.strategy) {
            GroupStrategy.Failover -> pool.minWith(compareBy({ it.priority }, { orderIndex[it.key] ?: Int.MAX_VALUE }))
            GroupStrategy.Weighted -> weightedFirst(pool)
            GroupStrategy.LeastLatency -> leastLatencyFirst(pool)
            GroupStrategy.CostFirst -> pool.minWith(compareBy({ it.costRank ?: Int.MAX_VALUE }, { it.priority }))
        }
        val firstIndex = pool.indexOfFirst { it === first }
        val rest = pool.filterIndexed { i, _ -> i != firstIndex }
            .sortedWith(compareBy({ it.priority }, { orderIndex[it.key] ?: Int.MAX_VALUE }))
        listOf(first) + rest
    }

    private fun eligible(need: ModelNeed): List<PoolMember> = members.filter { m ->
        val caps = m.model.capabilities
        (!need.toolCalls || caps.toolCalls) &&
            (!need.vision || caps.vision) &&
            (need.images == 0 || need.images <= caps.maxImages) &&
            (need.estimatedTokens == 0 || need.estimatedTokens <= caps.contextTokens) &&
            health.available(m.key)
    }

    /** Members with fewer than 3 samples keep a 10% exploration chance; the rest are ranked by recent speed and consecutive failures. */
    private fun leastLatencyFirst(pool: List<PoolMember>): PoolMember {
        val unsampled = pool.filter { health.snapshot(it.key).latencySamples < 3 }
        if (unsampled.isNotEmpty() && random.nextDouble() < 0.1) return unsampled.random(random)
        return pool.minByOrNull { health.snapshot(it.key).score() } ?: pool.first()
    }

    /** Smooth weighted round-robin: chosen once for a "new conversation", then pinned by stickiness. Accumulators are shared per group, so concurrent selection by different tasks is safe. */
    private fun weightedFirst(pool: List<PoolMember>): PoolMember = synchronized(weightedWeights) {
        var best: PoolMember? = null
        for (m in pool) {
            val w = (weightedWeights[m.key] ?: 0) + m.weight.coerceAtLeast(1)
            weightedWeights[m.key] = w
            if (best == null || w > (weightedWeights[best.key] ?: 0)) best = m
        }
        val total = pool.sumOf { it.weight.coerceAtLeast(1) }
        val chosen = best ?: pool.first()
        weightedWeights[chosen.key] = (weightedWeights[chosen.key] ?: 0) - total
        chosen
    }

    override fun noteResult(member: PoolMember, latencyMs: Long?, error: Throwable?) = synchronized(lock) {
        if (error == null) {
            health.noteSuccess(member.key, latencyMs ?: 0)
            if (group.sticky && stickyKey != null) stickyTable.put(stickyKey, member.key)
        } else {
            health.noteFailure(member.key, error)
            if (group.sticky && stickyKey != null && FailoverPolicy.canFailover(error)) stickyTable.remove(stickyKey)
        }
    }

    override fun owns(model: ChatModel): Boolean = members.any { it.model === model }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        attempt(system, messages, tools, onText = null)

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply = attempt(system, messages, tools, onText)

    /**
     * One failover for a side call. These calls bypass [ModelCaller] and do not know who produced each message, so
     * thinking content and tool-call private fields from other models are always stripped; one-off calls build fresh
     * messages anyway, so no useful context is lost. If text was already streamed, it is cleared before trying the
     * next member. No same-member retries here (that is [ModelCaller]'s job).
     */
    private suspend fun attempt(system: String, messages: List<Message>, tools: List<ToolSpec>, onText: ((String) -> Unit)?): ModelReply {
        val need = needOf(system, messages, tools)
        val candidates = ordered(need)
        if (candidates.isEmpty()) throw ModelError.GroupUnavailable(groupName, unavailableReason(need))
        val view = stripForeignPrivate(messages)
        var last: Throwable? = null
        for ((index, member) in candidates.withIndex()) {
            val started = now()
            try {
                val reply = if (onText != null && member.model is StreamingChatModel) {
                    (member.model as StreamingChatModel).completeStreaming(system, view, tools, onText)
                } else {
                    member.model.complete(system, view, tools).also { r ->
                        if (onText != null) {
                            r.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                                .takeIf { it.isNotEmpty() }?.let(onText)
                        }
                    }
                }
                noteResult(member, now() - started, null)
                return reply
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                noteResult(member, now() - started, e)
                last = e
                onText?.invoke("")
                if (!FailoverPolicy.canFailover(e) || index == candidates.lastIndex) throw e
            }
        }
        throw last ?: ModelError.GroupUnavailable(groupName)
    }

    private fun needOf(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelNeed {
        val images = messages.sumOf { m -> m.parts.count { it is Part.Image } }
        val overhead = TokenEstimate.of(system) + tools.sumOf { TokenEstimate.of(it.name + it.description + it.parametersJsonSchema) }
        return ModelNeed(
            toolCalls = tools.isNotEmpty(), vision = images > 0, images = images,
            estimatedTokens = TokenEstimate.of(messages) + overhead,
        )
    }

    private fun unavailableReason(need: ModelNeed): String = when {
        members.isEmpty() -> "no members in the group"
        need.toolCalls && members.none { it.model.capabilities.toolCalls } -> "no member supports tool calls"
        need.vision && members.none { it.model.capabilities.vision } -> "no member can view images"
        members.none { health.available(it.key) } -> "all members are cooling down"
        else -> "no member has a large enough context window"
    }
}

/** Strips thinking content and tool-call private fields produced by other models when switching members; they may only be returned verbatim to the model that produced them. */
internal fun stripForeignPrivate(messages: List<Message>): List<Message> = messages.map { m ->
    if (m.role != Role.Assistant || m.parts.none { it is Part.Thinking || (it is Part.ToolCall && it.opaque != null) }) m
    else m.copy(parts = m.parts.mapNotNull { p ->
        when {
            p is Part.Thinking -> null
            p is Part.ToolCall && p.opaque != null -> p.copy(opaque = null)
            else -> p
        }
    })
}
