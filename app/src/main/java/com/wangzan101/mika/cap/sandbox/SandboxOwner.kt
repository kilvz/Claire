package com.wangzan101.mika.cap.sandbox

import android.content.Context
import com.wangzan101.mika.core.workspace.SandboxTree

/** The single in-process copy of the sandbox's state and tree lock: supplied by the App, so the settings screen, the installer and the Agent all see the same one. */
interface SandboxOwner {
    val sandboxState: SandboxState
    val sandboxTree: SandboxTree

    companion object {
        fun of(context: Context): SandboxOwner = context.applicationContext as? SandboxOwner
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement SandboxOwner to give the sandbox's shared state")
    }
}
