package com.wangzan101.mika.ui

import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.oauth.OpenRouterAuth
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ChatGptSignIns
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.configFor
import com.wangzan101.mika.settings.connect
import com.wangzan101.mika.settings.connectOpenRouter
import com.wangzan101.mika.settings.openRouterAccount
import com.wangzan101.mika.settings.providerKey
import com.wangzan101.mika.settings.signOutOpenRouter
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.wangzan101.mika.settings.ui.models.AccountProvider
import com.wangzan101.mika.settings.ui.models.ModelSetupViewModel
import com.wangzan101.mika.settings.ui.models.SignInBrowser
import com.wangzan101.mika.settings.ui.models.signInWithAccount
import com.wangzan101.mika.settings.ui.models.signOutAccount
import com.wangzan101.mika.settings.ui.models.signedInShown

/**
 * Sign in with an OpenRouter account, end to end: the (fake) browser opens the authorization page, comes back to the local callback with the code, and exchanges it for a Key,
 * stored as a provider with the OpenRouter preset, with a main model picked and the details opened; signing out only deletes the local Key. The key-exchange endpoint is a local fake server.
 */
class OpenRouterSignInTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val server = MockWebServer().also { it.start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient()
    private val listCalls = mutableListOf<Pair<String, String?>>()

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun store(
        models: List<ListedModel> = listOf(ListedModel("openai/gpt-x"), ListedModel("anthropic/claude-x")),
    ) = ProviderStore(
        tmp.root, AesGcmKeyCipher { key },
        openRouterAuth = OpenRouterAuth(http, keysUrl = server.url("/api/v1/auth/keys").toString()),
        modelLister = { _, url, apiKey, _ ->
            synchronized(listCalls) { listCalls += url to apiKey }
            ModelListResult.Ok(models)
        },
    )

    /** Fake browser: when the authorization page is opened it calls the local callback with code and state just as an approving user would; [pages] records what the browser shows after the callback. */
    private inner class Browser(private val approve: Boolean = true) : SignInBrowser {
        val opened = mutableListOf<String>()
        val pages = mutableListOf<String?>()
        override fun open(url: String) {
            opened += url
            val u = url.toHttpUrl()
            val callback = u.queryParameter("callback_url")!!.toHttpUrl().newBuilder().host("127.0.0.1")
            if (approve) callback.addQueryParameter("code", "the-code").addQueryParameter("state", u.queryParameter("state"))
            else callback.addQueryParameter("error", "access_denied")
            Thread { http.newCall(Request.Builder().url(callback.build()).build()).execute().close() }.start()
        }
        override fun returnToApp() {}
        override fun page(failure: String?): String { pages += failure; return "<p>ok</p>" }
    }

    private fun ModelSetupViewModel.signInAndWait(provider: AccountProvider) = runBlocking {
        signInWithAccount(provider)
        withTimeout(15_000) { state.first { !it.accountSignIn.running && (it.signedInProvider != null || it.accountSignIn.failure != null) } }
    }

