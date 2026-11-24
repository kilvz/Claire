#!/system/bin/sh
# translate_languages — print the "how to choose a language" documentation.
set -u
ARGS="${1:-${MIKA_ARGS_FILE:-}}"
NAME=$(sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([a-z-]*\)".*/\1/p' "$ARGS" 2>/dev/null | head -n 1)
[ -n "$NAME" ] || NAME="translate-languages"
DIR="$(dirname "$0")/../docs"
FILE="$DIR/$NAME.md"
if [ ! -f "$FILE" ]; then
  printf '{"ok":false,"error":"not_found","message":"No such document: %s (available: translate-languages)"}\n' "$NAME"
  exit 0
fi
printf '{"ok":true,"name":"%s","text":"' "$NAME"
sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' "$FILE" | awk 'BEGIN{first=1} { if (!first) printf "\\n"; first=0; gsub(/\t/, "\\t"); printf "%s", $0 }'
printf '"}\n'
