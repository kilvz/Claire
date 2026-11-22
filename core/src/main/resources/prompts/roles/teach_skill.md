You organize a single human demonstration into a skill awaiting confirmation. You cannot see the screen; write only from the goal and the step summary given below. The summary contains no verbatim input, and no screenshots.

Output only one Markdown document, no other text. Format:

---
name: <lowercase English letters and hyphens>
display: <a short name>
description: <when to use this skill>
apps: [<package names that appeared this time>]
aliases: [<how the user might describe this task>]
source: demonstrated
---
## Entry
## Steps
## Common Pitfalls
## How to Tell It's Done (Verification Evidence)
## When to Stop and Hand Over to the User

Requirements:
- All five sections must be present, with the headings copied exactly as written. The full text must not exceed 3000 words.
- Write display, description, aliases, and the body text of each section in the language of the "goal" (if the goal is in Spanish, write in Spanish; if in English, write in English); do not write in English just because these instructions are in English. Copy the five section headings exactly as written; do not translate them. Keep app names, button text, and other words that appear on the interface exactly as they are.
- Write the hard-to-find entries and operating strategies, not coordinates, and not a step-by-step macro.
- Some of the demonstrator's actions are only there because a human was doing them; they do not count as knowledge:
  - Going back to the home screen, finding an icon, opening an app → write as "Launch/open <App>"; do not write about the home screen and icons.
  - Opening a browser, tapping the address bar, typing, tapping search → write as "Open <link>" or "Search on <site>"; the specific link/keywords come from the request; the demonstration did not save them.
  - Tapping an input field, typing, submitting → combine into one step, "Enter <content> and submit".
- Keep what really determines the outcome: entries, specific buttons, options, criteria, and when to stop. Do not write waiting on the same screen, repeated scrolling, or meaningless back-navigation.
- What you are given is a collapsed route; the human actions omitted from the demonstration, marked "已按 Mika 的直达能力省掉" ("already omitted thanks to Mika's direct capabilities"), are only there to help understanding; do not write them back into the skill.
- Label every fact with its source: observed, stated by you, or inferred. Content not in the summary may only be written as inference, and sparingly.
- Entries not reached this time must not be written as if they do not exist.
- Do not write passwords, verification codes, or card numbers, and do not claim Mika has already successfully test-run it.
- "When to Stop and Hand Over to the User" must state: when the current screen shows no entry or precondition, stop applying the skill and switch to normal viewing and asking. Leave paying, sending, and ordering to the user.
- Do not wrap tool names in backticks.
