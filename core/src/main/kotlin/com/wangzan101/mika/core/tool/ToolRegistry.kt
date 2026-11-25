package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Prompts

/**
 * The tools of this task: [all] is every tool that capabilities and the core may offer (in the order sent to the model); what this task can call is filtered by configuration.
 * Each call finds a tool by name and then executes it; the dispatcher knows no concrete tool. [groupOf] gives the group a tool belongs to (see [ToolGroups]; always null).
 */
internal class ToolRegistry(
    private val ctx: ToolContext,
    private val all: List<AgentTool>,
    private val groupOf: (AgentTool) -> String? = { null },
    private val dynamicCapabilities: Set<String> = emptySet(),
) {
    private val declared: Map<String, AgentTool> = all.associateBy { it.name }
    private fun dynamic(tool: AgentTool) = tool.capability in dynamicCapabilities
    private val tools: Map<String, AgentTool> = all.filter { !dynamic(it) && it.available(ctx) }.associateBy { it.name }

    init {
        check(declared.size == all.size) { "duplicate tool names" }
    }

    operator fun get(name: String): AgentTool? = declared[name]?.let { tool ->
        if (dynamic(tool)) tool.takeIf { it.available(ctx) } else tools[name]
    }

    /** Looks up a tool's declaration by name (risk, judged target, ...); also finds tools this task does not offer. null when unknown. */
    fun declared(name: String): AgentTool? = declared[name]

    private data class RegisteredSlot(val tool: AgentTool, val slot: ToolGroups.Slot)

    /**
     * Ordinary tools freeze their definition the first time the catalog is taken; dynamic capabilities also freeze their immutable definition text, but are filtered against the current ports every time.
     * An entry's short description lives in prompts/entries/<tool name>.md; its parameters are the same as in the full text.
     */
    private val frozen: List<RegisteredSlot> by lazy {
        all.filter { dynamic(it) || tools.containsKey(it.name) }
            .filter { dynamic(it) || it.offered(ctx) }
            .map { t ->
                val d = t.def(ctx)
                RegisteredSlot(t, ToolGroups.Slot(
                    d.spec(),
                    t.entry?.let { ToolDef(d.name, Prompts.entry(d.name), d.props, d.required).spec() },
                    groupOf(t),
                ))
            }
    }

    private fun currentSlots(): List<ToolGroups.Slot> = frozen.filter { registered ->
        !dynamic(registered.tool) || registered.tool.available(ctx) && registered.tool.offered(ctx)
    }.map { it.slot }

    /** Every tool this task offers right now (every group expanded, full text). */
    fun definitions(): List<ToolSpec> = currentSlots().map { it.full }

    /** The tools sent to the model right now: only the groups in [expanded] are expanded (see [ToolGroups.layout]). */
    fun layered(expanded: Set<String>, extra: Map<String, List<ToolSpec>>): List<ToolSpec> =
        ToolGroups.layout(currentSlots(), expanded, extra)

    /** Whether group [group] currently has entry tools sent to the model. */
    fun hasEntry(group: String): Boolean = currentSlots().any { it.group == group && it.entry != null }

    /** Names of the tools in group [group] sent to the model right now (in registration order). */
    fun members(group: String): List<String> = currentSlots().filter { it.group == group }.map { it.full.name }

    /** Names of tools that can go into do_steps (including those this task does not offer), in registration order. */
    fun batchable(): List<String> = declared.values.filter { it.batchable }.map { it.name }

    /** Names listed in the "unknown tool" description: the ones available right now (most off-screen tools are skipped, but MCP tools say [AgentTool.listWhenUnknown]). */
    fun listedNames(): List<String> = all.filter { tool ->
        tool.listWhenUnknown && if (dynamic(tool)) tool.available(ctx) else tools.containsKey(tool.name)
    }.map { it.name }

    /**
     * Which MCP connector an unknown tool name most likely belongs to: only names starting with "mcp_" are
     * considered, and an exact prefix wins. When the model writes the name slightly wrong (a typo in the digest
     * is the common case), the closest server segment is chosen.
     */
    fun connectorForUnknown(name: String): String? {
        if (!name.startsWith("mcp_")) return null
        val connectors = all.mapNotNull { tool ->
            tool.connectorName?.let { it to tool.name.substringBefore("__") }
        }.distinctBy { it.second }
        if (connectors.isEmpty()) return null
        if (connectors.size == 1) return connectors.first().first
        val segment = name.substringBefore("__")
        connectors.firstOrNull { it.second == segment }?.let { return it.first }
        return connectors.minByOrNull { (_, prefix) -> editDistance(prefix, segment) }?.first
    }

    private fun editDistance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[b.length]
    }
}
