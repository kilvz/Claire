package com.wangzan101.mika.about

import com.wangzan101.mika.core.device.adb.ConnectCandidates
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.PairingDiscovery
import com.wangzan101.mika.core.device.shell.PhoneSetupFlow
import com.wangzan101.mika.core.device.shell.A11yLossRecord
import com.wangzan101.mika.core.device.shell.HelperRestart
import com.wangzan101.mika.core.device.shell.HelperRestoreReport
import com.wangzan101.mika.core.device.shell.RecoveryCapability
import com.wangzan101.mika.core.device.shell.RecoveryConsent
import com.wangzan101.mika.core.device.shell.SetupInputs
import com.wangzan101.mika.core.device.shell.SetupStep
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.ChatGptSignInFailureRecord
import java.net.URI

/**
 * The state read at the moment "copy diagnostics" is pressed. Only what is needed for troubleshooting: version, device model, the
 * status of each phone-control step, the model providers' API type and address,
 * and the outcome of the last few runs; no keys, sign-in, chat content, task goals or screen content, and no IP, port or Wi-Fi name.
 * The setup steps come straight from the setup screen's own input [setup] (the same state machine computes the steps), not recomputed here.
 */
data class DiagnosticsSnapshot(
    val generatedAtMs: Long,
    val app: App,
    val device: Device,
    val setup: SetupInputs,
    val phone: Phone,
    val recovery: Recovery,
    val models: Models,
    val runs: List<Run>,
    val update: Update,
    /** Speech-to-text: what was chosen and the state of the phone's built-in recognition; older call sites that omit it leave out this section. */
    val speech: Speech? = null,
) {
    data class App(val versionName: String, val build: String, val installer: String?, val language: String)

    data class Device(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val abis: List<String>,
        /** Sandbox (runtime environment) status: the reason is recorded when unsupported. */
        val sandbox: String,
    )

    data class Phone(
        val accessibility: AccessibilityState,
        /** The "wireless debugging" item in system settings; null when it cannot be read. */
        val wirelessDebuggingSetting: Boolean?,
        val connect: ConnectCandidates?,
        /** Pairing-code notification channels that already exist in the system (id to importance); empty when none were created. Whether pairing is usable is determined by [SetupInputs.pairingChannel]. */
        val pairingChannels: Map<String, Int>,
        val attentionChannelImportance: Int?,
        val wifiConnected: Boolean?,
        /** Verbatim text of the last pairing or connection failure (redacted when rendered). */
        val lastError: String?,
        /**
         * Apps under another user (cloned apps, work profile): the built-in helper's verdict on each other user (id, flags, type,
         * profile group, no names) plus how many of them can be opened;
         * null when the helper is not connected or the query fails.
         */
        val appCopies: String? = null,
        /** State of the pairing key within this process (unused, read back, newly created, unreadable and why); null when unreadable. */
        val adbKey: String? = null,
        /** How many of the apps left on Claire's screen at the end of the most recent task in this process were kept and how many were closed (counts only, no package names); null when there has not been one yet. */
        val appsAfterTask: String? = null,
        /** The fixed stage the most recent helper start reached; null when it has not been started in this process. */
        val helperLaunchStage: String? = null,
        /** Binder handoff rejection of this start attempt; NotShell carries only a fixed caller category, no concrete UID. */
        val helperHandoffRejection: String? = null,
    )

    data class Recovery(
        val consent: RecoveryConsent,
        val writeSecureSettings: Boolean,
        /** Whether Claire is unrestricted in the system's battery optimization; null when unreadable. */
        val ignoringBatteryOptimizations: Boolean?,
        val lastRestore: String?,
        /** Record of the built-in helper bringing Claire back after it was killed (reported during helper handoff); null when none. */
        val helperRestores: HelperRestoreReport? = null,
        /** On Claire's previous start the accessibility service was found removed or unbound; null when none. */
        val lastA11yLoss: A11yLossRecord? = null,
    )

    data class Models(
        val providers: List<Provider>,
        val main: RoleModel?,
        val vision: RoleModel?,
        val light: RoleModel?,
        val chatGptSignedIn: Boolean,
        /** The most recent ChatGPT sign-in failure since this run (message already redacted); null when none. */
        val chatGptLastFailure: ChatGptSignInFailureRecord? = null,
        /** The most recent account (xAI) sign-in failure; null when none. */
        val accountLastFailure: ChatGptSignInFailureRecord? = null,
    )

    /** One provider: API type, address (host name only), how many models have been listed (null when never listed). */
    data class Provider(val kind: String, val baseUrl: String, val listedModels: Int?)

    data class RoleModel(val model: String, val baseUrl: String)

    /** One run: start time, outcome, failure category (not the exact wording), number of steps taken, model used. */
    data class Run(val startedAtMs: Long, val outcome: String, val failKind: String?, val judged: String?, val steps: Int, val model: String?)

    data class Update(val status: String, val latest: String?, val available: String?)

    /**
     * [choice] is the user's choice (auto, system, off, or a provider model "model @ address"); [systemAvailable] and [onDevice] are
     * what the system reports as available,
     * [silent] whether it was recorded as not responding; [remembered] the recognition service that was named and verified;
     * [services] the recognition services registered on the phone, one line each
     * (with whether the app it belongs to has record permission); [defaultService] is the system default recognition service (null when
     * unset or unreadable).
     */
    data class Speech(
        val choice: String,
        val systemAvailable: Boolean,
        val onDevice: Boolean,
        val silent: Boolean,
        val remembered: String?,
        val services: List<String>,
        val defaultService: String? = null,
    )
}

