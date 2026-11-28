package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.device.DeviceAccess
import com.wangzan101.mika.core.mcp.McpRpc
import com.wangzan101.mika.core.mcp.McpTaskTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

/**
 * Every HTTP and MCP request builds its task service from the same authenticated ownership, so no protocol path can bypass task, file or export
 * permissions. The read-only web console ([RemoteCompanion]) sits alongside the task API and likewise goes through the authentication entry here.
 */
class RemoteEndpoints(
    private val tasks: RemoteTasks,
    private val serverVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val device: DeviceAccess? = null,
    private val history: RemoteHistory? = null,
    private val records: RunExportSource? = null,
    private val calls: com.wangzan101.mika.core.calls.PhoneCallPort? = null,
    private val companion: RemoteCompanion? = null,
    /** The user paused Claire: the MCP and CLI APIs share this one flag ([refuseRemote]). */
    private val paused: com.wangzan101.mika.core.run.PauseFlag = com.wangzan101.mika.core.run.PauseFlag.Never,
) {
    private val companionApi = companion?.let(::CompanionApi)
    private val clients = LinkedHashMap<String, Long>()
    private val mutable = MutableStateFlow(RemoteActivity())
    val activity = mutable.asStateFlow()

    fun handle(req: HttpRequest, deviceAccess: Boolean = true, historyAccess: Boolean = true): HttpResponse {
        // Relay adapters do not carry the phone's bearer token. They still get one isolated task namespace,
        // never the internal unscoped view and never local history.
        val owner = req.accessOwner ?: "relay"
        val scopedTasks = tasks.scope(owner)
        val service = RemoteTaskService(scopedTasks, records = records?.scope(owner), paused = paused)
        val rpc = McpRpc(McpTaskTools(service, device.takeIf { deviceAccess }, calls.takeIf { deviceAccess }), serverVersion) { remote, name -> seen(remote, name) }
        if (req.path == McpRpc.PATH) return rpc.handle(req)
        if (req.path == RemoteTaskApi.ROOT || req.path.startsWith("${RemoteTaskApi.ROOT}/")) {
            seen(req.remote, req.header("x-mika-client")?.take(64))
            if (companionApi != null && CompanionApi.owns(req.path)) return companionApi.handle(req)
            val api = RemoteTaskApi(service, serverVersion, device.takeIf { deviceAccess }, history.takeIf { historyAccess }, companion = companion != null)
            return api.handle(req)
        }
        return RemoteTaskApi.error(404, "not_found", "No such endpoint")
    }

    private fun seen(remote: InetAddress, name: String?) {
        val now = clock()
        synchronized(this) {
            val who = remote.hostAddress ?: "?"
            val key = if (name != null) "$who|$name" else clients.keys.lastOrNull { it.startsWith("$who|") } ?: "$who|"
            clients.remove(key); clients[key] = now
            clients.entries.removeAll { now - it.value > RemoteHost.RECENT_MS }
            mutable.value = RemoteActivity(clients.size, now)
        }
    }
}
