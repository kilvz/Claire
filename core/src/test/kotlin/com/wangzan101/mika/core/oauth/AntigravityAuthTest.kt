package com.wangzan101.mika.core.oauth

import com.wangzan101.mika.core.chatgpt.TokenResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Antigravity Google OAuth: authorize URL, callback checks, token exchange/refresh, userinfo. No network. */
class AntigravityAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var auth: AntigravityAuth

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        auth = AntigravityAuth(
            OkHttpClient(),
            authUrl = server.url("/auth").toString(),
            tokenUrl = server.url("/token").toString(),
            userinfoUrl = server.url("/userinfo").toString(),
        )
    }
    @After fun tearDown() { server.shutdown() }

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun authorizeUrlCarriesClientScopesAndPkce() {
        val attempt = auth.newAttempt("http://127.0.0.1:9/auth/callback")
        val url = auth.authorizeUrl(attempt, "cid-123", "someone@example.com")
        assertTrue(url.contains("client_id=cid-123"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("code_challenge="))
        assertTrue(url.contains("state=${attempt.state}"))
        assertTrue(url.contains("login_hint=someone%40example.com"))
        assertTrue(url.contains("cloud-platform"))
        assertTrue(url.contains("access_type=offline"))
    }

    @Test fun readCallbackChecksStateAndRefusal() {
        val attempt = auth.newAttempt("http://127.0.0.1:9/auth/callback")
        assertEquals("code-1", auth.readCallback(mapOf("state" to attempt.state, "code" to "code-1"), attempt))
        try {
            auth.readCallback(mapOf("state" to "other", "code" to "code-1"), attempt)
            fail("expected state mismatch")
        } catch (e: AntigravitySignInException) {
            assertEquals(AntigravitySignInFailure.StateMismatch, e.reason)
        }
        try {
            auth.readCallback(mapOf("state" to attempt.state, "error" to "access_denied"), attempt)
            fail("expected denial")
        } catch (e: AntigravitySignInException) {
            assertEquals(AntigravitySignInFailure.Denied, e.reason)
        }
    }

    @Test fun exchangeSendsClientSecretToTokenEndpointOnly() = runTest {
        server.enqueue(ok("""{"access_token":"at","refresh_token":"rt","expires_in":3600,"scope":"openid email"}"""))
        val attempt = auth.newAttempt("http://127.0.0.1:9/auth/callback")
        val r = auth.exchange("cid-123", "csecret", "code-1", attempt)
        val tokens = (r as? TokenResult.Ok)?.tokens ?: error("expected tokens")
        assertEquals("at", tokens.accessToken)
        assertEquals("rt", tokens.refreshToken)
        val req = server.takeRequest()
        assertEquals("/token", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("grant_type=authorization_code"))
        assertTrue(body.contains("client_secret=csecret"))
        assertTrue(body.contains("code_verifier=${attempt.verifier}"))
    }

    @Test fun refreshKeepsOldTokenWhenNoneReturned() = runTest {
        server.enqueue(ok("""{"access_token":"at2","expires_in":3600}"""))
        val r = auth.refresh("cid-123", "csecret", "rt")
        val tokens = (r as? TokenResult.Ok)?.tokens ?: error("expected tokens")
        assertEquals("at2", tokens.accessToken)
        assertNull(tokens.refreshToken)
    }

    @Test fun rejectedExchangeKeepsServerCode() = runTest {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"error":"invalid_grant","error_description":"bad code"}"""))
        val r = auth.exchange("cid-123", "csecret", "code-1", auth.newAttempt("http://127.0.0.1:9/auth/callback"))
        val rejected = r as? TokenResult.Rejected ?: error("expected rejection")
        assertEquals("invalid_grant", rejected.code)
        assertTrue(rejected.terminal)
    }

    @Test fun fetchEmailReadsUserinfoAndToleratesFailure() = runTest {
        server.enqueue(ok("""{"email":"someone@example.com"}"""))
        assertEquals("someone@example.com", auth.fetchEmail("at"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertNull(auth.fetchEmail("at"))
    }

    @Test fun revokeConfirmsOn200() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        assertTrue(auth.revoke("rt"))
    }
}
