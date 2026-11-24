package com.wangzan101.mika.core.screen.uistate

import com.wangzan101.mika.core.screen.visual.FrameGrid
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val DOUBLE_TAP_UNSUPPORTED = "double_tap_unsupported"

/** Gap between the two taps: short enough to be recognized as a double tap (Android's double-tap window is 300 ms). */
const val DOUBLE_TAP_GAP_MS = 120L

/** Default duration of a swipe gesture. */
const val DEFAULT_SWIPE_MS = 400L

/** Inverse of the tap conversion: n = round(p*999/(size-1)), clamped to 0–999. */
fun normalize(p: Int, size: Int): Int {
    if (size <= 1) return 0
    return min(999, max(0, (p * 999.0 / (size - 1)).roundToInt()))
}

/**
 * An element on the screen. ref looks like "@e3"; center is in pixels (used inside the port, converted to 0–999 before
 * being handed to the model); bounds is [l,t,r,b], already normalized to 0–999.
 * [hint]: what is shown in the input field is a hint/prefilled word (name is that), not content already typed.
 * [focused]: has input focus (Enter and Space act on it).
 * [secret]: a password field.
 * [placeholder]: the input field's own hint text (still there after typing, unrelated to the currently displayed [name]); null if none.
 * [searchField]: the control is a search box by its own semantics (web type=search, role=searchbox, a key labeled search; Android search box controls).
 * [states]: the states the control currently has ([UiStates] names: checked, selected, expanded, pressed, disabled); flipping a switch shows up immediately when judging action effects.
 */
data class UiElement(
    val ref: String,
    val role: String,
    val name: String,
    val centerX: Int,
    val centerY: Int,
    val bounds: List<Int>,
    val resourceId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val hint: Boolean = false,
    val focused: Boolean = false,
    /** Keyboard shortcut the page declares for this control on the web (aria-keyshortcuts, accesskey rendered as Alt+key), written the same way as web_interact's key. */
    val keyShortcuts: List<String> = emptyList(),
    val secret: Boolean = false,
    val placeholder: String? = null,
    val searchField: Boolean = false,
    val states: Set<String> = emptySet(),
) {
    /** A node with no name and no interaction carries no information for the model. */
    val meaningful: Boolean get() = name.isNotBlank() || clickable || editable || scrollable
}

/** Names of control states, shared by phone and web. */
object UiStates {
    const val CHECKED = "checked"
    const val SELECTED = "selected"
    const val EXPANDED = "expanded"
    const val PRESSED = "pressed"
    const val DISABLED = "disabled"
    val ALL = setOf(CHECKED, SELECTED, EXPANDED, PRESSED, DISABLED)
}

/**
 * [changedDuringRead]: the interface was not the same before and after reading this list (the page was moving on its own), so the list may not match the screen.
 * [protected]: there is clear evidence that the current page is protected by the system against screen capture, so the picture cannot be seen.
 * [captureError]: the picture cannot be used as the current content, but there is no evidence to assert capture protection; the value is a generic error code.
 * The accessibility elements that were read may still be used; black or stale frames must not be handed to the vision model.
 * [hasWindow]: there is an App window on the screen (whether or not its root node can be read); only false with no elements means a truly blank screen.
 * [web]: page information when reading the web; empty when reading the phone.
 * [unreadableApp]: when window content cannot be read (secure window, splash screen, displayed elsewhere) but the port knows from the
 * focused window or the topmost task which App is in the foreground, its package name; null when readable or unknown. When present, "no elements" does not mean "no App opened".
 */
data class ScreenSnapshot(
    val snapshotId: String,
    val packageName: String?,
    val elements: List<UiElement>,
    val width: Int,
    val height: Int,
    val changedDuringRead: Boolean = false,
    val protected: Boolean = false,
    val hasWindow: Boolean = true,
    val web: WebInfo? = null,
    val unreadableApp: String? = null,
    /** Full visible hierarchy of the phone's accessibility windows; the web and test ports may omit it. */
    val windows: List<UiWindowTree> = emptyList(),
    /** An App that opened on the user's home screen only after the previous action and was just pulled back before this screen was read (see [OpenedElsewhere]); null if none. */
    val opened: OpenedElsewhere? = null,
    /** The picture is unavailable but this is not a confirmed capture protection; see the [captureError] note above. */
    val captureError: String? = null,
)

