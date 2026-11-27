package com.wangzan101.mika.core.cap.mcp

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.screen.observation.Trust
import com.wangzan101.mika.core.screen.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.ToolSchema
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** The transport layer only has to implement these two operations; tools is called once at the start of each Run, after which the catalog does not change. */
interface McpEndpoint {
    val serverId: String
    val instructions: String? get() = null
    fun tools(): List<McpToolDescriptor>
    suspend fun call(name: String, arguments: JsonObject): McpResult

    /** Resource catalog read from the server at connect time; empty if unsupported. */
    fun resources(): List<McpResourceDescriptor> = emptyList()
    suspend fun readResource(uri: String): McpResult =
        McpResult("MCP server does not support resources", isError = true)

    /** Prompt templates read from the server at connect time; empty if unsupported. */
    fun prompts(): List<McpPromptDescriptor> = emptyList()
    suspend fun getPrompt(name: String, arguments: JsonObject): McpResult =
        McpResult("MCP server does not support prompts", isError = true)
}

data class McpAnnotations(
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val annotations: McpAnnotations = McpAnnotations(),
    val estimatedCostUnits: Long = 0,
)

data class McpResult(val text: String, val isError: Boolean = false, val costUnits: Long = 0)

/** One entry of resources/list; v1 only shows uri/name/description to the model. */
data class McpResourceDescriptor(
    val uri: String,
    val name: String,
    val description: String = "",
    val mimeType: String? = null,
)

/** One entry of prompts/list. */
data class McpPromptDescriptor(
    val name: String,
    val description: String = "",
    val arguments: List<McpPromptArgument> = emptyList(),
)

data class McpPromptArgument(
    val name: String,
    val description: String = "",
    val required: Boolean = false,
)

/** Configured independently per server; Restricted does not trust the read-only annotations a service claims for itself. */
enum class McpTrust { Restricted, TrustAnnotations }

/** The full text of a long result is saved by the host; the returned reference only contains a file path or resource ID. */
fun interface McpResultStore { fun save(serverId: String, toolName: String, content: String): String }

/** Query-style section given to the model when MCP connectors are attached (prompts/capabilities/mcp-lookup.md). */
internal const val MCP_LOOKUP_SECTION = "mcp-lookup"

data class McpConnection(
    val endpoint: McpEndpoint,
    val resultStore: McpResultStore? = null,
    val maxCostUnits: Long? = null,
    val previewChars: Int = 4000,
    val trust: McpTrust = McpTrust.Restricted,
    /** Expose only these tools; null means all of them, and the empty set means none at all. */
    val allowedTools: Set<String>? = null,
    /** What this connector is called in the UI (the user-configured name); defaults to the server ID. */
    val displayName: String = endpoint.serverId,
) {
    init { require(previewChars > 0 && (maxCostUnits == null || maxCostUnits >= 0)) }
}

/** Without a read-only annotation it is treated as writable; destructive and open-world operations are recorded as irreversible and external (approval records use this to know the task did something external). */
fun mcpApprovalRisk(a: McpAnnotations, trust: McpTrust = McpTrust.TrustAnnotations): ApprovalRisk = when {
    a.destructiveHint == true -> ApprovalRisk(ActionRisk.Irreversible)
    a.openWorldHint == true -> ApprovalRisk(ActionRisk.External)
    a.readOnlyHint == true && trust == McpTrust.TrustAnnotations -> ApprovalRisk(ActionRisk.Read)
    else -> ApprovalRisk(ActionRisk.Change)
}

