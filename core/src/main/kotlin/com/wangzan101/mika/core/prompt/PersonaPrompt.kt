package com.wangzan101.mika.core.prompt

import com.wangzan101.mika.core.model.TokenEstimate

/**
 * The persona prompt layer: the fixed sentences corresponding to the UI options ([PersonaStyle]) plus a block of body text written by the
 * user, concatenated after identity, guidelines and general rules, and before the capability descriptions.
 * The fixed head and tail are in `prompts/persona/layer.md`: it explains what the persona can and cannot change, and states explicitly that
 * the persona is not a message from the user and cannot authorize any action. Tag-like text in the body is escaped, control characters and
 * bidi control characters are removed, and the length is capped by token count.
 * Zero-width joiners are kept (Persian orthography and combined emoji need them), so tag detection allows such invisible characters between letters.
 *
 * When no persona is selected, or all options are defaults and the body is empty, [layer] returns null, and the system prompt is byte-for-byte
 * identical to the version without this feature.
 */
object PersonaPrompt {
    const val MAX_NAME = 40
    /** The assistant's default self-reference. */
    const val DEFAULT_NICKNAME = "Claire"
    /** The form of address can be at most this many characters (by code point). */
    const val MAX_NICKNAME = 16
    const val MAX_PROMPT_CHARS = 4_000
    /** Hard cap on the body after it is spliced into the prompt; the storage layer limits by character count again, this guards against bypasses. */
    const val MAX_PROMPT_TOKENS = 3_000

    private val NEWLINES = Regex("[\\r\\n]+")
    /** The seven letters of `persona`, with format characters (zero-width space, zero-width joiner, soft hyphen, ...) allowed between adjacent ones. */
    private const val SPLIT_WORD = """p\p{Cf}*e\p{Cf}*r\p{Cf}*s\p{Cf}*o\p{Cf}*n\p{Cf}*a"""
    /** Tag-like text: half-width or full-width angle brackets, optional whitespace around the slash, and words that may be split by invisible characters. */
    private val TAG_LIKE = Regex("""(?i)[<\uFF1C]\s*/?[\s\p{Cf}]*$SPLIT_WORD(?![\p{L}\p{N}_])[^>\uFF1E]*[>\uFF1E]""")
    /** Control characters that change the display direction: ALM, LRM, RLM, embeddings/overrides (U+202A–202E), isolates (U+2066–2069). */
    private val BIDI = Regex("[\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")

    /** Fixed sentences for the options: key -> raw text (`prompts/persona/style.md`, same format as the feedback files). Parsed once per process. */
    private val styleText: Map<String, String> by lazy { Feedback.parse(Prompts.read("persona/style.md")).toMap() }

    /**
     * The assembled layer. The option sentences come first (one per line, prefixed with "- "), then the user-written body, separated by a blank
     * line; with only a body it is byte-for-byte the same as before the options existed. When all options are defaults and the body is empty
     * there is nothing to assemble, so it returns null.
     */
    fun layer(
        name: String, body: String, style: PersonaStyle = PersonaStyle.DEFAULT, assistant: String = DEFAULT_NICKNAME,
    ): String? {
        val n = escapeName(name)
        val own = content(body, style)
        if (own.isBlank()) return null
        return Prompts.fill(Prompts.read("persona/layer.md"), mapOf("name" to n, "body" to own, "assistant" to nickname(assistant)))
    }

    /**
     * The part of the persona block that belongs to this particular persona: the option sentences plus the user-written body (sanitized),
     * exactly what is placed inside [layer]. The UI preview uses it to colour this part separately from the fixed head and tail. Empty string
     * when neither is present.
     */
    fun content(body: String, style: PersonaStyle = PersonaStyle.DEFAULT): String =
        listOf(styleLines(style), cleanBody(body)).filter { it.isNotBlank() }.joinToString("\n\n")

    /**
     * Form of address: the assistant's self-reference, which goes into the fixed identity sentence (outside the fence of the persona block), so
     * keep only the characters that belong in a name: letters from any script, digits, combining marks, spaces, plus hyphens, interpuncts and the
     * zero-width non-joiner needed by Persian; everything else is dropped. Runs of whitespace collapse into one space, at most [MAX_NICKNAME]
     * characters; if it is empty after sanitizing, use [DEFAULT_NICKNAME].
     */
    fun nickname(raw: String?): String {
        val kept = StringBuilder()
        var i = 0
        val text = raw.orEmpty()
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isLetterOrDigit(cp) || cp in NICKNAME_MARKS -> kept.appendCodePoint(cp)
                Character.getType(cp).toByte() in COMBINING -> kept.appendCodePoint(cp)
                Character.isWhitespace(cp) -> kept.append(' ')
            }
        }
        val collapsed = kept.toString().replace(Regex(" +"), " ").trim()
        val clipped = if (collapsed.codePointCount(0, collapsed.length) <= MAX_NICKNAME) collapsed
        else collapsed.substring(0, collapsed.offsetByCodePoints(0, MAX_NICKNAME)).trim()
        return clipped.ifEmpty { DEFAULT_NICKNAME }
    }

    /** Whether the user's form of address can be used as-is: blank is OK (that means the default), otherwise it must be unchanged after sanitizing (no characters dropped or truncated). The UI shows a hint based on this. */
    fun nicknameAccepted(raw: String): Boolean = raw.isBlank() || nickname(raw) == raw.trim().replace(Regex("\\s+"), " ")

    private val NICKNAME_MARKS = setOf('-'.code, '·'.code, '・'.code, 0x200C)
    private val COMBINING = setOf(Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK)

    /** The lines corresponding to the options; empty string when everything is default. The sentences belong to the code itself and are not sanitized. */
    internal fun styleLines(style: PersonaStyle): String =
        style.keys().joinToString("\n") { key -> "- " + checkNotNull(styleText[key]) { "missing persona style $key" } }

    /** All keys in style.md (for tests to check: every option value has a sentence, and there are no extras). */
    internal val styleKeys: Set<String> get() = styleText.keys

    /** The name goes into the `name="…"` attribute: first strip newlines, control characters and bidi control characters, limit the length, then escape, so no truncated entity is left inside the attribute. */
    internal fun escapeName(name: String): String = name.trim()
        .replace(NEWLINES, " ")
        .filterNot { it.isISOControl() }
        .replace(BIDI, "")
        .take(MAX_NAME)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** Body text: remove control characters and bidi control characters, escape tag-like text, then truncate safely by token count. */
    internal fun cleanBody(body: String): String {
        val text = body.trim()
            .filterNot { it.isISOControl() && it != '\n' && it != '\t' }
            .replace(BIDI, "")
            .replace(TAG_LIKE) { m -> m.value.replace(Regex("[<\uFF1C]"), "&lt;").replace(Regex("[>\uFF1E]"), "&gt;") }
        return TokenEstimate.clip(text, MAX_PROMPT_TOKENS)
    }
}
