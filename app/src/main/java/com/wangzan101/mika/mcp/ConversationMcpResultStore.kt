package com.wangzan101.mika.mcp

import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.core.cap.mcp.McpResultStore
import com.wangzan101.mika.core.workspace.SandboxPaths
import java.util.UUID

/**
 * Long results from external MCP tools are written into the current task workspace, where the model can read them with read_file.
 * Only `mcp-results/` is written, and only safe characters are kept for server/tool in the file name.
 */
class ConversationMcpResultStore(
    private val dirs: SandboxDirs,
    private val workspace: WorkspaceKey,
) : McpResultStore {
    constructor(dirs: SandboxDirs, conversationId: String) : this(dirs, WorkspaceKey.Conversation(conversationId))

    override fun save(serverId: String, toolName: String, content: String): String {
        val ws = dirs.workspace(workspace)
        dirs.ensureBindSources(ws)
        val server = serverId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
        val tool = toolName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
        val rel = "mcp-results/$server-$tool-${UUID.randomUUID()}.txt"
        dirs.gate.createNew(ws, rel).use { it.write(content.toByteArray(Charsets.UTF_8)) }
        return "${SandboxPaths.WORKSPACE}/$rel"
    }
}
