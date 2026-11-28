package com.wangzan101.mika.core.self

import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The only way for the model to change Claire's own settings (implemented by the app; the core only knows these keys).
 *
 * What can be changed is decided by the app: **the model and Key, and deleting data, are never offered** — the former amounts to letting the model swap out its own brain and spend the user's money,
 * the latter is irreversible. Everything offered is a reversible preference.
 *
 * When the user has not enabled "allow Claire to change its own settings", this tool is not offered at all, so the model cannot reach it.
 */
interface SelfConfig {
    /** Which items can be changed right now, and what each one's value is. */
    fun options(): List<Option>

    /** Change one item; [value] must be one of [Option.choices]. Returns null on success, otherwise a reason. */
    fun set(key: String, value: String): String?

    /** One setting; [label] is the human-readable name, [value] is the current value, [choices] are the selectable values (empty means free text). */
    data class Option(val key: String, val label: String, val value: String, val choices: List<String> = emptyList())
}

const val SETTINGS_LIST = "settings_list"
const val SETTINGS_SET = "settings_set"

private fun port(ctx: ToolContext): SelfConfig =
    ctx.config.selfConfig ?: error("settings tools are only offered when the user allows it")

/** List the settings Claire can change itself: keys, human-readable names, current values, selectable values. Read-only. */
internal object SettingsList : AgentTool {
    override val verb = Verb.SettingsList
    override val label = "View settings"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe

    override fun available(ctx: ToolContext) = ctx.config.selfConfig != null

    override val def = ToolDef(SETTINGS_LIST, Prompts.tool(SETTINGS_LIST), emptyMap(), emptyList())

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ok {
        putJsonArray("settings") {
            port(ctx).options().forEach { o ->
                addJsonObject {
                    put("key", o.key); put("label", o.label); put("value", o.value)
                    if (o.choices.isNotEmpty()) putJsonArray("choices") { o.choices.forEach { add(it) } }
                }
            }
        }
    }
}

/** Change one of Claire's own settings; both the key and the value must come from [SettingsList]. */
internal object SettingsSet : AgentTool {
    override val verb = Verb.SettingsSet
    override val label = "Change settings"
    override val risk = Risk.Write
    /** Does not touch the screen: it changes Claire's own settings, unrelated to the current task's screen. */
    override val offScreen = true

    override fun available(ctx: ToolContext) = ctx.config.selfConfig != null

    override val def = ToolDef(SETTINGS_SET, Prompts.tool(SETTINGS_SET), mapOf(
        "key" to str("The setting key from settings_list."),
        "value" to str("The new value, one of that setting's choices."),
    ), listOf("key", "value"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val key = a.str("key") ?: return fail("missing_key", "Give the setting key from settings_list.")
        val value = a.str("value") ?: return fail("missing_value", "Give the new value.")
        val problem = port(ctx).set(key, value)
        if (problem != null) return fail("bad_value", problem)
        return ok {
            put("key", key)
            put("value", port(ctx).options().firstOrNull { it.key == key }?.value ?: value)
        }
    }
}
