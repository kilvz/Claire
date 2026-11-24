package com.wangzan101.mika.cap.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On some Android versions the seccomp policy installed for apps sends SIGSYS for new system calls (io_uring, clone3, faccessat2, epoll_pwait2, rseq, ...), while on others they are simply allowed.
 * On both kinds of platform, programs inside the sandbox must not be killed by the signal: trapped calls receive "not supported" (ENOSYS) and take their own fallback path, allowed calls get the kernel's own result; calls that carry a path must be judged by the path inside the sandbox either way.
 * Everything goes through the production path AndroidSandbox.run → mikarun → PRoot.
 */
@RunWith(AndroidJUnit4::class)
class SandboxSyscallFallbackDeviceTest {
    private val sb = AndroidSandbox(SandboxSmokeTest.testDirs, { SandboxStatus.Ready(network = false) }, { AndroidSandbox.dns(context) }, bridges = true)

    private fun sh(command: String, timeoutMs: Long = 60_000): CommandResult =
        runBlocking { sb.run("dev-sigsys", listOf("/bin/sh", "-lc", command), null, timeoutMs) {} }

    @Test fun nodeEvaluatesJs() {
        val r = sh("node -e 'console.log(1+1)'")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("2", r.output.trim())
    }

    @Test fun nodeFsAndTimers() {
        val script = """
            const fs = require('fs');
            fs.writeFileSync('sigsys-node.txt', 'written');
            setTimeout(async () => {
              const text = await fs.promises.readFile('sigsys-node.txt', 'utf8');
              fs.unlinkSync('sigsys-node.txt');
              setImmediate(() => console.log('read:' + text));
            }, 50);
        """.trimIndent()
        val r = sh("cat > sigsys-node.js <<'EOF'\n$script\nEOF\nnode sigsys-node.js; rc=\$?; rm -f sigsys-node.js; exit \$rc")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("read:written", r.output.trim())
    }

    /** musl's faccessat(…, AT_EACCESS) tries faccessat2 first; the answer must agree with the real permissions. */
    @Test fun effectiveAccessChecks() {
        val script = """
            import os
            open('sigsys-acc.txt', 'w').close()
            os.chmod('sigsys-acc.txt', 0o644)
            print(os.access('sigsys-acc.txt', os.R_OK, effective_ids=True),
                  os.access('sigsys-acc.txt', os.X_OK, effective_ids=True),
                  os.access('/bin/sh', os.X_OK, effective_ids=True),
                  os.access('sigsys-missing', os.F_OK, effective_ids=True))
            os.remove('sigsys-acc.txt')
        """.trimIndent()
        val r = sh(
            "cat > sigsys-acc.py <<'EOF'\n$script\nEOF\npython3 sigsys-acc.py; rc=\$?; rm -f sigsys-acc.py; " +
                "touch sigsys-t; [ -r sigsys-t ] && echo test-r-ok; rm -f sigsys-t; exit \$rc",
        )
        assertEquals(r.output, 0, r.exitCode)
        assertEquals(listOf("True False True False", "test-r-ok"), r.output.trim().lines())
    }

    /**
     * The process stays alive and every call returns -1: calls trapped by the platform answer ENOSYS; calls the platform allows are reported by the kernel as errors caused by the invalid arguments used here (EINVAL).
     * io_uring_setup is answered ENOSYS by PRoot itself (io_uring path operations do not go through system calls, so PRoot cannot translate them), independent of the platform.
     */
    @Test fun trappedSyscallsReturnEnosys() {
        // io_uring_setup, clone3, epoll_pwait2, rseq (arm64 numbers)
        val script = """
            import ctypes, errno
            libc = ctypes.CDLL(None, use_errno=True)
            for nr in (425, 435, 441, 293):
                ctypes.set_errno(0)
                rc = libc.syscall(nr, 0, 0, 0, 0, 0, 0)
                print(nr, rc, errno.errorcode.get(ctypes.get_errno()))
        """.trimIndent()
        val r = sh("cat > sigsys-nr.py <<'EOF'\n$script\nEOF\npython3 sigsys-nr.py; rc=\$?; rm -f sigsys-nr.py; exit \$rc")
        assertEquals(r.output, 0, r.exitCode)
        val lines = r.output.trim().lines()
        assertEquals(r.output, 4, lines.size)
        assertEquals("425 -1 ENOSYS", lines[0])
        for ((line, nr) in lines.drop(1).zip(listOf(435, 441, 293))) {
            assertTrue(r.output, line == "$nr -1 ENOSYS" || line == "$nr -1 EINVAL")
        }
    }

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

        @BeforeClass
        @JvmStatic
        fun prepareRootfs() {
            SandboxSmokeTest.prepareTestSandbox()
        }
    }
}
