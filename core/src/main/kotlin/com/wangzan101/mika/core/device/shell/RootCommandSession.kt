package com.wangzan101.mika.core.device.shell

/** Supervises the command on su's execution side. Standard input is left to the App, and closing it ends the process group of this command. */
object RootCommandSession {
    private const val SYSTEM_SHELL = "/system/bin/sh"

    /**
     * Only fixed system paths are probed; supervisor programs are not taken from the command's environment or the user's directory.
     * When no usable setsid or kill exists it still runs the original way, with the App ending su as a fallback.
     */
    val androidScript = script(
        shell = SYSTEM_SHELL,
        setsid = listOf(
            listOf("/system/bin/setsid"),
            listOf("/system/bin/toybox", "setsid"),
        ),
        kill = listOf(
            listOf("/system/bin/kill"),
            listOf("kill"),
            listOf("/system/bin/toybox", "kill"),
        ),
    )

    /**
     * The caller puts the command in the variable c. When setsid forks a process, -w waits for it, and the supervisor script obtains the real group id within the new session.
     * The parameters only accept executables and arguments fixed in the code; they are never used to splice user input.
     */
    internal fun script(shell: String, setsid: List<List<String>>, kill: List<List<String>>): String {
        require(setsid.all { it.isNotEmpty() } && kill.all { it.isNotEmpty() })
        val supervisor = """
            set +m
            group=${'$'}${'$'}
            exec 3<&0
            "${'$'}3" -c "${'$'}1" </dev/null 3<&- &
            child=${'$'}!
            (
                IFS= read -r stop <&3
                ${'$'}2 -s KILL -- "-${'$'}group" 2>/dev/null
            ) &
            watcher=${'$'}!
            exec 3<&-
            wait "${'$'}child"
            status=${'$'}?
            ${'$'}2 -s KILL -- "${'$'}watcher" 2>/dev/null
            wait "${'$'}watcher" 2>/dev/null
            exit "${'$'}status"
        """.trimIndent()
        val b = StringBuilder()
        b.append("mika_supervise='").append(supervisor).append("'; mika_kill=; ")
        kill.forEachIndexed { index, candidate ->
            val invocation = candidate.joinToString(" ")
            b.append(if (index == 0) "if " else "elif ")
                .append(available(candidate)).append(" && ")
                .append(invocation).append(" -s 0 -- \"${'$'}${'$'}\" >/dev/null 2>&1; then ")
                .append("mika_kill=\"").append(invocation).append("\"; ")
        }
        if (kill.isNotEmpty()) b.append("fi; ")
        if (setsid.isNotEmpty()) {
            b.append("if [ -n \"${'$'}mika_kill\" ]; then ")
            setsid.forEachIndexed { index, candidate ->
                val invocation = candidate.joinToString(" ")
                b.append(if (index == 0) "if " else "elif ")
                    .append(available(candidate)).append(" && ")
                    .append(invocation).append(" -w \"").append(shell).append("\" -c 'exit 0' </dev/null >/dev/null 2>&1; then ")
                    .append("exec ").append(invocation).append(" -w \"").append(shell)
                    .append("\" -c \"${'$'}mika_supervise\" mika-root \"${'$'}c\" \"${'$'}mika_kill\" \"").append(shell).append("\"; ")
            }
            b.append("fi; fi; ")
        }
        b.append("exec \"").append(shell).append("\" -c \"${'$'}c\" </dev/null")
        return b.toString()
    }

    private fun available(command: List<String>): String =
        if (command[0].startsWith('/')) "[ -x ${command[0]} ]"
        else "command -v ${command[0]} >/dev/null 2>&1"
}
