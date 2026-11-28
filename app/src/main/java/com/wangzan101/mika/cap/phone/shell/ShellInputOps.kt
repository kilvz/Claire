package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.content.ComponentName
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.INJECT_MODE_WAIT_FOR_FINISH
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.STROKE_MAX_MS
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.STROKE_MAX_POINTS
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TAG
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TAP_MS
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TASK_INFO
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.VIRTUAL_KEYBOARD
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.injectMode
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.sendPair
import com.wangzan101.mika.core.screen.uistate.PressKeys

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

internal fun ShellService.sendKey(displayId: Int, keyCode: Int): Boolean {
    // Same allowlist as press_key: even if the caller is wrong, never send whole-device keys like volume, power or Home from here
    if (!PressKeys.isAllowed(keyCode)) {
        Log.w(TAG, "refused key $keyCode")
        return false
    }
    val now = SystemClock.uptimeMillis()
    val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
    val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
    // The system routes keys by input focus and ignores the displayId on the event: without targeting they go to the main-screen focus and an app on the virtual display never gets Back.
    // When the uid of the top task on the virtual display resolves, inject directly to it; if targeted injection fails, fall back to untargeted injection (as the old path did), and only then to a shell command.
    // Log the channel actually used at each step to make Back injection failures easier to locate on a device (#622).
    val uid = topTaskUid(displayId)
    var channel = if (uid != null) "targeted" else "nontargeted"
    val ok = if (uid != null) {
        injectPairTo(down, up, displayId, uid) {
            channel = "nontargeted"
            injectPair(down, up, displayId) {
                channel = "shell"
                exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
            }
        }
    } else {
        injectPair(down, up, displayId) {
            channel = "shell"
            exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
        }
    }
    if (keyCode == KeyEvent.KEYCODE_BACK) {
        Log.i(TAG, "injectKey BACK display=$displayId uid=$uid channel=$channel ok=$ok")
    }
    return ok
}

internal fun ShellService.sendCtrlKey(displayId: Int, keyCode: Int): Int {
    if (!ownDisplay(displayId)) return AsciiTextKeys.NOT_SENT
    if (keyCode != KeyEvent.KEYCODE_A && keyCode != KeyEvent.KEYCODE_V) {
        Log.w(TAG, "refused ctrl key $keyCode")
        return AsciiTextKeys.NOT_SENT
    }
    val now = SystemClock.uptimeMillis()
    val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    fun event(code: Int, action: Int, modifiers: Int) = KeyEvent(
        now, SystemClock.uptimeMillis(), action, code, 0, modifiers,
        VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
    )
    var modifierDown = false
    try {
        if (!inject(event(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_DOWN, 0), displayId)) return AsciiTextKeys.NOT_SENT
        modifierDown = true
        if (!inject(event(keyCode, KeyEvent.ACTION_DOWN, meta), displayId)) return AsciiTextKeys.NOT_SENT
        val up = event(keyCode, KeyEvent.ACTION_UP, meta)
        return if (inject(up, displayId) || inject(up, displayId)) AsciiTextKeys.DISPATCHED else AsciiTextKeys.UNCONFIRMED
    } finally {
        if (modifierDown) {
            val up = event(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_UP, 0)
            if (ownDisplay(displayId) && !inject(up, displayId) && !inject(up, displayId)) {
                Log.w(TAG, "could not release Ctrl on display $displayId")
            }
        }
    }
}

@SuppressLint("DiscouragedPrivateApi")
internal fun ShellService.sendAsciiText(displayId: Int, taskId: Int, packageName: String, text: String): Int {
    if (displayId <= Display.DEFAULT_DISPLAY || !ownDisplay(displayId) || taskId < 0 || !AsciiTextKeys.supports(text)) {
        return AsciiTextKeys.NOT_SENT
    }
    val (events, targetUid) = runCatching {
        val task = tasksOn(displayId).firstOrNull() ?: return AsciiTextKeys.NOT_SENT
        val top = TASK_INFO.getField("topActivity").get(task) as? ComponentName
        if (TASK_INFO.getField("taskId").getInt(task) != taskId || top?.packageName != packageName) {
            return AsciiTextKeys.NOT_SENT
        }
        val uid = if (Build.VERSION.SDK_INT >= 33) {
            val user = TASK_INFO.getField("userId").getInt(task)
            val pm = context.packageManager
            (pm.javaClass.getMethod("getPackageUidAsUser", String::class.java, Int::class.java, Int::class.java)
                .invoke(pm, packageName, 0, user) as Int).takeIf { it >= 0 } ?: return AsciiTextKeys.NOT_SENT
        } else null
        val keys = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
            ?.takeIf { it.isNotEmpty() } ?: return AsciiTextKeys.NOT_SENT
        keys to uid
    }.getOrElse { return AsciiTextKeys.NOT_SENT }

    for (event in events) {
        if (!ownDisplay(displayId) || (targetUid == null && topTaskId(displayId) != taskId)) {
            return AsciiTextKeys.UNCONFIRMED
        }
        event.source = InputDevice.SOURCE_KEYBOARD
        val sent = if (targetUid != null) injectTo(event, displayId, targetUid)
            else inject(event, displayId, waitForFinish = true)
        // A failed dispatch can leave a prefix. Never replay it through another input route.
        if (!sent) return AsciiTextKeys.UNCONFIRMED
    }
    return AsciiTextKeys.DISPATCHED
}

internal fun ShellService.sendTap(displayId: Int, x: Int, y: Int): Boolean {
    val now = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
    val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
    return try {
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        injectPair(down, up, displayId) { exec("input", "-d", "$displayId", "tap", "$x", "$y") == 0 }
    } finally {
        down.recycle()
        up.recycle()
    }
}

internal fun ShellService.sendStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
    if (!ownDisplay(displayId)) return false
    val n = times.size
    if (n < 2 || n > STROKE_MAX_POINTS || xs.size != n || ys.size != n || times[0] != 0L ||
        times.last() > STROKE_MAX_MS || (1 until n).any { times[it] < times[it - 1] }
    ) return false
    val down = SystemClock.uptimeMillis()
    fun event(i: Int, action: Int): MotionEvent {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply { x = xs[i].toFloat(); y = ys[i].toFloat(); pressure = 1f; size = 1f }
        return MotionEvent.obtain(down, down + times[i], action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }
    fun send(i: Int, action: Int): Boolean {
        val wait = down + times[i] - SystemClock.uptimeMillis()
        if (wait > 0) SystemClock.sleep(wait)
        val e = event(i, action)
        return try { inject(e, displayId) } finally { e.recycle() }
    }
    if (!send(0, MotionEvent.ACTION_DOWN)) return false
    for (i in 1 until n) send(i, MotionEvent.ACTION_MOVE)
    return send(n - 1, MotionEvent.ACTION_UP) || send(n - 1, MotionEvent.ACTION_UP)
}
