package com.wangzan101.mika.core.device.shell

/**
 * su's answer on a rooted phone. [Granted]: it agreed and ran the command as the requested identity; [Denied]: it refused (or su exited without running the command);
 * [NoAnswer]: it did not answer within the limit (nobody tapped the authorization prompt, or the root manager hung); [WrongUid]: it agreed but cannot switch to the requested identity (this su does not support specifying a user).
 */
enum class SuAnswer { Granted, Denied, NoAnswer, WrongUid }

/** Finding su and reading its answer: judged purely by capability (whether the file exists, which uid it runs as), not by which root solution it is. */
object SuProbe {
    /** Common su locations: some from the system PATH, and some where root solutions usually put it. Additional ones from PATH are also searched. */
    val DIRS = listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/system/sbin", "/vendor/bin", "/odm/bin", "/debug_ramdisk")

    /** The full path of the first su that exists; null when there is none. [path] is the PATH environment variable, and [exists] checks whether the file is there (swapped out in tests). */
    fun find(path: String?, exists: (String) -> Boolean): String? =
        (path.orEmpty().split(':').map { it.trim().trimEnd('/') }.filter { it.startsWith("/") } + DIRS)
            .distinct().map { "$it/su" }.firstOrNull(exists)

    private val UID = Regex("""uid=(\d+)""")

    /** The uid in the output of `id` or `id -u`; null when it cannot be read. su may print a few extra prompt lines first, so the output is scanned line by line. */
    fun uid(output: String): Int? =
        UID.find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?: output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()

    /**
     * The answer after running a command such as `su … -c id`. [output] is the output su produced (an empty string when it exited without producing any);
     * [timedOut]: no answer within the limit; [expectUid]: the requested identity. A wrong uid counts as [SuAnswer.WrongUid], and no readable uid counts as a refusal.
     */
    fun answer(output: String, timedOut: Boolean, expectUid: Int): SuAnswer {
        val uid = uid(output)
        return when {
            uid == expectUid -> SuAnswer.Granted
            uid != null -> SuAnswer.WrongUid
            timedOut -> SuAnswer.NoAnswer
            else -> SuAnswer.Denied
        }
    }

    /** The arguments for running one command as root: `su -c <command>` (every su accepts this form; the command is passed to sh as-is as a single argument). */
    fun rootArgs(su: String, command: String): List<String> = listOf(su, "-c", command)

    /**
     * The arguments for running one command as the shell identity (uid 2000): `su 2000 -c <command>`. su's user argument goes before the options,
     * since some su implementations treat everything after -c as the command. A su that cannot switch is spotted from the uid it runs as (see [answer]).
     */
    fun shellArgs(su: String, command: String): List<String> = listOf(su, HelperHandoff.SHELL_UID.toString(), "-c", command)
}

/**
 * Whether "start the phone helper with root automatically" is usable right now. [Off]: the user never requested it, or there is no clear authorization result yet;
 * [Ready]: the user requested it, su exists, and su has confirmed it can switch to the shell identity; the rest mean it cannot be used after authorization, each with its own message to the user.
 * [Gone]: su is no longer on the phone; [Denied]: su refused; [NoAnswer]: su did not answer; [NoShellUid]: su cannot switch to the shell identity.
 * The last three only ask su again after the user taps retry (so the authorization prompt does not keep popping up).
 */
enum class RootStart {
    Off, Ready, Gone, Denied, NoAnswer, NoShellUid;

    /** The failure reported in the status when it is enabled but unusable; null for [Off] and [Ready]. */    val failure: BuiltinShellFailure?
        get() = when (this) {
            Off, Ready -> null
            Gone -> BuiltinShellFailure.RootUnavailable
            Denied -> BuiltinShellFailure.RootDenied
            NoAnswer -> BuiltinShellFailure.RootNoAnswer
            NoShellUid -> BuiltinShellFailure.RootNoShellUid
        }

    companion object {
        /** [enabled]: whether the user requested automatic start; [suPresent]: whether su can be found; [last]: the answer su explicitly gave. */
        fun of(enabled: Boolean, suPresent: Boolean, last: SuAnswer?): RootStart = when {
            !enabled || last == null -> Off
            !suPresent -> Gone
            last == SuAnswer.Denied -> Denied
            last == SuAnswer.NoAnswer -> NoAnswer
            last == SuAnswer.WrongUid -> NoShellUid
            else -> Ready
        }
    }
}

/**
 * How the root item in settings looks right now (a pure state machine): without su it is not shown at all; while asking su it is [Pending],
 * and once asked it becomes [Granted] / [Denied] / [NoAnswer] / [NoShellUid] according to the answer; never asked is [Unknown].
 */
enum class RootGrant {
    Unknown, Pending, Granted, Denied, NoAnswer, NoShellUid;

    companion object {
        fun of(answer: SuAnswer?): RootGrant = when (answer) {
            null -> Unknown
            SuAnswer.Granted -> Granted
            SuAnswer.Denied -> Denied
            SuAnswer.NoAnswer -> NoAnswer
            SuAnswer.WrongUid -> NoShellUid
        }
    }
}
