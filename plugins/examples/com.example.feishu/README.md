# Feishu messages (capability pack example)

```
manifest.json         P1a: skills only, can be installed directly
manifest.full.json    P1b: adds the send tool (webhook), currently rejected with only_skills_supported
skills/feishu/SKILL.md
scripts/send.py       tool script: POST to the Feishu custom bot webhook
build.py              builds the zip (Python standard library only)
```

## Build

```bash
python plugins/examples/com.example.feishu/build.py
# skill-only build:
python plugins/examples/com.example.feishu/build.py --only skill
```

Zip entries always use `/` and contain only the manifest, `skills/` and (for the tool version) `scripts/`.

## Behaviour after installation

### P1a (skills)

- The plugin skill name is `com.example.feishu/feishu`, and `skill_read` can take `plugin:com.example.feishu/feishu`.
- After the plugin is disabled it cannot be found in the next task; uninstalling deletes the skill.

### P1b (tools)

- `send` in the manifest becomes `mcp_com_example_feishu__send`, and its risk goes through the existing approval as `external`.
- Script convention: P1b writes the argument JSON to the temporary file pointed to by `MIKA_PLUGIN_ARGS`, and the script's last line outputs JSON.
- Without the `network` permission it cannot go online; with the permission it goes through Mika's global proxy.

### P3 (secret)

- Proper usage should put the webhook/secret into plugin settings (`secrets.bin`), with the script reading it from `MIKA_PLUGIN_SECRETS`, instead of putting the webhook in the tool parameters every time. The current parameter form is only a transition for the P1b stage.

## Manually verifying the tool script

```bash
echo '{"webhook":"https://open.feishu.cn/open-apis/bot/v2/hook/xxx","text":"Test message from Mika"}' \
  | python plugins/examples/com.example.feishu/scripts/send.py
```

A real webhook actually sends a message; confirm the target and the content first. The script does not print the webhook/secret, and on failure the last line is `{"ok": false, "error": ...}`.
