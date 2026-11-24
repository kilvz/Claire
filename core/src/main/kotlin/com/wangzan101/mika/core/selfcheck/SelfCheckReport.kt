package com.wangzan101.mika.core.selfcheck

import java.net.URLEncoder
import java.util.Locale

/**
 * Write one self-check as a plain-text report: the header holds the version and device facts, then each item is one line "number name: verdict — fact", English keys, understandable by anyone.
 * Only [DeviceFacts] and each item's [CheckResult.detail] are written, no accounts, app lists, user content or serial numbers;
 * finally the whole text goes through [sanitize] (in the app this is the same redaction as "copy diagnostic info"), and each item's fact is first flattened to one line and shortened.
 */
object SelfCheckReport {
    const val HEADER = "Claire self-check"
    private const val MAX_DETAIL = 200

    fun render(state: SelfCheckState, sanitize: (String) -> String = { it }): String = sanitize(buildString {
        val f = state.facts
        appendLine("$HEADER ${f?.appVersion ?: "?"} (build ${f?.appBuild ?: "?"})")
        if (f != null) {
            appendLine("Device: ${f.modelName}, Android ${f.androidRelease} (SDK ${f.sdk}), ${f.abis.firstOrNull() ?: "unknown ABI"}, RAM ${gb(f.ramBytes)}, ${f.screenWidth}x${f.screenHeight}")
            appendLine("Wireless debugging: ${when (f.wirelessDebugging) { true -> "present"; false -> "missing"; null -> "unknown" }}")
            f.developerOptionsPage?.let { appendLine("Developer options page: ${if (it) "present" else "missing"}") }
            if (f.phoneControlUnavailable) appendLine("Phone control: unavailable in this environment")
        } else {
            appendLine("Device: unknown")
        }
        appendLine(summary(state))
        appendLine()
        for (r in state.results) appendLine(line(r))
    }.trimEnd() + "\n")

    /** One line per item: `8 stays alive: FAIL — frames stopped after 6 s`. */
    fun line(r: CheckResult): String {
        val detail = oneLine(r.detail)
        return "${r.check.number} ${r.check.key}: ${label(r.status)}" + if (detail.isEmpty()) "" else " — $detail"
    }

    fun label(s: CheckStatus): String = when (s) {
        CheckStatus.Pending -> "PENDING"
        CheckStatus.Running -> "RUNNING"
        CheckStatus.Pass -> "PASS"
        CheckStatus.Partial -> "PARTIAL"
        CheckStatus.Fail -> "FAIL"
        CheckStatus.Skipped -> "SKIPPED"
    }

    private fun summary(s: SelfCheckState): String {
        val n = s.results.groupingBy { it.status }.eachCount()
        fun c(st: CheckStatus) = n[st] ?: 0
        val counts = "${c(CheckStatus.Pass)} pass, ${c(CheckStatus.Partial)} partial, ${c(CheckStatus.Fail)} fail, ${c(CheckStatus.Skipped)} skipped"
        val secs = (s.elapsedMs + 500) / 1000
        return if (s.phase == SelfCheckState.Phase.Cancelled) "Result: cancelled after $secs s; $counts" else "Result: $counts in $secs s"
    }

    private fun oneLine(s: String): String {
        val flat = s.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= MAX_DETAIL) flat else flat.take(MAX_DETAIL - 1) + "…"
    }

    private fun gb(bytes: Long): String =
        if (bytes <= 0) "unknown" else String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))

    /** The title of the feedback issue: the compatibility tag followed by `Android <version> <model>`; the model is exactly what the system reports (no manufacturer name added). */
    fun issueTitle(f: DeviceFacts?): String =
        if (f == null) "[Compatibility]" else "[Compatibility] Android ${f.androidRelease} ${f.model.trim()}".trim()

    /**
     * URL for creating a new issue: pick the device feedback template and pre-fill only the title. The report is too long to put into the URL (URLs have a length limit), so the user pastes it.
     * [newIssue] is the repository's new-issue URL.
     */
    fun issueUrl(newIssue: String, f: DeviceFacts?): String =
        "$newIssue?template=$ISSUE_TEMPLATE&title=${encode(issueTitle(f))}"

    const val ISSUE_TEMPLATE = "device-report.yml"

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
