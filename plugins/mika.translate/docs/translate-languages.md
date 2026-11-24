# How to choose the language

The plugin has no "language switch" layer: **the language to translate into is stated by you in the conversation**, and Mika passes it to `translate` as a parameter.
So any language can be translated, not limited to the ones in the list.

## How to say it

| What you want | What you say | What Mika passes |
|---|---|---|
| Chinese by default | "Translate this page" | `target: 简体中文` |
| Traditional Chinese | "Turn it into Traditional" / "convert to Taiwanese usage" | `target: 繁體中文（台灣用語）` |
| English | "Translate into English" | `target: English` |
| Japanese | "Translate into Japanese" | `target: 日本語` |
| Korean | "Translate this page into Korean" | `target: 한국어` |
| Any other language | "Translate into German/French/Vietnamese/Arabic…" | `target: 德语` and so on; saying it in Chinese or spelling it in the original script both work |

## What "translate this page" translates into by default

The default is **Simplified Chinese**. To change the default, create a new one under [设置 → 环境变量 (Settings → Environment variables)](minis://settings/environments):

| Variable | Effect | Example |
|---|---|---|
| `MIKA_TRANSLATE_TARGET` | Default target language | `繁體中文`, `English`, `日本語` |

Once it is set, "translate this page" follows that language; if a language is explicitly stated in the conversation, that still wins.

## Translating the same page into two languages

Just say it: "translate into Chinese first, then give me an English version" — Mika calls `translate` twice on the same page, both times with the same body.

## What language the source is

You do not need to care. `translate` judges by itself; if you are unsure, you can add "the source is Japanese", and Mika passes it in as `source`, so terms and proper nouns are restored more accurately.

## Bilingual side by side

To get the source and the translation side by side, say "give it to me side by side". Mika lists each source paragraph and its translation in pairs, rather than giving only the translation.
(By default only the translation is given — what the user wants is to understand, not to practise translation.)
