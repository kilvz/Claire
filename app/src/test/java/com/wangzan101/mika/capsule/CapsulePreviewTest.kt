package com.wangzan101.mika.capsule

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CapsulePreviewTest {
    private class Frame(val pixels: String)

    @Test fun atMostOneFramePerIntervalKeepingTheLatest() = runTest {
        val source = MutableSharedFlow<Frame>(extraBufferCapacity = 16)
        val got = mutableListOf<String>()
        val job = launch { source.previewFrames(200).collect { got += it.pixels } }
        runCurrent()
        source.emit(Frame("1"))
        runCurrent()
        // Of the three frames arriving within 200 milliseconds only the last is kept
        source.emit(Frame("2"))
        source.emit(Frame("3"))
        source.emit(Frame("4"))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("1"), got)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf("1", "4"), got)
        job.cancel()
    }

    @Test fun unchangedFramesAreNotPushed() = runTest {
        val source = MutableSharedFlow<Frame>(extraBufferCapacity = 16)
        val got = mutableListOf<String>()
        val job = launch { source.previewFrames(200) { a, b -> a.pixels == b.pixels }.collect { got += it.pixels } }
        runCurrent()
        val first = Frame("a")
        source.emit(first)
        advanceTimeBy(300)
        runCurrent()
        // The same frame and a new copy with identical content: neither is pushed
        source.emit(first)
        advanceTimeBy(300)
        runCurrent()
        source.emit(Frame("a"))
        advanceTimeBy(300)
        runCurrent()
        source.emit(Frame("b"))
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("a", "b"), got)
        job.cancel()
    }
}
