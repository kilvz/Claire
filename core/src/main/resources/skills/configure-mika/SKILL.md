---
name: configure-mika
display: Change Claire's own settings
description: Use when the user asks Claire to change her own settings, to view and change them; models, keys and deleting data are not included
aliases: ["设置你自己", "改你的设置", "调快一点", "别静音", "换个外观"]
terms: ["设置", "配置", "快一点", "慢一点", "静音", "外观", "深色", "浅色", "胶囊"]
---
## Entry

First call `settings_list`. It lists every item that can be changed at this moment: the key, the human-readable name, the current value, and (when there are only a few choices) all the available values.

**Use only the keys and values given in the list**; do not guess a word yourself. When what the user says does not match what you see (for example, the user says "don't mute", while the list calls it "mute_apps"), pick that item by name and read its `label` back to the user to confirm that it is this one.

## Steps

When the user has clearly said what to change, change it directly with `settings_set`, and tell the user the current value afterwards. When the user's words are vague ("make it a bit faster"), first say clearly which item you intend to change and to what, then change it:

> I've changed the operation pace to 「快」 (Fast); it takes effect starting with the next task.

Most changes take effect starting with the next task; appearance (dark/light) changes on the spot. Say a word about this after changing, so that the user does not think it has not taken effect.

## Common pitfalls

- **Do not change things the user did not mention.** When changing several items at once, state each one.

## How success is verified

`settings_list` returns the item with the new value, and the value reported to the user matches it; appearance (dark/light) has changed on the spot, and other changes take effect starting with the next task.

## When to stop and hand off

- **Models, API key, sign-in**: not in the list. When the user wants these, tell them to go to Settings › Model; you cannot do this step.
- **Deleting data** (clearing the workspace, resetting the runtime environment, deleting conversations): also not in the list, and likewise ask the user to do it themselves.
- **Container networking and proxy**: not in the list. These two items let data leave the phone, so the user has to turn them on or fill them in under Settings › Files & Compute themselves; do not change them on the user's behalf.

These kinds are deliberate: the first kind is equivalent to letting the model replace its own brain and touch the user's money; the second kind is irreversible; and the networking line has to be drawn by the user.
