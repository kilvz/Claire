---
name: skill-authoring
display: Write a skill
description: Use when the user wants to create, import or change a skill (a fixed way of doing a class of things): how to write it, how to check for duplicates, how to save it
aliases: ["创建技能", "新建技能", "写个技能", "保存技能", "导入技能", "改技能", "skill"]
terms: ["技能", "做法", "流程", "步骤", "模板", "以后都这样做", "存成技能", "导入", "改写"]
---
## Entry

- Write one when the user explicitly asks to "create a skill", "save this way of doing it", "import this as a skill" or "change a skill". If they are only talking about an approach or asking whether something can be done, answer first; do not save anything yourself.
- A skill records how to do a class of things from now on, not a one-off fact or preference. Something like "I don't eat cilantro" is a memory, not a skill.
- `skill_save` is the only thing that can write a skill. When this tool is not at hand, tell the user directly that it cannot be saved this time; do not say it has already been saved.

Before writing, check whether one already exists:

1. Use `skill_search` to search once with the user's wording for this class of things.
2. If there is already a personal skill for this class of things: read it in full with `skill_read`, change it on that basis, and when saving keep its name and pass the version you read. Do not start another one for the same class of things.
3. If only a built-in skill matches: built-ins cannot be changed. When the user wants a different approach, start a separate one under a different name as your own skill, and tell the user which one will be followed from now on.

## Steps

1. When the user gives only one vague sentence ("help me create a skill for ordering coffee"), first use `ask_user` to clarify the one or two points that most affect the approach: which app or site, at which step to stop, and whether there are fixed requirements. List the key points to save in a few lines for the user to confirm, then save. If the user has already spelled out the approach or pasted complete content, there is no need to ask again.
2. The whole thing is one document: a header plus five sections, with the headings exactly as written, no more than 4000 characters in total.

```
---
name: order-coffee
display: Order coffee
description: Use when the user wants to order a coffee for delivery or pickup
aliases: ["order coffee", "buy a cup of coffee"]
---
## Entry
## Steps
## Common pitfalls
## How success is verified
## When to stop and hand off
```

3. name uses lowercase English letters, digits and hyphens, and makes clear which class of things it is; display is a short name in the user's language.
4. description writes "when to use" in one sentence, and puts the user's likely wording into aliases: whether it gets used later depends entirely on these two places.
5. Entry says where to start (which app, site or search); Steps are numbered short sentences, one action per step, naming buttons and pages, not coordinates.
6. Common pitfalls lists the points where it can go off track and the ways around them; How success is verified says where the result can be seen after finishing; When to stop and hand off lists the moments that need the user to log in, pay, confirm or make a choice.
7. Prefer short over long: what the model already knows does not need writing; write only the approaches and pitfalls specific to this class of things.
8. When the user pastes a skill, note or process written elsewhere, rewrite it into the format above: keep the approach itself, remove things irrelevant here (commands for other software, tools that cannot run on a phone, scripts), and fill any sections with missing content with a sentence or two in keeping with the intent. Do not bring accounts, passwords and the like from the original text into it.
9. Save with `skill_save`: markdown is the whole document, quote copies the user's own words from this task asking you to create or change the skill.
10. Only an ok result counts as saved. Report back according to the result: what the skill is called, whether it is newly created or an existing one was changed, that it can be seen in "Settings › Memory & skills", and that it will be tried first the next time this class of things comes up.

## Common pitfalls

- Things that change each run (names, amounts, addresses, text to send) must be written as `<parameter>` or "the contact the user specifies", never hard-coded.
- Backticks are only for wrapping tool names; do not use backticks for app names or button text.
- Never write passwords, verification codes, card numbers or other people's private information, and never carry accounts, passwords and the like over from pasted text.

## How success is verified

`skill_save` returns ok, and the name in the result is the one being saved; the name and whether it was newly created or changed, as stated in the answer, match the result.

## When to stop and hand off

- If the result is a failure, fix it according to the reason and save once more; if it still fails, truthfully say it was not saved and why; do not say it has been created.
- Renaming and deleting cannot be done here: ask the user to delete the old one in "Settings › Memory & skills"; to change the name, save a copy under the new name and then ask the user to delete the old one.
