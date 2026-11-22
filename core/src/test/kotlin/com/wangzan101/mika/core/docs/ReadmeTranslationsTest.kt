package com.wangzan101.mika.core.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Claire 只发英文 README.md,没有译文:上游 Mika 的译文是对旧版 README 的翻译,
 * Claire 1.0.0 把 README 完全重写后它们已过期,随这次重写一并删除。
 * 以后如果有人重新翻译,按下面的机制把文件加回 [TRANSLATIONS] 即可:
 *  - 每份译文开头有 `<!-- translated-from: README.md @ <指纹> -->`,指纹是翻译时 README.md 的 [fingerprint];
 *    README.md 改了而译文没跟着改,这里失败并列出哪几份过期了;
 *  - 译文和 README.md 的 `## ` 小节一样多,用到的本地图片一样(各自按所在目录解析成仓库里的路径再比);网上的徽章图各语言自己写,不比。
 * 生成段落只在 README.md 里,由 [ReadmeSectionsTest] 守着;译文里不能有。
 */
class ReadmeTranslationsTest {
    private val root = File("..").canonicalFile
    private val source get() = File(root, ReadmeSections.README).readText()

    @Test fun translationsAreUpToDate() {
        val current = fingerprint(source)
        val stale = TRANSLATIONS.mapNotNull { name ->
            val marker = MARKER.find(File(root, name).readText())?.groupValues?.get(1)
            when (marker) {
                null -> "$name 开头缺少 <!-- translated-from: README.md @ $current -->"
                current -> null
                else -> "$name 是从旧版 README.md($marker)翻译的;按 README.md 的改动更新译文,再把开头的 translated-from 改成 $current"
            }
        }
        assertTrue("README 译文过期了(README.md 现在的指纹是 $current):\n" + stale.joinToString("\n"), stale.isEmpty())
    }

    @Test fun translationsKeepStructure() {
        val sections = sections(source)
        val images = localImages(source, ReadmeSections.README)
        val bad = TRANSLATIONS.flatMap { name ->
            val text = File(root, name).readText()
            listOfNotNull(
                sections(text).takeIf { it != sections }?.let { "$name 有 $it 个 ## 小节,README.md 有 $sections 个" },
                localImages(text, name).takeIf { it != images }?.let { "$name 的本地图片和 README.md 不一样:多了 ${it - images},少了 ${images - it}" },
            )
        }
        assertTrue("README 译文和 README.md 结构不一致:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun translationsHaveNoGeneratedBlocks() {
        val bad = TRANSLATIONS.filter { "<!-- generated:" in File(root, it).readText() }
        assertTrue("生成段落只放在 README.md 里,译文里换成指向它的一句话:$bad", bad.isEmpty())
    }

    @Test fun fingerprintIgnoresGeneratedBlocksAndLineEndings() {
        val a = "前\n${ReadmeSections.start("x")}\n旧\n${ReadmeSections.end("x")}\n后\n"
        val b = "前\r\n${ReadmeSections.start("x")}\r\n新的清单\r\n${ReadmeSections.end("x")}\r\n后\r\n"
        assertEquals(fingerprint(a), fingerprint(b))
        assertNotEquals(fingerprint(a), fingerprint(a.replace("后", "改了")))
        assertEquals(12, fingerprint(a).length)
    }

    companion object {
        /** Claire 只发英文 README,没有译文(见文件头注释);以后重新翻译时把文件加回这里。译文都在 docs/ 下,core/build.gradle.kts 里测试的 docsDir 输入已经盖住;放到别处要另加输入。 */
        val TRANSLATIONS = emptyList<String>()
        private val MARKER = Regex("""<!-- translated-from: README\.md @ ([0-9a-f]+) -->""")
        private val GENERATED = Regex("""<!-- generated:([\w-]+):start -->.*?<!-- generated:\1:end -->""", RegexOption.DOT_MATCHES_ALL)

        /** README.md 去掉生成段落(连同标记)、统一换行后的 SHA-256,取前 12 位十六进制。生成段落随代码变,不算译文要跟的改动。 */
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

        /** [text] 里的本地图片,按所在文件 [path](相对仓库根目录)的目录解析成仓库里的路径,各语言的相对写法不同也能比。 */
        private fun localImages(text: String, path: String): Set<String> {
            val dir = File(path).parentFile
            return IMAGE.findAll(prose(text).joinToString("\n")).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                .filter { "://" !in it }.map { (if (dir == null) File(it) else File(dir, it)).normalize().invariantSeparatorsPath }.toSet()
        }
    }
}