/** Renders a [DiagnosticsSnapshot] as plain text: fixed section order, English keys, easy to read in WeChat or on GitHub. The whole text is redacted one last time at the end. */
object Diagnostics {
    const val HEADER = "Claire diagnostics"

    fun render(s: DiagnosticsSnapshot): String = sanitize(buildString {
        appendLine(HEADER)
        appendLine("generated: ${java.time.Instant.ofEpochMilli(s.generatedAtMs).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)}")

        section("App")
        line("version", "${s.app.versionName} (build ${s.app.build})")
        line("installer", s.app.installer ?: "unknown")
        line("language", s.app.language)

        section("Device")
        val d = s.device
        line("model", if (d.model.startsWith(d.manufacturer, ignoreCase = true)) d.model else "${d.manufacturer} ${d.model}")
        line("android", "${d.androidRelease} (SDK ${d.sdk})")
        line("abi", d.abis.joinToString(", ").ifEmpty { "unknown" })
        line("arm64 sandbox", if ("arm64-v8a" in d.abis) "supported (${d.sandbox})" else "not supported (${d.sandbox})")

        section("Phone control")
        phone(s)

        section("Recovery")
        val r = s.recovery
        for (c in RecoveryCapability.entries) line(capability(c), consent(r.consent, c))
        line("write secure settings", if (r.writeSecureSettings) "granted" else "not granted")
        line("battery optimization", when (r.ignoringBatteryOptimizations) { true -> "unrestricted"; false -> "restricted"; null -> "unreadable" })
        line("last reboot restore", r.lastRestore ?: "none")
        line("restored by helper", r.helperRestores?.let { h ->
            (if (h.lastRestoredAtMs > 0) "${ago(s.generatedAtMs - h.lastRestoredAtMs)}, ${h.recent} recently" else "never") +
                (if (h.gaveUpAtMs > 0) ", backed off ${ago(s.generatedAtMs - h.gaveUpAtMs)}" else "")
        } ?: "never")
        line("accessibility on start", r.lastA11yLoss?.let { "${it.loss.name.lowercase()} ${ago(s.generatedAtMs - it.atMs)}" } ?: "ok")

        section("Models")
        val m = s.models
        line("providers", if (m.providers.isEmpty()) "none" else "${m.providers.size}")
        m.providers.forEach { p -> appendLine("- ${p.kind} ${host(p.baseUrl)}" + (p.listedModels?.let { " ($it models listed)" } ?: "")) }
        line("main", m.main?.let(::role) ?: "not set")
        line("vision", m.vision?.let(::role) ?: "off")
        line("light", m.light?.let(::role) ?: "off")
        line("ChatGPT sign-in", yes(m.chatGptSignedIn))
        m.chatGptLastFailure?.let { f ->
            line("ChatGPT last sign-in failure", "${ago(s.generatedAtMs - f.atMs)}: ${f.kind}" +
                (f.code?.let { " (code $it)" } ?: "") + f.detail.takeIf { it.isNotBlank() }?.let { " — ${it.take(MAX_ERROR)}" }.orEmpty())
        }
        m.accountLastFailure?.let { f ->
            line("account last sign-in failure", "${ago(s.generatedAtMs - f.atMs)}: ${f.kind}" +
                (f.code?.let { " (code $it)" } ?: "") + f.detail.takeIf { it.isNotBlank() }?.let { " — ${it.take(MAX_ERROR)}" }.orEmpty())
        }

        s.speech?.let { v ->
            section("Speech")
            line("speech to text", v.choice)
            line("built-in recognition", if (!v.systemAvailable) "none" else "present" + (if (v.onDevice) ", on-device" else "") + (if (v.silent) ", marked no response" else ""))
            v.remembered?.let { line("recognition service used", it) }
            line("default recognition service", v.defaultService ?: "not set")
            line("recognition services", if (v.services.isEmpty()) "none" else "${v.services.size}")
            v.services.forEach { appendLine("- $it") }
        }

        section("Recent runs")
        if (s.runs.isEmpty()) appendLine("none")
        s.runs.forEach { run ->
            appendLine(
                "- ${ago(s.generatedAtMs - run.startedAtMs)}: ${run.outcome}" +
                    (run.failKind?.let { " ($it)" } ?: "") + (run.judged?.let { ", judged $it" } ?: "") +
                    ", ${run.steps} steps" + (run.model?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""),
            )
        }

        section("Update")
        line("last check", s.update.status)
        line("latest seen", s.update.latest ?: "none")
        line("update available", s.update.available ?: "no")
    }.trimEnd() + "\n")

