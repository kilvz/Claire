package com.wangzan101.mika.core.script

/**
 * Lexical check of JS source written by the model or by site commands.
 *
 * [usesImport]: whether the word `import` appears outside strings, comments and regex literals (a dynamic import runs a module in the global scope, bypassing the read-only wrapper).
 * Whether a `/` is a division sign or the start of a regex sometimes cannot be told from the lexemes alone (after `)`, `}`, `++`, and after words such as of/yield/await that may be either a keyword or a variable name):
 * in that case both readings are followed, and any reading that sees import inside the code counts as present. Readings are deduplicated by (position, reading of `/`, bracket stack), so they do not multiply;
 * readings that cannot be parsed (a regex or string not closed before the end of the line) are discarded. Better to block by mistake than to miss.
 */
object JsSource {
    private enum class Slash { Regex, Divide, Both }

    private data class State(val pos: Int, val slash: Slash, val stack: List<Char>)

    /** Keywords after which a `/` always starts a regex. */
    private val BEFORE_EXPRESSION = setOf("return", "typeof", "instanceof", "in", "new", "delete", "void", "throw", "case", "do", "else")
    /** Words that may be either a keyword or a variable name: a `/` after them is followed both ways. */
    private val EITHER = setOf("of", "yield", "await", "let", "async", "get", "set", "static")

    fun usesImport(code: String): Boolean {
        val seen = HashSet<State>()
        val work = ArrayDeque<State>()
        work += State(0, Slash.Regex, emptyList())
        while (work.isNotEmpty()) {
            val s = work.removeLast()
            if (!seen.add(s)) continue
            if (Lexer(code, s) { work += it }.run()) return true
        }
        return false
    }

    /** Reads one reading starting at [start]; on an ambiguous `/` it hands both readings to [fork] and stops. Returns true when import is read. */
    private class Lexer(private val code: String, start: State, private val fork: (State) -> Unit) {
        private var i = start.pos
        private var slash = start.slash
        private val stack = start.stack.toMutableList()
        private val n = code.length

        fun run(): Boolean {
            while (i < n) {
                val c = code[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && peek(1) == '/' -> { while (i < n && !lineEnd(code[i])) i++ }
                    c == '/' && peek(1) == '*' -> { val e = code.indexOf("*/", i + 2); i = if (e < 0) n else e + 2 }
                    c == '/' -> when (slash) {
                        Slash.Divide -> { i++; slash = Slash.Regex }
                        Slash.Regex -> { if (!regex()) return false }
                        Slash.Both -> {
                            fork(State(i, Slash.Regex, stack.toList()))
                            fork(State(i, Slash.Divide, stack.toList()))
                            return false
                        }
                    }
                    c == '\'' || c == '"' -> { if (!string(c)) return false; slash = Slash.Divide }
                    c == '`' -> { i++; if (!template()) return false }
                    c == '{' -> { stack += '{'; i++; slash = Slash.Regex }
                    c == '}' -> {
                        i++
                        if (stack.lastOrNull() == '`') { stack.removeAt(stack.lastIndex); if (!template()) return false }
                        else { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex); slash = Slash.Both }
                    }
                    c == ')' -> { i++; slash = Slash.Both }
                    c == ']' -> { i++; slash = Slash.Divide }
                    (c == '+' || c == '-') && peek(1) == c -> { i += 2; slash = Slash.Both }
                    c.isDigit() || (c == '.' && peek(1)?.isDigit() == true) -> { while (i < n && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '_')) i++; slash = Slash.Divide }
                    identStart(c) -> {
                        val word = word()
                        if (word == "import") return true
                        slash = when (word) {
                            in BEFORE_EXPRESSION -> Slash.Regex
                            in EITHER -> Slash.Both
                            else -> Slash.Divide
                        }
                    }
                    else -> { i++; slash = Slash.Regex }
                }
            }
            return false
        }

        private fun peek(k: Int): Char? = code.getOrNull(i + k)
        private fun lineEnd(c: Char) = c == '\n' || c == '\r' || c == ' ' || c == ' '
        private fun identStart(c: Char) = c == '$' || c == '_' || c == '\\' || c.isLetter()
        private fun identPart(c: Char) = identStart(c) || c.isDigit() || c == '‌' || c == '‍'

        /** One identifier; `\uXXXX` and `\u{…}` escapes are decoded before comparing. */
        private fun word(): String {
            val out = StringBuilder()
            while (i < n && identPart(code[i])) {
                if (code[i] == '\\' && peek(1) == 'u') {
                    val braced = peek(2) == '{'
                    val from = i + if (braced) 3 else 2
                    val to = if (braced) code.indexOf('}', from).takeIf { it > 0 } ?: n else minOf(from + 4, n)
                    code.substring(from, to).toIntOrNull(16)?.let { out.appendCodePoint(it) }
                    i = if (braced) minOf(to + 1, n) else to
                } else out.append(code[i++])
            }
            return out.toString()
        }

        /** String literal; false if it is not closed by the end of the line (and is not a line continuation). */
        private fun string(q: Char): Boolean {
            i++
            while (i < n) {
                val c = code[i]
                when {
                    c == '\\' -> i += 2
                    c == q -> { i++; return true }
                    lineEnd(c) -> return false
                    else -> i++
                }
            }
            return false
        }

        /** The text part of a template literal, read from the current position to the backtick (end) or `${` (push onto the stack, back to code). */
        private fun template(): Boolean {
            while (i < n) {
                val c = code[i]
                when {
                    c == '\\' -> i += 2
                    c == '`' -> { i++; slash = Slash.Divide; return true }
                    c == '$' && peek(1) == '{' -> { stack += '`'; i += 2; slash = Slash.Regex; return true }
                    else -> i++
                }
            }
            return false
        }

        /** Regex literal (including `/` inside a character class) and flags; false if it is not closed by the end of the line. */
        private fun regex(): Boolean {
            i++
            var inClass = false
            while (i < n) {
                val c = code[i]
                when {
                    lineEnd(c) -> return false
                    c == '\\' -> i += 2
                    c == '[' -> { inClass = true; i++ }
                    c == ']' -> { inClass = false; i++ }
                    c == '/' && !inClass -> {
                        i++
                        while (i < n && identPart(code[i])) i++
                        slash = Slash.Divide
                        return true
                    }
                    else -> i++
                }
            }
            return false
        }
    }
}
