package com.wangzan101.mika.cap.sandbox

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/** Export to "Download/Claire": findable through MediaStore, size matches; oversized files, paths outside the workspace and links are never exported. */
@RunWith(AndroidJUnit4::class)
class ShareExporterDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val exporter = ShareExporter(context)
    private val ws = SandboxDirs(context).workspace(WorkspaceKey.Conversation("share-device-test")).apply { mkdirs() }
    private val exported = mutableListOf<String>()

    @After fun cleanUp() {
        for (uri in exported) runCatching { context.contentResolver.delete(android.net.Uri.parse(uri), null, null) }
        ws.deleteRecursively()
    }

    private fun expectError(code: String, block: suspend () -> Unit) = runBlocking {
        try { block(); fail("expected $code") } catch (e: SandboxError) { assertEquals(code, e.code) }
    }

    /** Same as AndroidSandbox.share: open [rel] in the workspace through the gate, then export from that handle. */
    private suspend fun export(rel: String, name: String) = AndroidFdSys.gate.openRead(ws, rel).use { exporter.export(it, name) }

    @Test fun exportsToDownloadsMika() = runBlocking {
        val f = File(ws, "out.txt").apply { writeText("你好".repeat(1000)) }
        val name = "mika-test-${System.currentTimeMillis()}.txt"
        val card = export(f.name, name)
        exported += card.uri
        val saved = card.savedAs
        assertTrue(saved, saved.startsWith("Download/Claire/"))
        assertEquals(f.length(), card.size)
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads.SIZE, MediaStore.Downloads.RELATIVE_PATH),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(saved.removePrefix("Download/Claire/")), null,
        )!!.use { c ->
            assertTrue("not found in MediaStore", c.moveToFirst())
            assertEquals(f.length(), c.getLong(0))
            assertTrue(c.getString(1), c.getString(1).trimEnd('/').endsWith("Download/Claire"))
        }
    }

    @Test fun tooLargeRejected() {
        val big = File(ws, "big.bin")
        RandomAccessFile(big, "rw").use { it.setLength(101L shl 20) }
        runBlocking {
            try { export("big.bin", "big.bin"); fail("exported a file over 100 MB") } catch (e: FileTooLarge) { }
        }
    }

    @Test fun outsideAndLinksRejected() {
        val outside = File(context.filesDir, "share-outside.txt").apply { writeText("secret") }
        try {
            expectError("path_outside_workspace") { export("../../../share-outside.txt", "x.txt") }
            val link = File(ws, "link.txt")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            // Open without following the link: the link itself cannot be opened
            expectError("not_a_file") { export("link.txt", "x.txt") }
            File(ws, "out.txt").writeText("a")
            expectError("invalid_arguments") { export("out.txt", "a‮txt.exe") }
        } finally {
            outside.delete()
        }
    }

    /** Swapped for a FIFO after the check: opening does not follow and does not block, the type itself is rejected, so it never hangs on a read. */
    @Test fun fifoRejectedWithoutBlocking() {
        val fifo = File(ws, "pipe.txt")
        android.system.Os.mkfifo(fifo.path, 0x180)
        val begin = System.currentTimeMillis()
        expectError("not_a_file") { export("pipe.txt", "pipe.txt") }
        assertTrue("took ${System.currentTimeMillis() - begin} ms", System.currentTimeMillis() - begin < 3_000)
    }

    /** Files with more than one hard link are not exported (they may be linked to Claire's own private files). Skipped when the system does not allow creating hard links. */
    @Test fun hardLinkedFileRejected() {
        val secret = File(context.filesDir, "share-secret.txt").apply { writeText("secret") }
        val link = File(ws, "linked.txt")
        try {
            val made = runCatching { android.system.Os.link(secret.path, link.path) }.isSuccess
            org.junit.Assume.assumeTrue("hard links not allowed here", made)
            expectError("linked_file") { export("linked.txt", "linked.txt") }
        } finally {
            secret.delete()
        }
    }

    @Test fun installPackagesExported() = runBlocking {
        val suffix = System.currentTimeMillis()
        val apk = File(ws, "app.apk").apply { writeText("PK") }
        val apkName = "mika-test-$suffix.apk"
        val apkCard = export(apk.name, apkName)
        exported += apkCard.uri
        assertEquals("Download/Claire/$apkName", apkCard.savedAs)
        assertEquals(apk.length(), apkCard.size)
        assertEquals("PK", context.contentResolver.openInputStream(android.net.Uri.parse(apkCard.uri))!!.bufferedReader().use { it.readText() })

        File(ws, "out.txt").writeText("a")
        val aabName = "mika-test-$suffix.aab"
        val aabCard = export("out.txt", aabName)
        exported += aabCard.uri
        assertEquals("Download/Claire/$aabName", aabCard.savedAs)
        assertEquals("a", context.contentResolver.openInputStream(android.net.Uri.parse(aabCard.uri))!!.bufferedReader().use { it.readText() })
    }
}
