package com.wangzan101.mika.core.screen.uistate

/**
 * The "limit reached" wording that newly appears in the UI after an action: today's quota used up, doing that too
 * often, come back tomorrow and the like.
 * Only short text that appears after the action counts (toasts, popups, page dialog text): anything already on the
 * screen before the action (explanatory text, help pages) does not count, and neither do long paragraphs. It is
 * recognized by wording only, not per site or app.
 */
object LimitNotice {
    /** Whitespace is stripped and case ignored when comparing. */
    val WORDS = listOf(
        "次数已用完", "次数用完", "机会已用完", "额度已用完", "已达上限", "已达到上限", "达到上限", "超过上限", "超出上限",
        "明天再来", "明日再来", "明天再试", "明日再试", "操作过于频繁", "操作太频繁", "操作频繁", "请求过于频繁", "访问过于频繁",
        "limitreached", "reachedthelimit", "reachedyourlimit", "reachedthedailylimit", "reachedyourdailylimit", "dailylimit",
        "tryagaintomorrow", "comebacktomorrow", "toomanyrequests", "doingthattoooften",
    )

    /** Wording with a few characters in between, such as "communication limit reached" or "exceeded the daily delivery limit". */
    private val REACHED = Regex("(已达|达到|超过|超出)[^，,。.!！?？]{0,6}上限")

    /** The upper bound for "short text" (characters after stripping whitespace): one or two sentences in a toast or popup. */
    private const val MAX_CHARS = 120

    /** [before] is the screen before the action (null when unreadable, in which case everything on screen counts as newly appeared); returns the newly appeared sentence, or null when there is none. */
    fun find(before: ScreenSnapshot?, after: ScreenSnapshot?): String? {
        after ?: return null
        val earlier = before?.let(::texts).orEmpty().map(::compact).toSet()
        return texts(after).firstOrNull { t ->
            val c = compact(t)
            c.length <= MAX_CHARS && c !in earlier && (WORDS.any { it in c } || REACHED.containsMatchIn(c))
        }?.trim()
    }

    private fun texts(s: ScreenSnapshot): List<String> =
        s.elements.filter { !it.editable && !it.hint && it.name.isNotBlank() }.map { it.name } + listOfNotNull(s.web?.dialog?.message)

    private fun compact(t: String) = t.filterNot(Char::isWhitespace).lowercase()
}
