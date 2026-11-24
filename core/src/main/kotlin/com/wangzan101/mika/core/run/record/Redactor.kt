package com.wangzan101.mika.core.run.record

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import com.wangzan101.mika.core.tool.Verb

/**
 * Redaction for run records: text typed into password fields and strings that look like API keys are always recorded as `***`.
 * When the caller is unsure whether the target is a password field, it should treat it as one (better to record too little than too much).
 */
object Redactor {
    const val MASK = "***"

    private val SK_KEY = Regex("""\bsk-[A-Za-z0-9_\-]{16,}""")
    private val BEARER = Regex("""\bBearer\s+[A-Za-z0-9._~+/=\-]{8,}""")
    private val GOOGLE_KEY = Regex("""\bAIza[0-9A-Za-z_\-]{30,}""")
    /** Google's new-format auth key: starts with `AQ.`, followed by a long run of base64url characters. */
    private val GOOGLE_AUTH_KEY = Regex("""\bAQ\.[0-9A-Za-z_\-]{20,}""")
    private val GITHUB_TOKEN = Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}""")

    /** [secretField]: the port reports that the target of this input is a password field. */
    /** [verb]: the action performed by the called tool (null for an unrecognized tool). */
    fun args(verb: Verb?, argsJson: String, secretField: Boolean): String {
        if (verb == Verb.MemorySave || verb == Verb.MemoryEdit) return maskFields(argsJson, MEMORY_ARGS)
        if (verb == Verb.Script) return script(argsJson)
        if (verb == Verb.PhoneCommand) return maskFields(argsJson, setOf("command"))
        // The text filtered on when reading notifications is mostly the names of people and chats in them: like the text actually read, it is not recorded
        if (verb == Verb.NoticesRead) return maskFields(argsJson, setOf("query"))
        if (verb == Verb.PhoneRun) return phoneRun(argsJson)
        if (verb != Verb.TypeText || !secretField) return text(argsJson)
        val o = try {
            Json.parseToJsonElement(argsJson) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return MASK // If it cannot be parsed, nothing is recorded at all; dropping information is the lesser evil
        val masked = if ("text" in o) JsonObject(o + ("text" to JsonPrimitive(MASK))) else o
        return text(masked.toString())
    }

    /** The input passed to a conditional phone command may be a password, so everything is masked field by field. */
    private fun phoneRun(argsJson: String): String {
        val o = try { Json.parseToJsonElement(argsJson) as? JsonObject } catch (e: SerializationException) { null } ?: return MASK
        val commands = o["commands"] as? JsonArray ?: return text(argsJson)
        val hidden = JsonArray(commands.map { command ->
            val item = command as? JsonObject ?: return@map command
            if ("text" !in item || item["op"] != JsonPrimitive("type")) item
            else JsonObject(item + ("text" to JsonPrimitive(MASK)))
        })
        return text(JsonObject(o + ("commands" to hidden)).toString())
    }

    /**
     * The source code of web_script: text to be typed by the script may be written in any form (handed to mika.type, stored in a variable first, concatenated),
     * and as with type_text it is unknown whether the target is a password field, so treat it as one: every string literal in the source is masked, keeping only the quote style (template strings keep the position of `${…}`).
     */
    private fun script(argsJson: String): String {
        val o = try { Json.parseToJsonElement(argsJson) as? JsonObject } catch (e: SerializationException) { null } ?: return MASK
        val code = (o["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return text(argsJson)
        val masked = STRING_LITERAL.replace(code) { m ->
            val q = m.value.first()
            val body = m.value.substring(1, m.value.length - 1)
            if (body.isEmpty()) m.value
            else if (q == '`') "`" + TEMPLATE_HOLE.findAll(body).joinToString("") { h -> MASK + STRING_LITERAL.replace(h.value) { "'$MASK'" } } + MASK + "`"
            else "$q$MASK$q"
        }
        return text(JsonObject(o + ("code" to JsonPrimitive(masked))).toString())
    }

    private val NOTICE_TEXT_FIELDS = setOf("chat", "sender", "text")

    /** Before a tool result enters the run record: the contents of memories and notes written by the user when handing back are not recorded (ids and kinds still are). */
    fun result(verb: Verb?, content: JsonObject): JsonObject = when (verb) {
        Verb.MemorySearch -> (content["memories"] as? JsonArray)?.let { arr ->
            JsonObject(content + ("memories" to JsonArray(arr.map { m ->
                (m as? JsonObject)?.let { if ("content" in it) JsonObject(it + ("content" to JsonPrimitive(MASK))) else it } ?: JsonPrimitive(MASK)
            })))
        } ?: content
        // Notification text that was read, and sentences written from it, do not enter the run record (the device keeps it for only two days, so the record must not keep it longer): time, app and item count are still recorded
        Verb.NoticesRead -> JsonObject(content.mapValues { (k, v) ->
            if ((k == "items" || k == "notes") && v is JsonArray) JsonArray(v.map { item ->
                (item as? JsonObject)?.let { o -> JsonObject(o.mapValues { (f, x) -> if (f in NOTICE_TEXT_FIELDS) JsonPrimitive(MASK) else x }) } ?: JsonPrimitive(MASK)
            }) else v
        })
        // The content of the notification-reminder turn (history_read marked with notice) is another app's raw notification text: like what notices_read read, it does not enter the run record
        // (turn number, status and time are still recorded). Without masking, looking that turn up again during the task would leave the notification text in a record that can be exported
        Verb.HistoryRead -> noticeOutcomeMasked(content).let { top ->
            (top["turns"] as? JsonArray)?.let { arr ->
                JsonObject(top + ("turns" to JsonArray(arr.map { t -> (t as? JsonObject)?.let(::noticeOutcomeMasked) ?: t })))
            } ?: top
        }
        Verb.HandToUser -> if ("userNote" in content) JsonObject(content + ("userNote" to JsonPrimitive(MASK))) else content
        Verb.PhoneCommand -> if ("output" in content) JsonObject(content + ("output" to JsonPrimitive(MASK))) else content
        else -> content
    }

    private fun noticeOutcomeMasked(o: JsonObject): JsonObject =
        if ((o["notice"] as? JsonPrimitive)?.booleanOrNull == true && "outcome" in o) JsonObject(o + ("outcome" to JsonPrimitive(MASK))) else o

    private fun maskFields(argsJson: String, fields: Set<String>): String {
        val o = try {
            Json.parseToJsonElement(argsJson) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return MASK
        return text(JsonObject(o.mapValues { (k, v) -> if (k in fields) JsonPrimitive(MASK) else v }).toString())
    }

    private val STRING_LITERAL = Regex("""'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"|`(?:\\.|[^`\\])*`""")
    /** `${…}` inside a template string: the expression is not a literal, so its position is kept and the text around it is masked. */
    private val TEMPLATE_HOLE = Regex("""\$\{[^}]*\}""")
    private val MEMORY_ARGS = setOf("content", "quote", "keywords")

    /** Words for one-time codes and the like. */
    private val CODE_WORD = Regex("""验证|校验码|动态码|动态密码|确认码|安全码|短信|口令|(?i:\b(?:code|otp|passcode|pin|2fa)\b)""")
    /** 4–8 digits, optionally separated by single spaces or hyphens ("4 8 2 9 1 3", "482-913"). */
    private val CODE_DIGITS = Regex(
        """(?<![\d.¥$€£])(?<!\d,)(?<![¥$€£] )\d(?:[ \-]?\d){3,7}(?!\d)(?!\.\d)(?!,\d{3}(?!\d))""" +
            // Followed by a unit, a year or a currency, it is a quantity, not a code
            """(?!\s*(?:年|元|块|角|分钟|小时|秒|天|月|日|号|次|个|人|件|条|米|公里|千米|克|公斤|千克|斤|吨|度|℃|°|%|‰|[¥$€£]|(?i:km|kg|mb|gb|kb|ms|cm|mm|mah|rmb|usd|cny)\b))""",
    )
    /** At most this many characters may separate a code and such a word (counting either side). */
    private const val CODE_WINDOW = 24

    /**
     * Material for the evidence ledger (screen reads, web pages, command output) passes through this before entering the ledger: besides the keys [text] recognizes, one-time codes are masked too —
     * 4–8 digits with a word like "verification", "SMS" or "code" within [CODE_WINDOW] characters before or after. Text typed into password fields never enters screen text in the first place.
     */
    fun evidence(s: String): String {
        val t = text(s)
        val words = CODE_WORD.findAll(t).map { it.range }.toList()
        if (words.isEmpty()) return t
        return CODE_DIGITS.replace(t) { m ->
            // "Exit code 0:" on one line followed by a quantity on the next is not a one-time code: a match only counts
            // when the word and the digits are on the same line (the window is a character distance, not a token one).
            val near = words.any { w ->
                val forward = m.range.first - w.last
                val backward = w.first - m.range.last
                (forward in 1..CODE_WINDOW + 1 && '\n' !in t.substring(w.last + 1, m.range.first)) ||
                    (backward in 1..CODE_WINDOW + 1 && '\n' !in t.substring(m.range.last + 1, w.first))
            }
            if (near) MASK else m.value
        }
    }

    fun text(s: String): String {
        var out = s
        for (re in listOf(SK_KEY, GOOGLE_KEY, GOOGLE_AUTH_KEY, GITHUB_TOKEN)) out = re.replace(out, MASK)
        return BEARER.replace(out, "Bearer $MASK")
    }

    // ---------- Web network data ----------

    data class Network(val url: String?, val body: String?, val headers: Map<String, String>?)

    /**
     * Web network data (URL, response body, headers) always passes through this before leaving the network log: what is handed to the model, what enters verification evidence and what enters the run record are the same copy.
     * Query parameters, JSON/form fields and header values whose names look like credentials are recorded as `***` (judged word by word: author and keyword do not count);
     * code and state in OAuth redirects are masked as well; so are JWTs, long mixed strings (upper- and lowercase letters plus digits) and the keys [text] recognizes.
     */
    fun network(url: String? = null, body: String? = null, headers: Map<String, String>? = null): Network = Network(
        url = url?.let { values(maskUrl(it), slash = false) },
        body = body?.let { values(maskBody(it), slash = true) },
        headers = headers?.mapValues { (k, v) -> if (secretName(k)) MASK else values(v, slash = true) },
    )

    /**
     * Data (JSON) read off a page by read scripts and site commands: the same rules as network response bodies (fields with credential-like names, JWTs, long mixed strings, keys),
     * except that long strings are not recognized across slashes (results often contain URLs, so each path segment is looked at separately).
     */
    fun pageData(s: String): String = values(maskBody(s), slash = false)

    private val SECRET_WORDS = setOf(
        "token", "tokens", "secret", "password", "passwd", "pwd", "passcode", "session", "sessionid", "sessid", "sid",
        "auth", "authorization", "csrf", "xsrf", "signature", "sig", "otp", "cookie", "credential", "credentials",
        "apikey", "jwt", "bearer", "pin", "privatekey",
    )
    private val SECRET_COMPOUNDS = setOf("apikey", "accesskey", "secretkey", "privatekey", "clientsecret", "sessionkey", "authkey")
    private val CASE_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
    private val WORD_SPLIT = Regex("[^A-Za-z0-9]+")

    /** Whether the name, split into words (camel case, underscores, hyphens), contains a credential-like word. */
    internal fun secretName(name: String): Boolean {
        val words = name.split(WORD_SPLIT).flatMap { it.split(CASE_BOUNDARY) }.map { it.lowercase() }.filter { it.isNotEmpty() }
        return words.any { it in SECRET_WORDS } || words.joinToString("") in SECRET_COMPOUNDS
    }

    private val OAUTH_MARKERS = setOf("state", "redirect_uri", "client_id", "code_verifier")
    private val OAUTH_RESULTS = setOf("code", "state")

    /** `a=b&c=d` in a query string or fragment: the values of credential-like names are masked; for ones that look like an OAuth redirect (has state etc. and carries code or a credential), code and state are masked too. [bareKey]: a lone `key` also counts as a credential. */
    private fun maskParams(q: String, bareKey: Boolean): String {
        val parts = q.split('&')
        val names = parts.map { it.substringBefore('=').lowercase() }
        val oauth = names.any { it in OAUTH_MARKERS } && (names.contains("code") || names.any { secretName(it) })
        return parts.joinToString("&") { p ->
            val eq = p.indexOf('=')
            if (eq <= 0) return@joinToString p
            val name = p.substring(0, eq)
            val n = name.lowercase()
            val hide = secretName(name) || (bareKey && n == "key") || (oauth && n in OAUTH_RESULTS)
            if (hide) "$name=$MASK" else p
        }
    }

    private fun maskUrl(u: String): String {
        val hash = u.indexOf('#')
        val beforeHash = if (hash >= 0) u.substring(0, hash) else u
        val fragment = if (hash >= 0) u.substring(hash + 1) else null
        val q = beforeHash.indexOf('?')
        val base = if (q >= 0) beforeHash.substring(0, q + 1) + maskParams(beforeHash.substring(q + 1), bareKey = true) else beforeHash
        return if (fragment == null) base else base + "#" + if ('=' in fragment) maskParams(fragment, bareKey = true) else fragment
    }

    private val JSON_PAIR = Regex(""""((?:[^"\\]|\\.){1,100})"(\s*:\s*)("(?:[^"\\]|\\.)*"|-?\d[\d.eE+\-]*|true|false|null)""")
    private val FORM = Regex("""^[^\s{}\[\]"<>]+=[^\s]*$""")

    private fun maskBody(b: String): String {
        if (FORM.matches(b)) return maskParams(b, bareKey = false)
        return JSON_PAIR.replace(b) { m ->
            if (secretName(m.groupValues[1])) "\"${m.groupValues[1]}\"${m.groupValues[2]}\"$MASK\"" else m.value
        }
    }

    private val JWT = Regex("""\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}""")
    private val LONG_WITH_SLASH = Regex("""[A-Za-z0-9_\-+/=]{32,}""")
    private val LONG = Regex("""[A-Za-z0-9_\-+=]{32,}""")

    /** JWTs, long mixed strings and known-format keys inside values. In URLs they are not recognized across slashes (each path segment is looked at separately). */
    private fun values(s: String, slash: Boolean): String {
        var out = JWT.replace(s, MASK)
        out = (if (slash) LONG_WITH_SLASH else LONG).replace(out) { m ->
            val v = m.value
            if (v.any(Char::isUpperCase) && v.any(Char::isLowerCase) && v.any(Char::isDigit)) MASK else v
        }
        return text(out)
    }
}