/**
 * An action opened an App outside Claire's screen, on the user's home screen (the App was not opened from its own page on Claire's
 * screen, so the system placed it on the home screen).
 * [packageName] is that App (when the system asks which one to open, the package of the window being asked about), [app] its display name.
 * [pulledBack]: it has been moved to Claire's screen and is on top; when false it is left untouched on the user's home screen (the App the user is using, or the system is asking the user).
 * [inProfile]: which copy was opened when the App is installed twice (clone, work profile), [MAIN_PROFILE] the original, [OTHER_PROFILE] the other one; null when only one is installed or it cannot be told.
 * [picker]: what was opened is the system window asking "open with which one" (the same App installed twice, several Apps can open it, etc.). It is never moved: the choice is left to the user on their own screen,
 * and the task waits until they choose; [app] is the name of the App being asked about (when recognizable).
 * [closed]: the user closed that question and nothing was opened. [byUser]: the chosen App needs the user's own handling; the user handled it on their screen and tapped continue.
 * [needsUser]: what surfaced on the home screen is a popup that needs the user's own handling (login verification, account confirmation, payment confirmation and the like), left on the home screen for them to tap.
 */
data class OpenedElsewhere(
    val packageName: String, val app: String?, val pulledBack: Boolean, val inProfile: String? = null, val picker: Boolean = false,
    val closed: Boolean = false, val byUser: Boolean = false, val needsUser: Boolean = false,
) {
    /** Still on the user's home screen right now (the user is using it, or the system is asking the user). */
    val onUserScreen: Boolean get() = !pulledBack && !closed && !byUser

    fun toJson(): JsonObject = buildJsonObject {
        put("package", packageName)
        app?.let { put("app", it) }
        put("pulledBack", pulledBack)
        if (onUserScreen) put("onUserScreen", true)
        if (needsUser) put("needsUser", true)
        inProfile?.let { put("inProfile", it) }
        if (picker) put("picker", true)
        if (closed) put("closed", true)
        if (byUser) put("byUser", true)
    }

    companion object {
        const val MAIN_PROFILE = "main"
        const val OTHER_PROFILE = "other"
    }
}

/** Interface events received from the current task's virtual display; notification events may come from the whole device. */
data class ScreenEvent(val type: String, val packageName: String?, val text: String?)

/** Text lines read from the current screen image by local OCR; bounds use the same 0–999 coordinates as accessibility elements; [confidence] is the 0–1 confidence reported by recognition, null if none. */
data class VisibleTextLine(val text: String, val bounds: List<Int>, val confidence: Float? = null)

/** Result of the built-in assistant executing a command as the Android shell. */
data class SystemCommandResult(val exitCode: Int, val output: String)

/** Result of reading the input field back after typing; UNKNOWN when it cannot be read back (password field, unreadable node). */
enum class Verification(val wire: String) { MATCH("match"), MISMATCH("mismatch"), UNKNOWN("unknown") }

