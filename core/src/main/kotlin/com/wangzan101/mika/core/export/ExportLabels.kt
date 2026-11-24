package com.wangzan101.mika.core.export

/**
 * Human-readable text in the exported document. core cannot access Android resources, so the app builds it in the current language and passes it in;
 * JSON backends avoid these labels where possible, keeping stable machine-readable English keys.
 */
data class ExportLabels(
    val user: String,
    val mika: String,
    val steer: String,
    val answer: String,
    val toolSteps: String,
    val attachments: String,
    val screens: String,
    val files: String,
    val quote: String,
    val page: String,
    val exportedAt: String,
    val model: String,
    val unknown: String,
    val warnings: String,
    val truncated: String,
    val missingAsset: String,
    val tooLarge: String,
    val runRecordMissing: String,
    val unverified: String,
    val notDone: String,
    val cancelled: String,
    val toolError: String,
    val step: String,
) {
    companion object {
        /** English fallback for tests and when there is no localization. */
        val English = ExportLabels(
            user = "User", mika = "Claire", steer = "Message during the task", answer = "Answer",
            toolSteps = "Tool steps", attachments = "Attachments", screens = "Screenshots", files = "Files",
            quote = "Quoted", page = "From page", exportedAt = "Exported", model = "Model", unknown = "Unknown",
            warnings = "Warnings", truncated = "truncated, original length %d", missingAsset = "Missing: %s", tooLarge = "Too large: %s", runRecordMissing = "Run record no longer available",
            unverified = "Not verified: %s", notDone = "Not done: %s", cancelled = "Cancelled", toolError = "Error",
            step = "Step %d",
        )
    }
}
