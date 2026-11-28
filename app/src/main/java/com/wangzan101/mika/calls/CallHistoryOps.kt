package com.wangzan101.mika.calls

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.modelFor
import kotlinx.coroutines.CancellationException

// Call runtime: call history and call summaries
suspend fun CallRuntime.setHistoryHandled(id: String, handled: Boolean): CallActionResult = onMain {
    historyResult(records.setHandled(id, handled), if (handled) "Marked as handled" else "Marked as open")
}

suspend fun CallRuntime.saveFollowUpDraft(id: String, text: String): CallActionResult = onMain {
    if (text.length > CallHistory.MAX_DRAFT_LENGTH) return@onMain fail(app.getString(R.string.calls_draft_save_failed))
    historyResult(records.saveFollowUpDraft(id, text), app.getString(R.string.calls_draft_saved))
}

suspend fun CallRuntime.deleteHistory(id: String): CallActionResult = onMain {
    historyResult(records.delete(id), "Call record deleted")
}

suspend fun CallRuntime.clearHistory(): CallActionResult = onMain {
    historyResult(records.clear(), "Call history cleared")
}

internal fun CallRuntime.historyResult(result: CallHistory.Result, message: String): CallActionResult = when (result) {
    CallHistory.Result.SAVED -> ok(message)
    CallHistory.Result.NOT_FOUND -> fail("This call record no longer exists")
    CallHistory.Result.WRITE_FAILED -> fail("Could not save the call record change; try again")
}

suspend fun CallRuntime.retrySummary(id: String): CallActionResult {
    if (!settings.state.value.summaryEnabled) {
        return CallActionResult(false, app.getString(R.string.calls_summary_cloud_consent))
    }
    val record = records.state.value.firstOrNull { it.id == id }
        ?: return CallActionResult(false, "Could not find this call record")
    if (record.transcript.isEmpty()) return CallActionResult(false, "There is no transcript to summarize")
    return if (records.retryFailedSummary(id) { summarizeCall(id, it.transcript) }) CallActionResult(true, "Summary updated")
    else CallActionResult(false, app.getString(R.string.calls_summary_failed))
}

internal suspend fun CallRuntime.summarizeCall(id: String, transcript: List<CallTranscriptLine>): Boolean {
    val direction = records.state.value.firstOrNull { it.id == id }?.direction ?: CallDirection.INCOMING
    val summary = try {
        if (!settings.state.value.summaryEnabled) null
        else app.providers.modelFor(ModelRole.Main)?.let {
            CallSummaryGenerator.generate(
                it,
                transcript,
                direction,
                languageTag = callLanguageTag(),
            ) { settings.state.value.summaryEnabled }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    return if (summary == null) {
        records.setSummary(id, null, SummaryStatus.FAILED)
        records.state.value.firstOrNull { it.id == id }?.let { notifications.updateCompletedSummary(id, it.number, it.direction, null, failed = true) }
        false
    } else {
        val saved = records.setSummary(id, summary, SummaryStatus.READY) == CallHistory.Result.SAVED
        if (saved) records.state.value.firstOrNull { it.id == id }?.let {
            notifications.updateCompletedSummary(id, it.number, it.direction, summary, failed = false)
        }
        saved
    }
}
