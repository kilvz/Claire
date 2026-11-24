package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.wangzan101.mika.about.ui.Licenses

class LicensesTest {
    /** The unit test working directory is app/; the repository root is one level up. */
    private val notices = File("../licenses/THIRD_PARTY_NOTICES.md").takeIf { it.isFile } ?: File("licenses/THIRD_PARTY_NOTICES.md")

    @Test
    fun everyNoticeEntryShown() {
        val headings = notices.readLines().filter { it.startsWith("## ") }.map { it.removePrefix("## ").trim() }
        assertTrue("notices has headings", headings.size >= 16)
        val shown = Licenses.ENTRIES.map { it.name }
        headings.forEach { assertTrue("missing on licenses screen: $it", it in shown) }
        assertEquals("Claire", shown.first())
    }

    @Test
    fun gplSourceOfferPresent() {
        val byName = Licenses.ENTRIES.associateBy { it.name }
        assertEquals("Apache-2.0", byName.getValue("Claire").license)
        assertEquals("GPL-2.0-or-later", byName.getValue("PRoot").license)
        assertEquals("LGPL-3.0-or-later", byName.getValue("talloc").license)
        assertEquals("Apache-2.0", byName.getValue("Apache Commons Compress").license)
        listOf("OkHttp", "Okio", "kotlinx.coroutines", "kotlinx.serialization", "Kotlin 标准库", "AndroidX(含 Jetpack Compose)")
            .forEach { assertEquals(it, "Apache-2.0", byName.getValue(it).license) }
        assertTrue(Licenses.GPL_SOURCE_OFFER.contains("gpl-sources.tar.gz"))
        assertTrue(Licenses.GPL_SOURCE_OFFER.contains("PRoot") && Licenses.GPL_SOURCE_OFFER.contains("talloc"))
        assertTrue(byName.getValue("Alpine Linux").note.contains("gpl-sources.tar.gz"))
        assertEquals("0BSD", byName.getValue("XZ for Java").license)
    }

    /** The "open-source licenses" page lists every package in the bundled runtime, and each has a license. */
    @Test
    fun runtimePackagesListed() {
        val manifest = File("src/main/assets/runtime/packages.json")
        val entries = Licenses.runtimePackages(manifest.readText())
        assertTrue(entries.size > 40)
        listOf("busybox", "musl", "python3", "nodejs", "py3-pillow", "py3-pypdf").forEach { n -> assertTrue(n, entries.any { it.name == n }) }
        entries.forEach { assertTrue(it.name, it.license.isNotBlank() && it.note.startsWith("Version ")) }
    }
}
