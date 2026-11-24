package com.wangzan101.mika.about

import com.wangzan101.mika.core.device.adb.ConnectCandidates
import com.wangzan101.mika.core.device.shell.BuiltinShellFailure
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.HelperRestart
import com.wangzan101.mika.core.device.shell.PairingChannelHealth
import com.wangzan101.mika.core.device.shell.PairingDiscovery
import com.wangzan101.mika.core.device.shell.RecoveryCapability
import com.wangzan101.mika.core.device.shell.RecoveryConsent
import com.wangzan101.mika.core.device.shell.SetupStep
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.ChatGptSignInFailureRecord
import com.wangzan101.mika.settings.SystemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    private val now = 1_790_000_000_000L
    private val skKey = "sk-" + "live1234567890abcdefABCDEF"
    private val googleKey = "AIza" + "SyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6"
    private val googleAuthKey = "AQ." + "Ab8RN6Lq_x-Zt3VwP0kJ9sYcH2mE4uFgD7aB"
    private val ghToken = "ghp_" + "abcdefghijklmnopqrstuvwxyz0123456789"

    private val status = SystemStatus(
        sdkInt = 34, builtinShell = BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed),
        accessibility = AccessibilityState.On, notificationsAllowed = true,
        developerOptions = true, wirelessDebugging = true, usbDebugging = true, restrictedSettings = false,
    )

    private fun snapshot(
        shell: BuiltinShellStatus = status.builtinShell,
        discovery: PairingDiscovery = PairingDiscovery.Found(37123),
        confirmed: Set<SetupStep> = setOf(SetupStep.WirelessDebugging),
        lastError: String? = "AdbConnectionException(CONNECT_FAILED): failed to connect to /192.168.1.23:37451 (port 37451) key=$skKey",
        providers: List<DiagnosticsSnapshot.Provider> = listOf(
            DiagnosticsSnapshot.Provider("OpenAiCompat", "https://api.deepseek.com/v1", 5),
            DiagnosticsSnapshot.Provider("Gemini", "https://generativelanguage.googleapis.com/v1beta?key=$googleKey", null),
        ),
        runs: List<DiagnosticsSnapshot.Run> = listOf(
            DiagnosticsSnapshot.Run(now - 12 * 60_000, "failed", "model_error", null, 4, "deepseek-chat"),
            DiagnosticsSnapshot.Run(now - 3 * 3_600_000, "succeeded", null, "done", 11, "deepseek-chat"),
            DiagnosticsSnapshot.Run(now - 2 * 86_400_000, "cancelled", null, null, 2, ""),
        ),
        wirelessSetting: Boolean? = true,
        installer: String? = "com.android.packageinstaller",
        connect: ConnectCandidates? = ConnectCandidates(property = 0, mdns = 2, cache = 1, connected = false),
    ) = DiagnosticsSnapshot(
        generatedAtMs = now,
        app = DiagnosticsSnapshot.App("0.1.7", "cfa4380", installer, "System (zh-Hans)"),
        device = DiagnosticsSnapshot.Device("Google", "Pixel 8", "14", 34, listOf("arm64-v8a", "armeabi-v7a"), "Ready"),
        setup = status.copy(builtinShell = shell).setupInputs(discovery, repair = false, confirmed = confirmed),
        phone = DiagnosticsSnapshot.Phone(
            accessibility = status.accessibility, wirelessDebuggingSetting = wirelessSetting,
            connect = connect,
            pairingChannels = mapOf("pairing_alert" to 4), attentionChannelImportance = 2, wifiConnected = true, lastError = lastError,
        ),
        recovery = DiagnosticsSnapshot.Recovery(
            RecoveryConsent(applied = setOf(RecoveryCapability.BatteryWhitelist), declined = setOf(RecoveryCapability.RestoreAfterReboot)),
            writeSecureSettings = false, ignoringBatteryOptimizations = true, lastRestore = "NotRecovered",
        ),
        models = DiagnosticsSnapshot.Models(
            providers = providers,
            main = DiagnosticsSnapshot.RoleModel("deepseek-chat", "https://api.deepseek.com/v1"),
            vision = null,
            light = DiagnosticsSnapshot.RoleModel("deepseek-chat", "https://api.deepseek.com/v1"),
            chatGptSignedIn = false,
        ),
        runs = runs,
        update = DiagnosticsSnapshot.Update("Done", "0.1.8", "0.1.8"),
    )

    @Test
    fun sectionsInStableOrderAndShort() {
        val t = Diagnostics.render(snapshot())
        assertTrue(t.startsWith(Diagnostics.HEADER + "\n"))
        val sections = t.lines().filter { it.startsWith("[") }
        assertEquals(listOf("[App]", "[Device]", "[Phone control]", "[Recovery]", "[Models]", "[Recent runs]", "[Update]"), sections)
        assertTrue(t.contains("version: 0.1.7 (build cfa4380)"))
        assertTrue(t.contains("installer: com.android.packageinstaller"))
        assertTrue(t.contains("model: Google Pixel 8"))
        assertTrue(t.contains("android: 14 (SDK 34)"))
        assertTrue(t.contains("abi: arm64-v8a, armeabi-v7a"))
        assertTrue(t.contains("arm64 sandbox: supported (Ready)"))
        assertTrue(t.contains("update available: 0.1.8"))
    }

    /** The speech-to-text section: the choice, the built-in recognition situation and the recognition services registered on the phone (component name, whether exported, required permissions); it comes after models. */
    @Test
    fun speechSectionListsRecognitionServices() {
        val speech = DiagnosticsSnapshot.Speech(
            choice = "auto", systemAvailable = true, onDevice = false, silent = true, remembered = null,
            services = listOf("com.example.voice/.Recognizer (not exported) needs android.permission.BIND_VOICE_INTERACTION"),
        )
        val t = Diagnostics.render(snapshot().copy(speech = speech))
        val sections = t.lines().filter { it.startsWith("[") }
        assertEquals(listOf("[App]", "[Device]", "[Phone control]", "[Recovery]", "[Models]", "[Speech]", "[Recent runs]", "[Update]"), sections)
        assertTrue(t, t.contains("speech to text: auto"))
        assertTrue(t, t.contains("built-in recognition: present, marked no response"))
        assertTrue(t, t.contains("recognition services: 1\n- com.example.voice/.Recognizer (not exported) needs android.permission.BIND_VOICE_INTERACTION"))
        // The default recognition service set by the system: when none is set, say so plainly (a registered service does not mean a default is set)
        assertTrue(t, t.contains("default recognition service: not set"))
        val set = Diagnostics.render(snapshot().copy(speech = speech.copy(
            defaultService = "com.example.voice/.Recognizer",
            services = listOf("com.example.voice/.Recognizer, its own mic permission: NOT granted"),
        )))
        assertTrue(set, set.contains("default recognition service: com.example.voice/.Recognizer"))
        assertTrue(set, set.contains("- com.example.voice/.Recognizer, its own mic permission: NOT granted"))
    }

    /** Those items on the setup page come from the same state machine: the failure is at the connect step, and the user has confirmed wireless debugging. */
    @Test
    fun phoneControlReadsTheSetupFlow() {
        val t = Diagnostics.render(snapshot())
        assertTrue(t, t.contains("helper: Failed (reason=ConnectFailed, fix=Retry)"))
        assertTrue(t, t.contains("setup: phase=Failed, current=6 Connect"))
        assertTrue(t, t.contains("steps: 1 Done, 2 Done, 3 Done, 4 Done, 5 Done, 6 Failed\n"))
        assertTrue(t, t.contains("confirmed by user: 4 WirelessDebugging"))
        assertTrue(t, t.contains("wireless debugging: on"))
        assertTrue(t, t.contains("accessibility: on (bound)"))
        assertTrue(t, t.contains("pairing service: found"))
        assertTrue(t, t.contains("connect service: cached 1, property 0, mDNS 2 candidates; scan: not run; manual: no; none connected"))
        assertTrue(t, t.contains("pairing channel: Ready (pairing_alert=high)"))
        assertTrue(t, t.contains("attention channel: low"))
        assertTrue(t, t.contains("Wi-Fi: connected"))
        assertTrue(t, t.contains("last error: AdbConnectionException(CONNECT_FAILED)"))
    }

    @Test
    fun helperStartupDiagnosticsSeparateLaunchTimeoutFromHandoffRejection() {
        val snap = snapshot()
        val timedOut = Diagnostics.render(snap.copy(phone = snap.phone.copy(helperLaunchStage = "handoff_timeout")))
        assertTrue(timedOut, timedOut.contains("helper launch stage: handoff_timeout"))
        assertTrue(timedOut, timedOut.contains("helper handoff rejection: none"))
        val rejected = Diagnostics.render(snap.copy(phone = snap.phone.copy(
            helperLaunchStage = "handoff_timeout", helperHandoffRejection = "StaleApk",
        )))
        assertTrue(rejected, rejected.contains("helper handoff rejection: StaleApk"))
        val shizukuRestart = Diagnostics.render(snap.copy(setup = snap.setup.copy(restart = HelperRestart.Shizuku)))
        assertTrue(shizukuRestart, shizukuRestart.contains("helper restart: Shizuku"))
    }

    /** This device has been scanned and the user has filled in a port: write only the counts and whether it was filled in, never the port number. */
    @Test
    fun connectServiceShowsScanAndManualWithoutPortNumbers() {
        val c = ConnectCandidates(0, 0, 0, connected = true, scanned = true, scanOpen = 2, scanAdb = 1, manual = true)
        val t = Diagnostics.render(snapshot(connect = c))
        assertTrue(t, t.contains("connect service: cached 0, property 0, mDNS 0 candidates; scan: 2 open, 1 adb; manual: yes; connected"))
        assertTrue(t, Diagnostics.render(snapshot(connect = null)).contains("connect service: not tried in this session"))
    }

    @Test
    fun notPairedAndUnreadableSwitch() {
        val t = Diagnostics.render(snapshot(shell = BuiltinShellStatus.NotPaired, discovery = PairingDiscovery.Searching, confirmed = emptySet(), lastError = null, wirelessSetting = null, installer = null))
        assertTrue(t, t.contains("helper: NotPaired"))
        assertTrue(t, t.contains("wireless debugging: unreadable"))
        assertTrue(t, t.contains("pairing service: searching"))
        assertTrue(t, t.contains("confirmed by user: none"))
        assertTrue(t, t.contains("last error: none"))
        assertTrue(t, t.contains("installer: unknown"))
    }

    /** The pairing notification channel is turned off: what is reported is the same state as on the setup page, and while stopped at the pairing step it also writes out the notification problem. */
    @Test
    fun pairingChannelOff() {
        val snap = snapshot(shell = BuiltinShellStatus.NotPaired, discovery = PairingDiscovery.Searching, confirmed = emptySet())
        val off = snap.copy(
            setup = snap.setup.copy(pairingChannel = PairingChannelHealth.Off, accessibility = false, accessibilitySkipped = true),
            phone = snap.phone.copy(pairingChannels = mapOf("pairing_alert" to 0)),
        )
        val t = Diagnostics.render(off)
        assertTrue(t, t.contains("pairing channel: Off (pairing_alert=off)"))
        assertTrue(t, t.contains("pairing notification issue: ChannelOff"))
        val none = Diagnostics.render(snap.copy(phone = snap.phone.copy(pairingChannels = emptyMap())))
        assertTrue(none, none.contains("pairing channel: Ready (not created)"))
    }

    /** No keys, tokens, emails, IPs or ports: even when they hide inside addresses or error messages. */
    @Test
    fun noSecretsAddressesOrPorts() {
        val t = Diagnostics.render(
            snapshot(
                lastError = "pair failed: Bearer abcdefgh12345678 for me@example.com at 10.0.0.7:5555 via [fe80::1c2b:3a4d] token=$ghToken $googleAuthKey $googleKey",
                providers = listOf(
                    DiagnosticsSnapshot.Provider("OpenAiCompat", "https://alice%40example.com:$skKey@api.example.org/v1?api_key=$skKey", 3),
                    DiagnosticsSnapshot.Provider("OpenAiCompat", "http://192.168.1.10:11434/v1", 2),
                    DiagnosticsSnapshot.Provider("Gemini", "https://generativelanguage.googleapis.com/v1beta?key=$googleAuthKey", null),
                ),
            ),
        )
        for (secret in listOf(skKey, googleKey, googleAuthKey, ghToken, "abcdefgh12345678", "me@example.com", "alice", "10.0.0.7", "5555", "192.168.1.10", "11434", "fe80", "37123", "api_key")) {
            assertFalse("$secret leaked:\n$t", t.contains(secret))
        }
        assertTrue(t, t.contains("- OpenAiCompat api.example.org (3 models listed)"))
        assertTrue(t, t.contains("- OpenAiCompat <ip> (2 models listed)"))
        assertTrue(t, t.contains("- Gemini generativelanguage.googleapis.com"))
    }

    @Test
    fun defaultErrorNeverShowsIpOrPort() {
        val t = Diagnostics.render(snapshot())
        assertFalse(t, t.contains("192.168.1.23"))
        assertFalse(t, t.contains("37451"))
        assertFalse(t, t.contains(skKey))
        assertTrue(t, t.contains("key=***"))
    }

    /** Recent runs: relative time, outcome, failure category, step count, model; no goal and no answer. */
    @Test
    fun recentRuns() {
        val t = Diagnostics.render(snapshot())
        assertTrue(t, t.contains("- 12m ago: failed (model_error), 4 steps, deepseek-chat"))
        assertTrue(t, t.contains("- 3h ago: succeeded, judged done, 11 steps, deepseek-chat"))
        assertTrue(t, t.contains("- 2d ago: cancelled, 2 steps\n"))
        assertTrue(Diagnostics.render(snapshot(runs = emptyList())).contains("[Recent runs]\nnone\n"))
    }

    @Test
    fun modelsAndRecovery() {
        val t = Diagnostics.render(snapshot())
        assertTrue(t, t.contains("providers: 2"))
        assertTrue(t, t.contains("- OpenAiCompat api.deepseek.com (5 models listed)"))
        assertTrue(t, t.contains("main: deepseek-chat @ api.deepseek.com"))
        assertTrue(t, t.contains("vision: off"))
        assertTrue(t, t.contains("ChatGPT sign-in: no"))
        assertTrue(t, t.contains("battery whitelist: on, applied"))
        assertTrue(t, t.contains("restore after reboot: off by user, not applied"))
        assertTrue(t, t.contains("write secure settings: not granted"))
        assertTrue(t, t.contains("battery optimization: unrestricted"))
        assertTrue(t, t.contains("last reboot restore: NotRecovered"))
    }

    /** Records of the built-in helper launching Claire and of accessibility service problems at startup: when present, write when and how many times; otherwise write never / ok. */
    @Test
    fun helperRestoresAndAccessibilityLoss() {
        val base = snapshot()
        val none = Diagnostics.render(base)
        assertTrue(none, none.contains("restored by helper: never"))
        assertTrue(none, none.contains("accessibility on start: ok"))
        val t = Diagnostics.render(base.copy(recovery = base.recovery.copy(
            helperRestores = com.wangzan101.mika.core.device.shell.HelperRestoreReport(now - 3 * 60_000, 2, now - 60_000),
            lastA11yLoss = com.wangzan101.mika.core.device.shell.A11yLossRecord(com.wangzan101.mika.core.device.shell.A11yLoss.Removed, now - 2 * 3_600_000),
        )))
        assertTrue(t, t.contains("restored by helper: 3m ago, 2 recently, backed off 1m ago"))
        assertTrue(t, t.contains("accessibility on start: removed 2h ago"))
    }

    /** The most recent ChatGPT sign-in failure: write the category, error code and description; token-like strings in the description are masked. */
    @Test
    fun chatGptSignInFailureShowsCodeWithoutTokens() {
        val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEyMzQ1Njc4OTAifQ.c2lnbmF0dXJlLWJ5dGVzLWhlcmUtMTIzNDU2Nzg5MA"
        val base = snapshot()
        val failure = ChatGptSignInFailureRecord(now - 5 * 60_000, "CodeExpired", "invalid_grant", "CodeExpired: invalid_grant — bad code token=$jwt")
        val t = Diagnostics.render(base.copy(models = base.models.copy(chatGptLastFailure = failure)))
        assertTrue(t, t.contains("ChatGPT last sign-in failure: 5m ago: CodeExpired (code invalid_grant) — CodeExpired: invalid_grant — bad code"))
        assertFalse(t, t.contains("eyJ"))
        assertFalse("没有失败就不写这一行", Diagnostics.render(base).contains("last sign-in failure"))
    }

    /** Sanitizing does not over-reach: the digits in times, version numbers and model names are written as they are. */
    @Test
    fun sanitizeKeepsTimesAndVersions() {
        assertEquals("at 12:30:05 v0.1.7 SDK 34 Pixel 8", Diagnostics.sanitize("at 12:30:05 v0.1.7 SDK 34 Pixel 8"))
        assertEquals("<ip> and <ip>", Diagnostics.sanitize("1.2.3.4 and 127.0.0.1:37000"))
        assertEquals("password: ***", Diagnostics.sanitize("password: hunter2"))
    }

    @Test
    fun relativeTime() {
        assertEquals("just now", Diagnostics.ago(10_000))
        assertEquals("59m ago", Diagnostics.ago(59 * 60_000L))
        assertEquals("47h ago", Diagnostics.ago(47 * 3_600_000L))
        assertEquals("3d ago", Diagnostics.ago(3 * 86_400_000L))
        assertEquals("just now", Diagnostics.ago(-5_000))
    }
}
