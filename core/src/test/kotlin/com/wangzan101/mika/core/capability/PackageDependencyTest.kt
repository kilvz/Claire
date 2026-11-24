package com.wangzan101.mika.core.capability

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dependency direction: capability → core; core references no capability, and capabilities do not reference each other; only the assembly layer knows several capabilities at once.
 * The tool engine (core/tool) does not reference UI state (core/screen/uistate): UI state belongs to the capabilities that have a UI, and the engine only uses it through SurfaceLayer.
 * Under cap/ in the app, the capability directories likewise do not reference each other (except for the core package of the corresponding capability). Checked by the imports and fully qualified names in the sources; whole-line comments do not count.
 */
class PackageDependencyTest {
    private val core = File("src/main/kotlin/com/wangzan101/mika/core")
    private val app = File("../app/src/main/java/com/wangzan101/mika")
    private val ref = Regex("""com\.wangzan101\.mika\.(core\.)?(cap\.([a-z0-9_]+)|assembly|screen\.uistate)\b""")

    private class Use(val file: File, val line: Int, val text: String, val target: String)

    /** The capability packages and assembly layer referenced by sources under [root] ([Use.target] is something like cap.<capability> or core.assembly). */
    private fun uses(root: File): List<Use> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
        f.readLines().asSequence().mapIndexedNotNull { i, line ->
            val t = line.trim()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("package ")) null else Triple(i, line, t)
        }.flatMap { (i, line, _) ->
            ref.findAll(line).map { m -> Use(f, i + 1, line.trim(), (m.groupValues[1] + m.groupValues[2]).removeSuffix(".")) }
        }.toList()
    }.toList()

    /** The capability directory name that contains [f]; null if it is not under cap/. */
    private fun capOf(root: File, f: File): String? {
        val rel = f.relativeTo(root).invariantSeparatorsPath
        return if (rel.startsWith("cap/")) rel.removePrefix("cap/").substringBefore('/') else null
    }

    private fun report(bad: List<Use>, root: File) = bad.joinToString("\n") { "${it.file.relativeTo(root)}:${it.line}: ${it.text}" }

    @Test fun sourcesAreFound() {
        assertTrue("找不到源码目录(工作目录应为 core/)", core.isDirectory && File(core, "cap").isDirectory)
    }

    @Test fun coreDoesNotKnowCapabilities() {
        val bad = uses(core).filter { u ->
            val rel = u.file.relativeTo(core).invariantSeparatorsPath
            !rel.startsWith("cap/") && !rel.startsWith("assembly/") && (u.target.startsWith("core.cap.") || u.target == "core.assembly")
        }
        assertTrue("核心引用了能力或装配层:\n" + report(bad, core), bad.isEmpty())
    }

    @Test fun toolEngineDoesNotKnowInterfaceState() {
        val bad = uses(core).filter { u -> u.file.relativeTo(core).invariantSeparatorsPath.startsWith("tool/") && u.target == "core.screen.uistate" }
        assertTrue("工具引擎引用了界面状态:\n" + report(bad, core), bad.isEmpty())
    }

    @Test fun capabilitiesDoNotKnowEachOther() {
        val bad = uses(core).filter { u ->
            val own = capOf(core, u.file) ?: return@filter false
            u.target == "core.assembly" || (u.target.startsWith("core.cap.") && u.target != "core.cap.$own")
        }
        assertTrue("能力之间互相引用,或引用了装配层:\n" + report(bad, core), bad.isEmpty())
    }

    @Test fun appCapabilitiesDoNotKnowEachOther() {
        if (!File(app, "cap").isDirectory) return
        val bad = uses(app).filter { u ->
            val own = capOf(app, u.file) ?: return@filter false
            val other = u.target.removePrefix("core.").removePrefix("cap.")
            u.target.contains("cap.") && other != own
        }
        assertTrue("App 里的能力目录互相引用:\n" + report(bad, app), bad.isEmpty())
    }
}
