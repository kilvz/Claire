package com.wangzan101.mika.cap.phone.shell

/**
 * Decisions for swapping out and restoring the clipboard; touches no system clipboard, which keeps it easy to unit test.
 * A clipboard read can be silently blocked by the system: the content obtained is empty, while "has content" and
 * "content description" report there is some. Then "it was originally empty" and "it has content but cannot be seen"
 * cannot be told apart, so it always counts as unreadable and must not be treated as empty and cleared or overwritten.
 */
internal object ShellClip {
    enum class Read {
        /** Definitely empty (content, presence and description all report nothing). */
        Empty,
        /** Content was read. */
        Has,
        /** There is content but it cannot be read. */
        Unreadable,
    }

    fun read(clipPresent: Boolean, hasPrimaryClip: Boolean, descriptionPresent: Boolean): Read = when {
        clipPresent -> Read.Has
        hasPrimaryClip || descriptionPresent -> Read.Unreadable
        else -> Read.Empty
    }

    /** What the clipboard looks like right now, before restoring. */
    enum class Now { Ours, Empty, Other, Unreadable }

    enum class Restore {
        /** Put the user's original content back. */
        PutBack,
        /** It was originally empty, so clear Claire's content. */
        Clear,
        /** Leave it alone: the user copied something else in the meantime, or it cannot be read right now. */
        Leave,
    }

    /**
     * [priorEmpty] being true means what was read before the swap-out really was empty (an unreadable one is never
     * swapped out). Put the content back even when someone else cleared the clipboard (for example the system's
     * periodic auto-clear) while it originally had content: an empty clipboard is not something the user copied anew,
     * so putting it back does not overwrite the user's content, whereas not putting it back loses the original.
     */
    fun restore(priorEmpty: Boolean, now: Now): Restore = when (now) {
        Now.Ours -> if (priorEmpty) Restore.Clear else Restore.PutBack
        Now.Empty -> if (priorEmpty) Restore.Leave else Restore.PutBack
        Now.Other, Now.Unreadable -> Restore.Leave
    }
}
