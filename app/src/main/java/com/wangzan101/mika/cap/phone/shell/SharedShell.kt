package com.wangzan101.mika.cap.phone.shell

import android.os.Handler
import android.os.Looper

/**
 * One shell connection shared within the process, bound/unbound by reference count; a single instance is
 * created by [com.wangzan101.mika.cap.phone.PhoneServices]. Unbinding reverts shell-side changes such as the
 * virtual display, so tasks and startup recovery must not each bind a connection and unbind it separately.
 * [create] builds the connection the first time it is needed.
 */
class SharedShell(private val create: () -> ShellBinding) {
    private var connection: ShellBinding? = null
    private var users = 0
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun acquire(): ShellBinding {
        val c = connection ?: create().also { connection = it }
        if (users++ == 0) onMain { c.bind() }
        return c
    }

    @Synchronized
    fun release() {
        check(users > 0) { "release without acquire" }
        if (--users == 0) {
            val c = connection
            onMain { synchronized(this) { if (users == 0) c?.unbind() } }
        }
    }

    /** "Reconnect": when bound but there is no helper, ask the watchdog to start one again immediately; does nothing when nobody holds it. */
    @Synchronized
    fun reconnect() {
        connection?.takeIf { users > 0 }?.reconnect()
    }

    // Bind/unbind always run on the main thread to avoid interleaving with connection callbacks
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
