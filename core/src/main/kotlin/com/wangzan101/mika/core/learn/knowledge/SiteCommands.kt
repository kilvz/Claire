package com.wangzan101.mika.core.learn.knowledge

import com.wangzan101.mika.core.script.JsSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** One argument of a site command; [type] is string / integer / number / boolean. */
@Serializable
data class CommandArg(val name: String, val type: String, val required: Boolean = false, val description: String = "")

/** A site command declaration (the script header); [file] is the path relative to the site directory, `scripts/<name>.js`. */
@Serializable
data class SiteCommandDecl(val name: String, val description: String, val args: List<CommandArg> = emptyList(), val returns: String, val file: String)

/** One runnable site command: the site it belongs to, the declaration, and the script body (after the header). */
data class SiteCommand(val site: String, val decl: SiteCommandDecl, val body: String)

/**
 * Site commands: `scripts/<name>.js` in a site knowledge directory (the ones not listed in scripts/manifest.json), run read-only in the current page's reading world.
 * The file opens with a block comment: the first line is [MARK], then one item per line up to the end of the comment, for example
 * - `name: list_results`
 * - `description: list the entries on the current results page`
 * - `arg: limit integer - at most how many`
 * - `arg: query string required - only those whose title contains this text`
 * - `returns: [{title, url}]`
 *
 * name is the same as the file name; arg may appear on several lines, written as "name type [required] [- description]"; after the header comes the async function body, with the arguments in `args`.
 */
object SiteCommandFile {
    const val MARK = "/* mika-command"
    const val DESCRIPTION_LIMIT = 120
    const val BODY_LIMIT = 8_000
    val NAME = Regex("[a-z][a-z0-9_]{0,39}")
    val TYPES = listOf("string", "integer", "number", "boolean")
    private val ARG = Regex("""^([a-z][A-Za-z0-9_]{0,39})\s+(string|integer|number|boolean)(\s+required)?(?:\s+-\s+(.+))?$""")

    fun isCommand(text: String): Boolean = clean(text).startsWith(MARK)

    /** [decl] is present only when there are no errors; [body] is the script after the header. */
    class Parsed(val decl: SiteCommandDecl?, val body: String, val errors: List<String>)

    private fun clean(text: String) = text.removePrefix("﻿").replace("\r\n", "\n").trimStart()

    /** [file] is the path relative to the site directory (`scripts/<name>.js`). */
    fun parse(file: String, text: String): Parsed {
        val t = clean(text)
        if (!t.startsWith(MARK)) return Parsed(null, "", listOf("the header must start with `$MARK`"))
        val end = t.indexOf("*/")
        if (end < 0) return Parsed(null, "", listOf("the header is not closed with */"))
        val body = t.substring(end + 2).trim()
        val errors = mutableListOf<String>()
        var name: String? = null
        var description: String? = null
        var returns: String? = null
        val args = mutableListOf<CommandArg>()
        for (raw in t.substring(MARK.length, end).lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val i = line.indexOf(':')
            if (i <= 0) { errors += "cannot parse this header line: $line"; continue }
            val value = line.substring(i + 1).trim()
            when (val key = line.substring(0, i).trim()) {
                "name" -> name = value
                "description" -> description = value
                "returns" -> returns = value
                "arg" -> ARG.matchEntire(value)?.let { m ->
                    args += CommandArg(m.groupValues[1], m.groupValues[2], m.groupValues[3].isNotEmpty(), m.groupValues[4].trim())
                } ?: run { errors += "each arg is written \"name type [required] [- description]\", and the type is ${TYPES.joinToString("/")}: $value" }
                else -> errors += "unknown header key $key"
            }
        }
        val base = file.substringAfterLast('/').removeSuffix(".js")
        when {
            name == null -> errors += "missing name"
            !NAME.matches(name) -> errors += "name may only use lowercase letters, digits and underscores, must start with a letter and be at most 40 characters"
            name != base -> errors += "name($name) must match the file name ($base.js)"
        }
        if (description.isNullOrEmpty()) errors += "missing description"
        else if (description.codePointCount(0, description.length) > DESCRIPTION_LIMIT) errors += "description exceeds $DESCRIPTION_LIMIT characters"
        if (returns.isNullOrEmpty()) errors += "missing returns"
        args.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { errors += "duplicate arg $it" }
        if (body.isEmpty()) errors += "the script body is empty"
        if (body.length > BODY_LIMIT) errors += "the script body is ${body.length} characters, over $BODY_LIMIT"
        // A dynamic import would run the module in the reading world's global scope, bypassing the read-only wrapper
        if (JsSource.usesImport(body)) errors += "the script body may not use import (the word may not appear outside strings and comments)"
        val decl = if (errors.isEmpty()) SiteCommandDecl(name!!, description!!, args, returns!!, file) else null
        return Parsed(decl, body, errors)
    }

    /** Checks the arguments the model gave against the declaration: undeclared ones, missing required ones and wrong types return a description of the problem; null when all is fine. */
    fun checkArgs(decl: SiteCommandDecl, args: JsonObject): String? {
        val declared = decl.args.associateBy { it.name }
        args.keys.firstOrNull { it !in declared }?.let {
            return "'$it' is not an argument of ${decl.name}; it takes " + (decl.args.joinToString { a -> a.name }.ifEmpty { "no arguments" })
        }
        for (a in decl.args) {
            val v = args[a.name]
            if (v == null || v is JsonNull) {
                if (a.required) return "${decl.name} needs '${a.name}' (${a.type})"
                continue
            }
            val p = v as? JsonPrimitive ?: return "'${a.name}' must be a ${a.type}"
            val fits = when (a.type) {
                "string" -> p.isString
                "boolean" -> !p.isString && p.booleanOrNull != null
                "integer" -> !p.isString && p.longOrNull != null
                else -> !p.isString && p.doubleOrNull != null
            }
            if (!fits) return "'${a.name}' must be a ${a.type}"
        }
        return null
    }

    /** One line as listed in the site knowledge block. */
    fun line(d: SiteCommandDecl): String {
        val sig = d.args.joinToString(", ") { it.name + (if (it.required) "" else "?") + ": " + it.type }
        val notes = d.args.filter { it.description.isNotEmpty() }.joinToString("; ") { "${it.name} ${it.description}" }
        return "- ${d.name}($sig): ${d.description}. Returns ${d.returns}" + (if (notes.isEmpty()) "" else ". Arguments: $notes")
    }
}
