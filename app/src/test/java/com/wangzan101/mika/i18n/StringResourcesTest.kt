package com.wangzan101.mika.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The three sets of UI texts, English (default), Simplified Chinese and Traditional Chinese, must line up one-to-one: the same entries and the same placeholders (Traditional checked against Simplified); English must not keep untranslated Chinese.
 * The other languages ([others]) must also line up with English one-to-one, with plurals written out for every quantity word of that language.
 */
class StringResourcesTest {
    /** A UI language other than Chinese and English: its resource folder, and which quantity words its plurals must define (per CLDR plural rules). */
    private class Other(val folder: String, val locale: Locale, val quantities: Set<String>) {
        val texts by lazy { ResFileTexts(folder, locale) }
    }

    private val others = listOf(
        Other("values-ja", Locale.JAPANESE, setOf("other")),
        Other("values-ko", Locale.KOREAN, setOf("other")),
        Other("values-ar", Locale.forLanguageTag("ar"), setOf("zero", "one", "two", "few", "many", "other")),
        Other("values-fa", Locale.forLanguageTag("fa"), setOf("one", "other")),
        Other("values-es", Locale.forLanguageTag("es"), setOf("one", "many", "other")),
        Other("values-pt", Locale.forLanguageTag("pt"), setOf("one", "many", "other")),
        Other("values-ru", Locale.forLanguageTag("ru"), setOf("one", "few", "many", "other")),
        // The Indonesian resource folder keeps Android's legacy language code in
        Other("values-in", Locale.forLanguageTag("id"), setOf("other")),
        Other("values-tr", Locale.forLanguageTag("tr"), setOf("other")),
    )

    private val en = ResFileTexts.english
    private val zh = ResFileTexts.chinese
    private val hant = ResFileTexts.traditional

