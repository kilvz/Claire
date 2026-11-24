#!/bin/sh
# Public-info ledger: it does exactly two things — record an entry (source required) and list entries.
# Args: {"action":"add","text":"...","source":"https://...","tag":"optional"}
#       {"action":"list","limit":20}
args="$1"
[ -n "$args" ] || args="$MIKA_ARGS_FILE"

# Extract a JSON string field. Avoid sed's \| alternation: toybox sed does not support it,
# so on a real device the value would never be found.
field() {
    raw=$(awk -v key="$1" '
    BEGIN { pat = "\"" key "\"[[:space:]]*:[[:space:]]*\"" }
    {
        if (match($0, pat) == 0) next
        rest = substr($0, RSTART + RLENGTH)
        out = ""
        for (i = 1; i <= length(rest); i++) {
            c = substr(rest, i, 1)
            if (c == "\\") { out = out "\\" substr(rest, i + 1, 1); i++; continue }
            if (c == "\"") break
            out = out c
        }
        printf "%s", out
        exit
    }' "$args")
    printf '%b' "$raw" | sed 's/\\"/"/g'
}
num() {
    sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p" "$args" 2>/dev/null | head -1
}

dir="$(cd "$(dirname "$0")/.." && pwd)"
ledger="$dir/.runtime/ledger.md"
mkdir -p "$dir/.runtime" || { echo "error: cannot create runtime dir"; exit 2; }

action=$(field action)
[ -n "$action" ] || action=add
limit=$(num limit)
[ -n "$limit" ] || limit=20
case "$limit" in *[!0-9]*) limit=20 ;; esac
[ "$limit" -ge 1 ] 2>/dev/null || limit=20
[ "$limit" -le 200 ] 2>/dev/null || limit=200

if [ "$action" = "list" ]; then
    if [ ! -f "$ledger" ]; then
        echo "The ledger is still empty: $ledger"
        exit 0
    fi
    total=$(grep -c '^- ' "$ledger" 2>/dev/null || echo 0)
    echo "Ledger: $ledger"
    echo "Entries: $total"
    echo "---"
    grep '^- ' "$ledger" 2>/dev/null | tail -n "$limit"
    exit 0
fi

text=$(field text)
source=$(field source)
tag=$(field tag)

if [ -z "$text" ]; then
    echo "error: text is required"
    exit 3
fi
# Entries without a source are always rejected: this is the one rule the whole flow cannot bend on
case "$source" in
    http://*|https://*) ;;
    *) echo "error: source is required and must be an http(s) URL — leads without a source are not accepted"; exit 4 ;;
esac

stamp=$(date '+%Y-%m-%d %H:%M')
{
    printf -- '- %s | %s | source: %s' "$stamp" "$text" "$source"
    [ -n "$tag" ] && printf ' | tag: %s' "$tag"
    printf '\n'
} >> "$ledger"

echo "recorded: $ledger"
echo "entry: $text"
echo "source: $source"
echo "entries now: $(grep -c '^- ' "$ledger" 2>/dev/null || echo 0)"
