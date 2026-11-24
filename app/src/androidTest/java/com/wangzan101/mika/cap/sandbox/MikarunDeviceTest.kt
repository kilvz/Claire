package com.wangzan101.mika.cap.sandbox

import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * On-device tests for the mikarun launcher: exit code, timeout, network off, cleanup (descendants detached from the parent process, Claire being killed).
 * Shares the root filesystem with SandboxSmokeTest.
 */
@RunWith(AndroidJUnit4::class)
class MikarunDeviceTest {

    @Test
    fun exitCodePassesThrough() {
        val r = run(launcher("--timeout", "30") + proot("/bin/sh", "-c", "exit 7"))
        assertEquals(r.output, 7, r.exitCode)
    }

    @Test
    fun timeoutReturns124() {
        val r = run(launcher("--timeout", "2") + proot("/bin/sleep", "30"))
        assertEquals(r.output, 124, r.exitCode)
        assertTrue("took ${r.elapsedMs} ms", r.elapsedMs < 5_000)
    }

    @Test
    fun detachedChildrenAreKilled() {
        val marker = "1001"
        val script = "setsid sleep $marker & (sleep $marker &) ; sleep $marker"
        val r = run(launcher("--timeout", "2") + proot("/bin/sh", "-c", script))
        assertEquals(r.output, 124, r.exitCode)
        assertEquals("leftover sleep processes", emptyList<Int>(), awaitGone(marker, 1_000))
    }

    @Test
    fun parentDeathKillsWholeTree() {
        val marker = "1002"
        val inner = (launcher("--timeout", "600") + proot("/bin/sleep", marker)).joinToString(" ") { shQuote(it) }
        // The "fake parent process": first reports its own PID, then starts the launcher in the background and waits for it
        val p = start(listOf("/system/bin/sh", "-c", "echo \$\$; $inner & wait"))
        try {
            val fakeParent = p.inputStream.bufferedReader().readLine().trim().toInt()
            val deadline = System.currentTimeMillis() + 15_000
            while (findProcesses(marker).isEmpty()) {
                if (System.currentTimeMillis() > deadline) fail("sandboxed sleep never started")
                Thread.sleep(100)
            }
            Os.kill(fakeParent, OsConstants.SIGKILL)
            assertEquals("leftover sleep processes", emptyList<Int>(), awaitGone(marker, 3_000))
        } finally {
            p.destroyForcibly()
        }
    }

