package com.wangzan101.mika.core.cap.root

/**
 * Whether a root command changes anything: read-only ones run directly, and changing ones ask the user first when asking is required. When in doubt, always treat it as changing:
 * unknown commands, command substitution, file-writing redirections, here-docs, and anything that hands text to another shell or interpreter all count as changing.
 * The command is split the way sh parses it (quotes, escapes, ; && || | & newlines, parentheses); each segment is judged on its own, and only if every segment is read-only is the command read-only.
 */
object RootCommandClass {
    enum class Kind { ReadOnly, Changes }

    /** Confirmation-free commands also use this PATH when run, so bare command names never resolve to a user directory. */
    const val SYSTEM_COMMAND_PATH = "/system/bin"

    fun classify(command: String): Kind {
        val commands = split(command) ?: return Kind.Changes
        if (commands.isEmpty()) return Kind.Changes
        return if (commands.all(::readOnly)) Kind.ReadOnly else Kind.Changes
    }

    /** Commands that only read (no matter how the arguments are written, they write nothing). */
    private val READERS = setOf(
        "ls", "cat", "head", "tail", "wc", "stat", "du", "df", "grep", "egrep", "fgrep", "id", "whoami", "uname", "getprop",
        "ps", "pwd", "echo", "printf", "file", "md5sum", "sha1sum", "sha256sum", "sha512sum", "readlink", "realpath",
        "basename", "dirname", "test", "[", "true", "false", "which", "uptime", "free", "cut", "tr", "strings",
        "od", "hexdump", "cmp", "diff", "base64", "getenforce", "lsof", "netstat", "nproc", "printenv", "cd",
        "sleep", "lsattr", "nl", "column", "comm", "expr", "seq", "rev", "fold", "tac",
    )

    /** dumpsys subcommands that change state (e.g. `dumpsys battery set`). */
    private val DUMPSYS_WRITES = setOf("set", "reset", "unplug", "force-idle", "unforce", "enable", "disable", "step", "whitelist", "tempwhitelist", "put", "delete", "clear")

    /** Read-only subcommands of `pm` and `cmd package`. */
    private val PM_READ = setOf(
        "list", "path", "dump", "resolve-activity", "query-activities", "query-services", "query-receivers",
        "get-install-location", "get-max-users", "get-max-running-users", "has-feature", "get-app-links", "get-privapp-permissions",
    )

    /** Read-only PRAGMAs in sqlite3 (the read form without =). */
    private val PRAGMA_READ = setOf(
        "table_info", "table_xinfo", "table_list", "index_list", "index_info", "index_xinfo", "foreign_key_list", "database_list",
        "user_version", "schema_version", "application_id", "page_count", "page_size", "integrity_check", "quick_check",
        "compile_options", "encoding", "journal_mode", "function_list", "collation_list", "freelist_count",
    )

    /** Read-only sqlite3 dot commands. */
    private val SQLITE_DOT_READ = setOf(".tables", ".schema", ".indexes", ".indices", ".headers", ".mode", ".dump", ".databases", ".fullschema", ".show", ".width", ".nullvalue", ".separator")

    private val SQLITE_FLAGS = setOf(
        "-header", "-noheader", "-csv", "-json", "-line", "-list", "-column", "-html", "-box", "-table", "-markdown", "-readonly",
        "-batch", "-bail", "-echo", "-quote", "-tabs", "-ascii",
    )
    private val SQLITE_OPTS_WITH_VALUE = setOf("-separator", "-newline", "-nullvalue")

