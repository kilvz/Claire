package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TAG
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.INJECT_MODE_WAIT_FOR_FINISH
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.injectMode
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.sendPair

// Assistant service: injecting keys and touches into a given display
internal inline fun ShellService.injectPair(down: InputEvent, up: InputEvent, displayId: Int, fallback: () -> Boolean): Boolean =
    sendPair({ inject(down, displayId) }, { inject(up, displayId) }, fallback)

/** Like [injectPair], but targeted at the focused window owned by [targetUid], bypassing the global input focus. */
internal inline fun ShellService.injectPairTo(down: InputEvent, up: InputEvent, displayId: Int, targetUid: Int, fallback: () -> Boolean): Boolean =
    sendPair({ injectTo(down, displayId, targetUid) }, { injectTo(up, displayId, targetUid) }, fallback)

@SuppressLint("PrivateApi")
internal fun ShellService.inject(event: InputEvent, displayId: Int, waitForFinish: Boolean = false): Boolean = try {
    event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
    val manager = inputManager()
    manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
        .invoke(manager, event, injectMode(event is KeyEvent, waitForFinish)) as Boolean
} catch (t: Throwable) {
    Log.w(TAG, "injectInputEvent failed", t)
    false
}

/**
 * Injects only into the focused window owned by [targetUid] and waits for handling to finish; the system refuses when
 * focus is not there, so the event never lands in another window. Uses the hidden overload
 * `injectInputEvent(event, mode, targetUid)` available since Android 13; without that overload nothing is injected.
 * On failure only the exception type is logged: the rejection reason carries no key information, but this path types
 * lock-screen credentials, so no extra information is left behind.
 */
@SuppressLint("PrivateApi")
internal fun ShellService.injectTo(event: InputEvent, displayId: Int, targetUid: Int): Boolean = try {
    event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
    val manager = inputManager()
    manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java, Int::class.java)
        .invoke(manager, event, INJECT_MODE_WAIT_FOR_FINISH, targetUid) as Boolean
} catch (t: Throwable) {
    Log.w(TAG, "targeted injectInputEvent failed: ${(t.cause ?: t).javaClass.simpleName}")
    false
}

/** Since API 34 the injection entry point is InputManagerGlobal; earlier it is InputManager. */
@SuppressLint("PrivateApi")
internal fun ShellService.inputManager(): Any = runCatching {
    Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
}.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!
