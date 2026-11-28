package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class CorruptBackupTest {
    @Test fun eachCorruptionKeepsItsOwnCopy() {
        val file = Files.createTempDirectory("corrupt-test").resolve("runs-v3.json").toFile()
        file.writeText("first")
        file.keepCorruptCopy(now = 5)
        file.writeText("second")
        file.keepCorruptCopy(now = 5)
        val copies = file.corruptCopies()
        assertEquals(listOf("runs-v3.json.corrupt-5", "runs-v3.json.corrupt-5-1"), copies.map { it.name })
        assertEquals(listOf("first", "second"), copies.map { it.readText() })
        assertEquals("second", file.readText())
    }
}
