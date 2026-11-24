package com.wangzan101.mika.core.frontdesk

import org.junit.Assert.assertEquals
import org.junit.Test

class DeskLinesTest {
    // An apostrophe inside a word is not a quote: the part between "don't" and "it's" must not be stripped as a quoted segment
    @Test
    fun apostrophesInsideWordsAreNotQuotes() {
        assertEquals("I don't think it's ready", stripDetails("I don't think it's ready"))
    }

    @Test
    fun singleQuotedDetailsAreStillStripped() {
        assertEquals("Opened the event", stripDetails("Opened the event 'Dinner with Sam'"))
    }

    @Test
    fun quotedTitlesAndCoordinatesAreStripped() {
        assertEquals("打开了 点了", stripDetails("打开了「周五晚饭」 点了 [120, 340]"))
    }
}
