#!/bin/sh
# Roll back: put this file's most recent backup back (the current content is backed up first, so a rollback can itself be rolled back).
args="$1"
[ -n "$args" ] || args="$MIKA_ARGS_FILE"

field() {
    sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$args" 2>/dev/null | head -1
}

path=$(field path)
if [ -z "$path" ]; then echo "error: path is required"; exit 2; fi

plugindir="$(cd "$(dirname "$0")/.." && pwd)"
backupdir="$plugindir/.runtime/backups"
base=$(basename "$path")

latest=$(ls -1t "$backupdir/$base".*.bak 2>/dev/null | head -1)
if [ -z "$latest" ]; then
    echo "error: no backup for $base in $backupdir"
    exit 3
fi

# Keep a copy of the current content first; the rollback itself is reversible too
if [ -f "$path" ]; then
    stamp=$(date +%Y%m%d-%H%M%S)
    cp "$path" "$backupdir/${base}.${stamp}.before-restore.bak"
fi

cp "$latest" "$path" || { echo "error: restore failed"; exit 4; }

echo "restored: $path"
echo "from: $latest"
echo "bytes: $(wc -c < "$path" 2>/dev/null)"
