#!/bin/sh
# Doing surgery: literal string replacement. Automatic backup, match-count check, atomic write; nothing is written if counts disagree.
# Args live in a JSON file ($1 or $MIKA_ARGS_FILE); fields are pulled out with sed only, no jq dependency.
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

path=$(field path)
find=$(field find)
repl=$(field replace)
expect=$(num expect)
# With no expect given, assume 1 match: better to refuse than to replace every same-named fragment at once
[ -n "$expect" ] || expect=1

command -v awk >/dev/null 2>&1 || { echo "error: awk is not available on this device, so patch cannot run"; exit 7; }

if [ -z "$path" ]; then echo "error: path is required"; exit 2; fi
if [ -z "$find" ]; then echo "error: find is required"; exit 2; fi
if [ ! -f "$path" ]; then echo "error: not a file: $path"; exit 3; fi

size=$(wc -c < "$path" 2>/dev/null)
if [ "$size" -gt 2097152 ] 2>/dev/null; then
    echo "error: file is larger than 2MB ($size bytes), refusing to patch"
    exit 6
fi

plugindir="$(cd "$(dirname "$0")/.." && pwd)"
backupdir="$plugindir/.runtime/backups"
mkdir -p "$backupdir" || { echo "error: cannot create $backupdir"; exit 7; }

tmp="$plugindir/.runtime/patch.$$"
cnt="$plugindir/.runtime/patch.count.$$"
trap 'rm -f "$tmp" "$cnt"' EXIT

# Pass strings via ENVIRON to bypass awk -v backslash escaping; index() is a literal match, not a regex
FIND="$find" REPL="$repl" CNTF="$cnt" awk '
BEGIN { find = ENVIRON["FIND"]; repl = ENVIRON["REPL"]; n = 0 }
{
    line = $0; out = ""; rest = line
    while ((i = index(rest, find)) > 0) {
        out = out substr(rest, 1, i - 1) repl
        rest = substr(rest, i + length(find))
        n++
    }
    print (out rest)
}
END { printf "%d\n", n > ENVIRON["CNTF"]; close(ENVIRON["CNTF"]) }
' "$path" > "$tmp" || { echo "error: awk failed"; exit 8; }

hits=$(cat "$cnt" 2>/dev/null)
[ -n "$hits" ] || hits=0

echo "file: $path"
echo "matches: $hits"

if [ "$hits" -eq 0 ]; then
    echo "refused: the text was not found, nothing was written"
    exit 4
fi
if [ "$hits" -ne "$expect" ]; then
    echo "refused: expected $expect match(es), found $hits — nothing was written"
    exit 5
fi

stamp=$(date +%Y%m%d-%H%M%S)
base=$(basename "$path")
bak="$backupdir/${base}.${stamp}.bak"
cp "$path" "$bak" || { echo "error: backup failed, nothing was written"; exit 9; }

mv "$tmp" "$path" || { echo "error: write failed; original is at $bak"; exit 10; }

newsize=$(wc -c < "$path" 2>/dev/null)
echo "backup: $bak"
echo "bytes: $size -> $newsize"

# Read the file back right after writing, to prove this is exactly what changed
echo "--- after ---"
grep -n -F "$repl" "$path" 2>/dev/null | head -5

echo "restore with: restore  {\"path\": \"$path\"}"
