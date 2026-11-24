package com.wangzan101.mika.core.screen.uistate

/**
 * A copy of an app installed in another user profile (a clone, work profile or similar): [packageName] the package
 * name, [userId] that profile's user ID (used to specify it when opening), [userName] the name the system gives that
 * profile (follows the system language, not hard-coded; empty when it cannot be read).
 */
data class AppCopy(val packageName: String, val userId: Int, val userName: String) {
    /** The name shown to the model: "app name (another copy: that profile's name)". */
    fun label(appLabel: String): String = "$appLabel (another copy: ${userName.ifBlank { "user $userId" }})"
}

/**
 * Which apps in other user profiles Claire can open, and the entries listed. Pure logic, shared by the built-in
 * assistant (deciding which profiles are usable) and Claire (listing, checking).
 *
 * Deciding whether one profile is usable (following Android's multi-user concepts: read the UserInfo and
 * UserProperties that UserManager provides and the names the system gives its flags, not the user's name):
 * 1. Excluded first, whatever else holds: the user type contains private; the system flag names contain HIDDEN or
 *    PRIVATE; the system marks it as hidden from other apps (UserProperties' profileApiVisibility); guest, demo,
 *    ephemeral, disabled (flag names GUEST / DEMO / EPHEMERAL / DISABLED; when the flag names cannot be read, the
 *    corresponding AOSP flag bits). Private spaces and the like are never listed or opened, even if they are running
 *    and linked to the current user.
 * 2. Running and unlocked; not in quiet mode (a paused work profile is in quiet mode).
 * 3. Linked to the current user: the profile group (profileGroupId) is the same as the current user's, or the parent
 *    user the system reports (parentId) is the current user.
 * 4. And recognizably a clone or work profile: the user type contains clone, or the system flag names contain CLONE,
 *    or UserProperties says it shares media with its parent, or it is AOSP's work profile type
 *    android.os.usertype.profile.MANAGED.
 * Anything that cannot be read counts as not satisfied: better to omit one profile than to touch a profile the user
 * did not mean to expose.
 */
object AppCopies {
    // UserInfo.FLAG_* (values from AOSP); used only when the system's flag names cannot be read
    const val FLAG_GUEST = 0x4
    const val FLAG_DISABLED = 0x40
    const val FLAG_QUIET_MODE = 0x80
    const val FLAG_EPHEMERAL = 0x100
    const val FLAG_DEMO = 0x200
    private const val EXCLUDED_FLAGS = FLAG_GUEST or FLAG_DISABLED or FLAG_EPHEMERAL or FLAG_DEMO
    private val EXCLUDED_NAMES = setOf("GUEST", "DEMO", "EPHEMERAL", "DISABLED")

    /** UserInfo.NO_PROFILE_GROUP_ID (i.e. UserHandle.USER_NULL): not in any profile group. */
    const val NO_PROFILE_GROUP = -10000
    const val TYPE_MANAGED = "android.os.usertype.profile.MANAGED"

    /**
     * A user read by the built-in assistant: [flags] the UserInfo flags, [flagNames] the names the system gives those
     * flags (UserInfo.flagsToString, including system-customized ones; null when unreadable), [type] the user type
     * (null when unreadable), [profileGroupId] the profile group, [parentId] the parent user the system reports (null
     * when there is none), [running] running, [unlocked] unlocked, [quiet] in quiet mode, [mediaShared]
     * UserProperties.isMediaSharedWithParent, [hidden] UserProperties marks it hidden from other apps; the last two
     * are null when unreadable.
     */
    data class User(
        val id: Int,
        val flags: Int,
        val type: String?,
        val profileGroupId: Int,
        val running: Boolean,
        val unlocked: Boolean = running,
        val quiet: Boolean = false,
        val mediaShared: Boolean? = null,
        val hidden: Boolean? = null,
        val flagNames: List<String>? = null,
        val parentId: Int? = null,
    )

    /** Why a profile is usable or not; only [Copy] is usable. */
    enum class Verdict { Copy, Self, Private, Flags, Stopped, Locked, Quiet, NotLinked, NotCopy }

