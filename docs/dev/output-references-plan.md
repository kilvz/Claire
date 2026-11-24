# Output references Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let files the model produces in `/workspace` (charts/reports/audio/screenshots) be referenced, rendered and opened in chat just like "user attachments".

**Architecture:** Three small pieces — ① extract `/workspace/...` references from the model reply's Markdown (pure logic, JVM-testable); ② resolve references into "type + host file"; ③ the chat page reuses `AttachmentMessage`'s photo grid / file card rendering, and tapping opens `FileViewerScreen`. Plus one prompt line guiding the model to use it this way.

**Tech Stack:** Kotlin + Jetpack Compose; commonmark (already a dependency) for Markdown parsing; `SandboxPort.hostPath` for path resolution; reuse of `ViewerKinds`/`AttachmentMessage`/`FileViewerScreen`.

**Spec:** `docs/dev/output-references.md`

## Global Constraints

- A reference is the `/workspace/...` path itself; no URL scheme, no new line of text convention.
- Session-scoped: reuse the existing `workspace: () -> String` (conversation ID); cleared when the conversation ends, no cross-session persistence.
- Do not change the sandbox security boundary: the resolved path must still go through `SandboxPaths.normalize` / `hostPath` (anything out of bounds or escaping via a link does not count as a reference).
- All new strings go through `R.string`, added in 11 languages (the i18n test checks).

## Review Focus

| Input/case | Expected behavior |
|---|---|
| The reference is `![alt](/workspace/a.png)` (image) or `[name](/workspace/a.csv)` (link) | Both are extracted as `/workspace/...` |
| Bare mention of `/workspace/c` in the body (not a Markdown image/link) | Not treated as a reference |
| Path contains `..`, or `hostPath` returns null (out of bounds/escape/does not exist) | That reference is not rendered, no crash |
| File is .csv/.pdf/.py or other non-media | Draw a file card (not a photo grid); tapping previews it |
| File no longer exists (after the session is cleared) | The card shows 「已失效」 (Expired) or is simply not rendered, no crash |

---

### Task 1: Extract `/workspace` references from Markdown (pure logic)

**Files:**
- Modify: `app/src/main/java/com/wangzan101/mika/ui/MarkdownModel.kt`
- Test: `app/src/test/java/com/wangzan101/mika/ui/MarkdownModelTest.kt` (append if it already exists)

**Interfaces:**
- Produces: `fun MarkdownModel.workspaceRefs(markdown: String): List<String>` — returns, from the `destination` of every `Image`/`Link` node in the Markdown, the paths starting with `/workspace` (in order of appearance, deduplicated, kept as-is including the `/workspace` prefix).

- [ ] **Step 1: Write a failing test**

```kotlin
@Test fun workspaceRefsExtractsImageAndLink() {
    assertEquals(
        listOf("/workspace/a.png", "/workspace/b.csv"),
        MarkdownModel.workspaceRefs("看图 ![图](/workspace/a.png) 和 [报告](/workspace/b.csv)"),
    )
}
@Test fun workspaceRefsIgnoresBareTextAndHttp() {
    assertEquals(emptyList<String>(), MarkdownModel.workspaceRefs("文件在 /workspace/c 里，外链 https://x.com/a.png"))
}
@Test fun workspaceRefsDeduplicates() {
    assertEquals(listOf("/workspace/a.png"), MarkdownModel.workspaceRefs("![x](/workspace/a.png) ![y](/workspace/a.png)"))
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.wangzan101.mika.ui.MarkdownModelTest"`
Expected: Compilation failure / `workspaceRefs` undefined

- [ ] **Step 3: Implement `MarkdownModel.workspaceRefs(markdown: String): List<String>`**

Parse with the commonmark `Parser` already in use, walk the `Image` and `Link` nodes, take the `destination`s that start with `/workspace`, and deduplicate in order. Other Markdown (code blocks, plain text) naturally produces no Image/Link nodes and needs no special handling.

- [ ] **Step 4: Run the test and confirm it passes**

