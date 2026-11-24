package com.wangzan101.mika.core.run.record

/**
 * Reads run records that are already stored (read-only): the summary (run.json) and the events (events.jsonl). Records are already redacted and truncated when written, so they are returned as they are.
 * Returns null / an empty list when the record has been pruned, the id is unknown or it cannot be read; it never throws. Implemented on the app side against the records written to disk.
 */
interface RunRecords {
    suspend fun summary(runId: String): RunSummary?
    suspend fun events(runId: String): List<RunEvent>
}
