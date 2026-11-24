package com.wangzan101.mika.core.cap.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import java.util.concurrent.TimeUnit

class SelectorPolicyTest {
    companion object {
        /**
         * All of these select elements by the value attribute (in a browser, against two inputs holding different values, they match different ones).
         * ReadScriptEnforcementTest runs the same batch through the JS version inside the page; the two sides must agree.
         */
        val BY_VALUE = listOf(
            """input[value^="h"]""",
            """input[ VALUE ]""",
            """input[v\61 lue^="h"]""",
            """input[\76 alue]""",
            """input[v\000061lue^="h"]""",
            """input[\v\61 l\u\65 ^="h"]""",
            """input[*|value^="h"]""",
            """input[|value]""",
            """input[/* x */value^="h"]""",
            """input[value/* x */^="h"]""",
            """input[value^='h' i]""",
            """form:has(input[value^="h"])""",
            """input:is([value^="h"])""",
            // Comment markers inside quotes do not count: stripping comments purely literally would swallow the [value part in the middle
            """input:not([data-x="/*"])[value^="h"]:not([data-y="*/"])""",
            // Inside quotes, a newline following an escape belongs to the escape, and a backslash plus newline continuing the line does not end the string either
            "input:not([data-x=\"\\41\n\"])[value^=\"h\"]",
            "input:not([data-x=\"\\41\r\n\"])[value^=\"h\"]",
            "input:not([data-x=\"a\\\nb\"])[value^=\"h\"]",
            // Unterminated quotes and square brackets are closed at end of input by the browser
            """input[value^="h""",
            // An unquoted url( runs to ) as a single token, so comment markers and quotes inside it do not count; :is() drops that item and the rest still applies
            """input:is(url(/*),[value^="h"])""",
            """input:is(url(x"),[value^="h"])""",
            """input:where(u\72 l(/*),[value^="h"])""",
            """input:is(URL( /*),[value^="h"])""",
        )

        /** Selectors that do not select by the value attribute pass as usual. */
        val NOT_BY_VALUE = listOf(
            "li.item",
            """input[name="q"]""",
            "input[data-value]",
            "input[value-x]",
            "input[valuex]",
            ".value",
            "#value",
            """input[type="value"]""",
            """[title="[value"]""",
            """[title='[value^=h]']""",
            """[data-x|="value"]""",
            "input:not([type=hidden])",
            """[d\61 ta-id]""",
            """[title="/*"], [data-x="*/"]""",
            """a[data-url="x"]""",
            "a.url",
            // url( inside quotes does not count: url( must be looked for after unescaping
            """[style*="url("]""",
        )
    }

    @Test fun valueSelectorsAreCaught() {
        for (s in BY_VALUE) assertTrue(s, SelectorPolicy.selectsByValue(s))
    }

    @Test fun otherSelectorsPass() {
        for (s in NOT_BY_VALUE) assertFalse(s, SelectorPolicy.selectsByValue(s))
    }

    @Test fun readsLikeCss() {
        assertEquals("[value]", SelectorPolicy.plain("""[v\61 lue]"""))
        assertEquals("[value]", SelectorPolicy.plain("""[v\000061lue]"""))
        assertEquals("[value]", SelectorPolicy.plain("""[\v\61 l\u\65 ]"""))
        assertEquals("a b", SelectorPolicy.plain("a/* [value] */b"))
        assertEquals("a ", SelectorPolicy.plain("a/* not closed"))
        assertEquals("""[title=""][x=""]""", SelectorPolicy.plain("""[title="[value\"/*"][x='\27']"""))
        // Newlines are always treated as \n; an unescaped newline ends the quoted run right there
        assertEquals("[a=\"\"\n[value]", SelectorPolicy.plain("[a=\"x\r\n[value]"))
        // 0, surrogate and out-of-range code points become the replacement character; so does a lone trailing backslash
        assertEquals("\uFFFD\uFFFD\uFFFD\uFFFD", SelectorPolicy.plain("""\0 \D800 \110000 \"""))
    }

    /** The JS version inside the page decides the same as the Kotlin version: the two groups above, plus a batch with error-prone fragments randomly spliced in around attribute names. Skipped when Node is not on the machine. */
    @Test fun jsVersionAgrees() {
        val node = ReadScriptEnforcementTest.node()
        assumeTrue("no node on this machine", node != null)
        val names = listOf("value", "VaLuE", "v\\61 lue", "\\76 alue", "v\\000061lue", "*|value", "|value", " value", "valu", "valuex", "value-x", "data-value", "val/**/ue", "va\\\nlue")
        val parts = listOf(
            "[", "]", "v", "e", "\\61 ", "\\76", "\\", "\"", "'", "/*", "*/", "*|", "|", " ", "\t", "\n", "\r", "\r\n", "\u000C",
            "x", "=\"h\"", "^", "(", ")", ":is(", "\\\n", "\\41\n", "-", "0", "\u00E9", "url(", "URL(", "u\\72 l(",
        )
        val rnd = Random(7)
        fun noise(n: Int) = buildString { repeat(rnd.nextInt(n)) { append(parts[rnd.nextInt(parts.size)]) } }
        val random = List(3000) { noise(4) + (if (rnd.nextBoolean()) "[" else "") + noise(2) + names[rnd.nextInt(names.size)] + noise(4) }
        val all = BY_VALUE + NOT_BY_VALUE + random
        val dir = kotlin.io.path.createTempDirectory("selector-policy").toFile()
        val input = File(dir, "all.json").apply { writeText(JsonArray(all.map(::JsonPrimitive)).toString()) }
        val script = File(dir, "check.js").apply {
            writeText("'use strict';\n" + SelectorPolicy.JS + "\nconst all = JSON.parse(require('fs').readFileSync(process.argv[2], 'utf8'));\nprocess.stdout.write(JSON.stringify(all.map(selectsByValue)));\n")
        }
        val p = ProcessBuilder(node, script.path, input.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("node timed out", p.waitFor(60, TimeUnit.SECONDS))
        dir.deleteRecursively()
        val js = runCatching { Json.parseToJsonElement(out).jsonArray.map { it.jsonPrimitive.boolean } }.getOrElse { error("node output: $out") }
        assertEquals(all.size, js.size)
        for ((i, s) in all.withIndex()) assertEquals(s, SelectorPolicy.selectsByValue(s), js[i])
        // The randomly assembled cases must yield plenty of both outcomes, otherwise only one side is being compared
        val hits = random.count { SelectorPolicy.selectsByValue(it) }
        assertTrue("$hits of ${random.size}", hits > 300 && random.size - hits > 300)
    }
}
