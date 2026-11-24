package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.learn.memory.SensitiveFilter
import java.text.Normalizer

/**
 * The two things that can be checked literally in the short text produced by summarization and persisted (topic names, contact display names): looking like a URL, and containing a long digit run (tracking numbers, phone numbers, verification codes and the like).
 * Both checks run on text normalized to consistent full-width/half-width with NFKC ([nfkc]), so full-width spellings cannot slip past.
 */
internal object LearnText {
    // Only the listed TLDs are recognized, so ordinary names such as Node.js, Vue.js and README.md are not taken for URLs. Domains not listed therefore still are not recognized;
    // ones that are themselves common words, common person names or company-name suffixes (to, in, id, de, us, one, wang, au, page, art, ltd…) are deliberately left out:
    // "Mr.Wang" and "Co.Ltd" are not URLs
    private const val TLDS =
        "com|cn|net|org|io|co|me|app|top|xyz|info|cc|tv|ly|gl|link|site|shop|vip|ink|ai|" +
            "dev|edu|gov|mil|int|tech|club|online|store|live|pro|mobi|biz|cloud|work|fun|icu|red|asia|wiki|news|email|" +
            "uk|hk|tw|jp|kr|fr|ru|eu|ca|br|mx|sg"
    private val URL_LIKE = Regex(
        "(?:[a-z][a-z0-9+.-]*://|www\\.|[a-z0-9-]+\\.(?:$TLDS)(?![a-z0-9]))",
        RegexOption.IGNORE_CASE,
    )

