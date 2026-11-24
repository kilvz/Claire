package com.wangzan101.mika.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.wangzan101.mika.ui.components.DetailNote
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.WebProxy
import com.wangzan101.mika.core.net.ProxyAddress
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.NetProxyStore
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * Copy for Settings › Network proxy (pure functions, easy to test). The address shows only the protocol, host and port, never credentials (this line is always visible).
 * It is only edited here on the settings home page; the row on the Files & compute environment page just displays it.
 */
object NetProxyCopy {
    val TITLE: String get() = tr(R.string.net_proxy)
    val SUMMARY: String get() = tr(R.string.net_proxy_summary)
    val HINT: String get() = tr(R.string.net_proxy_hint)
    val INVALID: String get() = tr(R.string.net_proxy_invalid)

    /** Right side of the row on the settings home page: "Not set" when unset, the address when set. */
    fun value(proxy: String): String = NetProxyStore.display(proxy).ifEmpty { tr(R.string.settings_not_set) }

    /**
     * Description below that row: normally says which connections it covers; when the form is container-only or the browser cannot use it, it says so plainly.
     * [browser] empty means the browser side has not been checked yet.
     */
    fun subtitle(proxy: String, browser: WebProxy.Mode?): String {
        val address = ProxyAddress.parse(proxy)
        return when {
            address != null && !address.forApp -> tr(R.string.net_proxy_container_only)
            browser == WebProxy.Mode.Unsupported -> tr(R.string.net_proxy_browser_unsupported)
            browser == WebProxy.Mode.SocksWithCredentials -> tr(R.string.net_proxy_browser_socks_auth)
            else -> tr(R.string.net_proxy_row_sub)
        }
    }
}

/** Edit the network proxy: one input field; empty means unused; when the value is invalid it explains the format inline and does not save. */
@Composable
fun NetProxyDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val ok = NetProxyStore.valid(text)
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(NetProxyCopy.TITLE) },
        text = {
            Column {
                DetailNote(NetProxyCopy.SUMMARY, NetProxyCopy.HINT, Modifier.padding(bottom = 4.dp))
                MikaTextField(
                    text, { text = it }, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("net-proxy-input"),
                    singleLine = true, isError = !ok, placeholder = { Text("http://127.0.0.1:7890") },
                )
                if (!ok) Text(NetProxyCopy.INVALID, Modifier.padding(top = 4.dp), style = MikaType.Hint, color = MikaTheme.colors.bad)
            }
        },
        confirmButton = { TextButton({ onSave(text) }, enabled = ok) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
