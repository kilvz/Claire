## ADDED Requirements

### Requirement: Per-app operating pace overrides

Users can specify an operating pace level (Fast / Standard / Human-like) for an individual app in settings. While a task runs, each app's effective level SHALL be "that app's override value, or the global level when there is no override", resolved once at task start (consistent with the existing semantics of the global level's "takes effect from the next task").

#### Scenario: Override wins over global

- **WHEN** the global operating pace is "Standard" and the user overrides an app to "Human-like"
- **THEN** that app runs in the task with Human-like dwell and typing pace, while other apps without overrides still run as Standard

#### Scenario: Deleting an override returns to following the global

- **WHEN** the user deletes an app's override and the global level is "Fast"
- **THEN** that app runs as Fast in subsequent tasks, with no separate pace any longer

### Requirement: Built-in careful list as the list's initial content

When the per-app list is first initialized, the built-in apps marked `pace: careful` in app knowledge SHALL be preset to "Human-like". After initialization, pace determination for phone operations SHALL only look at the per-app list and the global level, and no longer query app knowledge's pace at runtime; the pace determination for site (web) knowledge is unaffected.

#### Scenario: First use presets the built-in list

- **WHEN** the per-app list has never been initialized (storage is empty)
- **THEN** after initialization the built-in careful apps (WeChat, Xiaohongshu, Douyin and 8 in total) all appear in the list at the Human-like level, with behavior identical to before the preset

#### Scenario: Deleting a preset item is not pulled back by knowledge

- **WHEN** the user deletes the override for one preset careful app and the global setting is "Standard"
- **THEN** that app runs as the global Standard, even though app knowledge still marks it careful

### Requirement: Per-app semantics of the Fast level

When an app's effective level is "Fast", that app SHALL not dwell between actions, SHALL type in one shot, and SHALL have no screen-count cap; at Human-like it SHALL use careful dwell (longer, reading by character count, slower typing) and set a screen-count cap; Standard uses normal dwell. The global Fast setting no longer causes apps overridden to Human-like to lose their slowdown.

#### Scenario: An app overridden to Human-like still slows down under global Fast

- **WHEN** the global operating pace is "Fast" and an app is overridden to "Human-like"
- **THEN** that app runs with careful dwell and typing pace, while the other apps do not dwell

#### Scenario: The screen-count cap only follows an override to Human-like

- **WHEN** the global setting is "Human-like" and there are no overrides
- **THEN** all apps use careful dwell and typing, but only apps overridden to Human-like count screens and are bound by the cap (consistent with the current state: only apps that limit browsing pace are capped)

### Requirement: Per-app settings UI

Under Settings › During work › Operating pace, a "Per-app settings" entry SHALL be provided: the list shows each overridden app's current level, and supports adding from installed apps (with search), changing the level, and deleting overrides; apps not in the list are described as following the global setting.

#### Scenario: Changing the level is written immediately

- **WHEN** the user changes an app from "Human-like" to "Fast" in the per-app list and confirms
- **THEN** the list updates to show Fast, and that app runs as Fast starting from the next task

### Requirement: Eval hooks remain deterministic

When the evaluation/acceptance hook sets an operating pace, per-app overrides SHALL be ignored and all apps SHALL run at the hook's level.

#### Scenario: Evaluation overrides the per-app list

- **WHEN** the eval hook sets the operating pace to "Fast" and the user's per-app list has a Human-like override
- **THEN** all apps run as Fast in that evaluation run
