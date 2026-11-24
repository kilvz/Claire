package com.wangzan101.mika.core.export

import java.io.InputStream

/** Snapshot input: the app side maps conversation files, run records and workspace assets into these record types. */
interface ExportSource {
    suspend fun turns(conversationId: String): List<ExportTurnRecord>
    suspend fun run(runId: String): ExportRunRecord?
    suspend fun resolve(request: AssetRequest): ExportAsset?
}

data class ExportTurnRecord(
    val index: Int,
    val userText: String,
    val outcome: String,
    val status: String,
    val note: String? = null,
    val unconfirmed: List<String> = emptyList(),
    val runId: String? = null,
    val atMs: Long? = null,
    val steers: List<String> = emptyList(),
    val interruptedReplies: List<String> = emptyList(),
    val finishedReplies: List<String> = emptyList(),
    val files: List<ExportFileRef> = emptyList(),
)

data class ExportRunRecord(
    val model: String,
    val startedAtMs: Long,
    val steps: List<ToolStep>,
)

/** Opens asset bytes on demand while rendering; core tests use an in-memory implementation. */
interface ExportAssetReader {
    suspend fun open(asset: ExportAsset): InputStream?
}
