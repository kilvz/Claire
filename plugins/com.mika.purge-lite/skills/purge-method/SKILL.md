---
name: purge-method
display: Prompt review and edit method
description: The process to follow before and after changing a system prompt/host configuration written by someone else: review first, then back up, change only one place, verify after the change, keep a rollback. Works for any local prompt file.
---

# Prompt review and edit method

Editing a prompt is not writing a prompt. When writing you only think about the effect; when editing, the first thing is **do not break something that is still in use**.
This workflow comes from a host patching tool's approach, with the attack part removed and only the engineering part kept.

## Entry

- The user says "look at this prompt", "help me change its settings" or "why does it always reply like this".
- The user mentions a directory, a `.md`/`.json` configuration or an app's prompt file.
- Before you yourself are about to touch any file outside the plugin directory.

## Steps

1. **Review**: use `scan` to scan the directory and get the candidate files and hit counts; then use `read_part` to read only the first few dozen lines.
   First judge whether this is a "system prompt", "user data" or "program code" — the three are edited in completely different ways.
2. **State clearly what to change**: write in one sentence "it is like this now → change it to this → why".
   Say on the spot what cannot be changed (for example it is inside an APK or on a read-only partition); do not force it.
3. **Back up**: use `backup` to copy the original file into the plugin's own backup directory first, and note the returned path.
   Without a backup, do not proceed to step 4.
4. **Change only one place**: touch one passage at a time, use `patch` to do a **literal** replacement (not a regex), and always fill in `expect`:
   you expect this to appear only once here. `patch` backs up by itself first; when the match count is not equal to `expect` it writes nothing, and that is deliberate.
5. **Verify**: say "how to tell that the change is correct". Run what can be run; for what cannot, at least give the comparison: what was read before the change and what is read after it.
6. **Keep a rollback**: tell the user the backup path. To go back use `restore` (it also keeps a copy of the current content, so the rollback itself can be undone).

## Common pitfalls

- **Taking "not found" to mean "does not exist"**: scanning has suffix and keyword limits, so not finding it only means it was not found.
- **Changing too much at once**: when three changes break something, you do not know which one broke it.
- **Acting without a backup**: this is the only irreversible mistake; everything else can be redone. `patch` backs up automatically, but do not bypass it with hand-written sed.
- **Writing `find` as a regex**: `patch` is a literal match, so `.*` looks for those two literal characters, not a wildcard.
- **Leaving `expect` empty**: a word appears 20 times in the file and you only want to change 1 place; leave it empty and all of them change.
- **Treating program code as a prompt**: strings in code are often compiled into the artifact, so editing the source does not take effect.
- **Overstepping the scope**: the user asked about one file, so do not go and change the whole directory.

## How success is verified

- It is clear which file and which passage were changed, and what each looked like before and after.
- The backup path has been written out, and the user can restore from it.
- The change has been verified: the number of matches printed by `patch` is as expected, and the file has been read back with `read_part` afterwards.
- The user has the backup path and knows how to use `restore`.
- No file the user did not ask you to touch has been touched.

## When to stop and hand off

- The file is outside the readable range (inside an APK, on a read-only partition, needs root).
- The place to change can be understood in several ways, and choosing wrong is costly.
- What the user wants is an effect such as "make it not be restricted", and that is beyond what editing one text file can do — say so directly, do not go around it.
- The file to touch is the user's own data (chat history, photos, accounts), not configuration.
