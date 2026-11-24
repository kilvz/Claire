package com.wangzan101.mika.core.cap.sandbox

/**
 * The result of one sandbox command. [output] is the merged stdout and stderr (at most 1 MB),
 * with the excess dropped and counted in [droppedBytes]; [killedBySystem]: killed by the system (usually out of memory); [network]: whether it had network access while running.
 */
data class CommandResult(
    val exitCode: Int, val timedOut: Boolean, val killedBySystem: Boolean, val durationMs: Long,
    val output: String, val droppedBytes: Long, val network: Boolean,
)
