package com.wangzan101.mika.settings.ui.models

import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.model.chatgpt.SignInException
import com.wangzan101.mika.core.model.chatgpt.SignInFailure
import com.wangzan101.mika.core.model.chatgpt.signInWithChatGpt
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.oauth.AccountSignInException
import com.wangzan101.mika.core.model.oauth.AccountSignInFailure
import com.wangzan101.mika.core.model.oauth.DeviceChallenge
import com.wangzan101.mika.core.model.oauth.OpenCodeAuth
import com.wangzan101.mika.core.model.oauth.OpenRouterAuth
import com.wangzan101.mika.core.model.oauth.XaiAuth
import com.wangzan101.mika.core.model.oauth.signInWithOpenRouter
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.AccountOutcome
import com.wangzan101.mika.settings.DevicePrompt
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.chatGptProvider
import com.wangzan101.mika.settings.chatGptRegistration
import com.wangzan101.mika.settings.chatGptSession
import com.wangzan101.mika.settings.connectAccount
import com.wangzan101.mika.settings.connectChatGpt
import com.wangzan101.mika.settings.connectOpenRouter
import com.wangzan101.mika.settings.listModels
import com.wangzan101.mika.settings.openCodeProvider
import com.wangzan101.mika.settings.openRouterAccount
import com.wangzan101.mika.settings.rememberChatGptClient
import com.wangzan101.mika.settings.signOutAccount
import com.wangzan101.mika.settings.signOutChatGpt
import com.wangzan101.mika.settings.signOutOpenRouter
import com.wangzan101.mika.settings.xaiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Model setup: ChatGPT and account sign-in, and sign-out
/**
 * Opens the browser to sign in to ChatGPT; after the callback it exchanges the token, verifies it, lists models, saves (or updates) the ChatGPT provider and returns to Claire.
 * When there is no main model yet, one of its models is chosen as the main model. After the first successful registration it explains once that plan quota is being used. Failures state the reason (and are recorded for diagnostics).
 * Runs inside [signIns]: while the user signs in through the browser, Claire is in the background and cannot follow the UI.
 */