    private fun readOnly(words: List<String>): Boolean {
        val assignments = words.takeWhile(ASSIGNMENT::matches)
        // Variables such as PATH and LD_PRELOAD can change which program actually runs. Only common locale settings are allowed.
        if (assignments.any { it.substringBefore('=') !in LOCALE_ASSIGNMENTS }) return false
        val rest = words.drop(assignments.size)
        if (rest.isEmpty()) return true
        var name = rest[0]
        var args = rest.drop(1)
        // The command name is a variable or assembled with quotes: cannot be determined
        if ('$' in name) return false
        name = name.removePrefix("$SYSTEM_COMMAND_PATH/")
        if ('/' in name) return false
        if ((name == "toybox" || name == "busybox") && args.isNotEmpty()) {
            name = args[0]
            args = args.drop(1)
        }
        return when (name) {
            in READERS -> true
            "dumpsys" -> args.none { it in DUMPSYS_WRITES }
            // date -s changes the time; sort -o writes a file; uniq's second file is the output
            "date" -> args.none { it.startsWith("-s") || it.startsWith("--set") }
            "sort" -> args.none { (it.startsWith("-") && !it.startsWith("--") && 'o' in it) || it.startsWith("--output") }
            "uniq" -> args.count { !it.startsWith("-") } <= 1
            "find" -> args.none { it in FIND_WRITES }
            // A sed script can write files and execute subcommands, and -f reads another script; no incomplete after-the-fact guessing, so it always goes through confirmation
            "logcat" -> logcatReadOnly(args)
            "dmesg" -> args.none { it in DMESG_WRITES }
            "mount" -> args.isEmpty()
            "env" -> args.isEmpty()
            "pm" -> args.firstOrNull() in PM_READ
            "cmd" -> args.firstOrNull() == "package" && args.getOrNull(1) in PM_READ
            "settings" -> args.dropWhile { it.startsWith("--") || it.all(Char::isDigit) }.firstOrNull().let { it == "get" || it == "list" }
            "sqlite3" -> sqliteReadOnly(args)
            else -> false
        }
    }

    private val ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")
    private val LOCALE_ASSIGNMENTS = setOf("LANG", "LC_ALL")
    private val FIND_WRITES = setOf("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls")
    private val LOGCAT_READ_FLAGS = setOf("-d", "-D", "-h", "-L", "-g", "-S", "-p", "-s", "-B", "--help", "--last", "--statistics")
    private val LOGCAT_READ_LONG_VALUES = setOf("--buffer", "--format", "--max-count", "--pid", "--regex", "--tail", "--uid")
    private val LOGCAT_READ_VALUES = LOGCAT_READ_LONG_VALUES + setOf("-b", "-e", "-m", "-t", "-T", "-v")
    private val LOGCAT_FILTER = Regex("[A-Za-z0-9_.][A-Za-z0-9_.:-]*")
    private val DMESG_WRITES = setOf("-c", "-C", "-n", "--clear", "--console-level", "--read-clear")

    /** Short options can be combined, and values can directly follow an option. Only the explicitly listed read-only forms are accepted; anything else goes through confirmation. */
    private fun logcatReadOnly(args: List<String>): Boolean {
        var i = 0
        while (i < args.size) {
            val arg = args[i++]
            when {
                arg == "--" -> return true // What follows is a filter expression; getopt no longer parses options.
                arg in LOGCAT_READ_FLAGS || LOGCAT_FILTER.matches(arg) -> Unit
                arg in LOGCAT_READ_VALUES -> {
                    val value = args.getOrNull(i++) ?: return false
                    // When an argument expands into several words, the extra words may become new options.
                    if (value.isEmpty() || value.any { it in "*?[{" }) return false
                }
                '=' in arg && arg.substringBefore('=') in LOGCAT_READ_LONG_VALUES -> {
                    if (arg.substringAfter('=').any { it in "*?[{" }) return false
                }
                else -> return false
            }
        }
        return true
    }