    /** Entries marked translatable="false" exist only in the default file. */
    private val fixed: Set<String> = ResFileTexts.resFiles("values").flatMap { f ->
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.getAttribute("translatable") == "false" }.map { it.getAttribute("name") }
    }.toSet()

    @Test
    fun everyStringHasBothLanguages() {
        assertEquals(en.strings.keys - fixed, zh.strings.keys)
        assertEquals(en.plurals.keys, zh.plurals.keys)
        assertTrue(en.strings.size > 300)
    }

    @Test
    fun placeholdersMatch() {
        // The date takes different fields in the two languages (Chinese writes the month number, English writes the month name)
        for (name in zh.strings.keys - setOf("date_month_day", "date_year_month_day")) assertEquals(name, args(en.strings.getValue(name)), args(zh.strings.getValue(name)))
        for (name in zh.plurals.keys) {
            val expected = args(zh.plurals.getValue(name).getValue("other"))
            en.plurals.getValue(name).values.forEach { assertEquals(name, expected, args(it)) }
        }
    }

    @Test
    fun traditionalMatchesSimplified() {
        assertEquals(zh.strings.keys, hant.strings.keys)
        assertEquals(zh.plurals.keys, hant.plurals.keys)
        for (name in zh.strings.keys) assertEquals(name, args(zh.strings.getValue(name)), args(hant.strings.getValue(name)))
        for (name in zh.plurals.keys) {
            assertEquals(name, zh.plurals.getValue(name).keys, hant.plurals.getValue(name).keys)
            zh.plurals.getValue(name).forEach { (q, v) -> assertEquals("$name/$q", args(v), args(hant.plurals.getValue(name).getValue(q))) }
        }
    }

    @Test
    fun otherLanguagesMatchEnglish() {
        for (o in others) {
            val t = o.texts
            assertEquals(o.folder, en.strings.keys - fixed, t.strings.keys)
            assertEquals(o.folder, en.plurals.keys, t.plurals.keys)
            for (name in t.strings.keys - setOf("date_month_day", "date_year_month_day")) {
                assertEquals("${o.folder}/$name", args(en.strings.getValue(name)), args(t.strings.getValue(name)))
            }
            for (name in t.plurals.keys) {
                val forms = t.plurals.getValue(name)
                assertEquals("${o.folder}/$name", o.quantities, forms.keys)
                val expected = args(en.plurals.getValue(name).getValue("other"))
                forms.forEach { (q, v) ->
                    val actual = args(v)
                    val mayOmitCount = o.folder == "values-ar" && q in setOf("one", "two") && "%1\$d" in expected
                    if (mayOmitCount) {
                        assertTrue("${o.folder}/$name/$q", actual == expected || actual == expected - "%1\$d")
                    } else {
                        assertEquals("${o.folder}/$name/$q", expected, actual)
                    }
                }
            }
        }
    }

    /** Languages that do not use Han characters should have no untranslated Chinese left; Japanese uses Han characters by nature, so it is not checked. */
    @Test
    fun otherLanguagesHaveNoChineseLeftOver() {
        val cjk = Regex("[\\u4e00-\\u9fff]")
        for (o in others.filter { it.folder != "values-ja" }) {
            val left = o.texts.strings.filter { (_, v) -> cjk.containsMatchIn(v) }.keys +
                o.texts.plurals.filter { (_, forms) -> forms.values.any(cjk::containsMatchIn) }.keys
            assertEquals(o.folder, emptySet<String>(), left)
        }
    }

    /** Every UI language has a matching resource folder, and ui_locale names that same language. */
    @Test
    fun everyAppLanguageHasItsTexts() {
        val byLanguage = others.associateBy { AppLanguage.modernCode(it.locale.language) }
        for (l in AppLanguage.entries) {
            val code = l.language ?: continue
            if (code == "zh" || code == "en") continue
            val o = byLanguage[code]
            assertTrue("$l has no folder", o != null)
            assertEquals(l.toString(), code, AppLanguage.modernCode(Locale.forLanguageTag(o!!.texts.strings.getValue("ui_locale")).language))
        }
    }

    @Test
    fun englishHasNoChineseLeftOver() {
        val cjk = Regex("[\\u4e00-\\u9fff]")
        val left = en.strings.filter { (k, v) -> k !in fixed && cjk.containsMatchIn(v) }.keys +
            en.plurals.filter { (_, forms) -> forms.values.any(cjk::containsMatchIn) }.keys
        assertEquals(emptySet<String>(), left)
    }

    @Test
    fun languageFromTag() {
        // Unit tests have no Android ICU: the script-completion step is replaced with a fake that recognizes only one private-use tag as Traditional
        val likely = { tag: String -> if (tag == "zh-x-hant") "Hant" else "Hans" }
        fun of(tag: String?) = AppLanguage.fromTag(tag, likely)
        assertEquals(AppLanguage.System, of(null))
        assertEquals(AppLanguage.System, of(""))
        // Choosing "Chinese" in the past stored zh-Hans
        assertEquals(AppLanguage.SimplifiedChinese, of("zh-Hans"))
        assertEquals(AppLanguage.TraditionalChinese, of("zh-Hant"))
        assertEquals(AppLanguage.SimplifiedChinese, of("zh"))
        // When no script is written, the completed one is used
        assertEquals(AppLanguage.TraditionalChinese, of("zh-x-hant"))
        // When a script is written, it is used as written and not completed
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.fromTag("zh-Hans") { "Hant" })
        assertEquals(AppLanguage.English, of("en"))
        assertEquals(AppLanguage.English, of("fr-FR"))
        assertEquals(AppLanguage.Arabic, of("ar"))
        assertEquals(AppLanguage.Arabic, of("ar-MA"))
        assertEquals(AppLanguage.Persian, of("fa-IR"))
        // Other languages look only at the language code and ignore the region
        assertEquals(AppLanguage.Japanese, of("ja-JP"))
        assertEquals(AppLanguage.Portuguese, of("pt-PT"))
        assertEquals(AppLanguage.Portuguese, of("pt-BR"))
        assertEquals(AppLanguage.Spanish, of("es-419"))
        // Indonesian: both the current code and the legacy code Android Locale still uses are recognized
        assertEquals(AppLanguage.Indonesian, of("id-ID"))
        assertEquals(AppLanguage.Indonesian, of("in-ID"))
    }

    @Test
    fun eachLanguageHasItsOwnTag() {
        for (l in AppLanguage.entries) assertEquals(l, AppLanguage.fromTag(l.tag) { "Hans" })
    }

    private fun args(s: String): Set<String> = Regex("%(\\d+\\$)?[sd]").findAll(s).map { it.value }.toSet()
}