    fun nfkc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)

    fun urlLike(nfkc: String): Boolean = URL_LIKE.containsMatchIn(nfkc)

    /** Has a digit run longer than [LearnTuning.DIGIT_RUN_MAX], or a number written in separate groups ([groupedEnd]). */
    fun longDigits(nfkc: String): Boolean = longestDigitRun(nfkc) > LearnTuning.DIGIT_RUN_MAX || hasGrouped(nfkc)

    private val URL_SPAN = Regex(
        "(?:[a-z][a-z0-9+.-]*://\\S+|www\\.\\S+|[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:$TLDS)(?![a-z0-9])(?:/\\S*)?)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Before a summarized sentence (longer than a topic name and possibly containing amounts and dates) is persisted: an entire URL is replaced with "…", a digit run longer than [maxDigits]
     * (tracking number, card number, phone number) is likewise replaced with "…", and a number written in separate groups ("138 1234 5678", see [groupedEnd]) is replaced entirely with "…";
     * short numbers (amounts, times of day, day of month) and dates are kept. NFKC full-width/half-width normalization comes first.
     */
    fun maskLinksAndLongNumbers(s: String, maxDigits: Int): String {
        val text = URL_SPAN.replace(nfkc(s), "…")
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!Character.isDigit(cp)) {
                out.appendCodePoint(cp)
                i += Character.charCount(cp)
                continue
            }
            val date = dateEnd(text, i)
            if (date > 0) {
                out.append(text, i, date)
                i = date
                continue
            }
            val grouped = groupedEnd(text, i, maxDigits)
            if (grouped > 0) {
                out.append('…')
                i = grouped
                continue
            }
            var j = i
            var digits = 0
            while (j < text.length && Character.isDigit(text.codePointAt(j))) {
                j += Character.charCount(text.codePointAt(j))
                digits++
            }
            if (digits > maxDigits) out.append('…') else out.append(text, i, j)
            i = j
        }
        return out.toString()
    }

    /**
     * Whether text produced by summarization and to be persisted (sentences, topic names, contact display names) passes the sensitive-information check: beyond the main program's [SensitiveFilter],
     * login-credential wordings it does not recognize are checked as well ([authCode]). A hit rejects the whole entry. [full] is the cleaned, untruncated whole.
     */
    fun sensitive(full: String): Boolean = SensitiveFilter.hit(full) != null || authCode(nfkc(full))

    // the character set does not start with a colon ("[:" may be read as the start of a named character class by Android's regex engine), so the colon goes later
    /**
     * Before a summary's one-line overview is persisted: cleaned into a single line; rejected entirely (null) when it fails the sensitive-information check; URLs and long digit runs masked; truncated to the length limit
     * ([LearnTuning.BRIEF_MAX_CJK] for mostly CJK text, [LearnTuning.BRIEF_MAX] otherwise). Null when empty.
     */
    fun brief(raw: String): String? {
        val full = NoticeFilter.clean(raw, 1_000)
        if (full.isEmpty() || sensitive(full)) return null
        val masked = maskLinksAndLongNumbers(full, LearnTuning.NOTE_DIGIT_RUN_MAX)
        val max = if (mostlyCjk(masked)) LearnTuning.BRIEF_MAX_CJK else LearnTuning.BRIEF_MAX
        // counted by character (code point), not by UTF-16 unit: fifty Chinese characters are fifty
        val end = if (masked.codePointCount(0, masked.length) <= max) masked.length else masked.offsetByCodePoints(0, max)
        return masked.substring(0, end).trim().ifEmpty { null }
    }

    /** CJK characters (Han, kana, Hangul) make up 30% or more of the text. */
    private fun mostlyCjk(s: String): Boolean {
        var cjk = 0
        var total = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!Character.isWhitespace(cp)) {
                total++
                val script = Character.UnicodeScript.of(cp)
                if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
                    script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL) cjk++
            }
            i += Character.charCount(cp)
        }
        return total > 0 && cjk * 10 >= total * 3
    }

    // Digits are always written as \p{Nd} (decimal digits of any script, meaning the same on a desktop JVM as on Android), never \d; whitespace means only spaces and tabs
    // (text has already been cleaned into a single line before it arrives). Case-insensitive matching is not enabled (on the JVM it only covers ASCII by default): the whole text is lowercased first, and all word lists are written in lowercase.
    private const val CODE = "(?:[a-z]{1,3}-)?\\p{Nd}{4,}|\\p{Nd}{3}[ -]\\p{Nd}{3,4}"
    private const val GAP = "[ \t:：=,，是为為叫(（\\[【「『《“\"]{0,4}(?:(?:is|was|are|es|é)[ \t]*[：:]?[ \t]*)?"
    // what follows a password word may be alphanumeric (e.g. a Chinese "password" word followed by abc12345), but it must contain a digit: otherwise, in Latin-script languages, any following word would match
    private const val SECRET = "(?=[a-z0-9]*\\p{Nd})[a-z0-9]{4,}"

    // wordings for "a code used to log in or confirm identity" that [SensitiveFilter] does not recognize. Only this kind is included: pickup codes, redemption codes, invitation codes and postal codes are not credentials and are not listed here.
    private const val AUTH_WORDS =
        "登录码|登陆码|登入码|确认码|认证码|授权码|短信码|随机码|注册码|绑定码|核验码|交易码|提现码|" +
            "驗證碼|校驗碼|動態碼|安全碼|登錄碼|登入碼|確認碼|認證碼|授權碼|簡訊碼|隨機碼|註冊碼|綁定碼|" +
            "認証コード|確認コード|認証番号|暗証番号|セキュリティコード|" +
            "인증번호|인증 ?코드|보안 ?코드|확인 ?코드|" +
            "c[oó]digo de (?:verificaci[oó]n|verificação|seguridad|segurança|confirmaci[oó]n|confirmação|acceso|acesso)|" +
            "код (?:подтверждения|проверки|доступа|авторизации|для входа)|проверочный код|" +
            "kode (?:verifikasi|otp|keamanan|konfirmasi|login|masuk)|" +
            "رمز التحقق|رمز التأكيد|رمز الدخول|کد تایید|کد تأیید|کد ورود"
    // "password" in spellings and languages that [SensitiveFilter] does not recognize
    private const val PASSWORD_WORDS =
        "密碼|ワンタイムパスワード|パスワード|パスコード|비밀번호|contraseña|senha|пароль|kata sandi|كلمة المرور|رمز عبور"
    private val AUTH_THEN_CODE = Regex("(?:$AUTH_WORDS)$GAP(?:$CODE)")
    private val PASSWORD_THEN_SECRET = Regex("(?:$PASSWORD_WORDS)$GAP$SECRET")
    private val CODE_THEN_AUTH =
        Regex("(?<!\\p{Nd})\\p{Nd}{4,}[ \t]*(?:就是|是|为|為)(?:你的|您的|我的)?[ \t]*(?:$AUTH_WORDS|$PASSWORD_WORDS)")
    private val SPACES = Regex("[ \t]+")

    // a bare "code" in English: cases where the preceding word shows it is not a credential (zip code, error code…) do not count. With a colon, equals sign or "is" in between, 4 digits count;
    // separated only by spaces it takes 5 or more — 4 digits are usually a year ("… of Code 2026")
    private val ENGLISH_CODE = Regex(
        "(?<![a-z])codes?(?![a-z])(?:[ \t]*(?:[：:=]|(?:is|was)(?![a-z])[ \t]*[：:]?)[ \t]*(?:$CODE)" +
            "|[ \t]{1,2}(?:(?:[a-z]{1,3}-)?\\p{Nd}{5,}|\\p{Nd}{3}[ -]\\p{Nd}{3,4}))",
    )
    private val CODE_IS_YOUR = Regex("(?<!\\p{Nd})\\p{Nd}{4,} is (?:your|the) (?:[a-z-]+ ){0,3}?code(?![a-z])")
    private val NOT_AUTH = setOf(
        "zip", "postal", "post", "area", "country", "dial", "error", "status", "exit", "return", "response", "http",
        "promo", "promotion", "coupon", "discount", "voucher", "invite", "invitation", "referral",
        "pickup", "collection", "tracking", "booking", "reservation", "order", "product", "item", "model",
        "qr", "bar", "source", "color", "colour", "dress", "course", "class", "room", "gate", "door",
    )

    /**
     * Whether it looks like it carries a login credential: cases such as "login code 482913", "482913 is your login code" and "Your code is 482913" — a credential wording right next to
     * a code of 4 or more digits (only very short connectors are allowed in between). [nfkc] is text with full-width/half-width normalized. This is only the part that can be checked literally, not a guarantee.
     */
    fun authCode(nfkc: String): Boolean {
        val t = nfkc.lowercase()
        if (AUTH_THEN_CODE.containsMatchIn(t) || PASSWORD_THEN_SECRET.containsMatchIn(t) || CODE_THEN_AUTH.containsMatchIn(t)) return true
        // codes with spaces in between ("login code 4 8 2 9 1 3"): check once more with whitespace removed. Only for "code" wordings: the password rule matches alphanumerics after it,
        // and removing whitespace would merge the rest of the sentence into one word ("senha alterada em 12 de …"); the English "code" looks at the preceding word and must not be merged either
        val compact = SPACES.replace(t, "")
        if (AUTH_THEN_CODE.containsMatchIn(compact)) return true
        if (ENGLISH_CODE.findAll(t).any { wordBefore(t, it.range.first) !in NOT_AUTH }) return true
        return CODE_IS_YOUR.findAll(t).any { m ->
            val at = m.value.lastIndexOf("code")
            wordBefore(t, m.range.first + at) !in NOT_AUTH
        }
    }

    /** The English word immediately before [index] (separated by spaces or hyphens); empty string when there is none. */
    private fun wordBefore(s: String, index: Int): String {
        var end = index
        while (end > 0 && (s[end - 1] == ' ' || s[end - 1] == '-' || s[end - 1] == '\t')) end--
        var start = end
        while (start > 0 && s[start - 1] in 'a'..'z') start--
        return s.substring(start, end)
    }

    /** Where the run of digits starting at [i] ends (exclusive); when [i] is not a digit it is [i]. */
    private fun digitsEnd(s: String, i: Int): Int {
        var j = i
        while (j < s.length && Character.isDigit(s.codePointAt(j))) j += Character.charCount(s.codePointAt(j))
        return j
    }

    private fun digitCount(s: String, from: Int, to: Int): Int = s.codePointCount(from, to)

    private fun hyphen(c: Char): Boolean = c == '-' || c == '‐' || c == '‑' || c == '–'

    private fun joiner(c: Char): Boolean = c == ' ' || hyphen(c)

    /**
     * Whether a date starts at [i]: "year-month-day" (four digits, one or two, one or two) or "day-month-year" (one or two, one or two, four), joined by hyphens,
     * with no other digit run immediately adjacent before or after. Returns its end (exclusive) when it is one, -1 otherwise.
     * Dates must be kept and must not be merged with a following time of day into one "number written in groups". Only the shape is checked; the number of days in the month is not.
     */
    private fun dateEnd(s: String, i: Int): Int {
        if (i > 0 && (Character.isDigit(s[i - 1]) || hyphen(s[i - 1]))) return -1
        val a = digitsEnd(s, i)
        if (a >= s.length || !hyphen(s[a])) return -1
        val b = digitsEnd(s, a + 1)
        if (b >= s.length || !hyphen(s[b])) return -1
        val c = digitsEnd(s, b + 1)
        val first = digitCount(s, i, a)
        val mid = digitCount(s, a + 1, b)
        val last = digitCount(s, b + 1, c)
        val shaped = mid in 1..2 && ((first == 4 && last in 1..2) || (first in 1..2 && last == 4))
        if (!shaped) return -1
        if (c + 1 < s.length && hyphen(s[c]) && Character.isDigit(s[c + 1])) return -1
        return c
    }

    /**
     * Whether a number written in separate groups starts at [i] (phone numbers and card numbers are often written this way: "138 1234 5678", "138-1234-5678"): several digit groups each separated by a space
     * or hyphen, adding up to at least [GROUPED_DIGITS_MIN] digits, with at least one group of three or more (a score line or a pair of two-digit numbers does not count), and either three or more groups
     * or one of the two groups already longer than [maxDigits] ("010-12345678": the area code is masked along with it). Two groups that are each short are a range
     * ("15000-25000" as a price range) and are kept. Returns its end (exclusive) when it is one, -1 otherwise. Stops at a date; dates are not merged in.
     */
    private fun groupedEnd(s: String, i: Int, maxDigits: Int): Int {
        var end = digitsEnd(s, i)
        var total = digitCount(s, i, end)
        var widest = total
        var groups = 1
        while (end + 1 < s.length && joiner(s[end]) && Character.isDigit(s.codePointAt(end + 1)) && dateEnd(s, end + 1) < 0) {
            val next = digitsEnd(s, end + 1)
            val n = digitCount(s, end + 1, next)
            total += n
            if (n > widest) widest = n
            groups++
            end = next
        }
        val shaped = groups >= 3 || (groups == 2 && widest > maxDigits)
        return if (shaped && total >= GROUPED_DIGITS_MIN && widest >= 3) end else -1
    }

    /** A number written in groups must add up to at least this many digits: mobile numbers have 11, landlines with an area code 10 or more, card numbers 13 or more; a date with a time of day never reaches it. */
    private const val GROUPED_DIGITS_MIN = 10

    private fun hasGrouped(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!Character.isDigit(cp)) { i += Character.charCount(cp); continue }
            val date = dateEnd(s, i)
            if (date > 0) { i = date; continue }
            if (groupedEnd(s, i, LearnTuning.DIGIT_RUN_MAX) > 0) return true
            i = digitsEnd(s, i)
        }
        return false
    }

    /** The longest run of consecutive digits (digits of any script count; not relying on regex \d, whose meaning differs between a desktop JVM and Android). */
    private fun longestDigitRun(s: String): Int {
        var best = 0
        var run = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            run = if (Character.isDigit(cp)) run + 1 else 0
            if (run > best) best = run
            i += Character.charCount(cp)
        }
        return best
    }
}
