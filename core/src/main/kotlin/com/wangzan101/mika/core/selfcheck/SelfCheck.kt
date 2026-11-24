package com.wangzan101.mika.core.selfcheck

/**
 * One item of the compatibility self-check. The order of the entries is the order they run in; when any item in [needs] did not pass (failed or was skipped), this item is skipped and the missing item is named.
 * [key] is the English name in the report, [timeoutMs] is how long this item may run at most (the whole self-check has its own total limit, see [SelfCheckRunner.TOTAL_BUDGET_MS]).
 */
enum class SelfCheck(val key: String, val timeoutMs: Long) {
    /** Model, OS version, CPU architecture, memory, screen, whether wireless debugging exists, Claire's version. */
    DEVICE("device", 5_000),
    /** Accessibility is on and bound, notifications are allowed, battery is unrestricted. */
    PERMISSIONS("permissions", 5_000),
    /** The built-in helper is running, how it was started, and how long one simple call takes round trip. */
    HELPER("helper", 15_000),
    /** Claire's virtual display can be created; from Android 14 on, whether it has a focus of its own. */
    VIRTUAL_DISPLAY("virtual display", 10_000),
    /** Open Claire's own self-check page on the virtual display, then open system settings (only look, do not tap), and confirm they open on Claire's display, not on the main screen. */
    APP_LAUNCH("app launch", 20_000),
    /** The view tree of the self-check page can be read and all the required controls are there; the virtual display's screenshot is not all black or all blank. */
    SCREEN_READING("screen reading", 10_000),
    /** On the self-check page tap, type English and Chinese, scroll and go back; each is verified by reading back. */
    ACTIONS("actions", 20_000),
    /** While the self-check page keeps animating for a dozen or so seconds, the virtual display's frames keep changing and the view tree stays readable. */
    STAYS_ALIVE("stays alive", 25_000),
    /** The topmost task on the main screen did not change from start to finish. */
    MAIN_SCREEN("main screen", 5_000),
    /** The built-in browser opens a local web page (no network), reads the text out, taps a button and verifies it. */
    BROWSER("browser", 15_000),
    /** Run `echo ok` and one line of Python in the sandbox (arm64 only; a runtime that is not prepared counts as partially usable, nothing is installed on the spot). */
    SANDBOX("sandbox", 20_000),
    /** How many additional users (clones, work profiles) can open the apps inside; only counted, apps are not listed, the private space is excluded. */
    APP_COPIES("app copies", 10_000),
    ;

    /** The number in the report, starting at 1. */
    val number: Int get() = ordinal + 1

    /** The items that have to pass first. */
    val needs: List<SelfCheck>
        get() = when (this) {
            DEVICE, PERMISSIONS, BROWSER, SANDBOX -> emptyList()
            HELPER -> listOf(DEVICE)
            VIRTUAL_DISPLAY -> listOf(HELPER)
            APP_LAUNCH -> listOf(PERMISSIONS, VIRTUAL_DISPLAY)
            SCREEN_READING -> listOf(APP_LAUNCH)
            ACTIONS -> listOf(SCREEN_READING)
            STAYS_ALIVE -> listOf(SCREEN_READING)
            MAIN_SCREEN -> listOf(VIRTUAL_DISPLAY)
            APP_COPIES -> listOf(HELPER)
        }
}

enum class CheckStatus {
    Pending, Running, Pass, Partial, Fail, Skipped;

    /** Passed: usable, or partially usable. Items that depend on it still run. */
    val passed: Boolean get() = this == Pass || this == Partial

    /** Already has a conclusion (no longer changes). */
    val final: Boolean get() = this != Pending && this != Running
}

/**
 * Which situation the one-liner given to the user describes (the UI uses it to look up localized text, it is not written into the report). All are generic: the cause plus the next step that can be taken.
 */
