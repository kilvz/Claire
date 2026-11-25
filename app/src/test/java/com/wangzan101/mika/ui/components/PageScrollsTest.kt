package com.wangzan101.mika.ui.components

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PageScrollsTest {
    private enum class Page { Home, Advanced, Sandbox }

    // The main page leaves composition for a subpage (whose state was read in the meantime); on return it still gets the same one and the position has not moved
    @Test fun aPageKeepsItsScrollPositionWhileAnotherPageIsShown() {
        val scrolls = PageScrolls()
        val home = scrolls.of(Page.Home)
        home.dispatchRawDelta(600f)
        val sandbox = scrolls.of(Page.Sandbox)
        sandbox.dispatchRawDelta(150f)

        assertSame(home, scrolls.of(Page.Home))
        assertEquals(600, scrolls.of(Page.Home).value)
        assertEquals(150, sandbox.value)
    }

    // Page rebuild: the saved values are the positions of pages that were read, restored on return; pages never saved start at 0
    @Test fun savedPositionsComeBackAfterRecreation() {
        val scrolls = PageScrolls()
        scrolls.of(Page.Home).dispatchRawDelta(600f)
        scrolls.of(Page.Advanced).dispatchRawDelta(120f)

        val saved = with(PageScrolls.Saver) { with(AnyValue) { save(scrolls) } }
        val restored = PageScrolls.Saver.restore(saved!!)!!

        assertEquals(600, restored.of(Page.Home).value)
        assertEquals(120, restored.of(Page.Advanced).value)
        assertEquals(0, restored.of(Page.Sandbox).value)
    }

    private object AnyValue : SaverScope {
        override fun canBeSaved(value: Any) = true
    }
}
