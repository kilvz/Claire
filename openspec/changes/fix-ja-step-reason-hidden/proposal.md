## Why

In v0.1.50, #765 (f010acab) added two mutually canceling things in the same commit:

1. `SystemPrompt.languageDirective("ja")` instructs the model "all thinking, progress notes, tool reasons… in Japanese" — so on a Japanese UI the step reason is written in Japanese as instructed;
2. `ChatViewModel.StepRow.shouldHideRawReason` (app/src/main/java/com/wangzan101/mika/chat/ui/ChatViewModel.kt:142), meant to avoid awkwardness "when the model breaks the instruction and writes Chinese on a non-Chinese UI", hides the **entire** reason containing any U+4E00–9FA5 Han character and falls back to the localized action title.

The check has only the Han range as a single signal, while **almost every sentence of written Japanese contains Han characters** (search, settings, contacts… only kana distinguishes Japanese from Chinese). Result: users on a Japanese UI almost never see the model's step explanations — the better the model follows the instruction, the more information is lost. Languages other than Japanese are unaffected (Korean Hangul and Western scripts are outside the Han range; a Chinese UI always shows them).

Both display entry points for the step reason go through `StepRow.text` (ChatViewModel.kt:135): the step row itself and the collapsed-state last-line summary (`:254`), so hiding consumes both at once.

## What Changes

- The "looks like Chinese" check in `shouldHideRawReason` tightens from "contains Han characters" to "**contains Han characters and no kana**" (hiragana U+3040–U+309F, katakana U+30A0–U+30FF): Japanese sentences necessarily mix in kana (particles/inflection), so they are no longer misjudged.
- Behavior matrix (after the fix):

| UI language | Reason language | Before | After |
|---|---|---|---|
| ja | Japanese (following the instruction) | hidden ✗ | shown ✓ |
| en and other non-Chinese | Chinese (breaking the instruction) | hidden ✓ | hidden ✓ (original purpose kept) |
| en and other non-Chinese | Japanese (breaking the instruction) | hidden | shown (rare corner; showing loses less than dropping) |
| zh (Simplified/Traditional) | any | shown ✓ | shown ✓ (unchanged) |

- The residual limitation is recorded faithfully: a short Japanese string with only Han characters and no kana (e.g. "settings check") still cannot be distinguished from Chinese and stays hidden — such reasons are rare and the fallback title is itself localized.

## Capabilities

### New Capabilities

- `step-reason-language`: on non-Chinese UIs, a step reason is hidden and falls back to the localized title only when it "looks like Chinese (contains Han characters and no kana)"; text containing kana (Japanese) is no longer misjudged as Chinese.

### Modified Capabilities

(None — `StepRow`'s hide-and-fall-back behavior was newly introduced by #765 with v0.1.50, and there is no earlier existing spec.)
