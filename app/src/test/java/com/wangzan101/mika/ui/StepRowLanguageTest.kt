package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.UiText
import com.wangzan101.mika.i18n.UiTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.wangzan101.mika.chat.ui.StepRow
import com.wangzan101.mika.chat.ui.StepState

class StepRowLanguageTest {

    @Before
    fun setUp() {
        // Unit tests have no App process to install UiText.source; pin ui_locale to an English value so the default "is this a Chinese UI" check is stable.
        UiText.source = object : UiTexts {
            override fun string(id: Int, args: Array<out Any?>): String =
                if (id == R.string.ui_locale) "en" else ""

            override fun plural(id: Int, count: Int, args: Array<out Any?>): String = ""
        }
    }

    @Test
    fun testShouldHideChineseReasonInNonChineseLocale() {
        assertTrue(StepRow.shouldHideRawReason("点击搜索按钮查找联系人 Ved", isChineseLocale = false))
        assertTrue(StepRow.shouldHideRawReason("在搜索框输入“ved”", isChineseLocale = false))
    }

    @Test
    fun testShouldKeepEnglishReasonInNonChineseLocale() {
        assertFalse(StepRow.shouldHideRawReason("Tap search button to find Ved", isChineseLocale = false))
        assertFalse(StepRow.shouldHideRawReason("Type \"ved\" in search box", isChineseLocale = false))
        assertFalse(StepRow.shouldHideRawReason("", isChineseLocale = false))
    }

    @Test
    fun testShouldKeepChineseReasonInChineseLocale() {
        assertFalse(StepRow.shouldHideRawReason("点击搜索按钮查找联系人 Ved", isChineseLocale = true))
    }

    @Test
    fun testShouldKeepJapaneseReasonInNonChineseLocale() {
        // Under a Japanese UI, a Japanese reason written by the model per the language instruction contains both kanji and kana, so it is not Chinese and must not be hidden.
        assertFalse(StepRow.shouldHideRawReason("検索ボタンをタップして連絡先を探す", isChineseLocale = false))
        assertFalse(StepRow.shouldHideRawReason("「微信」アプリを開く", isChineseLocale = false))
        // Under a Chinese UI a Japanese reason is shown as usual (the Chinese UI never hides anything).
        assertFalse(StepRow.shouldHideRawReason("設定を開いてBluetoothを確認する", isChineseLocale = true))
    }

    @Test
    fun testShouldHideKanjiOnlyReasonInNonChineseLocale() {
        // Kanji-only text with no kana cannot be told apart from Chinese, so it still falls back under a non-Chinese UI (a remaining limitation, asserted as it is).
        assertTrue(StepRow.shouldHideRawReason("設定確認", isChineseLocale = false))
    }

    @Test
    fun testStepRowTextFallback() {
        // With an English UI language (ui_locale=en installed above), a Chinese reason output by the model falls back to the localized title.
        val rowFallback = StepRow(
            step = 1,
            title = "Tap · WeChat",
            subtitle = "",
            state = StepState.Ok,
            reason = "点击“微信”应用图标",
        )
        assertEquals("Tap · WeChat", rowFallback.text)

        // An English reason output by the model is always shown directly.
        val rowEnglish = StepRow(
            step = 1,
            title = "Tap · WeChat",
            subtitle = "",
            state = StepState.Ok,
            reason = "Tap \"WeChat\" icon to open app",
        )
        assertEquals("Tap \"WeChat\" icon to open app", rowEnglish.text)
    }

    @Test
    fun testStepRowTextShowsJapaneseReasonUnderJapaneseUi() {
        // Japanese UI (ui_locale=ja): a Japanese reason written by the model per the language instruction (kanji included) is shown directly, without falling back to the localized title.
        UiText.source = object : UiTexts {
            override fun string(id: Int, args: Array<out Any?>): String =
                if (id == R.string.ui_locale) "ja" else ""

            override fun plural(id: Int, count: Int, args: Array<out Any?>): String = ""
        }
        val row = StepRow(
            step = 1,
            title = "タップ · WeChat",
            subtitle = "",
            state = StepState.Ok,
            reason = "「微信」アプリを開く",
        )
        assertEquals("「微信」アプリを開く", row.text)
    }
}
