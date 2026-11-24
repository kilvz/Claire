package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.core.safety.guard.GuardVerdict
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.tool.Outcome
import kotlinx.serialization.json.JsonPrimitive

/**
 * Stop rules: when and with what outcome a task ends (giving up, a guard stopping it, a model error, no answer
 * produced, steps used up). Accomplishing the task is not handled here: a reply without tool calls is the answer, and
 * the loop checks it and delivers it. Pure functions; the explanations shown to the user in the outcome are not sent
 * to the model.
 */
internal object StopRules {
    /** After an action executes: if it gave up, end by that; otherwise stop if a guard ([stop], a screen guard, or a guard of a tool that does not touch the screen) called it. When neither applies, null, and work continues. */
    fun afterAction(outcome: Outcome?, stop: GuardVerdict?): TaskState? = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stop?.let(::guard)
    }

    /** The final turn after the step limit called a tool (only end_not_done was given): giving up ends by that, otherwise it counts as steps used up. */
    fun afterFinalTurn(outcome: Outcome?, maxSteps: Int): TaskState = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stepLimit(maxSteps)
    }

    fun stepLimit(maxSteps: Int) = TaskState.Failed("已达到 $maxSteps 步上限,任务没有完成。", FailKind.StepLimit)

    /** When the closeout request itself fails or still produces no result, choose the outcome matching why the closeout was triggered; do not falsely report that steps ran out. */
    fun closeoutFailed(reason: Closeout, maxSteps: Int): TaskState.Failed = when (reason) {
        Closeout.StepLimit -> stepLimit(maxSteps)
        Closeout.Completed -> TaskState.Failed("任务进度已经完成,但模型没有给出结果。", FailKind.Model)
        Closeout.NoNewData -> TaskState.Failed("连续 ${NewDataTracker.LIMIT} 次没有读到新数据,任务已停止。", FailKind.Guard)
    }

    /** Already reminded once and the model still replied with nothing (or was truncated by the output limit again). */
    fun noAnswer() = TaskState.Failed("模型停止了操作,没有给出回答。", FailKind.Model)

    /** Already sent back once and the model's tool call came back as text again (see [LeakedToolCall]): do not hand the raw markers out as an answer. */
    fun toolCallAsText() = TaskState.Failed("模型的工具调用没有被服务端识别,原样作为文字返回了,没能执行。", FailKind.Model)

    /** A model call failed (still failing after retries, or retrying is useless). */
    fun modelFailed(e: Throwable): TaskState.Failed = when (e) {
        is ModelError.TaskBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.DailyBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.ProactiveBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.RateLimited -> TaskState.Failed(userReason(e), FailKind.Model, FailureCode.ModelRateLimited)
        is ModelError -> TaskState.Failed(userReason(e), FailKind.Model)
        else -> TaskState.Failed("模型调用异常:${e.message ?: e::class.simpleName}", FailKind.Model)
    }

    fun guard(v: GuardVerdict): TaskState.Failed {
        val treeBlind = v.evidence["treeBlind"] == JsonPrimitive(true)
        val why = when (v.code) {
            "repeating_action" -> "同一个操作反复执行仍没有效果"
            "no_progress" -> if (treeBlind) "读不到这个界面的控件,点击和滑动画面也没有反应,该应用可能不响应 Claire 的自动化操作"
                else "操作多次后画面一直没有变化"
            "read_loop" -> "页面没有变化,反复读也读不出要找的内容"
            "too_many_failures" -> "操作连续失败"
            "sandbox_loop" -> "同一条命令原样重复仍然失败"
            else -> "触发了操作保护"
        }
        return TaskState.Failed("已安全停止(${v.code}):$why。", FailKind.Guard)
    }
}

