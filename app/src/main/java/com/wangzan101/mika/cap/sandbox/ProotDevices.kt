package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import java.io.File

/**
 * The /dev visible inside PRoot. We do not bind all of /dev: `/dev/socket/` (e.g. dnsproxyd, through which the
 * system can resolve domain names on the sandbox's behalf) and the binder devices (`/dev/binder`, `/dev/hwbinder`,
 * `/dev/vndbinder`, through which data can be carried out via system services) would both bypass the network
 * cutoff, so only the character devices below are bound; `/dev/fd`, `/dev/stdin`, etc. are created in the root
 * filesystem as symlinks pointing to /proc.
 */
object ProotDevices {
    /** Bind only if present on the host; the same path is bound at the same location inside the sandbox. */
    val DEVICE_BINDS = listOf(
        "/dev/null",
        "/dev/zero",
        "/dev/random",
        "/dev/urandom",
        "/dev/tty",
        "/dev/ptmx",
        "/dev/pts",
    )

    /** Symlinks in the root filesystem's /dev: name → target. */
    val DEV_SYMLINKS = linkedMapOf(
        "fd" to "/proc/self/fd",
        "stdin" to "/proc/self/fd/0",
        "stdout" to "/proc/self/fd/1",
        "stderr" to "/proc/self/fd/2",
    )

    /** PRoot's `-b` arguments (only devices that exist on the host). */
    fun bindArgs(exists: (String) -> Boolean = { File(it).exists() }): List<String> =
        DEVICE_BINDS.filter(exists).flatMap { listOf("-b", it) }

    /** Create the /dev directory and [DEV_SYMLINKS] in the root filesystem; anything already correct is left alone. Goes through the gate: nothing is created outside if dev has been swapped for a link. */
    fun prepareRootfsDev(rootfs: File, gate: WorkspaceFileGate) {
        for ((name, target) in DEV_SYMLINKS) gate.symlink(rootfs, "dev/$name", target)
    }
}