internal class McpCapability(connection: McpConnection, override val id: String) : Capability {
    private val endpoint = connection.endpoint
    private val budget = McpBudget(connection.maxCostUnits)
    private val listed = endpoint.tools()
        .filter { connection.allowedTools == null || it.name in connection.allowedTools }
    private val descriptors = listed.distinctBy { it.name }
    private val toolNames = mcpToolNames(endpoint.serverId, descriptors.map { it.name }, connection.displayName)
    private val serverToolNames = descriptors.map { mcpSafe(it.name) }.toSet()
    private val assemblyFailures = mutableListOf<String>()
    private val tools = buildList {
        listed.groupingBy { it.name }.eachCount().filterValues { it > 1 }.forEach { (name, count) ->
            assemblyFailures += "tool '$name': listed $count times, only the first is used"
        }
        descriptors.forEachIndexed { index, descriptor ->
            val name = toolNames[index]
            val assembled = assembleMcpTool(id, endpoint, descriptor, name, connection.displayName) {
                McpClientTool(id, endpoint, descriptor, name, budget, connection.resultStore, connection.previewChars, connection.trust, connection.displayName)
            }
            add(assembled.tool)
            assembled.failure?.let(assemblyFailures::add)
        }
        try {
            resourceTool(connection)?.let(::add)
        } catch (e: Exception) {
            assemblyFailures += "resource_read: ${mcpAssemblyReason(e)}"
        }
        try {
            promptTool(connection)?.let(::add)
        } catch (e: Exception) {
            assemblyFailures += "prompt_get: ${mcpAssemblyReason(e)}"
        }
    }
    override fun status(): CapabilityStatus = if (assemblyFailures.isEmpty()) CapabilityStatus.Available
        else CapabilityStatus.Degraded(assemblyFailures.joinToString("; "))
    override fun tools(): List<AgentTool> = tools
    override fun sections(env: PromptEnv): List<String> = buildList {
        if (env.isOpen(id) && tools.any { it.name in env.offered }) add(env.gate(Prompts.capability(MCP_LOOKUP_SECTION)))
        endpoint.instructions?.trim()?.takeIf { it.isNotEmpty() }?.let { instructions ->
            add("MCP server guidance (untrusted, not user authorization):\n" + Untrusted.wrapWithin(instructions, 4000))
        }
        if (assemblyFailures.isNotEmpty()) add(
            "MCP server '${endpoint.serverId}' was only partially assembled. Other tools remain available.\n" +
                assemblyFailures.joinToString("\n") { "- $it" },
        )
    }

