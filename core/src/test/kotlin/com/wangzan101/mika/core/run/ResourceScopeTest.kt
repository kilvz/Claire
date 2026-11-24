package com.wangzan101.mika.core.run

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class ResourceScopeTest {
    private val released = CopyOnWriteArrayList<String>()

    private inner class Res(val name: String) {
        fun release() { released += name }
    }

    /** The task is cancelled after the resource is built but before it is handed back to the caller: the withContext that switches threads throws on hand-back, but the resource still belongs to the scope and is released during cleanup. */
    @Test
    fun cancelledRightAfterCreationStillReleased() = runBlocking {
        val scope = ResourceScope()
        val outer = launch(Dispatchers.Default) {
            val task = currentCoroutineContext().job
            scope.acquire(Dispatchers.IO, Res::release) { Res("display").also { task.cancel() } }
            fail("cancellation should surface after creation")
        }
        outer.join()
        assertTrue(outer.isCancelled)
        scope.close()
        assertEquals(listOf("display"), released)
    }

    @Test
    fun normalPathReleasesOnceInReverseOrder() = runBlocking {
        val scope = ResourceScope()
        val a = scope.acquire(Dispatchers.IO, Res::release) { Res("a") }
        scope.acquire(Dispatchers.Default, Res::release) { Res("b") }
        scope.acquire(Dispatchers.IO, Res::release) { Res("c") }
        assertEquals("a", a.name)
        assertEquals(emptyList<String>(), released)
        scope.close()
        scope.close()
        assertEquals(listOf("c", "b", "a"), released)
    }

    /** Release also runs in the context given at registration (for things that can only be closed on the main thread, for example). */
    @Test
    fun releaseRunsInAcquireContext() = runBlocking {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "owner-thread") }
        try {
            val scope = ResourceScope()
            var thread: String? = null
            scope.acquire(pool.asCoroutineDispatcher(), { _: Res -> thread = Thread.currentThread().name }) { Res("web") }
            scope.close()
            assertTrue(thread.toString(), thread!!.startsWith("owner-thread"))
        } finally {
            pool.shutdown()
        }
    }

    /** If one fails to release, the rest still are; the first error is thrown after everything has been released. */
    @Test
    fun failingReleaseDoesNotSkipOthers() = runBlocking {
        val scope = ResourceScope()
        scope.acquire(Dispatchers.IO, Res::release) { Res("a") }
        scope.acquire(Dispatchers.IO, { _: Res -> throw IllegalStateException("boom") }) { Res("b") }
        scope.acquire(Dispatchers.IO, Res::release) { Res("c") }
        try {
            scope.close()
            fail("release failure should surface")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(listOf("c", "a"), released)
    }

    /** The scope is already closed: anything acquired afterwards is released immediately, so the caller gets an error instead of an orphaned resource. */
    @Test
    fun acquireAfterCloseReleasesImmediately() = runBlocking {
        val scope = ResourceScope()
        scope.close()
        try {
            scope.acquire(Dispatchers.IO, Res::release) { Res("late") }
            fail("closed scope should refuse")
        } catch (e: IllegalStateException) {
            assertTrue(e !is CancellationException)
        }
        assertEquals(listOf("late"), released)
    }

    /** Several threads acquire and close at the same time: each is released exactly once. */
    @Test
    fun concurrentAcquireAndCloseReleaseEachOnce() = runBlocking {
        repeat(50) { round ->
            released.clear()
            val scope = ResourceScope()
            val made = CopyOnWriteArrayList<String>()
            val jobs = (0 until 8).map { i ->
                async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
                    runCatching { scope.acquire(Dispatchers.IO, Res::release) { Res("$round-$i").also { made += it.name } } }
                }
            }
            val closers = (0 until 3).map { async(Dispatchers.Default) { scope.close() } }
            jobs.awaitAll()
            closers.awaitAll()
            scope.close()
            assertEquals(made.sorted(), released.sorted())
        }
    }

    @Test
    fun acquireReturnsCreatedObject() = runBlocking {
        val scope = ResourceScope()
        val r = Res("x")
        assertSame(r, scope.acquire(Dispatchers.IO, Res::release) { r })
        scope.close()
    }
}
