package com.wangzan101.mika.core.cap.phone

/**
 * When a system command does what a dedicated tool does (tap, swipe, type, key press, open App), the result reminds
 * the model to switch back to that tool: the dedicated tool acts on Claire's screen and verifies the effect
 * afterwards, whereas input sent by the shell is not verified. The first time it only reminds and still executes;
 * once this task has been reminded, commands doing these kinds of things are not executed and only that reminder is
 * returned ([refuses]). Sharing text through the shell is never executed and is redirected to the share-text tool.
 * Commands that check status or read settings and the like are unaffected.
 *
 * Typing ([Purpose.Type]) is the exception: hidden input fields of self-drawn UIs such as Flutter/WebView cannot be
 * written via accessibility node actions, only via the shell's `input text`; typing is also the safest and can be
 * read back to verify, so it may run repeatedly and is not refused because of [steered] (#371).
 */
internal object CommandSteer {
    enum class Purpose(val feedbackKey: String) {
        Gesture("system_command.use_tap"), Type("system_command.use_type"), Key("system_command.use_key"), Launch("system_command.use_launch"),
        Share("system_command.use_share"),
    }

    /** Whether a command with [purpose] is refused this time: sharing is always refused; the rest are refused once this task has been reminded ([steered]) — typing ([Type]) is the exception and is always allowed. */
    fun refuses(purpose: Purpose?, steered: Boolean): Boolean =
        purpose == Purpose.Share || (purpose != null && purpose != Purpose.Type && steered)

    /** Every `input …` sub-command in the command (optionally preceded by an input source and a -d display number). */
    private val INPUT = Regex("""(?:^|[;&|(\s])input\s+(?:(?:touchscreen|touchpad|keyboard|mouse|stylus|trackball|dpad|gamepad|joystick|rotaryencoder|touchnavigation)\s+)?(?:-d\s+\S+\s+)?([a-z]+)""")
    private val LAUNCH = Regex("""(?:^|[;&|(\s])(?:am\s+(?:start|start-activity)\b|cmd\s+activity\s+start(?:-activity)?\b|monkey\s+(?:.*\s)?-p\s)""")
    private val GESTURES = setOf("tap", "swipe", "draganddrop", "motionevent", "press", "roll")
    /** What is being launched is a share: the action spelled out (android.intent.action.SEND) or just -a SEND. */
    private val SHARE = Regex("""(?:android\.intent\.action\.SEND|-a\s+["']?SEND)(?![A-Za-z_])""")
    /** What is shared is a file (with STREAM): the share-text tool cannot do that, so treat it as an ordinary launch. */
    private const val STREAM = "android.intent.extra.STREAM"

    /**
     * Which kind of dedicated tool the command is imitating; null when none. When typing and tapping are in one
     * command it counts as typing (what needs saying is what typing should use);
     * sharing text comes first: it is never executed.
     */
    fun purpose(command: String): Purpose? {
        val subs = INPUT.findAll(command).map { it.groupValues[1] }.toSet()
        val launches = LAUNCH.containsMatchIn(command)
        return when {
            launches && SHARE.containsMatchIn(command) && STREAM !in command -> Purpose.Share
            "text" in subs -> Purpose.Type
            subs.any { it in GESTURES } -> Purpose.Gesture
            subs.any { it == "keyevent" || it == "keycombination" } -> Purpose.Key
            launches -> Purpose.Launch
            else -> null
        }
    }
}
