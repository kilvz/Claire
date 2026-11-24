package com.wangzan101.mika.files.ui

import com.wangzan101.mika.chat.FileSource
import org.junit.Assert.assertEquals
import org.junit.Test
import com.wangzan101.mika.chat.ui.ChatTarget

class FileLibraryTargetTest {
    private val source = FileSource("a", turnIndex = 3, runId = "run", atMs = 10L)

    @Test fun inputFileJumpsToTheMessageThatAttachedIt() {
        assertEquals(ChatTarget.Message(3, null), fileLibraryTarget(source, input = true))
        assertEquals(ChatTarget.Message(3, 1), fileLibraryTarget(source.copy(steerIndex = 1), input = true))
    }

    @Test fun outputFileJumpsToTheTurnFileCard() {
        assertEquals(ChatTarget.Files(3), fileLibraryTarget(source, input = false))
        // The output file card belongs to the whole turn and does not follow a particular steer
        assertEquals(ChatTarget.Files(3), fileLibraryTarget(source.copy(steerIndex = 1), input = false))
    }
}