enum class Advice {
    /** The OS version is below Android 12, so phone control is unavailable. */
    PhoneTooOld,
    /** There is no wireless debugging in this system: start via a computer over USB instead. */
    NoWirelessDebugging,
    /** This environment has neither developer options nor wireless debugging, so apps on the phone cannot be controlled; chat, web, files and calculation still work as usual. */
    PhoneControlUnavailable,
    /** Accessibility is not enabled. */
    TurnOnAccessibility,
    /** Accessibility is enabled but not running: turn it off and on again. */
    RestartAccessibility,
    /** Notifications are not allowed: progress and "needs you" reminders cannot be seen. */
    AllowNotifications,
    /** The battery is restricted: background work may be stopped. */
    AllowBattery,
    /** The built-in helper is not running: finish the phone control setup first. */
    SetUpPhoneControl,
    /** The virtual display cannot be created. */
    DisplayFailed,
    /** Below Android 14 a virtual display has no focus of its own, so the main screen's input focus may briefly be lost during background work. */
    FocusShared,
    /** The page did not open on Claire's display. */
    LaunchFailed,
    /** System settings opened on the main screen. */
    OpenedOnMainScreen,
    /** System settings are in use on your main screen, so this step did not open them. */
    SettingsInUse,
    /** The view tree cannot be read or is missing controls. */
    ReadFailed,
    /** The screenshot is all black or all blank. */
    BlankFrames,
    /** Tapping, typing, scrolling or going back had no effect. */
    ActionFailed,
    /** The virtual display's frames stop after a while. */
    FramesStopped,
    /** The topmost task on the main screen changed. */
    MainScreenChanged,
    /** There is no usable WebView in this system. */
    NoWebView,
    /** The built-in browser did not read anything out or the tap did not work. */
    WebFailed,
    /** The sandbox only exists on arm64 phones. */
    SandboxUnsupported,
    /** The runtime is not prepared yet. */
    SandboxNotPrepared,
    /** The command did not produce the expected result. */
    SandboxFailed,
    /** This item timed out. */
    TimedOut,
    /** This item hit an error. */
    Error,
    /** The time for the whole self-check ran out. */
    OutOfTime,
    /** The user cancelled. */
    Cancelled,
}

/**
 * The conclusion of a finished item. [detail] is the one line of fact written into the report (English, with no personal data: no accounts, app lists, user content or serial numbers);
 * [advice] lets the UI add one plain-language localized sentence; it is usually empty when the item passed.
 */
data class CheckOutcome(val status: CheckStatus, val detail: String, val advice: Advice? = null) {
    companion object {
        fun pass(detail: String) = CheckOutcome(CheckStatus.Pass, detail)
        fun partial(detail: String, advice: Advice) = CheckOutcome(CheckStatus.Partial, detail, advice)
        fun fail(detail: String, advice: Advice) = CheckOutcome(CheckStatus.Fail, detail, advice)
        fun skipped(detail: String, advice: Advice? = null) = CheckOutcome(CheckStatus.Skipped, detail, advice)
    }
}

/** An item's state right now: not run yet, running, or a conclusion; [blockedBy] is the item that did not pass and caused this one to be skipped. */
data class CheckResult(
    val check: SelfCheck,
    val status: CheckStatus = CheckStatus.Pending,
    val detail: String = "",
    val advice: Advice? = null,
    val blockedBy: SelfCheck? = null,
    val durationMs: Long = 0,
)

/**
 * The device facts in the report header, all from public system APIs; they contain nothing that identifies a person, such as serial numbers or accounts.
 * [wirelessDebugging]: whether this system has wireless debugging, null when it cannot be read reliably; [developerOptionsPage]: whether the developer options page can be opened on this system, null when unknown.
 * [phoneControlUnavailable]: this environment has no way to start the helper (the same decision as on the onboarding page).
 */
data class DeviceFacts(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdk: Int,
    val abis: List<String>,
    val ramBytes: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val wirelessDebugging: Boolean?,
    val appVersion: String,
    val appBuild: String,
    val developerOptionsPage: Boolean? = null,
    val phoneControlUnavailable: Boolean = false,
) {
    /** Model name: only the model when the model string already contains the manufacturer name. */
    val modelName: String get() = if (model.startsWith(manufacturer, ignoreCase = true)) model.trim() else "$manufacturer $model".trim()

    val arm64: Boolean get() = ARM64 in abis

    companion object {
        const val ARM64 = "arm64-v8a"
        /** Minimum OS version for phone control (Android 12). */
        const val MIN_PHONE_SDK = 31
        /** From Android 14 on a virtual display can have its own focus. */
        const val OWN_FOCUS_SDK = 34
    }
}

/**
 * The Android capabilities the self-check needs. The UI-side implementation lives in the app; unit tests substitute a fake.
 * Every item may be interrupted by a timeout or by the user cancelling and must be able to stop promptly; errors may be thrown directly, and the runner records them as failures.
 */
interface SelfCheckPort {
    /** The facts for the first item "device" (reads system APIs only, very fast). */
    suspend fun facts(): DeviceFacts

    /** Run one item (other than [SelfCheck.DEVICE]; that one is judged by [facts]). */
    suspend fun check(check: SelfCheck): CheckOutcome

    /** Release everything held during the run (virtual display, self-check page, web tabs, helper connection). Called after finishing, cancelling and on error, possibly more than once. */
    suspend fun release()
}
