package com.wangzan101.mika.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.search.WebSearchProvider
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.GroundingStatus
import com.wangzan101.mika.settings.WebSearchConfig
import com.wangzan101.mika.settings.WebSearchEntry
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** Text needed by the web search settings; it does not touch the UI, so unit tests test it directly. */
internal object WebSearchCopy {
    /** The provider's name in the UI: a brand name, not translated. */
    fun name(p: WebSearchProvider): String = when (p) {
        WebSearchProvider.Google -> "Google Search"
        WebSearchProvider.You -> "You.com"
        WebSearchProvider.Jina -> "Jina"
        WebSearchProvider.Exa -> "Exa"
        WebSearchProvider.DeepSeek -> "DeepSeek"
        WebSearchProvider.SearXNG -> "SearXNG"
    }

    /** The line below the provider when this one is selected: how it is used and what has to be filled in. */
    fun note(p: WebSearchProvider): String = tr(
        when (p) {
            WebSearchProvider.Google -> R.string.web_search_note_google
            WebSearchProvider.You -> R.string.web_search_note_you
            WebSearchProvider.Jina -> R.string.web_search_note_jina
            WebSearchProvider.Exa -> R.string.web_search_note_exa
            WebSearchProvider.DeepSeek -> R.string.web_search_note_deepseek
            WebSearchProvider.SearXNG -> R.string.web_search_note_searxng
        },
    )

    fun groundingUnavailableMessage(grounding: GroundingStatus): String =
        if (grounding.providerName.isNullOrBlank()) {
            tr(R.string.web_search_google_unavailable_none)
        } else {
            tr(R.string.web_search_google_unavailable_provider, grounding.providerName)
        }

    fun keyLabel(p: WebSearchProvider): String = tr(if (p.keyRequired) R.string.web_search_api_key_required else R.string.web_search_api_key)

    /** Only SearXNG needs an address: the URL of its instance. */
    fun endpointLabel(): String = tr(R.string.web_search_searxng_url)

    const val ENDPOINT_PLACEHOLDER = "https://searx.example.org"

    fun problem(
        p: WebSearchProvider,
        why: WebSearchProvider.Problem,
        grounding: GroundingStatus = GroundingStatus(),
    ): String = when (why) {
        WebSearchProvider.Problem.Endpoint -> tr(R.string.web_search_invalid_endpoint)
        WebSearchProvider.Problem.Key -> tr(R.string.web_search_missing_key, name(p))
        WebSearchProvider.Problem.UnsupportedProvider -> groundingUnavailableMessage(grounding)
    }

    /** Where to get a key for the providers that have a key field (their official key pages); You.com works without one, and gives a higher quota with it. */
    fun keyPage(p: WebSearchProvider): String? = when (p) {
        WebSearchProvider.You -> "https://you.com/platform/api-keys"
        WebSearchProvider.Jina -> "https://jina.ai/?sui=apikey"
        WebSearchProvider.Exa -> "https://dashboard.exa.ai/api-keys"
        WebSearchProvider.DeepSeek -> "https://platform.deepseek.com/api_keys"
        else -> null
    }

    /** Right side of the row under Advanced & other: which provider is in use when on, "Off" when off. */
    fun value(config: WebSearchConfig): String = when {
        !config.enabled -> tr(R.string.state_off)
        config.provider == WebSearchProvider.Google && !config.grounding.available ->
            tr(R.string.web_search_google_unavailable_value)
        else -> name(config.provider)
    }
}