    @Test
    fun kill9MikarunLeavesNoSleep() {
        val marker = "1003"
        val script = "setsid sleep $marker & sleep $marker"
        val p = start(launcher("--timeout", "600") + proot("/bin/sh", "-c", script))
        try {
            val deadline = System.currentTimeMillis() + 15_000
            while (findProcesses(marker).size < 2) {
                if (System.currentTimeMillis() > deadline) fail("sandboxed sleeps never started")
                Thread.sleep(100)
            }
            val launcherPid = File("/proc").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }.single {
                val args = cmdline(it)
                args.firstOrNull()?.endsWith("libmikarun.so") == true && script in args
            }
            // The launcher has no time to clean up: rely on PRoot's PDEATHSIG and PTRACE_O_EXITKILL to take down every sandbox process
            Os.kill(launcherPid, OsConstants.SIGKILL)
            assertEquals("leftover sleep processes", emptyList<Int>(), awaitGone(marker, 3_000))
        } finally {
            p.destroyForcibly()
        }
    }

    @Test
    fun netOffBlocksTcp() {
        val r = run(launcher("--timeout", "20", "--net", "off") + proot("/bin/busybox", "nc", "-w", "2", "1.1.1.1", "80"))
        assertNotEquals(r.output, 0, r.exitCode)
        assertTrue(r.output, r.output.contains("Permission denied"))
    }

    @Test
    fun netOffAllowsUnixSocket() {
        // busybox's nc does not support AF_UNIX; use syslogd (binds on /dev/log) and logger (connects) to talk to each other instead.
        // /dev/log is bound to a file in the cache directory, which syslogd replaces with a socket.
        val devLog = File(SandboxSmokeTest.prootTmp.parentFile, "mikarun-test-devlog").apply { delete(); writeText("") }
        val script = "syslogd -n -O /tmp/mikarun-unix.log & sleep 1; logger -t mikaprobe hello-unix; sleep 1; " +
            "kill \$!; cat /tmp/mikarun-unix.log; rm -f /tmp/mikarun-unix.log"
        val r = run(
            launcher("--timeout", "20", "--net", "off") +
                proot("/bin/sh", "-c", script, extraBinds = listOf("${devLog.absolutePath}:/dev/log")),
        )
        assertTrue(r.output, r.output.contains("hello-unix"))
    }

    @Test
    fun netOnAllowsTcp() {
        // Some networks let TCP connect but never answer: first pick, outside the sandbox, an address that really gets an HTTP reply, then have the sandbox connect to that same one
        val host = HTTP_PROBES.firstOrNull(::hostAnswersHttp)
        assumeTrue("no probe host answers HTTP on this network", host != null)
        // busybox nc exits as soon as stdin ends and does not wait for the reply: keep stdin open 3 more seconds after sending the request, so slower servers still have time to answer
        val script = "(printf 'HEAD / HTTP/1.0\\r\\n\\r\\n'; sleep 3) | nc -w 3 $host 80"
        val r = run(launcher("--timeout", "20", "--net", "on") + proot("/bin/sh", "-c", script))
        assertTrue(r.output, r.output.contains("HTTP/"))
    }

    @Test
    fun oomScoreRaised() {
        val r = run(launcher("--timeout", "20") + proot("/bin/cat", "/proc/self/oom_score_adj"))
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("1000", r.output.trim())
    }

    @Test
    fun selfTestReportsSeccomp() {
        val r = run(listOf(SandboxSmokeTest.lib("libmikarun.so"), "--self-test"))
        assertEquals(r.output, 0, r.exitCode)
        assertEquals(listOf("mikarun ok", "seccomp ok", "pty ok"), r.output.trim().lines())
    }

    @Test
    fun prootWithoutLoaderIsRefused() {
        val r = run(launcher("--timeout", "20") + proot("/bin/true"), withLoader = false)
        assertEquals(r.output, 125, r.exitCode)
        assertTrue(r.output, r.output.trim().lines().last().startsWith("mikarun: PROOT_LOADER missing"))
    }

    private data class Result(val exitCode: Int, val output: String, val elapsedMs: Long)

    private fun launcher(vararg options: String): List<String> =
        listOf(SandboxSmokeTest.lib("libmikarun.so")) + options + "--"

    private fun proot(vararg command: String, extraBinds: List<String> = emptyList()): List<String> =
        listOf(
            SandboxSmokeTest.lib("libproot.so"), "--kill-on-exit", "--link2symlink", "-0",
            "-r", SandboxSmokeTest.rootfs.absolutePath,
        ) + ProotDevices.bindArgs() + listOf("-b", "/proc", "-b", "/sys") + extraBinds.flatMap { listOf("-b", it) } + listOf("-w", "/") + command

    private fun start(cmd: List<String>, withLoader: Boolean = true): Process {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().apply {
            clear()
            if (withLoader) put("PROOT_LOADER", SandboxSmokeTest.lib("libproot_loader.so"))
            put("PROOT_TMP_DIR", SandboxSmokeTest.prootTmp.absolutePath)
            put("HOME", "/root")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("LANG", "C.UTF-8")
            put("TMPDIR", "/tmp")
        }
        return pb.start()
    }

    private fun run(cmd: List<String>, withLoader: Boolean = true): Result {
        val begin = System.nanoTime()
        val p = start(cmd, withLoader)
        var out = ""
        val reader = thread { out = p.inputStream.bufferedReader().readText() }
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            fail("command timed out: $cmd")
        }
        val elapsedMs = (System.nanoTime() - begin) / 1_000_000
        reader.join()
        Log.i(TAG, "exit=${p.exitValue()} ${elapsedMs}ms cmd=$cmd output=$out")
        return Result(p.exitValue(), out, elapsedMs)
    }

    /** Processes visible under this app's identity whose command line has an argument exactly equal to marker. */
    private fun findProcesses(marker: String): List<Int> =
        File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            dir.name.toIntOrNull()?.takeIf { cmdline(it).contains(marker) }
        }

    private fun cmdline(pid: Int): List<String> =
        runCatching { String(File("/proc/$pid/cmdline").readBytes(), Charsets.UTF_8).split('\u0000') }
            .getOrDefault(emptyList())

    private fun awaitGone(marker: String, withinMs: Long): List<Int> {
        val deadline = System.currentTimeMillis() + withinMs
        while (true) {
            val left = findProcesses(marker)
            if (left.isEmpty() || System.currentTimeMillis() > deadline) return left
            Thread.sleep(100)
        }
    }

    /** Send a HEAD request directly from outside the sandbox; only an address whose reply starts with "HTTP/" counts as usable. */
    private fun hostAnswersHttp(host: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, 80), 3_000)
            socket.soTimeout = 3_000
            socket.getOutputStream().write("HEAD / HTTP/1.0\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readLine()?.startsWith("HTTP/") == true
        }
    }.getOrDefault(false)

    private fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        private const val TAG = "MikarunDeviceTest"
        /** Public HTTP endpoints reached by direct IP (no DNS dependency inside the sandbox); different networks can reach different ones, so the first that answers is picked. */
        private val HTTP_PROBES = listOf("1.1.1.1", "223.5.5.5", "1.0.0.1")

        @BeforeClass
        @JvmStatic
        fun prepare() = SandboxSmokeTest.prepareRootfs()
    }
}