    private fun StringBuilder.phone(s: DiagnosticsSnapshot) {
        val i = s.setup
        val p = s.phone
        val view = PhoneSetupFlow.view(i)
        line("helper", shell(i.status))
        line("helper restart", when (i.restart) {
            HelperRestart.Wireless -> "wireless debugging (paired)"
            HelperRestart.UsbCommand -> "USB start command"
            HelperRestart.Shizuku -> "Shizuku"
            HelperRestart.None -> "not set up"
        })
        line("setup", "phase=${view.phase}" + (view.current?.let { ", current=${step(it)}" } ?: ""))
        line("steps", SetupStep.entries.joinToString(", ") { "${it.ordinal + 1} ${view.marks[it]}" })
        view.suspect?.let { line("suspect", step(it.step) + if (it.unreadable) " (switch unreadable)" else "") }
        line("confirmed by user", i.confirmed.sortedBy { it.ordinal }.joinToString(", ") { step(it) }.ifEmpty { "none" })
        if (i.accessibilitySkipped) line("accessibility step", "skipped")
        if (i.repair) line("repair", "yes")
        line("developer options", onOff(i.developerOptions))
        if (i.developerSettingsMissing) line("developer options page", "missing")
        if (i.wirelessMissing) line("wireless debugging setting", "missing")
        line("USB debugging", onOff(i.usbDebugging))
        line("wireless debugging", p.wirelessDebuggingSetting?.let(::onOff) ?: "unreadable")
        line("accessibility", when (p.accessibility) {
            AccessibilityState.On -> "on (bound)"
            AccessibilityState.EnabledNotRunning -> "enabled, not bound"
            AccessibilityState.Off -> "off"
        })
        line("restricted settings", if (i.restrictedSettings) "may block" else "no")
        line("pairing service", when (i.discovery) {
            is PairingDiscovery.Found -> "found"
            PairingDiscovery.Searching -> "searching"
            PairingDiscovery.Idle -> "not found / not searching"
        })
        line("connect service", p.connect?.let { c ->
            "cached ${c.cache}, property ${c.property}, mDNS ${c.mdns} candidates; " +
                (if (c.scanned) "scan: ${c.scanOpen} open, ${c.scanAdb} adb; " else "scan: not run; ") +
                "manual: " + (if (c.manual) "yes" else "no") + "; " +
                if (c.connected) "connected" else "none connected"
        } ?: "not tried in this session")
        line("notifications", if (i.notificationsAllowed) "allowed" else "blocked")
        line("pairing channel", "${i.pairingChannel} (" +
            (if (p.pairingChannels.isEmpty()) "not created" else p.pairingChannels.entries.joinToString(", ") { "${it.key}=${importance(it.value)}" }) + ")")
        view.notifyIssue?.let { line("pairing notification issue", it.name) }
        line("attention channel", importance(p.attentionChannelImportance))
        line("Wi-Fi", when (p.wifiConnected) { true -> "connected"; false -> "not connected"; null -> "unknown" })
        line("last error", p.lastError?.takeIf { it.isNotBlank() }?.take(MAX_ERROR) ?: "none")
        line("helper launch stage", p.helperLaunchStage ?: "not tried in this session")
        line("helper handoff rejection", p.helperHandoffRejection ?: "none")
        line("adb key status", p.adbKey ?: "unknown")
        line("app copies", p.appCopies ?: "unknown")
        p.appsAfterTask?.let { line("apps after task", it) }
    }

