package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.i18n.AppLanguage
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import android.view.KeyEvent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import android.view.Display
import com.wangzan101.mika.capsule.CapsuleAppBounds
import com.wangzan101.mika.capsule.CapsuleFullscreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where an overlay window sits: [gravity] is a combination of [android.view.Gravity], [x]/[y] are pixels from that
 * edge.
 * Coordinates are relative to the whole screen (including the status bar, notch and bottom gesture bar strips); what
 * to avoid is decided by the caller via [OverlayArea].
 */
data class OverlayPlacement(val gravity: Int, val x: Int = 0, val y: Int = 0)

/**
 * Width and height of the screen right now (in the current orientation), and the pixels taken on each side by
 * the status bar, notch, navigation bar or gesture bar.
 */
data class OverlayArea(
    val width: Int, val height: Int,
    val insetLeft: Int = 0, val insetTop: Int = 0, val insetRight: Int = 0, val insetBottom: Int = 0,
)

/**
 * Dragging a window: it only counts as a drag once the finger has moved past the system's touch slop after going
 * down, at which point the button currently pressed inside the window receives a cancel and is no longer treated as
 * a tap.
 * Only one part of the window (the handle, see [OverlayWindow.draggable]) can be made draggable; swipes elsewhere
 * still go to the content inside (for example scrolling).
 * [move]/[end] give how many pixels the finger has moved on the screen since going down (in screen coordinates, so
 * the window does not jitter while following the finger).
 */
interface OverlayDrag {
    fun start()
    fun move(dx: Float, dy: Float)
    fun end(dx: Float, dy: Float)
}

/**
 * Long press: it only counts once the finger stays still (has not passed the touch slop) for holdMs after going
 * down; passing the slop first makes it a drag, and it never counts as a long press afterwards (see [HoldGesture]).
 * Once the long press starts the window does not follow the drag; the finger's position on the screen (screen
 * coordinates) is handed to this interface; the button currently pressed inside the window then receives a cancel and
 * a single click no longer holds.
 */
interface OverlayHold {
    fun start(rawX: Float, rawY: Float)
    fun move(rawX: Float, rawY: Float)
    fun end(rawX: Float, rawY: Float)
    /** The system took this touch stream away (e.g. the window was torn down, an incoming call): this long press does not count. */
    fun cancel()
}

/**
 * Whether one touch stream is a tap, a drag or a long press (pure logic, the caller manages the clock): after [down]
 * it starts as [Kind.Tap] (still possibly a tap); once the finger is more than [slopPx] from where it went down it is
 * [Kind.Drag]; while still Tap, reaching the time limit ([timeout]) makes it [Kind.Hold]. Once a drag or long press is
 * decided it never changes, and the next [down] starts over.
 */
class HoldGesture(private val slopPx: Float) {
    enum class Kind { Tap, Drag, Hold }

    var kind = Kind.Tap
        private set

    fun down(): Kind {
        kind = Kind.Tap
        return kind
    }

    /** How many pixels the finger currently is from where it went down ([dist]). */
    fun move(dist: Float): Kind {
        if (kind == Kind.Tap && dist > slopPx) kind = Kind.Drag
        return kind
    }

    fun timeout(): Kind {
        if (kind == Kind.Tap) kind = Kind.Hold
        return kind
    }
}

/**
 * Draws overlays on the user's screen (the main screen) through the accessibility service: the window type is an
 * accessibility overlay, so no "display over other apps" permission is needed.
 * Handles mechanism only: adding windows, moving them, dragging, toggling whether they can receive the keyboard,
 * tearing them down; what is drawn is up to the Compose content the caller provides.
 * A window is only as large as its content; places outside the content go to the app underneath as usual.
 * When the service disconnects, [ScreenAccessibility] calls [closeAll] and the windows are torn down with it. Main
 * thread only.
 */
class OverlayHost internal constructor(private val service: ScreenAccessibility) {
    private val windows = mutableListOf<OverlayWindow>()
    private val injectedGestures = InjectedGestures { isolate ->
        forEachGestureWindow(windows.toList()) { it.isolateInjectedGestures(isolate) }
    }

