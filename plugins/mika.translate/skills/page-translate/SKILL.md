---
name: page-translate
description: Translate the foreign-language web page currently open in the Mika browser into the language the user wants (Simplified Chinese by default; Traditional Chinese, English, Japanese, Korean or any other language is also possible). Use when the user says "translate this page", "what does this page say, translate it", "translate into English", "convert to Traditional", "translate this piece of foreign text"; also for translating only a short passage the user selected.
---

# Page translation

The `mika.translate` plugin is already installed. The tool `plugin_mika_translate_translate` (the first call in this task needs the user to approve once) handles segmentation and translation; the skill only handles the step "read the page → translate → how to present it to the user".

## 1. Read the page first

Try in order and stop at the first success:

1. **The user selected text** → translate only the selected passage (pass this passage directly in step two, do not read the whole page).
2. `web_read_page` (set `maxChars` to `30000`): read the whole body of the current tab. When the body exceeds 30000 characters, use the returned `nextOffset` to continue reading.
3. `web_read_page` unavailable (returns `background_read_unavailable` or `page_not_loaded`) → switch to `run_command` to fetch the HTML and extract the body:
   ```
   curl -sL --max-time 30 "<page URL>" | sed -e 's/<script[^>]*>.*<\/script>//g' -e 's/<[^>]*>/ /g' | tr -s ' \n' ' \n'
   ```
   Pages that need a login state cannot be read this way; tell the user directly "this page needs a login, I cannot translate it in the conversation, please paste a passage over".

Do not use the screenshot + look-at-image route: it is slow and inaccurate.

## 2. Translation

First determine the target language — **follow what the user said; if they did not say, Simplified Chinese**:

- "translate this page" / "translate into Chinese" → `target: 简体中文`
- "turn it into Traditional" / "convert to Taiwanese usage" → `target: 繁體中文`
- "translate into English / Japanese / Korean / German…" → pass the word the user said as-is; `target: English` / `日本語` / `한국어` / `德语` all work
- they want two at once ("one Chinese and one English") → call twice, same body, with `target` swapped

Do not translate language names into English yourself before passing them; pass them as the user said, and the script normalizes them itself (Simplified/Traditional, Japanese/Korean/English all have aliases, other languages are handed to the model as-is). Simplified Chinese is used only when the user did not mention a language and no default is configured.

Call `plugin_mika_translate_translate`:

- `text`: the page body, **pass the whole passage as-is** (no need to split it yourself; the plugin splits at natural boundaries and calls the endpoint once per segment).
- `target`: fill according to the "determine the target language first" rule above; leave it empty only when the user did not say (the script falls back to `MIKA_TRANSLATE_TARGET`, then to Simplified Chinese).
- `source`: fill it in if you can tell (`English`, `日本語`); leave it empty if you cannot.
- `style`: normally leave it empty; when the user explicitly wants a "literal translation" fill `literal`, when they want it to "read smoothly" fill `polish`.

Call it only once per task (twice when the user wants two languages). **Do not** split the body into many calls — segmentation and rate limiting are all done inside the plugin.

The response has two forms:

- `mode: "translated"` → use the `translation` field directly.
- `mode: "segments"` → this means the user has not configured a translation endpoint. In that case **translate it yourself**: join each segment's translation back in `n` order, keep terms and proper nouns consistent, and leave URLs, code and personal names in the source unchanged. After translating, add a sentence at the end of the reply:
  "This time the main model in the conversation translated it. To keep the page body out of the conversation and make it faster and cheaper, just configure the three environment variables according to the `translate_setup` instructions."

When it fails, look at `error`:

- `api_failed` → relay **verbatim** the HTTP status code and the server's own words in `message` to the user (mostly an invalid Key or a wrong model name), then ask whether they want to reconfigure; do not retry the same error over and over.
- `too_long` → the page is too long; switch to `web_read_page`'s `offset` paging, translate about 20,000 characters at a time, and continue reading the next page when done.
- `empty_text` → the body was not read; go back to step one.

## 3. How to present it to the user

What the user wants is "to understand this page", not a report. Give it like this by default:

1. First line: give a **one-sentence conclusion**: what this page is about (judge from the first paragraph of the body and the title; do not make up content that is not in the title).
2. Then give the translation following the source's chapters/paragraphs. **Do not paste a long page (body over 3000 characters) in full**: give "the complete translation of the first 3~5 paragraphs + a one-sentence summary of each later section", and ask at the end "which section would you like me to translate in full?"
3. A short page (within 3000 characters) can be pasted in full, keeping the source's subheadings and lists.
4. Glossary/data/dates: none of the numbers, URLs, code or product model numbers in the source may be changed.

**Do not** do these: do not say things like "I cannot access the web page" (try step one first); do not paste a large chunk of source and then the translation (the user wants Chinese); do not add your own commentary to the translation; do not summarize it into boilerplate such as "this article discusses…" — translate directly.

## 4. Things you can conveniently do after translating

- The user says "save it so I can read it later" → write the translation to a workspace file via `write_file`, and give the file name in the reply.
- The same page translated repeatedly → remind them "this page was just translated" and give the previous result directly.
- The user asks "how can I make it faster / keep it out of the conversation" → call `plugin_mika_translate_translate_setup` and read the three environment variables in it to them.
- The user asks "which languages can it translate" or "how do I change the default language" → call `plugin_mika_translate_translate_languages` and answer according to what it says.
