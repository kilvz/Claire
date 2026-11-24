package com.wangzan101.mika.core.frontdesk

/** One report the front desk should say: [event] is the change to a background task, [title] the short name given at dispatch. */
data class DeskReport(val event: DeskEvent, val title: String)

/**
 * An entry point currently attached to the front desk (chat page, voice call, phone call, …) that says reports in its own way.
 * Returning false means the entry point is offline and did not speak; being interrupted by the user mid-sentence throws [kotlinx.coroutines.CancellationException].
 */
interface DeskSurface {
    suspend fun deliver(report: DeskReport): Boolean
}

/** Fallback when no entry point is online: write back to the conversation and post a notification; implemented by the app layer. */
interface DeskSink {
    suspend fun record(conversationId: String, report: DeskReport)
}
