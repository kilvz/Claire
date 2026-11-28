package com.wangzan101.mika.core.model.chatgpt.check

import com.wangzan101.mika.core.model.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.model.chatgpt.ChatGptSession
import com.wangzan101.mika.core.model.chatgpt.ChatGptTokens
import com.wangzan101.mika.core.model.chatgpt.SignInException
import com.wangzan101.mika.core.model.chatgpt.SignInFailure
import com.wangzan101.mika.core.model.chatgpt.signInWithChatGpt
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelCatalog
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Walks the entire "Sign in with ChatGPT" path on desktop: uses the app's own sign-in, model-listing, and Responses code
 * against the real OpenAI service, so the server side can be confirmed before moving to a phone. Usage: see docs/dev/chatgpt-signin-check.md.
 *
 * The host identifier and the registered client_id are stored in ~/.mika-dev/chatgpt-check/ (directory 0700) and reused
 * across runs, just like in the app. Tokens, authorization codes, and ID tokens are never printed; by default they stay in
 * memory only, and `--keep` writes them to session.json (0600) in the same directory.
 *
 * Arguments: `--timeout=<seconds>` how long to wait for the callback (default 300); `--model=<slug>` selects the model;
 * `--keep` saves the sign-in.
 */
fun main(args: Array<String>) {
    val opts = args.associate { a -> a.removePrefix("--").let { it.substringBefore('=') to it.substringAfter('=', "") } }
    val timeoutSec = opts["timeout"]?.toLongOrNull()?.takeIf { it > 0 } ?: 300L
    val ok = runBlocking { Check(timeoutSec, opts["model"]?.takeIf { it.isNotBlank() }, "keep" in opts).run() }
    exitProcess(if (ok) 0 else 1)
}

