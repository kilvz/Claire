package com.wangzan101.mika.calls.briefing

import com.wangzan101.mika.calls.CallPolicy
import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.calls.VolcRealtimeEvent

/** Control is recognized only from a complete current owner turn, never model text. */
internal object VoiceBriefingAuthorization {
    const val PHRASE = "我说完了，你去打"
    const val ENGLISH_PHRASE = "I'm done, go ahead and call"
    private val zhPattern = Regex("我说完了[，,、\\s]*你去打[。.!！]?")
    // Speech recognition often puts a period between the two clauses (I'm done. Make the call.), so like the Chinese pattern only whitespace and punctuation between them are allowed.
    private val enPattern = Regex("(?i)(?:i['’]?m|i am)?\\s*done[,.!;\\s]+(?:make the call|(?:go ahead and|you can|please) (?:call|dial))(?: now)?[.!]?")
    fun matches(event: VolcRealtimeEvent.UserTranscript): Boolean {
        if (!event.isFinal || !event.isCompleteFinal || !event.isCurrentTurn) return false
        // Ignore only ordinary whitespace and punctuation separating these two exact clauses.
        // Quotes, extra words, negation, conditional statements and quoted examples all fail closed.
        val text = event.text.trim()
        return text.matches(zhPattern) || text.matches(enPattern)
    }
}

internal class VoiceBriefingConversation(initialBrief: CallTaskBrief?) {
    private val initial = initialBrief?.validatedSnapshot()
    private val turns = mutableListOf<String>()
    private var terminal = false

    val ownerTranscript: String get() = turns.joinToString("\n")

    /** A null return means keep listening. A snapshot can be returned at most once. */
    fun accept(event: VolcRealtimeEvent.UserTranscript): CallTaskBrief? {
        if (terminal || !event.isFinal) return null
        if (!event.isCompleteFinal) throw VoiceBriefingFailure("That sentence was not recognized completely; please give the briefing again")
        // The shared provider event deliberately has no turn identity/sequence. Never reorder
        // a late correction by arrival time, or silently lose it by discarding it.
        if (!event.isCurrentTurn) throw VoiceBriefingFailure("The briefing order changed; no call was placed. Please give the full briefing again")
        if (VoiceBriefingAuthorization.matches(event)) {
            if (event.hasUnresolvedPriorTurn) {
                throw VoiceBriefingFailure("The previous briefing was not recognized completely; no call was placed. Please give the full briefing again")
            }
            val brief = snapshot()
            terminal = true
            return brief
        }
        // The provider normally emits one final per item. Consecutive repeated finals add no
        // instruction and cannot consume the task capacity twice.
        if (event.text.isNotBlank() && event.text.trim() != turns.lastOrNull()) turns.add(event.text.trim())
        if (ownerTranscript.length > CallTaskBrief.MAX_CONTEXT_CHARS) {
            throw VoiceBriefingFailure("The briefing exceeds the length that can be saved; please shorten it and start again. No call will be placed")
        }
        return null
    }

    fun cancel() { terminal = true }

    private fun snapshot(): CallTaskBrief {
        if (turns.isEmpty()) return initial ?: throw VoiceBriefingFailure("No task has been heard yet; please say what this call should do")
        val original = initial?.let {
            buildString {
                append("Existing briefing:\nGoal: ").append(it.goal)
                if (it.questions.isNotEmpty()) append("\nMust ask:\n").append(it.questions.joinToString("\n"))
                if (it.context.isNotEmpty()) append("\nBackground: ").append(it.context)
                if (it.constraints.isNotEmpty()) append("\nConstraints: ").append(it.constraints)
                append("\nAdditional dictation (the owner's later words win on conflict):\n")
            }
        }.orEmpty()
        val full = original + ownerTranscript
        if (full.length > CallTaskBrief.MAX_CONTEXT_CHARS) {
            throw VoiceBriefingFailure("The briefing exceeds the length that can be saved; please shorten it and start again. No call will be placed")
        }
        // Keep every owner instruction verbatim, including corrections and boundaries. Model
        // clarifications never enter the task as if they were owner-confirmed facts.
        return CallTaskBrief(goal = "Carry out this call task according to the owner's full dictation", context = full).validatedSnapshot()
    }

    companion object {
        const val PROMPT = "You are the phone assistant Claire. You are now only talking with the phone's owner to prepare a task before dialing, and no call has been placed yet. " +
            "Briefly ask for the task goal, the necessary questions, the background and anything that must not be promised; ask only one genuinely missing question at a time. " +
            "The owner's corrections override what was said before, and any suggestion you make must be stated as a suggestion; never present a guess as fact. " +
            "The owner may speak in several parts, continue after a pause, or revise the briefing; a pause, unrecognized speech, your own understanding of the task or your own summary do not mean the briefing is over. " +
            "Keep listening until the owner explicitly authorizes you; do not say 'briefing complete' or 'it is over', do not urge the owner to finish, and do not decide on the owner's behalf that they have finished. " +
            "Do not claim you have dialed or completed anything, and do not perform any call control. The owner authorizes you only by saying, on its own, 'I'm done, make the call'; " +
            "do not say that line for the owner, and do not accept any other confirmation word in its place. Begin with: Tell me what you need done on this call; I'm listening."
        const val GREETING = "Tell me what you need done on this call; I'm listening."

        /** The briefing prompt; the app language is passed to the model so it answers in that language, and [greeting] is the line the app already spoke. */
        fun promptFor(languageTag: String?, greeting: String = GREETING): String {
            val lang = java.util.Locale.forLanguageTag(languageTag ?: "en").getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { "English" }
            return PROMPT.replace(
                "Begin with: Tell me what you need done on this call; I'm listening.",
                "Speak concisely in $lang. Start by saying: $greeting",
            )
        }

        /** A briefing the owner already filled in, appended as context for this call only; same language rule as [promptFor]. */
        fun briefContext(brief: CallTaskBrief, languageTag: String?): String =
            "\nThe owner has already filled in this briefing; use it only as context for this conversation:\nGoal: ${brief.goal}\n" +
                "Must ask: ${brief.questions.joinToString("; ")}\nBackground: ${brief.context}\nConstraints: ${brief.constraints}"
    }
}

internal class VoiceBriefingFailure(val safeMessage: String) : Exception(safeMessage)
