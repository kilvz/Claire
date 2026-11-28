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

    fun stepLimit(maxSteps: Int) = TaskState.Failed("Reached the step limit of $maxSteps; the task was not completed.", FailKind.StepLimit)

    /** When the closeout request itself fails or still produces no result, choose the outcome matching why the closeout was triggered; do not falsely report that steps ran out. */
    fun closeoutFailed(reason: Closeout, maxSteps: Int): TaskState.Failed = when (reason) {
        Closeout.StepLimit -> stepLimit(maxSteps)
        Closeout.Completed -> TaskState.Failed("The task progress was already complete, but the model gave no result.", FailKind.Model)
        Closeout.NoNewData -> TaskState.Failed("No new data was read ${NewDataTracker.LIMIT} times in a row; the task was stopped.", FailKind.Guard)
    }

    /** Already reminded once and the model still replied with nothing (or was truncated by the output limit again). */
    fun noAnswer() = TaskState.Failed("The model stopped acting without giving an answer.", FailKind.Model)

    /** Already sent back once and the model's tool call came back as text again (see [LeakedToolCall]): do not hand the raw markers out as an answer. */
    fun toolCallAsText() = TaskState.Failed("The model's tool call was not recognized by the server and came back as plain text, so it could not be executed.", FailKind.Model)

    /** A model call failed (still failing after retries, or retrying is useless). */
    fun modelFailed(e: Throwable): TaskState.Failed = when (e) {
        is ModelError.TaskBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.DailyBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.ProactiveBudgetExceeded -> TaskState.Failed(userReason(e), FailKind.TokenLimit)
        is ModelError.RateLimited -> TaskState.Failed(userReason(e), FailKind.Model, FailureCode.ModelRateLimited)
        is ModelError -> TaskState.Failed(userReason(e), FailKind.Model)
        else -> TaskState.Failed("Model call failed: ${e.message ?: e::class.simpleName}", FailKind.Model)
    }

    fun guard(v: GuardVerdict): TaskState.Failed {
        val treeBlind = v.evidence["treeBlind"] == JsonPrimitive(true)
        val why = when (v.code) {
            "repeating_action" -> "the same action was repeated with no effect"
            "no_progress" -> if (treeBlind) "the controls on this screen cannot be read, and taps and swipes have no effect; this app may not respond to Claire's automation"
                else "the screen did not change after several actions"
            "read_loop" -> "the page did not change, and repeated reads did not find what was needed"
            "too_many_failures" -> "actions failed repeatedly"
            "sandbox_loop" -> "the same command was repeated verbatim and still failed"
            else -> "an operation guard was triggered"
        }
        return TaskState.Failed("Stopped safely (${v.code}): $why.", FailKind.Guard)
    }
}