private class Check(private val timeoutSec: Long, private val modelArg: String?, private val keep: Boolean) {
    private val dir = File(System.getProperty("user.home"), ".mika-dev/chatgpt-check")
    private val hostFile = File(dir, "host.json")
    private val sessionFile = File(dir, "session.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
    private val auth = ChatGptAuth(http)
    private val results = mutableListOf<Pair<String, Boolean>>()

    suspend fun run(): Boolean {
        println("== Claire: Sign in with ChatGPT · desktop check ==")
        println("Working directory: ${dir.absolutePath} (tokens are not printed; ${if (keep) "--keep: save the sign-in at the end" else "tokens are not saved by default"})")
        prepareDir()
        val (hostId, savedClient) = registration()
        println("Host identifier: ${if (savedClient == null) "first run, will register a new client" else "reusing the registered client"}")

        var session = signIn(hostId, savedClient) ?: return summary()
        val tokens = ChatGptTokens(auth, { session }, { session = it })

        val model = listModels(session.accessToken)
        if (model != null) {
            val caps = Capabilities(
                toolCalls = true, vision = model.vision ?: false,
                contextTokens = model.contextTokens ?: 128_000, reasoning = model.reasoning ?: false,
            )
            val responses = ResponsesModel(ChatGptAuth.API_BASE, tokens, model.id, caps, http, chatGptPlan = true)
            simpleCall(responses)
            toolRoundTrip(responses)
        }
        refresh(tokens) { session }
        if (keep) save(session)
        return summary()
    }

    // ---------- 1. Host identifier ----------

    private fun prepareDir() {
        dir.mkdirs()
        runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) }
    }

    private fun registration(): Pair<String, String?> {
        val saved = runCatching { json.parseToJsonElement(hostFile.readText()).jsonObject }.getOrNull()
        val host = saved?.get("hostId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val client = saved?.get("clientId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (host != null) return host to client
        val fresh = ChatGptAuth.newHostId()
        writePrivate(hostFile, buildJsonObject { put("hostId", fresh) }.toString())
        return fresh to null
    }

    private fun rememberClient(hostId: String, clientId: String) {
        writePrivate(hostFile, buildJsonObject { put("hostId", hostId); put("clientId", clientId) }.toString())
        step("Register client (client_id saved to ${hostFile.name})", true)
    }

    // ---------- 2–3. Sign-in ----------

    private suspend fun signIn(hostId: String, clientId: String?): ChatGptSession? {
        // Only take the previous email as a sign-in hint; without the ID token, the printed authorization URL contains no token
        val previous = if (sessionFile.isFile) ChatGptSession.fromJson(sessionFile.readText())?.signedOut() else null
        return try {
            val s = signInWithChatGpt(
                auth, hostId, clientId, previous,
                openBrowser = { url -> openBrowser(url) },
                onRegistered = { rememberClient(hostId, it) },
                page = ::callbackPage,
                timeoutMs = timeoutSec * 1000,
            )
            step("Callback received (state verified)", true)
            step("Exchange the authorization code for tokens", true)
            step("Verify the ID token (signature, issuer, audience, expiry, nonce)", true)
            step("Granted ${ChatGptAuth.PLAN_SCOPE}", true)
            println("    Granted scopes: ${s.scopes.joinToString(" ")}")
            println("    Account: ${s.email.ifEmpty { "(no email in the ID token)" }}")
            println("    Access token valid for: about ${(s.expiresAtMs - System.currentTimeMillis()) / 1000} seconds; refresh token: ${if (s.refreshToken.isNotEmpty()) "present" else "none"}")
            s
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignInException) {
            step(signInStep(e.reason), false, explain(e))
            null
        } catch (e: Exception) {
            step("Sign-in", false, "${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun openBrowser(url: String) {
        println("Authorization URL (no token):")
        println("    $url")
        val opened = runCatching {
            System.getProperty("os.name").lowercase().contains("mac") &&
                ProcessBuilder("open", url).inheritIO().start().waitFor(10, TimeUnit.SECONDS)
        }.getOrDefault(false)
        step(if (opened) "Opened the authorization page in the default browser" else "Could not open the browser automatically; copy the URL above and open it manually", opened, null, record = false)
        println("Waiting for the callback… (up to $timeoutSec seconds; Ctrl+C to give up)")
    }

    private fun callbackPage(failure: SignInException?): String {
        val text = if (failure != null) "Sign-in failed (${failure.reason}); check the terminal for the reason." else "Sign-in complete; you can close this page and check the result in the terminal."
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Mika</title></head>" +
            "<body style=\"font-family:system-ui;margin:3em\"><p>$text</p></body></html>"
    }

    private fun signInStep(r: SignInFailure): String = when (r) {
        SignInFailure.Denied, SignInFailure.StateMismatch, SignInFailure.ClientMismatch, SignInFailure.NoClientId -> "Check callback"
        SignInFailure.TimedOut -> "Wait for callback"
        SignInFailure.Exchange, SignInFailure.CodeExpired, SignInFailure.Region, SignInFailure.Blocked -> "Exchange the authorization code for tokens"
        SignInFailure.IdToken -> "Verify the ID token"
        SignInFailure.NoPlanScope -> "Granted ${ChatGptAuth.PLAN_SCOPE}"
        SignInFailure.Network -> "Connect to the OpenAI sign-in service"
    }

    // ---------- 4. List models ----------

    private suspend fun listModels(accessToken: String): ListedModel? {
        val listed = when (val r = ModelCatalog(http).openAiCompat(ChatGptAuth.API_BASE, accessToken)) {
            is ModelListResult.Ok -> r.models
            is ModelListResult.Failed -> {
                step("List models (GET /v1/models)", false, r.message)
                return null
            }
        }
        step("List models (GET /v1/models): ${listed.size}", listed.isNotEmpty(), if (listed.isEmpty()) "the list is empty" else null)
        listed.forEach { m ->
            val flags = listOfNotNull(
                m.contextTokens?.let { "context $it" }, m.vision?.let { if (it) "vision" else "no vision" },
                m.reasoning?.takeIf { it }?.let { "reasoning" }, m.toolCalls?.let { if (it) "tools" else "no tools" },
            )
            println("    ${m.id}${m.displayName.takeIf { it.isNotEmpty() && it != m.id }?.let { "  「$it」" }.orEmpty()}" +
                if (flags.isEmpty()) "" else "  (${flags.joinToString(",")})")
        }
        val chosen = if (modelArg != null) {
            listed.firstOrNull { it.id == modelArg } ?: ListedModel(modelArg).also { println("    Note: --model=$modelArg is not in the list; trying it anyway") }
        } else {
            // Prefer a reasoning model: the tool-call step then also verifies replay of encrypted reasoning
            listed.firstOrNull { it.usable && it.reasoning == true && it.toolCalls != false } ?: listed.firstOrNull { it.usable }
        }
        chosen?.let { println("    Using model: ${it.id}") }
        return chosen
    }

    // ---------- 5. A single simple call ----------

    private suspend fun simpleCall(model: ResponsesModel) {
        val name = "Responses call"
        try {
            val reply = model.complete("", listOf(Message(Role.User, listOf(Part.Text("Reply with the single word: pong")), Author.User)), emptyList())
            val text = reply.text()
            step(name, text.isNotBlank(), if (text.isBlank()) "the answer is empty" else null)
            println("    Answer: ${text.trim().take(200)}")
            println("    ${usage(reply.usage)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            step(name, false, explain(e))
        }
    }

    // ---------- 6. Tool-call round trip ----------

    private suspend fun toolRoundTrip(model: ResponsesModel) {
        val tool = ToolSpec(
            "get_time", "Returns the current local time as an ISO-8601 string.",
            """{"type":"object","properties":{},"additionalProperties":false}""",
        )
        val system = "You can call tools. When asked for the time, call get_time, then answer with the time it returned."
        val history = mutableListOf(Message(Role.User, listOf(Part.Text("What time is it now? Use the get_time tool.")), Author.User))
        try {
            val first = model.complete(system, history, listOf(tool))
            val call = first.parts.filterIsInstance<Part.ToolCall>().firstOrNull()
            if (call == null) {
                step("Tool call: the model calls get_time", false, "the model did not call a tool and answered directly: ${first.text().take(120)}")
                return
            }
            step("Tool call: the model called ${call.name} (args ${call.argsJson.take(80)})", call.name == tool.name)
            println("    Encrypted reasoning: ${if (call.opaque != null) "present; replayed as-is in the next turn (store:false)" else "none (not returned by the model or not a reasoning model)"}")
            println("    ${usage(first.usage)}")
            val now = OffsetDateTime.now().withNano(0).toString()
            history += Message(Role.Assistant, first.parts, Author.Mika)
            history += Message(Role.Tool, listOf(Part.ToolResult(call.id, """{"time":"$now"}""", isError = false)), Author.Mika)
            val second = model.complete(system, history, listOf(tool))
            val text = second.text()
            val more = second.parts.any { it is Part.ToolCall }
            step("Tool call: answer after receiving function_call_output", text.isNotBlank() && !more,
                when { more -> "the model called another tool"; text.isBlank() -> "the answer is empty"; else -> null })
            println("    Answer: ${text.trim().take(300)}")
            println("    ${usage(second.usage)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            step("Tool-call round trip", false, explain(e))
        }
    }

    // ---------- 7. Refresh the token ----------

    private suspend fun refresh(tokens: ChatGptTokens, current: () -> ChatGptSession) {
        val before = current()
        try {
            tokens.get(fresh = true)
            val after = current()
            step("Exchange the refresh token for new tokens", true)
            println("    Access token ${if (after.accessToken != before.accessToken) "updated" else "unchanged"};" +
                "refresh token ${if (after.refreshToken != before.refreshToken) "rotated" else "unchanged"};" +
                "new validity about ${(after.expiresAtMs - System.currentTimeMillis()) / 1000} seconds")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            step("Exchange the refresh token for new tokens", false, explain(e))
        }
    }

    private fun save(session: ChatGptSession) {
        writePrivate(sessionFile, session.toJson())
        println("Saved the sign-in to ${sessionFile.absolutePath} (0600)")
    }

    // ---------- Output ----------

    private fun step(name: String, ok: Boolean, why: String? = null, record: Boolean = true) {
        println("${if (ok) "✓" else "✗"} $name")
        if (!ok && why != null) println("    Reason: $why")
        if (record) results += name to ok
    }

    private fun summary(): Boolean {
        val failed = results.filterNot { it.second }
        println()
        println("== Summary: ${results.size - failed.size}/${results.size} steps passed ==")
        failed.forEach { println("✗ ${it.first}") }
        return failed.isEmpty() && results.isNotEmpty()
    }

    private fun ModelReply.text() = parts.filterIsInstance<Part.Text>().joinToString("") { it.text }

    private fun usage(u: Usage?) = u?.let { "Usage: input ${it.inputTokens} (cached ${it.cachedInputTokens}), output ${it.outputTokens}" } ?: "Usage: not provided by the server"

    private fun writePrivate(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }
}

/** Turns known errors into plain language. */
private fun explain(e: Throwable): String = when (e) {
    is SignInException -> when (e.reason) {
        SignInFailure.Denied -> "The authorization page returned an error (${e.message?.substringAfter(": ", "")}): " +
            "access_denied means the user cancelled on the authorization page; if the page says the required permission is unavailable, the account is not Plus or Pro and cannot share its plan allowance with other apps"
        SignInFailure.StateMismatch -> "The state in the callback does not match the one sent by this run (not a callback for this sign-in)"
        SignInFailure.ClientMismatch -> "The client_id in the callback does not match the saved one: delete clientId from ~/.mika-dev/chatgpt-check/host.json and start over"
        SignInFailure.NoClientId -> "This was the first registration, but the callback did not include a client_id"
        SignInFailure.TimedOut -> "Timed out waiting for the callback: sign-in was not completed in the browser, or the callback did not reach 127.0.0.1"
        SignInFailure.Exchange -> "Token exchange rejected (${e.message?.substringAfter(": ", "")}): redirect_uri or PKCE mismatch, or another reason"
        SignInFailure.CodeExpired -> "Token exchange rejected (${e.message?.substringAfter(": ", "")}): the authorization code expired, was already used, or does not match this run"
        SignInFailure.Region -> "Token exchange rejected (${e.message?.substringAfter(": ", "")}): the region of the current network is not supported"
        SignInFailure.Blocked -> "Token exchange rejected (${e.message?.substringAfter(": ", "")}): the response was a web page instead of JSON, so the request was blocked by protection"
        SignInFailure.IdToken -> "The ID token failed verification (${e.message?.substringAfter(": ", "")})"
        SignInFailure.NoPlanScope -> "${ChatGptAuth.PLAN_SCOPE} was not granted: this account's plan cannot share its allowance with other apps (Plus or Pro required), or it was not selected during authorization"
        SignInFailure.Network -> "Cannot reach the OpenAI sign-in service (${e.message?.substringAfter(": ", "")})"
    }
    is ModelError.ChatGptNotEligible -> "subscription_sharing_user_not_eligible: this account cannot use its ChatGPT allowance in other apps (not allowed by plan, region or account policy)" +
        e.detail.takeIf { it.isNotEmpty() }?.let { "; the server said: $it" }.orEmpty()
    is ModelError.ChatGptLimitReached -> "usage_limit_exceeded: the ChatGPT plan's allowance for other apps has reached its limit; wait for it to reset and try again (usage: ${ChatGptAuth.USAGE_URL})"
    is ModelError.SignInExpired -> "The refresh token no longer works (revoked, expired or reused); sign in again"
    is ModelError.Auth -> "The token was rejected or the client configuration is wrong (401/403, client_id not recognized)"
    is ModelError.ModelNotFound -> "model_not_found: this account cannot use this model; choose another (--model=…)"
    is ModelError.ContextTooLong -> "context_length_exceeded"
    is ModelError.Network -> "Network connection failed: ${e.cause?.message.orEmpty()}"
    is ModelError.Server -> "Server error (HTTP ${e.status}); try again later"
    is ModelError.BadResponse -> if (e.message.orEmpty().contains("The server does not support ")) {
        val item = e.message.orEmpty().substringAfter("The server does not support ").substringBefore(" in the request")
        "unsupported_capability:`$item` in the request is not supported"
    } else e.message.orEmpty()
    else -> "${e::class.simpleName}: ${e.message}"
}
