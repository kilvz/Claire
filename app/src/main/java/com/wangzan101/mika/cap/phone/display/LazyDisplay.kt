package com.wangzan101.mika.cap.phone.display

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A virtual display created only when needed: [get] builds it the first time a task opens an app, or first uses a system command or an event tool, and it stays the same one afterwards; a pure-conversation task never creates one.
 * [width] and [height] are the size of the display to be created; while it has not been created yet, display reads report these sizes.
 * The type parameter exists only so unit tests can substitute a fake object; in production it is [LazyAgentDisplay].
 */
class LazyDisplay<T : Any>(width: Int, height: Int, private val sizeOf: ((T) -> Pair<Int, Int>)? = null, private val create: suspend () -> T) {
    private val initialWidth = width
    private val initialHeight = height
    val width: Int get() = made?.let { sizeOf?.invoke(it)?.first } ?: initialWidth
    val height: Int get() = made?.let { sizeOf?.invoke(it)?.second } ?: initialHeight
    private val lock = Mutex()
    @Volatile private var made: T? = null

    /** The already-created display; null while it has not been created. Does not create one. */
    val current: T? get() = made

    /** Get the display, creating it on the first call; a failed creation throws, and the next call retries. */
    suspend fun get(): T = made ?: lock.withLock { made ?: create().also { made = it } }
}

typealias LazyAgentDisplay = LazyDisplay<AgentDisplay>
