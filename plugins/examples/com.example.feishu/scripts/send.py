#!/usr/bin/env python3
"""Send a text message through a Feishu custom-bot webhook.

Arguments come from the JSON file MIKA_PLUGIN_ARGS points to (the P1b convention); without that variable they are read from stdin.
The last line is JSON, which Mika uses as the tool result; on failure the exit code is non-zero.
"""

import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request

MAX_TEXT = 20000
WEBHOOK_PREFIX = "https://open.feishu.cn/open-apis/bot/v2/hook/"


def fail(message, code=2):
    print(json.dumps({"ok": False, "error": message}, ensure_ascii=False))
    sys.exit(code)


def load_args():
    path = os.environ.get("MIKA_PLUGIN_ARGS", "").strip()
    try:
        if path:
            with open(path, encoding="utf-8") as f:
                return json.load(f)
        return json.load(sys.stdin)
    except (OSError, ValueError) as e:
        fail("could not read arguments: {}".format(type(e).__name__))


def main():
    args = load_args()
    if not isinstance(args, dict):
        fail("arguments must be a JSON object")
    webhook = str(args.get("webhook", "")).strip()
    text = str(args.get("text", ""))
    secret = str(args.get("secret", "")).strip()
    if not webhook.startswith(WEBHOOK_PREFIX):
        fail("webhook must be a Feishu custom bot URL")
    if not text.strip():
        fail("text is empty")
    if len(text) > MAX_TEXT:
        fail("text is too long")

    payload = {"msg_type": "text", "content": {"text": text}}
    if secret:
        timestamp = str(int(time.time()))
        string_to_sign = "{}\n{}".format(timestamp, secret)
        sign = base64.b64encode(
            hmac.new(string_to_sign.encode("utf-8"), digestmod=hashlib.sha256).digest()
        ).decode("utf-8")
        payload["timestamp"] = timestamp
        payload["sign"] = sign

    request = urllib.request.Request(
        webhook,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            body = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        if secret:
            body = body.replace(secret, "***")
        fail("HTTP {}: {}".format(e.code, body[:500]), 1)
    except Exception as e:  # A network failure is returned as an ordinary error, without retrying
        fail("request failed: {}".format(type(e).__name__), 1)

    try:
        data = json.loads(body)
    except ValueError:
        fail("unexpected response: {}".format(body[:500]), 1)
    code = data.get("code", data.get("StatusCode"))
    if code not in (0, None):
        fail(
            "Feishu error {}: {}".format(
                code, data.get("msg") or data.get("StatusMessage", "")
            ),
            1,
        )
    print(json.dumps({"ok": True, "code": code, "msg": data.get("msg", "")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
