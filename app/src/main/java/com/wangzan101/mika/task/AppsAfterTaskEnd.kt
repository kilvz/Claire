package com.wangzan101.mika.task

import android.util.Log
import com.wangzan101.mika.cap.phone.shell.AppsAfterTask
import com.wangzan101.mika.core.screen.uistate.AfterTaskMarks
import com.wangzan101.mika.shell.IMikaShell

/**
 * What happens to the apps on Claire's display when a task wraps up: the keep/close marks left by the model are handed to the built-in helper ([IMikaShell.finishDisplay]),
 * which keeps or closes them per the rules ([AppsAfterTask]) and then releases the virtual display. Must be called before restoring mutes: whatever is currently making sound is closed first, so kept apps do not suddenly make noise during the restore.
 * If the helper fails (error, unknown display), the display is still released at teardown and the apps on it close along with the display.
 */
internal object AppsAfterTaskEnd {
    private const val TAG = "AppsAfterTask"

    /** The result when a task last ended in this process (the one-line diagnostics, counts only); null when there has not been one yet. */
    @Volatile var lastLine: String? = null
        private set

    data class Outcome(val kept: List<String>, val closed: List<String>, val note: String?) {
        /** One line in the run record: the keys kept and closed, plus the reason. */
        val detail: String
            get() = listOfNotNull(
                "kept ${kept.joinToString(",").ifEmpty { "-" }}",
                "closed ${closed.joinToString(",").ifEmpty { "-" }}",
                note,
            ).joinToString("; ")

        val diagnostics: String get() = AppsAfterTask.diagnosticsLine(kept.size, closed, note)
    }

    fun finish(shell: IMikaShell, displayId: Int, marks: AfterTaskMarks?): Outcome {
        val keep = marks?.keepKeys().orEmpty().toTypedArray()
        val close = marks?.closeKeys().orEmpty().toTypedArray()
        val result = try {
            shell.finishDisplay(displayId, keep, close)
        } catch (t: Throwable) {
            Log.w(TAG, "finishDisplay failed; apps close with the display", t)
            null
        }
        val outcome = if (result == null) Outcome(emptyList(), emptyList(), "closed with the display (helper did not sort them)")
        else Outcome(
            result.getStringArray(AppsAfterTask.KEY_KEPT)?.toList().orEmpty(),
            result.getStringArray(AppsAfterTask.KEY_CLOSED)?.toList().orEmpty(),
            result.getString(AppsAfterTask.KEY_NOTE),
        )
        lastLine = outcome.diagnostics
        return outcome
    }
}
