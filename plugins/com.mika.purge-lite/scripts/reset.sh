#!/bin/sh
# One-shot restore: put persona.conf back to the default (all comments, i.e. the prompt is left unmodified).
# The previous content is backed up to .runtime/backups/ first, so it can be brought back at any time.
args="$1"
[ -n "$args" ] || args="$MIKA_ARGS_FILE"

dir="$(cd "$(dirname "$0")/.." && pwd)"
cfg="$dir/persona.conf"
backupdir="$dir/.runtime/backups"
mkdir -p "$backupdir" || { echo "error: cannot create $backupdir"; exit 2; }

if [ -f "$cfg" ]; then
    stamp=$(date +%Y%m%d-%H%M%S)
    bak="$backupdir/persona.conf.$stamp.bak"
    cp "$cfg" "$bak" || { echo "error: backup failed, nothing changed"; exit 3; }
    echo "backup: $bak"
fi

cat > "$cfg" <<'EOS'
# Prompt-rewrite configuration. Changes take effect on the next task.
# Wrapped around the whole prompt: prefix= (at the front), suffix= (at the end)
# One-by-one replacement: replace=old text=>new text (literal match, not a regex)
# A [model-name fragment] at the start of the line applies only to that model, e.g. [claude]prefix=…
#
# Example: lead with the conclusion
# prefix=Give the conclusion first, then the reasoning. If you are not sure, say so plainly; do not hedge.
#
# Example: replace the identity sentence (left is the original Mika text, right is the replacement)
# replace=You are Mika, the user's personal assistant on their phone.=>You are Mika.
#
# Example: add a requirement for one model only
# [gpt]suffix=Keep answers to three sentences.
EOS
echo "restored: $cfg"
echo "rules now: $(grep -cE '^(\[[^]]*\])?(prefix|suffix|replace)=' "$cfg" 2>/dev/null || echo 0)"
