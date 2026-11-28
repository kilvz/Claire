# Output references

## Intent

The model produces files in the sandbox `/workspace` (charts, reports, audio, screenshots, etc.), but right now:

- there is no way to show or tap them for the user directly in the chat — only `share_file`, which copies them to "Downloads/Claire", and the user has to go find them in the folder;
- to reference one again in a later turn, it can only remember the `/workspace/...` path itself, relying on memory and unreliable.

Yet "user-provided attachments" have long had this treatment (an `Attachment:/workspace/inbox/...` line + the chat page's `AttachmentMessage` card). The gap is that the "model output" half does not have it.

## Goals

Give "things the model produced" the same "referenceable + viewable/tappable in chat" treatment as "user-provided attachments".

## Non-goals

- No URL scheme (no `mika://` or the like).
- No cross-session persistence: the workspace is isolated by conversation ID and cleared when the conversation ends (this is already the case).
- Do not change the sandbox security boundary (path normalization and link-escape interception stay as they are).

## Current state (confirmed)

- `/workspace` is partitioned by "conversation ID" (`TaskTools` has `workspace: () -> String`, commented "this task's workspace (conversation ID)", defaulting to `"default"`).
- Input attachments go through `AttachmentLines` (`Attachment:/workspace/...` text lines) + the chat page's `AttachmentMessage` rendering.
- `share_file` copies the file to the host's "Downloads/Claire" and returns `savedAs` (a name, not a renderable reference).
- Assistant replies are Markdown (rendered by `MarkdownText`), but the renderer does not recognize `/workspace/...` images/links.

## Approach

Reuse the `/workspace` path itself as the "output reference" rather than inventing another format. Three small pieces:

1. **Rendering**: the chat page's Markdown rendering recognizes `/workspace/...` — images/audio inline, ordinary files drawn as file cards (reusing `AttachmentMessage`).
2. **Prompt**: tell the model "files written to `/workspace` will be rendered in the chat if written as Markdown images/links", and prefer this over `share_file` when showing them.
3. **Tool results**: make `/workspace/...` paths explicit in the results of output-producing tools (mostly already the case; just unify it).

## Decisions

- A reference = the `/workspace/...` path itself, not a URL scheme and not a new line-of-text convention (assistant replies are Markdown anyway).
- Session-scoped: reuse the existing "partitioned by conversation ID", cleared when the conversation ends.
- The rendering layer reuses `AttachmentMessage`: images/audio inline, ordinary files drawn as cards.
