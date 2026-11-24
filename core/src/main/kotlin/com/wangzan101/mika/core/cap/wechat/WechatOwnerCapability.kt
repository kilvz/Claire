package com.wangzan101.mika.core.cap.wechat

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The outlet that delivers task results to the owner's WeChat; binding checks, deduplication and the actual sending all live on the app side, and core only knows this interface. */
fun interface WechatOwnerPort {
    suspend fun send(text: String): WechatOwnerResult
}

/**
 * The outcome of one push. [Uncertain] means "handed to the channel but no confirmation received"; like a failure it is reported truthfully to the model, and neither may be retried automatically;
 * [message] is the truth shown to the model (failure reason, reason for being unavailable) and goes into the result verbatim.
 */
data class WechatOwnerResult(val status: Status, val message: String? = null) {
    enum class Status { Delivered, Uncertain, Failed, Unavailable }
}

/** Gate patterns for one language: only when both a send verb and an owner target appear, and no negation precedes the verb, does it count as an explicit request to send. */
private class GatePattern(val verb: Regex, val target: Regex, val negation: Regex)

/** Table of explicit-intent patterns per language; add a row here when another language is supported later. */
private val GATE_PATTERNS = listOf(
    GatePattern(
        verb = Regex("发送|发到|发给|推送|推给|推到|转发|传给|发"),
        target = Regex("我的?微信"),
        negation = Regex("不|别|无需"),
    ),
    GatePattern(
        verb = Regex("\\b(?:send|deliver|forward|push)\\b", RegexOption.IGNORE_CASE),
        target = Regex("\\bmy wechat\\b", RegexOption.IGNORE_CASE),
        negation = Regex("\\b(?:don't|do not|never)\\b", RegexOption.IGNORE_CASE),
    ),
)

/**
 * Whether the user's opening sentence ([goal]) explicitly says "send the result to my WeChat".
 * The verb and the target are each matched anywhere in the sentence, with no character-distance window (long sentences are common in real requests); a negation only counts if it appears before the send verb,
 * and when it is not clear it does not pass — this gate only decides whether the tool is offered, so it errs on the side of exclusion.
 */
fun explicitWechatOwnerRequest(goal: String): Boolean = GATE_PATTERNS.any { p ->
    val verb = p.verb.find(goal) ?: return@any false
    if (!p.target.containsMatchIn(goal)) return@any false
    p.negation.find(goal)?.let { it.range.first < verb.range.first } != true
}

/**
 * Owner WeChat push capability: wechat_send_owner is offered to the model only when the user explicitly says "send to my WeChat" in the task and the app side has a bound delivery outlet.
 * When the port is null, neither the tool nor the section is emitted (the assembly layer uses this to decide whether to install it; see the anonymous Provider wrapping it in TaskTools).
 */
internal class WechatOwnerCapability(private val port: WechatOwnerPort?) : Capability {
    override val id: String = WECHAT_OWNER

    override fun status(): CapabilityStatus =
        if (port != null) CapabilityStatus.Available else CapabilityStatus.Unavailable("owner push channel not bound")

    override fun tools(): List<AgentTool> = if (port == null) emptyList() else wechatOwnerAgentTools

    override fun sections(env: PromptEnv): List<String> =
        if (port != null && env.isOpen(id) && SEND_OWNER in env.offered) listOf(SECTION) else emptyList()

    override fun onTaskStart(ctx: ToolContext) {
        port?.let { ctx[KEY] = it }
    }

    companion object {
        const val WECHAT_OWNER = "wechat_owner"
        const val SEND_OWNER = "wechat_send_owner"
        /** Upper limit on the body of one push (in characters). */
        const val TEXT_MAX = 4000
        val KEY = CapabilityKey<WechatOwnerPort>(WECHAT_OWNER)

        /** Behavioral guidelines: given with the system prompt when the tool is offered. */
        private const val SECTION = "WeChat owner push: the user explicitly asked for this task's result to be sent to their bound WeChat chat. " +
            "Call wechat_send_owner once, when the task is done, with only the final result and any honest uncertainty. " +
            "A delivered status means the channel accepted the text, not that the owner has read it. " +
            "If delivery fails or is uncertain, report exactly that and never resend on your own. " +
            "Never use the WeChat app or a browser chat as a substitute for this tool."
    }
}

/** All tools of this capability; stateless, the port enters the tool context at task start via [WechatOwnerCapability.KEY]. */
internal val wechatOwnerAgentTools: List<AgentTool> = listOf(WechatSendOwner())

private class WechatSendOwner : AgentTool {
    override val capability = WechatOwnerCapability.WECHAT_OWNER
    override val verb = Verb.WechatSendOwner
    override val risk = Risk.Write
    override val offScreen = true
    override val logged = true
    override val def = ToolDef(
        WechatOwnerCapability.SEND_OWNER,
        Prompts.tool(WechatOwnerCapability.SEND_OWNER),
        mapOf("text" to str("Final result text to deliver to the owner's bound WeChat chat.")),
        listOf("text"),
    )

    /** Double confirmation: the tool is absent when the capability was not assembled (no delivery outlet bound). */
    override fun available(ctx: ToolContext) = ctx[WechatOwnerCapability.KEY] != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx[WechatOwnerCapability.KEY]
            ?: return ToolExecution(buildJsonObject {
                put("ok", false)
                put("error", "wechat_owner_unavailable")
                put("status", WechatOwnerResult.Status.Unavailable.name.lowercase())
            })
        val text = a.str("text", min = 1, max = WechatOwnerCapability.TEXT_MAX)!!
        val result = port.send(text)
        return ToolExecution(buildJsonObject {
            put("ok", result.status == WechatOwnerResult.Status.Delivered)
            put("status", result.status.name.lowercase())
            result.message?.let { put("text", it) }
        })
    }
}