    /** sqlite3 [options] dbfile SQL...: read-only only if every SQL statement is read-only. Missing SQL (which would be read from stdin) and unknown options count as changing. */
    private fun sqliteReadOnly(args: List<String>): Boolean {
        val sql = mutableListOf<String>()
        var db: String? = null
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                db == null && a in SQLITE_FLAGS -> Unit
                db == null && a in SQLITE_OPTS_WITH_VALUE -> i++
                db == null && a == "-cmd" -> { sql += args.getOrNull(i + 1) ?: return false; i++ }
                db == null && a.startsWith("-") -> return false
                db == null -> db = a
                else -> sql += a
            }
            i++
        }
        if (db == null || sql.isEmpty()) return false
        val statements = sql.flatMap { it.split(';') }.map { it.trim() }.filter { it.isNotEmpty() }
        return statements.isNotEmpty() && statements.all(::sqlReadOnly)
    }

    private fun sqlReadOnly(s: String): Boolean {
        val lower = s.lowercase()
        if (lower.startsWith(".")) return lower.split(Regex("\\s+")).first() in SQLITE_DOT_READ
        val first = lower.split(Regex("[\\s(]+")).first()
        return when (first) {
            "select", "explain" -> WRITE_SQL.none { Regex("\\b$it\\b").containsMatchIn(lower) }
            "pragma" -> '=' !in lower && lower.removePrefix("pragma").trim().substringBefore('(').trim().substringAfter('.') in PRAGMA_READ
            else -> false
        }
    }

    /** Words that count as changing even when mixed into a SELECT (write statements hidden in a clause, loading extensions). */
    private val WRITE_SQL = listOf("insert", "update", "delete", "replace", "drop", "create", "alter", "attach", "detach", "vacuum", "reindex", "load_extension", "writefile", "edit")

    /**
     * Splits the command the way sh parses it into simple commands (each a list of words with quotes removed). Returns null for constructs that cannot be determined (treated as changing):
     * command substitution, process substitution, here-docs, read-write opens, redirections to anything other than /dev/null, and unterminated quotes.
     */
    private fun split(command: String): List<List<String>>? {
        val commands = mutableListOf<List<String>>()
        var words = mutableListOf<String>()
        val word = StringBuilder()
        var inWord = false
        var single = false
        var double = false
        var i = 0
        fun endWord() {
            if (inWord) words += word.toString()
            word.clear()
            inWord = false
        }
        fun endCommand() {
            endWord()
            if (words.isNotEmpty()) commands += words
            words = mutableListOf()
        }
        while (i < command.length) {
            val c = command[i]
            val next = command.getOrNull(i + 1)
            when {
                single -> if (c == '\'') single = false else word.append(c)
                c == '\\' && next != null -> { word.append(next); inWord = true; i++ }
                c == '`' -> return null
                // Any unescaped $ outside single quotes is left for the root shell to expand; static classification cannot see the expanded arguments
                c == '$' -> return null
                double -> when (c) {
                    '"' -> double = false
                    else -> word.append(c)
                }
                c == '\'' -> { single = true; inWord = true }
                c == '"' -> { double = true; inWord = true }
                c == '>' || (c == '&' && next == '>') -> {
                    // A digit directly before it is a file descriptor (2>...)
                    if (inWord && word.all(Char::isDigit)) { word.clear(); inWord = false } else endWord()
                    i += if (c == '&') 2 else 1
                    if (command.getOrNull(i) == '>' || command.getOrNull(i) == '|') i++
                    // >&2 and >&- duplicate or close a descriptor and do not write a file
                    if (command.getOrNull(i) == '&') {
                        i++
                        val fd = command.drop(i).takeWhile { it.isDigit() || it == '-' }
                        if (fd.isEmpty()) return null
                        i += fd.length
                        continue
                    }
                    while (command.getOrNull(i)?.isWhitespace() == true) i++
                    val target = command.drop(i).takeWhile { !it.isWhitespace() && it !in ";&|()<>" }
                    if (target != "/dev/null") return null
                    i += target.length
                    continue
                }
                c == '<' -> {
                    if (inWord && word.all(Char::isDigit)) { word.clear(); inWord = false } else endWord()
                    // here-doc, read-write open, process substitution
                    if (next == '<' || next == '>' || next == '(') return null
                    i++
                    while (command.getOrNull(i)?.isWhitespace() == true) i++
                    val target = command.drop(i).takeWhile { !it.isWhitespace() && it !in ";&|()<>" }
                    if (target.isEmpty() || '$' in target) return null
                    i += target.length
                    continue
                }
                c in ";&|\n()" -> endCommand()
                c.isWhitespace() -> endWord()
                else -> { word.append(c); inWord = true }
            }
            i++
        }
        if (single || double) return null
        endCommand()
        // { and } only group several commands
        return commands.map { w -> w.filter { it != "{" && it != "}" } }.filter { it.isNotEmpty() }
    }
}
