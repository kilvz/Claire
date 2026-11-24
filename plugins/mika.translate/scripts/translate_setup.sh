#!/system/bin/sh
# doc — used by the plugin's skills: print a doc file from the plugin directory (the App has no reader besides cat, so everything goes through here).
# Originally this was meant to be cat; the placeholder is kept so that, once there are more docs, they can be fetched by section; for now only whole documents are supported.
set -u
ARGS="${1:-${MIKA_ARGS_FILE:-}}"
NAME=$(sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([a-z_]*\)".*/\1/p' "$ARGS" 2>/dev/null | head -n 1)
[ -n "$NAME" ] || NAME="translate-setup"
DIR="$(dirname "$0")/../docs"
FILE="$DIR/$NAME.md"
if [ ! -f "$FILE" ]; then
  printf '{"ok":false,"error":"not_found","message":"No such document: %s (available: translate-setup)"}\n' "$NAME"
  exit 0
fi
printf '{"ok":true,"name":"%s","text":"' "$NAME"
sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' "$FILE" | sed -e ':a' -e 'N' -e '$!ba' -e 's/\n/\\n/g' -e 's/\r//g' -e 's/\t/\\t/g'
printf '"}\n'