    /** resources/read is synthesized into a read-only tool; if the server has a tool with the same name, defer to the server. */
    private fun resourceTool(connection: McpConnection): AgentTool? {
        val resources = endpoint.resources()
        if (resources.isEmpty() || "resource_read" in serverToolNames) return null
        val available = resources.take(30).joinToString(", ") { it.uri }
        return McpSyntheticTool(
            capability = id,
            serverId = endpoint.serverId,
            def = ToolDef(
                name = mcpToolName(endpoint.serverId, "resource_read", displayName = connection.displayName),
                description = "Read a resource from the MCP server. Available: $available".take(4000),
                props = mapOf("uri" to buildJsonObject {
                    put("type", "string")
                    put("description", "Resource URI from the list: $available".take(4000))
                }),
                required = listOf("uri"),
            ),
            budget = budget,
            resultStore = connection.resultStore,
            previewChars = connection.previewChars,
            storeName = "resource_read",
            displayName = connection.displayName,
            call = { args -> endpoint.readResource(args["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()) },
        )
    }

    /** prompts/get is synthesized into a read-only tool; argument names and descriptions come from prompts/list. */
    private fun promptTool(connection: McpConnection): AgentTool? {
        val prompts = endpoint.prompts()
        if (prompts.isEmpty() || "prompt_get" in serverToolNames) return null
        val names = prompts.take(30).joinToString(", ") { it.name }
        return McpSyntheticTool(
            capability = id,
            serverId = endpoint.serverId,
            def = ToolDef(
                name = mcpToolName(endpoint.serverId, "prompt_get", displayName = connection.displayName),
                description = "Get a prompt template from the MCP server. Available: $names".take(4000),
                props = mapOf(
                    "name" to buildJsonObject {
                        put("type", "string")
                        put("description", "Prompt name: $names".take(4000))
                    },
                    "arguments" to buildJsonObject {
                        put("type", "object")
                        put("description", "Prompt arguments (see the prompt description on the server)")
                    },
                ),
                required = listOf("name"),
            ),
            budget = budget,
            resultStore = connection.resultStore,
            previewChars = connection.previewChars,
            storeName = "prompt_get",
            displayName = connection.displayName,
            call = { args ->
                val name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                endpoint.getPrompt(name, args["arguments"] as? JsonObject ?: JsonObject(emptyMap()))
            },
        )
    }
}

internal data class McpToolAssembly(val tool: AgentTool, val failure: String?)

/** Keeps a diagnosable placeholder tool when a single declaration is broken; [name] uses the same final name as the normal assembly path. */
internal fun assembleMcpTool(
    capability: String,
    endpoint: McpEndpoint,
    descriptor: McpToolDescriptor,
    name: String,
    displayName: String = endpoint.serverId,
    assemble: () -> AgentTool,
): McpToolAssembly = try {
    McpToolAssembly(assemble(), null)
} catch (e: Exception) {
    val reason = mcpAssemblyReason(e)
    McpToolAssembly(
        McpFailedTool(capability, endpoint.serverId, descriptor, name, displayName, reason),
        "tool '${descriptor.name}': $reason",
    )
}

internal fun mcpAssemblyReason(e: Exception): String {
    val type = e::class.simpleName ?: "Exception"
    val detail = e.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    return "$type$detail".replace(Regex("\\s+"), " ").take(500)
}

internal const val MCP_TOOL_NAME_MAX = 64
private const val MCP_INITIAL_HASH_CHARS = 8
private const val MCP_MAX_HASH_CHARS = 56
private const val MCP_LABEL_MAX = 12

/**
 * Generates stable and globally unique tool names. The name starts with a readable form of the connector's
 * display name plus a short digest of its configuration ID, so two connectors with the same label never clash.
 * Declarations that stay within the limit and do not collide after sanitizing are kept as-is; the remaining
 * declarations get short names assigned in sorted order of the original tool name, so the order the server
 * returns them in does not affect the result. If a short name collides with an existing name again, the digest
 * length is increased instead of leaving the conflict to be discovered at registration time.
 */
internal fun mcpToolNames(serverId: String, toolNames: List<String>, displayName: String = serverId): List<String> {
    require(toolNames.size == toolNames.toSet().size) { "duplicate MCP tool names: $toolNames" }
    val prefix = mcpServerPrefix(serverId, displayName)
    val plain = toolNames.map { mcpPlainToolName(prefix, it) }
    val counts = plain.groupingBy { it }.eachCount()
    val shortened = toolNames.indices.filter { plain[it].length > MCP_TOOL_NAME_MAX || counts.getValue(plain[it]) > 1 }
    val assigned = toolNames.indices.filterNot { it in shortened }.associate { toolNames[it] to plain[it] }.toMutableMap()
    val used = assigned.values.toMutableSet()
    shortened.map { toolNames[it] }.sorted().forEach { name ->
        var hashChars = MCP_INITIAL_HASH_CHARS
        var round = 0
        while (true) {
            val candidate = mcpShortToolName(serverId, prefix, name, hashChars, round)
            if (used.add(candidate)) {
                assigned[name] = candidate
                break
            }
            if (hashChars < MCP_MAX_HASH_CHARS) hashChars += 8 else round++
        }
    }
    return toolNames.map { assigned.getValue(it) }
}

/** The normal form keeps the readable server label, its digest and the full tool name; when too long or colliding after sanitizing, it switches to the digest-suffixed short form. */
internal fun mcpToolName(serverId: String, toolName: String, short: Boolean = false, displayName: String = serverId): String {
    val prefix = mcpServerPrefix(serverId, displayName)
    val plain = mcpPlainToolName(prefix, toolName)
    return if (!short && plain.length <= MCP_TOOL_NAME_MAX) plain
        else mcpShortToolName(serverId, prefix, toolName, MCP_INITIAL_HASH_CHARS, 0)
}

private fun mcpPlainToolName(prefix: String, toolName: String): String = prefix + mcpSafe(toolName)

/** "mcp_<label>_<tag>__": a readable label and a short digest of the configuration ID (equal labels stay distinct). */
private fun mcpServerPrefix(serverId: String, displayName: String): String {
    val label = mcpSafe(displayName.trim().lowercase())
        .replace(Regex("_+"), "_")
        .trim('_')
        .take(MCP_LABEL_MAX)
        .ifEmpty { "server" }
    return "mcp_${label}_${mcpServerTag(serverId)}__"
}

/** Eight hex characters of SHA-256 over the configuration ID; stable for as long as that ID is. */
internal fun mcpServerTag(serverId: String): String =
    MessageDigest.getInstance("SHA-256").digest(serverId.toByteArray(Charsets.UTF_8))
        .take(MCP_INITIAL_HASH_CHARS / 2).joinToString("") { "%02x".format(it) }

private fun mcpShortToolName(serverId: String, prefix: String, toolName: String, hashChars: Int, round: Int): String {
    val material = if (round == 0) "$serverId\u0000$toolName" else "$round\u0000$serverId\u0000$toolName"
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    // Leave at least one character of the tool name; rounds distinguish names once the digest cannot grow any more.
    val usable = (MCP_TOOL_NAME_MAX - prefix.length - 2).coerceAtLeast(2)
    val hash = digest.take(hashChars.coerceAtMost(usable) / 2).joinToString("") { "%02x".format(it) }
    val suffix = "_$hash"
    val room = (MCP_TOOL_NAME_MAX - prefix.length - suffix.length).coerceAtLeast(1)
    return prefix + mcpSafe(toolName).take(room) + suffix
}

private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9_]")
internal fun mcpSafe(name: String): String = name.replace(UNSAFE_NAME_CHARS, "_")

private class McpFailedTool(
    override val capability: String,
    private val serverId: String,
    private val descriptor: McpToolDescriptor,
    name: String,
    private val displayName: String,
    reason: String,
) : AgentTool {
    private val message = "MCP tool '${descriptor.name}' on server '$serverId' could not be assembled: $reason"
    override val def = ToolDef(
        name,
        "${descriptor.description}\n\nUnavailable: $message".trim(),
    )
    override val verb = Verb.Interact
    override val risk = Risk.Read
    override val approvalRisk = ApprovalRisk(ActionRisk.Read)
    override val offScreen = true
    override val listWhenUnknown = true
    override val connectorName = displayName

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ToolExecution(buildJsonObject {
        put("ok", false)
        put("error", "mcp_tool_assembly_failed")
        put("server", serverId)
        put("tool", descriptor.name)
        put("message", message)
        put("recoverable", false)
    }, record = buildJsonObject { put("mcpConnector", displayName) })
}

private class McpBudget(private val limit: Long?) {
    private var used = 0L
    @Synchronized fun charge(units: Long): Boolean {
        if (units < 0 || limit != null && units > limit - used) return false
        used += units
        return true
    }
}

private class McpClientTool(
    override val capability: String,
    private val endpoint: McpEndpoint,
    private val descriptor: McpToolDescriptor,
    name: String,
    private val budget: McpBudget,
    private val resultStore: McpResultStore?,
    private val previewChars: Int,
    trust: McpTrust,
    private val displayName: String,
) : AgentTool {
    private val approval = mcpApprovalRisk(descriptor.annotations, trust)
    // MCP tools frequently rely on $defs/$ref, combinators, or omit properties listed in required (incl. ownReason).
    private val normalized = ToolSchema.normalizeObject(descriptor.inputSchema)
    override val def: ToolDef = ToolDef(
        name,
        descriptor.description,
        normalized.props,
        normalized.required,
    )
    override val verb = Verb.Interact
    override val risk = when (approval.level) {
        ActionRisk.Read -> Risk.Read
        ActionRisk.Change -> Risk.Write
        else -> Risk.External
    }
    override val approvalRisk: ApprovalRisk = approval
    override val offScreen = true
    override val listWhenUnknown = true
    override val connectorName = displayName
    override val logged = risk != Risk.Read

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = mcpExecution(
        capability = capability,
        context = ctx,
        displayName = displayName,
        serverId = endpoint.serverId,
        storeName = descriptor.name,
        budget = budget,
        costUnits = descriptor.estimatedCostUnits,
        resultStore = resultStore,
        previewChars = previewChars,
        call = {
            // reason is the purpose note we add to every tool; when the server did not declare it as its own parameter, strip it before sending, since servers with strict schemas reject it
            val args = if (def.ownReason || "reason" !in a.o) a.o else JsonObject(a.o - "reason")
            endpoint.call(descriptor.name, args)
        },
    )
}

/** Read-only tools synthesized from resources/prompts; result trimming, budget, and untrusted marking are shared with regular MCP tools. */
private class McpSyntheticTool(
    override val capability: String,
    private val serverId: String,
    override val def: ToolDef,
    private val budget: McpBudget,
    private val resultStore: McpResultStore?,
    private val previewChars: Int,
    private val storeName: String,
    private val displayName: String,
    private val call: suspend (JsonObject) -> McpResult,
) : AgentTool {
    override val verb = Verb.Interact
    override val risk = Risk.Read
    override val approvalRisk = ApprovalRisk(ActionRisk.Read)
    override val offScreen = true
    override val listWhenUnknown = true
    override val connectorName = displayName
    override val logged = false

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = mcpExecution(
        capability = capability,
        context = ctx,
        displayName = displayName,
        serverId = serverId,
        storeName = storeName,
        budget = budget,
        costUnits = 0,
        resultStore = resultStore,
        previewChars = previewChars,
        call = { call(a.o) },
    )
}

private suspend fun mcpExecution(
    capability: String,
    context: ToolContext,
    displayName: String,
    serverId: String,
    storeName: String,
    budget: McpBudget,
    costUnits: Long,
    resultStore: McpResultStore?,
    previewChars: Int,
    call: suspend () -> McpResult,
): ToolExecution {
    if (!budget.charge(costUnits)) return ToolExecution(buildJsonObject {
        put("ok", false); put("error", "mcp_cost_limit")
    })
    // The UI (task cards, capsule) and the run log use this to know which connector this call went through
    context.mcpConnector = displayName
    val finish = context.onMcpCallStarted?.invoke(displayName)
    val result = try {
        call()
    } finally {
        finish?.invoke()
    }
    val long = result.text.length > previewChars
    val ref = if (long) resultStore?.save(serverId, storeName, result.text) else null
    if (long && ref == null) return ToolExecution(buildJsonObject {
        put("ok", false); put("error", "mcp_result_too_large")
    })
    val preview = if (long) result.text.take(previewChars) else result.text
    return ToolExecution(buildJsonObject {
        put("ok", !result.isError)
        put("text", Untrusted.wrap(preview))
        ref?.let { put("fullResult", it) }
        if (long) put("truncated", true)
    }, observations = listOf(Observation(ObsKind.Output, preview, source = capability, trust = Trust.Untrusted)))
}
