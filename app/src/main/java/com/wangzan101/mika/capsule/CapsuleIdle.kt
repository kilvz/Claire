package com.wangzan101.mika.capsule

import com.wangzan101.mika.chat.CurrentChat

/**
 * Which conversation the card continues from when the idle icon is tapped (pure logic): the one the user is in inside the app ([current], see [CurrentChat]); when that is not
 * recorded, the most recent one the caller read ([recent], which only exists when the process just started and older versions have no record); with neither, it is a new conversation card.
 *
 * [title] returns the title (user-given or the first sentence) of the conversation [CurrentChat.Open.id], or null when there is none yet.
 */
object CapsuleIdle {
    fun chat(current: CurrentChat?, recent: CardView.Chat?, title: (String) -> String? = { null }): CardView.Chat = when (current) {
        null -> recent ?: CardView.Chat(null)
        CurrentChat.Fresh -> CardView.Chat(null, fresh = true)
        // Remember that this is the one the user is in right now: results that ended in other conversations and are still lingering must not steal the card (see [CapsuleCards.of])
        is CurrentChat.Open -> CardView.Chat(current.id, title = title(current.id), current = true)
    }
}
