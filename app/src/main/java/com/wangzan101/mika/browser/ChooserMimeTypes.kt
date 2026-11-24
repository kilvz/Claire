package com.wangzan101.mika.browser

/** Types supplied by the web page's file chooser → MIME types for the system picker. */
object ChooserMimeTypes {
    /**
     * Extensions (starting with `.`) are passed to [mimeOf] to be converted into MIME types, everything else is kept as-is; empty entries are dropped; when none are left, all types are used.
     */
    fun of(acceptTypes: List<String>, mimeOf: (String) -> String?): List<String> {
        val types = acceptTypes.mapNotNull { raw ->
            val t = raw.trim()
            when {
                t.isEmpty() -> null
                t.startsWith(".") -> mimeOf(t.removePrefix(".").lowercase())
                else -> t
            }
        }.distinct()
        return types.ifEmpty { listOf("*/*") }
    }
}
