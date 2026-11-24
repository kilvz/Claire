package com.wangzan101.mika.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messages of the self-hosted Fun-ASR real-time service; the samples are taken from real service responses (the speech is a Chinese sentence). */
class FunAsrProtocolTest {
    @Test fun openingAndClosingAreThePlainTextCommands() {
        val p = FunAsrProtocol()
        assertEquals(listOf("START"), p.opening())
        assertEquals(listOf("STOP"), p.closing())
        assertEquals(0L, p.packetGapMs)
    }

    // With a language chosen, LANGUAGE goes first (the server prompt uses Chinese language names), then START; nothing is sent when automatic
    @Test fun languageIsSentBeforeStart() {
        assertEquals(listOf("LANGUAGE:中文", "START"), FunAsrProtocol(AsrLanguage.Chinese).opening())
        assertEquals(listOf("LANGUAGE:英文", "START"), FunAsrProtocol(AsrLanguage.English).opening())
        assertEquals(listOf("LANGUAGE:粤语", "START"), FunAsrProtocol(AsrLanguage.Cantonese).opening())
        assertEquals(listOf("START"), FunAsrProtocol(AsrLanguage.Auto).opening())
        // The server's acknowledgement of LANGUAGE arrives ahead of started and does not count as the start
        assertEquals(LiveAsrUpdate.None, FunAsrProtocol(AsrLanguage.Chinese).read("""{"event": "language_set", "language": "中文"}"""))
    }

    @Test fun snapshotsReplaceTheWholeTextAndFinalWaitsForStopped() {
        val p = FunAsrProtocol()
        assertEquals(LiveAsrUpdate.Started, p.read("""{"event": "started"}"""))
        assertEquals(LiveAsrUpdate.Text("", ""), p.read("""{"sentences": [], "partial": "", "partial_start_ms": 0, "duration_ms": 500, "is_final": false}"""))
        assertEquals(LiveAsrUpdate.Text("", "你好，帮。"), p.read("""{"sentences": [], "partial": "你好，帮。", "partial_start_ms": 0, "duration_ms": 1000, "is_final": false}"""))
        assertEquals(
            LiveAsrUpdate.Text("先打开微信。", "给张三发消息"),
            p.read("""{"sentences": [{"text": "先打开微信。", "start": 0, "end": 1800, "spk": 0}], "partial": "给张三发消息", "partial_start_ms": 1800, "duration_ms": 3000, "is_final": false}"""),
        )
        val final = """{"sentences": [{"text": "先打开微信。", "start": 0, "end": 1800}, {"text": "给张三发一条消息。", "start": 1800, "end": 6867}], "partial": "", "partial_start_ms": 0, "duration_ms": 6867, "is_final": true}"""
        assertEquals(LiveAsrUpdate.Text("先打开微信。给张三发一条消息。", ""), p.read(final))
        assertEquals(LiveAsrUpdate.Ended("先打开微信。给张三发一条消息。"), p.read("""{"event": "stopped"}"""))
    }

