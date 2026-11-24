package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPreviewTest {
    // Reading a page only in a temporary background tab: the screen shows no tab, so it does not count as being on the web and the small window does not appear (it used to be a blank white rectangle)
    @Test fun backgroundReadsAloneDoNotOpenThePreview() {
        val p = WebPreview.of(onWeb = true, hasTab = false, handedOver = false)
        assertEquals(WebPreview.NoPage, p)
        assertFalse(p.open)
    }

    // A visible page is open: frames are shown
    @Test fun aVisibleTabShowsItsFrames() {
        val p = WebPreview.of(onWeb = true, hasTab = true, handedOver = false)
        assertEquals(WebPreview.Frames, p)
        assertTrue(p.open)
    }

    // The page is in the user's hands: still counts as being on the web, but no frames are shown
    @Test fun handedOverPageStaysOpenWithoutFrames() {
        val p = WebPreview.of(onWeb = true, hasTab = true, handedOver = true)
        assertEquals(WebPreview.Hidden, p)
        assertTrue(p.open)
    }

    @Test fun notOnTheWebIsOff() {
        assertEquals(WebPreview.Off, WebPreview.of(onWeb = false, hasTab = true, handedOver = false))
        assertEquals(WebPreview.Off, WebPreview.of(onWeb = false, hasTab = false, handedOver = true))
    }
}
