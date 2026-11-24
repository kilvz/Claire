package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.cap.sandbox.SandboxPathContract
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** AndroidSandbox path rules run on the JVM: directories are swapped for temporary ones and no process is started. */
class AndroidSandboxContractTest : SandboxPathContract() {
    private val tmp: File = Files.createTempDirectory("android-sandbox").toFile()
    private fun dirs() = SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache/proot-tmp"), File(tmp, "lib"), JVM_GATE, SandboxTree())
    override fun sandbox(): SandboxPort = AndroidSandbox(dirs(), { SandboxStatus.Ready(false) }, { emptyList() })
    override fun outsideDir(): File = File(tmp, "outside").apply { mkdirs() }

    // Reading files, searching files and checking upload paths do not hold the write-tree permit: if they create workspace directories as a side effect, the final rmdir halfway through a reset deletion fails with ENOTEMPTY
    @Test fun lookingUpPathsNeverCreatesTheWorkspace() {
        val sb = AndroidSandbox(dirs(), { SandboxStatus.Ready(false) }, { emptyList() })
        assertNotNull(sb.hostRoot("fresh", "/workspace/a.txt", forWrite = false))
        sb.uploadCheck("fresh", "/workspace/a.txt")
        assertFalse(dirs().workspace(WorkspaceKey.Conversation("fresh")).exists())
        assertFalse(dirs().work.exists())
    }
}