fun ModelSetupViewModel.signInWithChatGpt() {
    val browser = browser ?: return
    if (_state.value.chatGptSignIn == ListState.Running || signIns.running.value) return
    _state.update { it.copy(chatGptSignIn = ListState.Running, chatGptRevokeUnconfirmed = false) }
    signIns.start {
        val (listProblem, first) = try {
            withContext(io) {
                val (hostId, clientId) = store.chatGptRegistration()
                val existing = store.chatGptProvider()
                val session = signInWithChatGpt(
                    store.chatGptAuth, hostId, clientId, existing?.let { store.chatGptSession(it.id) },
                    openBrowser = browser::open, onRegistered = store::rememberChatGptClient,
                    page = { e -> browser.page(e?.let { ModelsCopy.chatGptFailure(it.reason, it.code) }) },
                )
                val listed = store.listModels(ProviderConfig.Kind.ChatGpt, ChatGptAuth.API_BASE, session.accessToken)
                val p = store.connectChatGpt(session, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty())
                if (!store.roles.value.hasMain) {
                    val options = ModelOptions.ofProvider(p, store.list())
                    (ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id)?.let { m -> use(ModelRole.Main, p, m, options) }
                }
                (listed as? ModelListResult.Failed)?.let(ModelsCopy::listFailure) to (clientId == null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = e as? SignInException ?: SignInException(SignInFailure.Network, e.message.orEmpty(), e)
            signIns.recordFailure(failure)
            browser.returnToApp()
            _state.update { it.copy(chatGptSignIn = ListState.Failed(ModelsCopy.chatGptFailure(failure.reason, failure.code))) }
            return@start
        }
        browser.returnToApp()
        val id = store.chatGptProvider()?.id
        _state.update {
            snapshot(it.copy(
                chatGptSignIn = ListState.Idle, chatGptWelcome = first, formOpen = false,
                listing = if (listProblem != null && id != null) it.listing + (id to ListState.Failed(listProblem)) else it.listing - id.orEmpty(),
            ))
        }
    }
}

/** Abandons the sign-in in progress (the local callback is closed along with it). */
fun ModelSetupViewModel.cancelChatGptSignIn() {
    signIns.cancel()
    _state.update { it.copy(chatGptSignIn = ListState.Idle) }
}

fun ModelSetupViewModel.dismissChatGptWelcome() = _state.update { it.copy(chatGptWelcome = false) }

/**
 * Sign in to xAI, OpenCode Go or OpenRouter with an account: for xAI and OpenCode Go, a verified page opens in the browser and a short code stays in the UI until the user confirms;
 * for OpenRouter, the authorization page opens and, once approved, control returns to the local callback and a Key belonging to the user is obtained.
 * On success it lists the models, saves this provider, picks a main model if there is none yet, then opens its details page ([ModelSetupUi.signedInProvider]).
 * Failures only state the category and error code (and are recorded for diagnostics). Like ChatGPT it runs inside [signIns]: Claire is in the background while the user is in the browser.
 */
fun ModelSetupViewModel.signInWithAccount(provider: AccountProvider) {
    val browser = browser ?: return
    if (signIns.running.value || _state.value.accountSignIn.running || _state.value.chatGptSignIn == ListState.Running) return
    _state.update { it.copy(accountSignIn = AccountSignInUi(provider = provider, running = true)) }
    val started = signIns.start(
        target = provider.target,
        noticeTitle = tr(R.string.account_signin_notif_title),
        noticeBody = tr(R.string.account_signin_notif_body),
    ) {
        try {
            val (p, failed) = withContext(io) {
                val (p, listed) = when (provider) {
                    AccountProvider.Xai -> connectXai(browser)
                    AccountProvider.OpenCodeGo -> connectOpenCodeGo(browser)
                    AccountProvider.OpenRouter -> connectOpenRouter(browser)
                }
                if (!store.roles.value.hasMain) {
                    val options = ModelOptions.ofProvider(p, store.list())
                    (ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id)?.let { m -> use(ModelRole.Main, p, m, options) }
                }
                p to (listed as? ModelListResult.Failed)
            }
            browser.returnToApp()
            _state.update {
                it.copy(listing = if (failed != null) it.listing + (p.id to ListState.Failed(ModelsCopy.listFailure(failed))) else it.listing - p.id)
            }
            signIns.finishAccount(AccountOutcome(provider.target, providerId = p.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = e as? AccountSignInException ?: AccountSignInException(AccountSignInFailure.Network, cause = e)
            signIns.recordAccountFailure(provider.name, failure.reason.name, failure.code)
            browser.returnToApp()
            signIns.finishAccount(AccountOutcome(provider.target, failure = ModelsCopy.accountFailure(failure)))
        }
    }
    if (!started) _state.update { it.copy(accountSignIn = AccountSignInUi()) }
}

/** xAI device-code sign-in: lists models and saves (or updates) that provider. */
internal suspend fun ModelSetupViewModel.connectXai(browser: SignInBrowser): Pair<Provider, ModelListResult> {
    val session = store.xaiAuth.signIn(openBrowser = browser::open, onChallenge = ::publishDevice)
    val existing = store.xaiProvider()
    val listed = store.listModels(ProviderConfig.Kind.Xai, session.apiBase.ifBlank { XaiAuth.API_BASE }, session.accessToken)
    return store.connectAccount(session, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty()) to listed
}

/** OpenCode Go device-code sign-in: lists models and saves (or updates) that provider. The listing is public; calls use the access token. */
internal suspend fun ModelSetupViewModel.connectOpenCodeGo(browser: SignInBrowser): Pair<Provider, ModelListResult> {
    val session = store.openCodeAuth.signIn(openBrowser = browser::open, onChallenge = ::publishDevice)
    val existing = store.openCodeProvider()
    val listed = store.listModels(ProviderConfig.Kind.OpenCodeGo, session.apiBase.ifBlank { OpenCodeAuth.API_BASE }, session.accessToken)
    return store.connectAccount(session, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty()) to listed
}

/** OpenRouter authorization exchanged for a Key: lists models and saves (or updates) that provider using the OpenRouter preset. */
internal suspend fun ModelSetupViewModel.connectOpenRouter(browser: SignInBrowser): Pair<Provider, ModelListResult> {
    val key = signInWithOpenRouter(store.openRouterAuth, browser::open, page = { e -> browser.page(e?.let(ModelsCopy::accountFailure)) })
    val existing = store.openRouterAccount()
    val listed = store.listModels(ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, key)
    return store.connectOpenRouter(key, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty()) to listed
}

/** Leaves the short code on screen. The browser is opened by the sign-in flow itself, so it is not opened again here. */
internal fun ModelSetupViewModel.publishDevice(c: DeviceChallenge) {
    val openUrl = c.verificationUriComplete ?: c.verificationUri
    val expiresAt = System.currentTimeMillis() + c.expiresInSec * 1000
    // The UI keeps showing it through signIns.device (a rebuilt UI receives it too)
    signIns.showDevice(DevicePrompt(c.userCode, openUrl, expiresAt))
}

fun ModelSetupViewModel.cancelAccountSignIn() {
    signIns.cancel()
    _state.update { it.copy(accountSignIn = AccountSignInUi()) }
}

fun ModelSetupViewModel.dismissAccountFailure() = _state.update { it.copy(accountSignIn = AccountSignInUi()) }

/** The details page has been opened. */
fun ModelSetupViewModel.signedInShown() = _state.update { it.copy(signedInProvider = null) }

/** Sign out of the account sign-in: xAI and OpenCode Go clear the local token, OpenRouter deletes the local Key; the provider and roles are kept so signing in again picks up where it left off. */
fun ModelSetupViewModel.signOutAccount(providerId: String) {
    scope.launch {
        withContext(io) {
            val p = store.provider(providerId) ?: return@withContext
            if (p.kind == ProviderConfig.Kind.Xai || p.kind == ProviderConfig.Kind.OpenCodeGo) store.signOutAccount(providerId)
            else store.signOutOpenRouter(providerId)
        }
        _state.update { snapshot(it.copy(listing = it.listing - providerId)) }
    }
}

/** Sign out of ChatGPT: revoke on the server and clear the local token; the provider and roles stay, so signing in again picks up where it left off. */
fun ModelSetupViewModel.signOutChatGpt(providerId: String) {
    scope.launch {
        val revoked = withContext(io) { store.signOutChatGpt(providerId) }
        _state.update { snapshot(it.copy(chatGptRevokeUnconfirmed = !revoked, listing = it.listing - providerId)) }
    }
}
