package com.wangzan101.mika.cap.sandbox

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillsSyncTest {
    @get:Rule val texts = ChineseTexts()

    private val tmp: File = Files.createTempDirectory("skills-sync").toFile()
    private val dirs = SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache"), File(tmp, "lib"), JVM_GATE, SandboxTree())

    private val v1 = mapOf(
        "skills/report/scripts/manifest.json" to """[{"file":"scripts/tabulate.py","runnable":true}]""".toByteArray(),
        "skills/report/scripts/tabulate.py" to "print(1)".toByteArray(),
    )

    @Test fun copiesOnceAndReplacesWhenContentChanges() = runBlocking {
        SkillsSync.sync(dirs, v1)
        val py = File(dirs.skills, "skills/report/scripts/tabulate.py")
        assertEquals("print(1)", py.readText())
        // The bundled scripts changed: swap in the new set as a whole, nothing old is left behind
        val v2 = mapOf("skills/report/scripts/manifest.json" to "[]".toByteArray(), "skills/other/scripts/x.sh" to "echo".toByteArray())
        SkillsSync.sync(dirs, v2)
        assertFalse(py.exists())
        assertTrue(File(dirs.skills, "skills/other/scripts/x.sh").isFile)
    }

    @Test fun tamperedFilesAreResynced() = runBlocking {
        SkillsSync.sync(dirs, v1)
        val py = File(dirs.skills, "skills/report/scripts/tabulate.py")
        // A command in the sandbox modified the script: the next sync restores the original
        py.writeText("import os; os.system('evil')")
        SkillsSync.sync(dirs, v1)
        assertEquals("print(1)", py.readText())
        // Extra files are cleaned up as well
        val extra = File(dirs.skills, "skills/report/scripts/extra.py").apply { writeText("x") }
        SkillsSync.sync(dirs, v1)
        assertFalse(extra.exists())
        // A file replaced by a link also counts as inconsistent
        py.delete()
        java.nio.file.Files.createSymbolicLink(py.toPath(), File(tmp, "elsewhere").toPath())
        SkillsSync.sync(dirs, v1)
        assertTrue(java.nio.file.Files.isRegularFile(py.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("print(1)", py.readText())
    }

    @Test fun pathsOutsideSkillsAreSkipped() = runBlocking {
        SkillsSync.sync(dirs, mapOf("../../escape.txt" to "x".toByteArray(), "skills/a/scripts/manifest.json" to "[]".toByteArray()))
        assertFalse(File(dirs.root, "escape.txt").exists())
        assertFalse(File(tmp, "files/escape.txt").exists())
        assertTrue(File(dirs.skills, "skills/a/scripts/manifest.json").isFile)
    }

    @Test fun manifestAndScriptsCheckedAgainstBundledCopy() = runBlocking {
        val synced = mutableListOf<Int>()
        val sb = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() },
            sync = { synced += 1; SkillsSync.sync(dirs, v1) }, skillFiles = { v1 })
        sb.prepare()
        assertEquals(listOf("scripts/tabulate.py"), sb.scriptManifest("skills/report").map { it.file })
        assertTrue(sb.scriptManifest("skills/none").isEmpty())
        assertTrue(sb.scriptManifest("../../../etc").isEmpty())
        // The manifest was modified inside the sandbox: no effect, the bundled copy is what gets read
        File(dirs.skills, "skills/report/scripts/manifest.json").writeText("""[{"file":"scripts/evil.py","runnable":true}]""")
        assertEquals(listOf("scripts/tabulate.py"), sb.scriptManifest("skills/report").map { it.file })
        // A tampered script: after re-syncing it matches the bundled copy and stays runnable
        File(dirs.skills, "skills/report/scripts/tabulate.py").writeText("evil")
        assertTrue(sb.verifyScript("skills/report", "scripts/tabulate.py"))
        assertEquals("print(1)", File(dirs.skills, "skills/report/scripts/tabulate.py").readText())
        // If it cannot be restored (sync does not take effect) it is rejected; scripts absent from the bundle are rejected too
        val stuck = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() }, skillFiles = { v1 })
        File(dirs.skills, "skills/report/scripts/tabulate.py").writeText("evil")
        assertFalse(stuck.verifyScript("skills/report", "scripts/tabulate.py"))
        assertFalse(sb.verifyScript("skills/report", "scripts/other.py"))
    }

    @Test fun cardLineForSandboxSteps() {
        assertEquals("正在处理文件:python3 sum.py", com.wangzan101.mika.settings.ui.StatusCopy.sandboxCommand("python3   sum.py", network = false))
        assertEquals("正在处理文件:${"x".repeat(40)}… · 可联网", com.wangzan101.mika.settings.ui.StatusCopy.sandboxCommand("x".repeat(50), network = true))
        assertEquals("正在运行脚本", com.wangzan101.mika.settings.ui.StatusCopy.sandboxScript(network = false))
        assertEquals("第 3/10 页", com.wangzan101.mika.settings.ui.StatusCopy.sandboxProgress("第 3/10 页", network = false))
        // Zero-width characters and direction-override characters in the command never reach the card
        assertEquals("正在处理文件:rm a.txt", com.wangzan101.mika.settings.ui.StatusCopy.sandboxCommand("rm \u202Ea\u200B.txt", network = false))
    }
}
