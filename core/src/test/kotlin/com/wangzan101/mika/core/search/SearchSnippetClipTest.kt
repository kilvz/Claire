package com.wangzan101.mika.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search snippets are clipped to 600 code points and must not leave half a surrogate pair. */
class SearchSnippetClipTest {
    @Test fun blankUrlsAreDropped() {
        assertNull(SearchHttp.hit("t", "  ", "s"))
    }

    @Test fun longSnippetsAreClippedWithoutSplittingASurrogatePair() {
        // The leading ASCII character makes the 600th char land exactly on the high surrogate of an emoji
        val text = SearchHttp.hit("t", "https://example.com", "x" + "😀".repeat(700))!!.snippet
        assertTrue(text.endsWith("…"))
        val body = text.removeSuffix("…")
        assertEquals(600, body.codePointCount(0, body.length))
        assertFalse("must not end with a lone high surrogate", body.last().isHighSurrogate())
        assertEquals("clipped snippet must be valid Unicode", body,
            String(body.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test fun shortSnippetsAreOnlyTrimmed() {
        assertEquals("hello", SearchHttp.hit("t", "https://example.com", "  hello  ")!!.snippet)
        assertEquals("😀😀", SearchHttp.hit("t", "https://example.com", "😀😀")!!.snippet)
    }
}
