package com.wangzan101.mika.wechat.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.cap.wechat.WechatAccount
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.wechat.WechatRuntime
import com.wangzan101.mika.wechat.WechatStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import com.wangzan101.mika.ui.clickInDialog

class WechatSettingsTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "wechat-settings-ui-${UUID.randomUUID()}")
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain
        override fun decrypt(data: ByteArray): ByteArray = data
    }
    private val store = WechatStore(dir, cipher)
    private val account = WechatAccount("test-token", "https://example.test", "bot", "owner")
    private var runtime: WechatRuntime? = null

    @After fun cleanup() {
        runtime?.stop()
        dir.deleteRecursively()
    }

    private fun page() {
        val value = WechatRuntime(context, store).also { runtime = it }
        compose.setContent { MikaTheme(dark = false, reduceMotion = true) {
            WechatSettingsScreen(value, {})
        } }
    }

    @Test fun unlockPermissionIsExplicitAndRebindingUpdatesTheOpenPage() {
        store.saveAccount(account)
        page()
        compose.onNodeWithTag("wechat-allow-unlock").performScrollTo().assertIsOff().performClick()
        compose.waitUntil(5_000) { store.unlockAllowed() }
        compose.onNodeWithTag("wechat-allow-unlock").assertIsOn()
        assertFalse(store.enabled())
        assertTrue(WechatStore(dir, cipher).unlockAllowed())

        // Rebinding does not necessarily change the connection state; an already open page must still observe that the grant was cleared.
        compose.runOnIdle { store.saveAccount(account.copy(token = "new-test-token")) }
        compose.onNodeWithTag("wechat-allow-unlock").assertIsOff()
    }

    @Test fun unbindingRemovesTheUnlockControlAndRevokesPermission() {
        store.saveAccount(account)
        store.setUnlockAllowed(true)
        page()
        compose.onNodeWithTag("wechat-allow-unlock").performScrollTo().assertIsOn()
        compose.onNodeWithText("解除绑定").performScrollTo().performClick()
        compose.clickInDialog("解除绑定")
        compose.waitUntil(5_000) { store.account() == null }
        compose.onNodeWithTag("wechat-allow-unlock").assertDoesNotExist()
        assertFalse(store.unlockAllowed())
        assertFalse(WechatStore(dir, cipher).unlockAllowed())
    }

    @Test fun failedSaveShowsAnErrorWithoutGrantingUnlock() {
        store.saveAccount(account)
        page()
        // Simulate a failure to write the authorization file and check that the page does not show a grant that was never saved.
        assertTrue(File(dir, "unlock.json.tmp").mkdir())
        compose.onNodeWithTag("wechat-allow-unlock").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("设置未能保存，请重试。").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("设置未能保存，请重试。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("wechat-allow-unlock").performScrollTo().assertIsOff().assertIsEnabled()
        assertFalse(store.unlockAllowed())
        assertFalse(WechatStore(dir, cipher).unlockAllowed())
    }
}
