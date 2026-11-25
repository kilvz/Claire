package com.wangzan101.mika.cap.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.calls.onCallerLabelEvent
import com.wangzan101.mika.core.screen.uistate.ScreenEvent
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Claire's accessibility service: actions and events are forwarded to the currently connected instance, so a
 * task holding an old instance can recover after a reconnect.
 */
open class ScreenAccessibility : AccessibilityService() {

    private class DisplayEvent(val displayId: Int, val event: AccessibilityEvent)


    override fun onServiceConnected() {
        super.onServiceConnected()
        // Many apps mark the text views that display results as "not important for accessibility"; without reading
        // them the result cannot be seen. It is already declared in the config file, and is added again at runtime
        // here so the system cannot keep using an old service config
        serviceInfo?.let { info ->
            if (info.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS == 0) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                serviceInfo = info
            }
        }
        _instance.value = this
        // Pairing code reading and onboarding check-offs both rely on it; log one line on connect and one on
        // disconnect, to diagnose "clearly on but treated as off"
        Log.i(TAG, "accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "accessibility service unbound")
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    private fun clearInstance() {
        _instance.compareAndSet(this, null)
        overlayHost?.closeAll()
        overlayHost = null
    }

    private var overlayHost: OverlayHost? = null

    /**
     * Host used to draw overlays on the user's screen (main thread only); when the service disconnects, the
     * windows it added are torn down with it.
     */
    fun overlays(): OverlayHost = overlayHost ?: OverlayHost(this).also { overlayHost = it }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (instance.value !== this) return
        // Count only user interactions on the main screen, keeping no text or other content; the re-lock obligation
        // uses this to judge whether the user is still using the phone
        if (event.eventType in MAIN_INTERACTIONS && displayOf(event) == Display.DEFAULT_DISPLAY) mainInteractions.incrementAndGet()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) windowChanges.tryEmit(Unit)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            windowGeneration.incrementAndGet()
        }
        val app = application as? com.wangzan101.mika.MikaApp
        // Do not copy local consent fields or privately filled code events into teaching/observers.
        if (app != null && (app.appAccess.sensitiveUi && event.packageName?.toString() == app.packageName ||
                app.phone.smsLogin.isPrivate(event.packageName?.toString()) ||
                app.phone.smsLogin.privateLoginActive && event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)) return
        val access = app?.appAccess?.current()
        if (access != null && access.denial(event.packageName?.toString()) != null) return
        app?.runningCalls?.onCallerLabelEvent(this, event)
        if (app != null && app.teachReady && app.teach.wantsEvents()) {
            val displayId = displayOf(event)
            if (displayId != Display.INVALID_DISPLAY) {
                com.wangzan101.mika.teach.TeachEvents.capture(event, displayId)?.let(app.teach::offer)
            }
        }
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && notifications.subscriptionCount.value > 0) {
            val notification = event.parcelableData as? Notification
            val title = notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val body = notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val text = listOfNotNull(title, body).joinToString(" ").ifBlank { event.text.joinToString(" ") }
            notifications.tryEmit(ScreenEvent("notification", event.packageName?.toString(), text.take(2_000)))
        }
        // With no subscribers, do not copy and do not look up the display id; events are very frequent
        if (events.subscriptionCount.value == 0) return
        val displayId = displayOf(event)
        if (displayId == Display.INVALID_DISPLAY) return
        // API 33 以下系统会在回调返回后回收事件对象,订阅方异步读取前先复制一份
        val copy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) AccessibilityEvent(event) else @Suppress("DEPRECATION") AccessibilityEvent.obtain(event)
        events.tryEmit(DisplayEvent(displayId, copy))
    }

    override fun onInterrupt() = Unit

    /** Windows on the given display; an empty list when that display has no windows (or does not exist). */
    fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> {
        val service = instance.value ?: return emptyList()
        // getWindowsOnAllDisplays exists only from Android 11; older systems only have getWindows for the main display.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return service.windowsOnAllDisplays[displayId].orEmpty()
        if (displayId != Display.DEFAULT_DISPLAY) return emptyList()
        @Suppress("DEPRECATION")
        val main = service.windows
        return main.orEmpty()
    }

    /**
     * Performs one single-finger gesture along [path] on the given display; returns true when completed, false
     * when cancelled or when it cannot be dispatched.
     */
    suspend fun gesture(displayId: Int, path: Path, durationMs: Long): Boolean {
        return gesture(displayId, listOf(path), durationMs)
    }

    suspend fun gesture(displayId: Int, paths: List<Path>, durationMs: Long): Boolean =
        dispatch(displayId, paths.map { GestureDescription.StrokeDescription(it, 0, durationMs.coerceAtLeast(1)) })

    /** Taps [count] times at the same point (several strokes in one gesture): each press lasts [holdMs], with [gapMs] between presses. */
    suspend fun taps(displayId: Int, x: Float, y: Float, count: Int, holdMs: Long, gapMs: Long): Boolean {
        val at = Path().apply { moveTo(x, y) }
        return dispatch(displayId, (0 until count).map { i -> GestureDescription.StrokeDescription(at, i * (holdMs + gapMs), holdMs.coerceAtLeast(1)) })
    }

    private suspend fun dispatch(displayId: Int, strokes: List<GestureDescription.StrokeDescription>): Boolean {
        val service = instance.value ?: return false
        // 手势指定屏要 Android 11;更早只能在主屏上做
        val builder = GestureDescription.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setDisplayId(displayId)
        else if (displayId != Display.DEFAULT_DISPLAY) return false
        val gesture = builder
            .apply { strokes.forEach(::addStroke) }
            .build()
        suspend fun send(): Boolean = suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (!service.dispatchGesture(gesture, callback, null) && cont.isActive) cont.resume(false)
        }
        // Injecting operates other apps on the user's screen, not Claire's own overlays; all taps, double taps and drags are isolated uniformly
        return service.overlayHost?.passInjectedGesturesThrough { send() } ?: send()
    }

    /** Result of taking one screenshot of the user's screen (the main screen): the picture, or a secure window on screen, or failure (error code). */
    sealed interface MainShot {
        class Ok(val bitmap: Bitmap) : MainShot
        data object Secure : MainShot
        data class Failed(val code: Int) : MainShot
    }

    /**
     * Takes one screenshot of the main screen (the screen the user is looking at, including Claire's own overlays:
     * whether to hide them is up to the caller). The system requires a minimum interval between screenshots, so when
     * taken too fast it waits one second and takes another. The picture is a software bitmap (pixels can be read,
     * encoded).
     */
    suspend fun takeMainScreenshot(): MainShot {
        val first = screenshotOnce()
        if (first !is MainShot.Failed || first.code != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return first
        kotlinx.coroutines.delay(SCREENSHOT_INTERVAL_MS)
        return screenshotOnce()
    }

    private suspend fun screenshotOnce(): MainShot = suspendCancellableCoroutine { cont ->
        val service = instance.value
        if (service == null) {
            cont.resume(MainShot.Failed(ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS))
            return@suspendCancellableCoroutine
        }
        // takeScreenshot exists only from Android 11; older systems treat it as unattainable, so the capsule's screenshot path does not crash.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cont.resume(MainShot.Failed(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
            return@suspendCancellableCoroutine
        }
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val shot = runCatching {
                    result.hardwareBuffer.use { buffer ->
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@runCatching null
                        hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() }
                    }
                }.getOrNull()
                if (cont.isActive) cont.resume(shot?.let(MainShot::Ok) ?: MainShot.Failed(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
            }

            override fun onFailure(errorCode: Int) {
                // A secure window is on screen: from Android 14 there is a dedicated error code; older systems
                // return a black picture and the caller judges by the image
                val secure = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                if (cont.isActive) cont.resume(if (secure) MainShot.Secure else MainShot.Failed(errorCode))
            }
        })
    }

    /**
     * Accessibility events that happen on the given display. The channel is kept across instances; only the currently
     * connected service publishes events.
     */
    fun eventsFor(displayId: Int): Flow<AccessibilityEvent> =
        events.filter { it.displayId == displayId }.map { it.event }

    fun notificationEvents(): Flow<ScreenEvent> = notifications

    /** Signal that windows changed (see [windowChanges]); the channel is kept across service instances. */
    fun windowChanges(): Flow<Unit> = windowChanges

    /**
     * The display the event happened on. From API 33 events carry their display id; older systems look the event's
     * window id up among the windows of each display, and drop it when not found (e.g. notification-type events with no
     * window id).
     */
    private fun displayOf(event: AccessibilityEvent): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return event.displayId
        val windowId = event.windowId
        if (windowId == -1) return Display.INVALID_DISPLAY
        // "Windows on each display" exists only from Android 11; older systems have only the main display's copy, so look only there.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            val main = windows
            return if (main.any { it.id == windowId }) Display.DEFAULT_DISPLAY else Display.INVALID_DISPLAY
        }
        val all = windowsOnAllDisplays
        for (i in 0 until all.size()) {
            if (all.valueAt(i).any { it.id == windowId }) return all.keyAt(i)
        }
        return Display.INVALID_DISPLAY
    }

    companion object {
        private const val EVENT_BUFFER = 64
        /** Minimum interval the system requires between two screenshots (one second on stock Android). */
        private const val SCREENSHOT_INTERVAL_MS = 1_000L

        // Keep the flow hot: callers subscribe with UNDISPATCHED before acting, so a service switch must not
        // introduce an asynchronous subscription window.
        // Events are observation only; when subscribers cannot keep up, drop the oldest and never block the system callback
        private val events = MutableSharedFlow<DisplayEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        /**
         * Windows changed (added, removed, moved, including the input method appearing and disappearing): just
         * a signal, subscribers read the windows themselves.
         */
        private val windowChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        private val windowGeneration = java.util.concurrent.atomic.AtomicLong()

        /**
         * Incremented every time a window is added, removed or changes page (on any display); only used to tell
         * "did windows change since last time", the value itself has no meaning.
         */
        fun windowGeneration(): Long = windowGeneration.get()

        /**
         * How many times the user interacted on the main screen (click, long click, scroll, text change, touch
         * start); no event content is kept, only the count.
         */
        private val mainInteractions = java.util.concurrent.atomic.AtomicLong()
        private val MAIN_INTERACTIONS = setOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
        )

        /**
         * Current main-screen interaction count; only used to tell "did the user interact with the main screen
         * since last time", the value itself has no meaning.
         */
        fun mainInteractionCount(): Long = mainInteractions.get()
        private val notifications = MutableSharedFlow<ScreenEvent>(
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        private const val TAG = "MikaA11y"
        private val _instance = MutableStateFlow<ScreenAccessibility?>(null)
        /** The connected service instance; null while the service is disabled or has disconnected. */
        val instance: StateFlow<ScreenAccessibility?> = _instance.asStateFlow()
    }
}
