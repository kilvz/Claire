## ADDED Requirements

### Requirement: Non-Chinese UIs hide only step reasons that "look like Chinese"

When the UI language is not Chinese, a step reason written by the model is hidden in full and falls back to the localized action title only when it is judged to "look like Chinese" — **containing CJK Han characters (U+4E00–U+9FA5) and no kana (U+3040–U+30FF)**; text containing kana is treated as Japanese and shown as usual. When the UI language is Chinese (Simplified/Traditional), no reason is hidden.

#### Scenario: A Japanese UI shows Japanese reasons
- **WHEN** the UI language is Japanese and a step's reason is 「検索ボタンをタップして連絡先を探す」 (tap the search button to find a contact; containing both Han characters and kana)
- **THEN** that reason is shown in both the step row and the collapsed-state summary, without falling back to the localized title

#### Scenario: A non-Chinese UI still hides Chinese reasons
- **WHEN** the UI language is English and a step's reason is 「点击搜索按钮查找联系人」 (tap the search button to find a contact; containing Han characters and no kana)
- **THEN** that step falls back to the localized action title (e.g. Tap · WeChat) and the original reason is not shown

#### Scenario: A Chinese UI hides no reasons
- **WHEN** the UI language is Simplified or Traditional Chinese and the reason is in any language
- **THEN** the original reason is always shown (existing behavior unchanged)

#### Scenario: Text with only Han characters and no kana is still treated as Chinese
- **WHEN** the UI language is not Chinese and the reason is 「設定確認」 (settings check; Han characters, no kana, indistinguishable from Chinese)
- **THEN** the hide-and-fall-back behavior is kept (a residual limitation recorded faithfully; such reasons are rare)
