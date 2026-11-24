package com.wangzan101.mika.core.learn.knowledge

/** Whether a single script in a knowledge or skill directory can run right now; when it cannot, [reason] gives the cause. */
data class ScriptState(val file: String, val runnable: Boolean, val reason: String?)

/** The ability to run bundled scripts, handed to knowledge_read, skill_read and reference notes. */
interface BundledScripts {
    /** This task offers the script-running tool (the runtime may still be getting ready). */
    fun offered(): Boolean

    /** When the runtime is ready right now, first reconcile the skill directory to the bundled copy and return true; false when it is not ready. */
    suspend fun ready(): Boolean

    /** The scripts in the manifest under [skillDir], and whether each can run right now. */
    fun scripts(skillDir: String): List<ScriptState>
}
