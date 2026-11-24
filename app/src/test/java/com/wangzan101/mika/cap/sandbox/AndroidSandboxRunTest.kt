package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class AndroidSandboxRunTest {
    private val tmp: File = Files.createTempDirectory("android-sandbox-run").toFile()
    private val sb = AndroidSandbox(SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache"), File(tmp, "lib"), JVM_GATE, SandboxTree()), { SandboxStatus.Ready(false) }, { emptyList() })

    @Test fun cwdOutsideWorkspaceRejected() = runBlocking {
        for (cwd in listOf("../..", "/etc", "/opt/mika/skills/x", "a/../../b")) {
            try {
                sb.run("c1", listOf("true"), cwd, 1_000) {}
                fail("cwd $cwd was accepted")
            } catch (e: SandboxError) {
                assertEquals(cwd, "path_outside_workspace", e.code)
            }
        }
    }

    /** Create the skills directory before binding even when it is not synced yet: with a missing source directory PRoot writes warnings into the command output. */
    @Test fun bindSourcesExistBeforeLaunch() = runBlocking {
        val dirs = SandboxDirs(File(tmp, "s3/files/sandbox"), File(tmp, "s3/cache"), File(tmp, "s3/lib"), JVM_GATE, SandboxTree())
        val sb = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() })
        assertFalse(dirs.skills.exists())
        runCatching { sb.run("c1", listOf("true"), null, 1_000) {} } // no launcher on the JVM, so the launch itself fails
        assertTrue(dirs.skills.isDirectory)
        assertTrue(dirs.workspace(WorkspaceKey.Conversation("c1")).isDirectory)
        assertTrue(dirs.tmp.isDirectory)
    }

    @Test fun existingSandboxGetsUpdatedClientScript() = runBlocking {
        val dirs = SandboxDirs(File(tmp, "client/files/sandbox"), File(tmp, "client/cache"), File(tmp, "client/lib"), JVM_GATE, SandboxTree())
        val client = File(dirs.rootfs, "usr/local/bin/mika")
        client.parentFile!!.mkdirs()
        client.writeText("old bridge")
        val sb = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() },
            clientScript = { "new bridge".toByteArray() })
        runCatching { sb.run("c1", listOf("true"), null, 1_000) {} } // PRoot is not started on the JVM
        assertEquals("new bridge", client.readText())
        assertTrue(client.canExecute())
    }

    /** The runtime is not ready when a tool call arrives: start preparing and wait for it; it is Ready once preparation finishes, a timeout returns the status at that moment, and states that are not supported do not wait. */
    @Test fun awaitReadyStartsPreparationAndWaits() = runBlocking {
        var status: SandboxStatus = SandboxStatus.NotInstalled
        var starts = 0
        val dirs = SandboxDirs(File(tmp, "s4/files/sandbox"), File(tmp, "s4/cache"), File(tmp, "s4/lib"), JVM_GATE, SandboxTree())
        val sb = AndroidSandbox(dirs, { status }, { emptyList() }, startPreparing = {
            starts++
            kotlinx.coroutines.GlobalScope.launch { kotlinx.coroutines.delay(50); status = SandboxStatus.Ready(false) }
        })
        assertEquals(SandboxStatus.Ready(false), sb.awaitReady(5_000))
        assertEquals(1, starts)
        assertEquals(SandboxStatus.Ready(false), sb.awaitReady(5_000))
        assertEquals(1, starts) // already ready, so no further preparation

        status = SandboxStatus.Installing(10, "解包")
        val slow = AndroidSandbox(dirs, { status }, { emptyList() }, startPreparing = { kotlinx.coroutines.GlobalScope.launch { kotlinx.coroutines.delay(60_000) } })
        assertEquals(SandboxStatus.Installing(10, "解包"), slow.awaitReady(100))

        status = SandboxStatus.Unsupported("x86")
        assertEquals(SandboxStatus.Unsupported("x86"), AndroidSandbox(dirs, { status }, { emptyList() }, startPreparing = { fail("must not prepare"); null }).awaitReady(100))
        status = SandboxStatus.Broken("坏了")
        assertEquals(SandboxStatus.Broken("坏了"), AndroidSandbox(dirs, { status }, { emptyList() }).awaitReady(100))
    }

    @Test fun preparerNeedsWork() {
        assertTrue(RuntimePreparer.needsWork(SandboxStatus.NotInstalled, null, "v2", skipBroken = false))
        assertTrue(RuntimePreparer.needsWork(SandboxStatus.Ready(false), "v1", "v2", skipBroken = false)) // Claire was upgraded
        assertFalse(RuntimePreparer.needsWork(SandboxStatus.Ready(true), "v2", "v2", skipBroken = false))
        assertTrue(RuntimePreparer.needsWork(SandboxStatus.Broken("x"), "v2", "v2", skipBroken = false))
        assertFalse(RuntimePreparer.needsWork(SandboxStatus.Broken("x"), "v2", "v2", skipBroken = true))
        assertFalse(RuntimePreparer.needsWork(SandboxStatus.Installing(5, "解包"), null, "v2", skipBroken = false))
        assertFalse(RuntimePreparer.needsWork(SandboxStatus.Unsupported("x86"), null, "v2", skipBroken = false))
    }

    @Test fun exit124OnlyCountsAsTimeoutAtTheLimit() {
        assertFalse(sb.launcherTimedOut(124, 200, 60))
        assertTrue(sb.launcherTimedOut(124, 59_600, 60))
        assertFalse(sb.launcherTimedOut(1, 60_000, 60))
    }

    @Test fun shareGoesToExporterWithItsOwnWorkspace() = runBlocking {
        val dirs = SandboxDirs(File(tmp, "s2/files/sandbox"), File(tmp, "s2/cache"), File(tmp, "s2/lib"), JVM_GATE, SandboxTree())
        val calls = mutableListOf<Pair<String, String>>()
        var fail: Exception? = null
        val sb = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() }, exporter = { g, n ->
            // The exporter is handed the handle that passed the gate: content is read from it
            calls += String(g.read(0, 100)) to n; fail?.let { throw it }; com.wangzan101.mika.chat.SharedFile(n, g.size, "Download/Claire/$n", "content://x")
        })
        val f = sb.hostPath("c1", "out/a.txt", forWrite = true)!!.apply { parentFile?.mkdirs(); writeText("a") }
        assertEquals("Download/Claire/a.txt", sb.share(f, "a.txt"))
        assertEquals("a" to "a.txt", calls.single())
        // Hard links inside the workspace (possibly linked to Claire's private files) are not exported
        val private = File(tmp, "s2/private.db").apply { writeText("secret") }
        val linked = File(f.parentFile, "linked.db")
        Files.createLink(linked.toPath(), private.toPath())
        assertEquals("linked_file", runCatching { sb.share(linked, "linked.db") }.exceptionOrNull().let { (it as SandboxError).code })
        assertEquals(1, calls.size)
        fail = FileTooLarge(1L shl 30)
        assertEquals("file_too_large", runCatching { sb.share(f, "a.txt") }.exceptionOrNull().let { (it as SandboxError).code })
        val outside = File(tmp, "elsewhere.txt").apply { writeText("x") }
        assertEquals("path_outside_workspace", runCatching { sb.share(outside, "x.txt") }.exceptionOrNull().let { (it as SandboxError).code })
        val none = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() })
        assertEquals("share_unavailable", runCatching { none.share(f, "a.txt") }.exceptionOrNull().let { (it as SandboxError).code })
    }

    // Web uploads follow the same path rules as the sandbox: only regular files inside this conversation's workspace are accepted
    @Test fun uploadCheckUsesWorkspaceRules() {
        val ok = sb.hostPath("c1", "in/a.pdf", forWrite = true)!!.apply { parentFile?.mkdirs(); writeText("a") }
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.Ok, sb.uploadCheck("c1", "/workspace/in/a.pdf"))
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.NotFound, sb.uploadCheck("c1", "/workspace/in/none.pdf"))
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.NotFound, sb.uploadCheck("c1", "/workspace/in"))
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.Outside, sb.uploadCheck("c1", "/opt/mika/skills/skills/x/SKILL.md"))
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.Outside, sb.uploadCheck("c1", "/workspace/../etc/passwd"))
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.NotFound, sb.uploadCheck("c2", "/workspace/in/a.pdf")) // invisible to other conversations
        // Links pointing outside the workspace are not followed
        val outside = File(tmp, "secret.txt").apply { writeText("s") }
        Files.createSymbolicLink(File(ok.parentFile, "link.txt").toPath(), outside.toPath())
        assertTrue(sb.uploadCheck("c1", "/workspace/in/link.txt") != com.wangzan101.mika.core.workspace.WorkspacePathCheck.Ok)
    }

    @Test fun projectWorkspaceWireMapsToPDirAndSharesMetrics() {
        val dirs = SandboxDirs(File(tmp, "proj/files/sandbox"), File(tmp, "proj/cache"), File(tmp, "proj/lib"), JVM_GATE, SandboxTree())
        val sb = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() })
        val projectKey = WorkspaceKey.Project("proj-alpha")
        val ws = dirs.workspace(projectKey)
        assertEquals("p-proj-alpha", ws.name)

        val file = sb.hostPath(projectKey.wire, "notes/plan.md", forWrite = true)!!
            .apply { parentFile?.mkdirs(); writeText("hello") }
        assertTrue(file.canonicalPath.startsWith(ws.canonicalPath))
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.Ok, sb.uploadCheck(projectKey, "/workspace/notes/plan.md"))
        assertEquals(5L, sb.workspaceBytes(projectKey))
        val outPath = sb.saveOutput(projectKey.wire, "saved")
        assertTrue(File(ws, outPath.removePrefix("/workspace/")).isFile)
    }
}
