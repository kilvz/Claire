package com.wangzan101.mika.core.learn.knowledge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SiteCommandsTest {
    companion object {
        /** A site command for tests (it exists only in tests). */
        const val FIXTURE = """/* mika-command
name: list_items
description: 列出结果页上的条目
arg: limit integer - 最多几条
arg: query string required - 只要标题里有这段字的
returns: [{title, url}]
*/
const rows = [...document.querySelectorAll('li a')].map(a => ({ title: a.textContent.trim(), url: a.href }));
return rows.filter(r => r.title.includes(args.query)).slice(0, args.limit || 20);
"""
        val files = arrayOf(siteMd("shop.test", domains = listOf("shop.test")), "knowledge/sites/shop.test/scripts/list_items.js" to FIXTURE)
    }

    private fun errors(vararg files: Pair<String, String>): List<String> {
        val root = Files.createTempDirectory("kn").toFile().apply { deleteOnExit() }
        files.forEach { (p, t) -> File(root, p).apply { parentFile.mkdirs() }.writeText(t) }
        return KnowledgeIndex.build(root).errors
    }

    private fun assertError(part: String, vararg files: Pair<String, String>) {
        val e = errors(*files)
        assertTrue("expected an error with '$part', got $e", e.any { part in it })
    }

    @Test fun headerParses() {
        val p = SiteCommandFile.parse("scripts/list_items.js", FIXTURE)
        assertEquals(emptyList<String>(), p.errors)
        val d = p.decl!!
        assertEquals("list_items", d.name)
        assertEquals(listOf(CommandArg("limit", "integer", false, "最多几条"), CommandArg("query", "string", true, "只要标题里有这段字的")), d.args)
        assertEquals("[{title, url}]", d.returns)
        assertTrue(p.body.startsWith("const rows"))
        assertEquals("- list_items(limit?: integer, query: string): 列出结果页上的条目. Returns [{title, url}]. Arguments: limit 最多几条; query 只要标题里有这段字的", SiteCommandFile.line(d))
    }

    @Test fun badHeadersAreRejected() {
        fun errs(text: String, file: String = "scripts/list_items.js") = SiteCommandFile.parse(file, text).errors
        assertTrue(errs(FIXTURE, "scripts/other.js").any { "must match the file name" in it })
        assertTrue(errs(FIXTURE.replace("returns: [{title, url}]\n", "")).any { "missing returns" in it })
        assertTrue(errs(FIXTURE.replace("arg: limit integer", "arg: limit int")).any { "each arg is written" in it })
        assertTrue(errs(FIXTURE.replace("name: list_items", "name: list_items\ncolor: red")).any { "unknown header key color" in it })
        assertTrue(errs(FIXTURE.replace("limit integer", "query integer")).any { "duplicate" in it })
        assertTrue(errs("const x = 1;").any { "header" in it })
        assertTrue(errs(FIXTURE.substringBefore("*/") + "*/\n").any { "script body is empty" in it })
        for (bad in listOf("\nawait import('x');", "\nawait import/**/('x');", "\nawait import\n('x');"))
            assertTrue(bad, errs(FIXTURE + bad).any { "may not use import" in it })
        assertTrue(errs(FIXTURE + "\n// import('x') 只是注释\nconst s = 'import';").isEmpty())
    }

    @Test fun argsAreCheckedAgainstTheDeclaration() {
        val d = SiteCommandFile.parse("scripts/list_items.js", FIXTURE).decl!!
        fun check(s: String) = SiteCommandFile.checkArgs(d, Json.parseToJsonElement(s).jsonObject)
        assertNull(check("""{"query":"shoe"}"""))
        assertNull(check("""{"query":"shoe","limit":5}"""))
        assertTrue(check("{}")!!.contains("needs 'query'"))
        assertTrue(check("""{"query":"shoe","limit":"5"}""")!!.contains("integer"))
        assertTrue(check("""{"query":"shoe","limit":1.5}""")!!.contains("integer"))
        assertTrue(check("""{"query":"shoe","color":"red"}""")!!.contains("not an argument"))
        assertTrue(check("""{"query":3}""")!!.contains("string"))
    }

    @Test fun indexLoadsCommandsAndCatalogOffersThemByHost() {
        val catalog = testCatalog(*files)
        assertTrue(catalog.hasSiteCommands())
        val cmds = catalog.siteCommands("www.shop.test")
        assertEquals(listOf("list_items"), cmds.map { it.decl.name })
        assertEquals("shop.test", cmds.single().site)
        assertTrue(cmds.single().body.startsWith("const rows"))
        assertEquals(cmds, catalog.siteCommands("m.shop.test"))
        assertEquals(emptyList<SiteCommand>(), catalog.siteCommands("other.test"))
        assertFalse(testCatalog(siteMd("plain.test")).hasSiteCommands())
        val entry = KnowledgeStore.loadBundled(fixtureLoader(mapOf(*files))).single()
        assertEquals(listOf("list_items"), entry.commands.map { it.name })
    }

    @Test fun mostSpecificSiteWinsForCommandsAndKnowledgeAlike() {
        val parent = siteMd("example.test")
        val child = siteMd("shop.example.test")
        val script = "knowledge/sites/example.test/scripts/list_items.js" to FIXTURE
        val files = arrayOf(parent, child, script)
        val catalog = testCatalog(*files)
        // The sub-site has its own knowledge: commands are resolved by it, and the parent site's commands are not borrowed
        assertEquals(emptyList<SiteCommand>(), catalog.siteCommands("shop.example.test"))
        assertEquals(listOf("list_items"), catalog.siteCommands("www.example.test").map { it.decl.name })
        val store = KnowledgeStore(KnowledgeStore.loadBundled(fixtureLoader(mapOf(*files))))
        assertEquals("shop.example.test", store.byDomain("a.shop.example.test")!!.name)
        assertEquals("example.test", store.byDomain("other.example.test")!!.name)
        assertEquals(12, SiteMatch.score("www.example.test", listOf("test", "example.test")))
        assertNull(SiteMatch.score("example.org", listOf("example.test")))
    }

    @Test fun scriptsFolderRules() {
        assertError("has no site command header", siteMd("shop.test"), "knowledge/sites/shop.test/scripts/helper.js" to "module.exports = 1")
        assertError("is listed in", siteMd("shop.test"), "knowledge/sites/shop.test/scripts/list_items.js" to FIXTURE,
            "knowledge/sites/shop.test/scripts/manifest.json" to """[{"file":"scripts/list_items.js","runnable":true}]""")
        assertError("may only be written in site knowledge", skillMd("buy"), "skills/buy/scripts/list_items.js" to FIXTURE)
        assertError("scripts/list_items.js: missing description", siteMd("shop.test"), "knowledge/sites/shop.test/scripts/list_items.js" to FIXTURE.replace("description: 列出结果页上的条目\n", ""))
        // Sandbox scripts are as before: a .js listed in a manifest is not treated as a site command
        assertEquals(emptyList<String>(), errors(siteMd("shop.test"), "knowledge/sites/shop.test/scripts/tool.js" to "console.log(1)",
            "knowledge/sites/shop.test/scripts/manifest.json" to """[{"file":"scripts/tool.js","runnable":true}]"""))
    }
}
