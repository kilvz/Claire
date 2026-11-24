package com.wangzan101.mika.cap.phone.shell

import com.wangzan101.mika.core.tool.LinkPolicy

/** The two checks the shell service makes before opening a link; depends on no Android classes, so unit tests call it directly. */
internal object ShellLinks {
    /** Returns [ShellService.START_INVALID_LINK] when the link may not be opened, null when it may. The dispatcher already checked; this checks once more. */
    fun refuse(action: String?, uri: String?, pkg: String?): Int? =
        if (LinkPolicy.check(action, uri, pkg) != null) ShellService.START_INVALID_LINK else null

    /** In use when any app that can open the link has a task on the main display; when nothing resolves at all it is unclear, so it counts as in use as well. */
    fun inUse(candidates: List<String>, onMainScreen: Set<String>): Boolean =
        candidates.isEmpty() || candidates.any { it in onMainScreen }
}