    @Test fun signInStoresAnEncryptedKeyAndOpensTheProvider() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"key":"sk-or-v1-SECRET"}"""))
        val s = store()
        val browser = Browser()
        val vm = ModelSetupViewModel(s, scope, io = Dispatchers.IO, browser = browser, signIns = ChatGptSignIns(scope))
        val ui = vm.signInAndWait(AccountProvider.OpenRouter)

        val p = s.openRouterAccount()!!
        assertEquals(p.id, ui.signedInProvider)
        assertEquals(ProviderConfig.Kind.OpenAiCompat, p.kind)
        assertEquals(OpenRouterAuth.API_BASE, p.baseUrl)
        assertEquals("OpenRouter", p.name)
        assertEquals("sk-or-v1-SECRET", s.providerKey(p.id))
        assertEquals(listOf(OpenRouterAuth.API_BASE to "sk-or-v1-SECRET"), listCalls)
        assertTrue(ui.openRouterAccount!!.signedIn)
        assertNotNull("pick one when there is no main model yet", ui.roles.main)
        assertEquals(listOf<String?>(null), browser.pages)
        assertTrue(browser.opened.single().startsWith("https://openrouter.ai/auth?"))
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent, sent.contains("\"code\":\"the-code\""))
        // The Key is stored encrypted only and never goes into a config file
        tmp.root.walk().filter(File::isFile).forEach { f -> assertFalse(f.name, f.readText().contains("SECRET")) }
        // After reloading it is still recognized as coming from sign-in
        assertEquals(p.id, store().openRouterAccount()?.id)

        vm.signedInShown()
        assertNull(vm.state.value.signedInProvider)
        runBlocking {
            vm.signOutAccount(p.id)
            withTimeout(5_000) { vm.state.first { it.openRouterAccount?.signedIn == false } }
        }
        assertNull(s.providerKey(p.id))
        assertNotNull("provider and model are kept", s.provider(p.id)?.models)
        assertTrue(vm.state.value.signedOut(s.provider(p.id)!!))
    }

    @Test fun deniedAuthorizationSaysSoAndSavesNothing() {
        val s = store()
        val browser = Browser(approve = false)
        val vm = ModelSetupViewModel(s, scope, io = Dispatchers.IO, browser = browser, signIns = ChatGptSignIns(scope))
        val ui = vm.signInAndWait(AccountProvider.OpenRouter)
        assertEquals(AccountProvider.OpenRouter, ui.accountSignIn.provider)
        assertEquals("已取消登录。", ui.accountSignIn.failure)
        assertEquals(listOf<String?>("已取消登录。"), browser.pages)
        assertTrue(s.providers.value.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun accountSignInPicksAUsableMainModel() {
        val models = listOf(
            ListedModel("fishaudio/drama-3-int4"),
            ListedModel("vendor/synth", unsupportedApis = listOf("/v1/audio/speech")),
            ListedModel("vendor/opaque"),
        )
        val s = store(models)
        val existing = s.connectOpenRouter("old-key", models)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"key":"sk-or-v1-NEW"}"""))
        val vm = ModelSetupViewModel(s, scope, io = Dispatchers.IO, browser = Browser(), signIns = ChatGptSignIns(scope))

        val ui = vm.signInAndWait(AccountProvider.OpenRouter)

        assertEquals(existing.id, ui.signedInProvider)
        assertTrue(ui.openRouterAccount!!.signedIn)
        assertEquals(models, s.openRouterAccount()?.models)
        // No longer excluded by the ttsModels marker: the first usable chat model becomes the main model
        assertNotNull(ui.roles.main)
        assertEquals("fishaudio/drama-3-int4", s.configFor(com.wangzan101.mika.core.model.ModelRole.Main)?.model)
    }

    /**
     * The user has already typed an OpenRouter Key themselves: signing in saves a separate provider and does not replace that Key; signing out deletes only the one that came from sign-in.
     * Typing a Key again later with "Test and save" updates only the user's own provider and leaves the signed-in one alone.
     */
    @Test fun signInKeepsAKeyTheUserTypedForOpenRouter() {
        val s = store()
        val typed = s.connect("OpenRouter", ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, "sk-typed", emptyList())
        val signed = s.connectOpenRouter("sk-or-v1-signed", emptyList())
        assertFalse(typed.id == signed.id)
        assertEquals("sk-typed", s.providerKey(typed.id))
        assertEquals("sk-or-v1-signed", s.providerKey(signed.id))
        assertNull(typed.signIn)
        assertEquals(signed.id, s.openRouterAccount()?.id)
        // Signing in once more updates the signed-in provider instead of adding a third one
        assertEquals(signed.id, s.connectOpenRouter("sk-or-v1-again", emptyList()).id)
        assertEquals(2, s.providers.value.size)
        // Typing the Key again updates the user's own provider
        assertEquals(typed.id, s.connect("OpenRouter", ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, "sk-typed-2", emptyList()).id)
        assertEquals("sk-or-v1-again", s.providerKey(signed.id))
        s.signOutOpenRouter(signed.id)
        assertNull(s.providerKey(signed.id))
        assertEquals("sk-typed-2", s.providerKey(typed.id))
        // The provider created by typing a Key must not be treated as a sign-in to sign out
        s.signOutOpenRouter(typed.id)
        assertEquals("sk-typed-2", s.providerKey(typed.id))
        // After reloading, the two providers are still kept apart
        val reloaded = store()
        assertEquals(signed.id, reloaded.openRouterAccount()?.id)
        assertEquals("sk-typed-2", reloaded.providerKey(typed.id))
    }
}
