package com.wangzan101.mika.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Placement of the sidebar long-press menu: put it below if it fits, otherwise above, and if neither fits move that row up. Usable range 100..1000, row height 50, menu 150, gap 8. */
class ConversationMenuPlacementTest {
    private fun place(rowTop: Float) = placeMenu(rowTop, rowHeight = 50f, menuHeight = 150f, gap = 8f, top = 100f, bottom = 1000f)

    @Test fun roomBelowPutsTheMenuBelow() {
        assertEquals(MenuPlacement(rowTop = 300f, menuTop = 358f, above = false), place(300f))
        // Exactly fits: 792 + 50 + 8 + 150 = 1000
        assertEquals(MenuPlacement(792f, 850f, above = false), place(792f))
    }

    @Test fun nearTheBottomPutsTheMenuAbove() {
        assertEquals(MenuPlacement(rowTop = 800f, menuTop = 642f, above = true), place(800f))
        assertEquals(MenuPlacement(940f, 782f, above = true), place(940f))
    }

    @Test fun aRowPartlyOffTheUsableAreaIsMovedIn() {
        // A row scrolled under the status bar is first moved to the top of the usable range, with the menu below it
        assertEquals(MenuPlacement(100f, 158f, above = false), place(60f))
        // A row hidden by the keyboard is moved above the bottom edge of the usable range, with the menu above it
        assertEquals(MenuPlacement(950f, 792f, above = true), place(990f))
    }

    @Test fun noRoomEitherSideMovesTheRowUp() {
        // Only 100..400 of the usable range is left (the keyboard takes up most of the screen): it fits neither below nor above, so the row moves to 400-150-8-50 = 192 and the menu goes below
        assertEquals(MenuPlacement(192f, 250f, above = false), placeMenu(200f, 50f, 150f, 8f, top = 100f, bottom = 400f))
        // No matter how small it gets, it never crosses the top edge
        assertEquals(MenuPlacement(100f, 158f, above = false), placeMenu(150f, 50f, 150f, 8f, top = 100f, bottom = 250f))
    }
}
