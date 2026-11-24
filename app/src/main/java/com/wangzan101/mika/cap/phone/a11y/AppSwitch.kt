package com.wangzan101.mika.cap.phone.a11y

/** Whether the foreground switching to another package counts as "being switched away from". */
internal object AppSwitch {
    /** [byTarget]: the new foreground is in the target app's own task and was opened by it (such as sharing to another app), so the wording does not treat it as an interruption. */
    data class Switch(val packageName: String, val byTarget: Boolean)

    /**
     * Not counted as being switched away from: the system chooser, the file/photo picker, permission dialogs ([partOfTask]), and system settings
     * pages the target app opens within its own task ([settingsPage], such as a city picker page provided by system settings). Other apps
     * in the target task (including the preinstalled browser and app store) are still reported, but tagged byTarget.
     * [sameTask] is only queried when the package name differs and it is not a chooser.
     * When the package name is the same, only [otherCopy] is consulted: when the same app is installed twice (app clone, work profile) and the top one has changed to the copy that is not the target, that also counts as being switched away from.
     */
    fun switchedTo(
        target: String?,
        current: String?,
        sameTask: () -> Boolean,
        partOfTask: Set<String>,
        settingsPage: (String) -> Boolean,
        otherCopy: () -> Boolean = { false },
    ): Switch? {
        if (target != null && current == target) return if (otherCopy()) Switch(current, byTarget = false) else null
        if (target == null || current == null || current in partOfTask) return null
        if (!sameTask()) return Switch(current, byTarget = false)
        return if (settingsPage(current)) null else Switch(current, byTarget = true)
    }
}
