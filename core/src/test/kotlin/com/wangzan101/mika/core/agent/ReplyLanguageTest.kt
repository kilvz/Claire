package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.agent.ReplyLanguage.CHINESE
import com.wangzan101.mika.core.agent.ReplyLanguage.JAPANESE
import com.wangzan101.mika.core.agent.ReplyLanguage.Mismatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyLanguageTest {
    private val latin = ReplyLanguage.Family("Latin script")
    private val english = "What Android version is this phone on, and how much storage is used?"

    // First sentence in English, then the whole rest drifts into Chinese (the Chinese labels read on screen were not translated)
    private val drifted = """
        Done — I opened Settings and looked at both pages.

        **Android 版本：14**
        - 设备：Phone X（型号 AB-1234），系统为 OEM OS 8.0，安全补丁是 2024 年 9 月 1 日
        **存储使用情况**
        - 已使用 **250.53 GB / 512 GB**，可用 261.47 GB
        - 应用占 167.7 GB，图片和视频占 40.2 GB
        两个数字对照得上，存储页和关于手机页显示的一致。
    """.trimIndent()

    @Test fun driftedAnswerToEnglishGoalMismatches() {
        assertEquals(Mismatch(CHINESE, latin), ReplyLanguage.mismatch(english, drifted))
    }

    @Test fun fullyChineseAnswerToEnglishGoalMismatches() {
        val answer = "已经打开设置看过了：安卓版本是 14，存储已使用 250.53 GB，总共 512 GB，剩余 261.47 GB，两个页面的数字对得上。"
        assertEquals(Mismatch(CHINESE, latin), ReplyLanguage.mismatch(english, answer))
    }

    // English body text, with labels written as "translation (original)"
    @Test fun englishAnswerWithOriginalLabelsInParenthesesPasses() {
        val answer = """
            **Android version: 14**
            (from 关于手机 / About phone, where it lists Android 版本 14 and 安全补丁 2024-09-01)
            - 应用 (Apps): 167.7 GB
            - Storage used: 250.53 GB of 512 GB, with 261.47 GB free; the two pages agree with each other.
        """.trimIndent()
        assertNull(ReplyLanguage.mismatch(english, answer))
    }

    @Test fun englishAnswerQuotingManyChineseLabelsPasses() {
        val answer = "Open 「设置」, then 「关于手机」 and tap 「存储」. The page titled 「存储使用情况」 shows that " +
            "250.53 GB is used (已使用) and 261.47 GB is free (可用空间); the app list (应用列表) takes most of it, " +
            "followed by photos (图片和视频) and the system (系统数据)."
        assertNull(ReplyLanguage.mismatch(english, answer))
    }

    @Test fun chineseAnswerQuotingEnglishNamesToChineseGoalPasses() {
        val answer = "已经查过了：Settings 里 Wi-Fi 连着 HomeNetwork，Bluetooth 已关闭，Google Play 有 3 个应用待更新，" +
            "Android System WebView 是最新版本，Chrome 也不用更新。"
        assertNull(ReplyLanguage.mismatch("帮我看看手机的网络和应用更新情况", answer))
    }

    @Test fun answerListingOnlyForeignNamesPasses() {
        val answer = "Here are the apps on your home screen:\n- 微信\n- 支付宝\n- 淘宝\n- 抖音\n- 美团\n- 高德地图\n- 小红书\n- 网易云音乐\n- 哔哩哔哩\n- 拼多多"
        assertNull(ReplyLanguage.mismatch("Which apps are on my home screen?", answer))
        assertNull(ReplyLanguage.mismatch("Which apps are on my home screen?", "Apps: 微信、支付宝、淘宝、抖音、美团、高德地图、小红书、网易云音乐、哔哩哔哩"))
    }

    @Test fun shortAnswersNeverMismatch() {
        assertNull(ReplyLanguage.mismatch(english, "蓝牙已开启。"))
        assertNull(ReplyLanguage.mismatch("看看蓝牙开没开", "Bluetooth is on."))
    }

    @Test fun emptyOrTinyUserMessageNeverMismatches() {
        assertNull(ReplyLanguage.mismatch("", drifted))
        assertNull(ReplyLanguage.mismatch("  14  ", drifted))
    }

    @Test fun sameFamilyNeverMismatches() {
        assertNull(ReplyLanguage.mismatch("安卓版本是多少，存储用了多少", drifted))
        val english = "I opened Settings and checked both pages: the phone runs Android 14, and 250.53 GB of 512 GB is used, " +
            "leaving 261.47 GB free. The numbers on the storage page match the ones on the about page."
        assertNull(ReplyLanguage.mismatch("What Android version is this phone on?", english))
    }

    @Test fun japaneseIsTellFromChineseByKana() {
        val japanese = "設定を開いて確認しました。アンドロイドのバージョンは14で、ストレージは250ギガバイト使われています。"
        assertEquals(JAPANESE, ReplyLanguage.units(japanese, skipNames = false).keys.single())
        // Asked in Japanese, answered in Chinese: different language families
        assertEquals(Mismatch(CHINESE, JAPANESE), ReplyLanguage.mismatch("アンドロイドのバージョンを教えてください", drifted.substringAfter("\n")))
        // Asked in Japanese, answered in Japanese
        assertNull(ReplyLanguage.mismatch("アンドロイドのバージョンを教えてください", japanese))
        // Asked in English, answered in Japanese
        assertEquals(Mismatch(JAPANESE, latin), ReplyLanguage.mismatch(english, japanese))
    }

    @Test fun codeUrlsAndQuotesAreIgnored() {
        val stripped = ReplyLanguage.stripQuoted("看 `设置页面` 和 https://例子.com/路径 还有 “引号里” \"双引号\" 《书名》 （外（里）层）<标签>")
        assertEquals(setOf("看", "和", "还有"), stripped.split(Regex("\\s+")).filter(String::isNotBlank).toSet())
    }

    // Han characters count per character, Latin per word; abbreviations and units with no lowercase letters do not count
    @Test fun unitsAreFairAcrossScripts() {
        assertEquals(mapOf(CHINESE to 4), ReplyLanguage.units("打开设置", skipNames = false))
        assertEquals(mapOf(latin to 2), ReplyLanguage.units("Open Settings 512 GB OS", skipNames = false))
        assertEquals(mapOf(ReplyLanguage.KOREAN to 2), ReplyLanguage.units("설정 열기", skipNames = false))
    }
}
