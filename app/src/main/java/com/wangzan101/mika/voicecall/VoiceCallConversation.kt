package com.wangzan101.mika.voicecall

/** Which conversation a call hangs on: every opening must land on the same one so what was said in earlier calls can be picked up. */
object VoiceCallConversation {
    /**
     * [showing] is the conversation the chat page has open right now. Child-task conversations dispatched by the front desk (where [parentOf] resolves a front desk conversation) do not take the call; it reattaches to their front desk conversation;
     * when the chat page is a blank new conversation, [claim] reserves the next one (the chat page stays on it too), so the next opening uses the same one instead of creating a new one every time.
     */
    fun pick(showing: String?, parentOf: (String) -> String?, claim: () -> String): String =
        showing?.let { parentOf(it) ?: it } ?: claim()
}
