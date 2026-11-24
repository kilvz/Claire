package com.wangzan101.mika.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** The attachment-line format: the words first and one line per attachment after them, and parsing it back; the format stored in old conversations; and lines that only look like attachment lines. */
class AttachmentLinesTest {
    @Test fun composeWritesWhatParseReads() {
        val files = listOf("/workspace/inbox/a.png" to "3 KB", "/workspace/inbox/b.docx" to "1.0 MB")
        val text = AttachmentLines.compose("查一下", files)
        assertEquals("查一下\n附件:/workspace/inbox/a.png(3 KB)\n附件:/workspace/inbox/b.docx(1.0 MB)", text)
        assertEquals(AttachmentLines.Parsed(files, "查一下"), AttachmentLines.parse(text))
        assertEquals("附件:/workspace/inbox/a.png(3 KB)", AttachmentLines.compose("", listOf("/workspace/inbox/a.png" to "3 KB")))
    }

    // Format saved in older conversations: parentheses inside the name, attachments with no words, and a size in bytes
    @Test fun oldMessagesParseTheSameWay() {
        assertEquals(
            AttachmentLines.Parsed(listOf("/workspace/inbox/报表 (1).csv" to "2 KB", "/workspace/inbox/x" to "512 B"), ""),
            AttachmentLines.parse("附件:/workspace/inbox/报表 (1).csv(2 KB)\n附件:/workspace/inbox/x(512 B)"),
        )
    }

    @Test fun linesThatOnlyLookLikeAttachmentsStayInTheWords() {
        for (t in listOf("附件:/etc/passwd(1 KB)", "附件:/workspace/inbox/a.pdf", "附件:/workspace/inbox/a.pdf(很大)", "看看附件:/workspace/inbox/a.pdf(1 KB)")) {
            assertEquals(t, AttachmentLines.Parsed(emptyList(), t), AttachmentLines.parse(t))
        }
        assertEquals(AttachmentLines.Parsed(emptyList(), "普通的一句话"), AttachmentLines.parse("  普通的一句话 "))
    }
}
