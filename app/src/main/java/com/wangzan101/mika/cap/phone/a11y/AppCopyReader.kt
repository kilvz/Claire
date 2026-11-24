package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.core.screen.uistate.AppCopies
import com.wangzan101.mika.core.screen.uistate.AppCopy
import com.wangzan101.mika.core.screen.uistate.AppProfiles
import com.wangzan101.mika.core.screen.uistate.UserProfile
import com.wangzan101.mika.shell.IMikaShell

/** Asks the built-in assistant about another copy of an app that can be opened (installed in another user such as an app clone or work profile). */
internal object AppCopyReader {
    /** The user Claire runs as: the original copy of the app lives here; copies in other users are the other copies. */
    val myUser: Int get() = android.os.Process.myUid() / 100_000 // UserHandle.PER_USER_RANGE

    /**
     * The built-in assistant decides which users are usable (rules in [AppCopies]), then checks what is installed in those users
     * and matches it against the [apps] the current user can open;
     * names come from the system as given by `pm list users` (following the system language). Null when it cannot be queried, empty when there is no other copy.
     */
    fun read(shell: IMikaShell, apps: List<Pair<String, String>>): List<AppCopy>? {
        val ids = shell.appCopyUsers()?.getIntArray(ShellService.KEY_COPY_USERS) ?: return null
        if (ids.isEmpty()) return emptyList()
        val names = AppProfiles.parseUsers(shell.listUsers().orEmpty()).associate { it.id to it.name }
        val packages = ids.associateWith { AppProfiles.parsePackages(shell.listPackages(it) ?: return null) }
        return AppCopies.copies(apps, ids.map { UserProfile(it, names[it].orEmpty(), running = true) }, packages)
    }
}