/**
 * Web search settings under Advanced & other; still a dialog within the existing settings hierarchy, adding no new page.
 * A row of providers on top (chips, the same as for the recognition language), and below it only what the selected provider needs: the hosted ones just a key (Jina and Exa required),
 * SearXNG just the instance URL.
 * What is entered for each provider is stored separately, so switching back and forth loses nothing and never spills into another provider.
 * When something is missing, "Save" can still be tapped: tapping does not save; it marks the field to fill in red, spells out what is missing and moves the cursor there;
 * nothing is marked red on open, only after Save has been tapped or a field was filled and then cleared. The providers that must have a key are labelled "Required"; every key field gets a link for obtaining a key.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebSearchDialog(initial: WebSearchConfig, onDismiss: () -> Unit, onSave: (WebSearchConfig) -> Unit) {
    var enabled by remember(initial) { mutableStateOf(initial.enabled) }
    var provider by remember(initial) { mutableStateOf(initial.provider) }
    val entries = remember(initial) { mutableStateMapOf<WebSearchProvider, WebSearchEntry>().apply { putAll(initial.entries) } }
    val grounding = initial.grounding
    val entry = entries[provider] ?: WebSearchEntry()
    val config = WebSearchConfig(enabled, provider, entries.toMap(), grounding)
    val problem = config.problem
    // Only fields that were hit by a Save tap (recomputed when switching provider) or that have been typed in get marked red when something is missing
    var tried by remember(initial) { mutableStateOf(false) }
    val touched = remember(initial) { mutableStateMapOf<Pair<WebSearchProvider, WebSearchProvider.Problem>, Boolean>() }
    fun shows(why: WebSearchProvider.Problem) = problem == why && (tried || touched[provider to why] == true)
    val endpointFocus = remember { FocusRequester() }
    val keyFocus = remember { FocusRequester() }
    val uri = LocalUriHandler.current
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.web_search_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.web_search_enabled), Modifier.weight(1f))
                    MikaSwitch(
                        enabled,
                        { next ->
                            if (!enabled && next && grounding.available &&
                                (provider == WebSearchProvider.Google ||
                                    (provider == WebSearchProvider.You && entries[WebSearchProvider.You]?.apiKey.isNullOrBlank()))
                            ) {
                                provider = WebSearchProvider.Google
                                tried = false
                            }
                            enabled = next
                        },
                        Modifier.testTag("web-search-enabled"),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(stringResource(R.string.web_search_google_grounding_toggle))
                        if (grounding.available) {
                            if (provider != WebSearchProvider.Google) {
                                Text(
                                    stringResource(R.string.web_search_note_google),
                                    modifier = Modifier.padding(top = 2.dp),
                                    style = MikaType.Hint,
                                    color = MikaTheme.colors.ink3,
                                )
                            }
                        } else {
                            Text(
                                WebSearchCopy.groundingUnavailableMessage(grounding),
                                modifier = Modifier.padding(top = 2.dp).testTag("web-search-grounding-unavailable"),
                                style = MikaType.Hint,
                                color = MikaTheme.colors.ink3,
                            )
                        }
                    }
                    MikaSwitch(
                        checked = enabled && provider == WebSearchProvider.Google,
                        onCheckedChange = { next ->
                            if (next && grounding.available) {
                                enabled = true
                                provider = WebSearchProvider.Google
                                tried = false
                            } else if (!next && provider == WebSearchProvider.Google) {
                                enabled = false
                            }
                        },
                        modifier = Modifier.testTag("web-search-grounding-toggle"),
                        enabled = grounding.available,
                    )
                }
                GroupLabel(stringResource(R.string.web_search_provider), Modifier.padding(top = 8.dp))
                FlowRow(
                    Modifier.fillMaxWidth().testTag("web-search-provider"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WebSearchProvider.entries.forEach { p ->
                        val chipEnabled = p != WebSearchProvider.Google || grounding.available
                        SelectChip(
                            WebSearchCopy.name(p),
                            p == provider,
                            { if (chipEnabled) { provider = p; tried = false } },
                            Modifier.testTag("web-search-provider-${p.wire}"),
                            enabled = chipEnabled,
                        )
                    }
                }
                Text(
                    WebSearchCopy.note(provider), Modifier.padding(top = 8.dp).testTag("web-search-note"),
                    style = MikaType.Hint, color = MikaTheme.colors.ink3,
                )
                if (shows(WebSearchProvider.Problem.UnsupportedProvider)) {
                    Text(
                        WebSearchCopy.problem(provider, WebSearchProvider.Problem.UnsupportedProvider, grounding),
                        modifier = Modifier.padding(top = 4.dp).testTag("web-search-error"),
                        style = MikaType.Hint,
                        color = MikaTheme.colors.bad,
                    )
                }
                if (provider.needsEndpoint) {
                    val error = shows(WebSearchProvider.Problem.Endpoint)
                    OutlinedTextField(
                        entry.endpoint, { entries[provider] = entry.copy(endpoint = it); touched[provider to WebSearchProvider.Problem.Endpoint] = true },
                        Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(endpointFocus).testTag("web-search-endpoint"),
                        singleLine = true, isError = error,
                        label = { Text(WebSearchCopy.endpointLabel()) }, placeholder = { Text(WebSearchCopy.ENDPOINT_PLACEHOLDER) },
                        supportingText = if (error) ({ Text(WebSearchCopy.problem(provider, WebSearchProvider.Problem.Endpoint, grounding)) }) else null,
                    )
                }
                if (provider.takesKey) {
                    val error = shows(WebSearchProvider.Problem.Key)
                    OutlinedTextField(
                        entry.apiKey, { entries[provider] = entry.copy(apiKey = it); touched[provider to WebSearchProvider.Problem.Key] = true },
                        Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(keyFocus).testTag("web-search-key"),
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        isError = error,
                        label = { Text(WebSearchCopy.keyLabel(provider)) },
                        supportingText = if (error) ({ Text(WebSearchCopy.problem(provider, WebSearchProvider.Problem.Key, grounding)) }) else null,
                    )
                    WebSearchCopy.keyPage(provider)?.let { page ->
                        // openUri throws when there is no app that can open web pages: do nothing in that case
                        TextButton({ runCatching { uri.openUri(page) } }, Modifier.testTag("web-search-get-key")) { Text(stringResource(R.string.web_search_get_key)) }
                    }
                }
                // The two important points from the original description: keys are stored encrypted on this device, and results are treated as untrusted content
                Text(
                    stringResource(R.string.web_search_privacy), Modifier.padding(top = 8.dp).testTag("web-search-privacy"),
                    style = MikaType.Hint, color = MikaTheme.colors.ink3,
                )
            }
        },
        confirmButton = {
            TextButton({
                when (problem) {
                    null -> onSave(config)
                    else -> {
                        tried = true
                        runCatching {
                            when (problem) {
                                WebSearchProvider.Problem.Key -> keyFocus.requestFocus()
                                WebSearchProvider.Problem.Endpoint -> endpointFocus.requestFocus()
                                WebSearchProvider.Problem.UnsupportedProvider -> Unit
                            }
                        }
                    }
                }
            }, Modifier.testTag("web-search-save")) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