    private fun StringBuilder.section(name: String) {
        appendLine()
        appendLine("[$name]")
    }

    private fun StringBuilder.line(key: String, value: String) {
        appendLine("$key: $value")
    }

    private fun step(s: SetupStep) = "${s.ordinal + 1} ${s.name}"

    private fun shell(s: BuiltinShellStatus): String = when (s) {
        is BuiltinShellStatus.Failed -> "Failed (reason=${s.reason}, fix=${s.fix})"
        else -> s.toString()
    }

    private fun capability(c: RecoveryCapability) = when (c) {
        RecoveryCapability.Accessibility -> "auto accessibility"
        RecoveryCapability.BatteryWhitelist -> "battery whitelist"
        RecoveryCapability.RestoreAfterReboot -> "restore after reboot"
        RecoveryCapability.AutoWirelessDebugging -> "auto wireless debugging"
    }

    private fun consent(r: RecoveryConsent, c: RecoveryCapability) =
        (if (r.wants(c)) "on" else "off by user") + ", " + (if (r.isApplied(c)) "applied" else "not applied")

    private fun role(r: DiagnosticsSnapshot.RoleModel) = "${r.model} @ ${host(r.baseUrl)}"

    /** Keep only the host name of the address (the path or query may hold other things); writes nothing when it cannot be parsed. */
    fun host(url: String): String = runCatching { URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "(unparsed host)"

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    private fun yes(b: Boolean) = if (b) "yes" else "no"

    /** Notification channel importance using the system's values: 0 off, 1 min, 2 low, 3 default, 4 high. */
    private fun importance(i: Int?) = when (i) {
        null -> "not created"
        0 -> "off"
        1 -> "min"
        2 -> "low"
        3 -> "default"
        4, 5 -> "high"
        else -> "$i"
    }

    fun ago(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "just now"
            m < 60 -> "${m}m ago"
            m < 48 * 60 -> "${m / 60}h ago"
            else -> "${m / (24 * 60)}d ago"
        }
    }

    private const val MAX_ERROR = 200

    private val IP = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b(:\d{1,5})?""")
    /** Full form (at least five groups) or the abbreviated form containing "::"; the 12:30:05 inside a timestamp does not count. */
    private val IPV6 = Regex("""\[?(?:\b(?:[0-9A-Fa-f]{1,4}:){4,7}[0-9A-Fa-f]{1,4}\b|[0-9A-Fa-f]{0,4}::[0-9A-Fa-f:]*[0-9A-Fa-f])(?:%\w+)?\]?(:\d{1,5})?""")
    private val PORT = Regex("""(?i)\bport\s*[=:]?\s*\d{2,5}\b""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val SECRET_PAIR = Regex("""(?i)\b(api[_-]?key|key|token|secret|password|passwd|authorization|cookie)\b(\s*[=:]\s*)\S+""")

    /** Final pass: IP (with port), IPv6, port numbers, e-mail addresses, credential-like "name=value", then hand over to [Redactor] (keys, JWTs, long mixed strings). */
    fun sanitize(text: String): String {
        var out = IP.replace(text, "<ip>")
        out = IPV6.replace(out, "<ip>")
        out = PORT.replace(out, "port <n>")
        out = EMAIL.replace(out, "<email>")
        out = SECRET_PAIR.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + Redactor.MASK }
        return Redactor.pageData(out)
    }
}
