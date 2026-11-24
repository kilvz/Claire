package com.wangzan101.mika.core.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Claire ships only the English README.md, with no translations: the upstream Mika translations were of an older README,
 * and they went stale when Claire 1.0.0 rewrote the README completely, so they were removed along with that rewrite.
 * If someone translates again later, add the files back to [TRANSLATIONS] following the mechanism below:
 *  - each translation starts with `<!-- translated-from: README.md @ <fingerprint> -->`, the fingerprint being the [fingerprint] of README.md at translation time;
 *    if README.md changes and the translation doesn't follow, this fails and lists which ones are stale;
 *  - a translation has the same number of `## ` sections as README.md and uses the same local images (each resolved to a repo path from its own directory before comparing);
 *    online badge images are written per language and are not compared.
 * Generated sections live only in README.md and are guarded by [ReadmeSectionsTest]; translations must not have them.
 */
class ReadmeTranslationsTest {
    private val root = File("..").canonicalFile
    private val source get() = File(root, ReadmeSections.README).readText()

    @Test fun translationsAreUpToDate() {
        val current = fingerprint(source)
        val stale = TRANSLATIONS.mapNotNull { name ->
            val marker = MARKER.find(File(root, name).readText())?.groupValues?.get(1)
            when (marker) {
                null -> "$name is missing <!-- translated-from: README.md @ $current --> at the top"
                current -> null
                else -> "$name was translated from an older README.md ($marker); update the translation for the changes in README.md, then change the translated-from at the top to $current"
            }
        }
        assertTrue("README translations are stale (README.md now has fingerprint $current):\n" + stale.joinToString("\n"), stale.isEmpty())
    }

    @Test fun translationsKeepStructure() {
        val sections = sections(source)
        val images = localImages(source, ReadmeSections.README)
        val bad = TRANSLATIONS.flatMap { name ->
            val text = File(root, name).readText()
            listOfNotNull(
                sections(text).takeIf { it != sections }?.let { "$name has $it ## sections, README.md has $sections" },
                localImages(text, name).takeIf { it != images }?.let { "$name has different local images from README.md: extra ${it - images}, missing ${images - it}" },
            )
        }
        assertTrue("README translations do not match the structure of README.md:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun translationsHaveNoGeneratedBlocks() {
        val bad = TRANSLATIONS.filter { "<!-- generated:" in File(root, it).readText() }
        assertTrue("generated sections belong only in README.md; replace them in translations with a one-line pointer:$bad", bad.isEmpty())
    }

    @Test fun fingerprintIgnoresGeneratedBlocksAndLineEndings() {
        val a = "前\n${ReadmeSections.start("x")}\n旧\n${ReadmeSections.end("x")}\n后\n"
        val b = "前\r\n${ReadmeSections.start("x")}\r\n新的清单\r\n${ReadmeSections.end("x")}\r\n后\r\n"
        assertEquals(fingerprint(a), fingerprint(b))
        assertNotEquals(fingerprint(a), fingerprint(a.replace("后", "改了")))
        assertEquals(12, fingerprint(a).length)
    }

    companion object {
        /** Claire ships only the English README, with no translations (see the file header comment); add files back here if it is translated again. Translations all live under docs/, which the test's docsDir input in core/build.gradle.kts already covers; anywhere else needs an extra input. */
        val TRANSLATIONS = emptyList<String>()
        private val MARKER = Regex("""<!-- translated-from: README\.md @ ([0-9a-f]+) -->""")
        private val GENERATED = Regex("""<!-- generated:([\w-]+):start -->.*?<!-- generated:\1:end -->""", RegexOption.DOT_MATCHES_ALL)

        /** SHA-256 of README.md with generated sections (markers included) removed and line endings normalized, truncated to the first 12 hex digits. Generated sections change with the code, so they don't count as changes a translation must follow. */
        fun fingerprint(readme: String): String {
            val text = GENERATED.replace(readme.replace("\r\n", "\n"), "")
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
        }

        private fun prose(text: String): List<String> {
            var fenced = false
            return text.lines().filter { line ->
                if (line.trimStart().startsWith("```")) { fenced = !fenced; false } else !fenced
            }
        }

        private fun sections(text: String) = prose(text).count { it.startsWith("## ") }

        private val IMAGE = Regex("""<img\s[^>]*\bsrc="([^"]+)"|!\[[^\]]*]\(([^)\s]+)""")

        /** Local images in [text], resolved to repo paths relative to the directory of the file at [path] (relative to the repo root), so different relative notations per language still compare. */
        private fun localImages(text: String, path: String): Set<String> {
            val dir = File(path).parentFile
            return IMAGE.findAll(prose(text).joinToString("\n")).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                .filter { "://" !in it }.map { (if (dir == null) File(it) else File(dir, it)).normalize().invariantSeparatorsPath }.toSet()
        }
    }
}
