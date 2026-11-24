package com.wangzan101.mika.cap.phone.a11y

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.screen.uistate.OpenedElsewhere
import com.wangzan101.mika.core.screen.uistate.ScreenCheck
import com.wangzan101.mika.core.screen.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.screen.uistate.ScreenEvent
import com.wangzan101.mika.core.screen.uistate.ScreenFacts
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.VisibleTextLine
import com.wangzan101.mika.core.screen.visual.FrameGrid
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Screen reading / screenshots / UI events: reads the Agent virtual screen into snapshots and text, captures frames,
 * and watches UI events.
 * Fields and methods shared with [AndroidScreen] are accessed through [screen] as a forward reference.
 */
internal class ScreenCapture(private val screen: AndroidScreen) {

    internal fun windows() = screen.a11y.windowsOn(screen.displayId)
    /**
     * Window probe for app access: the ownership of each app window on the screen, reading only the root node's
     * package name and no content. Entries whose root node cannot be read are null (ownership unknown); when there are
     * no app windows it is the top task's app, and when there is none either it is the empty screen (empty list). Same
     * rules as the window ownership in screen-read results.
     */
    suspend fun accessPackages(): List<String?> = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext emptyList()
        val packages = windows().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .map { it.root?.packageName?.toString() }
        packages.ifEmpty { listOfNotNull(foregroundTaskPackage()) }
    }

    internal fun read() = ScreenReader.read(windows(), screen.screenWidth, screen.screenHeight)
    internal fun treeHash() = ScreenReader.hash(windows())

    /** Creates the screen on demand when a system command or event tool is used explicitly; ordinary tasks wait until the first app is opened. */
    suspend fun ensureDisplay() { screen.display.get() }

    // ---------- Screen reading ----------

    suspend fun snapshot(): ScreenSnapshot = capture(register = true)

    // Not entered into the number registry: the latest ref the model holds still stays valid
    suspend fun peek(): ScreenSnapshot = capture(register = false)

    /** Device-local SMS verification needs the exact IDs, including IDs on labelled buttons. */
    internal suspend fun smsSnapshot(register: Boolean): ScreenSnapshot =
        capture(register, fullResourceIds = true)

    internal suspend fun capture(register: Boolean, fullResourceIds: Boolean = false): ScreenSnapshot = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext ScreenSnapshot(snapshotId(), null, emptyList(), screen.screenWidth, screen.screenHeight, hasWindow = false)
        // Something a previous action opened on the user's main screen after a while: move it back before
        // reading (only on the screen the model is about to read, not in the glance that judges the effect)
        val late = if (register) screen.spill.lateSpill() else null
        val r = read()
        val after = treeHash()
        val foreground = if (r.topPackage == null) foregroundTaskPackage() else null
        val snap = NodeSnapshot(snapshotId(), r.entries)
        if (register) screen.ids.put(snap)
        ScreenSnapshot(
            snapshotId = snap.id,
            packageName = r.topPackage,
            elements = r.entries.map { entry ->
                if (fullResourceIds) entry.element.copy(resourceId = entry.locator.resourceId) else entry.element
            },
            width = screen.screenWidth,
            height = screen.screenHeight,
            changedDuringRead = after != r.hash,
            hasWindow = r.anyAppWindow,
            unreadableApp = foreground,
            windows = r.windows,
            // Still waiting for the user to choose on the main screen: say so as well
            opened = late?.opened ?: screen.spill._userPick.value?.let { OpenedElsewhere(it.packageName, it.app, pulledBack = false, picker = true) },
            // A black frame only means the picture is unavailable; it cannot prove a window is secure.
            // Screen-read elements can still serve as a fallback path.
            captureError = if ((r.anyAppWindow || foreground != null) && frameServable() && screen.frameBlack() == true)
                ScreenDiagnosis.DISPLAY_CAPTURE_UNAVAILABLE else null,
        )
    }

    /**
     * When the window content cannot be read (a secure window, a splash screen, shown elsewhere), identify the
     * foreground app by the top task on the Agent screen; null when the screen is not created yet, there is no task, or
     * it cannot be asked.
     */
    internal fun foregroundTaskPackage(): String? {
        if (!screen.hasDisplay) return null
        val task = runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)
        if (task < 0) return null
        return runCatching { screen.shell.packageOfTask(task) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * The app on top of Claire's screen (package name, task, owning user), re-emitted every time it changes: emitted
     * once on subscription, then re-read whenever windows change (a new page opened, another app opened from inside
     * another app, another copy coming on top). Emits nothing while the screen is not created or there is no task. Asks
     * the built-in assistant on the caller's thread.
     */
    fun foregroundApps(): Flow<AndroidScreen.TargetApp> = flow {
        emit(Unit)
        emitAll(screen.a11y.windowChanges())
    }.conflate().mapNotNull {
        if (!screen.hasDisplay) return@mapNotNull null
        val task = runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)
        if (task < 0) return@mapNotNull null
        val pkg = runCatching { screen.shell.packageOfTask(task) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        AndroidScreen.TargetApp(pkg, task, screen.userOf(task))
    }.distinctUntilChanged { a, b -> a.packageName == b.packageName && a.userId == b.userId }

    suspend fun screenshotPng(): ByteArray? = withContext(Dispatchers.IO) {
        // A black picture has no usable picture information, and it cannot distinguish a secure window from a
        // compositing failure; never hand it to the model or OCR.
        if (!frameServable() || screen.frameBlack() == true) return@withContext null
        screen.display.current?.latestFramePng()
    }
    /**
     * Whether the latest frame of the Agent screen may be handed to the model right now: not until a new frame
     * arrives after launch, and the guard lifts only then.
     */
    internal fun frameServable(): Boolean = when (FrameFreshness.serve({ screen.display.current?.lastFrameAt() }, screen.frameGuardSince)) {
        FrameFreshness.Serve.Guarded -> false
        FrameFreshness.Serve.Fresh -> {
            screen.frameGuardSince = null
            true
        }
    }

    /** Waits for the first frame after this picture change; if none arrives, keep the guard on and never let an old frame through. */
    internal suspend fun awaitFrameAfter(startedAt: Long) {
        screen.frameGuardSince = startedAt
        if (FrameFreshness.awaitNewFrame(
                { SystemClock.uptimeMillis() },
                ::delay,
                { screen.display.current?.lastFrameAt() },
                startedAt,
            )
        ) screen.frameGuardSince = null
    }

    /**
     * Recognizes text on screen. Custom-drawn UIs emit almost no accessibility events, and when the events are quiet
     * the picture may still be transitioning: wait for the picture to settle before recognizing; if the whole page
     * changed during recognition, recognize the new page again (rules in [StillFrameRead]). Local motion (animated
     * avatars, cursor) does not affect text positions, so it is used as is and no longer throws the whole result away.
     */
    suspend fun readVisibleText(): List<VisibleTextLine>? = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay || !frameServable() || screen.frameBlack() == true) return@withContext null
        StillFrameRead.read(SystemClock::uptimeMillis, { delay(it) }, screen::frameSignature) {
            screen.display.current?.latestFrameBitmap()?.let { PhoneOcr.read(it) }
        }
    }

    suspend fun frameGrid(): FrameGrid? = withContext(Dispatchers.IO) {
        if (!frameServable()) return@withContext null
        val bmp = screen.display.current?.frames?.value ?: return@withContext null
        runCatching { FrameGrid.sample(bmp.width, bmp.height, bmp::getPixel) }.getOrNull()
    }

    suspend fun checkScreen(): ScreenCheck = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext ScreenDiagnosis.classify(
            ScreenFacts(currentApp = null, hasWindow = false, readableElements = 0, changing = false, blackFrames = emptyList(), texts = emptyList(), passwordField = false),
        )
        // First see whether the UI quiets down: constant change means it is still loading
        val quiet = screen.withEventClock { clock -> clock.awaitQuiet(AndroidScreen.QUIET_MS, AndroidScreen.CHECK_CAP_MS) }
        // When quiet, look twice; consistently all-black only reports the picture as unavailable, black pixels
        // are not taken as evidence of a secure window
        val black = mutableListOf<Boolean>()
        screen.frameBlack()?.let(black::add)
        if (quiet) {
            screen.withEventClock { clock -> clock.awaitQuiet(AndroidScreen.SECOND_LOOK_QUIET_MS, AndroidScreen.SECOND_LOOK_CAP_MS) }
            screen.frameBlack()?.let(black::add)
        }
        val r = read()
        ScreenDiagnosis.classify(
            ScreenFacts(
                currentApp = r.topPackage,
                hasWindow = r.hasAppWindow,
                anyWindow = r.anyAppWindow,
                readableElements = r.entries.count { it.element.meaningful },
                changing = !quiet,
                blackFrames = black,
                texts = r.texts,
                passwordField = r.passwordField,
                foregroundApp = if (r.topPackage == null) foregroundTaskPackage() else null,
            ),
        )
    }

    suspend fun waitStable(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext true
        screen.withEventClock { clock -> clock.awaitQuiet(AndroidScreen.QUIET_MS, timeoutMs) }
    }

    /** The next UI event on this virtual screen (hover-type ones excluded); waits the full time limit when the screen is not open yet. */
    suspend fun awaitChange(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) { delay(timeoutMs.coerceAtLeast(0)); return@withContext false }
        withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) { screen.a11y.eventsFor(screen.displayId).first { it.eventType and AndroidScreen.IGNORED_EVENTS == 0 } } != null
    }

    suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!screen.hasDisplay) return@withContext false
        if (condition == "stable") return@withContext waitStable(timeoutMs)
        if (condition !in setOf("appear", "disappear", "activity") || value.isNullOrBlank()) return@withContext false
        coroutineScope {
            val changed = Channel<Boolean>(Channel.CONFLATED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                screen.a11y.eventsFor(screen.displayId).collect { event ->
                    if (condition == "activity" && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                        event.className?.toString()?.contains(value, true) == true) changed.trySend(true)
                    else if (condition != "activity") changed.trySend(false)
                }
            }
            try {
                fun matched(): Boolean = when (condition) {
                    "activity" -> runCatching { screen.shell.topActivity(screen.displayId)?.contains(value, true) == true }.getOrDefault(false)
                    else -> {
                        val r = read()
                        val exists = r.windows.any { w ->
                            w.title?.contains(value, true) == true || w.nodes.any { n -> containsText(n, value) }
                        } || r.texts.any { it.contains(value, true) }
                        exists == (condition == "appear")
                    }
                }
                if (matched()) return@coroutineScope true
                withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) {
                    while (true) {
                        val activityEvent = changed.receive()
                        if (condition == "activity") {
                            if (activityEvent) return@withTimeoutOrNull true
                        } else if (matched()) return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } ?: false
            } finally { job.cancel(); changed.close() }
        }
    }

    internal fun containsText(node: com.wangzan101.mika.core.screen.uistate.UiTreeNode, text: String): Boolean =
        (!node.secret && (node.text?.contains(text, true) == true || node.description?.contains(text, true) == true)) ||
            node.children.any { containsText(it, text) }

    suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? = withContext(Dispatchers.IO) {
        if (kind == "notification") return@withContext withTimeoutOrNull(timeoutMs) {
            screen.a11y.notificationEvents().first { packageName == null || it.packageName == packageName }
        }
        if (!screen.hasDisplay) return@withContext null
        withTimeoutOrNull(timeoutMs) {
            val event = screen.a11y.eventsFor(screen.displayId).first { e ->
                (packageName == null || e.packageName?.toString() == packageName) && when (kind) {
                    "window" -> e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    "content" -> e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    else -> true
                }
            }
            ScreenEvent(AccessibilityEvent.eventTypeToString(event.eventType), event.packageName?.toString(), event.text.joinToString(" ").take(2_000))
        }
    }

    suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> = withContext(Dispatchers.IO) {
        if (kind != "notification" && !screen.hasDisplay) return@withContext emptyList()
        val collected = mutableListOf<ScreenEvent>()
        val stream = if (kind == "notification") screen.a11y.notificationEvents().filter { packageName == null || it.packageName == packageName }
        else screen.a11y.eventsFor(screen.displayId)
            .filter { event ->
                (packageName == null || event.packageName?.toString() == packageName) && when (kind) {
                    "window" -> event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    "content" -> event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    else -> true
                }
            }
            .map { event -> ScreenEvent(AccessibilityEvent.eventTypeToString(event.eventType), event.packageName?.toString(),
                event.text.joinToString(" ").ifBlank { event.className?.toString().orEmpty() }.take(2_000)) }
        withTimeoutOrNull(durationMs) { stream.take(maxEvents).collect { collected += it } }
        collected
    }
}