sealed interface ActionResult {
    /**
     * The action has been issued. The remaining fields are facts the port verified afterwards; null/false means not verified or not applicable:
     * [changed] whether the interface changed before and after the action; [appSwitched] the current package name when the foreground is no longer the target App;
     * [relocated] the ref had expired and the action was performed after relocating the element by its original characteristics in the new interface, [relocatedTo] is the current name of the relocated element;
     * [switchedByTarget] the new foreground was opened by the target App within its own task (e.g. sharing to another App), not an external interruption; [verification] the input read-back result;
     * [hint] the port's extra note on this result (e.g. why it could not be verified), passed to the model verbatim;
     * [secret] the input target was a password field (the run record therefore does not keep the original text);
     * [settled] with DevTools on the web: true when the action waited for network idle and a quiet page, false when the limit was reached without settling, otherwise null;
     * [coveredBy] the opened App is already on top but another package's window covers it (permission dialog, account picker): that package;
     * [chooser] after opening the App the system first asks "open with which one" (the same App installed twice, etc.); until a choice is made the App is not yet in the foreground;
     * [opened] this action made an App open on the user's home screen (see [OpenedElsewhere]);
     * [inProfile] which copy was opened when the App that just came to the foreground is installed twice (see [OpenedElsewhere.inProfile]): reported after the system asked which one to open,
     * and also every time the target App listed another copy or the other copy was requested; [profileUser] is the user id owning the copy currently on screen,
     * [requestedUser] is the user of the copy requested when opening (null when the original copy was requested).
     */
    data class Ok(
        val changed: Boolean? = null,
        val appSwitched: String? = null,
        val relocated: Boolean = false,
        val relocatedTo: String? = null,
        val switchedByTarget: Boolean = false,
        val verification: Verification? = null,
        val hint: String? = null,
        val secret: Boolean = false,
        val settled: Boolean? = null,
        val coveredBy: String? = null,
        val chooser: LaunchChoice? = null,
        val opened: OpenedElsewhere? = null,
        val inProfile: String? = null,
        val profileUser: Int? = null,
        val requestedUser: Int? = null,
    ) : ActionResult

    /** reason is "ref_expired" when the ref has expired. [hint] is the port's extra note (e.g. what covers the target), appended verbatim after the error description. */
    data class Failed(val reason: String, val hint: String? = null) : ActionResult
}

/** This App is currently in use by another task when it is being opened (the same App can only be on one screen at a time). */
const val APP_IN_USE_BY_OTHER_TASK = "app_in_use_by_other_task"

/** Result of [ScreenPort.awaitAppFree]. */
enum class AppWait {
    /** Released; it can be opened now. */
    Free,
    /** Still held when the timeout was reached. */
    TimedOut,
    /** The task holding it is waiting for the App this task holds: waiting on would deadlock both, so it did not wait. */
    WouldDeadlock,
    /** This port does not compete for Apps with other tasks, so there is nothing to wait for. */
    Unsupported,
}

/** The sole entry point for the Agent to observe and operate the phone screen; implemented by the Android module. */
interface ScreenPort {
    val appAccess: com.wangzan101.mika.core.device.access.AppAccessPolicy get() = com.wangzan101.mika.core.device.access.AppAccessPolicy.ALLOW

    /** Only check window ownership, without reading screen contents. Allowed for a blank screen; rejected when there is a window but its owner is unknown. */
    suspend fun checkAppAccess() {}

    suspend fun snapshot(): ScreenSnapshot

    /** Read one screen without registering refs (the refs the model holds are unaffected); used for irreversibility checks. */
    suspend fun peek(): ScreenSnapshot = snapshot()
    suspend fun screenshotPng(): ByteArray?
    /** Optional local text recognition; null means the current picture is unreadable or the port has no OCR. */
    suspend fun readVisibleText(): List<VisibleTextLine>? = null
    suspend fun tapRef(ref: String, snapshotId: String): ActionResult
    suspend fun tapXY(x: Int, y: Int): ActionResult
    /** Double tap (the gap between the two taps is within the system's double-tap window); ports that do not support it report double_tap_unsupported. */
    suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult = ActionResult.Failed(DOUBLE_TAP_UNSUPPORTED)
    suspend fun doubleTapXY(x: Int, y: Int): ActionResult = ActionResult.Failed(DOUBLE_TAP_UNSUPPORTED)
    suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult
    suspend fun clearText(ref: String?, snapshotId: String?): ActionResult

