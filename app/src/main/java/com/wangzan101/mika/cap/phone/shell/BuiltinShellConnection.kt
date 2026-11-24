package com.wangzan101.mika.cap.phone.shell

import com.wangzan101.mika.core.device.shell.HelperLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import com.wangzan101.mika.shell.IMikaShell

/**
 * Connection to the built-in helper. The helper process is long-lived and does not end with unbinding: on
 * unbind it is told to revert this session's changes ([IMikaShell.releaseAll]), which has the same effect as
 * the service ending; on rebind the Binder already in hand is used directly. If there is no helper, the
 * watchdog starts one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuiltinShellConnection(hub: HelperHub, need: () -> Unit) : ShellBinding {
    // Single-threaded: the revert and the next publish run in order; the revert is a synchronous binder call, so it is kept off the main thread
    private val link = HelperLink(
        hub.shell,
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
        release = { it.releaseAll() },
        need = need,
    )

    override val shell: StateFlow<IMikaShell?> get() = link.shell
    override val dropped: StateFlow<Boolean> get() = link.dropped
    override fun bind() = link.bind()
    override fun unbind() = link.unbind()
    override fun reconnect() = link.reconnect()
}
