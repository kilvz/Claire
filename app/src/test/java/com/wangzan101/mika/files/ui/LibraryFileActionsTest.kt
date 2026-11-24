package com.wangzan101.mika.files.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFileActionsTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    /** The copy plus `.tmp` must fit in the 255 bytes allowed for a file name, the extension is kept (drafts and previews use it to recognize the type), and no character is split. */
    @Test fun copyNameKeepsTheExtensionAndFitsTheFileSystem() {
        val ascii = LibraryFileActions.copyName("r".repeat(230) + ".xlsx")
        assertTrue(ascii, ascii.endsWith(".xlsx"))
        assertTrue(bytes("$ascii.tmp") <= 255)

        // Truncated by bytes: 83 CJK characters plus .pdf is 253 bytes, and adding .tmp would exceed the limit
        val cjk = LibraryFileActions.copyName("报".repeat(83) + ".pdf")
        assertTrue(cjk, cjk.endsWith(".pdf"))
        assertTrue(bytes("$cjk.tmp") <= 255)

        // When truncating to 240 bytes that emoji does not fit, so it is dropped entirely instead of being left half-cut
        assertEquals("a".repeat(233) + ".txt", LibraryFileActions.copyName("a".repeat(233) + "\uD83D\uDE00" + ".txt"))

        assertEquals("合同.pdf", LibraryFileActions.copyName("合同.pdf"))
        assertEquals("a_b.txt", LibraryFileActions.copyName("a/b.txt"))
        assertEquals("attachment", LibraryFileActions.copyName(".."))
    }
}