    fun verdict(u: User, current: Int, group: Int): Verdict {
        val type = u.type.orEmpty()
        val names = u.flagNames.orEmpty().map { it.uppercase() }
        return when {
            u.id == current -> Verdict.Self
            type.contains("private", ignoreCase = true) || u.hidden == true ||
                names.any { "HIDDEN" in it || "PRIVATE" in it } -> Verdict.Private
            (if (u.flagNames != null) names.any { it in EXCLUDED_NAMES } else u.flags and EXCLUDED_FLAGS != 0) -> Verdict.Flags
            !u.running -> Verdict.Stopped
            !u.unlocked -> Verdict.Locked
            u.quiet || u.flags and FLAG_QUIET_MODE != 0 || "QUIET_MODE" in names -> Verdict.Quiet
            !(u.profileGroupId == group && group != NO_PROFILE_GROUP) && u.parentId != current -> Verdict.NotLinked
            type.contains("clone", ignoreCase = true) || names.any { "CLONE" in it } || u.mediaShared == true || type == TYPE_MANAGED -> Verdict.Copy
            else -> Verdict.NotCopy
        }
    }

    /** The profile group the current user [current] belongs to; when the current user cannot be read or is in no group, it is itself. */
    fun groupOf(current: Int, users: List<User>): Int =
        users.firstOrNull { it.id == current }?.profileGroupId?.takeIf { it != NO_PROFILE_GROUP } ?: current

    /** The IDs of the other profiles whose apps can be opened. */
    fun usable(current: Int, users: List<User>): List<Int> {
        val group = groupOf(current, users)
        return users.filter { verdict(it, current, group) == Verdict.Copy }.map { it.id }
    }

    /** One line for diagnostics: for each other profile its ID, flags (and the flag names the system gives), type, profile group, parent and verdict; no names and no installed packages. */
    fun summary(current: Int, users: List<User>): String {
        val group = groupOf(current, users)
        val others = users.filter { it.id != current }
        val usable = others.count { verdict(it, current, group) == Verdict.Copy }
        if (others.isEmpty()) return "no other users"
        return "${others.size} other, $usable usable: " + others.joinToString("; ") { u ->
            "${u.id} flags=0x${Integer.toHexString(u.flags)}" + (u.flagNames?.let { " (${it.joinToString("|")})" } ?: "") +
                " type=${u.type ?: "?"} group=${u.profileGroupId}" + (u.parentId?.let { " parent=$it" } ?: "") +
                (if (!u.running) " stopped" else if (u.unlocked) " unlocked" else " locked") +
                " -> ${verdict(u, current, group).name.lowercase()}"
        }
    }

    /** A few fields for one user in `dumpsys user`: the flag names the system gives (in parentheses) and the parent user (parentId). */
    data class Dumped(val flagNames: List<String>?, val parentId: Int?)

    private val DUMP_USER = Regex("""UserInfo\{(\d+):""")
    private val DUMP_PARENT = Regex("""parentId=(-?\d+)""")
    private val DUMP_FLAGS = Regex("""^\s*Flags:\s*-?\d+\s*\(([^)]*)\)""")

    /** Parses `dumpsys user`: flag names and parent user for each user (by ID); null when the flag names cannot be read. Used when reflection cannot read the flag names. */
    fun parseDumpsys(output: String): Map<Int, Dumped> {
        val out = linkedMapOf<Int, Dumped>()
        var id: Int? = null
        for (line in output.lineSequence()) {
            val user = DUMP_USER.find(line)
            if (user != null) {
                val u = user.groupValues[1].toInt()
                id = u
                out[u] = Dumped(null, DUMP_PARENT.find(line)?.groupValues?.get(1)?.toInt())
                continue
            }
            val u = id ?: continue
            val flags = DUMP_FLAGS.find(line) ?: continue
            out[u] = out.getValue(u).copy(flagNames = flags.groupValues[1].split('|').map(String::trim).filter(String::isNotEmpty))
        }
        return out
    }

    /**
     * The other copies to list: [apps] the apps the current user can open (package name, display name), [users] the
     * usable profiles (with names), [packages] the packages installed in each profile. Only ones also installed for
     * the current user are listed (so they match an app in the list), ordered as in [apps].
     */
    fun copies(apps: List<Pair<String, String>>, users: List<UserProfile>, packages: Map<Int, Set<String>>): List<AppCopy> =
        apps.flatMap { (pkg, _) ->
            users.filter { pkg in packages[it.id].orEmpty() }.map { AppCopy(pkg, it.id, it.name) }
        }
}
