package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import kotlinx.coroutines.CancellationException
import java.util.concurrent.CopyOnWriteArrayList

/** The event dispatcher of one Run. Recording, UI and delivery can each subscribe once; a single failing receiver does not affect the others. */
class RunEventBus : RunSink {
    private val listeners = CopyOnWriteArrayList<RunSink>()

    fun subscribe(listener: RunSink): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun event(e: RunEvent) {
        listeners.forEach { listener ->
            try { listener.event(e) }
            catch (e: CancellationException) { throw e }
            catch (_: Throwable) { /* the run must not be interrupted by a subscriber's failure */ }
        }
    }

    override fun media(mediaId: Int, png: ByteArray) {
        listeners.forEach { listener ->
            try { listener.media(mediaId, png) }
            catch (e: CancellationException) { throw e }
            catch (_: Throwable) { /* as above */ }
        }
    }
}
