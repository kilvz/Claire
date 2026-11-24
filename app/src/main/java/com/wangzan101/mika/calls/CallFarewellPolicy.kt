package com.wangzan101.mika.calls

/** Accepts only an unambiguous closing from a caller's final utterance. No transcript is retained. */
internal object CallFarewellPolicy {
    private const val THANKS = "谢谢(?:你|您)?(?:啊|了)?|多谢|辛苦了"
    private const val COURTESY = "好(?:的|吧|啊)?|行(?:吧|啊)?|嗯{1,3}|那(?:好吧?)?|$THANKS"
    private const val CLOSING_CONTEXT = "(?:那)?就(?:先)?这样吧|(?:我这边|我)?没(?:有)?别的事了|不打扰了|(?:改天|回头)再聊"
    private const val GOODBYE = "(?:再见|拜拜)(?:啊|啦|了)?"
    private const val SELF_HANGUP = "(?:我(?:们|这边)?)(?:就)?(?:先)?挂(?:断)?(?:电话)?了(?:啊|啦|吧)?"
    private const val REQUEST_HANGUP = "(?:(?:麻烦|请)(?:你|您)?(?:帮我)?(?:先)?(?:挂断(?:电话)?(?:吧|了|一下)?|挂(?:一下)?电话(?:吧|了)?)|" +
        "(?:你|您)(?:可以|先)?挂(?:断)?(?:电话)?(?:了|吧|了吧)|" +
        "(?:可以|请)(?:直接)?结束(?:这次)?通话(?:了|吧)?|" +
        "(?:可以|请)(?:直接)?挂断(?:电话)?(?:了|吧)?|挂(?:断)?电话吧|挂断吧|可以挂了)"

    // English equivalents. Deliberately narrow: a courtesy word alone is never a closing.
    private const val EN_THANKS = "thanks(?: (?:a lot|so much|again))?|thank you(?: (?:very much|so much|again))?|cheers|appreciate it"
    private const val EN_COURTESY = "ok(?:ay)?|alright|all right|sure|fine|right|well|so|great|perfect|good|got it|sounds good|no worries|no problem|that's fine|$EN_THANKS"
    private const val EN_CLOSING = "(?:(?:that's|that is|this is) (?:all|it|everything)(?: for now| for today)?|" +
        "(?:i think )?(?:that's|that is) about it|all done|" +
        "(?:i )?(?:don't|do not) have anything else|nothing else(?: from me| for now)?|" +
        "(?:let's|let us|we(?:'ll| will| can| should)|i(?:'ll| will)) (?:leave|stop|end|finish|wrap up|call) (?:it )?(?:there|here)|" +
        "(?:let's|let us) call it a day|(?:i'll|i will) let you go)"
    private const val EN_GOODBYE = "(?:good ?bye|bye(?:[- ]?bye)?|see you(?: later| soon| next time)?|talk to you later|take care|" +
        "have a (?:good|nice|great) (?:day|one|evening|weekend))"
    private const val EN_HANGUP = "(?:(?:please |can you |could you |would you )?(?:hang up|end (?:the|this) call|disconnect)(?: now| please| for me| then)?|" +
        "you (?:can|may) (?:hang up|end (?:the|this) call)(?: now)?|" +
        "(?:feel free to|go ahead and) (?:hang up|end (?:the|this) call)|" +
        "(?:i(?:'ll| will)|let me|we(?:'ll| will)) (?:hang up|end (?:the|this) call|go)(?: now| then)?)"

