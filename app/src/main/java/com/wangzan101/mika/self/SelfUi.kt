package com.wangzan101.mika.self

import com.wangzan101.mika.core.self.SelfScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** The Workspace file to open: conversation id + sandbox path. */
data class SelfFileRequest(val conversationId: String, val path: String, val name: String?)

/** One self UI request: open a screen, or open a file. */
data class SelfUiRequest(
    val id: Long,
    val screen: SelfScreen? = null,
    val file: SelfFileRequest? = null,
    val url: String? = null,
    val loginHost: String? = null,
    val newTab: Boolean = false,
    val show: Boolean = true,
    val note: String? = null,
    val atMs: Long = System.currentTimeMillis(),
)

/**
 * In-process self UI requests. Tasks run in the service while the UI lives in MainActivity; both are in the same process,
 * so a request is kept in a StateFlow and can still be handled when the UI comes back. Modelled on [com.wangzan101.mika.memory.MemoryRuntime.openRequest].
 */
class SelfUi {
    private val _request = MutableStateFlow<SelfUiRequest?>(null)
    val request: StateFlow<SelfUiRequest?> = _request.asStateFlow()
    private val serial = AtomicLong()

    fun requestOpen(
        screen: SelfScreen? = null,
        file: SelfFileRequest? = null,
        url: String? = null,
        loginHost: String? = null,
        newTab: Boolean = false,
        show: Boolean = true,
        note: String? = null,
    ): Long {
        val id = serial.incrementAndGet()
        _request.value = SelfUiRequest(id, screen, file, url, loginHost, newTab, show, note)
        return id
    }

    /** The UI has handled it; clear only that one, so a later request is not removed by mistake. */
    fun consumed(id: Long) {
        if (_request.value?.id == id) _request.value = null
    }
}

/** The extras carried back when a background notification is tapped open. */
internal object SelfIntents {
    const val EXTRA_SCREEN = "mika_self_screen"
    const val EXTRA_FILE = "mika_self_file"
    const val EXTRA_FILE_CONVERSATION = "mika_self_file_conversation"
    const val EXTRA_FILE_NAME = "mika_self_file_name"
    const val EXTRA_URL = "mika_self_url"
    const val EXTRA_LOGIN_HOST = "mika_self_login_host"
    const val EXTRA_NEW_TAB = "mika_self_new_tab"
    const val EXTRA_SHOW = "mika_self_show"
}
