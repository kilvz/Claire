package com.wangzan101.mika.cap.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SANDBOX_PACKAGES
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** Real install (unpacking the bundled runtime, no network); once installed, run Python, Node and `mika` inside the sandbox. */
@RunWith(AndroidJUnit4::class)
class InstallDeviceTest {
    private val sb = AndroidSandbox(context, SandboxState.get(context))

    private fun sh(command: String): CommandResult =
        runBlocking { sb.run("install-test", listOf("/bin/sh", "-lc", command), null, 60_000) {} }

    @Test
    fun fullInstallThenPythonAndNode() {
        assertEquals(SandboxStatus.Ready(network = false), SandboxState.get(context).status.value)
        sh("python3 -c 'print(1+1)'").let { assertEquals(it.output, 0, it.exitCode); assertEquals("2", it.output.trim()) }
        sh("node -e 'console.log(2+2)'").let { assertEquals(it.output, 0, it.exitCode); assertEquals("4", it.output.trim()) }
        sh("mika info").let { r ->
            assertEquals(r.output, 0, r.exitCode)
            Json.parseToJsonElement(r.output.trim()).jsonObject // is a JSON object
        }
    }

    /** The bundled Alpine is what gets installed; the packages are all in the APK's database, and the database matches the files (apk-tools 3 cannot write to the database under PRoot, hence apk-tools 2 from 3.22). */
    @Test
    fun pinnedReleaseAndPackagesRecorded() {
        sh("cat /etc/alpine-release").let { assertEquals(it.output, BundledRuntime.VERSION.substringBefore('-'), it.output.trim()) }
        sh("apk --version").let { assertEquals(it.output, 0, it.exitCode); assertTrue(it.output, it.output.startsWith("apk-tools 2.")) }
        sh("apk info -e ${SANDBOX_PACKAGES.joinToString(" ")} 2>/dev/null").let { r ->
            assertEquals(r.output, 0, r.exitCode)
            assertEquals(r.output, SANDBOX_PACKAGES.toSet(), r.output.lines().filter(String::isNotBlank).toSet())
        }
        // With networking off, apk complains on stderr that it cannot read the repository cache, so only stdout is inspected
        // The unpacker does not carry extended attributes (by design), so entries that differ only in extended attributes (code x) do not count; differences in content, permissions or ownership still do
        sh("apk audit --system 2>/dev/null").let { r ->
            assertEquals(r.output, 0, r.exitCode)
            val drift = r.output.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("x ") }
            assertEquals(r.output, emptyList<String>(), drift)
        }
    }

    @Test
    fun networkOffAfterInstall() {
        val r = sh("python3 -c 'import socket; socket.create_connection((\"1.1.1.1\", 443), timeout=5)'")
        assertNotEquals(r.output, 0, r.exitCode)
        assertTrue(r.output, r.output.contains("PermissionError") || r.output.contains("Permission denied"))
        assertEquals(false, r.network)
    }

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

        @BeforeClass
        @JvmStatic
        fun install() {
            val state = SandboxState.get(context)
            state.networkAllowed = false
            val installer = RootfsInstaller.create(context)
            runBlocking {
                installer.reset()
                installer.install { _, _ -> }
            }
            assertEquals(InstallStep.Done, state.completedStep())
        }
    }
}
