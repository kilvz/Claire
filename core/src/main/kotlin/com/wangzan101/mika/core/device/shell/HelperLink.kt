package com.wangzan101.mika.core.device.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A "connection" to the resident helper: the helper process does not end when it is unbound, and the Binder is supplied by [source] (null when there is none).
 * Externally it behaves like a service-bound connection: [shell] only has a value while bound, and [dropped] means it was connected, is still needed, but is broken right now.
 * On unbind, [release] makes the helper undo the changes left by this binding (virtual display, mute, clipboard), equivalent to the service ending.
 *
 * [scope] must execute sequentially on a single thread: the undo from unbinding is ordered before the publishing of a later binding, so screens created by a new task are not deleted by mistake by the previous undo.
 */
class HelperLink<T : Any>(
    private val source: StateFlow<T?>,
    private val scope: CoroutineScope,
    private val release: (T) -> Unit,
    private val need: () -> Unit,
) {
    private val _shell = MutableStateFlow<T?>(null)
    val shell: StateFlow<T?> = _shell.asStateFlow()

    private val _dropped = MutableStateFlow(false)
    val dropped: StateFlow<Boolean> = _dropped.asStateFlow()

    private var wanted = false
    private var job: Job? = null

    @Synchronized
    fun bind() {
        if (wanted) return
        wanted = true
        var seen = false
        job = scope.launch {
            source.collect { t ->
                synchronized(this@HelperLink) {
                    if (!wanted) return@collect
                    _shell.value = t
                    if (t != null) {
                        seen = true
                        _dropped.value = false
                    } else {
                        _dropped.value = seen
                    }
                }
                if (t == null) need()
            }
        }
    }

    @Synchronized
    fun unbind() {
        if (!wanted) return
        wanted = false
        job?.cancel()
        job = null
        val last = _shell.value
        _shell.value = null
        _dropped.value = false
        if (last != null) scope.launch { runCatching { release(last) } }
    }

    /** "Reconnect": while bound but with no helper, ask the watchdog to start one again right away. */
    fun reconnect() {
        val ask = synchronized(this) { wanted && _shell.value == null }
        if (ask) need()
    }
}
