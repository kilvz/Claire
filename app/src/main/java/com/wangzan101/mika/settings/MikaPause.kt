package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.core.run.PauseFlag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The persistent flag for "Pause Claire", one per process. This is the single source of truth: the task service, scheduling, channels, tasks handed over from the computer, workflows,
 * the built-in helper's watchdog and hand-off, auto-repair and pairing notifications all read only it ([paused]) and do not store their own copies; the built-in helper's Paused state is only a projection of it.
 * Change it only through [set]: write to disk synchronously first, and only update the in-memory value after the write succeeds, so no matter when the process is killed, a restart reads the last successful write.
 * [set] waits for disk, so do not call it on the main thread (the pause page switches to the IO thread via [com.wangzan101.mika.task.MikaPauseControl]).
 */
class MikaPause internal constructor(initial: Boolean, private val persist: (Boolean) -> Boolean) : PauseFlag {
    private val _state = MutableStateFlow(initial)
    /** For places that need to follow it (UI, auto-repair's "can it act right now"). */
    val state: StateFlow<Boolean> = _state.asStateFlow()

    override fun paused(): Boolean = _state.value

    /** Returns false when the disk write fails; the in-memory value stays unchanged. */
    @Synchronized
    fun set(paused: Boolean): Boolean {
        if (_state.value == paused) return true
        if (!persist(paused)) return false
        _state.value = paused
        return true
    }

    companion object {
        private const val PREFS = "mika_pause"
        private const val KEY_PAUSED = "paused"

        @Volatile private var instance: MikaPause? = null

        fun get(context: Context): MikaPause = instance ?: synchronized(this) {
            instance ?: run {
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                MikaPause(prefs.getBoolean(KEY_PAUSED, false)) { prefs.edit().putBoolean(KEY_PAUSED, it).commit() }
            }.also { instance = it }
        }
    }
}