internal fun userReason(e: ModelError): String = when (e) {
    is ModelError.Auth -> "The model service rejected the request: the API Key is invalid or unauthorized. Check in Settings that the API Key is entered correctly and is allowed to use this model."
    is ModelError.IllegalHeader -> "The API Key contains characters that cannot be used. Go to Settings › Models and paste only the key itself, without explanatory text, line breaks or emoji."
    is ModelError.QuotaExceeded -> "The model account quota is used up. Check the provider account's balance or plan."
    is ModelError.TaskBudgetExceeded -> "This task reached its budget limit of ${e.limit} tokens; the task was not completed."
    is ModelError.DailyBudgetExceeded ->
        "Provider \"${e.providerName}\" has reached its budget limit of ${e.limit} tokens for today; the task was not completed. It recovers automatically when the local date changes."
    // Proactive-capability calls are not sent inside a task, so this should never be reached; if it is, only state it truthfully and do not tell the user to change anything
    is ModelError.ProactiveBudgetExceeded -> "Proactive capabilities have reached today's budget limit. It recovers automatically when the local date changes."
    is ModelError.ModelNotFound -> {
        // A 404 does not necessarily mean the model name is wrong (the address may be wrong, or a gateway or relay service says something else): when the original text is at hand, quote it and do not assert that the model does not exist
        val check = if (e.model.isEmpty()) "Go to Settings › Models and check that the model name and Base URL are correct."
            else "Go to Settings › Models, check the model name (enter the full model ID; Gemini model names look like gemini-flash-latest), or switch to the matching preset."
        when {
            e.detail.isNotEmpty() ->
                (if (e.model.isEmpty()) "The server replied: " else "When calling model \"${e.model}\", the server replied: ") +
                    "${quoted(e.detail)}. The model name may be wrong, or the service may be temporarily unavailable. $check"
            e.model.isEmpty() -> "The model was not found. $check"
            else -> "Model \"${e.model}\" does not exist at this provider. $check"
        }
    }
    is ModelError.ModelNotAvailable ->
        "Model \"${e.model.ifEmpty { "current model" }}\" is not activated or not allowed for this account. Go to Settings › Models to switch models, or enable it in the provider console."
    is ModelError.ToolsUnsupported ->
        "Model \"${e.model.ifEmpty { "current model" }}\" does not support tool calls, and Claire needs tools to get things done. Go to Settings › Models and switch to a model that supports tool calls."
    is ModelError.ContextTooLong -> "The conversation exceeds the model's context length. Switch to a model with a larger context, or split the task into smaller parts."
    is ModelError.ContentRefused -> "The model refused to handle this content. Adjust the task description or switch to another model."
    is ModelError.ReasoningEffortRejected ->
        "The model does not accept the selected reasoning effort. The server replied: ${quoted(e.detail)}. Go to Settings › Models and change the reasoning effort to the default or a level the server supports."
    is ModelError.ServiceTierRejected ->
        "OpenAI does not accept the selected response speed. " +
            (if (e.detail.isBlank()) "" else "The server replied: ${quoted(e.detail)}. ") +
            "Go to Settings › Models › Providers, open this model, and change the response speed to the default or another available level."
    is ModelError.RateLimited -> "The model service is rate limiting requests; it was still too frequent after retrying. Try again later, or check the account's rate limit."
    is ModelError.Network -> "Could not connect to the model service; it was still unreachable after retrying. Check the network and the Base URL."
    is ModelError.Proxy -> when (e.failure) {
        ProxyFailure.Unreachable ->
            "Cannot reach the network proxy, or the proxy did not forward this request; it was still unreachable after retrying. Check that the proxy is on and that the address and port in Settings › Network proxy are correct; clear it when you do not need a proxy."
        ProxyFailure.AuthFailed -> "The network proxy rejected the username or password (HTTP 407). Check the username and password in the proxy address under Settings › Network proxy."
    }
    is ModelError.Server -> {
        val detail = safeServerDetail(e.detail)
        if (detail != null) "The model service returned an error (HTTP ${e.status}: $detail). Try again later."
        else "The model service returned an error (HTTP ${e.status}). Try again later."
    }
    is ModelError.StreamCut ->
        "The model's reply was cut off upstream before it finished, and it was still cut off after retrying. The usual causes are an unstable network, or an idle timeout that is too short in a relay service or gateway (common when the model thinks for a long time). Try again later."
    is ModelError.BadResponse -> "${e.message}. Check that the Base URL matches the API type."
    is ModelError.ChatGptLimitReached ->
        "The ChatGPT quota is used up. Try again later. Usage can be seen in ChatGPT settings › Usage (chatgpt.com/settings/usage); the Plus quota per 5 hours is shared with other apps that sign in with ChatGPT. You can also switch to another model for now."
    is ModelError.ChatGptNotEligible ->
        "This account cannot use ChatGPT quota in other apps (it needs Plus or Pro, and a region and account settings that allow it). ${if (e.detail.isEmpty()) "" else "The server said: ${quoted(e.detail)}. "}You can switch to another model."
    is ModelError.SignInExpired -> "${e.account} sign-in has expired. Sign in again with ${e.account} in Settings › Models."
    is ModelError.AccountPlanNotAllowed ->
        "Your ${e.account} subscription plan cannot use this API (HTTP 403). ${if (e.detail.isEmpty()) "" else "The server said: ${quoted(e.detail)}. "}You can use an ${e.account} API Key instead, or switch to another model in Settings › Models."
    is ModelError.ChainFailed -> {
        // The main message is the primary model's original error (generated above by its type), followed by each chain model's status code and a one-line summary (MK-1 batch 2)
        val trail = e.entries.joinToString("; ") { (name, err) ->
            "\"$name\"${chainErrorTag(err)}: ${err.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(80)}"
        }
        userReason(e.primary) + " This task tried the whole fallback chain and every model failed: $trail."
    }
    is ModelError.GroupUnavailable ->
        "Model group \"${e.group}\" has no available models${if (e.detail.isEmpty()) "" else " (${e.detail})"}. Go to Settings › Models and check that the group members are enabled and have the capabilities needed, or try again later."
    is ModelError.GroupExhausted ->
        "All models in group \"${e.group}\" have reached their limits${if (e.detail.isEmpty()) "" else " (${e.detail})"}. Try again later or adjust the group settings."
}

/** Short tag for each error in a chain-failure record: the HTTP status code when recognizable, otherwise the failure name. */
private fun chainErrorTag(e: ModelError): String = when (e) {
    is ModelError.Server -> " HTTP ${e.status}"
    is ModelError.RateLimited -> " HTTP 429"
    is ModelError.Auth -> " " + RetryPolicy.label(e)
    else -> " ${RetryPolicy.label(e)}"
}

private fun safeServerDetail(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (trimmed.startsWith("<!", ignoreCase = true) ||
        trimmed.startsWith("<html", ignoreCase = true) ||
        trimmed.startsWith("<head", ignoreCase = true) ||
        trimmed.startsWith("<body", ignoreCase = true)
    ) return null
    val normalized = trimmed.replace(Regex("""\s+"""), " ")
    val masked = com.wangzan101.mika.core.run.record.Redactor.text(normalized).trim()
    return masked.takeIf { it.isNotEmpty() }?.let(::quoted)
}

/** A server quote embedded in a sentence: drop one trailing period (the quote's own), and the sentence quoting it supplies its own period; ellipses (truncated "…" or "...") are left alone. */
private fun quoted(detail: String): String =
    when {
        detail.endsWith("..") -> detail
        detail.endsWith(".") || detail.endsWith("。") -> detail.dropLast(1)
        else -> detail
    }
