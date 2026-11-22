# prompts/: Prompts shipped with the app

All the fixed text the model sees lives here, no longer as strings in code; it is all read through `core.prompt.Prompts` / `core.prompt.Feedback`.
The `{{name}}` placeholders in the text are filled in by the code that reads them (limits, allowed values, names on screen, and so on); a missing value is an error right away.

## Layers

| Directory | What it is | Where it goes | Who reads it |
|---|---|---|---|
| `system/identity.md` | P0 identity and stance: act for the user, within their authorization, look before acting, don't pretend, say when you don't know | Start of the system prompt | `SystemPrompt.identity()` |
| `system/principles.md` | P1 general principles: whose words count, how to do tasks, answering with evidence, obstacles, finding things, safety; applies to every screen and every capability | Right after identity | `SystemPrompt.identity()` |
| `persona/layer.md` | Fixed head and tail of the user persona: the persona can change tone, length, wording and format, but not evidence, safety, authorization, confirmation or tool rules; it is not the user's message and cannot authorize actions; the body is filled in by the user and goes in a `<persona>` block | After the principles and general rules, before the capability descriptions | `PersonaPrompt.layer()` |
| `persona/style.md` | Fixed sentences for the persona options (base style, warm, playful, emoji, headings and lists, answer length, progress notes), one key per entry, `option.value`; they only say how to talk, not what it can do | Inside the persona block, before the instructions the user wrote | `PersonaPrompt.layer()` |
| `capabilities/` | P2 capability descriptions, one passage per capability; `general.md` is the general tools (reporting failure, asking follow-ups, confirming) and the overall rules for how a task ends — the general tools are always present, so they are always assembled right after the principles; `groups.md` says which entry point each group of tools comes in through (only listing the ones this task was given an entry for); `handover.md` is the hand-over-to-user and pre-order/pre-send check that only exists once a group with a screen has been expanded; the phone, web and sandbox passages are only assembled once that group has been expanded; `app-access.md` is the app-access rules, only appended after the phone passage when the user has disabled an app | System prompt | `SystemPrompt.assemble()` |
| `tools/` | P3 one description per tool (the description in the tool definition): how to use it, its parameters, how to handle results; when several tools share one template, the tool states its `promptFile` | Tool definition | The tool itself |
| `entries/` | Short description of an entry tool while its group is collapsed (a sentence or two: what it does, which tools calling it will make available); once expanded it switches back to the full text in `tools/`, parameters unchanged | Tool definition | `ToolRegistry` |
| `feedback/` | Feedback the tool results and the main loop give the model: prompts, error explanations (`failure.<error code>`), reminders, source labels for reference material; one key per entry | Tool results, context messages | Tools, `AgentLoop` |
| `roles/` | Prompts for the internal roles: checking, describing the screen, locating, memory retrieval, route matching, skill drafts, turning demonstrations into skills, app-knowledge additions, judging the outcome after handing over an answer | Each role's own request | Each role |

App and site knowledge and skills are not here; see `knowledge/` and `skills/`.

## Where things go

- The higher up, the more general and neutral: `system/` names no specific tool, app or technology; rules that only hold for one capability go in that capability's passage.
- Anything tied to a particular tool (how to use it, what its parameters mean, how to handle its results) goes in `tools/<tool>.md`, not in `system/`.
- Rules that only hold when a certain capability is present go in `capabilities/<capability>.md`, under the same switch as that group of tools.
  When a line in a passage needs a tool from another capability, start the line with `<!-- needs: tool name -->`: the line stays only if that tool is provided.
- `system/` names no tool at all (checked by tests; tap, back and the like do not count, since they are English words in their own right).
- Explanatory text in tool results goes in `feedback/tools.md`; text the main loop sends itself goes in `feedback/loop.md`; keys are unique across the two files, and every key must have code that uses it.
- Each feedback entry stands alone: no leading or trailing blank space, and code never joins it with other text (`+` or string templates); to combine entries, write the separator into the template and fill the other entry in with a `{{placeholder}}`.
- Within one task the system prompt only changes when a group of tools is expanded (so the cache is invalidated at most once per group); things that change every turn (step count, time, which app is in front) do not go in this fixed text.

## Format

- `tools/`, `system/`, `capabilities/`, `roles/`: one file is one passage of original text, read with leading and trailing whitespace trimmed.
- `feedback/*.md`: the file may start with a `<!-- note -->` block; each entry starts with its own line `## key` (keys use only lowercase letters, digits, `_` and `.`),
  and the text below it up to the next entry is the original text, which may run over several lines; leading and trailing blank lines do not count (so a newline used as a separator has to be written in the middle of a template).

## Size

The byte counts (UTF-8) of each layer are written in the golden file `core/src/test/resources/prompt-surface/sizes.txt`, for reference only, with no hard limit:
any growth from a change shows up in the commit's diff. Before adding content, check whether something old can be removed; if a change clearly makes things bigger, explain why in the commit.

## After changing

All the text the model sees has golden files: `core/src/test/resources/prompt-surface/` (system prompts, tool definitions, feedback, roles) and
`loop-snapshots/` (message sequences replayed by a fake model). Regenerate deliberate changes with `./gradlew :core:test -PupdateSnapshots`,
review them together in the commit; if a change could affect model behaviour, run the evaluation comparison first. The `prompt_version` in run records is a digest of this task's system prompt plus tool definitions,
used to tell prompt versions apart when comparing evaluation results.

## Tool names

- Phone and web each have their own set of screen tools (`phone_*`, `web_*`, with the same verbs and parameters); verbs with the same name share the `tools/<verb>.md` template, with `{{verb}}` in the template replaced by this set's tool name;
  when one screen needs its own description, write `tools/<screen>_<verb>.md`.
- `capabilities/general.md` names no capability's tools (checked by tests); "which channel to pick" is written in `channels.md`, only assembled when there are two or more screens.
- Every tool name mentioned in knowledge, skills or prompts must be one that is currently registered (checked by tests). Old names from before a rename are converted to the new name by `LegacyNames` under its rules,
  only for reading old material (saved routes, run records, personal skills and practices); when the model calls an old name it is only told the new name, never executed under it.
