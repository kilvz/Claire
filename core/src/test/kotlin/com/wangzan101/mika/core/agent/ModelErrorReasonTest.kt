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
            "模型「gemini」在这个服务商那里不存在。去设置 › 模型,检查模型名(要填完整的模型 ID,比如 Gemini 的模型名类似 gemini-flash-latest)或改用对应的预设。",
            StopRules.modelFailed(ModelError.ModelNotFound("gemini")).reason,
        )
        assertEquals("找不到该模型,请到设置 › 模型检查模型名和 Base URL 是否正确。", StopRules.modelFailed(ModelError.ModelNotFound()).reason)
        assertEquals("找不到模型「gemini」", ModelError.ModelNotFound("gemini").message)
    }

    /** A 404 may also be a gateway or relay service saying something else: when the server's own wording is available, quote it and do not assert that the model does not exist, and do not duplicate the trailing period; without it, behave as above. */
    @Test fun modelNotFoundCarriesWhatTheServerSaid() {
        assertEquals(
            "调用模型「group/rpa」时服务端回复：none of group/rpa's models is ready。可能是模型名不对，或者服务那边暂时不可用。" +
                "去设置 › 模型,检查模型名(要填完整的模型 ID,比如 Gemini 的模型名类似 gemini-flash-latest)或改用对应的预设。",
            StopRules.modelFailed(ModelError.ModelNotFound("group/rpa", "none of group/rpa's models is ready")).reason,
        )
        assertEquals(
            "服务端回复：The model `x` does not exist。可能是模型名不对，或者服务那边暂时不可用。请到设置 › 模型检查模型名和 Base URL 是否正确。",
            StopRules.modelFailed(ModelError.ModelNotFound("", "The model `x` does not exist.")).reason,
        )
        val said = StopRules.modelFailed(ModelError.ModelNotFound("m", "gone")).reason
        assertFalse(said.contains("不存在"))
    }

    /** When the server's own wording is quoted mid-sentence, all three failures that carry it drop only one trailing period; an ellipsis from truncation is left alone. */
    @Test fun quotedServerTextDropsOneTrailingPeriod() {
        fun reasons(detail: String) = listOf(
            StopRules.modelFailed(ModelError.ModelNotFound("m", detail)).reason,
            StopRules.modelFailed(ModelError.ChatGptNotEligible(detail)).reason,
            StopRules.modelFailed(ModelError.AccountPlanNotAllowed("ChatGPT", detail)).reason,
        )
        reasons("Not allowed.").forEach { assertTrue(it, it.contains("Not allowed。") && !it.contains("Not allowed.")) }
        reasons("不允许。").forEach { assertTrue(it, it.contains("不允许。") && !it.contains("不允许。。")) }
        reasons("Not allowed").forEach { assertTrue(it, it.contains("Not allowed。")) }
        reasons("Too long…").forEach { assertTrue(it, it.contains("Too long…。")) }
        reasons("Wait...").forEach { assertTrue(it, it.contains("Wait...。")) }
    }

    /** A model this account cannot use, or one that does not support tools: state which one it is and where to switch, or to enable it in the provider's console. */
    @Test fun modelNotAvailableNamesTheModel() {
        assertEquals(
            "模型「qwen-max」这个账号没开通或没权限用。去设置 › 模型换一个模型，或到服务商后台开通。",
            StopRules.modelFailed(ModelError.ModelNotAvailable("qwen-max", "not activated")).reason,
        )
        assertEquals(
            "模型「gemma」不支持调用工具,Claire 干活要靠工具。去设置 › 模型换一个会调用工具的模型。",
            StopRules.modelFailed(ModelError.ToolsUnsupported("gemma")).reason,
        )
    }

    /** When the server rejects the reasoning effort, keep the parameter error and point to the level setting, and stop falsely blaming the Base URL or API type. */
    @Test fun rejectedReasoningEffortPointsToTheActualSetting() {
        val detail = "Invalid value: 'ultra'. Supported values are: 'none', 'minimal', 'low', 'medium', 'high', 'xhigh', and 'max'."
        val reason = StopRules.modelFailed(ModelError.ReasoningEffortRejected(detail)).reason
        assertTrue(reason.contains(detail.dropLast(1)))
        assertTrue(reason.contains("思考强度改为默认"))
        assertFalse(reason.contains("Base URL"))
        assertFalse(reason.contains("接口类型"))
    }

    /** The whole chain failed: the primary message is the main model's error, followed by each model's status code and a one-line summary (MK-1 batch 2). */
    @Test fun chainFailedLeadsWithPrimaryAndListsTrail() {
        val primary = ModelError.RateLimited(null)
        val e = ModelError.ChainFailed(primary, listOf("inferx-main" to primary, "gemini-backup" to ModelError.Server(400)))
        val reason = StopRules.modelFailed(e).reason
        assertTrue(reason.startsWith(userReason(primary)))
        assertTrue(reason.contains("「inferx-main」 HTTP 429"))
        assertTrue(reason.contains("「gemini-backup」 HTTP 400"))
    }

    /** A cut stream has its own wording and does not mention the Base URL; only when parsing really fails (the format does not match) does it suggest checking the Base URL and API type. */
    @Test fun streamCutIsNotBlamedOnConfiguration() {
        val cut = StopRules.modelFailed(ModelError.StreamCut()).reason
        assertTrue(cut.contains("连接就在上游被断开了"))
        assertTrue(cut.contains("重试后"))
        assertFalse(cut.contains("Base URL"))
        assertFalse(cut.contains("接口类型"))
        assertTrue(StopRules.modelFailed(ModelError.BadResponse("流式响应不是合法 JSON")).reason.contains("请检查 Base URL 和接口类型是否匹配"))
    }
}