    /**
     * Type into the input field at this point ([x], [y] in pixels): tap it first, and if the tap brings up an input field holding focus, type as usual; when the tree shows no input field, paste via the clipboard and
     * verify by comparing the on-screen text before and after typing; if nothing changed at that point after pasting, it is point_not_typed (that point is probably not an input field, or it did not get focus).
     * By default it taps first and then types into the focused field, without separately verifying that point.
     */
    suspend fun typeAt(x: Int, y: Int, text: String): ActionResult =
        tapXY(x, y).let { if (it is ActionResult.Ok) typeText(null, null, text) else it }

    /** Clear the input field at this point, the same way as [typeAt]. */
    suspend fun clearAt(x: Int, y: Int): ActionResult =
        tapXY(x, y).let { if (it is ActionResult.Ok) clearText(null, null) else it }
    suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = DEFAULT_SWIPE_MS): ActionResult

    /**
     * While dwelling, scroll gently in passing ([fraction] of a screen or viewport height, positive for downward) without tapping anything. On the web it uses the same native input as scrolling down and does not move the pointer;
     * on the phone it slowly swipes a short distance over the scrollable content that takes up most of the screen. When scrolling is inconvenient at the moment (a dialog is open, the IME is up, an input field has focus, no scrollable content is visible), it does not scroll and
     * returns false; true when it scrolled. Does not scroll by default.
     */
    suspend fun idleScroll(fraction: Double): Boolean = false
    /** Multi-touch gesture; each path is a time-ordered list of pixel points, and all fingers move at the same time. */
    suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long): ActionResult = ActionResult.Failed("unsupported")
    suspend fun back(): ActionResult
    suspend fun pressKey(keyCode: Int): ActionResult
    suspend fun openApp(packageName: String, cold: Boolean = false): ActionResult

    /**
     * Open the copy of [packageName] installed under another user [userId] (clone, work profile, see [appCopies]); the rest is the same as [openApp].
     * Fails with copy_not_found when that user is unavailable or the App is not installed there. Unsupported ports return unsupported.
     */
    suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean = false): ActionResult = ActionResult.Failed("unsupported")

    /**
     * When [packageName] (the copy under another user [userId]; null is the original one) is in use by another task ([APP_IN_USE_BY_OTHER_TASK]),
     * wait up to [timeoutMs] for it to be released. This only waits, it does not open: once released the caller opens it again. When the task holding it is itself waiting for the
     * App this task holds (mutual waiting, neither can proceed), it does not wait and returns [AppWait.WouldDeadlock] immediately. Ports that do not compete for Apps with other tasks return [AppWait.Unsupported].
     */
    suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait = AppWait.Unsupported

    /**
     * Open a link or an Android settings page on the Agent's screen (when [action] is null, open [uri] as VIEW); [packageName] restricts which App opens it.
     * Failure reasons: link_unresolved (no App can open it), invalid_link, app_in_use_by_user. Unsupported ports return unsupported.
     */
    suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult = ActionResult.Failed("unsupported")

    /**
     * Share a piece of text to [packageName] (ACTION_SEND text/plain), opening its share page on the Agent's screen; only opens, does not send.
     * Fails with cannot_share when this App has no page for receiving shared text; everything else is like [openApp] (creating the screen, the user using it, the system asking which one to open). Unsupported ports return unsupported.
     */
    suspend fun shareText(packageName: String, text: String): ActionResult = ActionResult.Failed("unsupported")

    /** Whether this port can share audio; when not, the phone tools do not offer phone_share_audio. */
    val canShareAudio: Boolean get() = false

    /**
     * Share an audio clip from the workspace to [packageName] (ACTION_SEND + EXTRA_STREAM, with a content URI the other side may read), opening its share page on the Agent's screen;
     * only opens, does not send. Fails with cannot_share when this App has no page for receiving shared audio; unsupported ports return unsupported.
     */
    suspend fun shareAudio(packageName: String, path: String, text: String?): ActionResult = ActionResult.Failed("unsupported")
    /** Whether this port can share images (ACTION_SEND + EXTRA_STREAM); when not, the phone tools do not offer phone_share_image. */
    val canShareImage: Boolean get() = false

    /**
     * Share an image from the workspace to [packageName] (ACTION_SEND + EXTRA_STREAM, with a content URI the other side may read), opening its share page on the Agent's screen;
     * only opens, does not send. Fails with cannot_share when this App has no page for receiving shared images; unsupported ports return unsupported.
     */
    suspend fun shareImage(packageName: String, path: String, text: String?): ActionResult = ActionResult.Failed("unsupported")

    /**
     * Perform a system action through standard Android interfaces (see [SystemAction]): first check whether any App accepts it; if none, report not_supported; if one does, hand it over on the Agent's screen
     * (creating the screen on first use, and not opening when the user is using that App on the home screen). Unsupported ports return unsupported.
     */
    suspend fun systemAction(action: SystemAction): SystemActionResult = SystemActionResult(ActionResult.Failed("unsupported"))

    /**
     * Write a batch of calendar events at once through the Android calendar provider, without opening the calendar interface. The caller has already validated every event in full;
     * returns the corresponding failure reason when permissions are insufficient, no writable calendar exists, or the write result is uncertain, and does not automatically rewrite via another path.
     */
    suspend fun bulkCalendarInsert(events: List<SystemAction.AddCalendarEvent>): SystemActionResult =
        SystemActionResult(ActionResult.Failed(SystemActionResult.NOT_SUPPORTED))

    /**
     * Turn the wheel with ref [ref] (or an item inside the wheel) until [value] is displayed: all done inside the port in one go (type if typing works, scroll step by step if accessibility scrolling works,
     * otherwise drag by the height of one item), reading back after every few steps. Unsupported ports report unsupported.
     */
    suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome =
        WheelOutcome(false, null, null, 0, "none", error = "unsupported")
    suspend fun waitStable(timeoutMs: Long): Boolean
    /**
     * Wait for this screen's next interface change, at most [timeoutMs]; returns true when one occurs. Event-driven ports wake up on interface events (accessibility events, page mutations);
     * unsupported ports wait out the timeout and return false, and the caller re-reads at an interval.
     */
    suspend fun awaitChange(timeoutMs: Long): Boolean { delay(timeoutMs.coerceAtLeast(0)); return false }
    /** Wait for an event-driven interface condition; condition: appear/disappear/activity/stable. */
    suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean = false
    /** Listen for interface events of the virtual display, or global notifications; subscribed only during the call. */
    suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? = null
    /** Continuously collect events over a period; a single subscription does not miss changes between two events. */
    suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> =
        listOfNotNull(nextEvent(kind, packageName, durationMs))
    /** Execute a command directly through the shell assistant held by the task. */
    suspend fun systemCommand(command: String): SystemCommandResult? = null
    suspend fun listApps(): List<Pair<String, String>> // package name, display name

    /** Apps also installed under another user of this phone (clone, work profile, etc.): package name → names of those users. Empty when unavailable or unsupported. */
    suspend fun otherProfiles(): Map<String, List<String>> = emptyMap()

    /** Other App copies that can be opened (installed under another user such as a clone or work profile; see [AppCopies]); empty when unavailable or unsupported. */
    suspend fun appCopies(): List<AppCopy> = emptyList()

    /**
     * What happens to this App when the task ends (see [AfterTaskMarks]): only recorded, nothing is touched right now. Null for ports without end-of-task disposition.
     */
    val afterTask: AfterTaskMarks? get() = null

    /** Screen diagnosis; null when the port does not support it, and the caller makes a rough judgement from the element list. */
    suspend fun checkScreen(): ScreenCheck? = null

    /** Whether the current page is protected by the system against screen capture. */
    suspend fun isProtected(): Boolean = false

    /** The brightness cells the current picture is reduced to (used to judge action effects, see [FrameGrid.sample]); null when there is no picture or the port does not support it. Must be cheap: no image encoding. */
    suspend fun frameGrid(): FrameGrid? = null
}
