package com.wangzan101.mika.core.action

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Boundary of the execution entry: the constructor of [com.wangzan101.mika.core.tool.ActionPass] is internal, so the App module can't create one;
 * inside core, this test guards that only core.action creates it and only core.action calls the irreversible check and the consent verification.
 * Package dependencies may point only one way: core.action and core.guard don't depend on core.agent, which schedules them.
 */
class SingleEntryTest {
    private val action = File("src/main/kotlin/com/wangzan101/mika/core/action").canonicalPath + File.separator
    private val roots = listOf(File("src/main/kotlin"), File("../app/src/main/java"))

    /** Code lines in the source (whole-line comments don't count; inline strings and URLs count as usual). */
    private fun codeLines(): List<Triple<File, Int, String>> = roots.filter { it.isDirectory }.flatMap { root ->
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) null else Triple(f, i + 1, line)
            }
        }.toList()
    }

    private fun outsideAction(f: File) = !f.canonicalPath.startsWith(action)

    private fun offenders(pattern: Regex) = codeLines()
        .filter { (f, _, line) -> outsideAction(f) && pattern.containsMatchIn(line) }
        .map { (f, n, line) -> "${f.path}:$n: ${line.trim()}" }

    @Test fun sourcesAreFound() {
        assertTrue("cannot find the source directory (the working directory should be core/)", roots.first().isDirectory)
        assertTrue(codeLines().any { (f, _, line) -> !outsideAction(f) && "ActionPass()" in line })
    }

    @Test fun onlyTheExecutorHoldsThePass() {
        val bad = offenders(Regex("""\bActionPass\s*\(""")).filterNot { "class ActionPass" in it }
        assertTrue("these places forge their own execution token, bypassing the entry point:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun onlyTheExecutorDecides() {
        val bad = offenders(Regex(
            """IrreversibleDetector\s*\.\s*(needsCheck|classify|hasLabelAt|unresolved)\b|\.\s*permits\s*\(|\.\s*noteWrite\s*\(|\bIrreversibleDetector\s*[,)]"""
        ))
        assertTrue("these places decide directly, bypassing the entry point:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun actionAndGuardDoNotDependOnTheLoop() {
        val bad = listOf("action", "safety/guard").flatMap { pkg ->
            File("src/main/kotlin/com/wangzan101/mika/core/$pkg").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                f.readLines().filter { it.startsWith("import com.wangzan101.mika.core.agent.") }.map { "${f.path}: $it" }
            }.toList()
        }
        assertTrue("the entry point and guards depend on the main loop, backwards:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
