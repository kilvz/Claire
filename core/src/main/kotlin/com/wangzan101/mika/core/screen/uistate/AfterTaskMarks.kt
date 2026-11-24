package com.wangzan101.mika.core.screen.uistate

/**
 * What the model decided for the apps in this task regarding "what to do when the task ends": keep them (keep) or
 * close them (close), with a reason. Unmarked apps follow the default: stay in the background, and ones still making
 * sound at task end are closed (executed by the phone side when the task ends). Marking the same app copy again keeps
 * only the last mark. [userId] null means the original copy; another copy (clone, work profile) records that
 * profile's user ID.
 */
class AfterTaskMarks {
    data class Mark(val packageName: String, val userId: Int?, val keep: Boolean, val reason: String) {
        /** Key agreed with the phone side: the original copy uses the package name, another copy uses package@user. */
        val key: String get() = key(packageName, userId)
    }

    private val marks = LinkedHashMap<String, Mark>()

    /** Records a mark; returns the previous mark for this app copy (null when there was none). */
    @Synchronized
    fun mark(packageName: String, userId: Int?, keep: Boolean, reason: String): Mark? {
        val m = Mark(packageName, userId, keep, reason)
        return marks.remove(m.key).also { marks[m.key] = m }
    }

    @Synchronized
    fun all(): List<Mark> = marks.values.toList()

    fun keepKeys(): List<String> = all().filter { it.keep }.map { it.key }
    fun closeKeys(): List<String> = all().filter { !it.keep }.map { it.key }

    companion object {
        fun key(packageName: String, userId: Int?): String = if (userId == null) packageName else "$packageName@$userId"
    }
}
