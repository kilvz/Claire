package com.wangzan101.mika.core.prompt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's regex engine is stricter than the Java one on desktop: an unescaped `{` or `}` (not a quantifier such as {n} or {n,m})
 * compiles on desktop but throws as soon as it is loaded on the phone. This scans every hard-coded regex literal in the sources.
 */
class RegexPortabilityTest {
    private val roots = listOf(File("src/main/kotlin"), File("../app/src/main/java"))
    // Only the literal right after the call parenthesis is inspected; triple-quoted strings are taken verbatim, ordinary strings are unescaped the way Kotlin does
    private val call = Regex("""(?:Regex|Pattern\.compile)\(\s*(\"\"\"[\s\S]*?\"\"\"|"(?:[^"\\]|\\.)*")""")
    private val toRegex = Regex("""("(?:[^"\\]|\\.)*")\.toRegex\(""")

    @Test
    fun literalPatternsHaveNoBareBraces() {
        val offenders = roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val t = line.trim()
                    if (t.startsWith("//") || t.startsWith("*")) return@mapIndexedNotNull null
                    (call.findAll(line) + toRegex.findAll(line)).map { pattern(it.groupValues[1]) }
                        .firstOrNull { hasBareBrace(it) }
                        ?.let { "${f.path}:${i + 1}: $it" }
                }.toList()
            }
        }
        assertTrue("正则里有没转义的花括号,安卓上会编译失败:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun detectorCatchesTheClass() {
        assertTrue(hasBareBrace("""\{\{([a-z0-9_]+)}}"""))
        assertTrue(hasBareBrace("""\{\d+}"""))
        assertTrue(!hasBareBrace("""\{\{([a-z0-9_]+)\}\}"""))
        assertTrue(!hasBareBrace("""\p{L}{1,8}\s{2}[{}]"""))
    }

    private fun pattern(lit: String) =
        if (lit.startsWith("\"\"\"")) lit.removeSurrounding("\"\"\"")
        else lit.removeSurrounding("\"").replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\u0000", "\\")

    /** After removing escaped braces, properties like \p{…}, […] character classes and {n}/{n,}/{n,m} quantifiers, any brace left over counts. */
    private fun hasBareBrace(pattern: String): Boolean {
        var p = pattern.replace(Regex("""\\[pP]\{[A-Za-z_=]+\}"""), "")
        p = p.replace(Regex("""\\[{}\\]"""), "")
        p = p.replace(Regex("""\[(?:\\.|[^\]])*]"""), "")
        p = p.replace(Regex("""\{\d+(,\d*)?}"""), "")
        return '{' in p || '}' in p
    }
}
