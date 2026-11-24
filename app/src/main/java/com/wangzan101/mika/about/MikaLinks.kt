package com.wangzan101.mika.about

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import java.net.URLEncoder

/** Every external address Claire uses lives here: repository, issues, and the two sources used to check for updates. Changing an address happens only in this one place. */
object MikaLinks {
    const val REPO = "https://github.com/kilvz/Claire"
    const val REPO_LABEL = "github.com/kilvz/Claire"
    const val README = "$REPO#readme"
    const val RELEASES = "$REPO/releases"
    const val NEW_ISSUE = "$REPO/issues/new"
    const val X = "https://x.com/KilZT"
    const val X_HANDLE = "@KilZT"
    /** Update checks ask only the GitHub releases endpoint (no sign-in, rate-limited per hour). */
    const val LATEST_RELEASE_API = "https://api.github.com/repos/kilvz/Claire/releases/latest"

    /** Device and version information pre-filled into the issue template when giving feedback. */
    data class Device(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val versionName: String,
        val build: String,
    )

    /** Pre-filled issue body: three lines for device model, Android version and Claire version, with a few empty paragraphs below for the user to write in. */
    fun issueBody(d: Device): String {
        val model = if (d.model.startsWith(d.manufacturer, ignoreCase = true)) d.model else "${d.manufacturer} ${d.model}"
        return buildString {
            append(tr(R.string.issue_body, model.trim(), d.androidRelease, d.sdk, d.versionName, d.build))
        }
    }

    /** New-issue URL with an empty title and a pre-filled body; parameters are URL-encoded (spaces become %20). */
    fun issueUrl(d: Device): String = "$NEW_ISSUE?title=&body=${encode(issueBody(d))}"

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
