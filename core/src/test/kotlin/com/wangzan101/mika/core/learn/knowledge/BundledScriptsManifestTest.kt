package com.wangzan101.mika.core.learn.knowledge

import com.wangzan101.mika.core.cap.sandbox.SANDBOX_PACKAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The scripts mentioned in the bundled knowledge and skill documents match the `scripts/manifest.json` of their own directory. */
class BundledScriptsManifestTest {
    /** Resource root (below it are knowledge/apps, knowledge/sites and skills). */
    private val root = File(GuideCatalog::class.java.classLoader.getResource(KnowledgeIndex.RESOURCE)!!.toURI()).parentFile.parentFile

    /** The entry directory the file belongs to, e.g. `knowledge/sites/zhihu.com` or `skills/research`. */
    private fun entryDir(rel: String): String = rel.split('/').let { p -> p.take(if (p[0] == "knowledge") 3 else 2).joinToString("/") }

    private fun entryFiles(): Sequence<File> = sequenceOf("knowledge/apps", "knowledge/sites", "skills")
        .flatMap { File(root, it).walkTopDown().filter(File::isFile) }
    private val ref = Regex("scripts/[A-Za-z0-9_./-]+\\.(py|sh|js|ts|mjs)")

    /** Entry directory → manifest; this fails when a manifest file cannot be parsed. */
    private val manifests: Map<String, List<ScriptEntry>> by lazy {
        entryFiles().filter { it.name == "manifest.json" && it.parentFile.name == "scripts" }.associate { f ->
            val dir = f.parentFile.parentFile.relativeTo(root).invariantSeparatorsPath
            val text = f.readText()
            val entries = ScriptManifest.parse(text)
            assertTrue("$dir: manifest is empty or unreadable", entries.isNotEmpty())
            // parse drops unsafe paths; here every entry must survive
            assertEquals("$dir: every entry must be a safe relative path", Regex("\"file\"").findAll(text).count(), entries.size)
            dir to entries
        }
    }

    /** Entry directory → the scripts mentioned in the documents (license files starting with LICENSE do not count). */
    private fun references(): Map<String, Set<String>> {
        val out = mutableMapOf<String, MutableSet<String>>()
        entryFiles().filter { it.extension == "md" && !it.name.startsWith("LICENSE") }.forEach { f ->
            val dir = entryDir(f.relativeTo(root).invariantSeparatorsPath)
            ref.findAll(f.readText()).forEach { out.getOrPut(dir) { mutableSetOf() } += it.value }
        }
        return out
    }

    @Test fun everyReferencedScriptHasManifestEntry() {
        val refs = references()
        // The directories that mention scripts and those that have a manifest are the same set
        assertEquals(manifests.keys, refs.keys)
        for ((dir, scripts) in refs) {
            val listed = manifests[dir]?.map { it.file }.orEmpty()
            for (s in scripts) assertTrue("$dir mentions $s but its scripts/manifest.json does not list it", s in listed)
        }
        // Conversely: every script listed in a manifest is mentioned in a document
        for ((dir, entries) in manifests) {
            for (e in entries) assertTrue("$dir lists ${e.file} that no document mentions", e.file in refs[dir].orEmpty())
        }
    }

    @Test fun runnableScriptsExist() {
        val runnable = manifests.flatMap { (dir, es) -> es.filter { it.runnable }.map { dir to it } }
        // The shipped script files (attachments under scripts/ in each index entry, other than the manifest) are exactly the ones marked runnable in the manifests
        val shipped = GuideCatalog.loadBundled().rows.flatMap { r ->
            r.files.filter { it.startsWith("scripts/") && it != "scripts/manifest.json" }.map { "${KnowledgeIndex.dirOf(r.kind)}/${r.name}" to it }
        }.toSet()
        assertEquals(shipped, runnable.map { (dir, e) -> dir to e.file }.toSet())
        for ((dir, e) in runnable) {
            val f = File(root, "$dir/${e.file}")
            assertTrue("$dir/${e.file} is marked runnable but missing", f.isFile)
            assertNotNull("$dir/${e.file}: no interpreter for this type", ScriptManifest.interpreterFor(e.file))
            val head = f.readLines().take(3).joinToString("\n")
            assertTrue("$dir/${e.file} needs an Apache-2.0 header", "SPDX-License-Identifier: Apache-2.0" in head)
            assertTrue("$dir/${e.file} needs --help", "Usage:" in f.readText())
        }
        // Non-runnable scripts give a reason; those needing account authorization use one shared wording
        for ((dir, es) in manifests) for (e in es.filter { !it.runnable }) {
            assertTrue("$dir/${e.file} has no reason", !e.reason.isNullOrBlank())
            if ("auth" in e.needs) assertEquals("$dir/${e.file}", AUTH_REASON, e.reason)
        }
        // A scripts directory holds only the manifest and the files marked runnable
        for ((dir, es) in manifests) {
            val files = File(root, "$dir/scripts").listFiles()!!.map { "scripts/${it.name}" }.toSet()
            assertEquals(dir, es.filter { it.runnable }.map { it.file }.toSet() + "scripts/manifest.json", files)
        }
    }

    /** Every shipped script has a test that actually runs it: some test file contains both its directory (the source for run_bundled_script) and the script path. */
    @Test fun everyShippedScriptHasATest() {
        val catalog = GuideCatalog.loadBundled()
        val tests = listOf("src/test", "../app/src/test", "../app/src/androidTest").map(::File)
            .flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "BundledScriptsManifestTest.kt" }.toList() }
            .map { it.readText() }
        assertTrue("no test sources found", tests.isNotEmpty())
        val shipped = catalog.runnableScripts()
        assertEquals(shipped, catalog.scriptFiles().keys.filter { !it.endsWith("manifest.json") }.toSet())
        for (path in shipped) {
            val script = "scripts/" + path.substringAfter("/scripts/")
            val skill = path.substringBefore("/scripts/")
            assertTrue("$path has no test that runs it", tests.any { "\"$skill\"" in it && "\"$script\"" in it })
        }
    }

    @Test fun manifestNeedsAreInstalled() {
        for ((dir, es) in manifests) for (e in es) {
            // Networking: `net` in needs matches the network field (run_bundled_script gates on network)
            assertEquals("$dir/${e.file}: needs 'net' must match network", "net" in e.needs, e.network)
            if (!e.runnable) continue
            for (n in e.needs) {
                assertTrue("$dir/${e.file}: runnable scripts may only need apk: packages or net, not $n", n == "net" || n.startsWith("apk:"))
                if (n.startsWith("apk:")) assertTrue("$dir/${e.file} needs $n, which SANDBOX_PACKAGES lacks", n.removePrefix("apk:") in SANDBOX_PACKAGES)
            }
        }
    }

    private companion object {
        const val AUTH_REASON = "需要账号授权,Claire 暂不支持"
    }
}
