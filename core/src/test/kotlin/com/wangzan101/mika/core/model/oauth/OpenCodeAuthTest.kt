package com.wangzan101.mika.core.model.oauth

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class OpenCodeAuthTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    // Bind to 127.0.0.1 so the loopback exemption in OauthUrls applies to the mock server's own address
    @Before fun setUp() { server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) } }
    @After fun tearDown() { server.shutdown() }

    private val origin get() = "http://127.0.0.1:${server.port}"
    private val host get() = "127.0.0.1"

    private fun json(code: Int = 200, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun signInUsesTheDeviceGrantAndResolvesRelativeVerificationPaths() = runTest {
        val access = "opencode-access-SECRET"
        val refresh = "opencode-refresh-SECRET"
        var now = 1_000L
        val auth = auth()
        val opened = mutableListOf<String>()
        val challenges = mutableListOf<DeviceChallenge>()
        server.enqueue(json(body = """{"device_code":"dev-SECRET","user_code":"ABCD-1234","verification_uri":"/console/device","verification_uri_complete":"/console/device?user_code=ABCD-1234","expires_in":60,"interval":1}"""))
        server.enqueue(json(400, """{"_tag":"DeviceTokenError","error":"authorization_pending"}"""))
        server.enqueue(json(body = """{"access_token":"$access","refresh_token":"$refresh","expires_in":3600}"""))
        server.enqueue(json(body = """{"data":{"user":{"id":"user-7","email":"a@b.test"}}}"""))
        val session = auth.signIn(
            openBrowser = { opened += it },
            onChallenge = { challenges += it },
            sleep = { now += it },
            clock = { now },
        )
        assertEquals(OpenCodeAuth.PROVIDER, session.provider)
        assertEquals("user-7", session.accountId)
        assertEquals("a@b.test", session.label)
        assertEquals(access, session.accessToken)
        assertEquals(refresh, session.refreshToken)
        assertEquals(OpenCodeAuth.API_BASE, session.apiBase)
        assertEquals(OpenCodeAuth.CLIENT_ID, session.clientId)
        assertEquals(origin, session.issuer)
        assertEquals("$origin/console/auth/device/token", session.tokenUrl)
        // The relative path from OpenCode becomes absolute against the console, and the complete page carries the code
        assertEquals("$origin/console/device", challenges.single().verificationUri)
        assertEquals("$origin/console/device?user_code=ABCD-1234", opened.single())
        assertFalse(opened.single(), opened.single().contains("dev-SECRET"))
        assertFalse(opened.single(), opened.single().contains(access))

        server.takeRequest() // device code
        server.takeRequest() // pending
        val tokenRequest = server.takeRequest()
        assertEquals("/console/auth/device/token", tokenRequest.path)
        val tokenBody = tokenRequest.body.readUtf8()
        assertTrue(tokenBody.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
        assertTrue(tokenBody.contains("device_code=dev-SECRET"))
        assertTrue(tokenBody.contains("client_id=opencode-cli"))
        val identityRequest = server.takeRequest()
        assertEquals("Bearer $access", identityRequest.getHeader("Authorization"))
    }

    @Test fun deviceRequestDeclaresOrgScopeAndSendsNoEmptyScope() = runTest {
        val auth = auth()
        server.enqueue(json(body = """{"device_code":"d","user_code":"U","verification_uri":"/console/device","expires_in":60,"interval":1}"""))
        server.enqueue(json(body = """{"access_token":"a","refresh_token":"r","expires_in":60}"""))
        server.enqueue(json(code = 401, body = """{"_tag":"Unauthorized"}"""))
        auth.signIn(openBrowser = {}, onChallenge = {}, sleep = {}, clock = { 0 })
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("client_id=opencode-cli"))
        assertTrue(body.contains("supports_org_scope=true"))
        assertFalse(body, body.split("&").any { it.startsWith("scope=") })
    }

    @Test fun foreignVerificationHostIsRefusedBeforeOpeningIt() = runTest {
        var now = 0L
        val auth = OpenCodeAuth(http, consoleBase = origin, allowedHosts = setOf(host))
        server.enqueue(json(body = """{"device_code":"d","user_code":"U","verification_uri":"https://phish.example/console/device","expires_in":60,"interval":1}"""))
        var opened = false
        val e = runCatching {
            auth.signIn(openBrowser = { opened = true }, onChallenge = {}, sleep = { now += it }, clock = { now })
        }.exceptionOrNull() as AccountSignInException
        assertEquals(AccountSignInFailure.BadIssuer, e.reason)
        assertFalse(opened)
        assertEquals(1, server.requestCount)
    }

    @Test fun refreshUsesTheStoredTokenEndpointAndKeepsTheNewRefreshToken() = runTest {
        val auth = auth()
        val session = AccountSession(
            provider = OpenCodeAuth.PROVIDER, accountId = "u", accessToken = "old", refreshToken = "refresh-SECRET",
            clientId = OpenCodeAuth.CLIENT_ID, issuer = origin, tokenUrl = "$origin/console/auth/device/token",
        )
        server.enqueue(json(body = """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":10}"""))
        val ok = auth.refresh(session) as OauthTokenResult.Ok
        assertEquals("new-access", ok.tokens.accessToken)
        assertEquals("new-refresh", ok.tokens.refreshToken)
        val req = server.takeRequest()
        assertEquals("/console/auth/device/token", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("refresh-SECRET"))
        assertTrue(body.contains("client_id=opencode-cli"))
    }

    @Test fun rejectedRefreshIsTerminalAndNoRefreshTokenIsRejectedLocally() = runTest {
        val auth = auth()
        val session = AccountSession(
            provider = OpenCodeAuth.PROVIDER, accessToken = "old", refreshToken = "bad",
            tokenUrl = "$origin/console/auth/device/token",
        )
        server.enqueue(json(code = 400, body = """{"_tag":"DeviceTokenError","error":"invalid_grant"}"""))
        val rejected = auth.refresh(session) as OauthTokenResult.Rejected
        assertEquals("invalid_grant", rejected.code)
        assertTrue(rejected.terminal)
        val empty = auth.refresh(session.copy(refreshToken = "")) as OauthTokenResult.Rejected
        assertTrue(empty.terminal)
        assertEquals(1, server.requestCount)
    }

    private fun auth() = OpenCodeAuth(http, consoleBase = origin, allowedHosts = setOf(host))
}
