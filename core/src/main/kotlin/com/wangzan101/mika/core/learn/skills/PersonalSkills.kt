package com.wangzan101.mika.core.learn.skills

/** pending: a draft; trial: accepted but nothing has been got done with it yet; active: something has been got done with it (see LearnGate); suspended: has not been working lately. */
@Suppress("EnumEntryName")
enum class SkillStatus { active, pending, suspended, trial }

/**
 * The user's own skills: distilled from tasks that got done and accepted by the user (or drafts awaiting acceptance).
 * [markdown] is the whole SKILL.md (including the front matter); [replacesDraftOf] is the name of the existing skill an "update" draft points at;
 * [outcomes] are the results of the most recent uses (used by the suspension rules).
 */
data class PersonalSkill(
    val name: String,
    val markdown: String,
    val status: SkillStatus,
    val learnedFrom: String?,
    val replacesDraftOf: String?,
    val createdAt: Long,
    val outcomes: List<String>,
    /** Fingerprint of the original text as read when the update draft was written; the original must be read again and checked before accepting. */
    val baseHash: String? = null,
    /**
     * Where it came from: [SOURCE_USER] means the user asked for it to be written down during a task (`skill_save`); [SOURCE_IMPORTED] means it was imported from a SKILL.md someone shared
     * (see [SkillImport]); null means it was distilled from a task that got done.
     */
    val source: String? = null,
) {
    val createdByUser: Boolean get() = source == SOURCE_USER
    val imported: Boolean get() = source == SOURCE_IMPORTED

    /** Which state it returns to when restored after being suspended: ones that have not got anything done yet (distilled or written down at the user's request) go back to trial, ones that have go back to active. */
    fun restoredStatus(): SkillStatus =
        if ((learnedFrom != null || createdByUser || imported) && SkillResult.verified.name !in outcomes) SkillStatus.trial else SkillStatus.active

    companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_IMPORTED = "imported"
    }
}

data class SkillRevision(val id: String, val operation: String, val atMs: Long)

/** Storage for personal skills and drafts awaiting acceptance; persistence to disk is implemented by the app layer. */
interface PersonalSkills {
    /** Accepted ones (in use and suspended), excluding drafts. */
    fun list(): List<PersonalSkill>
    fun get(name: String): PersonalSkill?
    fun put(s: PersonalSkill)
    fun remove(name: String)
    /** Read-modify-write one skill, mutually exclusive with other writes (so results recorded concurrently by parallel tasks are not lost); [transform] returning null means no change. Returns the updated skill. */
    fun update(name: String, transform: (PersonalSkill) -> PersonalSkill?): PersonalSkill?

    fun pending(): List<PersonalSkill>
    /** Drafts awaiting acceptance, keyed by draft id (used by the UI when accepting or discarding them). */
    fun drafts(): Map<String, PersonalSkill>
    fun putPending(id: String, s: PersonalSkill)
    /** Accept a draft: it becomes an in-use personal skill (an update draft overwrites [PersonalSkill.replacesDraftOf]); [editedMarkdown] is the full text as edited by the user. Draft not found → null. */
    fun approve(id: String, editedMarkdown: String? = null): PersonalSkill?
    fun reject(id: String)
    fun revisions(name: String): List<SkillRevision> = emptyList()
    fun restore(revision: String): Boolean = false

    companion object {
        /** Version string shown to the model: the first 12 characters of the text fingerprint (read, then edit, passing it along when editing; a mismatch means it was changed in between). */
        fun version(markdown: String): String = fingerprint(markdown).take(12)
        fun fingerprint(markdown: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(markdown.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        /** Skill name: lowercase letters, digits and hyphens; it is also the directory name. */
        val NAME = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

        /** Change the `name:` line in the front matter to [name] (when accepting an update draft with edited text, the header name must match the stored name); returns the input unchanged if there is no front matter. */
        fun withName(markdown: String, name: String): String = withField(markdown, "name", name)

        /** Replace the front matter's [key] line with [value] (add it if missing); duplicate fields are reduced to one; returns the input unchanged if there is no front matter. */
        fun withField(markdown: String, key: String, value: String): String {
            val lines = markdown.replace("\r\n", "\n").split("\n").toMutableList()
            if (lines.firstOrNull()?.trimEnd() != "---") return markdown
            val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: return markdown
            val matches = (1 until end).filter { isField(lines[it], key) }
            if (matches.isEmpty()) lines.add(end, "$key: $value") else {
                lines[matches.first()] = "$key: $value"
                matches.drop(1).asReversed().forEach(lines::removeAt)
            }
            return lines.joinToString("\n")
        }

        /** Remove all [key] lines from the front matter; returns the input unchanged if there is no front matter or no such line. */
        fun withoutField(markdown: String, key: String): String {
            val lines = markdown.replace("\r\n", "\n").split("\n").toMutableList()
            if (lines.firstOrNull()?.trimEnd() != "---") return markdown
            val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: return markdown
            val matches = (1 until end).filter { isField(lines[it], key) }
            if (matches.isEmpty()) return markdown
            matches.asReversed().forEach(lines::removeAt)
            return lines.joinToString("\n")
        }

        /** Accepts whitespace around the key name, same as FrontMatter. */
        private fun isField(line: String, key: String): Boolean {
            val colon = line.indexOf(':')
            return colon > 0 && line.substring(0, colon).trim() == key
        }
    }
}
