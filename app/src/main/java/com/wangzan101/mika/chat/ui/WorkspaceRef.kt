package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.ui.MarkdownModel

/** Turns the `/workspace/...` references extracted from a reply into the attachment render model (photos/files separated, the size left blank); references that escape the sandbox (`..`) are dropped. */
fun outputRefsParsed(answer: String): AttachmentMessage.Parsed {
    val items = MarkdownModel.workspaceRefs(answer)
        .filter { SandboxPaths.normalize(it) != null }
        .map { AttachmentMessage.Item(it, size = "") }
    return AttachmentMessage.Parsed(photos = items.filter { it.photo }, files = items.filterNot { it.photo }, words = "")
}