    // When an utterance runs long without a sentence break, the server decodes only the most recent 15 seconds: words that slide out of the window stay in the preview instead of being dropped; see PartialStitchTest for how they are joined
    @Test fun textThatSlidesOutOfThePartialWindowIsKept() {
        val p = FunAsrProtocol()
        fun partial(text: String, start: Long, duration: Long) =
            p.read("""{"sentences": [], "partial": "$text", "partial_start_ms": $start, "duration_ms": $duration, "is_final": false}""")
        assertEquals(LiveAsrUpdate.Text("", "今天我们来聊一聊语音识别的问题，首先需要打开设置页面。"), partial("今天我们来聊一聊语音识别的问题，首先需要打开设置页面。", 0, 15000))
        // The window moved: the new snapshot starts a couple of characters in (at the second word); the beginning of the overlapping stretch keeps what is already shown and only switches to the new text where the two agree
        assertEquals(
            LiveAsrUpdate.Text("今天我们来聊一聊语音", "识别的问题，首先需要打开设置页面，然后"),
            partial("我们来聊一聊语音识别的问题，首先需要打开设置页面，然后", 1000, 16000),
        )
        assertEquals(
            LiveAsrUpdate.Text("今天我们来聊一聊语音识别的问题，", "首先需要打开设置页面，然后找到"),
            partial("聊语音识别的问题。首先需要打开设置页面，然后找到", 2000, 17000),
        )
        // The window did not move and the same stretch was decoded again: the carried text stays put and the new text follows it
        assertEquals(
            LiveAsrUpdate.Text("今天我们来聊一聊语音识别的问题，", "首先需要打开设置页面，然后找到模型"),
            partial("聊语音识别的问题，首先需要打开设置页面，然后找到模型", 2000, 17500),
        )
        // The whole stretch is placed into sentences: the carried characters are discarded and only the server's sentences are used; the next stretch starts from scratch and is not aligned with the previous one
        val whole = "今天我们来聊一聊语音识别的问题，首先需要打开设置页面，然后找到模型。"
        assertEquals(
            LiveAsrUpdate.Text(whole, ""),
            p.read("""{"sentences": [{"text": "$whole", "start": 0, "end": 17800}], "partial": "", "partial_start_ms": 18000, "duration_ms": 18500, "is_final": false}"""),
        )
        assertEquals(
            LiveAsrUpdate.Text(whole, "第二段"),
            p.read("""{"sentences": [{"text": "$whole", "start": 0, "end": 17800}], "partial": "第二段", "partial_start_ms": 19000, "duration_ms": 20000, "is_final": false}"""),
        )
    }

    // Releasing while carried characters are kept: the final result uses only the server's sentences from re-decoding the whole stretch, and does not splice in the carried part
    @Test fun finalIgnoresCarriedText() {
        val p = FunAsrProtocol()
        p.read("""{"sentences": [], "partial": "今天我们来聊一聊", "partial_start_ms": 0, "duration_ms": 15000, "is_final": false}""")
        assertEquals(
            LiveAsrUpdate.Text("今天", "我们来聊一聊语音"),
            p.read("""{"sentences": [], "partial": "我们来聊一聊语音", "partial_start_ms": 1000, "duration_ms": 16000, "is_final": false}"""),
        )
        assertEquals(
            LiveAsrUpdate.Text("今天我们来聊一聊语音识别。", ""),
            p.read("""{"sentences": [{"text": "今天我们来聊一聊语音识别。", "start": 0, "end": 16500}], "partial": "", "partial_start_ms": 0, "duration_ms": 16500, "is_final": true}"""),
        )
        assertEquals(LiveAsrUpdate.Ended("今天我们来聊一聊语音识别。"), p.read("""{"event": "stopped"}"""))
    }

    // The window moved but this snapshot is empty (no speech at this moment): the carried text remains and nothing follows it
    @Test fun emptyPartialKeepsCarriedText() {
        val p = FunAsrProtocol()
        p.read("""{"sentences": [], "partial": "今天我们来聊一聊", "partial_start_ms": 0, "duration_ms": 15000, "is_final": false}""")
        p.read("""{"sentences": [], "partial": "我们来聊一聊语音", "partial_start_ms": 1000, "duration_ms": 16000, "is_final": false}""")
        assertEquals(
            LiveAsrUpdate.Text("今天", ""),
            p.read("""{"sentences": [], "partial": "", "partial_start_ms": 16500, "duration_ms": 16500, "is_final": false}"""),
        )
    }

