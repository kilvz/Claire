package com.wangzan101.mika.cap.web

import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.cap.web.DownloadInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.coroutines.resume
import com.wangzan101.mika.core.workspace.SandboxTree

/**
 * On-device test: web downloads land in the workspace's downloads/, and uploads only hand over workspace files. Uses blob: downloads from a local fixture (no external network);
 * Cookie handling, limits, and status for http(s) downloads are covered by the JVM WebDownloadsTest with MockWebServer (plain-text local servers are blocked by the system on a real device).
 */
@RunWith(AndroidJUnit4::class)
class WebFilesTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var stack: WebStack
    private lateinit var ws: File

    @Before
    fun setUp() {
        // Placed under the sandbox's work/ just like the real thing; the upload hands the page a copy from the cache staging area
        ws = File(ctx.filesDir, "sandbox/work/c-webfiles-test").apply { deleteRecursively(); mkdirs() }
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        instr.runOnMainSync {
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(instr.context)).build()
            stack = WebStack.create(
                ctx, "run-files", bounds.width(), bounds.height(), ctx.resources.displayMetrics.densityDpi, listOf(loader),
                workspace = WebStack.Workspace(ws, com.wangzan101.mika.cap.sandbox.AndroidFdSys.gate, SandboxTree()),
            )
        }
        runBlocking { stack.screen.navigate("https://appassets.androidplatform.net/assets/web-fixtures/files.html", newTab = false) }
    }

    @After
    fun tearDown() {
        instr.runOnMainSync { stack.close() }
        ws.deleteRecursively()
    }

    private suspend fun js(script: String): String = withContext(Dispatchers.Main) {
        val tab = stack.pool.current("run-files")!!
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private suspend fun tap(id: String): ActionResult {
        val s = stack.screen.snapshot()
        return stack.screen.tapRef(s.elements.first { it.resourceId == "#$id" }.ref, s.snapshotId)
    }

    private fun awaitDone(file: String): DownloadInfo? = DeviceSupport.pollUntil(10_000) {
        runBlocking { stack.screen.downloads() }.firstOrNull { it.file == file && it.status != "running" }
    }

    private fun topResumed() = DeviceSupport.sh("dumpsys activity activities").lines().map { it.trim() }
        .firstOrNull { it.startsWith("topResumedActivity") || it.startsWith("mTopResumedActivity") }

    @Test
    fun blobDownloadWorks() = runBlocking<Unit> {
        val r = tap("blobcsv") as ActionResult.Ok
        assertTrue("${r.hint}", r.hint!!.contains("A download started: \"/workspace/downloads/report.csv\""))
        assertEquals("done", awaitDone("/workspace/downloads/report.csv")!!.status)
        assertEquals("a,b\n1,2\n", File(ws, "downloads/report.csv").readText())
    }

    @Test
    fun maliciousFilenameSanitized() = runBlocking<Unit> {
        tap("evilname")
        val d = DeviceSupport.pollUntil(10_000) { runBlocking { stack.screen.downloads() }.firstOrNull { it.status == "done" } }
        assertNotNull(d)
        assertTrue(d!!.file, d.file.startsWith("/workspace/downloads/") && ".." !in d.file && d.file.count { it == '/' } == 3)
        // Nothing besides downloads/ appeared in the workspace, and certainly nothing outside it
        assertEquals(listOf("downloads"), ws.list()!!.toList())
        assertFalse(File(ws.parentFile, "x.sh").exists())
    }

    @Test
    fun apkSavedNeverOpened() = runBlocking<Unit> {
        val before = topResumed()
        assertNotNull(before)
        tap("blobapk")
        assertEquals("done", awaitDone("/workspace/downloads/app.apk")!!.status)
        Thread.sleep(1_000)
        assertEquals(before, topResumed())
        assertEquals(6L, File(ws, "downloads/app.apk").length())
    }

    @Test
    fun uploadGivesWorkspaceFile() = runBlocking<Unit> {
        File(ws, "inbox").mkdirs()
        File(ws, "inbox/photo.txt").writeText("12345")
        val s = stack.screen.snapshot()
        val picker = s.elements.first { it.resourceId == "#picker" }
        assertEquals("file", picker.role)
        val r = stack.screen.upload(picker.ref, s.snapshotId, listOf("/workspace/inbox/photo.txt"))
        assertTrue("$r", r is ActionResult.Ok)
        assertTrue((r as ActionResult.Ok).hint!!.contains("photo.txt"))
        assertNotNull(DeviceSupport.pollUntil(5_000) { runBlocking { js("window.picked") }.takeIf { it == "\"photo.txt:5\"" } })
        // The page receives a copy from the staging area; it is deleted when the task ends
        val staging = File(ctx.cacheDir, UploadStaging.DIR)
        assertEquals(1, staging.list().orEmpty().size)
        instr.runOnMainSync { stack.close() }
        assertTrue(staging.list().orEmpty().isEmpty())
    }

    /** Hard links inside the workspace (which may link to Claire's private files) are not handed to the page, and no copy is left behind. */
    @Test
    fun uploadRefusesHardLinkedFiles() = runBlocking<Unit> {
        val secret = File(ctx.filesDir, "webfiles-secret.txt").apply { writeText("secret") }
        try {
            val made = runCatching { android.system.Os.link(secret.path, File(ws, "linked.txt").path) }.isSuccess
            org.junit.Assume.assumeTrue("hard links not allowed here", made)
            val s = stack.screen.snapshot()
            val picker = s.elements.first { it.resourceId == "#picker" }
            assertEquals(ActionResult.Failed("linked_file"), stack.screen.upload(picker.ref, s.snapshotId, listOf("/workspace/linked.txt")))
            assertTrue(File(ctx.cacheDir, UploadStaging.DIR).list().orEmpty().isEmpty())
        } finally {
            secret.delete()
        }
    }

    @Test
    fun uploadRefusesFilesOutsideTheWorkspace() = runBlocking<Unit> {
        val s = stack.screen.snapshot()
        val picker = s.elements.first { it.resourceId == "#picker" }
        assertEquals(ActionResult.Failed("file_not_found"), stack.screen.upload(picker.ref, s.snapshotId, listOf("/workspace/missing.txt")))
        assertEquals("null", js("window.picked"))
    }

    @Test
    fun chooserWithoutPendingUploadIsCancelled() = runBlocking<Unit> {
        tap("picker")
        Thread.sleep(500)
        assertEquals("null", js("window.picked"))
        assertEquals(1, withContext(Dispatchers.Main) { stack.pool.current("run-files")!!.choosersRefused })
        // The page can still be operated as usual
        assertTrue(tap("blobcsv") is ActionResult.Ok)
    }
}
