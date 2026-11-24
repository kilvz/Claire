package com.wangzan101.mika.voice

/**
 * While holding, the recognition service may end a segment on its own and restart; this joins the segments together.
 * Chinese and Japanese segments join directly; segments in other scripts (English etc.) get a space between them. Pure logic, touches no platform APIs.
 */
class Segments {
    private var done = ""
    private var pending = ""

    /** Intermediate result of the current segment; returns the full text so far. */
    fun partial(text: String): String {
        pending = text.trim()
        return full()
    }

    /** The current segment finished recognizing; [text] is that segment's final text. */
    fun segmentDone(text: String) {
        done = join(done, text.trim())
        pending = ""
    }

    /** The completed segments plus the intermediate result of the unfinished one; used when no final result arrives after release. */
    fun full(): String = join(done, pending).trim()

    companion object {
        /** This many trailing characters (by code point) may still be changed by the recognition service, so the UI draws them dimmer. */
        private const val TAIL = 4

        fun split(full: String): SpeechEvent.Partial {
            val n = full.codePointCount(0, full.length)
            if (n <= TAIL) return SpeechEvent.Partial("", full)
            val cut = full.offsetByCodePoints(0, n - TAIL)
            return SpeechEvent.Partial(full.substring(0, cut), full.substring(cut))
        }

        private fun join(a: String, b: String): String = when {
            a.isEmpty() -> b
            b.isEmpty() -> a
            noSpace(a.codePointBefore(a.length)) || noSpace(b.codePointAt(0)) || b.codePointAt(0).toChar() in ATTACHED -> a + b
            else -> "$a $b"
        }

        /** Punctuation that attaches to the preceding text without a space. */
        private const val ATTACHED = ",.!?;:)"

        /** It is whitespace itself, or belongs to scripts that are not segmented by spaces (Han characters, kana and their full-width punctuation). */
        private fun noSpace(cp: Int): Boolean {
            if (Character.isWhitespace(cp)) return true
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> return true
                else -> {}
            }
            val block = Character.UnicodeBlock.of(cp)
            return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
        }
    }
}
