package com.wangzan101.mika.core.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The lease for long-running processes: normally it blocks exclusive access like shared, but an exclusive request revokes it instead of being blocked. */
class SandboxTreeLeaseTest {
    @Test fun exclusiveRevokesLeaseInsteadOfWaiting() = runBlocking {
        val tree = SandboxTree()
        val stopped = AtomicInteger()
        val lease = tree.lease { stopped.incrementAndGet() }
        val r = withTimeout(5_000) { tree.exclusive { 42 } }
        assertEquals(42, r)
        assertEquals(1, stopped.get())
        assertTrue(lease.revoked)
        lease.close() // After revocation the holder closes as usual, without error
        assertEquals(1, stopped.get())
    }

    @Test fun closedLeaseFreesThePermitWithoutRevoking() = runBlocking {
        val tree = SandboxTree()
        val stopped = AtomicInteger()
        val lease = tree.lease { stopped.incrementAndGet() }
        lease.close()
        withTimeout(5_000) { tree.exclusive { } }
        assertEquals(0, stopped.get())
        assertFalse(lease.revoked)
    }

    /** A lease requested while exclusive is held queues behind it and has not been revoked when granted. */
    @Test fun leaseRequestedDuringExclusiveWaitsAndIsFresh() = runBlocking {
        val tree = SandboxTree()
        val stopped = AtomicInteger()
        val inside = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ex = async(Dispatchers.Default) { tree.exclusive { inside.complete(Unit); release.await() } }
        inside.await()
        val pending = async(Dispatchers.Default) { tree.lease { stopped.incrementAndGet() } }
        delay(100)
        assertFalse(pending.isCompleted)
        release.complete(Unit)
        ex.await()
        val lease = withTimeout(5_000) { pending.await() }
        assertFalse(lease.revoked)
        assertEquals(0, stopped.get())
        lease.close()
    }

    /** A plain shared still blocks exclusive; the lease does not change that. */
    @Test fun plainSharedStillBlocksExclusive() = runBlocking {
        val tree = SandboxTree()
        val inside = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val sh = async(Dispatchers.Default) { tree.shared { inside.complete(Unit); release.await() } }
        inside.await()
        val ex = async(Dispatchers.Default) { tree.exclusive { 1 } }
        delay(100)
        assertFalse(ex.isCompleted)
        release.complete(Unit)
        sh.await()
        assertEquals(1, withTimeout(5_000) { ex.await() })
    }
}