    private val splitClause = Regex("[,，、。.!！；;]+")
    private val spaces = Regex("[ \\t\\r\\n　]+")
    private val closing = Regex("(?:$COURTESY){0,4}(?:(?:$GOODBYE){1,2}|$SELF_HANGUP|$REQUEST_HANGUP)(?:$THANKS)?")
    private val beforeClosing = Regex("(?:$COURTESY)|(?:$CLOSING_CONTEXT)")
    private val afterClosing = Regex(THANKS)
    private val enClosing = Regex("(?:(?:$EN_COURTESY) ){0,4}(?:$EN_GOODBYE|$EN_CLOSING|$EN_HANGUP)(?: (?:$EN_THANKS))?")
    private val enBeforeClosing = Regex("(?:$EN_COURTESY)|(?:$EN_CLOSING)")
    private val enAfterClosing = Regex(EN_THANKS)
    private val enSpaces = Regex("[ \\t\\r\\n　]+")
    private val quoteOrQuestion = "?？\"“”‘「」『』《》〈〉＂＇"
    private val quotationApostrophe = Regex("(?<![A-Za-z])['’]|['’](?![A-Za-z])")
    private val reportedSpeech = Regex(
        "例如|比如|举例|示例|假如|假设|假设说|引用|原话|(?:的|这)句(?:话)?|" +
            "(?:可以|可|建议|不妨|就|应该|请|要|试着|用来)(?:先|直接|跟\\S{1,8}|对\\S{1,8}|这么|这样)?(?:说|回答|回复|念)|" +
            "(?:他说|她说|对方说|他当时说|她当时说|转述|复述)[：:]?",
    )

    private val settledEnding = Regex(
        "(?:那|好|行)?(?:我们)?(?:就(?:先)?这样|先这样|(?:就|先)(?:到这里|聊到这里|说到这里))(?:吧|了)?|" +
            "(?:那)?(?:我)?(?:就)?(?:先)?不打扰(?:您|你)?了|祝您(?:顺利|生活愉快)",
    )

    /** A natural closing sentence, never a lone acknowledgement, a question or quoted words. */
    fun isAssistantClosing(text: String): Boolean {
        if (isExplicitCallerFarewell(text)) return true
        if (text.length !in 1..180 || hasQuoteOrQuestion(text)) return false
        return isChineseAssistantClosing(text) || isEnglishFarewell(text, 180)
    }

    private fun isChineseAssistantClosing(text: String): Boolean {
        val normalized = text.replace(spaces, "")
        if (reportedSpeech.containsMatchIn(normalized)) return false
        val ending = normalized.split(splitClause).lastOrNull(String::isNotEmpty) ?: return false
        return settledEnding.matches(ending)
    }

    fun isExplicitCallerFarewell(text: String): Boolean {
        if (text.length !in 1..100 || hasQuoteOrQuestion(text)) return false
        return isChineseCallerFarewell(text) || isEnglishFarewell(text, 100)
    }

    private fun isChineseCallerFarewell(text: String): Boolean {
        val clauses = text.split(splitClause).map { it.replace(spaces, "") }.filter(String::isNotEmpty)
        if (clauses.size !in 1..8) return false

        var foundClosing = false
        for (clause in clauses) {
            when {
                closing.matches(clause) -> foundClosing = true
                !foundClosing && beforeClosing.matches(clause) -> Unit
                foundClosing && afterClosing.matches(clause) -> Unit
                else -> return false
            }
        }
        return foundClosing
    }

    /** English closings stay conservative: a courtesy word or "thanks" alone never counts. */
    private fun isEnglishFarewell(text: String, maxLength: Int): Boolean {
        if (text.length !in 1..maxLength) return false
        val clauses = text.lowercase().replace('’', '\'').split(splitClause)
            .map { it.trim().replace(enSpaces, " ") }.filter(String::isNotEmpty)
        if (clauses.size !in 1..8) return false

        var foundClosing = false
        for (clause in clauses) {
            when {
                enClosing.matches(clause) -> foundClosing = true
                !foundClosing && enBeforeClosing.matches(clause) -> Unit
                foundClosing && enAfterClosing.matches(clause) -> Unit
                else -> return false
            }
        }
        return foundClosing
    }

    /** Quotes and question marks fail closed; an apostrophe inside a word (that's, don't) does not. */
    private fun hasQuoteOrQuestion(text: String): Boolean =
        text.any { it in quoteOrQuestion } || quotationApostrophe.containsMatchIn(text)
}
