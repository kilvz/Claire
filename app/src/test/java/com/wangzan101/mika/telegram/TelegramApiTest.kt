package com.wangzan101.mika.telegram

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class TelegramApiTest {
    private fun api(server: MockWebServer) =
        TelegramApi(OkHttpClient(), server.url("/").toString().removeSuffix("/"))

    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun sendChatActionPostsTypingToTheBotPath() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(response("""{"ok":true,"result":true}"""))

            api(server).sendChatAction("test-token", "8756247324")

            val request = server.takeRequest()
            assertEquals("/bottest-token/sendChatAction", request.path)
            assertEquals("POST", request.method)
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"chat_id\":\"8756247324\""))
            assertTrue(body.contains("\"action\":\"typing\""))
        }
    }

    @Test fun sendChatActionRejectsWhenTelegramSaysNotOk() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(response("""{"ok":false,"description":"Forbidden: bot was blocked by the user"}"""))

            try {
                api(server).sendChatAction("test-token", "8756247324")
                fail("expected TelegramApiException")
            } catch (e: TelegramApiException) {
                assertEquals("Forbidden: bot was blocked by the user", e.message)
            }
        }
    }

    @Test fun sendChatActionSurfacesTheHttpCode() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(response("""{"ok":false,"description":"Unauthorized"}""").setResponseCode(401))

            try {
                api(server).sendChatAction("bad-token", "8756247324")
                fail("expected TelegramApiException")
            } catch (e: TelegramApiException) {
                assertEquals(401, e.httpCode)
            }
        }
    }

    @Test fun sendMessageStillPostsToTheBotPath() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(response("""{"ok":true,"result":{"message_id":7}}"""))

            assertEquals(7, api(server).sendMessage("test-token", "8756247324", "hi"))

            val request = server.takeRequest()
            assertEquals("/bottest-token/sendMessage", request.path)
            assertTrue(request.body.readUtf8().contains("\"chat_id\":\"8756247324\""))
        }
    }

    @Test fun sendPhotoUploadsTheFileToTheBotPath() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(response("""{"ok":true,"result":{"message_id":9}}"""))
            val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

            assertEquals(9, api(server).sendPhoto("test-token", "8756247324", "selfie.jpg", bytes))

            val request = server.takeRequest()
            assertEquals("/bottest-token/sendPhoto", request.path)
            assertEquals("POST", request.method)
            val body = request.body.readUtf8()
            assertTrue(body.contains("name=\"chat_id\""))
            assertTrue(body.contains("8756247324"))
            assertTrue(body.contains("filename=\"selfie.jpg\""))
            assertTrue(body.contains("Content-Type: image/jpeg"))
        }
    }

    @Test fun sendDocumentKeepsTheOriginalFileName() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(response("""{"ok":true,"result":{"message_id":10}}"""))
            val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

            assertEquals(10, api(server).sendDocument("test-token", "8756247324", "scan.png", bytes))

            val request = server.takeRequest()
            assertEquals("/bottest-token/sendDocument", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("filename=\"scan.png\""))
            assertTrue(body.contains("Content-Type: image/png"))
        }
    }

    @Test fun sendPhotoSurfacesATelegramRejection() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(response("""{"ok":false,"description":"PHOTO_INVALID_DIMENSIONS"}""").setResponseCode(400))

            try {
                api(server).sendPhoto("test-token", "8756247324", "selfie.jpg", byteArrayOf(1, 2, 3))
                fail("expected TelegramApiException")
            } catch (e: TelegramApiException) {
                assertEquals(400, e.httpCode)
            }
        }
    }

    @Test fun getMeReturnsTheBotUsername() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(response("""{"ok":true,"result":{"id":123,"is_bot":true,"first_name":"Claire","username":"ClaireAppBot"}}"""))

            assertEquals("ClaireAppBot", api(server).getMe("test-token"))

            val request = server.takeRequest()
            assertEquals("/bottest-token/getMe", request.path)
            assertEquals("GET", request.method)
        }
    }

    @Test fun getMeRejectsWhenTelegramSaysNotOk() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(response("""{"ok":false,"description":"Unauthorized"}""").setResponseCode(401))

            try {
                api(server).getMe("bad-token")
                fail("expected TelegramApiException")
            } catch (e: TelegramApiException) {
                assertEquals(401, e.httpCode)
            }
        }
    }

    @Test fun getMeReturnsNullWhenUsernameMissing() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(response("""{"ok":true,"result":{"id":123,"is_bot":true,"first_name":"Claire"}}"""))

            assertNull(api(server).getMe("test-token"))
        }
    }
}
