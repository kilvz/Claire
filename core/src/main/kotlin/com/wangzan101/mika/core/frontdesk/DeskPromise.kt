package com.wangzan101.mika.core.frontdesk

/**
 * A front-desk reply that promises work it never started. Answering a question, explaining or giving a status
 * takes no tool call, so the test is deliberately narrow: only first-person commitments to do something
 * ("On it, I'll set …", "Let me check …", "I'm changing …") count. The caller gives the model one silent turn to
 * start the work before the promise is left with nothing behind it.
 *
 * The prompt is the primary guard and works in every language; this is the safety net for the acknowledgement
 * forms the front desk uses most, in the languages it most often answers in. A miss only leaves the old behaviour.
 */
object DeskPromise {
    /** The note for that corrective turn; only its tool calls matter, the caller drops any text. */
    const val CHECK_NOTE = "Your last reply promised the user something but started no task. If the user asked for " +
        "work that is not finished, call dispatch_task now with their words exactly as they said them; if the reply " +
        "was only an explanation, or that work is already running, call nothing and do not repeat the reply."

    private val commitments = listOf(
        // English: the short acknowledgements the front desk uses before starting something.
        Regex(
            """^\s*(on it\b|i['’]?m on it\b|(okay|ok|sure|alright)[,.!]?\s+i['’]?ll\b)""",
            RegexOption.IGNORE_CASE,
        ),
        // English: "I'll" / "I will" / "I'm going to" / "let me" followed by an action, not by an offer to explain.
        Regex(
            """\b(i['’]?ll|i will|i['’]?m going to|i am going to|let me)\s+(set|turn|check|fix|change|start|open|look|find|do|handle|try|verify|make|get|run|switch|enable|disable|remove|install|update|restart|reboot|connect|disconnect|clear|move|copy|delete|send|share|book|order|cancel|add|stop|test|scan|search|read|write|configure|reset|grant|revoke)\b""",
            RegexOption.IGNORE_CASE,
        ),
        // English: work already under way in words: "I'm checking …", "I am looking …".
        Regex(
            """\b(i['’]?m|i am)\s+(checking|looking|setting|turning|changing|fixing|working on|starting|opening|running|verifying|trying)\b""",
            RegexOption.IGNORE_CASE,
        ),
        // Indonesian: the same acknowledgements ("Siap, saya …", "Baik, aku …") and "sedang/lagi" work in progress.
        Regex(
            """^\s*(siap|baik|oke|ok)\b[,.!]?\s*(saya|aku)\b""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """\b(saya|aku)\s+(akan|sedang|lagi)\s+(memeriksa|mengecek|mengatur|mengubah|memperbaiki|menyalakan|mematikan|mencoba|mencari|menyetel|mengurus)\b""",
            RegexOption.IGNORE_CASE,
        ),
        // Indonesian: "Biar saya …" (let me …).
        Regex(
            """\b(biar|biarlah)\s+(saya|aku)\b""",
            RegexOption.IGNORE_CASE,
        ),
        // Chinese: patterns meaning "I'll / let me / I will / I'm about to … + action", simplified and traditional; the action can follow a short object (for example, "I'll change the settings").
        Regex(
            """(我来|我來|让我|讓我|我会|我會|我这就|我這就|我马上|我馬上|正在|马上|馬上)[^。！？!?\n]{0,12}(检查|檢查|查看|设置|設定|调整|調整|处理|處理|修好|修改|关掉|關掉|关闭|關閉|打开|打開|试试|試試|看一下|改)""",
        ),
    )

    fun pending(reply: CharSequence): Boolean = commitments.any { it.containsMatchIn(reply) }
}
