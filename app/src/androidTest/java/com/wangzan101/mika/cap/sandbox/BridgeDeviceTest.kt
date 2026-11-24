package com.wangzan101.mika.cap.sandbox

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Collections
import java.util.UUID

/**
 * Real-device cases for the bridge: the `mika` command inside the sandbox calls back into Claire over an abstract socket. Uses the installed sandbox (both python3 and the mika client are placed at install time);
 * skipped when it is not installed.
 */
@RunWith(AndroidJUnit4::class)
class BridgeDeviceTest {
    private val shared = Collections.synchronizedList(mutableListOf<SharedFile>())
    private val sb = AndroidSandbox(context, SandboxState.get(context)) { shared += it }
    private val progress = Collections.synchronizedList(mutableListOf<String>())

    private fun sh(command: String): CommandResult =
        runBlocking { sb.run("bridge-ws", listOf("/bin/sh", "-lc", command), null, 30_000) { progress += it } }

    // Use the installed sandbox (python3 and the mika client were installed with it); skip if it is not installed, the test does not unpack a root filesystem itself
    @Before fun needsInstalledSandbox() {
        assumeTrue("sandbox not installed", SandboxState.get(context).status.value is SandboxStatus.Ready)
        assumeTrue("python3 not installed in the rootfs",
            Files.exists(File(SandboxDirs(context).rootfs, "usr/bin/python3").toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    // Delete the test files exported to "Downloads/Claire" so they are not left behind in the user's Downloads directory
    @After fun deleteExported() {
        for (f in shared) runCatching { context.contentResolver.delete(Uri.parse(f.uri), null, null) }
        SandboxDirs(context).workspace(WorkspaceKey.Conversation("bridge-ws")).deleteRecursively()
    }

    @Test fun progressReachesCallback() {
        val r = sh("mika progress 第1步")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals(listOf("第1步"), progress.toList())
    }

    @Test fun shareCallsExporter() {
        // The name carries a random string so it cannot collide in the Downloads directory; files outside the workspace are rejected by the path rules first
        val name = "mika-bridge-test-${UUID.randomUUID().toString().take(8)}.txt"
        val r = sh("echo hi > $name; mika share $name; echo rc=$?; mika share /etc/passwd; echo rc=$?")
        assertTrue(r.output, r.output.contains("\"savedAs\":\"Download/Claire/$name\""))
        assertTrue(r.output, r.output.contains("rc=0"))
        assertTrue(r.output, r.output.contains("path_outside_workspace"))
        assertEquals(r.output, 1, Regex("rc=1").findAll(r.output).count())
        assertEquals(listOf("Download/Claire/$name"), shared.map { it.savedAs })
    }

    @Test fun bridgeWorksWithNetOff() {
        val r = sh("mika info")
        assertEquals(r.output, 0, r.exitCode)
        assertTrue(r.output, r.output.contains("\"network\":false"))
        assertTrue(r.output, r.output.contains("\"workspace\":\"/workspace\""))
    }

    @Test fun sessionClosedAfterCommand() {
        val name = sh("echo \$MIKA_BRIDGE").output.trim()
        assertTrue(name, name.startsWith("mika-bridge-"))
        val refused = try {
            LocalSocket().use { it.connect(LocalSocketAddress(name)) }
            false
        } catch (e: IOException) {
            true
        }
        assertTrue("old bridge name still accepts connections", refused)
    }

    @Test fun wrongTokenIsRejected() {
        val r = sh("MIKA_TOKEN=forged mika info")
        assertEquals(r.output, 1, r.exitCode)
        assertTrue(r.output, r.output.contains("bad_token"))
    }

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    }
}
