package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.ui.viewer.ViewerKind
import com.wangzan101.mika.ui.viewer.ViewerKinds

/**
 * Attachments carried by the user's message (one line per attachment at the end of the message, format in [AttachmentLines]): the conversation page uses this to draw them as photo tiles and file cards,
 * and the path lines are never shown to the user.
 */
object AttachmentMessage {
    /** One attachment: its path in the workspace and the size written in the message ("2 KB"). */
    data class Item(val path: String, val size: String) {
        val name: String get() = path.substringAfterLast('/')
        /** Recognizes photos by extension; photos are laid out as tiles, everything else as file cards. */
        val photo: Boolean get() = isPhoto(name)
    }

    /** A parsed message: [photos] and [files] in order of appearance, [words] is the text outside the attachment lines (trimmed). */
    data class Parsed(val photos: List<Item>, val files: List<Item>, val words: String) {
        val hasAttachments: Boolean get() = photos.isNotEmpty() || files.isNotEmpty()
    }

    fun parse(text: String): Parsed {
        val p = AttachmentLines.parse(text)
        val items = p.files.map { (path, size) -> Item(path, size) }
        return Parsed(items.filter { it.photo }, items.filterNot { it.photo }, p.words)
    }

    /** The text with attachment lines removed; when only attachments were sent it is a label ([label]). Used for the title and in the drawer. */
    fun plain(text: String): String {
        val p = parse(text)
        return if (!p.hasAttachments) text else p.words.ifEmpty { label(p) }
    }

    /** How to refer to a message that contains only attachments: "Photo", "3 photos", "contract.pdf", "contract.pdf and 2 more files", "Photos and files". */
    fun label(p: Parsed): String = when {
        p.photos.isNotEmpty() && p.files.isNotEmpty() -> tr(R.string.attach_photos_and_files)
        p.photos.size == 1 -> tr(R.string.attach_photo)
        p.photos.isNotEmpty() -> trPlural(R.plurals.attach_n_photos, p.photos.size)
        p.files.size == 1 -> p.files[0].name
        p.files.isNotEmpty() -> tr(R.string.attach_file_and_more, p.files[0].name, p.files.size)
        else -> ""
    }

    fun isPhoto(name: String): Boolean = ViewerKinds.of(null, name) == ViewerKind.Image
}

/** Photo tile layout: one is shown large (aspect ratio preserved), two sit side by side, three or more use three columns and wrap onto more rows. */
object PhotoGrid {
    fun columns(count: Int): Int = when {
        count <= 1 -> 1
        count == 2 -> 2
        else -> 3
    }

    fun rows(count: Int): Int = if (count <= 0) 0 else (count + columns(count) - 1) / columns(count)
}

/** Type badge on a file card: the extension (at most 4 letters) and the color group. */
object FileBadge {
    enum class Kind { Pdf, Sheet, Doc, Slides, Archive, Other }

    fun text(name: String): String = ext(name).uppercase().take(4).ifEmpty { tr(R.string.attach_files) }

    fun kind(name: String): Kind = when (ext(name)) {
        "pdf" -> Kind.Pdf
        "xls", "xlsx", "csv", "tsv", "numbers", "ods" -> Kind.Sheet
        "doc", "docx", "txt", "md", "rtf", "pages", "odt" -> Kind.Doc
        "ppt", "pptx", "key", "odp" -> Kind.Slides
        "zip", "rar", "7z", "gz", "tar", "tgz" -> Kind.Archive
        else -> Kind.Other
    }

    private fun ext(name: String): String = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }
}

/** Attachments waiting above the composer: at most [LIMIT] (photos and files together), and each URI counts only once. */
object AttachTray {
    const val LIMIT = 9

    /** Add: skip ones already present, reject ones beyond the limit; returns the new list and the number rejected. */
    fun add(current: List<PendingAttachment>, items: List<PendingAttachment>): Pair<List<PendingAttachment>, Int> {
        val out = current.toMutableList()
        var dropped = 0
        for (i in items) {
            if (out.any { it.uri == i.uri }) continue
            if (out.size >= LIMIT) dropped++ else out += i
        }
        return out to dropped
    }

    /** Tap: remove it if selected, add it if not (nothing is added when full, and [Toggle.full] is true). */
    fun toggle(current: List<PendingAttachment>, item: PendingAttachment): Toggle =
        if (current.any { it.uri == item.uri }) Toggle(current.filterNot { it.uri == item.uri }, full = false)
        else if (current.size >= LIMIT) Toggle(current, full = true)
        else Toggle(current + item, full = false)

    data class Toggle(val items: List<PendingAttachment>, val full: Boolean)

    /** Which photo this is among the pending ones (1-based, counting photos only); null when not selected. Shown in the top-right corner of the photo tile. */
    fun order(current: List<PendingAttachment>, uri: String): Int? =
        current.filter { it.photo }.indexOfFirst { it.uri == uri }.takeIf { it >= 0 }?.plus(1)

    /** Can send when there is text or an attachment and no import is in progress. */
    fun canSend(text: String, items: List<PendingAttachment>, importing: Boolean): Boolean =
        (text.isNotBlank() || items.isNotEmpty()) && !importing
}