    /**
     * Adds an overlay window wrapping its content size. Normally it does not take focus, and untapped areas go to the app below as usual;
     * when [onOutside] is given, a press outside the window notifies it (used to collapse the card). With [touchable] false the window only draws and takes no touches (pressing it is like pressing the app below).
     * With [allowOffscreen] true the window may sit off-screen and the system no longer clamps it back; use it only for windows that must really show halfway off-screen (the edge-tucked capsule), and keep the protection for other overlays.
     * Returns null instead of throwing when it cannot be added (service disconnected, system refuses).
     */
    fun open(
        placement: OverlayPlacement,
        onOutside: (() -> Unit)? = null,
        touchable: Boolean = true,
        secure: Boolean = false,
        allowOffscreen: Boolean = false,
        content: @Composable () -> Unit,
    ): OverlayWindow? {
        val owners = OverlayOwners()
        val frame = OverlayFrame(service).apply {
            setViewTreeLifecycleOwner(owners)
            setViewTreeSavedStateRegistryOwner(owners)
            setViewTreeViewModelStoreOwner(owners)
            addView(
                ComposeView(AppLanguage.wrap(service)).apply { setContent(content) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            this.onOutside = onOutside
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // When the capsule tucks to the edge the whole window moves out leaving a half-circle, so tell the system not to clamp it back on-screen
                (if (allowOffscreen) WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS else 0) or
                (if (touchable && !injectedGestures.isolated) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
                (if (secure) WindowManager.LayoutParams.FLAG_SECURE else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Position against the whole screen, without letting the system squeeze the window inside the status bar
            // and navigation bar: what to avoid is decided by the caller via area().
            // fitInsetsTypes exists only from Android 11; older systems have no such option and lay the window out
            // against the whole screen anyway.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            // Reaching into the notch requires ALWAYS (from Android 11); older systems use SHORT_EDGES, which in
            // portrait also draws into the notch area.
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            place(placement)
        }
        val window = OverlayWindow(this, frame, params, owners, touchable)
        owners.start()
        return try {
            wm().addView(frame, params)
            windows += window
            window
        } catch (e: RuntimeException) {
            // The window token is already invalid right after the service disconnected, or the system does not allow
            // such windows: treat it as no overlay
            Log.w(TAG, "overlay unavailable", e)
            owners.destroy()
            null
        }
    }

    /** The current screen size and the parts of its edges taken by system bars and the cutout (changing after rotation). When it cannot be read, fall back to the display size with a typical status-bar height reserved at the top and bottom. */
    fun area(): OverlayArea = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) fallbackArea() else runCatching {
        val m = wm().currentWindowMetrics
        val i = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        OverlayArea(m.bounds.width(), m.bounds.height(), i.left, i.top, i.right, i.bottom)
    }.getOrElse { fallbackArea() }

    private fun fallbackArea(): OverlayArea {
        val d = service.resources.displayMetrics
        val bar = (FALLBACK_INSET_DP * d.density).toInt()
        return OverlayArea(d.widthPixels, d.heightPixels, insetTop = bar, insetBottom = bar)
    }

    /**
     * Top edge of the input method on the user's screen (screen coordinates); null when it is not showing. No matter
     * which app the input method is showing for (including Claire's own overlays), the capsule and cards move out of
     * its way accordingly. Re-read whenever the windows change; when the window cannot be read, treat it as not
     * showing.
     */
    fun imeTop(): Flow<Int?> = service.windowChanges().onStart { emit(Unit) }
        .map { readImeTop() }
        .distinctUntilChanged()

    /** Enabled only for the optional fullscreen preference. No screenshots, text or app content. */
    fun fullscreenChanges(): Flow<Boolean> = flow {
        while (currentCoroutineContext().isActive) {
            // Reading window size and status-bar visibility requires Android 11; earlier versions do not recognize full screen
            emit(if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) false else runCatching {
                val metrics = wm().currentWindowMetrics
                val windows = service.windowsOn(Display.DEFAULT_DISPLAY)
                val app = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer }
                val bounds = app?.let { Rect().also(it::getBoundsInScreen) }
                val safe = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
                CapsuleFullscreen.matches(metrics.bounds.width(), metrics.bounds.height(),
                    metrics.windowInsets.isVisible(WindowInsets.Type.statusBars()),
                    bounds?.let { CapsuleAppBounds(it.left, it.top, it.right, it.bottom) },
                    safe.bottom, safe.left, safe.top, safe.right)
            }.getOrDefault(false))
            delay(500)
        }
    }.distinctUntilChanged()

    private fun readImeTop(): Int? = runCatching {
        service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Rect().also(w::getBoundsInScreen) }
            ?.takeIf { !it.isEmpty }?.top
    }.getOrNull()

