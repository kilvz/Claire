package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.settings.Provider

/**
 * When a task failed because of the model and the main model has not yet passed testing at its provider ([untested], see [com.wangzan101.mika.settings.ProviderStore.untestedMainProvider]):
 * append a new line after the reason pointing at that provider's "Test again" ([hint] produces that sentence from the provider name). Any other ending is returned unchanged.
 */
internal object RetestHint {
    fun apply(end: TaskState, untested: Provider?, hint: (String) -> String): TaskState =
        if (end is TaskState.Failed && end.kind == FailKind.Model && untested != null) {
            end.copy(reason = end.reason + "\n" + hint(untested.name))
        } else end
}
