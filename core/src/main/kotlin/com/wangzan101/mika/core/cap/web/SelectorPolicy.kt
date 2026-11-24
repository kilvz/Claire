package com.wangzan101.mika.core.cap.web

/**
 * A CSS selector handed to the page may not select elements by the value attribute: trying `input[value^="h"]` one character at a time can guess the value of a password field or a hidden field.
 * Checking only the literal text misses cases, so the selector is first restored the way the CSS rules read it into what the browser sees, and is searched after that: comments are removed, escapes are resolved (`v\61 lue` is value),
 * whole quoted sections are dropped (attribute names are not written inside quotes, and a comment marker or `[value` inside quotes does not count either); an attribute name may carry a namespace prefix (`*|value`, `|value`).
 * When restoring, it is better to over-detect: a bracket produced by an escape (the class name `.\5b value`) also counts as selecting by value.
 * Anything containing `url(` always counts as selecting by value: after url( without quotes everything up to `)` is a single token, and inside it comment markers and quotes start neither a comment nor a string,
 * so the restoration above does not match; `:is()` drops only the malformed item, and a later `[value` still takes effect. Selectors have no real use for it anyway.
 * web_script's mika.query checks in Kotlin ([selectsByValue]); the lookup methods of read scripts are called in the page and use the JS version of the same rules ([JS]).
 */
object SelectorPolicy {
    fun selectsByValue(selector: String): Boolean = plain(selector).let { URL_FUNCTION.containsMatchIn(it) || BY_VALUE.containsMatchIn(it) }

    /** `[`, an optional namespace prefix, then value, with no further name character after it. Only CSS whitespace counts (space, tab, newline), matching the JS version. */
    private val BY_VALUE = Regex("""\[[ \t\n]*(?:[^ \t\n\[\]|]*[ \t\n]*\|[ \t\n]*)?value(?![\w\x{80}-\x{10FFFF}-])""", RegexOption.IGNORE_CASE)

    /** `url(` appears after restoration (the name may be spelled out with escapes): CSS starts a url token here. */
    private val URL_FUNCTION = Regex("""url\(""", RegexOption.IGNORE_CASE)

    /** Restore it the way CSS reads it: newlines are normalized to \n first; a comment becomes one space; a whole quoted section becomes `""`; an escape becomes the character it stands for. */
    internal fun plain(selector: String): String {
        val s = selector.replace("\r\n", "\n").replace('\r', '\n').replace('\u000C', '\n')
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                    val end = s.indexOf("*/", i + 2)
                    i = if (end < 0) s.length else end + 2
                    out.append(' ')
                }
                c == '"' || c == '\'' -> {
                    i++
                    // Inside quotes: a backslash plus newline continues the line, other escapes are consumed as usual; an unescaped newline ends the section there (the browser behaves the same way)
                    while (i < s.length && s[i] != c && s[i] != '\n') {
                        i = when {
                            s[i] != '\\' -> i + 1
                            i + 1 < s.length && s[i + 1] == '\n' -> i + 2
                            else -> escape(s, i).second
                        }
                    }
                    if (i < s.length && s[i] == c) i++
                    out.append("\"\"")
                }
                c == '\\' && (i + 1 >= s.length || s[i + 1] != '\n') -> {
                    val (ch, next) = escape(s, i)
                    out.append(ch)
                    i = next
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** There is a backslash at [i]: returns the escaped character and the next position. 1-6 hex digits (one immediately following whitespace counts as part of the escape), otherwise the next character itself. */
    private fun escape(s: String, i: Int): Pair<String, Int> {
        if (i + 1 >= s.length) return "\uFFFD" to s.length
        var j = i + 1
        while (j < s.length && j - i <= 6 && s[j].isHex()) j++
        if (j == i + 1) {
            val cp = s.codePointAt(j)
            return String(Character.toChars(cp)) to j + Character.charCount(cp)
        }
        val n = s.substring(i + 1, j).toInt(16)
        val ch = if (n == 0 || n in 0xD800..0xDFFF || n > 0x10FFFF) "\uFFFD" else String(Character.toChars(n))
        return ch to if (j < s.length && s[j] in " \t\n") j + 1 else j
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** The JS version: defines `selectsByValue(selectorString)` in the page, matching the code above line by line. */
    val JS: String = """
  const selectsByValue = (() => {
    const HEX = /^[0-9a-fA-F]${'$'}/;
    const escape = (s, i) => {
      if (i + 1 >= s.length) return ['\ufffd', s.length];
      let j = i + 1;
      while (j < s.length && j - i <= 6 && HEX.test(s[j])) j++;
      if (j === i + 1) { const cp = s.codePointAt(j); return [String.fromCodePoint(cp), j + (cp > 0xffff ? 2 : 1)]; }
      const n = parseInt(s.slice(i + 1, j), 16);
      const ch = n === 0 || (n >= 0xd800 && n <= 0xdfff) || n > 0x10ffff ? '\ufffd' : String.fromCodePoint(n);
      return [ch, j < s.length && ' \t\n'.includes(s[j]) ? j + 1 : j];
    };
    const plain = (raw) => {
      const s = raw.replace(/\r\n?|\f/g, '\n');
      let out = '', i = 0;
      while (i < s.length) {
        const c = s[i];
        if (c === '/' && s[i + 1] === '*') { const end = s.indexOf('*/', i + 2); i = end < 0 ? s.length : end + 2; out += ' '; }
        else if (c === '"' || c === "'") {
          i++;
          while (i < s.length && s[i] !== c && s[i] !== '\n') i = s[i] !== '\\' ? i + 1 : s[i + 1] === '\n' ? i + 2 : escape(s, i)[1];
          if (s[i] === c) i++;
          out += '""';
        } else if (c === '\\' && s[i + 1] !== '\n') { const [ch, next] = escape(s, i); out += ch; i = next; }
        else { out += c; i++; }
      }
      return out;
    };
    const BY_VALUE = /\[[ \t\n]*(?:[^ \t\n\[\]|]*[ \t\n]*\|[ \t\n]*)?value(?![\w\u0080-\uffff-])/i;
    const URL_FUNCTION = /url\(/i;
    return (s) => { const p = plain(s); return URL_FUNCTION.test(p) || BY_VALUE.test(p); };
  })();
"""
}
