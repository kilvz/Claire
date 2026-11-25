package com.wangzan101.mika.settings.ui.models

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.openInBrowser

/** The three providers in the "Sign in with account" group of "Add model". ChatGPT has its own sign-in flow; xAI and OpenRouter map to [AccountProvider]. */
enum class SignInKind(val account: AccountProvider?, val label: String, val tag: String) {
    ChatGpt(null, "ChatGPT", "chatgpt"),
    Xai(AccountProvider.Xai, "xAI / Grok", "xai"),
    OpenRouter(AccountProvider.OpenRouter, "OpenRouter", "openrouter");

    /** Preset selected for "Use API Key instead"; ChatGPT has no such option. */
    val keyPreset: String? get() = when (this) {
        ChatGpt -> null
        Xai -> "xAI"
        OpenRouter -> "OpenRouter"
    }

    companion object {
        fun of(account: AccountProvider): SignInKind = entries.first { it.account == account }
    }
}

/** How this provider looks in account sign-in: the signed-in provider id (null if absent or signed out), whether sign-in is running, and the failure reason. */
internal fun ModelSetupUi.signInState(kind: SignInKind): Triple<String?, Boolean, String?> = when (kind) {
    SignInKind.ChatGpt -> Triple(
        providers.firstOrNull { it.kind == ProviderConfig.Kind.ChatGpt }?.id?.takeIf { chatGptAccount?.signedIn == true },
        chatGptSignIn == ListState.Running,
        (chatGptSignIn as? ListState.Failed)?.message,
    )
    else -> {
        val signedIn = if (kind == SignInKind.Xai) xaiAccount?.signedIn == true else openRouterAccount?.signedIn == true
        val id = providers.firstOrNull {
            if (kind == SignInKind.Xai) it.kind == ProviderConfig.Kind.Xai
            else it.signIn == ProviderStore.OPENROUTER_SIGN_IN
        }?.id
        val mine = accountSignIn.provider == kind.account
        Triple(id?.takeIf { signedIn }, mine && accountSignIn.running, accountSignIn.failure?.takeIf { mine })
    }
}

/**
 * "Sign in with account · no Key required": one row each for ChatGPT, xAI / Grok and OpenRouter. Signed-in ones show "Signed in" and open provider details when tapped;
 * signed-out ones open the explanation panel ([onOpen]) and sign-in only starts from inside it. Rows with sign-in in progress show "Signing in…" and open to show progress.
 */
@Composable
internal fun AccountSignInGroup(ui: ModelSetupUi, onOpen: (SignInKind) -> Unit, openProvider: (String) -> Unit) {
    GroupLabel(ModelsCopy.SIGN_IN_GROUP)
    GroupCard {
        SignInKind.entries.forEachIndexed { i, kind ->
            val (signedIn, running, _) = ui.signInState(kind)
            Box(Modifier.testTag("signin-${kind.tag}")) {
                ListRow(
                    null, kind.label, ModelsCopy.signInRowLine(kind), first = i == 0,
                    onClick = { if (signedIn != null) openProvider(signedIn) else onOpen(kind) },
                ) {
                    when {
                        running -> RowValue(ModelsCopy.SIGN_IN_RUNNING)
                        signedIn != null -> Box(Modifier.testTag("signin-${kind.tag}-signed-in")) { RowValue(ModelsCopy.CHATGPT_SIGNED_IN, ValueTone.Ok) }
                        else -> RowChevron()
                    }
                }
            }
        }
    }
}

/** Actions of the sign-in panel: start, cancel, use API Key instead (select that provider's preset and open the Key form), and dismiss. */
class SignInSheetActions(
    val start: (SignInKind) -> Unit = {},
    val cancel: (SignInKind) -> Unit = {},
    val useApiKey: (SignInKind) -> Unit = {},
    val dismiss: () -> Unit = {},
)

/**
 * Panel for signing in to one account. It first explains what will happen (and the risks); sign-in only starts after tapping "Continue sign-in". While signing in: xAI shows a short code (copy it and reopen the confirmation page),
 * the others say "complete it in the browser", and all of them can be cancelled; failures show the reason in the panel and can be retried. On success the page closes the panel and opens the details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SignInSheet(ui: ModelSetupUi, kind: SignInKind, actions: SignInSheetActions) {
    val c = MikaTheme.colors
    ModalBottomSheet(
        onDismissRequest = actions.dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = MikaShapes.SheetTop,
    ) {
        SignInSheetBody(ui, kind, actions)
    }
}

/** The content inside the panel (without the panel itself, for easier testing). */
@Composable
internal fun SignInSheetBody(ui: ModelSetupUi, kind: SignInKind, actions: SignInSheetActions) {
    val (_, running, failure) = ui.signInState(kind)
    val code = ui.accountSignIn.userCode.takeIf { running && kind == SignInKind.Xai && it.isNotBlank() }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).testTag("signin-sheet-${kind.tag}")) {
        when {
            code != null -> DeviceCodeBody(ui.accountSignIn, code) { actions.cancel(kind) }
            // Only one sign-in at a time: this one can't start while another provider is signing in
            else -> IntroBody(kind, running, failure, busy = !running && (ui.chatGptSignIn == ListState.Running || ui.accountSignIn.running), actions)
        }
    }
}

