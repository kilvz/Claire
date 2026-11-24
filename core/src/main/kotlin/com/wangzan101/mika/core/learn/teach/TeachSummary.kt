package com.wangzan101.mika.core.learn.teach

import com.wangzan101.mika.core.learn.skills.PersonalSkill
import kotlinx.coroutines.CancellationException

/**
 * Orchestration of one assembly: assemble the skill and write it into pending confirmation.
 *
 * If the user discards this demonstration during the summary, or has already started a new demonstration, nothing is written — a draft the model has already returned must not land in pending confirmation either,
 * or an unclaimed draft would appear in the skill list. Persisting is itself re-checked against the session by the caller under the same lock;
 * the two [current] checks here only discover "no need to go any further" a little earlier (a model call is also an expense).
 */
object TeachSummary {
    sealed interface Outcome {
        /** The draft was written into pending confirmation; [byModel] says whether the body was written by the model. */
        data class Drafted(val draftId: String, val skill: PersonalSkill, val byModel: Boolean) : Outcome

        /** The demonstration still exists, but no usable draft was assembled. */
        data object Failed : Outcome

        /** The session was discarded or replaced with a new one, and nothing was written. */
        data object Abandoned : Outcome
    }

    /**
     * @param current whether it is still this demonstration
     * @param build handed to the model or assembled locally
     * @param store writes into pending confirmation and records it in the session; must return false when the session is no longer this one
     * @param newId the id of the new draft
     */
    suspend fun run(
        current: () -> Boolean,
        build: suspend () -> TeachDraft.Result?,
        store: (String, TeachDraft.Result) -> Boolean,
        newId: () -> String,
    ): Outcome {
        if (!current()) return Outcome.Abandoned
        val result = try {
            build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (result == null) return if (current()) Outcome.Failed else Outcome.Abandoned
        if (!current()) return Outcome.Abandoned
        val id = newId()
        val stored = try {
            store(id, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        return when {
            stored -> Outcome.Drafted(id, result.skill, result.byModel)
            current() -> Outcome.Failed
            else -> Outcome.Abandoned
        }
    }
}
