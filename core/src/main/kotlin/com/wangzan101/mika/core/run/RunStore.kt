package com.wangzan101.mika.core.run

/** Minimal persisted record of a run's state. After an interruption a run is only reported and allowed to be re-run, never resumed from the middle of the screen. */
data class StoredRun(
    val id: RunId,
    val threadId: String,
    val goal: String,
    val phase: RunPhase,
    val reply: String? = null,
    val deliveryId: String? = null,
    val roundInProgress: Boolean = false,
    val triggerWindowKey: String? = null,
    val runSucceeded: Boolean? = null,
    /** Reply address and inbound id for a non-local task; when an old record lacks them it is still restored as a local task. */
    val channelPeer: ChannelPeer? = null,
    val channelMessageId: String? = null,
    /** The user's own words shown to people and recorded in the conversation; for a condition-check run [goal] is the generated check goal, while this stores the original condition text the user wrote. */
    val userText: String? = null,
    /** The id of the persisted queue entry; on recovery it is used to prevent a run that is already registered from being enqueued and executed again. */
    val followUpId: String? = null,
    /** The id of the detailed run record; after a process interruption it is only read to produce an account, never to replay tool calls. */
    val recordId: String? = null,
)

interface RunStore {
    fun save(record: StoredRun)
    fun get(id: RunId): StoredRun?
    fun all(): List<StoredRun>

    /** Marks runs as interrupted one by one on restart; tool calls must not silently run a second time. [live] holds the runs still running right now, which are left alone. */
    fun interruptUnfinished(live: Set<RunId> = emptySet()): List<StoredRun> {
        val interrupted = all().filter { it.phase != RunPhase.Ended && it.phase != RunPhase.Interrupted && it.id !in live }
        interrupted.forEach { save(it.copy(phase = RunPhase.Interrupted)) }
        return interrupted
    }
}

class MemoryRunStore : RunStore {
    private val records = linkedMapOf<RunId, StoredRun>()
    @Synchronized override fun save(record: StoredRun) { records[record.id] = record }
    @Synchronized override fun get(id: RunId): StoredRun? = records[id]
    @Synchronized override fun all(): List<StoredRun> = records.values.toList()
}
