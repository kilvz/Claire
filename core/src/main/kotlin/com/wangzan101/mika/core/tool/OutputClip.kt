package com.wangzan101.mika.core.tool

/** Command output handed to the model (shared by the sandbox and root commands): head + tail, with a note in the middle of how many characters were omitted. */
object OutputClip {
    fun clip(s: String, head: Int = 4000, tail: Int = 12000): Pair<String, Boolean> {
        if (s.length <= head + tail) return s to false
        // Do not split a surrogate pair in half
        val h = if (head > 0 && s[head - 1].isHighSurrogate()) head - 1 else head
        var start = s.length - tail
        if (start < s.length && s[start].isLowSurrogate()) start++
        return "${s.substring(0, h)}\n…(${start - h} characters omitted)…\n${s.substring(start)}" to true
    }
}
