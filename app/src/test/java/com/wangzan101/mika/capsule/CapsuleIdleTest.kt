package com.wangzan101.mika.capsule

import com.wangzan101.mika.chat.CurrentChat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which conversation the card continues when the idle icon is opened: the one the user is in inside the App ([CurrentChat]); with no record, the most recent one (the fallback the caller reads out),
 * while a newly opened conversation with nothing said yet is a new-chat card. Pure logic, no disk reads.
 */
class CapsuleIdleTest {
    private val recent = CardView.Chat("c-old", title = "订票")

    @Test fun noRecordFallsBackToTheMostRecentConversation() {
        assertEquals(recent, CapsuleIdle.chat(null, recent))
        // Not a single conversation: a new-chat card
        assertEquals(CardView.Chat(null), CapsuleIdle.chat(null, null))
    }

    @Test fun aBrandNewConversationIsAFreshCard() {
        val card = CapsuleIdle.chat(CurrentChat.Fresh, recent)
        assertEquals(CardView.Chat(null, fresh = true), card)
        // No ID: the caller opens a new one when something is said in the card
        assertEquals(null, card.target)
    }

    @Test fun theConversationOnScreenWins() {
        val asked = mutableListOf<String>()
        val card = CapsuleIdle.chat(CurrentChat.Open("c-new"), recent) { id -> asked += id; "查快递" }
        assertEquals(CardView.Chat("c-new", title = "查快递", current = true), card)
        // Only its title is looked up: the fallback's title is not needed
        assertEquals(listOf("c-new"), asked)
        // It records that this is "the one the user is in right now", which stops the card side from letting an old task override it
        assertEquals(true, card.current)
    }

    /** A conversation without a title (it has an ID but no name yet) is still continued, only no title is written at the top of the card. */
    @Test fun aConversationWithoutATitleIsStillContinued() {
        val card = CapsuleIdle.chat(CurrentChat.Open("c-new"), recent) { null }
        assertEquals(CardView.Chat("c-new", title = null, current = true), card)
        assertEquals("c-new", card.target)
    }
}
