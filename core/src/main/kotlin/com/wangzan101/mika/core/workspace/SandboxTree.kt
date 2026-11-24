package com.wangzan101.mika.core.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Owner of the `files/sandbox/` tree: anyone who wants to write into the tree takes a permit here first. One instance per process, supplied by the App and passed through constructor parameters to the places that write.
 *
 * - [exclusive]: changes the tree's structure, or deletes large parts of it (install/unpack/swap the root filesystem, reset, wipe or delete a workspace; in the App via `SandboxDirs.exclusiveWrite`). Nobody else is writing while it is held.
 * - [shared]: adds things to an existing tree (skill sync, running commands, web downloads, attachment import). Many may run at once,
 *   but they exclude [exclusive]: while a reset is half-way through deleting, nobody can create a directory inside it again (which would make the final rmdir fail with ENOTEMPTY).
 *
 * While someone is waiting for [exclusive], newly arriving [shared] requests also queue behind it, so a reset cannot be starved by a steady stream of commands.
 * If the same coroutine chain already holds a permit (of either kind), taking it again is allowed straight away, so nested calls do not deadlock on themselves.
 *
 * - [lease]: a shared permit for long-lived processes (stdio MCP servers inside the sandbox), held for as long as they live but revocable:
 *   as soon as [exclusive] acquires the entry it revokes all leases (telling holders to stop their processes and releasing the permit immediately), so a long-lived process cannot block it for tens of minutes;
 *   a lease that is never closed cannot block a reset either. After the root filesystem is swapped or the workspace wiped, the process could not keep running anyway, and stopping it is cleaner than letting it continue on a deleted directory.
 */
class SandboxTree {
    /** Must be acquired before starting: [shared] holds it only briefly, [exclusive] holds it throughout, so when writers queue up new readers wait too. */
    private val entry = Mutex()
    /** Locked while any [shared] is in use. */
    private val noShared = Mutex()
    private val count = Mutex()
    private var holders = 0

    /** Which trees' permits this coroutine chain currently holds. */
    private class Held(val trees: Set<SandboxTree>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>
    }

    private suspend fun heldTrees() = currentCoroutineContext()[Held]?.trees.orEmpty()

    suspend fun <T> exclusive(block: suspend () -> T): T {
        val held = heldTrees()
        if (this in held) return block()
        return entry.withLock {
            revokeLeases()
            noShared.withLock { withContext(Held(held + this)) { block() } }
        }
    }

    /** Registered leases (including ones still queued); [exclusive] revokes them one by one while holding [entry]. */
    private val leases = LinkedHashSet<Lease>()

    /**
     * A long-held shared permit, released on [Lease.close]. When [exclusive] needs to run, it first calls [onRevoke] (on the IO thread, where it may block waiting for the process to exit)
     * and then releases the permit; afterwards the holder may close as usual without error. A lease revoked while queued re-acquires after that [exclusive] finishes, and a lease handed back was never revoked before it was acquired.
     * Anything the holder starts after acquiring the lease must check [Lease.revoked] itself: a revocation may land exactly between acquisition and start.
     */
    suspend fun lease(onRevoke: () -> Unit): Lease {
        check(this !in heldTrees()) { "lease() must not be called while holding the tree" }
        while (true) {
            val lease = Lease(onRevoke)
            // Register before queueing: a lease still queued at the entry can also be revoked, so there is no window where the permit is held but unregistered and blocks a reset
            synchronized(leases) { leases += lease }
            val acquired = CompletableDeferred<Boolean>()
            val holder = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    shared {
                        acquired.complete(lease.markHeld())
                        lease.released.await()
                    }
                } finally {
                    synchronized(leases) { leases -= lease }
                    acquired.complete(false)
                }
            }
            val ok = try {
                acquired.await()
            } catch (e: Throwable) {
                lease.close()
                holder.cancel()
                throw e
            }
            if (ok) return lease
        }
    }

    private suspend fun revokeLeases() {
        val all = synchronized(leases) { leases.toList() }
        if (all.isEmpty()) return
        withContext(Dispatchers.IO + NonCancellable) { for (l in all) l.revoke() }
    }

    /** See [lease]. */
    class Lease internal constructor(private val onRevoke: () -> Unit) : java.io.Closeable {
        internal val released = CompletableDeferred<Unit>()
        private var held = false

        /** Has been revoked by [exclusive]; after revocation the permit is already released, and the holder must not write into the tree any more. */
        @Volatile var revoked = false
            private set

        /** Called when the permit is acquired: returns false if it was already revoked before that. */
        internal fun markHeld(): Boolean = synchronized(this) { if (revoked) { released.complete(Unit); false } else { held = true; true } }

        internal fun revoke() {
            val wasHeld = synchronized(this) {
                if (revoked || released.isCompleted) return
                revoked = true
                held
            }
            // A lease still queued needs nothing stopped: it will re-acquire after this run
            if (wasHeld) runCatching { onRevoke() }
            released.complete(Unit)
        }

        override fun close() { released.complete(Unit) }
    }

    suspend fun <T> shared(block: suspend () -> T): T {
        val held = heldTrees()
        if (this in held) return block()
        entry.withLock { count.withLock { if (holders++ == 0) noShared.lock() } }
        try {
            return withContext(Held(held + this)) { block() }
        } finally {
            withContext(NonCancellable) { count.withLock { if (--holders == 0) noShared.unlock() } }
        }
    }
}
