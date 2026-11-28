# Settings home information simplification design

## Goal and rationale

The settings home is responsible only for entry navigation and showing current status, with fewer permanent subtitles that restate titles or list details-page content, avoiding text wrapping and inflated line heights. The user has already confirmed the home direction in the local demo.

## Scope

- Only change the entries on the settings home page, plus the "All ready" success status card.
- Remove the static subtitles from the 16 entries on the home page; the success status card no longer shows "Model configured · Phone control available".
- Keep all entries, groups, icons, click callbacks, toggles, status pills, row-end dynamic values and the version number.
- Error status cards continue to show the specific problem, what to do, and a fix button.

## Non-goals

- Do not modify task running, advanced, or other second- and third-level pages.
- Do not change the shared interface or default style of list rows or status cards.
- Do not delete string resources; the same copy may still be used by other pages.
- Do not adjust navigation structure, font sizes, spacing, colors or icons.

## Implementation

Omit the static subtitle at each list-row call site on the home page, rather than hiding subtitles globally. The "All ready" status card passes an empty subtitle only when there are no problems; when there are problems it keeps the existing StatusCopy.banner content. This confines the change to the home page and does not affect other callers.

## Verification

Add Compose tests covering all selectable home entries, asserting that the static subtitles are gone; existing tests continue to verify dynamic status, navigation, toggles and error-fix prompts. Run the `SettingsScreenTest` on-device target test and review the final diff to confirm there are no second- or third-level page changes.
