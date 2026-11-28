package com.wangzan101.mika.voice.ondevice

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * All loaded on-device recognition models live here: each pack (id + version) loads once and then stays resident;
 * it is released after [idleMs] of disuse or when system memory is tight ([release]), and loaded again on next use.
 *
 * Loading runs in [scope], independent of the caller: [prewarm] starts it on press-to-talk, and the load finishes and stays usable even if the caller cancels or times out.
 * Releasing never tears down the native object under an in-progress recognition; it waits for that call to end before closing. That call still returns text, and only later uses of the handle report [OnDeviceSttException].
 * Recognition also runs in [scope] (see [Held.transcribe]): native decoding cannot be cancelled, yet a caller timeout must return immediately.
 */
class OnDeviceEngines(
    private val backends: OnDeviceBackends,
    private val scope: CoroutineScope,
    private val idleMs: Long = IDLE_MS,
    private val options: EngineOptions = EngineOptions.default(),
    private val loadDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Loading one pack: once [ready] completes, the model is usable. */
    private inner class Slot(val version: Int) {
        val ready = CompletableDeferred<Held>()
        @Volatile var held: Held? = null
        /** Someone timed out waiting for this load; later callers no longer wait it out and report a timeout until it really finishes. */
        @Volatile var timedOut = false
    }

    private val lock = Any()
    private val slots = HashMap<String, Slot>()

    /** Loads in the background ahead of time (called on press-to-talk) without waiting; errors surface later at [engine]. */
    fun prewarm(pack: InstalledVoicePack) {
        if (usable(pack)) slot(pack)
    }

    /** The loaded model for this pack; loads it if needed, waiting at most [OnDeviceTimeout.LOAD_TIMEOUT_MS]. */
    suspend fun engine(pack: InstalledVoicePack): OnDeviceSttEngine {
        if (!usable(pack)) throw OnDeviceSttException(OnDeviceSttException.Kind.Unsupported, "no backend for ${pack.entry.backend}/${pack.entry.family} on this device")
        val slot = slot(pack)
        val timeout = { OnDeviceSttException(OnDeviceSttException.Kind.Timeout, "loading ${pack.entry.id} took over ${OnDeviceTimeout.LOAD_TIMEOUT_MS} ms") }
        // A stuck load has not finished: stop waiting the full limit every time; once it finishes later it is still usable
        if (slot.timedOut && !slot.ready.isCompleted) throw timeout()
        val held = withTimeoutOrNull(OnDeviceTimeout.LOAD_TIMEOUT_MS) { slot.ready.await() }
            ?: run {
                slot.timedOut = true
                throw timeout()
            }
        held.touch()
        return held
    }

    /** Releases all models (called when system memory is tight). */
    fun release() {
        val all = synchronized(lock) { slots.values.toList().also { slots.clear() } }
        all.forEach { retire(it, OnDeviceSttException.Kind.OutOfMemory) }
    }

    /** Releases one pack (called on deletion or version change). */
    fun release(packId: String) {
        synchronized(lock) { slots.remove(packId) }?.let { retire(it, OnDeviceSttException.Kind.Missing) }
    }

    private fun usable(pack: InstalledVoicePack): Boolean =
        backends.find(pack.entry.backend)?.let { it.supports(pack.entry.family) && it.abiSupported() } == true

    /** The load for the current version (running or done); starts one when absent. The old version is released first so both never hold memory at once. */
    private fun slot(pack: InstalledVoicePack): Slot {
        var stale: Slot? = null
        var created = false
        val slot = synchronized(lock) {
            slots[pack.entry.id]?.takeIf { it.version == pack.entry.version }
                ?: Slot(pack.entry.version).also { stale = slots.put(pack.entry.id, it); created = true }
        }
        stale?.let { retire(it, OnDeviceSttException.Kind.Missing) }
        if (created) start(pack, slot)
        return slot
    }

    private fun start(pack: InstalledVoicePack, slot: Slot) {
        val backend = backends.find(pack.entry.backend)!!
        scope.launch(loadDispatcher) {
            val outcome = try {
                Result.success(backend.load(pack, options))
            } catch (e: CancellationException) {
                Result.failure(e)
            } catch (e: OnDeviceSttException) {
                Result.failure(e)
            } catch (e: OutOfMemoryError) {
                Result.failure(OnDeviceSttException(OnDeviceSttException.Kind.OutOfMemory, "out of memory loading ${pack.entry.id}", e))
            } catch (e: UnsatisfiedLinkError) {
                Result.failure(OnDeviceSttException(OnDeviceSttException.Kind.Unsupported, "native library unavailable", e))
            } catch (e: Throwable) {
                Result.failure(OnDeviceSttException(OnDeviceSttException.Kind.Corrupt, "failed to load ${pack.entry.id}: ${e.message}", e))
            }
            outcome.fold(
                onSuccess = { engine ->
                    val held = Held(pack.entry.id, slot, engine)
                    slot.held = held
                    // Released while loading: close it right away instead of keeping it
                    if (slot.ready.complete(held)) held.touch() else engine.close()
                },
                onFailure = { error ->
                    // A failed load is not kept: the next use tries again
                    synchronized(lock) { if (slots[pack.entry.id] === slot) slots.remove(pack.entry.id) }
                    slot.ready.completeExceptionally(error)
                },
            )
        }
    }

    /** Still loading: close as soon as it finishes. Already loaded: wait for in-progress recognition to end, then close. */
    private fun retire(slot: Slot, kind: OnDeviceSttException.Kind) {
        if (slot.ready.completeExceptionally(OnDeviceSttException(kind, "voice pack released"))) return
        slot.held?.retire(kind)
    }

    /** Idle timeout: release when not recognizing; if a recognition is running, the timer restarts after it ends. */
    private fun idle(held: Held) {
        synchronized(lock) {
            if (slots[held.packId] === held.slot && held.retireIfIdle()) slots.remove(held.packId)
        }
    }

    /** The handle given to callers: tracks in-progress recognition and waits for it to finish before closing the native model on release. */
    private inner class Held(val packId: String, val slot: Slot, private val inner: OnDeviceSttEngine) : OnDeviceSttEngine {
        private var inFlight = 0
        /** How many calls the caller gave up on while native decoding is still running. */
        private var abandoned = 0
        private var retired: OnDeviceSttException.Kind? = null
        private var idleJob: Job? = null

        /**
         * Native decoding occupies a thread for its whole run (a JNI call that cannot be cancelled), so it runs separately in [scope] and the caller only waits:
         * a caller timeout or cancellation returns immediately while decoding finishes in the background. [inFlight] counts actual decode completion,
         * and release or idle shutdown waits for it before closing the native model. While an abandoned decode is still running, a new recognition reports a timeout (to try the next one)
         * instead of queueing on the same lock, and it never keeps occupying more threads.
         */
        override suspend fun transcribe(pcm: ShortArray, language: String?): String {
            synchronized(this) {
                retired?.let { throw released(it) }
                if (abandoned > 0) {
                    throw OnDeviceSttException(OnDeviceSttException.Kind.Timeout, "an earlier recognition with $packId is still running")
                }
                inFlight++
                idleJob?.cancel()
            }
            val call = Call()
            val work = scope.async { decode(pcm, language) }
            work.invokeOnCompletion { finished(call) }
            val text = try {
                work.await()
            } catch (e: CancellationException) {
                synchronized(this) {
                    if (!work.isCompleted) {
                        call.gaveUp = true
                        abandoned++
                    }
                }
                throw e
            }.getOrThrow()
            // Released mid-decode (deletion, version change, memory pressure) still returns text: the native object closes only after this decode ends,
            // and the backend serialises decode and close, so the text is valid; only later recognitions report that it was released
            return text
        }

        /** One recognition call and whether the caller abandoned it; read and written only inside synchronized(Held). */
        private inner class Call {
            var gaveUp = false
        }

        /** The actual decode; errors go into the result instead of escaping from [scope]. */
        private suspend fun decode(pcm: ShortArray, language: String?): Result<String> = try {
            Result.success(inner.transcribe(pcm, language))
        } catch (e: CancellationException) {
            Result.failure(e)
        } catch (e: OnDeviceSttException) {
            Result.failure(e)
        } catch (e: OutOfMemoryError) {
            Result.failure(OnDeviceSttException(OnDeviceSttException.Kind.OutOfMemory, "out of memory transcribing with $packId", e))
        } catch (e: Throwable) {
            // The model loaded fine, so an unexpected decode error is not file corruption: report Failed, and the UI only offers a retry instead of asking for another download
            Result.failure(OnDeviceSttException(OnDeviceSttException.Kind.Failed, "on-device model $packId failed to transcribe: ${e.message}", e))
        }

        /** A decode really ended (whether or not the caller is still waiting): close the native model if released and nothing else is running, otherwise restart the idle timer. */
        private fun finished(call: Call) {
            val closeNow = synchronized(this) {
                inFlight--
                if (call.gaveUp) abandoned--
                if (retired == null) {
                    scheduleIdle()
                    false
                } else {
                    inFlight == 0
                }
            }
            if (closeNow) runCatching { inner.close() }
        }

        fun touch() = synchronized(this) { if (retired == null && inFlight == 0) scheduleIdle() }

        /** Releases and closes when nothing is recognizing, returning whether it released; check and release are one step, so a just-started recognition cannot race in. */
        fun retireIfIdle(): Boolean {
            synchronized(this) {
                if (retired != null || inFlight > 0) return false
                retired = OnDeviceSttException.Kind.OutOfMemory
                idleJob?.cancel()
            }
            // Called from [scope] on idle timeout; a native close error must not escape
            runCatching { inner.close() }
            return true
        }

        fun retire(kind: OnDeviceSttException.Kind) {
            val closeNow = synchronized(this) {
                if (retired != null) return
                retired = kind
                idleJob?.cancel()
                inFlight == 0
            }
            // May be called on the main thread (onTrimMemory); a native close error must not escape
            if (closeNow) runCatching { inner.close() }
        }

        /**
         * Does nothing: this is a shared model, so a caller's `engine(pack).use {}` must not throw it away.
         * Release happens only through [OnDeviceEngines.release], `release(packId)` and the idle timer.
         */
        override fun close() = Unit

        private fun scheduleIdle() {
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(idleMs)
                idle(this@Held)
            }
        }

        private fun released(kind: OnDeviceSttException.Kind) = OnDeviceSttException(kind, "on-device model $packId was released")
    }

    companion object {
        const val IDLE_MS = 5 * 60_000L

        /** One shared instance in the app; `MikaApp.onTrimMemory` calls [release] when memory is tight. */
        val shared: OnDeviceEngines by lazy {
            OnDeviceEngines(OnDeviceBackends(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        }
    }
}
