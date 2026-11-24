package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.settings.AppAccessStore

/**
 * What the three choices on the overlay mean when the user shares the screen of a blocked app on their own:
 * 1 = allow screen sharing only (can share proactively later, automatic reading and actions still forbidden),
 * 2 = allow this app (normal calls resume), 0/other = cancel the share.
 * Returns whether this share continues; cancelling leaves permissions untouched. Choosing "allow sharing only" does
 * not lift the block, choosing "allow this app" clears the standalone share permission.
 */
internal fun applyShareChoice(choice: Int, pkg: String, store: AppAccessStore): Boolean = when (choice) {
    1 -> { store.setShareAllowed(pkg, true); true }
    2 -> { store.setBlocked(pkg, false); true }
    else -> false
}
