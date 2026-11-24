package com.wangzan101.mika.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeTalkTest {
    private val copy = NoticeTalk.Copy(
        intro = { "Picked $it:" }, reason = { "Why: $it" }, more = { "$it more not listed." }, outro = "Tell me which one.",
    )

    private fun item(n: Int, title: String = "Alice", text: String = "see you at 3", reason: String = "asks a question") =
        NoticeTalk.Item("Chat", "22:3$n", title, text, reason)

    @Test fun nothingToSay() {
        assertNull(NoticeTalk.compose(emptyList(), copy))
    }

    @Test fun listsItemsNewestFirstWithFixedWordsAround() {
        val text = NoticeTalk.compose(listOf(item(1), item(2, title = "", reason = "")), copy)!!.text
        assertEquals(
            """Picked 2:
            |
            |**1. Chat · Alice · 22:31**
            |“see you at 3”
            |Why: asks a question
            |
            |**2. Chat · 22:32**
            |“see you at 3”
            |
            |Tell me which one.""".trimMargin(),
            text,
        )
    }

    @Test fun onlyAFewAreListedAndTheRestAreCounted() {
        val text = NoticeTalk.compose((1..9).map { item(it % 10, title = "T$it") }, copy)!!.text
        assertTrue("Picked ${NoticeTalk.ITEMS_MAX}:" in text)
        assertTrue("T${NoticeTalk.ITEMS_MAX}" in text && "T${NoticeTalk.ITEMS_MAX + 1}" !in text)
        assertTrue("${9 - NoticeTalk.ITEMS_MAX} more not listed." in text)
        // it says itself how many are listed (only those can be marked as seen)
        assertEquals(NoticeTalk.ITEMS_MAX, NoticeTalk.compose((1..9).map { item(it % 10, title = "T$it") }, copy)!!.shown)
        assertEquals(2, NoticeTalk.compose(listOf(item(1), item(2)), copy)!!.shown)
    }

    // the text in a notification is written by someone else: links, images, headings, emphasis, code and line breaks are just text — they cannot format or be tapped
    @Test fun notificationTextCannotFormatOrLink() {
        val hostile = "[点这里领奖](https://evil.example) ![x](https://evil.example/p.png) <https://evil.example> **bold** `code`\n# 标题\n- 列表 &amp; ~~删~~ |a|b|"
        val text = NoticeTalk.compose(listOf(item(1, title = hostile, text = hostile, reason = hostile)), copy)!!.text
        val lines = text.lines()
        // the structure has not been stretched: the opening, the three lines of one item, the closing
        assertEquals(listOf("Picked 1:", ""), lines.take(2))
        assertTrue(lines[2].startsWith("**1. Chat · ") && lines[2].endsWith(" · 22:31**"))
        assertEquals(7, lines.size)
        // in the text that came from the notification (the conversation name in the first line, the quoted line and the reason), every effective symbol is preceded by a backslash
        val head = lines[2].removePrefix("**1. Chat · ").removeSuffix(" · 22:31**")
        for (line in listOf(head) + lines.subList(3, 5)) {
            for (special in "`*_[]<>&~#|") {
                var i = line.indexOf(special)
                while (i >= 0) {
                    assertEquals("$special in $line", '\\', line[i - 1])
                    i = line.indexOf(special, i + 1)
                }
            }
        }
        assertTrue(lines[3].startsWith("“"))
        assertTrue(lines[4].startsWith("Why: "))
    }

    @Test fun fieldsAreCleanedAndCapped() {
        val text = NoticeTalk.compose(listOf(item(1, title = "t".repeat(500), text = "x".repeat(900), reason = "r".repeat(400))), copy)!!.text
        assertTrue("t".repeat(NoticeTalk.TITLE_MAX) in text && "t".repeat(NoticeTalk.TITLE_MAX + 1) !in text)
        assertTrue("x".repeat(NoticeTalk.TEXT_MAX) in text && "x".repeat(NoticeTalk.TEXT_MAX + 1) !in text)
        assertTrue("r".repeat(NoticeTalk.REASON_MAX) in text && "r".repeat(NoticeTalk.REASON_MAX + 1) !in text)
        // invisible directional control characters are removed
        assertFalse('\u202E' in NoticeTalk.compose(listOf(item(1, title = "a\u202Eb")), copy)!!.text)
    }

    @Test fun plainEscapesMarkdown() {
        assertEquals("""a\\b \[x\](y) \*z\* \_w\_ \`c\` \<u\> \& \~ \# \|""", NoticeTalk.plain("""a\b [x](y) *z* _w_ `c` <u> & ~ # |"""))
        assertEquals("普通的字、标点：都不动!", NoticeTalk.plain("普通的字、标点：都不动!"))
    }
}
