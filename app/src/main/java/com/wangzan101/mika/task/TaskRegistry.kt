package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.MemoryRunStore
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.core.run.RunStore
import com.wangzan101.mika.core.run.StoredRun
import com.wangzan101.mika.core.screen.uistate.AppWait

sealed interface StartResult<out R> {
    data class Started<R>(val run: R) : StartResult<R>
    /** Above the parallelism limit, queued at position [position]. */
    data class Queued<R>(val run: R, val position: Int) : StartResult<R>
    /** An already registered channel message is never executed again, including ones the server redelivers after a process restart. */
    data object DuplicateChannelMessage : StartResult<Nothing>
    /** A run has already been registered for this queued item; it must never execute again, whether that run was later interrupted, cancelled or finished. */
    data object DuplicateFollowUp : StartResult<Nothing>
    /** This conversation already has a task (running or queued): it is not accepted per the M1 rule, and the conversation queues it itself. */
    data object AlreadyRunningInConversation : StartResult<Nothing>
}

/**
 * Tasks indexed by conversation, the parallelism limit and the cross-task global locks. With a limit of 1 (the switch off) this is M1's one-at-a-time.
 * [create] builds the task object at registration; R is [TaskRun] in the product and a plain object in tests.
 */
class TaskCenter<R : Any>(
    private val limit: () -> ParallelLimit,
    private val conversationOf: (R) -> String,
    /** The task's unique ID (the notification buttons carry it); two consecutive tasks in the same conversation have different IDs. */
    private val keyOf: (R) -> String,
    val store: RunStore = MemoryRunStore(),
    val resources: ResourceScheduler = ResourceScheduler(),
    /** Persistent queue of the next jobs for a conversation; replaced by an in-memory implementation in tests. */
    val followUps: FollowUpQueue = FollowUpQueue(),
    /** The key used to serialize within a workspace; isolated per conversation by default, and once a workspace resolver is supplied, conversations in the same project queue up serially. */
    private val workspaceOf: (R) -> String = conversationOf,
    private val resolveWorkspace: (String) -> String = { it },
    private val create: (conversationId: String, goal: String) -> R,
) {
    private val running = mutableListOf<R>()
    private val queued = mutableListOf<R>()
    /** Callers still being migrated keep taking resources from here; the actual ownership belongs to [resources]. */
    val clipLock get() = resources.clipboard
    val takeoverQueue get() = resources.takeoverQueue

    @Synchronized
    fun tryStart(conversationId: String, goal: String, queueInConversation: Boolean = false,
        channelPeer: ChannelPeer? = null, channelMessageId: String? = null,
        followUp: QueuedFollowUp? = null): StartResult<R> {
        if (channelMessageId != null && store.all().any {
            it.channelMessageId == channelMessageId && it.channelPeer == channelPeer
        }) return StartResult.DuplicateChannelMessage
        if (followUp != null && store.all().any { it.followUpId == followUp.id }) {
            followUps.remove(followUp.id)
            return StartResult.DuplicateFollowUp
        }
        val workspace = resolveWorkspace(conversationId)
        val sameConversation = byConversation(conversationId) != null
        if (sameConversation && !queueInConversation) {
            // Tapping send collided with task teardown: put it back into the queue under the same lock, so the caller knows the next job is still queued
            followUp?.let { followUps.add(it) }
            return StartResult.AlreadyRunningInConversation
        }
        val sameWorkspaceActive = runs().any { workspaceOf(it) == workspace }
        val run = create(conversationId, goal)
        val record = StoredRun(RunId(keyOf(run)), conversationId, goal, RunPhase.Preparing,
            channelPeer = channelPeer, channelMessageId = channelMessageId, followUpId = followUp?.id)
        return if (!sameConversation && !sameWorkspaceActive && running.size < limit().max) {
            store.save(record)
            running += run
            StartResult.Started(run)
        } else {
            // The capacity queue also keeps the persistent queued item and removes it only on the actual markRunning; a process death leaves no unrecoverable gap.
            store.save(record.copy(phase = RunPhase.Queued))
            queued += run
            StartResult.Queued(run, queued.size)
        }
    }

    @Synchronized fun runs(): List<R> = running + queued
    @Synchronized fun running(): List<R> = running.toList()
    @Synchronized fun queued(): List<R> = queued.toList()
    @Synchronized fun isQueued(run: R): Boolean = queued.any { it === run }
    @Synchronized fun byConversation(id: String): R? = runs().firstOrNull { conversationOf(it) == id }
    @Synchronized fun byKey(key: String): R? = runs().firstOrNull { keyOf(it) == key }

    /**
     * Task [owner] wants to open [pkg] on its own display: claim it and return true when nobody holds it, when the caller itself holds it, or when the holding task
     * no longer uses it ([stillUsed] is false, e.g. the app was closed by itself); otherwise another task is using it and false is returned. Checking and claiming happen under the same lock, so when two tasks open the same app at once only one can succeed.
     */
    @Synchronized
    fun reserve(pkg: String, owner: String, stillUsed: () -> Boolean): Boolean {
        return resources.reserveApp(pkg, owner, { byKey(it) != null }, stillUsed)
    }

    /** Whether task [owner] could claim [pkg] right now (check only, no claim; same rules as [reserve]). */
    @Synchronized
    fun appFree(pkg: String, owner: String, stillUsed: () -> Boolean): Boolean =
        resources.appFree(pkg, owner, { byKey(it) != null }, stillUsed)

    /**
     * Task [owner] waits for another task to release [pkg], for at most [timeoutMs] (see [ResourceScheduler.awaitApp]: tasks waiting on each other don't wait).
     * It waits suspended without taking the lock here; membership is judged by the task list at the moment the wait starts.
     */
    suspend fun awaitApp(pkg: String, owner: String, timeoutMs: Long, free: suspend () -> Boolean): AppWait {
        val active = synchronized(this) { runs().map(keyOf).toSet() }
        return resources.awaitApp(pkg, owner, timeoutMs, { it in active }, free)
    }

    /** Remove it from the queue and return true if it was still queued (the caller records it as cancelled); return false if it has already started, and the task cancels itself. */
    @Synchronized
    fun cancelIfQueued(run: R): Boolean {
        if (queued.none { it === run }) return false
        val record = store.get(RunId(keyOf(run)))
        record?.let { store.save(it.copy(phase = RunPhase.Ended)) }
        record?.followUpId?.let(followUps::remove)
        return queued.removeAll { it === run }
    }

    /** A task ended (or was cancelled while queued); returns the tasks released from the queue that should now start running. */
    @Synchronized
    fun onEnded(run: R): List<R> {
        if (queued.removeAll { it === run }) return emptyList()
        running.removeAll { it === run }
        resources.releaseOwner(keyOf(run))
        val started = mutableListOf<R>()
        while (queued.isNotEmpty() && running.size < limit().max) {
            val index = queued.indexOfFirst { candidate ->
                val conv = conversationOf(candidate)
                val ws = workspaceOf(candidate)
                running.none { conversationOf(it) == conv || workspaceOf(it) == ws } &&
                    queued.takeWhile { it !== candidate }.none {
                        conversationOf(it) == conv || workspaceOf(it) == ws
                    }
            }
            if (index < 0) break
            val next = queued.removeAt(index)
            running += next
            store.get(RunId(keyOf(next)))?.let { store.save(it.copy(phase = RunPhase.Preparing)) }
            started += next
        }
        return started
    }

    @Synchronized fun markRunning(run: R) {
        val record = store.get(RunId(keyOf(run)))
        record?.let { store.save(it.copy(phase = RunPhase.Running, roundInProgress = true)) }
        // Only here does the queued item's persistent lifetime end; a crash before this is deduplicated by the run ledger and reported as interrupted.
        record?.followUpId?.let(followUps::remove)
    }

    /** A detailed record has been opened: the ledger only stores its ID, so after a process restart the events can be read to build the summary. */
    @Synchronized fun markRecord(run: R, recordId: String) {
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(recordId = recordId)) }
    }

    @Synchronized fun markResult(run: R, reply: String?, deliveryId: String? = null, successful: Boolean? = null) {
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(phase = RunPhase.Aftercare, reply = reply,
            deliveryId = deliveryId, runSucceeded = successful)) }
    }

    @Synchronized fun markDelivered(run: R) {
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(phase = RunPhase.Ended, roundInProgress = false)) }
    }

    /**
     * User text received at the teardown boundary that can no longer be injected into the original Run: returns true if this conversation has no task
     * right now (the caller tries to start); if it has one, it is written to the persistent queue first, returning false on success and true on failure so the caller goes through the concurrency-guarded persistent retry.
     * The check and the queueing happen under the same lock, so it can't interleave with [onEnded].
     */
    @Synchronized fun deferOrStart(threadId: String, text: String, id: String, source: QueuedSource = QueuedSource.Fallback): Boolean {
        if (byConversation(threadId) == null) return true
        // Even a failed write is handed to startFollowUp for one more try; tryStart blocks concurrency under the same lock, so an unpersisted item is never reported as queued.
        return !followUps.add(QueuedFollowUp(id, threadId, text, System.currentTimeMillis(), source))
    }

    /** Confirm the queued item is persisted before starting; a claimed item already in the ledger doesn't need rewriting. */
    @Synchronized fun persistFollowUp(item: QueuedFollowUp): Boolean {
        if (store.all().any { it.followUpId == item.id }) {
            followUps.remove(item.id)
            return true
        }
        return followUps.containsId(item.id) || followUps.add(item)
    }

    /** The next job for this conversation; only one is taken, and only when its conversation has no task. */
    @Synchronized fun takeReady(conversationId: String? = null): List<QueuedFollowUp> {
        val started = store.all().mapNotNull { it.followUpId }.toSet()
        started.forEach(followUps::remove)
        return followUps.claimNext({ byConversation(it) == null }, conversationId) { it.id in started }
    }

    @Synchronized fun cancelFollowUp(id: String): Boolean = followUps.cancel(id) != null

    @Synchronized fun dropFollowUps(conversationId: String) = followUps.dropConversation(conversationId)

    @Synchronized fun hasFollowUps(conversationId: String): Boolean = followUps.contains(conversationId)
}

/** Legacy name from the migration period; existing callers and tests can move to TaskCenter gradually. */
typealias TaskRegistry<R> = TaskCenter<R>
