package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.chat.ProjectStore

/**
 * Sandbox workspace identifier: it belongs either to a single conversation ([Conversation]) or to an entire project ([Project]).
 *
 * Conversations and the built-in fixed workspaces (`self-check`, `mcp-settings`) keep their original ID as the wire identifier ([wire]),
 * with a host directory of `c-<id>` (or `h-<hash>`); project workspaces use `p:<id>` as the wire identifier and `p-<id>` as the host directory.
 * Valid conversation IDs contain no `:`, so the two can never collide on disk directories or serialization keys.
 */
sealed interface WorkspaceKey {
    val wire: String

    data class Conversation(val id: String) : WorkspaceKey {
        override val wire: String get() = id
    }

    data class Project(val id: String) : WorkspaceKey {
        init {
            require(ProjectStore.isValidId(id)) { "invalid project workspace id: $id" }
        }

        override val wire: String get() = "$PROJECT_PREFIX$id"
    }

    companion object {
        private const val PROJECT_PREFIX = "p:"

        fun parse(wire: String): WorkspaceKey =
            if (wire.startsWith(PROJECT_PREFIX)) {
                val id = wire.removePrefix(PROJECT_PREFIX)
                if (ProjectStore.isValidId(id)) Project(id) else Conversation(wire)
            } else {
                Conversation(wire)
            }
    }
}
