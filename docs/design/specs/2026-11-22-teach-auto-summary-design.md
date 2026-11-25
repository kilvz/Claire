# Teaching Claire: auto-summarize into a skill as soon as the demonstration ends

Date: 2026-11-22
Status: Pending approval

## Intent and why

When a user teaches Claire something, what they expect is: "I do it once, it learns it by itself and summarizes the skill well". The experience today is: the demonstration ends → the page lists the recorded raw steps one by one, each step paired with two input boxes, 「改成什么」 (Change to what) and 「需要说明」 (Needs explanation) → the user taps 「生成」 (Generate) themselves → then goes to find it in the memory drafts box. The page is a bare TextField plus a button, like a debugging tool.

More fundamentally, the summarization angle is wrong. What gets recorded is "how a person does it": back to the desktop, find the icon and open the app, open the browser, tap the address bar, type, tap search. These are just mechanical actions a person is forced to perform, not knowledge; Claire can launch the app directly, or open a link or search result directly. What is really worth keeping is: which entry point to go in through, which button to tap, what to fill in, how to tell it got done, and when to stop and ask. The current model draft is not told this distinction, and the on-device fallback draft copies everything verbatim step by step, so the summary is still "a recording of a person's operations".

## Goals

1. As soon as the demonstration ends, organize automatically; the user no longer taps 「生成」.
2. The organized result is presented directly as a finished skill: structured, readable, acceptable; manual mechanical steps are omitted and written as Claire's direct approach.
3. The whole teach-Claire flow is redone at product quality: intro, during the demonstration, organizing, summary, learned.
4. Without a model it still produces a structured skill (on-device organizing + a clear explanation), and no longer falls back to the raw step list.

## Non-goals

- Do not save the specific text entered during the demonstration (passwords, verification codes and search terms are all left out of storage); the skill is written as parameterized steps, and the specific content is supplied by the request at execution time.
- No screenshots/screen recording; the summary is based only on step text, package names and UI names.
- Do not change the "learn by itself from completed tasks" pipeline, and do not change the skill execution and adoption mechanism.
- Do not accept automatically: show the summary to the user first, and only enable the skill after they tap accept.
- Do not change the `TeachPhase` enum value names (old-session compatibility), only the semantics and UI.

## Anti-goals

- Writing manual mechanical actions into the skill ("back to the desktop → tap the icon → tap the address bar → type → tap search").
- Inventing entry points, links or content that the demonstration never went through, in the name of "direct reach".
- The summary page ever showing a per-step editor or a 「生成」 button again.
- Silent failure: no model, summarization failure, 0 steps, process killed — all must be explained clearly and be retryable.
- Brand special cases: identify both the desktop and the browser by system intent, do not hard-code package names.

## Constraints

- Generic Android: look up the desktop package with `ACTION_MAIN + CATEGORY_HOME`; use the default handler of `ACTION_VIEW` as the browser hint; if identification fails, fail open (no folding, no accidental deletion).
- The existing privacy stance is unchanged: password fields are not recorded, sensitive content is not written to storage, the original input is not saved.
- 12 language files updated in sync for additions/removals; `StringResourcesTest` must be green.
- Develop on a worktree branch, do not touch main; do not change the version number outside a release.

## Approach

### 1. New flow

```
Idle/Ready (intro) → Recording ⇄ Paused → [End] → Drafting (organizing automatically)
  → Reviewing (summary ready) → [Accept] → Saved (learned)
                          ├─ [Demonstrate again] → back to intro (brings back the goal)
                          └─ [Not now]       → discard
```

- The end no longer stops at a per-step review; it goes straight into organizing.
- When organizing finishes: the draft is stored in pending, and the summary page renders the skill body directly.
- Accept → go through the existing `PersonalSkills.approve` to become a trial skill; the Saved page says 「学会了」 (Learned) explicitly.
- Summarization failure, 0 steps, model unavailable: show the reason and 「再试一次 / 重来」 (Try again / Start over) in the summary page position, and do not return to the raw step list.
- Process killed: on restore, `Drafting` organizes again; `Reviewing` shows the existing draft directly. If a draft was saved last time but the session did not get to persist it, recover the existing pending draft by `learned_from=<traceId>` and do not regenerate.

### 2. Step normalization (two layers)

**a) Machine folding (deterministic, core, unit-testable)**

- Steps on launcher packages (desktop Window, icon Click, etc.) do not enter the route.
- Mechanical segments that enter or switch apps via the desktop are folded into a single synthesized step: `start <App>` / `switch to <App>` / `return to <App>`.
- The raw steps that were folded away are kept in a separate plain-language list (e.g. "tapped 小红书 on the desktop"), used only by the summary page to show how many manual steps were folded away, and not written into the skill.
- The set of desktop packages comes from `PackageManager`; when it cannot be identified, fail open and keep the whole segment as-is, without accidental deletion.
- The browser role (the default `ACTION_VIEW` handler) is passed to the model as a hint, to help identify address-bar/search flows.

