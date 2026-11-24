package com.wangzan101.mika.files.ui

import com.wangzan101.mika.chat.FileSource
import com.wangzan101.mika.chat.SharedFile

/** File library multi-select: the key is determined only by the source identity, so it still matches after the index is refreshed. */
internal object LibrarySelection {
    fun inputKey(conversationId: String, path: String): String = "in:$conversationId:$path"

    /** Artifacts with a URI are merged by URI; those without a URI can only be distinguished by their source position. */
    fun outputKey(file: SharedFile, source: FileSource): String =
        if (file.uri.isNotBlank()) "out:uri:${file.uri}"
        else "out:src:${source.conversationId}:${source.turnIndex}:${source.runId}:${source.steerIndex}:${file.savedAs}:${file.size}"

    fun toggle(selected: Set<String>, key: String): Set<String> =
        if (key in selected) selected - key else selected + key

    /** Select all only affects the currently filtered rows; when all of those rows are selected, it deselects them. */
    fun toggleAll(selected: Set<String>, visible: List<String>): Set<String> {
        val keys = visible.toSet()
        return if (keys.isNotEmpty() && keys.all { it in selected }) selected - keys else selected + keys
    }

    /** Drops rows that no longer exist after a refresh. */
    fun prune(selected: Collection<String>, valid: Collection<String>): Set<String> {
        val keys = valid.toSet()
        return selected.filterTo(mutableSetOf()) { it in keys }
    }
}
