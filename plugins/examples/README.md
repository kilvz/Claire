# Plugin examples

Both examples use Feishu as the subject and correspond to the plugin system's two shapes:

| Directory | Shape | What it can do now | Depends on |
|---|---|---|---|
| [`com.example.feishu/`](com.example.feishu/) | capability pack | `manifest.json` is a pure skill plugin, installable already at P1a; `manifest.full.json` adds a webhook tool and waits for P1b | P1a (skills); P1b (tools), P3 (secret) |
| [`com.example.feishu-connector/`](com.example.feishu-connector/) | connector (external connector) | only a P4 manifest sample, showing the install guidance/command/status fields; does not run code inside the app | P4, plus merge of the official Feishu connector PR #160 |

## Relationship to the official Feishu connector

The official Feishu connector runs on a computer (`mika-feishu` / `/api/v1`) and is responsible for turning Feishu direct messages into Mika tasks, then sending the results back to Feishu. By spec it is `kind: "connector"` and **does not move code into the app** (corresponding to the P4 stage of the plugin plan).

`com.example.feishu/` is the other path: a capability pack installed in the app that provides Feishu operation skills and can optionally send messages directly through a custom bot webhook. The two can coexist:

- direct messages are brought in by the desktop connector;
- scheduled reminders and group notifications, the "proactively send one message" kind, use the webhook tool in the capability pack;
- reading messages and replying still goes through the Feishu app on the phone or the connector.

## Installing the skill version (current P1a)

1. Generate the zip:

   ```bash
   python plugins/examples/com.example.feishu/build.py
   ```

   The artifact is at `plugins/examples/com.example.feishu/dist/com.example.feishu-skill-0.1.0.zip`.

2. In the app open 设置 › 高级 › 插件 › 安装插件 (Settings › Advanced › Plugins › Install plugin) and choose this zip.
3. After enabling, in the next task `skill_search` can find `com.example.feishu/feishu` and `skill_read` can read it; the body is prefixed with the 「第三方插件技能」 (third-party plugin skill) marker.

## Tool version (waiting for P1b)

`build.py` also generates `com.example.feishu-full-0.2.0.zip`, whose manifest carries `tools`/`permissions`. **P1a currently rejects installation with `only_skills_supported`, which is expected**; the `mcp_com_example_feishu__send` tool only appears after P1b is merged.

## connector sample (waiting for P4)

The `installDocs`/`command`/`statusApi` in `com.example.feishu-connector/manifest.json` come from the planned P4 shape; after PR #160 is merged they must be corrected according to its actual `docs/feishu.md` and `/api/v1`. The P1a/P1b validator currently only accepts `kind: "capability"`, so it cannot be installed either.

## Security conventions

- Neither skills nor tools may change app-global settings, models/Keys, personal skills or other plugins' data.
- webhooks and secrets should not be written into logs or conversations; the example tool reads them from parameters, and P3 will change this to plugin secrets.
- The example only calls the official Feishu custom bot domain; the script does not retry and does not follow arbitrary redirects.
