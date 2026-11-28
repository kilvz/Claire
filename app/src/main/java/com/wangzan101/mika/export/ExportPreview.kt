package com.wangzan101.mika.export

import com.wangzan101.mika.core.export.ExportWarning
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Text preview returns content directly; HTML preview hands ownership of this staging directory to the caller. */
sealed interface ExportPreview {
    val warnings: List<ExportWarning>

    data class Text(
        val formatId: String,
        val text: String,
        override val warnings: List<ExportWarning>,
        val truncated: Boolean = false,
    ) : ExportPreview

    data class Html(
        val directory: File,
        val entry: String,
        override val warnings: List<ExportWarning>,
    ) : ExportPreview {
        private val released = AtomicBoolean(false)

        /** Called when closing the preview or leaving the panel; repeated release does not wrongly delete a later preview's directory. */
        fun release() {
            if (released.compareAndSet(false, true)) directory.deleteRecursively()
        }
    }
}
