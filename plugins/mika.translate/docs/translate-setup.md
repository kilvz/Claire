# Page translation: how to configure the endpoint

The plugin itself carries no key. Translation goes through **an OpenAI-compatible endpoint you configure yourself**, with three environment variables:

| Variable | Required | Description | Example |
|---|---|---|---|
| `MIKA_TRANSLATE_BASE` | yes | Full chat/completions URL | `https://ark.cn-beijing.volces.com/api/v3/chat/completions` |
| `MIKA_TRANSLATE_KEY` | yes | API Key for that service | `ark-xxxx` |
| `MIKA_TRANSLATE_MODEL` | yes | Model name (must work when called at the URL above) | `doubao-seed-2-0-mini-260428` |

## How to set it in Mika

1. Open 「设置 → 环境变量」 (Settings → Environment variables).
2. Create the three variables above one by one and fill in the values as they are.
3. After creating them **restart Mika once** so that the app process reads the new environment.
4. Go back to the browser, open a foreign-language page, and tell Mika "translate this page"; it first calls `translate` to see whether the channel works, then does the work.

## Usable without configuration

When `translate` finds `MIKA_TRANSLATE_BASE` empty it does not report an error; instead it splits the body into numbered paragraphs at natural boundaries, marks them `mode: segments` and hands them back, and Mika translates them itself with the current main model in the conversation. There are only three differences:

- it uses the model you are already using, and **the page body goes through the conversation** (the model provider you normally chat with);
- long pages are slower;
- quality depends on the current main model.

If you do not want the body to leave the channel you chose, configure the three variables.

## How to write common services

| Service | BASE | MODEL |
|---|---|---|
| Volcano Engine Ark | `https://ark.cn-beijing.volces.com/api/v3/chat/completions` | `doubao-seed-2-0-mini-260428` |
| DeepSeek | `https://api.deepseek.com/chat/completions` | `deepseek-chat` |
| Qwen (compatible mode) | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | `qwen-plus` |
| Zhipu | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | `glm-4-flash` |
| Local Ollama | `http://127.0.0.1:11434/v1/chat/completions` | `qwen2.5` |

## What to look at when it fails

The `error` field in `translate`'s response:

- `api_failed` + `message` carries the HTTP status code and the server's own words — mostly an invalid Key, a wrong model name, or insufficient balance;
- `too_long` — more than 40 segments in one call; switch to `web_read_page`'s `offset` paging and translate in two passes;
- `empty_text` — the body was not passed in;
- `mode: segments` — see "Usable without configuration" above.

## Privacy

Once the variables are configured, **the characters on the page that get translated** are sent to the endpoint you specified. The whole page body does not enter Mika's conversation records, and is not sent to Mika's main model.
