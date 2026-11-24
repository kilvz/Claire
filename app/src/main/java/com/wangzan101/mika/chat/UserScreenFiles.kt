package com.wangzan101.mika.chat

import android.content.Context
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.core.screen.user.CapturedScreen
import com.wangzan101.mika.core.screen.user.UserScreen
import com.wangzan101.mika.core.screen.user.UserScreenJson
import com.wangzan101.mika.core.screen.user.UserScreenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Screens the user sent are stored alongside the conversation: in that conversation's workspace (`/workspace/screens/<id>.jpg` and `.json`), the same place as attachments,
 * so they disappear with the conversation's workspace; commands in the sandbox can read the image too. All reads and writes go through the workspace's file gate (commands in the sandbox cannot read or write elsewhere even via symlinks),
 * and writes hold the tree lock's permit (mutually exclusive with a reset).
 */
class UserScreenFiles(
    private val dirs: SandboxDirs,
    private val resolveWorkspace: (String) -> WorkspaceKey = WorkspaceKey::Conversation,
) {
    constructor(context: Context) : this(SandboxDirs(context), WorkspaceResolver.resolver(context))

    /** Stores it in conversation [conversationId] and returns the description with its id. */
    suspend fun save(conversationId: String, shot: CapturedScreen): UserScreen = dirs.tree.shared {
        withContext(Dispatchers.IO) {
            val id = "s" + UUID.randomUUID().toString().replace("-", "").take(12)
            val screen = shot.screen.copy(id = id)
            val ws = dirs.workspace(resolveWorkspace(conversationId))
            ws.mkdirs()
            shot.jpeg?.let { dirs.gate.replace(ws, "$DIR/$id.jpg", it) }
            dirs.gate.replace(ws, "$DIR/$id.json", UserScreenJson.encode(screen).toByteArray())
            screen
        }
    }

    suspend fun screen(conversationId: String, id: String): UserScreen? = read(conversationId, "$id.json")?.let { UserScreenJson.decode(String(it)) }

    suspend fun image(conversationId: String, id: String): ByteArray? = read(conversationId, "$id.jpg")

    /** The screens in this conversation, handed to the task so it can fetch them by id. */
    fun source(conversationId: String): UserScreenSource = object : UserScreenSource {
        override suspend fun screen(id: String) = this@UserScreenFiles.screen(conversationId, id)
        override suspend fun image(id: String) = this@UserScreenFiles.image(conversationId, id)
    }

    private suspend fun read(conversationId: String, name: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!SAFE.matches(name)) return@withContext null
        runCatching {
            dirs.gate.openRead(dirs.workspace(resolveWorkspace(conversationId)), "$DIR/$name").use { f ->
                if (f.size > MAX_BYTES) null else f.read(0, f.size.toInt())
            }
        }.getOrNull()
    }

    private companion object {
        const val DIR = "screens"
        const val MAX_BYTES = 20L shl 20
        val SAFE = Regex("^[A-Za-z0-9_-]{1,64}\\.(jpg|json)$")
    }
}
