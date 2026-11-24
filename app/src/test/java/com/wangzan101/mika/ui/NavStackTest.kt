package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavStackTest {
    @Test fun backReturnsToWhereYouCameFrom() {
        val nav = NavStack()
        assertEquals(Route.Chat, nav.current)
        assertFalse(nav.canGoBack)
        assertFalse(nav.back())

        // Entering Memory from the side menu: back returns to Chat; entering Memory from Settings: back returns to Settings
        nav.push(Route.Memory)
        assertTrue(nav.back())
        assertEquals(Route.Chat, nav.current)
        nav.push(Route.Settings)
        nav.push(Route.Memory)
        assertEquals(listOf(Route.Chat, Route.Settings, Route.Memory), nav.stack)
        nav.back()
        assertEquals(Route.Settings, nav.current)
    }

    @Test fun samePageIsNotPushedTwice() {
        val nav = NavStack()
        nav.push(Route.PhoneSetup)
        nav.push(Route.PhoneSetup)
        assertEquals(listOf(Route.Chat, Route.PhoneSetup), nav.stack)
    }

    @Test fun resetAndChatGoBackToTheRoot() {
        val nav = NavStack(listOf(Route.Settings, Route.Memory))
        assertEquals(listOf(Route.Chat, Route.Settings, Route.Memory), nav.stack)
        nav.push(Route.Chat)
        assertEquals(listOf(Route.Chat), nav.stack)
        nav.push(Route.Plans)
        nav.reset()
        assertEquals(listOf(Route.Chat), nav.stack)
    }

    @Test fun restoredStackAlwaysStartsWithChat() {
        assertEquals(listOf(Route.Chat, Route.Run), NavStack(listOf(Route.Chat, Route.Chat, Route.Run)).stack)
        assertEquals(listOf(Route.Chat), NavStack(emptyList()).stack)
    }

    @Test fun backFromAPageOpenedInTheDrawerReopensTheDrawer() {
        val nav = NavStack()
        nav.pushFromDrawer(Route.Settings)
        nav.push(Route.Memory)
        nav.back()
        assertFalse(nav.reopenDrawer)
        nav.back()
        assertTrue(nav.reopenDrawer)
        nav.drawerReopened()
        assertFalse(nav.reopenDrawer)
    }

    @Test fun pagesNotOpenedFromTheDrawerReturnToChatOnly() {
        val nav = NavStack()
        nav.push(Route.Settings)
        nav.back()
        assertFalse(nav.reopenDrawer)
    }

    @Test fun openingAConversationDoesNotReopenTheDrawer() {
        val nav = NavStack()
        val initialReset = nav.resetSerial
        nav.pushFromDrawer(Route.Plans)
        nav.reset()
        assertEquals(initialReset + 1, nav.resetSerial)
        assertFalse(nav.reopenDrawer)
        nav.push(Route.Settings)
        nav.back()
        assertFalse(nav.reopenDrawer)
    }

    // Call page: tapping call again during a call brings it back to the top (pages stacked above it are dismissed); when the call ends it is removed from the stack, even if pages sit below it
    @Test fun callPageComesBackAndLeaves() {
        val nav = NavStack()
        nav.bringToTop(Route.VoiceCall)
        assertEquals(listOf(Route.Chat, Route.VoiceCall), nav.stack)
        nav.push(Route.Run)
        nav.bringToTop(Route.VoiceCall)
        assertEquals(listOf(Route.Chat, Route.VoiceCall), nav.stack)
        nav.leave(Route.VoiceCall)
        assertEquals(listOf(Route.Chat), nav.stack)
        nav.push(Route.VoiceCall)
        nav.push(Route.CallMode)
        nav.leave(Route.VoiceCall)
        assertEquals(listOf(Route.Chat, Route.CallMode), nav.stack)
        nav.leave(Route.VoiceCall)
        assertEquals(listOf(Route.Chat, Route.CallMode), nav.stack)
    }
}
