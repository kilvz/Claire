package com.wangzan101.mika.settings.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.device.shell.BuiltinShellFailure
import com.wangzan101.mika.core.device.shell.BuiltinShellFix
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.HelperRestart
import com.wangzan101.mika.core.device.shell.RootGrant
import com.wangzan101.mika.core.device.shell.SetupStep
import com.wangzan101.mika.core.device.shell.SetupSuspect
import com.wangzan101.mika.settings.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSetupCopyTest {
    @get:Rule val texts = ChineseTexts()

    /** These technical terms must not appear in user-facing text (except option names that already exist in system settings, see below). */
    private val jargon = listOf("shell", "端口", "mDNS", "TLS", "Binder", "uid", "进程")

    private fun allText(): List<String> = buildList {
        add(PhoneSetupCopy.INTRO)
        SetupStep.entries.forEach { val c = PhoneSetupCopy.step(it); add(c.title); add(c.body); c.action?.let(::add); add(c.done); add(c.brief) }
        BuiltinShellFailure.entries.forEach { val f = PhoneSetupCopy.failure(it); add(f.title); add(f.body) }
        BuiltinShellFix.entries.forEach { add(PhoneSetupCopy.fixLabel(it)) }
        statuses.forEach { add(PhoneSetupCopy.statusLine(it)); add(PhoneSetupCopy.statusLine(it, usbDebugging = false)); add(PhoneSetupCopy.statusValue(it).first) }
        statuses.forEach { add(PhoneSetupCopy.statusLine(it, rootStart = true)); add(PhoneSetupCopy.statusLine(it, shizukuStart = true)) }
        RootGrant.entries.forEach {
            add(PhoneSetupCopy.rootStartLine(it)); add(PhoneSetupCopy.rootStatusLine(it))
            add(PhoneSetupCopy.rootCommandsLine(true, it)); add(PhoneSetupCopy.rootCommandsLine(false, it))
        }
        addAll(listOf(
            PhoneSetupCopy.ROOT_START, PhoneSetupCopy.ROOT_COMMANDS, PhoneSetupCopy.ROOT_COMMANDS_TITLE, PhoneSetupCopy.ROOT_COMMANDS_WARNING,
            PhoneSetupCopy.ROOT_NOTE, PhoneSetupCopy.ROOT_COMMANDS_NOTE, PhoneSetupCopy.ROOT_AVAILABLE, PhoneSetupCopy.ROOT_AUTO,
            PhoneSetupCopy.ROOT_STATUS_TITLE, PhoneSetupCopy.ROOT_COMMANDS_GROUP,
            PhoneSetupCopy.rootEntryLine(false), PhoneSetupCopy.rootEntryLine(true),
        ))
        addAll(
            listOf(
                PhoneSetupCopy.SUCCESS_LINE, PhoneSetupCopy.UNPAIR_BODY, PhoneSetupCopy.SKIP_NOTE, PhoneSetupCopy.DEGRADED_LINE, PhoneSetupCopy.PORT_FOUND,
                PhoneSetupCopy.NEED_NOTIFICATIONS, PhoneSetupCopy.MANUAL_HINT, PhoneSetupCopy.NOTIFY_ENTER_TEXT,
                PhoneSetupCopy.NOTIFY_ENTER_TITLE, PhoneSetupCopy.NOTIFY_ACTION, PhoneSetupCopy.NOTIFY_INPUT_LABEL,
                PhoneSetupCopy.AUTO_READ_HINT, PhoneSetupCopy.ACCESSIBILITY_SKIP, PhoneSetupCopy.NOTIFY_WINDOW_FOUND,
                PhoneSetupCopy.NOTIFY_CODE_INVALID, PhoneSetupCopy.PAIRING_CHANNEL_OFF, PhoneSetupCopy.OPEN_PAIRING_CHANNEL,
                PhoneSetupCopy.PAIRING_LOWERED, PhoneSetupCopy.NOTIFY_DONE_TITLE, PhoneSetupCopy.NOTIFY_DONE_TEXT,
                PhoneSetupCopy.ACCESSIBILITY_SKIPPED, PhoneSetupCopy.APP_INFO, PhoneSetupCopy.TURNED_ON_NEXT,
                PhoneSetupCopy.REVISIT_BACK, PhoneSetupCopy.REVISIT_NOTE, PhoneSetupCopy.CONNECT_PORT_SUBMIT,
            ),
        )
        HelperRestart.entries.forEach { add(PhoneSetupCopy.successNote(it)); add(PhoneSetupCopy.usageNote(it)) }
        suspects.forEach { add(PhoneSetupCopy.suspect(it)); add(PhoneSetupCopy.goBackTo(it.step)) }
        addAll(PhoneSetupCopy.NOTIFY_GUIDE)
        BuiltinShellFailure.entries.forEach { add(PhoneSetupCopy.notifyRetry(it)) }
    }

    private val suspects = listOf(
        SetupSuspect(SetupStep.DeveloperOptions, unreadable = false),
        SetupSuspect(SetupStep.WirelessDebugging, unreadable = false),
        SetupSuspect(SetupStep.WirelessDebugging, unreadable = true),
    )

    private val statuses = listOf(
        BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting, BuiltinShellStatus.Running,
    ) + BuiltinShellFailure.entries.map { BuiltinShellStatus.Failed(it) }

    @Test
    fun connectPortInputAcceptsOnlyValidPorts() {
        assertEquals(41235, ConnectPortInput.parse("41235"))
        assertEquals(41235, ConnectPortInput.parse(" 41 235 "))
        assertEquals(41235, ConnectPortInput.parse("４１２３５"))
        // Pasting a whole "IP address and port" works too
        assertEquals(41235, ConnectPortInput.parse("192.0.2.10:41235"))
        assertEquals(41235, ConnectPortInput.parse("192.0.2.10：41235"))
        assertEquals(1, ConnectPortInput.parse("1"))
        assertEquals(65535, ConnectPortInput.parse("65535"))
        for (bad in listOf("", " ", "0", "65536", "70000", "abc", "41a35", "-1", "192.0.2.10:")) assertNull(bad, ConnectPortInput.parse(bad))
    }

    @Test
    fun reconnectOnlyWhenRetryingIsTheFix() {
        val reconnectable = statuses.filter { PhoneSetupCopy.canReconnect(it) }
        assertEquals(
            // root denied or no answer: tapping retry asks su again; a Shizuku service that is not running or a failed bind also retries directly; after fixing a non-local address a retry works too.
            listOf(
                BuiltinShellFailure.ConnectFailed, BuiltinShellFailure.NotLocal, BuiltinShellFailure.HelperStartFailed,
                BuiltinShellFailure.RootDenied, BuiltinShellFailure.RootNoAnswer,
                BuiltinShellFailure.ShizukuNotRunning, BuiltinShellFailure.ShizukuBindFailed, BuiltinShellFailure.Unknown,
            ),
            reconnectable.map { (it as BuiltinShellStatus.Failed).reason },
        )
        reconnectable.forEach { s ->
            assertEquals(Fix.RetryPhoneHelper, StatusCopy.directFix(Problem.PhoneHelper(s)))
        }
        // Not paired goes through the guide; wireless debugging off must be turned on first; no Wi-Fi recovers by itself; a stale pairing needs re-pairing; Shizuku not installed or not authorized is handled on the phone operations page
        listOf(
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting,
            BuiltinShellStatus.Running, BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi),
            BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked),
            BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuNotInstalled),
            BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuPermissionNeeded),
        ).forEach { assertFalse(PhoneSetupCopy.canReconnect(it)) }
    }

    @Test
    fun bannerAndFixForPhoneHelperProblem() {
        statuses.filter { it != BuiltinShellStatus.Running }.forEach { s ->
            val p = Problem.PhoneHelper(s)
            val fix = when {
                PhoneSetupCopy.canReconnect(s) -> Fix.RetryPhoneHelper
                // This Wi-Fi has not allowed wireless debugging yet: go turn it on there yourself
                s == BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed) -> Fix.OpenWirelessDebugging
                s == BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuNotInstalled) ||
                    s == BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuPermissionNeeded) -> Fix.OpenPhoneOps
                else -> Fix.OpenPhoneSetup
            }
            assertEquals(fix, StatusCopy.directFix(p))
            assertEquals(Fix.OpenPhoneSetup, StatusCopy.explain(p, "14").fix)
        }
    }

    @Test
    fun copyIsPlainChinese() {
        // When the connection fails the user has to copy the port from the "Wireless debugging" page: this is the only sentence that says "port" outright
        val portAllowed = PhoneSetupCopy.failure(BuiltinShellFailure.ConnectFailed).body
        allText().forEach { t ->
            assertTrue("empty copy", t.isNotBlank())
            jargon.forEach { j -> if (!(j == "端口" && t == portAllowed)) assertFalse("「$j」 in: $t", t.contains(j, ignoreCase = true)) }
            // Only the system option name itself contains adb
            if (t.contains("adb")) assertTrue(t, t.contains("「停用 adb 授权超时功能」"))
        }
    }

    @Test
    fun failuresAreDistinctAndActionable() {
        val titles = BuiltinShellFailure.entries.map { PhoneSetupCopy.failure(it).title }
        assertEquals(titles.size, titles.toSet().size)
        assertEquals("重新配对", PhoneSetupCopy.fixLabel(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode).fix))
        assertEquals("重试", PhoneSetupCopy.fixLabel(BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed).fix))
    }

    /** The helper started by a USB command has stopped: the settings page and the banner both say plainly that it stopped and to run the command again; they do not point to wireless debugging (some phones do not have it at all). */
    @Test
    fun usbHelperStoppedSaysHowToStartItAgain() {
        val stopped = BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped)
        assertEquals("手机助手已停止", PhoneSetupCopy.failure(BuiltinShellFailure.UsbHelperStopped).title)
        val line = PhoneSetupCopy.statusLine(stopped)
        assertTrue(line, line.contains("USB 启动命令") && line.contains("再运行一次"))
        assertFalse(line, line.contains("无线调试"))
        assertEquals(BuiltinShellFix.RunUsbCommand, stopped.fix)
        assertEquals("复制", PhoneSetupCopy.fixLabel(stopped.fix))
        // It was started with the command all along: the card says "run it again", not "wireless debugging will not open"
        val (againTitle, againBody) = PhoneSetupCopy.usbCard(HelperRestart.UsbCommand)
        assertEquals("再运行一次启动命令", againTitle)
        assertTrue(againBody.contains("重启后"))
        assertEquals(PhoneSetupCopy.USB_TITLE to PhoneSetupCopy.USB_BODY, PhoneSetupCopy.usbCard(HelperRestart.None))
        val banner = StatusCopy.banner(Problem.PhoneHelper(stopped))
        assertEquals("手机助手已停止", banner.title)
        assertEquals(line, banner.detail)
        assertEquals("这台手机打不开无线调试？改用电脑启动", PhoneSetupCopy.USB_TITLE)
        assertEquals("找不到无线调试？", PhoneSetupCopy.USB_ASK)
        assertTrue(PhoneSetupCopy.USB_BODY.contains("数据线"))
        assertTrue(PhoneSetupCopy.USB_BODY.contains("重启后"))
    }

    /** When "start with root" is unavailable, the settings page and the banner both state the reason and what to do; denial and no answer offer a retry. When root start is in progress it says so. */
    @Test
    fun rootStartStatusSaysWhyAndWhatToDo() {
        val denied = BuiltinShellStatus.Failed(BuiltinShellFailure.RootDenied)
        assertEquals("root 授权被拒绝了", PhoneSetupCopy.failure(BuiltinShellFailure.RootDenied).title)
        assertTrue(PhoneSetupCopy.statusLine(denied).contains("重试"))
        assertTrue(PhoneSetupCopy.canReconnect(denied))
        val banner = StatusCopy.banner(Problem.PhoneHelper(denied))
        assertEquals("root 授权被拒绝了", banner.title)
        assertEquals(PhoneSetupCopy.RECONNECT, banner.link)
        val gone = BuiltinShellStatus.Failed(BuiltinShellFailure.RootUnavailable)
        assertTrue(PhoneSetupCopy.statusLine(gone).contains("找不到 su"))
        assertFalse(PhoneSetupCopy.canReconnect(gone))
        assertEquals("手机助手没在运行,正在用 root 启动…", PhoneSetupCopy.statusLine(BuiltinShellStatus.Connecting, rootStart = true))
        assertEquals(PhoneSetupCopy.statusLine(BuiltinShellStatus.Connecting), PhoneSetupCopy.statusLine(BuiltinShellStatus.Connecting, rootStart = false))
        // Starting through root does not rely on Wi-Fi: with USB debugging off there is no warning that losing Wi-Fi will stop it
        assertEquals(PhoneSetupCopy.statusLine(BuiltinShellStatus.Running), PhoneSetupCopy.statusLine(BuiltinShellStatus.Running, usbDebugging = false, rootStart = true))
    }

    /** Auto-start has no toggle: the first grant is explicit and afterwards it is automatic; denial and no answer can both be retried by hand. Root commands are still off by default. */
    @Test
    fun rootLinesFollowAuthorization() {
        assertEquals(PhoneSetupCopy.ROOT_PENDING, PhoneSetupCopy.rootStartLine(RootGrant.Pending))
        assertTrue(PhoneSetupCopy.rootStartLine(RootGrant.Unknown).contains("点这里授权"))
        assertTrue(PhoneSetupCopy.rootStartLine(RootGrant.Granted).contains("已授权"))
        assertTrue(PhoneSetupCopy.rootStartLine(RootGrant.Denied).contains("再问一次"))
        assertTrue(PhoneSetupCopy.rootStartLine(RootGrant.NoAnswer).contains("再问一次"))
        assertTrue(PhoneSetupCopy.rootStatusLine(RootGrant.Unknown).contains("检测到 su"))
        assertTrue(PhoneSetupCopy.rootStatusLine(RootGrant.Granted).contains("已授权"))
        assertFalse(PhoneSetupCopy.rootStatusLine(RootGrant.Denied).contains("再问一次"))
        assertTrue(PhoneSetupCopy.rootCommandsLine(false, RootGrant.Unknown).contains("默认关闭"))
        assertTrue(PhoneSetupCopy.rootCommandsLine(true, RootGrant.Granted).contains("已开启"))
        assertTrue(PhoneSetupCopy.rootCommandsLine(false, RootGrant.Denied).contains("保持关闭"))
        assertTrue(PhoneSetupCopy.rootEntryLine(false).contains("已关闭"))
        assertTrue(PhoneSetupCopy.rootEntryLine(true).contains("已开启"))
        assertTrue(PhoneSetupCopy.ROOT_COMMANDS_WARNING.contains("其他 App 的数据"))
        assertEquals("允许 Claire 执行 root 命令", PhoneSetupCopy.ROOT_COMMANDS)
        assertEquals("用 root 启动手机助手", PhoneSetupCopy.ROOT_START)
    }

    /** USB start does not promise it keeps working after unplugging: the success screen, the settings page and the rerun card all give the generic way to recover. */
    @Test
    fun usbCopyDoesNotPromiseUnplugSurvival() {
        listOf(
            PhoneSetupCopy.successNote(HelperRestart.UsbCommand),
            PhoneSetupCopy.usageNote(HelperRestart.UsbCommand),
            PhoneSetupCopy.usbCard(HelperRestart.UsbCommand).second,
        ).forEach { text ->
            assertTrue(text, text.contains("多数手机"))
            assertTrue(text, text.contains("拔掉数据线"))
            assertTrue(text, text.contains("再运行一次"))
            assertFalse(text, text.contains("拔掉数据线也会继续运行"))
        }
        // The hints on the wireless debugging path are not affected by the USB recovery note
        assertFalse(PhoneSetupCopy.successNote(HelperRestart.Wireless).contains("拔掉数据线"))
        assertFalse(PhoneSetupCopy.usageNote(HelperRestart.Wireless).contains("拔掉数据线"))
    }

    /** Shizuku failure copy, the retry / go-to-settings branches, the starting and running status lines, and the after-reboot hint. */
    @Test
    fun shizukuFailuresHaveClearCopyAndActions() {
        listOf(
            BuiltinShellFailure.ShizukuNotInstalled,
            BuiltinShellFailure.ShizukuPermissionNeeded,
        ).forEach { reason ->
            val st = BuiltinShellStatus.Failed(reason)
            assertFalse(reason.name, PhoneSetupCopy.canReconnect(st))
            assertEquals(Fix.OpenPhoneOps, StatusCopy.directFix(Problem.PhoneHelper(st)))
            val banner = StatusCopy.banner(Problem.PhoneHelper(st))
            assertEquals(PhoneSetupCopy.failure(reason).title, banner.title)
            assertEquals(PhoneSetupCopy.failure(reason).body, banner.detail)
            assertEquals("去处理", banner.link)
        }

        listOf(
            BuiltinShellFailure.ShizukuNotRunning,
            BuiltinShellFailure.ShizukuBindFailed,
        ).forEach { reason ->
            val st = BuiltinShellStatus.Failed(reason)
            assertTrue(reason.name, PhoneSetupCopy.canReconnect(st))
            assertEquals(Fix.RetryPhoneHelper, StatusCopy.directFix(Problem.PhoneHelper(st)))
            val banner = StatusCopy.banner(Problem.PhoneHelper(st))
            assertEquals(PhoneSetupCopy.failure(reason).title, banner.title)
            assertEquals(PhoneSetupCopy.failure(reason).body, banner.detail)
            assertEquals(PhoneSetupCopy.RECONNECT, banner.link)
        }

        val notRunning = PhoneSetupCopy.failure(BuiltinShellFailure.ShizukuNotRunning)
        assertTrue(notRunning.title, notRunning.title.contains("未运行"))
        assertTrue(notRunning.body, notRunning.body.contains("手机重启后"))

        assertTrue(PhoneSetupCopy.statusLine(BuiltinShellStatus.Connecting, shizukuStart = true).contains("Shizuku"))
        assertEquals(
            PhoneSetupCopy.statusLine(BuiltinShellStatus.Running, shizukuStart = true),
            PhoneSetupCopy.statusLine(BuiltinShellStatus.Running, usbDebugging = false, shizukuStart = true),
        )
        assertTrue(PhoneSetupCopy.usageNote(HelperRestart.Shizuku).contains("不会自行启动"))
        assertTrue(PhoneSetupCopy.successNote(HelperRestart.Shizuku).contains("手机重启后"))
    }
}
