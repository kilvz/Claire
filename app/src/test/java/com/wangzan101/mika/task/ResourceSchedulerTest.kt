package com.wangzan101.mika.task

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.screen.uistate.AppWait
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ResourceSchedulerTest {
    @Test fun simultaneousAppClaimsHaveExactlyOneOwnerAndReleaseFreesIt() {
        val resources = ResourceScheduler()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val wins = AtomicInteger()
        for (owner in listOf("a", "b")) Thread {
            start.await()
            if (resources.reserveApp("com.example", owner, { true }, { true })) wins.incrementAndGet()
            done.countDown()
        }.start()
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, wins.get())
        val first = resources.appHolder("com.example")!!
        resources.releaseOwner(first)
        assertTrue(resources.reserveApp("com.example", if (first == "a") "b" else "a", { true }, { true }))
    }

    @Test fun clipboardLeaseSerializesTwoTasks() {
        val resources = ResourceScheduler()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val secondEntered = AtomicBoolean(false)
        val first = Thread {
            resources.clipboard.lock()
            try { entered.countDown(); release.await() } finally { resources.clipboard.unlock() }
        }
        val second = Thread {
            resources.clipboard.lock()
            try { secondEntered.set(true) } finally { resources.clipboard.unlock(); secondFinished.countDown() }
        }
        first.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        second.start()
        assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS))
        assertFalse(secondEntered.get())
        release.countDown()
        assertTrue(secondFinished.await(5, TimeUnit.SECONDS))
        first.join(5_000)
    }

    // ---------- waiting for another task to release an app ----------

    private val active: (String) -> Boolean = { true }

    /** "Nobody in the registry holds it" counts as released (tests ignore other displays). */
    private fun ResourceScheduler.freeFor(pkg: String, owner: String): suspend () -> Boolean = { appFree(pkg, owner, active, { true }) }

    /** As soon as the holding task releases the app, the waiter wakes up immediately (without waiting for the next poll). */
    @Test fun releaseWakesTheWaiter() = runTest {
        val r = ResourceScheduler()
        assertTrue(r.reserveApp("com.chat", "a", active, { true }))
        val waiter = async { r.awaitApp("com.chat", "b", 60_000, active, r.freeFor("com.chat", "b"), pollMs = 30_000) }
        advanceTimeBy(5_000)
        assertFalse(waiter.isCompleted)
        r.releaseOwner("a")
        runCurrent()
        assertEquals(AppWait.Free, waiter.await())
        assertTrue("醒来远早于下一次轮询", currentTime < 30_000)
        assertTrue(r.reserveApp("com.chat", "b", active, { true }))
    }

    /** Still held when the deadline hits: reports honestly that the wait failed, and no longer counts as waiting. */
    @Test fun waitingTimesOut() = runTest {
        val r = ResourceScheduler()
        assertTrue(r.reserveApp("com.chat", "a", active, { true }))
        assertEquals(AppWait.TimedOut, r.awaitApp("com.chat", "b", 3_000, active, r.freeFor("com.chat", "b")))
        assertEquals(3_000L, currentTime)
        // b is no longer registered as waiting: a now waiting for the app b holds is not treated as a mutual wait
        assertTrue(r.reserveApp("com.mail", "b", active, { true }))
        assertEquals(AppWait.TimedOut, r.awaitApp("com.mail", "a", 1_000, active, r.freeFor("com.mail", "a")))
    }

    /** a holds chat and waits for mail held by b; if b then waits for chat that is a mutual wait: b does not wait at all, a keeps waiting. */
    @Test fun mutualWaitIsRefusedAtOnce() = runTest {
        val r = ResourceScheduler()
        assertTrue(r.reserveApp("com.chat", "a", active, { true }))
        assertTrue(r.reserveApp("com.mail", "b", active, { true }))
        val a = async { r.awaitApp("com.mail", "a", 60_000, active, r.freeFor("com.mail", "a")) }
        runCurrent()
        assertEquals(AppWait.WouldDeadlock, r.awaitApp("com.chat", "b", 60_000, active, r.freeFor("com.chat", "b")))
        assertEquals("没等:时间没走", 0L, currentTime)
        // b does not wait and releases mail when finishing up, so a gets its turn
        r.releaseOwner("b")
        runCurrent()
        assertEquals(AppWait.Free, a.await())
    }

    /** A three-task cycle (a waits for b, b waits for c, c waits for a again) is likewise detected; non-cyclic chains wait as usual. */
    @Test fun longerCyclesAreFoundAndChainsWithoutCycleWait() = runTest {
        val r = ResourceScheduler()
        for ((pkg, owner) in listOf("p.a" to "a", "p.b" to "b", "p.c" to "c")) assertTrue(r.reserveApp(pkg, owner, active, { true }))
        val a = async { r.awaitApp("p.b", "a", 60_000, active, r.freeFor("p.b", "a")) }
        val b = async { r.awaitApp("p.c", "b", 60_000, active, r.freeFor("p.c", "b")) }
        runCurrent()
        assertEquals(AppWait.WouldDeadlock, r.awaitApp("p.a", "c", 60_000, active, r.freeFor("p.a", "c")))
        // d holds nothing, so waiting for what a holds does not form a cycle
        val d = async { r.awaitApp("p.a", "d", 2_000, active, r.freeFor("p.a", "d")) }
        advanceTimeBy(2_001)
        assertEquals(AppWait.TimedOut, d.await())
        a.cancel(); b.cancel()
    }

    /** The holding task is already gone (it never released the app): it does not count as holding it, nor as a mutual wait. */
    @Test fun goneHolderDoesNotBlockOrDeadlock() = runTest {
        val r = ResourceScheduler()
        assertTrue(r.reserveApp("com.chat", "gone", active, { true }))
        val alive: (String) -> Boolean = { it != "gone" }
        assertEquals(AppWait.Free, r.awaitApp("com.chat", "b", 1_000, alive, { r.appFree("com.chat", "b", alive, { true }) }))
    }
}

