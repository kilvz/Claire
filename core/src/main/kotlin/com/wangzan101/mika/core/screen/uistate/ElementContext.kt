package com.wangzan101.mika.core.screen.uistate

/** An element's own context; when judging what it does only these are looked at, not the whole screen: the text on the same row (attached labels, in-row descriptions). */
object ElementContext {
    /** Same row: its center falls within the element's vertical range, or the element's center falls within its vertical range. */
    fun row(e: UiElement, s: ScreenSnapshot): List<UiElement> {
        if (e.bounds.size != 4) return emptyList()
        val cy = (e.bounds[1] + e.bounds[3]) / 2
        return s.elements.filter { o ->
            o !== e && o.bounds.size == 4 && ((o.bounds[1] + o.bounds[3]) / 2 in e.bounds[1]..e.bounds[3] || cy in o.bounds[1]..o.bounds[3])
        }
    }
}