    /**
     * While our own injected gestures run, make every overlay not receive touches, so gestures landing where the
     * capsule or a card is also go to the app underneath.
     * Afterwards restore each window's original touchability; panels that were not touchable are not accidentally made
     * touchable. Nested gestures only restore when the outermost one ends.
     * When the caller cancels the coroutine, also wait for the system gesture to finish before restoring: cancelling
     * the wait does not withdraw touches already injected into the system.
     */
    suspend fun <T> passInjectedGesturesThrough(block: suspend () -> T): T = injectedGestures.passThrough(block)

    /** Tears down all overlays (when the service disconnects). */
    fun closeAll() {
        windows.toList().forEach(OverlayWindow::close)
    }

    internal fun wm(): WindowManager = service.getSystemService(WindowManager::class.java)

    internal fun forget(window: OverlayWindow) {
        windows.remove(window)
    }

    private companion object {
        const val TAG = "MikaOverlay"
        const val FALLBACK_INSET_DP = 24
    }
}

/** A window added by [OverlayHost.open]. After it has been torn down, calling anything does nothing. */
class OverlayWindow internal constructor(
    private val host: OverlayHost,
    private val frame: OverlayFrame,
    private val params: WindowManager.LayoutParams,
    private val owners: OverlayOwners,
    private val touchable: Boolean,
) {
    var isOpen = true
        private set

    /** The window's current width and height (pixels); 0 before it has been laid out. */
    val width: Int get() = frame.width
    val height: Int get() = frame.height

    /** Moves the window. */
    fun place(placement: OverlayPlacement) {
        if (!isOpen || params.gravity == placement.gravity && params.x == placement.x && params.y == placement.y) return
        params.place(placement)
        update()
    }

    /**
     * Give [drag] when it can be dragged, null when it cannot. [handle] means only presses inside that area drag
     * (pixel coordinates within the window, e.g. a card's title bar); when null, pressing anywhere drags; when empty,
     * nowhere drags.
     */
    fun draggable(drag: OverlayDrag?, handle: Rect? = null) {
        if (!isOpen) return
        frame.drag = drag
        frame.handle = handle?.let(::Rect)
    }

    /**
     * Give [hold] when a long press is supported, null when not: pressing without moving for [holdMs] milliseconds is
     * a long press (mutually exclusive with [draggable]'s drag; moving first makes it a drag).
     * A light haptic fires when the long press starts, and buttons inside the window receive a cancel.
     */
    fun holdable(hold: OverlayHold?, holdMs: Long = 400) {
        if (!isOpen) return
        frame.setHold(hold, holdMs)
    }

    /**
     * Fill the whole screen (e.g. the layer that dims what is behind while holding to talk), or go back to wrapping the
     * content size. A normally empty window wrapping its content has no size and does not occupy a full-screen canvas;
     * fill the screen only when needed (the system first measures a wrapping window at dialog width, so the content
     * cannot reach the full screen even when it fills that).
     */
    fun setFullScreen(full: Boolean) {
        if (!isOpen) return
        val size = if (full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT
        if (params.width == size && params.height == size) return
        params.width = size
        params.height = size
        update()
    }

    /**
     * The user pressed back while it can receive the keyboard (see [setFocusable]); when the input method is
     * showing, back dismisses the input method first and never reaches here.
     */
    fun onBack(onBack: (() -> Unit)?) {
        if (isOpen) frame.onBack = onBack
    }

    /**
     * [onTouch] is called when the window itself is pressed (no matter where): the caller uses it to restore
     * opacity and to read the position and source of this real touch.
     */
    fun onTouch(onTouch: ((MotionEvent) -> Unit)?) {
        if (isOpen) frame.onTouch = onTouch
    }

    /** Temporarily not touchable during injected gestures; restored afterwards per the [touchable] this window was created with. */
    internal fun isolateInjectedGestures(isolate: Boolean) {
        if (!isOpen) return
        val flags = if (touchable && !isolate) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (flags == params.flags) return
        params.flags = flags
        update()
    }

    /**
     * [onResize] is called when the content size changes (the capsule turns into a card, a re-layout after
     * rotation); the caller uses it to reposition.
     */
    fun onResize(onResize: (() -> Unit)?) {
        if (isOpen) frame.onResize = onResize
    }

    /**
     * Shows or hides the whole window. Merely clearing the Compose content waits for the next layout and frame, and
     * the old frame may still be on the system's window canvas; when closing or alternating two windows, visibility
     * must be switched explicitly, and empty content must not be taken as already invisible.
     */
    fun setVisible(visible: Boolean) {
        if (!isOpen) return
        frame.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    /**
     * Whether it can receive the keyboard: turn on when typing inside the overlay (only then does the input method pop
     * up facing it), and turn it off at once when collapsing, giving focus back to the app underneath.
     */
    fun setFocusable(focusable: Boolean) {
        if (!isOpen) return
        val flags = if (focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (flags == params.flags) return
        params.flags = flags
        params.softInputMode = if (focusable) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        update()
    }

    /**
     * Hides what is in the window first, runs [block] once it has really left the screen (e.g. taking a screenshot of
     * the user's screen), then shows it again.
     * "Really left": after the hidden frame has been drawn and handed to the system (frame commit callback), wait
     * [GONE_FRAMES] more refreshes for the compositor to pick up that frame; if the frame commit callback never comes
     * (the window is not drawing then), wait at most [GONE_WAIT_MS]. The window itself stays (position and state
     * unchanged), it is just transparent during this time.
     */
    suspend fun <T> hiddenWhile(block: suspend () -> T): T {
        val content = frame.getChildAt(0)
        if (!isOpen || content == null) return block()
        content.visibility = View.INVISIBLE
        try {
            withTimeoutOrNull(GONE_WAIT_MS) {
                suspendCancellableCoroutine { cont ->
                    frame.viewTreeObserver.registerFrameCommitCallback { if (cont.isActive) cont.resume(Unit) }
                    frame.invalidate()
                }
            }
            repeat(GONE_FRAMES) {
                suspendCancellableCoroutine { cont -> Choreographer.getInstance().postFrameCallback { if (cont.isActive) cont.resume(Unit) } }
            }
            return block()
        } finally {
            if (isOpen) content.visibility = View.VISIBLE
        }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        frame.release()
        host.forget(this)
        runCatching { host.wm().removeViewImmediate(frame) }
        owners.destroy()
    }

    private fun update() {
        runCatching { host.wm().updateViewLayout(frame, params) }.onFailure { close() }
    }

    private companion object {
        /** After the hidden frame is handed to the system, wait a few more refreshes: the compositor only picks it up on the next refresh. */
        const val GONE_FRAMES = 2
        const val GONE_WAIT_MS = 300L
    }
}

/**
 * Root of an overlay window, containing the Compose content. Receives presses outside the window ([onOutside]); when
 * draggable ([drag]), if the finger goes down inside the handle ([handle], or the whole window when absent) and moves
 * past the touch slop, this touch stream is intercepted and buttons inside receive a cancel. Dragging is computed in
 * screen coordinates.
 * When a long press is supported ([hold]), the timer starts on down and if the finger stays still until the limit,
 * this touch stream is intercepted and handed to [hold] (neither a tap nor a drag holds any more).
 */
internal class OverlayFrame(context: Context) : FrameLayout(context) {
    var onOutside: (() -> Unit)? = null
    var onResize: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onTouch: ((MotionEvent) -> Unit)? = null
    var drag: OverlayDrag? = null
    var handle: Rect? = null
    private var hold: OverlayHold? = null
    private var holdMs = 400L
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val gesture = HoldGesture(slop.toFloat())
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var tracking = false
    private var dragging = false
    private var holding = false
    /** The cancel we send to the content inside when a long press starts: when it passes through [track] it is not treated as a system cancel. */
    private var ownCancel = false
    private val holdTimer = Runnable(::holdReached)
    /**
     * Long-press timing runs on a main-thread Handler, not View.postDelayed: the latter waits to run until the
     * window is attached when it is not yet.
     */
    private val timer = Handler(Looper.getMainLooper())

    init {
        addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) onResize?.invoke()
        }
    }

    /**
     * Replaces the long-press receiver ([hold] null means no long press any more). Installing the same receiver again
     * (which happens on every redraw) changes nothing; when it is replaced or removed, an ongoing long press first
     * notifies the old one with a cancel, and the subsequent lift of this touch stream is handed to no one — otherwise
     * the old one would never see its end.
     */
    fun setHold(hold: OverlayHold?, holdMs: Long) {
        this.holdMs = holdMs
        if (hold === this.hold) return
        val old = this.hold
        this.hold = hold
        timer.removeCallbacks(holdTimer)
        if (holding) {
            holding = false
            tracking = false
            old?.cancel()
        }
    }

    fun release() {
        timer.removeCallbacks(holdTimer)
        hold = null
        holding = false
        drag = null
        handle = null
        onResize = null
        onOutside = null
        onBack = null
        onTouch = null
        tracking = false
        dragging = false
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            val outside = onOutside ?: return false
            outside()
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) onTouch?.invoke(e)
        return super.dispatchTouchEvent(e)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val back = onBack
        if (back != null && e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) back()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = track(e) && (dragging || holding)

    /** Dragging also works when pressing where nothing in the content handles it. */
    @SuppressLint("ClickableViewAccessibility") // dragging only moves the window; with a screen reader use "move to the other side" on the capsule
    override fun onTouchEvent(e: MotionEvent): Boolean = track(e)

    /** Follows this touch stream; returning true means it still wants to keep receiving it (when draggable or long-pressable). */
    private fun track(e: MotionEvent): Boolean {
        if (ownCancel) return true
        val d = drag
        val h = hold
        if (d == null && h == null) {
            timer.removeCallbacks(holdTimer)
            tracking = false
            dragging = false
            holding = false
            return false
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                lastX = e.rawX
                lastY = e.rawY
                tracking = handle?.contains(e.x.toInt(), e.y.toInt()) ?: true
                dragging = false
                holding = false
                gesture.down()
                timer.removeCallbacks(holdTimer)
                if (tracking && h != null) timer.postDelayed(holdTimer, holdMs)
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                lastX = e.rawX
                lastY = e.rawY
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (holding) {
                    hold?.move(e.rawX, e.rawY)
                } else if (gesture.move(hypot(dx, dy)) == HoldGesture.Kind.Drag) {
                    timer.removeCallbacks(holdTimer)
                    if (d != null) {
                        if (!dragging) {
                            dragging = true
                            d.start()
                        }
                        d.move(dx, dy)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                timer.removeCallbacks(holdTimer)
                val up = e.actionMasked == MotionEvent.ACTION_UP
                if (holding) {
                    if (up) hold?.end(e.rawX, e.rawY) else hold?.cancel()
                } else if (dragging) d?.end(e.rawX - downX, e.rawY - downY)
                tracking = false
                dragging = false
                holding = false
            }
        }
        return tracking
    }

    /**
     * Pressed without moving until the time limit: it has become a long press. A button currently pressed
     * inside immediately receives a cancel (without waiting for the next touch event); neither a tap nor a
     * double tap holds any more.
     */
    private fun holdReached() {
        val h = hold ?: return
        if (!tracking || dragging || gesture.timeout() != HoldGesture.Kind.Hold) return
        holding = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        ownCancel = true
        try {
            super.dispatchTouchEvent(cancel)
        } finally {
            ownCancel = false
            cancel.recycle()
        }
        h.start(lastX, lastY)
    }
}

private fun WindowManager.LayoutParams.place(p: OverlayPlacement) {
    gravity = p.gravity
    x = p.x
    y = p.y
}

/**
 * Compose outside an Activity must be given its own lifecycle, saved-state and ViewModel host; it enters
 * RESUMED when the window is added and is destroyed when torn down.
 */
internal class OverlayOwners : SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    fun start() {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        // Ones that never started (failed before the window was added) must still enter CREATED before being destroyed
        if (registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
