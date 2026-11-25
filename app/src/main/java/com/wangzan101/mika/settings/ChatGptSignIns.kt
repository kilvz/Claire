package com.wangzan101.mika.settings

import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.core.model.chatgpt.SignInException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The most recent sign-in failure, used for diagnostics only: the time, the kind of reason, the server's error code, and a short description (already redacted). */
data class ChatGptSignInFailureRecord(val atMs: Long, val kind: String, val code: String?, val detail: String)

/** Which provider this sign-in belongs to. The foreground service and a rebuilt UI rely on it, so that another provider's sign-in is not displayed as ChatGPT's. */
enum class SignInTarget { None, ChatGpt, Xai, OpenCodeGo, OpenRouter }

/** For device-code sign-in, the user code shown to the user, the verification page, and when it expires. The device code itself is not here. */
data class DevicePrompt(val userCode: String, val openUrl: String, val expiresAtMs: Long = 0)

/**
 * The result of one account sign-in (xAI, OpenCode Go, OpenRouter): on success the stored provider id, on failure the reason shown to the user.
 * It lives in [ChatGptSignIns] rather than in UI state: the UI may be rebuilt during a sign-in, and the result must reach whichever UI is there at that moment.
 */
data class AccountOutcome(val target: SignInTarget, val providerId: String? = null, val failure: String? = null)

/**
 * The single in-process copy of account sign-ins (ChatGPT, xAI, OpenCode Go, OpenRouter). A sign-in waits for the user to type the password and pass two-step verification in an external browser, while Claire is in the background:
 * it runs in the app's scope [scope] (continuing through UI rebuilds and going to the background), and at the start [keepAlive] is called to hold the process up (a foreground service,
 * so the process is not frozen and the local callback has someone to receive it); if that cannot start, a single line is logged and the sign-in proceeds. The foreground service watches [running] and stops itself when it goes back to false,
 * so success, failure, cancellation and timeout all stop it. Only one sign-in exists at a time; [cancel] gives up (the "cancel" on the notification goes through here as well).
 */
class ChatGptSignIns(
    private val scope: CoroutineScope,
    private val keepAlive: () -> Unit = {},
    private val log: (String, Throwable) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null

    /** The most recent failure (see [recordFailure]); kept in memory only. */
    @Volatile var lastFailure: ChatGptSignInFailureRecord? = null
        private set

    /** The most recent sign-in failure for other accounts (xAI, OpenCode Go, OpenRouter); the description is likewise already redacted. */
    @Volatile var lastAccountFailure: ChatGptSignInFailureRecord? = null
        private set

    /** Which provider is in progress; [SignInTarget.None] when no sign-in is running. */
    @Volatile var target: SignInTarget = SignInTarget.None
        private set

    /** The text on the foreground notification; when empty, the two ChatGPT strings are used. */
    @Volatile var noticeTitle: String? = null
        private set

    @Volatile var noticeBody: String? = null
        private set

    private val _device = MutableStateFlow<DevicePrompt?>(null)
    /** The user code that device-code sign-in is currently showing; cleared when the sign-in ends. After a UI rebuild it is used to keep showing it. */
    val device: StateFlow<DevicePrompt?> = _device.asStateFlow()
    val devicePrompt: DevicePrompt? get() = _device.value

    fun showDevice(prompt: DevicePrompt) {
        _device.value = prompt
    }

    private val _outcome = MutableStateFlow<AccountOutcome?>(null)
    /** The result of the most recent account sign-in; the UI calls [consumeOutcome] after displaying it. */
    val outcome: StateFlow<AccountOutcome?> = _outcome.asStateFlow()

    fun finishAccount(o: AccountOutcome) {
        _outcome.value = o
    }

    fun consumeOutcome(o: AccountOutcome) {
        _outcome.compareAndSet(o, null)
    }

    /** Starts one sign-in, running [block]; if one is already running it does not start, and returns false. */
    fun start(
        target: SignInTarget = SignInTarget.ChatGpt,
        noticeTitle: String? = null,
        noticeBody: String? = null,
        block: suspend CoroutineScope.() -> Unit,
    ): Boolean = synchronized(this) {
        if (_running.value) return false
        this.target = target
        this.noticeTitle = noticeTitle
        this.noticeBody = noticeBody
        _device.value = null
        _outcome.value = null
        _running.value = true
        runCatching(keepAlive).onFailure { log("sign-in keep-alive could not start", it) }
        // The app's scope has no exception handler: anything that leaks out is only logged, never allowed to crash the process
        val j = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("sign-in ended with an unexpected error", e)
            }
        }
        job = j
        j.invokeOnCompletion {
            synchronized(this) {
                if (job === j) {
                    job = null
                    _running.value = false
                    this.target = SignInTarget.None
                    _device.value = null
                    this.noticeTitle = null
                    this.noticeBody = null
                }
            }
        }
        true
    }

    fun cancel() {
        synchronized(this) { job }?.cancel()
    }

    fun recordFailure(e: SignInException) {
        val detail = Diagnostics.sanitize(e.message.orEmpty()).take(MAX_DETAIL)
        lastFailure = ChatGptSignInFailureRecord(now(), e.reason.name, e.code, detail)
    }

    /** Records an account sign-in failure. Only the kind and the error code are kept, and the description is redacted once more. */
    fun recordAccountFailure(provider: String, reason: String, code: String?) {
        val detail = Diagnostics.sanitize(listOfNotNull(reason, code).joinToString(": ")).take(MAX_DETAIL)
        lastAccountFailure = ChatGptSignInFailureRecord(now(), provider, code, detail)
    }

    private companion object {
        const val MAX_DETAIL = 160
    }
}