Run: Same as above
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wangzan101/mika/ui/MarkdownModel.kt app/src/test/java/com/wangzan101/mika/ui/MarkdownModelTest.kt
git commit -m "feat(markdown): extract /workspace references from replies"
```

---

### Task 2: Resolve references into "type + host file"

**Files:**
- Create: `app/src/main/java/com/wangzan101/mika/chat/ui/WorkspaceRef.kt` (or merge into the existing attachment file, see below)
- Test: `app/src/test/java/com/wangzan101/mika/chat/ui/WorkspaceRefTest.kt`

**Interfaces:**
- Consumes: `MarkdownModel.workspaceRefs(markdown)` (Task 1)
- Produces:
  ```kotlin
  data class WorkspaceRef(val path: String, val host: java.io.File, val kind: ViewerKind)
  fun resolveWorkspaceRefs(sandbox: SandboxPort, workspace: String, refs: List<String>): List<WorkspaceRef>
  ```
  `kind` uses `ViewerKinds` (Image / other), and audio can be merged in later; `host` comes from `sandbox.hostPath(workspace, ref, forWrite=false)`, and the reference is dropped when it is null.

- [ ] **Step 1: Write a failing test** (use a fake `SandboxPort` where `hostPath` returns a `File` for existing paths and null for out-of-bounds/`..`)

```kotlin
@Test fun resolveWorkspaceRefsDropsInvalid() {
    // fake hostPath: /workspace/a.png -> file, /workspace/../x -> null
    val r = resolveWorkspaceRefs(fake, "conv1", listOf("/workspace/a.png", "/workspace/../x"))
    assertEquals(listOf("/workspace/a.png"), r.map { it.path })
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.wangzan101.mika.chat.ui.WorkspaceRefTest"`
Expected: FAIL (type/function undefined)

- [ ] **Step 3: Implement `resolveWorkspaceRefs`**

For each ref: first `SandboxPaths.normalize(ref)` and check it still starts with `/workspace`, then `hostPath`, dropping it if null; `kind = ViewerKinds.of(null, host.name)`.

- [ ] **Step 4: Run the test and confirm it passes** → **Step 5: Commit** (`feat: resolve /workspace references to host files and types`)

---

### Task 3: Render output references in the chat page

**Files:**
- Modify: the assistant message rendering entry point (the Message rendering in `ChatScreen.kt` or near `MarkdownText.kt`), and the rendering primitives in `AttachmentMessage.kt`
- Test: manual verification + the JVM tests from Task 1/2 as a safety net (no instrumentation for Compose UI)

**Interfaces:**
- Consumes: `MarkdownModel.workspaceRefs` (Task 1), `resolveWorkspaceRefs` (Task 2)

- [ ] **Step 1: Hook references into assistant message rendering**

If the model reply contains `/workspace` references, render them below its Markdown text with `AttachmentMessage`'s photo grid (images) + file cards (non-images); tapping opens `FileViewerScreen` (reusing the existing open logic, including workspace addressing).

- [ ] **Step 2: Manual/UI verification**: have the model write `![image](/workspace/a.png)` and `[table](/workspace/b.csv)`, and confirm they render in the chat as a photo thumbnail and a file card respectively, and preview when tapped.

- [ ] **Step 3: Commit** (`feat: render /workspace output references in chat`)

---

### Task 4: Prompt guidance

**Files:**
- Modify: the section about "files/workspace" in the system prompt (the corresponding file under `core/src/main/resources/prompts/`)

- [ ] **Step 1: Add a line of guidance**

Tell the model: images/files written to `/workspace` will be rendered in the chat if they are written in the reply as Markdown `![description](/workspace/xxx.png)` or `[file name](/workspace/xxx.csv)`; when showing something to the user, prefer this over `share_file`.

- [ ] **Step 2: Run the prompt-surface / DocsConsistency related tests**

Run: `./gradlew :core:test`
Expected: prompt snapshot tests updated if any, otherwise PASS

- [ ] **Step 3: Commit** (`feat(prompt): guide the model to reference /workspace output`)

---

## Self-Review conclusion

- Covers the three small spec pieces + the prompt, nothing missed.
- Task 1 is pure logic, JVM-testable, and the anchor of the whole chain; Task 3 is Compose UI, mainly manually verified, so the testable logic (parsing/resolution) is all pushed down into Task 1/2.
- The five Review Focus items land on Task 1 (bare text/http not wrongly extracted, deduplication), Task 2 (out-of-bounds/missing dropped), Task 3 (non-media drawn as cards, failures do not crash).
