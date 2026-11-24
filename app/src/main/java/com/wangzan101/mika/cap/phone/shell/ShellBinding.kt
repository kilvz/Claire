package com.wangzan101.mika.cap.phone.shell

import kotlinx.coroutines.flow.StateFlow
import com.wangzan101.mika.shell.IMikaShell

/** One connection to the shell identity service; [SharedShell] reference-counts bind/unbind, and callers only look at these members. */
interface ShellBinding {
    /** While bound, the service interface when connected, otherwise null. */
    val shell: StateFlow<IMikaShell?>

    /** It was connected and is still wanted, but is now down. Reset after reconnecting or unbinding. */
    val dropped: StateFlow<Boolean>

    fun bind()

    /** After unbinding, no virtual display, mute or clipboard change is left behind. */
    fun unbind()

    /** "Reconnect": retry manually when the connection did not come back on its own after dropping. */
    fun reconnect()
}
