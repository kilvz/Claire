package com.wangzan101.mika.core.learn.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class KnowledgeIndexTest {
    private fun errors(vararg files: Pair<String, String>): List<String> {
        val root = Files.createTempDirectory("kn").toFile().apply { deleteOnExit() }
        files.forEach { (p, t) -> File(root, p).apply { parentFile.mkdirs() }.writeText(t) }
        return KnowledgeIndex.build(root).errors
    }

    private fun assertError(part: String, vararg files: Pair<String, String>) {
        val e = errors(*files)
        assertTrue("expected an error with '$part', got $e", e.any { part in it })
    }

    @Test fun validTreeIndexesAllKinds() {
        val loader = fixtureLoader(mapOf(
            appMd("shop", listOf("com.shop"), sites = listOf("shop.com"), extra = "package_aliases: [\"小店 | com.shop\"]\n"),
            siteMd("shop.com", apps = listOf("shop"), body = "看 `references/a.md`", domains = listOf("shop.com", "www.shop.cn")),
            "knowledge/sites/shop.com/references/a.md" to "附件",
            skillMd("buy", apps = listOf("shop"), sites = listOf("shop.com"), extra = "terms: [购物]\n"),
        ))
        val rows = GuideCatalog.loadBundled(loader).rows
        assertEquals(listOf("app:shop", "site:shop.com", "skill:buy"), rows.map { "${it.kind}:${it.name}" })
        assertEquals(mapOf("小店" to "com.shop"), rows[0].packageAliases)
        assertEquals(listOf("购物"), rows[2].terms)
        assertEquals(listOf("shop.com", "shop.cn"), rows[1].domains)
        assertEquals(listOf("references/a.md"), rows[1].files)
    }

    @Test fun requiredFieldsAndLimits() {
        assertError("missing header", "knowledge/apps/a/APP.md" to "没有头部")
        assertError("must match the directory name", appMd("a", listOf("p.a")).let { (p, t) -> p.replace("/a/", "/b/") to t })
        assertError("missing packages", appMd("a", emptyList()))
        assertError("unknown header key assistent", appMd("a", listOf("p.a"), extra = "assistent: 小助手\n"))
        // The metadata keys carried over from the migration were removed; writing one is an error
        listOf("risk: read", "status: draft", "updated: 2026-10-24", "verified_on: x")
            .forEach { assertError("unknown header key ${it.substringBefore(':')}", appMd("a", listOf("p.a"), extra = "$it\n")) }
        assertError("only skills may have terms", appMd("a", listOf("p.a"), extra = "terms: [购物]\n"))
        assertError("only skills may have terms", siteMd("a.com").let { (p, t) -> p to t.replace("mode: desktop\n", "mode: desktop\nterms: [购物]\n") })
        assertError("description exceeds", appMd("a", listOf("p.a")).let { (p, t) -> p to t.replace("description: a 的说明", "description: " + "x".repeat(201)) })
        assertError("over 8000", appMd("a", listOf("p.a"), body = "字".repeat(8001)))
        assertError("mode must be", siteMd("a.com").let { (p, t) -> p to t.replace("mode: desktop\n", "") })
        // pace: apps and sites may set it and only accept careful; skills may not
        assertError("pace must be", siteMd("a.com").let { (p, t) -> p to t.replace("mode: desktop\n", "mode: desktop\npace: slow\n") })
        assertError("pace must be", appMd("a", listOf("p.a"), extra = "pace: slow\n"))
        assertError("only apps and sites may set pace", skillMd("s", extra = "pace: careful\n"))
        assertTrue(errors(appMd("a", listOf("p.a"), extra = "pace: careful\n")).isEmpty())
        val careful = siteMd("a.com").let { (p, t) -> p to t.replace("mode: desktop\n", "mode: desktop\npace: careful\n") }
        assertTrue(errors(careful).isEmpty())
        assertError("must be in domains", siteMd("a.com", domains = listOf("b.com")))
        assertError("missing SKILL.md", "skills/x/notes.md" to "x")
        assertError("in links is not allowed", appMd("a", listOf("p.a"), extra = "links: [\"文件 | file:///sdcard/a\"]\n"))
        // Skill bodies have no limit
        assertTrue(errors(skillMd("long", body = "字".repeat(5000))).isEmpty())
    }

    @Test fun uniqueNamesPackagesAndDomains() {
        assertError("package p.a is declared by several apps", appMd("a", listOf("p.a")), appMd("b", listOf("p.a")))
        assertError("domain a.com is declared by several sites", siteMd("a.com"), siteMd("b.com", domains = listOf("b.com", "a.com")))
        assertError("duplicate name dup", appMd("dup", listOf("p.a")), skillMd("dup"))
    }

    @Test fun linksMustExistBothWays() {
        assertError("a.com in sites does not exist", appMd("a", listOf("p.a"), sites = listOf("a.com")))
        assertError("apps is missing a", appMd("a", listOf("p.a"), sites = listOf("a.com")), siteMd("a.com"))
        assertError("nope in apps does not exist", skillMd("s", apps = listOf("nope")))
    }

    @Test fun referencedAttachmentsMustExist() {
        assertError("attachment references/x.md does not exist", appMd("a", listOf("p.a"), body = "读 `references/x.md`"))
        // When another entry is named, look for the file inside that entry
        assertTrue(errors(appMd("a", listOf("p.a"), body = "读 `skill:s` 的 `references/x.md`"), skillMd("s"), "skills/s/references/x.md" to "x").isEmpty())
        assertError("the referenced skill:t does not exist", appMd("a", listOf("p.a"), body = "见 skill:t"))
        assertError("scripts/run.py, marked runnable in the manifest, does not exist", skillMd("s"), "skills/s/scripts/manifest.json" to """[{"file":"scripts/run.py","runnable":true}]""")
    }

    @Test fun localPathsRejectedExceptLicenseFiles() {
        assertTrue(errors(appMd("a", listOf("p.a"), body = "见 /Users/me/x")).any { "local path" in it })
        assertTrue(errors(appMd("a", listOf("p.a")), "knowledge/apps/a/assets/LICENSE.md" to "/Users/me/x").isEmpty())
    }
}
