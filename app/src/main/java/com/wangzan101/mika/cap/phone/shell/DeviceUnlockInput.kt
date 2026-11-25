package com.wangzan101.mika.cap.phone.shell

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import com.wangzan101.mika.core.device.CredentialKind
import com.wangzan101.mika.core.device.DeviceCredential

/**
 * Called only by the authenticated Claire Binder. For the main screen only; the credential never goes into a
 * command line, the clipboard, a screenshot or a log.
 * Every credential keystroke is sent through [injectTo] onto the focused window owned by SystemUI alone: when
 * another window sits above the keyguard (an app shown over the lock screen, an incoming call, a full-screen
 * notification), or when the device is already unlocked and back in an app, the system refuses the injection, so
 * the keystrokes cannot land anywhere else. [inject] is used only for the wake key, which carries no secret.
 */
internal class DeviceUnlockInput(private val context: Context, private val inject: (InputEvent) -> Boolean,
    private val injectTo: (InputEvent, Int) -> Boolean, private val command: (List<String>) -> Boolean) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    /** The keyguard UI belongs to SystemUI; when its uid cannot be resolved, no credential is entered. */
    private val systemUiUid: Int? by lazy { runCatching { context.packageManager.getPackageUid(SYSTEM_UI, 0) }.getOrNull() }

    @Suppress("DEPRECATION")
    fun wake(): Boolean {
        // WakeLock + ACQUIRE_CAUSES_WAKEUP is the old but compatible way to wake the screen; injecting KEYCODE_WAKEUP / input keyevent 224 fails on some ROMs (#618).
        // This only does a best-effort wake; whether the screen is really on is judged by DeviceAccess reading system state.
        val woken = runCatching {
            power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "mika:wake").acquire(1_000)
        }.isSuccess
        return woken || key(KeyEvent.KEYCODE_WAKEUP, send = inject) || command(listOf("input", "keyevent", "224"))
    }

    fun dismiss(): Boolean = command(listOf("wm", "dismiss-keyguard"))

    private fun screenBounds(): Rect {
        val wm = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return wm.currentWindowMetrics.bounds
        val size = Point()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(size)
        return Rect(0, 0, size.x, size.y)
    }

    /** Performs a generic swipe up while a secure lock screen is still on the notification/fingerprint page. Events target SystemUI, so finishing the unlock midway does not leak into the foreground app. */
    fun reveal(): Boolean {
        val target = systemUiUid ?: return false
        if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return false
        val bounds = runCatching { screenBounds() }.getOrNull() ?: return false
        val x = bounds.exactCenterX()
        val startY = bounds.bottom - bounds.height() * 0.15f
        val endY = bounds.top + bounds.height() * 0.45f
        val downAt = SystemClock.uptimeMillis()
        fun send(action: Int, at: Long, y: Float): Boolean {
            val event = MotionEvent.obtain(downAt, at, action, x, y, 0)
            return try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                injectTo(event, target)
            } finally {
                event.recycle()
            }
        }
        if (!send(MotionEvent.ACTION_DOWN, downAt, startY)) return false
        var completed = false
        try {
            repeat(6) { step ->
                if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return false
                SystemClock.sleep(24)
                val progress = (step + 1) / 6f
                if (!send(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis(), startY + (endY - startY) * progress)) return false
            }
            if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return false
            completed = send(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), endY)
            return completed
        } finally {
            if (!completed) send(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis(), endY)
        }
    }

    /** A directed tap on SystemUI, used for fallback buttons such as "unlock with password"; carries no credential. */
    fun tap(x: Int, y: Int): Boolean {
        val target = systemUiUid ?: return false
        if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return false
        val downAt = SystemClock.uptimeMillis()
        fun send(action: Int, eventTime: Long): Boolean {
            val event = MotionEvent.obtain(downAt, eventTime, action, x.toFloat(), y.toFloat(), 0)
            return try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                injectTo(event, target)
            } finally {
                event.recycle()
            }
        }
        if (!send(MotionEvent.ACTION_DOWN, downAt)) return false
        return send(MotionEvent.ACTION_UP, downAt + TAP_MS)
    }

    fun enter(kind: Int, bytes: ByteArray, clear: Int = 0): Boolean {
        var chars = CharArray(0)
        var sent = false
        val target = systemUiUid
        val deadline = SystemClock.uptimeMillis() + 8_000
        fun toKeyguard(event: InputEvent): Boolean { sent = true; return injectTo(event, target!!) }
        try {
            if (target == null || !power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return false
            val type = CredentialKind.entries.getOrNull(kind) ?: return false
            if (bytes.any { it.toInt() !in 32..126 }) return false
            chars = CharArray(bytes.size) { bytes[it].toInt().toChar() }
            if (!DeviceCredential.valid(type, chars)) return false
            val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            val events = if (type == CredentialKind.Password) map.getEvents(chars) ?: return false else null
            // The caller has already confirmed SystemUI's credential input field. Delete characters only according
            // to the length of the existing input, never on an empty field: on some systems a DEL on an empty input
            // field closes the password page outright, and blindly sending 16 DELs would make every following
            // digit land nowhere.
            if (type == CredentialKind.Pin) {
                repeat(clear.coerceIn(0, MAX_CLEAR)) { if (!key(KeyEvent.KEYCODE_DEL, send = ::toKeyguard)) return false }
            } else if (!key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, ::toKeyguard) ||
                !key(KeyEvent.KEYCODE_DEL, send = ::toKeyguard)) return false
            if (type == CredentialKind.Pin) {
                for (c in chars) {
                    if (SystemClock.uptimeMillis() > deadline || !keyguard.isKeyguardLocked || !power.isInteractive) return false
                    if (!key(KeyEvent.KEYCODE_0 + (c - '0'), send = ::toKeyguard)) return false
                }
            } else {
                for (event in events!!) {
                    event.source = InputDevice.SOURCE_KEYBOARD
                    if (SystemClock.uptimeMillis() > deadline || !keyguard.isKeyguardLocked || !power.isInteractive || !toKeyguard(event)) return false
                }
            }
            // Some systems auto-submit on the last digit. Enter is likewise sent only to SystemUI: if the device has
            // already unlocked and returned to an app by then, the system refuses it, so it is not delivered to the app.
            SystemClock.sleep(200)
            return !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked || key(KeyEvent.KEYCODE_ENTER, send = ::toKeyguard)
        } finally {
            bytes.fill(0); chars.fill('\u0000')
            // Symbols in a password may involve modifier keys; release them only if keystrokes were sent, again only
            // to SystemUI, so no event is sent to the foreground app.
            if (sent && target != null) for (code in MODIFIERS) {
                val now = SystemClock.uptimeMillis()
                injectTo(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    InputDevice.SOURCE_KEYBOARD), target)
            }
        }
    }

    private fun key(code: Int, meta: Int = 0, send: (InputEvent) -> Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            InputDevice.SOURCE_KEYBOARD)
        return send(down) && send(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
        const val MAX_CLEAR = 16
        const val TAP_MS = 30L
        val MODIFIERS = intArrayOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT)
    }
}
