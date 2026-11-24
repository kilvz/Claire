## Context

`StepRow.shouldHideRawReason` (app/src/main/java/com/wangzan101/mika/chat/ui/ChatViewModel.kt) is a pure-function check introduced by #765 (v0.1.50): non-Chinese UI + reason containing Han characters → hide. Its callers `StepRow.text` and the collapsed-state last-line summary both depend on it. The existing unit test `StepRowLanguageTest` (app/src/test/.../ui/StepRowLanguageTest.kt) injects the `isChineseLocale` parameter to cover scenarios such as hiding Chinese on an en UI and not hiding on a zh UI.

## Goals / Non-Goals

- Goal: on a Japanese UI, Japanese reasons written as instructed are no longer misjudged as Chinese and hidden; the original purpose of hiding Chinese reasons that break the instruction on non-Chinese UIs stays unchanged.
- Non-Goal: no "per-UI-language whitelist" (e.g. letting everything through on a ja UI); no translation; do not change `languageDirective` or the prompt side.

## Decisions

- **Tighten the check to "contains Han characters and no kana"** (kana meaning the single range `\u3040`–`\u30ff`, covering both hiragana and katakana), instead of special-casing by UI language:
  - kana is the only reliable character-level signal separating Japanese from Chinese, independent of language;
  - special-casing a ja UI to let things through would also let Japanese reasons that break the instruction through on an en UI, making the rule more convoluted while still not solving the real question of "what language is this text".
- The residual limitation (a Japanese string with only Han characters and no kana is still hidden) is accepted and recorded faithfully in the proposal/spec: it cannot be distinguished at the character level, and the fallback title is itself localized, so the information loss is limited.
- Also fix the implicit global dependency in the default parameter? **No.** `isChineseLocale: Boolean = uiLocale().language == "zh"` depends on `UiText.source` (already stubbed in unit tests); keep it as is for the smallest change surface.

## Risks / Trade-offs

- On an en UI the model breaks the instruction and writes Japanese (previously hidden, shown after the fix): showing a foreign language loses less information than dropping the model's explanation, and that scenario is far rarer than the main ja-UI path.
- A Chinese reason quoting a single kana character (e.g. 「ナ」 inside a Chinese sentence) is judged Japanese and shown: natural Chinese text contains no kana, so this is theoretical only.

## Migration Plan

No state migration; the behavior takes effect with the next release. Historical steps already on display are unaffected (display is computed at read time).