    // The complete response of the real service to a 40-plus-second utterance with no pause (audio recorded on a phone): before the server finalizes the stretch, every preview, once punctuation is removed, is a prefix of the final result —
    // no characters repeated at the seams, and no mis-decoded characters at the start of the window; after it is finalized, both the text and the final result use only the server's sentences
    @Test fun recordedLongUtterancePreviewNeverStraysFromTheFinalText() {
        val lines = FunAsrProtocolTest::class.java.getResource("/voice/funasr-long-utterance.jsonl")!!.readText().trim().lines()
        fun letters(text: String) = text.filter(Char::isLetterOrDigit).lowercase()
        val final = (FunAsrProtocol().read(lines.last()) as LiveAsrUpdate.Text).stable
        val p = FunAsrProtocol()
        var stitched = 0
        for (line in lines.dropLast(1)) {
            val locked = "\"sentences\": []" !in line
            val text = p.read(line) as LiveAsrUpdate.Text
            if (locked) { assertTrue(text.stable, final.startsWith(text.stable.take(20))); continue }
            if (text.stable.isEmpty()) continue
            stitched++
            assertTrue("${text.stable}|${text.tail}", letters(final).startsWith(letters(text.stable + text.tail)))
        }
        assertEquals(18, stitched)
        assertEquals(LiveAsrUpdate.Text(final, ""), p.read(lines.last()))
        assertEquals(LiveAsrUpdate.Ended(final), p.read("""{"event": "stopped"}"""))
    }

    // The deployment guide on the settings page opens this document in the repository: if the document moves, update this too
    @Test fun deployGuidePointsAtADocInThisRepo() {
        val path = FunAsrProtocol.DEPLOY_GUIDE.substringAfter("/blob/main/", "")
        assertTrue(path, path.isNotEmpty() && java.io.File("..", path).isFile)
    }

    // When no audio was sent (or it was too short) the server gives no final result and just replies stopped: treat it as not hearing clearly
    @Test fun stoppedWithoutFinalIsEmpty() {
        assertEquals(LiveAsrUpdate.Ended(""), FunAsrProtocol().read("""{"event": "stopped"}"""))
    }

    @Test fun acknowledgementsDoNotChangeText() {
        val p = FunAsrProtocol()
        assertEquals(LiveAsrUpdate.None, p.read("""{"event": "hotwords_set", "hotwords": ["Claire"]}"""))
        assertEquals(LiveAsrUpdate.None, p.read("""{"event": "language_set", "language": "中文"}"""))
    }

    @Test fun latinSentencesAreJoinedWithASpace() {
        val p = FunAsrProtocol()
        val update = p.read("""{"sentences": [{"text": "Open WeChat"}, {"text": "and send a message"}], "partial": "", "is_final": true}""")
        assertEquals(LiveAsrUpdate.Text("Open WeChat and send a message", ""), update)
    }

    @Test fun addressesAreNormalized() {
        assertEquals("ws://192.168.1.10:10095", FunAsrProtocol.normalize("192.168.1.10"))
        assertEquals("ws://192.168.1.10:10095", FunAsrProtocol.normalize(" 192.168.1.10:10095 "))
        assertEquals("ws://asr.lan", FunAsrProtocol.normalize("ws://asr.lan/"))
        assertEquals("ws://asr.lan:8080/asr", FunAsrProtocol.normalize("http://asr.lan:8080/asr/"))
        assertEquals("wss://asr.example.com/ws?room=1", FunAsrProtocol.normalize("https://asr.example.com/ws?room=1"))
        assertEquals("ws://[fd00::5]:10095", FunAsrProtocol.normalize("ws://[fd00::5]:10095"))
        // Normalizing an already normalized address leaves it unchanged
        for (url in listOf("ws://192.168.1.10:10095", "wss://asr.example.com/ws?room=1", "ws://[fd00::5]:10095")) {
            assertEquals(url, FunAsrProtocol.normalize(url))
        }
    }

    @Test fun badAddressesAreRejected() {
        for (bad in listOf("", "  ", "ftp://host", "ws://", "ws://user:pass@host:10095", "two words", "ws://host:port")) {
            assertNull(bad, FunAsrProtocol.normalize(bad))
        }
    }

    @Test fun hostOfDropsPathAndQuery() {
        assertEquals("192.168.1.10:10095", FunAsrProtocol.hostOf("ws://192.168.1.10:10095"))
        assertEquals("asr.example.com", FunAsrProtocol.hostOf("wss://asr.example.com/ws?token=x"))
    }
}
