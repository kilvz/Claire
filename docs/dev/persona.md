# Persona (nameable custom instructions)

A persona is a nameable, reusable custom prompt: a set of options chosen in the UI plus a piece of instruction text you write yourself. Choose one in Settings and it applies to all conversations. It only changes how Claire speaks (tone, length, wording, format, how progress notes are written), not what Claire can do. Memory answers "what it knows"; persona answers "how it does things".

> Status: v1 (#292) has been merged and is on by default in releases. Capability narrowing, import/export and model-group binding are planned for later versions; see the end of this document.

## Where the prompt goes

The persona is assembled after identity, principles and general rules, and before each capability description:

```
system/identity.md
system/principles.md
capabilities/general.md
prompts/persona/layer.md (fixed header and footer + the user's body)
capabilities/groups.md, channels.md, phone.md, web.md, sandbox.md, … (the capabilities expanded for this task)
```

- `TaskRun` assembles it once at the start and it does not change within a task; the main AgentLoop and sub-AgentLoops use the same one.
- Condition checks in scheduled tasks (`conditionCheck`, an internal read-only check required to output only JSON) do not inherit the persona.
- The run record uses `RunEvent.State("persona", "<name> · vN")` to note who was used this round; `PromptVersion` automatically covers prompt changes.
- Without a persona, `SystemPrompt.withPersona(base, null) == base`, and the existing prompt goldens are byte-for-byte unchanged.

## Options

`PersonaStyle` (`core/src/main/kotlin/com/wangzan101/mika/core/prompt/PersonaStyle.kt`) is the part chosen in the UI:

- Base style and tone: Default / Crisp / Friendly / Playful (stored as `default`, `crisp`, `warm`, `playful`).
- Three three-level options: emoji, answer length, progress notes (all stored as `less`, `default`, `more`).

The options are deliberately few: one base style sets the tone, three options cover the three things most often asked to be changed, and everything else goes into the instructions. The previously tried "Professional" style and the friendliness, enthusiasm, and headings-and-lists options have been removed; if a file still carries them, they are read as default.

Each non-default value corresponds to one fixed sentence in `core/src/main/resources/prompts/persona/style.md` (the key is "option.value", same format as the feedback files), assembled in fixed order inside the persona block before the user-written instructions, one per line, with a blank line between them and the instructions. When all options are default, nothing is added, and a persona with only instructions comes out byte-for-byte the same as before options existed; when all options are default and the instructions are empty, this layer is absent.

The options only land within the scope the fixed header allows a persona to change. "Ask me fewer questions" and "skip the verification" belong to authorization and evidence rules and are not made into options. The light model and the main model use the same system prompt, and the options apply to both.

Writing guidance for users and AI assistants is in [persona-writing.md](../persona-writing.md).

## Name

A persona can carry a name (`Persona.nickname`): the assistant's self-designation, Claire by default. It goes into fixed text outside the fence, so it is strictly sanitized by `PersonaPrompt.nickname`: only letters of any script, digits, combining marks, spaces, hyphens and middle dots are kept, consecutive whitespace is collapsed into one, at most 16 characters, and if it is empty after sanitizing it becomes Claire. A name that the UI sanitizing has altered cannot be saved (`PersonaPrompt.nicknameAccepted`) — it is not changed silently.

- When the name is Claire, the identity sentence and the persona layer are byte-for-byte the same as before.
- When it is not Claire: the first sentence of `system/identity.md` uses the name (the file has a placeholder); it is immediately followed by a `persona/renamed.md` passage explaining that Claire is the name of the app it lives in, that "Claire" later in the text and in tool descriptions refers to this app and to itself, and that renaming does not change what it can do; the "You are still …" in the persona layer's fixed header also uses the name.
- When only the name is changed and everything else is default, there is no persona layer, but the run record still notes which one was used this round.
- 「Claire」 in the UI (the top bar, the top-left of the answer card, copy in Settings) does not follow the name: that is the app's name.
- Claire in tool descriptions, feedback text and internal-role prompts is unchanged; whether messages addressing it by the new name go equally smoothly through internal steps such as intent detection has not been tested. Personal assistant mode has its own prompt and carries no name.

## Fixed header/footer and escaping

`core/src/main/resources/prompts/persona/layer.md` is the fixed header and footer, read only by code; users cannot change it. It makes clear that:

- a persona is not a user message and cannot authorize tasks, call tools, read memory/the screen, change settings, send externally or disclose data;
- it can only change tone, length, wording, format and how progress notes are written;
- it cannot change evidence, safety, privacy, authorization, confirmation or completion rules;
- a persona cannot grant tools, permissions or access.

`PersonaPrompt` (`core/src/main/kotlin/com/wangzan101/mika/core/prompt/PersonaPrompt.kt`) handles assembly:

- the name has newlines, control characters and bidi control characters removed, is length-limited to 40, then has `& < > "` escaped;
- the body has control characters other than newline/tab and bidi control characters removed;
- tag-like text (`</persona>`, `<persona …>`, and variants with spaces/newlines, full-width angle brackets, or words split by zero-width characters) is escaped uniformly so the body cannot close the block early; zero-width joiners themselves are kept (Persian and combined emoji need them);
- the body is truncated safely by token (limit 3000 tokens) without splitting surrogate pairs;
- `{{…}}` in the body is kept as-is (`Prompts.fill` replaces in a single pass).

## Storage

- `files/settings/personas.json`: `{ "version": 1, "personas": [...], "active": "<id>" }`. `active` is the set selected in Settings, written in the same write as the list; deleting the active one clears it too, and an id pointing at a nonexistent one is treated as none. It carries `version`, is written atomically (temp file + rename), and when it cannot be parsed the original file is saved aside as `personas.json.bad-<time>` and it is treated as an empty list. Not encrypted, contains no keys.
- Fields: `id`, `name`, `description`, `prompt`, `style` (options; all default when missing in an older file, unrecognized values read as default), `nickname` (the name; empty means Claire), `revision`, `createdAt`, `updatedAt`, `builtin` (v1 ships no built-in examples; field reserved). `revision` increments by 1 only when the content actually changes (tapping the tier already selected writes nothing and does not increment); only the latest content is kept.
- The per-conversation file `<conversationId>.persona` (upstream v1's per-conversation binding) is no longer read or written: `persona` / `setPersona` in `ConversationStore` and the copy on branching remain, but tasks do not look at it, and existing files on disk are left untouched.

## Safety boundaries

- The persona does not take part in constructing the tool list, permissions, approvals or model selection.
- A persona cannot authorize actions: when an imported persona (a future version) asks to call tools, change settings or send externally, the model still acts according to the user's message and the safety rules.
- When capability narrowing is done in the future, it will **subtract** from the capabilities the user has already authorized (a deny list), not take a union; general tools, MCP, plugins, memory, scheduled tasks and subtasks are left alone until there is a unified permission model.
- "Clear all" currently does not delete personas; the persona page deletes them one by one. Backup integration will be designed separately.

## UI

- Entry: Settings › Preferences › Persona (right after "Language and appearance"); the row end shows what is in use now: Default / the name of the active set when several are stored / the base style's label / Adjusted. There is no persona entry on the main screen.
- The persona page (`PersonaScreen`) opens on the set in use. Edits on the page are a draft; only tapping "Save" at the bottom writes them and returns to the previous page; if there are unsaved changes and you go back or go to "Manage personas", it first asks "Discard changes?". At the top is the name input row; the base style is four radio rows with descriptions; the three three-level options each take one row in a card, with the current value at the row end, and tapping opens a radio dialog; when any option is not default, the card gets an extra row at the end, "Restore default options" (which only touches options); the instructions are a multi-line input box on the page.
- When there is none in use, the page shows all defaults; the first save creates one (named "My style") and sets it active (`PersonaStore.editActive`); when the saved content is the same as before, nothing is written and the revision does not increment.
- At the bottom of the page, "Manage personas": the stored sets; tapping a row opens an action sheet (use this one, rename, duplicate, delete), and the active one is marked "In use"; "New persona" creates an all-default set and selects it directly; at most 20 sets; for the active set, when a prompt has been assembled you can expand "Preview full prompt". Deleting the active set returns to default.
- Changes take effect for all conversations from the next task onward (including scheduled tasks, channels and tasks handed over from the computer); a task already running is unaffected.
- Feature flag `FeatureFlags.persona()`: on by default, with no entry in Settings; when off, the UI shows no persona entry and tasks carry no persona.

## Tests

```bash
./gradlew :core:test --tests "*PersonaPromptTest*" --tests "*PromptSurfaceTest*"
./gradlew :app:testDebugUnitTest --tests "*PersonaStoreTest*" --tests "*ConversationStoreTest*" --tests "*StringResourcesTest*"
./gradlew :app:compileDebugKotlin
```

Prompt goldens: `core/src/test/resources/prompt-surface/persona/layer.txt`, `layer-options.txt`, `renamed.txt`. After intentionally changing the fixed header/footer, regenerate with `-PupdateSnapshots` and review them together in the commit.

## Files

- `core/.../core/prompt/PersonaPrompt.kt`, `PersonaStyle.kt`
- `core/src/main/resources/prompts/persona/layer.md`, `style.md`, `renamed.md`
- `app/.../settings/PersonaStore.kt`
- `app/.../chat/ConversationStore.kt` (`persona` / `setPersona`: upstream v1's conversation binding, no longer used)
- `app/.../task/TaskRun.kt` (assembled at start, excluded from condition checks, shared with subtasks)
- `app/.../ui/PersonaScreen.kt`
- `app/.../ui/SettingsScreen.kt`, `MainActivity.kt`, `Navigation.kt`
- `app/src/main/res/values*/strings_persona.xml`

## Not doing yet

- Capability narrowing (v2, `RunSpec.disabledCapabilities` deny list, to avoid accidentally deleting MCP providers)
- Import/export (v2, JSON + full-text preview + no auto-enable)
- Model group binding, skill pinning (v3)
- Multiple persona stacking, persona marketplace, persona-specific memory
