package com.wangzan101.mika.cap.phone.shell

/**
 * Whether the process may be killed when launch_app cold=true. Killing the process also wipes this app's
 * state on every display, so as long as it still has tasks outside Claire's display (even in the background),
 * it is not killed; a normal launch is used instead.
 */
enum class ColdStart {
    RESTART, WARM, IN_USE;

    companion object {
        fun decide(taskDisplays: List<Int>, agentDisplay: Int, visibleOnMain: Boolean): ColdStart = when {
            visibleOnMain -> IN_USE
            taskDisplays.any { it != agentDisplay } -> WARM
            else -> RESTART
        }
    }
}
