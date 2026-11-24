package com.wangzan101.mika.task

import com.wangzan101.mika.core.cap.root.ROOT_CONFIRM_APP
import com.wangzan101.mika.i18n.tr
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.wangzan101.mika.R
import com.wangzan101.mika.task.alertQuietly
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.safety.gate.IrrevAction

/**
 * Text on the confirmation card and notification. The fields are all written by the model: treat them purely as plain text (parse no markup at all);
 * newlines and line separators are collapsed to spaces, and control characters, zero-width characters and text-direction controls are stripped;
 * "·" and colons inside the fields are replaced with other symbols, so no fake field such as "· Amount:" can be assembled.
 */
object ConfirmationCopy {
    /** How many characters of the content are shown on the collapsed single line. */
    const val CONTENT_MAX = 60
    /** Maximum characters of content shown in the expanded card and notification (same as the limit at request time). */
    const val DETAIL_MAX = 200
    /** The note on the confirmation card: only actions that cost money need your consent, and each consent covers only that one time. */
    val NOTE: String get() = tr(R.string.confirm_note)

    fun actionLabel(action: IrrevAction): String = when (action) {
        IrrevAction.send -> tr(R.string.chat_send)
        IrrevAction.pay -> tr(R.string.confirm_pay)
        IrrevAction.delete -> tr(R.string.confirm_delete)
        IrrevAction.order -> tr(R.string.confirm_order)
        IrrevAction.submit -> tr(R.string.confirm_submit)
        IrrevAction.other -> tr(R.string.confirm_other)
    }

    /** A root command is not inside any app: the card title says it is a root command, "where" says the whole phone, and the command is shown in full (only commands that change something are asked about). */
    private fun root(r: ConfirmationRequest) = r.app == ROOT_CONFIRM_APP

    fun title(r: ConfirmationRequest) = tr(R.string.confirm_title, if (root(r)) tr(R.string.confirm_root) else actionLabel(r.action))

    /** The collapsed line: "in <app name> · target: <on whom> · content: <first 60 chars> · amount: <how much>"; empty fields are omitted. */
    fun body(r: ConfirmationRequest, appName: String): String = fields(r, appName, CONTENT_MAX).joinToString(" · ")

    /** Big text on the expanded card and notification: one field per line, with the content shown in full (at most 200 characters). */
    fun detail(r: ConfirmationRequest, appName: String): String = fields(r, appName, if (root(r)) Int.MAX_VALUE else DETAIL_MAX).joinToString("\n")

    private fun fields(r: ConfirmationRequest, appName: String, contentMax: Int): List<String> {
        val content = plain(r.content).let { if (it.length > contentMax) it.take(contentMax) + "…" else it }
        return listOfNotNull(
            tr(R.string.confirm_in_app, if (root(r)) tr(R.string.confirm_root_where) else plain(appName)),
            plain(r.target).takeIf { it.isNotEmpty() }?.let { tr(R.string.confirm_target, it) },
            content.takeIf { it.isNotEmpty() }?.let { tr(R.string.confirm_content, it) },
            r.amount?.let(::plain)?.takeIf { it.isNotEmpty() }?.let { tr(R.string.payment_amount, it) },
        )
    }

    private val INVISIBLE = Regex("[\\u061C\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2069\\uFEFF]")
    private val SPACES = Regex("[\\s\\u2028\\u2029\\u0085\\u00A0\\u3000]+")

    private fun plain(s: String) = s.replace(INVISIBLE, "").replace(SPACES, " ")
        .filterNot { Character.isISOControl(it) }
        .replace('·', '/').replace(':', '∶').replace('：', '∶')
        .trim()
}

/** The prominent notification for the confirmation gate: the same high-priority channel as takeover, with "Approve" and "Decline" buttons. */
object ConfirmationNotifier {
    /** Carried by the notification and card buttons; the service uses it to discard answers tapped on a stale notification or card. */
    const val EXTRA_REQUEST = "confirmation_request"

    /** The app's display name; the package name is used when it cannot be read (not installed, package invisible). */
    fun appLabel(context: Context, packageName: String): String = if (packageName == ROOT_CONFIRM_APP) tr(R.string.confirm_root_where) else try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().ifBlank { packageName }
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    /** One ID per request: the loop creates a new waiting state for each request, and the service and UI in the same process get the same object. */
    fun requestKey(state: TaskState.WaitingConfirmation): Int = System.identityHashCode(state)

    /** The data of the button Intent: distinct per request and per button, so the system never swaps a stale notification's button for a new request. */
    fun intentData(key: Int, approve: Boolean) = "mika-confirm://request/$key/" + if (approve) "approve" else "decline"

    fun build(
        context: Context, channel: String, state: TaskState.WaitingConfirmation, service: Class<*>, approveAction: String, declineAction: String,
        contentIntent: PendingIntent,
        /** Each task uses a separate request-code range when several await confirmation at once, and the buttons also carry [runExtra] to mark which task they belong to. */
        codeOffset: Int = 0,
        runExtra: Pair<String, String>? = null,
        /** Deliver normally but without sound or a heads-up banner (while the floating capsule is showing); see [alertQuietly]. */
        quiet: Boolean = false,
    ): Notification {
        val r = state.request
        val key = requestKey(state)
        val app = appLabel(context, r.app)
        fun button(title: Int, action: String, approve: Boolean, code: Int): Notification.Action {
            val pi = PendingIntent.getService(
                context, code + codeOffset,
                Intent(context, service).setAction(action).setData(Uri.parse(intentData(key, approve))).putExtra(EXTRA_REQUEST, key)
                    .apply { runExtra?.let { (k, v) -> putExtra(k, v) } },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
            )
            return Notification.Action.Builder(null, context.getString(title), pi).apply {
                // Approving on the lock screen requires unlocking first
                if (approve && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAuthenticationRequired(true)
            }.build()
        }
        // On the lock screen only the kind of action to approve is exposed; the target and content become visible after unlocking
        val public = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ConfirmationCopy.title(r))
            .setContentText(context.getString(R.string.confirmation_locked_hint))
            .build()
        return Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ConfirmationCopy.title(r))
            .setContentText(ConfirmationCopy.body(r, app))
            .setStyle(Notification.BigTextStyle().bigText(ConfirmationCopy.detail(r, app)))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(button(R.string.action_approve, approveAction, true, 20))
            .addAction(button(R.string.action_decline, declineAction, false, 21))
            .apply { if (quiet) alertQuietly() }
            .build()
    }
}
