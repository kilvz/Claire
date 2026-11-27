package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ModelError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelErrorReasonTest {
    @Test fun rateLimitHasStableFailureCode() {
        val failure = StopRules.modelFailed(ModelError.RateLimited(null))
        assertEquals(FailKind.Model, failure.kind)
        assertEquals(FailureCode.ModelRateLimited, failure.code)
        assertEquals(null, StopRules.modelFailed(ModelError.Network()).code)
    }

    /** Model not found: when the model name is known, say which one it is, where to change it, and what the model name roughly looks like; otherwise fall back to the general wording. */
    @Test fun modelNotFoundNamesTheModel() {
        assertEquals(
            "Model \"gemini\" does not exist at this provider. Go to Settings › Models, check the model name (enter the full model ID; Gemini model names look like gemini-flash-latest), or switch to the matching preset.",
            StopRules.modelFailed(ModelError.ModelNotFound("gemini")).reason,
        )
        assertEquals("The model was not found. Go to Settings › Models and check that the model name and Base URL are correct.", StopRules.modelFailed(ModelError.ModelNotFound()).reason)
        assertEquals("Model \"gemini\" not found", ModelError.ModelNotFound("gemini").message)
    }

    /** A 404 may also be a gateway or relay service saying something else: when the server's own wording is available, quote it and do not assert that the model does not exist, and do not duplicate the trailing period; without it, behave as above. */
    @Test fun modelNotFoundCarriesWhatTheServerSaid() {
        assertEquals(
            "When calling model \"group/rpa\", the server replied: none of group/rpa's models is ready. The model name may be wrong, or the service may be temporarily unavailable. " +
                "Go to Settings › Models, check the model name (enter the full model ID; Gemini model names look like gemini-flash-latest), or switch to the matching preset.",
            StopRules.modelFailed(ModelError.ModelNotFound("group/rpa", "none of group/rpa's models is ready")).reason,
        )
        assertEquals(
            "The server replied: The model `x` does not exist. The model name may be wrong, or the service may be temporarily unavailable. Go to Settings › Models and check that the model name and Base URL are correct.",
            StopRules.modelFailed(ModelError.ModelNotFound("", "The model `x` does not exist.")).reason,
        )
        val said = StopRules.modelFailed(ModelError.ModelNotFound("m", "gone")).reason
        assertFalse(said, said.contains("does not exist"))
    }

    /** When the server's own wording is quoted mid-sentence, all three failures that carry it drop only one trailing period; an ellipsis from truncation is left alone. */
    @Test fun quotedServerTextDropsOneTrailingPeriod() {
        fun reasons(detail: String) = listOf(
            StopRules.modelFailed(ModelError.ModelNotFound("m", detail)).reason,
            StopRules.modelFailed(ModelError.ChatGptNotEligible(detail)).reason,
            StopRules.modelFailed(ModelError.AccountPlanNotAllowed("ChatGPT", detail)).reason,
        )
        reasons("Not allowed.").forEach { assertTrue(it, it.contains("Not allowed.") && !it.contains("Not allowed..")) }
        reasons("不允许。").forEach { assertTrue(it, it.contains("不允许.") && !it.contains("不允许。。")) }
        reasons("Not allowed").forEach { assertTrue(it, it.contains("Not allowed.")) }
        reasons("Too long…").forEach { assertTrue(it, it.contains("Too long….")) }
        reasons("Wait...").forEach { assertTrue(it, it.contains("Wait....")) }
    }

    /** A model this account cannot use, or one that does not support tools: state which one it is and where to switch, or to enable it in the provider's console. */
    @Test fun modelNotAvailableNamesTheModel() {
        assertEquals(
            "Model \"qwen-max\" is not activated or not allowed for this account. Go to Settings › Models to switch models, or enable it in the provider console.",
            StopRules.modelFailed(ModelError.ModelNotAvailable("qwen-max", "not activated")).reason,
        )
        assertEquals(
            "Model \"gemma\" does not support tool calls, and Claire needs tools to get things done. Go to Settings › Models and switch to a model that supports tool calls.",
            StopRules.modelFailed(ModelError.ToolsUnsupported("gemma")).reason,
        )
    }

    /** When the server rejects the reasoning effort, keep the parameter error and point to the level setting, and stop falsely blaming the Base URL or API type. */
    @Test fun rejectedReasoningEffortPointsToTheActualSetting() {
        val detail = "Invalid value: 'ultra'. Supported values are: 'none', 'minimal', 'low', 'medium', 'high', 'xhigh', and 'max'."
        val reason = StopRules.modelFailed(ModelError.ReasoningEffortRejected(detail)).reason
        assertTrue(reason.contains(detail.dropLast(1)))
        assertTrue(reason.contains("reasoning effort to the default"))
        assertFalse(reason.contains("Base URL"))
        assertFalse(reason.contains("API type"))
    }

    /** The whole chain failed: the primary message is the main model's error, followed by each model's status code and a one-line summary (MK-1 batch 2). */
    @Test fun chainFailedLeadsWithPrimaryAndListsTrail() {
        val primary = ModelError.RateLimited(null)
        val e = ModelError.ChainFailed(primary, listOf("inferx-main" to primary, "gemini-backup" to ModelError.Server(400)))
        val reason = StopRules.modelFailed(e).reason
        assertTrue(reason.startsWith(userReason(primary)))
        assertTrue(reason.contains("\"inferx-main\" HTTP 429"))
        assertTrue(reason.contains("\"gemini-backup\" HTTP 400"))
    }

    /** 断流有自己的说法、不提 Base URL;真的解析不了(格式对不上)才提示检查 Base URL 和接口类型。 */
    @Test fun serverErrorIncludesQuotedDetailWhenPresentAndOmitsWhenBlank() {
        val withDetail = userReason(ModelError.Server(400, "Invalid schema: Pointer '/\$defs/Filter' does not exist."))
        assertTrue(withDetail.contains("HTTP 400: Invalid schema: Pointer '/\$defs/Filter' does not exist"))
        assertFalse(withDetail.contains("does not exist.)"))

        assertEquals("The model service returned an error (HTTP 500). Try again later.", userReason(ModelError.Server(500, null)))
        assertEquals("The model service returned an error (HTTP 502). Try again later.", userReason(ModelError.Server(502, "   ")))
        assertEquals("The model service returned an error (HTTP 502). Try again later.", userReason(ModelError.Server(502, "<html><body>502 Bad Gateway</body></html>")))

        val redacted = userReason(ModelError.Server(400, "Invalid key sk-1234567890abcdef123456 or Bearer secretToken123456"))
        assertFalse(redacted.contains("sk-1234567890abcdef123456"))
        assertFalse(redacted.contains("secretToken123456"))
        assertTrue(redacted.contains("***"))
    }

    @Test fun streamCutIsNotBlamedOnConfiguration() {
        val cut = StopRules.modelFailed(ModelError.StreamCut()).reason
        assertTrue(cut.contains("cut off upstream"))
        assertTrue(cut.contains("after retrying"))
        assertFalse(cut.contains("Base URL"))
        assertFalse(cut.contains("API type"))
        assertTrue(StopRules.modelFailed(ModelError.BadResponse("流式响应不是合法 JSON")).reason.contains("Check that the Base URL matches the API type"))
    }
}
