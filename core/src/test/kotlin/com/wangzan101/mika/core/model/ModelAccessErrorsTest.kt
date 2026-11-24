package com.wangzan101.mika.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAccessErrorsTest {
    private fun notAvailable(status: Int, body: String) = ModelAccessErrors.of(status, body, "m") as ModelError.ModelNotAvailable

    /** Each vendor's way of saying "this account cannot use this model": map it to ModelNotAvailable, carrying the server's own wording. */
    @Test fun accountCannotUseTheModel() {
        val notActivated = """{"code":"InvalidParameter","message":"The product is not activated, please confirm that you have activated products and try again after activation.","request_id":"x"}"""
        val e = notAvailable(400, notActivated)
        assertEquals("m", e.model)
        assertTrue(e.detail, e.detail.startsWith("The product is not activated"))
        assertEquals("Model access denied.", notAvailable(403, """{"error":{"code":"Model.AccessDenied","message":"Model access denied."}}""").detail)
        notAvailable(403, """{"error":{"message":"You are not authorized to access this model","type":"permission_error"}}""")
        notAvailable(403, """{"error":{"message":"Project does not have access to model gpt-x","code":"model_not_found"}}""")
        notAvailable(400, """{"error":{"code":"1220","message":"您当前使用该 API 的权限未开通"}}""")
        notAvailable(403, """{"error":{"message":"无权限访问该模型"}}""")
        // A 404 may say this too
        notAvailable(404, """{"error":{"message":"Model not activated for this account"}}""")
        assertEquals("plain text not activated", notAvailable(400, "plain text not activated").detail)
        val cfError = """{"errors":[{"message":"AiError: Model @cf/zai-org/glm-5.3 is not available on the Workers Free plan: Upgrade to access this model","code":5035}],"success":false}"""
        val cfE = notAvailable(403, cfError)
        assertEquals("m", cfE.model)
        assertTrue(cfE.detail.contains("not available on the Workers Free plan"))
    }

    @Test fun modelDoesNotSupportTools() {
        assertTrue(ModelAccessErrors.of(400, """{"error":{"message":"registry.ollama.ai/library/gemma:2b does not support tools"}}""", "gemma") is ModelError.ToolsUnsupported)
        assertTrue(ModelAccessErrors.of(400, """{"error":{"message":"Function calling is not supported for this model"}}""", "x") is ModelError.ToolsUnsupported)
    }

    /** A 400 / 403 saying the model is missing: map it to model-not-found, carrying the original wording; a 404 that says nothing more is still handled by the caller. */
    @Test fun modelNotFoundInsideOtherStatuses() {
        val nf = ModelAccessErrors.of(400, """{"error":{"message":"Model Not Exist","type":"invalid_request_error"}}""", "m") as ModelError.ModelNotFound
        assertEquals("m", nf.model)
        assertEquals("Model Not Exist", nf.detail)
        assertTrue(ModelAccessErrors.of(400, """{"error":{"code":"model_not_found"}}""", "m") is ModelError.ModelNotFound)
        assertNull(ModelAccessErrors.of(404, """{"error":{"code":"model_not_found","message":"The model `x` does not exist or you do not have access to it."}}""", "m"))
    }

    /** For model-not-found we want only the wording from the JSON: the HTML page a reverse proxy returns for a wrong URL, and empty content, are not taken as the wording. */
    @Test fun jsonDetailTakesOnlyWhatJsonSays() {
        assertEquals("none of group/rpa's models is ready", ModelAccessErrors.jsonDetail("""{"error":{"type":"not_found_error","message":"none of group/rpa's models is ready"}}"""))
        assertEquals("no route", ModelAccessErrors.jsonDetail("""{"error":"no route"}"""))
        assertEquals("Not Found", ModelAccessErrors.jsonDetail("""{"detail":"Not Found"}"""))
        assertEquals("models/x not found", ModelAccessErrors.jsonDetail("""[{"error":{"code":404,"message":"models/x not found"}}]"""))
        assertEquals("", ModelAccessErrors.jsonDetail("<html><head><title>404 Not Found</title></head></html>"))
        assertEquals("", ModelAccessErrors.jsonDetail(""))
        assertEquals("<html><head><title>404 Not Found</title></head></html>", ModelAccessErrors.detail("<html><head><title>404 Not Found</title></head></html>"))
    }

    /** The wording is trimmed; overly long text is clipped and gets "…" appended. */
    @Test fun longDetailIsClippedWithAnEllipsis() {
        assertEquals("short", ModelAccessErrors.jsonDetail("""{"error":{"message":"  short \n"}}"""))
        val long = "models/x is not found. " + "y".repeat(200)
        val clipped = ModelAccessErrors.jsonDetail("""{"error":{"message":"$long"}}""")
        assertEquals(161, clipped.length)
        assertTrue(clipped, clipped.startsWith("models/x is not found. yyy") && clipped.endsWith("y…"))
        assertEquals(clipped, ModelAccessErrors.detail("""{"error":{"message":"$long"}}"""))
    }

    /** A 429 saying this account's quota for this model is zero: only another model will do, so map it to not-available; other 429s (genuine rate limiting, quota used up) are left to the caller. */
    @Test fun quotaZeroForThisModelIsNotAvailable() {
        val freeTierZero = """{"error":{"code":429,"message":"You exceeded your current quota, please check your plan and billing details. """ +
            """* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 0, model: gemini-x-pro",""" +
            """"status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generate_content_free_tier_requests"}]}]}}"""
        val e = notAvailable(429, freeTierZero)
        assertTrue(e.detail, e.detail.startsWith("You exceeded your current quota"))
        notAvailable(429, """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED","details":[{"violations":[{"quotaValue":"0"}]}]}}""")
        notAvailable(429, """{"error":{"code":429,"message":"This model doesn't have a free quota tier. Please enable billing.","status":"RESOURCE_EXHAUSTED"}}""")
        // The OpenAI-compatible endpoint returns an array
        val inArray = notAvailable(429, """[{"error":{"code":429,"message":"Quota exceeded for metric: free_tier_input_token_count, limit: 0","status":"RESOURCE_EXHAUSTED"}}]""")
        assertEquals("Quota exceeded for metric: free_tier_input_token_count, limit: 0", inArray.detail)
        assertNull(ModelAccessErrors.of(429, """{"error":{"code":429,"message":"Quota exceeded for metric: requests_per_minute, limit: 10. Please retry in 7s.","status":"RESOURCE_EXHAUSTED"}}""", "m"))
        assertNull(ModelAccessErrors.of(429, """{"error":{"code":429,"message":"Resource has been exhausted (e.g. check quota).","status":"RESOURCE_EXHAUSTED"}}""", "m"))
        assertNull(ModelAccessErrors.of(429, """{"error":{"code":429,"message":"limit: 0.5 rps","status":"RESOURCE_EXHAUSTED"}}""", "m"))
        assertNull(ModelAccessErrors.of(429, "", "m"))
    }

    /** The model is retired: 404 / 400 responses saying no longer available, retired, or model … deprecated map to not-available, carrying the original wording; Gemini's not found for API version maps to not-found. */
    @Test fun retiredModels() {
        assertEquals(
            "Gemini 1.5 Flash is no longer available. Please switch to a newer model.",
            notAvailable(404, """{"error":{"code":404,"message":"Gemini 1.5 Flash is no longer available. Please switch to a newer model.","status":"NOT_FOUND"}}""").detail,
        )
        notAvailable(400, """{"error":{"message":"The model gemini-1.0-pro has been retired."}}""")
        notAvailable(400, """{"error":{"message":"models/gemini-1.5-pro-001 is deprecated and no longer served"}}""")
        notAvailable(400, """{"error":{"message":"This model is deprecated."}}""")
        notAvailable(400, """{"error":{"message":"该模型已下线"}}""")
        assertTrue(
            ModelAccessErrors.of(400, """{"error":{"code":400,"message":"models/gemini-x is not found for API version v1beta, or is not supported for generateContent.","status":"INVALID_ARGUMENT"}}""", "m")
                is ModelError.ModelNotFound,
        )
        // One that only says some parameter is deprecated is not recognized
        assertNull(ModelAccessErrors.of(400, """{"error":{"message":"The parameter `max_tokens` is deprecated, use `max_completion_tokens`."}}""", "m"))
    }

    /** A 400 saying this model cannot go through the current endpoint (the provider requires another shape): count it as this model being unavailable, move on to the next one, and carry the server's own wording. */
    @Test fun modelCannotGoThroughThisEndpoint() {
        val shape = """{"error":{"message":"Model claude-sonnet-5-5 must be called via /provider/v1/messages (Anthropic Messages shape).","type":"invalid_request_error","param":"model","code":"unsupported_model"}}"""
        val e = notAvailable(400, shape)
        assertEquals("m", e.model)
        assertTrue(e.detail, e.detail.startsWith("Model claude-sonnet-5-5 must be called via /provider/v1/messages"))
        // One that gives only the error code
        notAvailable(400, """{"error":{"type":"invalid_request_error","param":"model","code":"unsupported_model"}}""")
        notAvailable(400, """{"error":{"message":"This model is only supported in v1/responses and not in v1/chat/completions.","param":"model"}}""")
        assertTrue(ModelAccessErrors.of(400, """{"error":{"message":"gpt-4o is not supported for this endpoint"}}""", "gpt-4o") is ModelError.ModelNotAvailable)
        // One where the request itself is wrong is not recognized; it is left to the caller to report by status code
        assertNull(ModelAccessErrors.of(400, """{"error":{"message":"Invalid value for 'temperature': 3"}}""", "m"))
        // It is the Key that cannot use this endpoint, not the model: not recognized; 403 is still treated by the caller as a Key problem
        assertNull(ModelAccessErrors.of(403, """{"error":{"message":"Your API key is not supported for this endpoint"}}""", "gpt-x"))
        assertTrue(ModelHttp.openAiError(403, null, """{"error":{"message":"Your API key is not supported for this endpoint"}}""", "gpt-x") is ModelError.Auth)
    }

    /** HTTP errors with nothing more specific are classified by status code (MK-1 batch 2): >= 400 becomes Server, so the fallback chain still holds. */
    @Test fun unmappedHttpErrorsBecomeServer() {
        val e = ModelHttp.openAiError(400, null, "Bad Request", "m")
        assertTrue(e is ModelError.Server)
        assertEquals(400, (e as ModelError.Server).status)
        assertTrue(ModelHttp.geminiError(400, null, "{}", "m") is ModelError.Server)
        // More specific wording wins: context-too-long is still recognized first and is not masked by the status-code classification
        assertTrue(ModelHttp.openAiError(400, null, "This model's maximum context length is 8192 tokens", "m") is ModelError.ContextTooLong)
    }

    /** Ones where the Key, the quota, or the request itself is wrong are not recognized; a 401 is never looked at. */
    @Test fun unmappedHttpErrorKeepsProviderDetailForDiagnostics() {
        val e = ModelHttp.openAiError(400, null, """{"error":{"type":"MissingSessionID"}}""", "m")
        assertTrue(e is ModelError.Server)
        assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("MissingSessionID"))
    }

    @Test fun otherErrorsAreLeftToTheAdapter() {
        assertNull(ModelAccessErrors.of(401, """{"error":{"message":"not activated"}}""", "m"))
        assertNull(ModelAccessErrors.of(400, """{"error":{"message":"Invalid parameter: messages"}}""", "m"))
        assertNull(ModelAccessErrors.of(403, """{"error":{"message":"Incorrect API key provided"}}""", "m"))
        assertNull(ModelAccessErrors.of(500, """{"error":{"message":"not activated"}}""", "m"))
        assertNull(ModelAccessErrors.of(400, "context_length_exceeded", "m"))
    }
}
