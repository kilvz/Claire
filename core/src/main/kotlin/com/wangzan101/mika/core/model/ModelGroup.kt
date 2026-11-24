package com.wangzan101.mika.core.model

/**
 * Strategy of a group. v1 implements [Failover]; [Weighted], [LeastLatency] and [CostFirst] keep their interfaces for now,
 * selection and recording already work, they are enabled in the UI gradually.
 */
enum class GroupStrategy(val wire: String) {
    /** In priority order; the next one is used only when the previous one fails. */
    Failover("failover"),

    /** A new conversation is assigned one member by weight and sticks to it afterwards; not round-robin per request. */
    Weighted("weighted"),

    /** Chosen by recent success rate and latency; members with few samples keep a bit of exploration. */
    LeastLatency("least-latency"),

    /** Chosen by the cost rank the user filled in; members without cost data are placed later. */
    CostFirst("cost-first");

    companion object {
        fun of(wire: String?): GroupStrategy = entries.firstOrNull { it.wire == wire } ?: Failover
    }
}

/**
 * A member of a group. [configId] points to the id of [com.wangzan101.mika.settings.ProviderConfig];
 * being a group member keeps that configuration from being removed by `ProviderStore.prune`.
 */
data class GroupMember(
    val configId: String,
    /** Smaller comes first; equal priorities follow the member list order. */
    val priority: Int = 0,
    /** 1..100, used only by [GroupStrategy.Weighted]. */
    val weight: Int = 1,
    val enabled: Boolean = true,
    /** Overrides the reasoning effort chosen for the role; null follows the role. Ignored when the member does not support it. */
    val reasoningEffort: String? = null,
    /** Used only by [GroupStrategy.CostFirst]: smaller means cheaper; null means the user did not fill it in. */
    val costRank: Int? = null,
)

/** Limits; not read in v1, wired up in v2. */
data class GroupLimits(
    val requestsPerMinute: Int? = null,
    val maxConcurrent: Int? = null,
    val dailyTokens: Long? = null,
    val dailyCostMicros: Long? = null,
)

/**
 * Model group: a set of concrete models, one is chosen to answer according to [strategy]. The order of [members] is the order in the UI;
 * the actual order follows [GroupMember.priority]. Groups cannot nest.
 */
data class ModelGroup(
    val id: String,
    val name: String,
    val strategy: GroupStrategy = GroupStrategy.Failover,
    val members: List<GroupMember> = emptyList(),
    /** Keep the same conversation on one member as far as possible to protect the prefix cache; on by default. */
    val sticky: Boolean = true,
    /** v3: allow tool-free, read-only parallel racing; off by default. */
    val raceReadOnly: Boolean = false,
    val limits: GroupLimits = GroupLimits(),
) {
    val enabledMembers: List<GroupMember> get() = members.filter { it.enabled }

    /**
     * Whether this group can be selected for a given use. The main model requires every member to support tool calls; vision requires at least one member that can see images;
     * lightweight is not enforced (lightSteps does not enable itself when tools are unsupported). When [capsOf] cannot get the configuration, treat it as unmet.
     */
    fun usableFor(role: ModelRole, capsOf: (String) -> Capabilities?): Boolean {
        val caps = enabledMembers.mapNotNull { capsOf(it.configId) }
        if (caps.isEmpty()) return false
        return when (role) {
            ModelRole.Main -> caps.all { it.toolCalls }
            ModelRole.Vision -> caps.any { it.vision }
            ModelRole.Light -> true
        }
    }
}

/** Stable identity of a member: provider + model name, not the configuration id (which changes when a configuration is deleted and recreated). */
data class MemberKey(val providerId: String, val model: String) {
    override fun toString(): String = if (providerId.isEmpty()) model else "$providerId/$model"
}

/** What this request requires of a member; computed by ModelCaller from the actual request. */
data class ModelNeed(
    val toolCalls: Boolean = false,
    val vision: Boolean = false,
    val images: Int = 0,
    /** Estimated tokens of the whole request (messages + fixed overhead), used to filter out members with too small a window. */
    val estimatedTokens: Int = 0,
)
