package com.wangzan101.mika.telegram

import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.wechat.WechatAccount
import com.wangzan101.mika.core.wechat.WechatApi
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.wechat.WechatChannel
import com.wangzan101.mika.wechat.WechatStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress

class TelegramChannelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }

    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun api(server: MockWebServer) =
        TelegramApi(OkHttpClient(), server.url("/").toString().removeSuffix("/"))

    private fun configuredStore(dir: File): TelegramStore {
        val store = TelegramStore(dir, cipher)
        store.saveBot("test-token", "123")
        store.setEnabled(true)
        return store
    }

    @Test fun typingSendsChatActionForTheOwnerPeer() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = configuredStore(tmp.newFolder())
            val channel = TelegramChannel(store, api(server), log = {})
            val peer = store.peer("123")
            server.enqueue(response("""{"ok":true,"result":true}"""))

            channel.typing(peer)

            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/bottest-token/sendChatAction", request.path)
            val body = request.body.readUtf8()
            assertEquals(true, body.contains("\"chat_id\":\"123\""))
            assertEquals(true, body.contains("\"action\":\"typing\""))
        }
    }

    @Test fun typingSendsNothingForAStrangerPeer() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = configuredStore(tmp.newFolder())
            val channel = TelegramChannel(store, api(server), log = {})

            channel.typing(ChannelPeer(TelegramChannel.ID, "999", "other-thread"))

            assertEquals(0, server.requestCount)
        }
    }

    @Test fun typingSendsNothingWhenTheBotIsDisabled() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = configuredStore(tmp.newFolder())
            store.setEnabled(false)
            val channel = TelegramChannel(store, api(server), log = {})
            val peer = store.peer("123")

            channel.typing(peer)

            assertEquals(0, server.requestCount)
        }
    }

    @Test fun typingSendsNothingWithoutAToken() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = TelegramStore(tmp.newFolder(), cipher)
            val channel = TelegramChannel(store, api(server), log = {})

            channel.typing(ChannelPeer(TelegramChannel.ID, "123", "thread"))

            assertEquals(0, server.requestCount)
        }
    }

    @Test fun typingFailureNeverThrows() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = configuredStore(tmp.newFolder())
            val channel = TelegramChannel(store, api(server), log = {})
            val peer = store.peer("123")
            server.enqueue(response("""{"ok":false}""").setResponseCode(500))

            channel.typing(peer) // must not throw: typing is best-effort

            assertEquals(1, server.requestCount)
        }
    }

    @Test fun typingSendsNothingForTheOldPeerAfterReconfiguration() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = configuredStore(tmp.newFolder())
            val channel = TelegramChannel(store, api(server), log = {})
            val peer = store.peer("123")
            store.saveBot("new-token", "123")

            channel.typing(peer)

            assertEquals(0, server.requestCount)
        }
    }

    @Test fun typingIsANoOpOnOtherChannels() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            val peer = store.peer("owner")

            channel.typing(peer)

            assertEquals(0, server.requestCount)
        }
    }
}
