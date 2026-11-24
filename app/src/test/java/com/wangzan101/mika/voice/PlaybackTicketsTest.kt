package com.wangzan101.mika.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTicketsTest {

    @Test
    fun `a new request invalidates the old one`() {
        val tickets = PlaybackTickets()
        val first = tickets.begin()
        assertTrue(tickets.owns(first))
        val second = tickets.begin()
        assertFalse(tickets.owns(first))
        assertTrue(tickets.owns(second))
    }

    @Test
    fun `the old request's callback is dropped when the file write completes`() {
        val tickets = PlaybackTickets()
        // The first request starts writing the file (suspended), and the second request comes in right after
        val staleWrite = tickets.begin()
        val freshWrite = tickets.begin()
        // The old write finishes first: its generation is already invalidated, so it may not land as a player
        assertFalse(tickets.owns(staleWrite))
        // The new write finishes: only it may go on to build the MediaPlayer
        assertTrue(tickets.owns(freshWrite))
    }

    @Test
    fun `the old player's callback is dropped after stopping on purpose`() {
        val tickets = PlaybackTickets()
        val playing = tickets.begin()
        // The user tapped stop; the MediaPlayer whose prepare is still in flight only calls back onPrepared afterwards
        tickets.invalidate()
        assertFalse(tickets.owns(playing))
        // The next playback afterwards gets a brand-new generation and is unaffected by this invalidation
        val next = tickets.begin()
        assertTrue(tickets.owns(next))
    }

    @Test
    fun `multiple callbacks within the same generation all count`() {
        val tickets = PlaybackTickets()
        val ticket = tickets.begin()
        assertTrue(tickets.owns(ticket))
        assertTrue(tickets.owns(ticket))
    }
}