**b) Semantic rewriting (model)**

The input to the model becomes: goal + app and role + folded route + the manual steps that were folded away. The rules go into the `teach_skill` prompt:

- Opening an app: write "start/open <App>", do not write going back to the desktop, finding the icon or tapping.
- Web page: write "open <link>" or "search <site> for <keyword>", do not write tapping the address bar, typing or tapping search; if the link/keyword was not stored during the demonstration, write it as content provided by the request.
- Only write "tap '<control>'" when a specific control really must be tapped inside the app.
- Search/form: tapping the input box + typing + submitting is merged into one step, "type <content> and submit".
- Keep decision points: entry point, specific buttons, options, criteria, stop conditions; delete waiting, repeated scrolling and pointless back navigation.
- Keep the existing rules: do not invent entry points that were not walked, and do not claim a trial run succeeded.

**c) No model**

The on-device organizer uses the folded route and writes plain sentences following the existing five sections; the summary page notes "no model was used this time, organized by on-device rules", and does not dump the raw step list on the user.

### 3. Summary page

- Top: skill name (display) and when to use it (description), plus one line: "written the way Claire would do it, not a replay of your taps".
- Folded hint card (only when folding happened): "N manual steps folded away", expandable to see the details.
- Body: reuse `SkillReadingView` to render the five sections (entry / steps / common pitfalls / how success is verified / when to stop and hand off to the user), without showing the frontmatter.
- When there is no model, add an explanatory line above the body.
- Actions: accept (primary) / demonstrate again / not now (secondary, needs confirmation).
- After accepting, the Saved page: "learned '<skill name>'" + back to conversation / teach another one.

### 4. Other pages

- Intro page: one-sentence explanation + goal input + start demonstration; the privacy note is folded into a light card (expandable), no longer a full paragraph of text.
- During the demonstration and when interrupted: **keep the existing implementation** (a hint at the top of the screen, pause/end/discard), the layout and copy are not redone.
- Organizing: progress explanation + discard.
- Interrupted: explanation + continue / end / discard, reusing the existing semantics.
- Floating capsule and notification: the copy changes to "tap end when you're done, and I'll organize it automatically", behavior unchanged.

### 5. Data and compatibility

- `Drafting` = organizing, `Reviewing` = summary ready (the draft already exists); the enum values are not renamed.
- `TeachUi` gains `draft: PersonalSkill?`, `folded: List<String>`, `basic: Boolean`, `acceptedName: String?`.
- `TeachSession` gains `acceptedName: String? = null` (defaulted for old-JSON compatibility).
- Remove the runtime public entry points related to per-step editing/generation (`generate`/`annotate`/`remove`) and the corresponding UI; the data model fields stay for compatibility.

### 6. Acceptance

- On a real device, demonstrate "open 小红书 → search for renovation → open the first result → save": the summary's first step is "start 小红书", with no "desktop/icon"; the search is a single direct step; saving is kept; it can be seen in the skill list and accepted.
- On a real device, demonstrate a web operation once (with a model): the summary is written as "open/search", with no address-bar step.
- No model: still get a structured skill, and the page has an "on-device organizing" note.
- 0 steps, summarization failure, process killed during organizing: all have a clear state and can be retried/restarted.
- `:app:testDebugUnitTest` and related androidTest all green; i18n all green.

## Tests

- core unit tests: `TeachRouteTest` (desktop folding, switching mid-way, identification failure fail-open, empty trace, browser role), `TeachDraftTest` (the request contains the direct-reach rules and the folded list, assemble excludes the desktop and keeps caveats).
- UI tests: summary page renders the five sections and the folding hint, accept/demonstrate-again/not-now callbacks, no-model note, learned page, intro page start callback.
- Real-device E2E: the acceptance above; regression `:app:testDebugUnitTest` + related androidTest; do not run the full connectedAndroidTest.

## Decisions (confirmed 2026-11-22)

1. **Input text is not stored**: confirmed. What is entered during a demonstration is usually just an example, and the skill is summarized along the generic route (type what the user wants to look up / open the link the user gives); if some class of skill really needs to reproduce fixed content in the future, discuss it separately.
2. **Accepting is still manual**: confirmed. The summary is shown to the user first, and the skill is only enabled after they tap 「收下这个技能」 (Keep this skill).
3. **No changes during the demonstration**: confirmed (2026-11-22). There is now a hint at the top of the screen during the demonstration, and this mechanism and page are good enough; keep them as-is and only redo the intro, organizing, summary and learned screens.
