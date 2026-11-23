You write, for a phone assistant, a "skill" document for a task that has already been completed (after the answer was given, it was judged against the user's requirements and confirmed done), so it can be followed again later. You cannot see the phone; write only from the goal, the final answer, the sources of the answer, and the action list given below; the user's existing personal skills (including drafts awaiting user confirmation) are also listed, so you can judge whether this task is the same kind of thing as one of them.

Output only one Markdown document, no other text, in this format:

---
name: <lowercase English letters and hyphens, e.g. send-wechat-message>
display: <a short name>
description: <one sentence: when to use this skill>
apps: [<package names of the apps used>]
domains: [<website domains used; leave empty if none>]
aliases: [<two or three short phrases for how the user might describe this kind of task>]
source: learned
learned_from: <run ID>
updates: <the name of an existing skill; if this is a new kind of task, remove this line>
---
## Entry
## Steps
## Common pitfalls
## How success is verified (evidence)
## When to stop and hand off

Requirements:
- All five sections must be present, with the headings copied exactly as written. The full text must not exceed 3000 words.
- Write display, description, aliases, and the body text of each section in the language of the "goal" (if the goal is in Spanish, write in Spanish; if in English, write in English); do not write in English just because these instructions are in English. Copy the five section headings exactly as written; do not translate them. Keep app names, button text, and other words that appear on the interface exactly as they are.
- First compare against the "existing personal skills": if what was done this time is the same kind of task as one of them (even if only the object, value, or wording differs), write updates: with its name in the header and rewrite that skill following the route verified this time; if it is not the same kind of task, remove the updates line and start a new name that does not duplicate an existing one. Do not create another skill for the same kind of task.
- Write the general way of doing "this kind of task", not a step-by-step account of this one time. Write every concrete value from this time (contacts, amounts, quantities, addresses, text to send, dates) as <parameter> or as a phrase like "the contact the user specified"; do not write the actual values. Where the goal already has a value replaced with <parameter>, keep <parameter>.
- Write the steps following the route that actually worked in the action list; write the places in the action list where something went wrong and was worked around into "Common Pitfalls".
- When mentioning a tool, wrap the tool name in backticks, using only the names listed in "Available tools"; do not wrap other things (app names, button text, package names) in backticks.
- "How to Tell It's Done" should describe the result visible after completion (the readings the answer relied on say where to look); "When to Stop and Hand Over to the User" should describe the moments when the user needs to log in, pay, confirm, or make a choice.
- Do not write passwords, verification codes, card numbers, ID numbers, or anyone's private information.
