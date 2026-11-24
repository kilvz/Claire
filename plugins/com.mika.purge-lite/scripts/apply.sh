#!/bin/sh
# One-shot apply: check whether persona.conf is usable, and do a trial run on the spot (read-only, no files touched).
# Args: nothing required; optional {"model":"model name"} selects which model to trial-run against.
args="$1"
[ -n "$args" ] || args="$MIKA_ARGS_FILE"

field() {
    sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$args" 2>/dev/null | head -1
}

dir="$(cd "$(dirname "$0")/.." && pwd)"
cfg="$dir/persona.conf"
hook="$dir/scripts/rewrite_prompt.sh"

if [ ! -f "$cfg" ]; then
    echo "error: persona.conf is missing: $cfg"
    exit 2
fi
if [ ! -f "$hook" ]; then
    echo "error: rewrite_prompt.sh is missing: $hook"
    exit 3
fi

model=$(field model)
[ -n "$model" ] || model="${MIKA_MODEL:-}"

rules=$(grep -cE '^(\[[^]]*\])?(prefix|suffix|replace)=' "$cfg" 2>/dev/null)
[ -n "$rules" ] || rules=0

echo "Config: $cfg"
echo "Rules: $rules"
if [ "$rules" -eq 0 ]; then
    echo "Note: there are no active rules now; the prompt is sent through unchanged."
fi

echo "--- Rules ---"
grep -nE '^(\[[^]]*\])?(prefix|suffix|replace)=' "$cfg" 2>/dev/null | sed 's/^/  /' || true

sample="You are Mika, the user's personal assistant on their phone.
Rule: check the current state before acting."

echo "--- Trial run (model: ${model:-not specified}) ---"
before=$(printf '%s' "$sample" | wc -c)
after_json=$(printf '%s' "$sample" | MIKA_MODEL="$model" sh "$hook" | wc -c)
echo "Input $before bytes -> output $after_json bytes"

printf '%s' "$sample" | MIKA_MODEL="$model" sh "$hook" | head -6 | sed 's/^/  /'

echo "--- Result ---"
if [ "$rules" -gt 0 ]; then
    echo "The configuration is usable and already active: from the next task on, the prompt goes through this script first."
else
    echo "The configuration is usable but has no rules, so nothing is changed."
fi