internal fun userReason(e: ModelError): String = when (e) {
    is ModelError.Auth -> "模型服务拒绝了请求:API Key 无效或没有权限。请在设置里检查 API Key 是否填写正确、是否有权使用该模型。"
    is ModelError.IllegalHeader -> "API Key 含有无法使用的字符。请在设置 › 模型里只粘贴密钥本身，不要带说明文字、换行或表情。"
    is ModelError.QuotaExceeded -> "模型账户额度已用完,请检查服务商账户的余额或套餐。"
    is ModelError.TaskBudgetExceeded -> "已达到本任务 ${e.limit} Token 的预算上限,任务没有完成。"
    is ModelError.DailyBudgetExceeded ->
        "服务商「${e.providerName}」已达到今天 ${e.limit} Token 的预算上限,任务没有完成。本地日期变更后会自动恢复。"
    // Proactive-capability calls are not sent inside a task, so this should never be reached; if it is, only state it truthfully and do not tell the user to change anything
    is ModelError.ProactiveBudgetExceeded -> "主动能力已达到今天的预算上限。本地日期变更后会自动恢复。"
    is ModelError.ModelNotFound -> {
        // A 404 does not necessarily mean the model name is wrong (the address may be wrong, or a gateway or relay service says something else): when the original text is at hand, quote it and do not assert that the model does not exist
        val check = if (e.model.isEmpty()) "请到设置 › 模型检查模型名和 Base URL 是否正确。"
            else "去设置 › 模型,检查模型名(要填完整的模型 ID,比如 Gemini 的模型名类似 gemini-flash-latest)或改用对应的预设。"
        when {
            e.detail.isNotEmpty() ->
                (if (e.model.isEmpty()) "" else "调用模型「${e.model}」时") +
                    "服务端回复：${quoted(e.detail)}。可能是模型名不对，或者服务那边暂时不可用。$check"
            e.model.isEmpty() -> "找不到该模型,$check"
            else -> "模型「${e.model}」在这个服务商那里不存在。$check"
        }
    }
    is ModelError.ModelNotAvailable ->
        "模型「${e.model.ifEmpty { "当前模型" }}」这个账号没开通或没权限用。去设置 › 模型换一个模型，或到服务商后台开通。"
    is ModelError.ToolsUnsupported ->
        "模型「${e.model.ifEmpty { "当前模型" }}」不支持调用工具,Claire 干活要靠工具。去设置 › 模型换一个会调用工具的模型。"
    is ModelError.ContextTooLong -> "对话内容超出了模型的上下文长度,请换用上下文更长的模型,或把任务拆小一些。"
    is ModelError.ContentRefused -> "模型拒绝处理该内容,请调整任务描述或换一个模型。"
    is ModelError.ReasoningEffortRejected ->
        "模型不接受所选的思考强度。服务端回复：${quoted(e.detail)}。请到设置 › 模型,把思考强度改为默认或服务端支持的档位。"
    is ModelError.ServiceTierRejected ->
        "OpenAI 不接受所选的响应速度。" +
            (if (e.detail.isBlank()) "" else "服务端回复：${quoted(e.detail)}。") +
            "请到设置 › 模型 › 服务商，打开该模型，将响应速度改为默认或其他可用档位。"
    is ModelError.RateLimited -> "模型服务限流,重试后仍然请求太频繁。请稍后再试,或检查账户的调用频率限制。"
    is ModelError.Network -> "连接模型服务失败,重试后仍然不通。请检查网络和 Base URL。"
    is ModelError.Proxy -> when (e.failure) {
        ProxyFailure.Unreachable ->
            "连不上网络代理,或代理没有转发这个请求,重试后仍然不通。请检查代理是否开着、设置 › 网络代理 里的地址和端口是否正确;不需要代理时把它清空。"
        ProxyFailure.AuthFailed -> "网络代理拒绝了账号或密码(HTTP 407)。请在设置 › 网络代理 里检查代理地址中的用户名和密码。"
    }
    is ModelError.Server -> "模型服务出错(HTTP ${e.status}),请稍后再试。"
    is ModelError.StreamCut ->
        "模型的回复还没传完,连接就在上游被断开了,重试后仍然如此。常见原因是网络不稳,或中转服务、网关的空闲超时太短(模型长时间思考时容易碰上)。请稍后再试。"
    is ModelError.BadResponse -> "${e.message}。请检查 Base URL 和接口类型是否匹配。"
    is ModelError.ChatGptLimitReached ->
        "ChatGPT 额度用完了,稍后再试。用量可以在 ChatGPT 设置 › 用量(chatgpt.com/settings/usage)查看;Plus 每 5 小时的额度和其他用 ChatGPT 登录的 App 共用。也可以先换一个模型。"
    is ModelError.ChatGptNotEligible ->
        "这个账号不能在其他 App 里用 ChatGPT 额度(需要 Plus 或 Pro,并且所在地区和账号设置允许)。${if (e.detail.isEmpty()) "" else "服务端说明:${quoted(e.detail)}。"}可以换一个模型。"
    is ModelError.SignInExpired -> "${e.account} 登录已失效,请在设置 › 模型里重新用 ${e.account} 登录。"
    is ModelError.AccountPlanNotAllowed ->
        "你的 ${e.account} 订阅档位不能用这个接口(HTTP 403)。${if (e.detail.isEmpty()) "" else "服务端说明:${quoted(e.detail)}。"}可以改用 ${e.account} 的 API Key,或在设置 › 模型里换一个模型。"
    is ModelError.ChainFailed -> {
        // The main message is the primary model's original error (generated above by its type), followed by each chain model's status code and a one-line summary (MK-1 batch 2)
        val trail = e.entries.joinToString("；") { (name, err) ->
            "「$name」${chainErrorTag(err)}：${err.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(80)}"
        }
        userReason(e.primary) + "本任务把整条备援链都试过了，全部失败：$trail。"
    }
    is ModelError.GroupUnavailable ->
        "模型组「${e.group}」没有可用的模型${if (e.detail.isEmpty()) "" else "(${e.detail})"}。请到设置 › 模型检查组成员是否启用、能力是否符合,或稍后再试。"
    is ModelError.GroupExhausted ->
        "模型组「${e.group}」的模型都达到限额了${if (e.detail.isEmpty()) "" else "(${e.detail})"}。请稍后再试或调整组设置。"
}

/** Short tag for each error in a chain-failure record: the HTTP status code when recognizable, otherwise the failure name. */
private fun chainErrorTag(e: ModelError): String = when (e) {
    is ModelError.Server -> " HTTP ${e.status}"
    is ModelError.RateLimited -> " HTTP 429"
    is ModelError.Auth -> " " + RetryPolicy.label(e)
    else -> " ${RetryPolicy.label(e)}"
}

/** Server text quoted inside a sentence: drop one trailing period (the original's own), and the sentence quoting it supplies its own "。"; ellipses (truncated "…" or "...") are left untouched. */
private fun quoted(detail: String): String =
    when {
        detail.endsWith("..") -> detail
        detail.endsWith(".") || detail.endsWith("。") -> detail.dropLast(1)
        else -> detail
    }