@Composable
private fun IntroBody(kind: SignInKind, running: Boolean, failure: String?, busy: Boolean, actions: SignInSheetActions) {
    val c = MikaTheme.colors
    Text(ModelsCopy.signInTitle(kind), Modifier.fillMaxWidth(), style = MikaType.Title, color = c.ink, textAlign = TextAlign.Center)
    Text(ModelsCopy.signInBody(kind), Modifier.padding(top = 10.dp), style = MikaType.Caption, color = c.ink2)
    ModelsCopy.signInRisk(kind)?.let { Note(it, c.warnSoft, c.warn, Modifier.testTag("signin-risk")) }
    ModelsCopy.signInInfo(kind)?.let { Note(it, c.fill, c.ink2) }
    MikaButton(
        ModelsCopy.signInStart(kind), { actions.start(kind) },
        Modifier.fillMaxWidth().padding(top = 16.dp).testTag("signin-start"), enabled = !running && !busy,
    )
    if (busy) Text(ModelsCopy.SIGN_IN_BUSY, Modifier.padding(top = 10.dp).testTag("signin-busy"), style = MikaType.Caption, color = c.ink2)
    if (running) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ModelsCopy.signInWaiting(kind), Modifier.weight(1f), style = MikaType.Caption, color = c.ink2)
            Text(
                ModelSetupCopy.CANCEL,
                Modifier.clickable { actions.cancel(kind) }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp).testTag("signin-cancel"),
                style = MikaType.Caption, color = c.accent,
            )
        }
    }
    failure?.let {
        Text(it, Modifier.padding(top = 10.dp).testTag("signin-failed"), style = MikaType.Caption, color = c.bad)
    }
    if (kind.keyPreset != null && !running) {
        MikaButton(
            ModelsCopy.SIGN_IN_USE_KEY, { actions.useApiKey(kind) },
            Modifier.fillMaxWidth().padding(top = 4.dp).testTag("signin-use-key"), kind = ButtonKind.Text, size = ButtonSize.Medium,
        )
    }
}

/** xAI device code: the same short code and expiry as on the browser confirmation page; copy it and reopen the confirmation page; can be cancelled while waiting. */
@Composable
private fun DeviceCodeBody(s: AccountSignInUi, code: String, cancel: () -> Unit) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    Text(ModelsCopy.XAI_CODE_TITLE, Modifier.fillMaxWidth(), style = MikaType.Title, color = c.ink, textAlign = TextAlign.Center)
    Text(
        ModelsCopy.XAI_CODE_BODY, Modifier.fillMaxWidth().padding(top = 10.dp),
        style = MikaType.Caption, color = c.ink2, textAlign = TextAlign.Center,
    )
    Text(
        code, Modifier.fillMaxWidth().padding(top = 16.dp).testTag("signin-code"),
        fontFamily = FontFamily.Monospace, style = MikaType.Display, letterSpacing = 4.sp,
        fontWeight = FontWeight.SemiBold, color = c.ink, textAlign = TextAlign.Center,
    )
    if (s.expiresAtMs > 0) {
        val minutes = ((s.expiresAtMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)
        Text(ModelsCopy.codeValid(minutes.toInt()), Modifier.fillMaxWidth().padding(top = 4.dp), style = MikaType.Hint, color = c.ink3, textAlign = TextAlign.Center)
    }
    MikaButton(
        ModelsCopy.XAI_CODE_COPY_REOPEN, { copyAndReopen(context, code, s.openUrl) },
        Modifier.fillMaxWidth().padding(top = 16.dp).testTag("signin-copy-reopen"), kind = ButtonKind.Tonal,
    )
    Text(
        ModelsCopy.XAI_CODE_WAITING, Modifier.fillMaxWidth().padding(top = 14.dp),
        style = MikaType.Caption, color = c.ink2, textAlign = TextAlign.Center,
    )
    MikaButton(
        ModelSetupCopy.CANCEL, cancel, Modifier.fillMaxWidth().padding(top = 4.dp).testTag("signin-cancel"),
        kind = ButtonKind.Text, size = ButtonSize.Medium,
    )
}

@Composable
private fun Note(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Text(
        text, modifier.fillMaxWidth().padding(top = 12.dp).background(bg, MikaShapes.Chip).padding(horizontal = 12.dp, vertical = 10.dp),
        style = MikaType.Hint, color = fg,
    )
}

private fun copyAndReopen(context: Context, code: String, url: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("code", code))
    Toast.makeText(context, tr(R.string.account_signin_copied), Toast.LENGTH_SHORT).show()
    if (url.isNotBlank()) com.wangzan101.mika.ui.openInSystemBrowser(context, url)
}
