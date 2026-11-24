#!/system/bin/sh
# translate — call the user's own OpenAI-compatible endpoint to translate text into the target language.
#
# Runs inside Mika's App process (no python3), so it uses only POSIX sh plus a tiny awk JSON parser.
# Arguments come from $MIKA_ARGS_FILE (JSON); the script also accepts $1 as the argument file path.
# Credentials come from environment variables: MIKA_TRANSLATE_BASE / MIKA_TRANSLATE_KEY / MIKA_TRANSLATE_MODEL
# (Mika plugin scripts inherit the App process environment, so once these three are set in the system, the text never passes through the conversation).

set -u

ARGS="${1:-${MIKA_ARGS_FILE:-}}"
if [ -z "$ARGS" ] || [ ! -f "$ARGS" ]; then
  printf '{"ok":false,"error":"bad_args","message":"no argument file; expected MIKA_ARGS_FILE"}\n'
  exit 0
fi

# ---------- Minimal JSON: get a top-level string value ----------
# json_str <file> <key>: return the decoded string (\\n \\t \\" \\uXXXX are basically covered), empty when absent.
json_str() {
  awk -v key="$2" '
    { buf = buf $0 "\n" }
    END {
      pat = "\"" key "\"[ \t\r\n]*:"
      if (!match(buf, pat)) exit 0
      i = RSTART + RLENGTH
      n = length(buf)
      # skip whitespace
      while (i <= n) { c = substr(buf, i, 1); if (c != " " && c != "\t" && c != "\n" && c != "\r") break; i++ }
      if (substr(buf, i, 1) != "\"") exit 0
      i++
      out = ""
      while (i <= n) {
        c = substr(buf, i, 1)
        if (c == "\\") {
          d = substr(buf, i + 1, 1)
          if (d == "n") out = out "\n"
          else if (d == "t") out = out "\t"
          else if (d == "r") out = out ""
          else if (d == "\"") out = out "\""
          else if (d == "\\") out = out "\\"
          else if (d == "/") out = out "/"
          else if (d == "u") {
            hex = substr(buf, i + 2, 4)
            # decode only the common BMP range (CJK); surrogate pairs are not handled and hardly ever appear in the source
            code = 0
            for (k = 1; k <= 4; k++) {
              ch = substr(hex, k, 1)
              v = index("0123456789abcdef", tolower(ch))
              if (v == 0) { code = 63; break }
              code = code * 16 + (v - 1)
            }
            if (code < 128) out = out sprintf("%c", code)
            else if (code < 2048) out = out sprintf("%c%c", 192 + int(code / 64), 128 + code % 64)
            else out = out sprintf("%c%c%c", 224 + int(code / 4096), 128 + int(code / 64) % 64, 128 + code % 64)
            i = i + 4
          } else out = out d
          i = i + 2
          continue
        }
        if (c == "\"") break
        out = out c
        i++
      }
      # What is decoded here is real text (a newline is a newline), so do not escape again; escaping is done only where the JSON is output
      printf "%s", out
    }
  ' "$1"
}

json_num() {
  # The value may be a number or a string (model JSON gives both)
  sed -n 's/.*"'"$2"'"[[:space:]]*:[[:space:]]*"\([0-9][0-9]*\)".*/\1/p' "$1" | head -n 1 > /tmp/.jn1
  sed -n 's/.*"'"$2"'"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$1" | head -n 1 > /tmp/.jn2
  A=$(cat /tmp/.jn1); B=$(cat /tmp/.jn2); rm -f /tmp/.jn1 /tmp/.jn2
  [ -n "$A" ] && printf '%s' "$A" || printf '%s' "$B"
}

# ---------- Input ----------
TEXT=$(json_str "$ARGS" text)
TARGET=$(json_str "$ARGS" target)
SOURCE=$(json_str "$ARGS" source)
STYLE=$(json_str "$ARGS" style)
CHUNK=$(json_num "$ARGS" chunk)
# Target language: argument > environment variable MIKA_TRANSLATE_TARGET > Simplified Chinese.
# No whitelist — German, Vietnamese, Swahili, whatever the user asks for; the model works it out.
[ -n "$TARGET" ] || TARGET="${MIKA_TRANSLATE_TARGET:-}"
[ -n "$TARGET" ] || TARGET="简体中文"
TARGET_NOTE=""
# Strip spaces and hyphens when matching aliases ("Traditional Chinese" and "zh-tw" both work), but keep the original value for pass-through
NORM=$(printf '%s' "$TARGET" | tr 'A-Z' 'a-z' | tr -d ' _-')
case "$NORM" in
  zh|zhcn|cn|chinese|中文|简体|简体中文|zhhans)
    TARGET="简体中文"; TARGET_NOTE="用中国大陆的习惯用语与标点。" ;;
  zhtw|zhhk|hk|tw|har|hant|繁体|繁體|繁體中文|繁体中文|traditionalchinese)
    TARGET="繁體中文"; TARGET_NOTE="用台灣的習慣用語與標點（軟體、網路、影片這類詞按台灣說法）。" ;;
  ja|jp|japanese|日语|日文|日本語)
    TARGET="日本語"; TARGET_NOTE="自然な日本語で。" ;;
  ko|kr|korean|韩语|韓語|한국어)
    TARGET="한국어"; TARGET_NOTE="자연스러운 한국어로。" ;;
  en|english|英语|英文)
    TARGET="English"; TARGET_NOTE="Natural English." ;;
  *) TARGET_NOTE="" ;;
esac
[ -n "$CHUNK" ] || CHUNK=1800
# Default 1800; may be lowered to 200, upper limit 6000 (chunks that are too large make the model drop parts).
# Anything below 200 is not clamped to 200 but falls back to the default — too small a chunk only fragments the split.
if [ "$CHUNK" -lt 200 ] 2>/dev/null || [ "$CHUNK" -gt 6000 ] 2>/dev/null; then
  CHUNK=1800
fi

if [ -z "$(printf '%s' "$TEXT" | tr -d ' \t\n\r')" ]; then
  printf '{"ok":false,"error":"empty_text","message":"nothing to translate"}\n'
  exit 0
fi

BASE="${MIKA_TRANSLATE_BASE:-}"
KEY="${MIKA_TRANSLATE_KEY:-}"
MODEL="${MIKA_TRANSLATE_MODEL:-}"
NOTE=""
case "$BASE" in
  http://*|https://*) ;;
  *) BASE=""; NOTE="未配置 MIKA_TRANSLATE_BASE，用自己的模型在对话里翻译（不调用接口）。" ;;
esac

# ---------- Chunking ----------
# Split on blank line -> newline -> sentence-ending punctuation -> hard cut, preferring natural boundaries to minimise lost context.
# Resolve the work directory to an absolute path: a relative path (pt/scripts/../work) fails when awk writes files — awk's > does not resolve parent directories.
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || SELF=""
[ -n "$SELF" ] || SELF="$(dirname "$0")"
WORK="$SELF/../work"
mkdir -p "$WORK" 2>/dev/null
WORK="$(cd "$WORK" 2>/dev/null && pwd)" || WORK="/tmp"
PART="$WORK/rt-$$.txt"
rm -f "$WORK"/rt-"$$".*.txt 2>/dev/null
LIST="$WORK/rt-$$.list"
# awk's > assembles the output line by line: a real newline in the body gets treated as "a new line" and truncated.
# So encode first (newline -> literal \n, backslash -> \001); awk only sees single lines, and decoding happens after writing to disk.
enc() { printf '%s' "$1" | awk '{ if (NR > 1) printf "%sn", "\\"; printf "%s", $0 }'; }
printf '%s' "$(enc "$TEXT")" | awk -v max="$CHUNK" -v dir="$WORK" -v pid="$$" -v list="$LIST" '
  # awk length/substr count bytes on Android, and one CJK character takes 3 bytes;
  # byte cutting leaves half a character and turns it into garbage. Build a map from UTF-8 lead bytes
  # (string comparison here, to dodge the ord trap), and do length, truncation and the k-th character
  # all in characters. Lines that are not valid UTF-8 fall back to bytes.
  function buildmap(s,   i, n, c, o, w) {
    split("", CB); split("", CE)
    o = 0; n = length(s)
    for (i = 1; i <= n; i++) {
      c = substr(s, i, 1)
      if (c < "\200") w = 1
      else if (c < "\340") w = 2
      else if (c < "\360") w = 3
      else w = 4
      if (i + w - 1 > n) return 0
      o++; CB[o] = i; CE[o] = i + w - 1
      i += w - 1
    }
    CLEN = o
    return 1
  }
  function charlen(s) { return (buildmap(s)) ? CLEN : length(s) }
  function charsub(s, a, b,   en) {
    if (!buildmap(s)) return substr(s, a, b)
    if (a < 1) a = 1
    if (a > CLEN || b <= 0) return ""
    en = a + b - 1
    if (en > CLEN) en = CLEN
    return substr(s, CB[a], CE[en] - CB[a] + 1)
  }
  function charat(s, k) {
    if (!buildmap(s)) return substr(s, k, 1)
    if (k < 1 || k > CLEN) return ""
    return substr(s, CB[k], CE[k] - CB[k] + 1)
  }
  # Note: $(...) eats trailing newlines, so do not append \n to the last line
  { lines[NR] = $0 }
  END {
    buf = ""; n = 0; pending_blank = ""
    for (r = 1; r <= NR; r++) {
      line = lines[(r)]
      # Remember blank lines and prepend them once the next line really emits a chunk (otherwise a paragraph-final blank line is lost)
      if (line ~ /^[ \t]*$/) { pending_blank = pending_blank "\n"; continue }
      # Prefer cutting at sentence-ending punctuation (both CJK and Latin); lengths are always counted in characters.
      # When the loop ends rest is empty = this line is done; the eol flag is computed separately per chunk,
      # not by testing cut==0 for end of line (the last chunk can have cut > 0 too, and then it would never end).
      rest = line
      while (1) {
        # Note: never write charlen(rest) + charlen(buf); buildmap mutates the global CLEN, so the two calls overwrite each other
        if (!buildmap(rest)) { LEN_REST = length(rest) } else { LEN_REST = CLEN }
        if (!buildmap(buf)) { LEN_BUF = length(buf) } else { LEN_BUF = CLEN }
        eol = 0
        if (LEN_REST + LEN_BUF <= max) {
          piece = rest
          rest = ""
          eol = 1
        } else {
          head = charsub(rest, 1, max)
          buildmap(rest); RLEN = CLEN
          cut = 0
          for (k = charlen(head); k > max * 0.5; k--) {
            ch = charat(head, k)
            if (ch == "." || ch == "!" || ch == "?" || ch == "。" || ch == "！" || ch == "？" || ch == ";" || ch == "；") { cut = k; break }
          }
          if (cut > 0) {
            piece = charsub(rest, 1, cut)
            if (cut >= RLEN) { rest = ""; eol = 1 }
            else { rest = charsub(rest, cut + 1, RLEN - cut) }
          } else {
            piece = head
            if (max >= RLEN) { rest = ""; eol = 1 }
            else { rest = charsub(rest, max + 1, RLEN - max) }
          }
        }
        buf = buf pending_blank piece
        pending_blank = ""
        buildmap(buf); BUF_NOW = CLEN
        if (BUF_NOW >= max) { n++; f = sprintf("%s/rt-%s.%04d.txt", dir, pid, n); printf "%s", buf > f; close(f); printf "%s\n", f >> list; buf = "" }
        if (eol) break
      }
    }
    if (pending_blank != "" && charlen(buf) > 0) buf = buf pending_blank
    if (charlen(buf) > 0) { n++; f = sprintf("%s/rt-%s.%04d.txt", dir, pid, n); printf "%s", buf > f; close(f); printf "%s\n", f >> list }
  }
'
# Decode every chunk back to real text (literal \n -> newline, \001 -> backslash)
if [ -s "$LIST" ]; then
  while IFS= read -r f; do
    [ -f "$f" ] || continue
    sed -e 's/\001/\\/g' -e 's/\\n/\n/g' -e '$ s/\n$//' "$f" > "$f.dec" && mv "$f.dec" "$f"
  done < "$LIST"
fi
PARTS=$(wc -l < "$LIST" 2>/dev/null | tr -d ' ')
[ -n "$PARTS" ] || PARTS=0
[ "$PARTS" -gt 0 ] || { printf '{"ok":false,"error":"split_failed","message":"could not split the text"}\n'; exit 0; }
if [ "$PARTS" -gt 40 ]; then
  rm -f "$WORK"/rt-"$$".*
  printf '{"ok":false,"error":"too_long","message":"%s 段超过 40 段上限，请分次翻译（改用 web_read_page 的 offset 分页）"}\n' "$PARTS"
  exit 0
fi

# ---------- JSON escaping ----------
# JSON escaping: backslash -> quote -> newline/tab. Newlines are escaped with awk (the -e clauses of sed apply in order,
# so escaping with sed and then removing \r would strip the \n that was just written — hit this in practice).
# Tabs are escaped too (a raw \t is equally illegal inside a JSON string)
jesc() { sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | awk 'BEGIN{first=1} { if (!first) printf "\\n"; first=0; gsub(/\t/, "\\t"); printf "%s", $0 }' ; }

# ---------- No endpoint: hand the chunks back so the model translates them itself ----------
if [ -z "$BASE" ]; then
  OUT="$WORK/rt-$$.out.json"
  {
    printf '{"ok":true,"mode":"segments","note":"%s","target":"%s","targetNote":"%s","parts":%s,"segments":[' "$NOTE" "$TARGET" "$TARGET_NOTE" "$PARTS"
    i=0
    while IFS= read -r f; do
      [ -f "$f" ] || continue
      i=$((i + 1))
      [ "$i" -gt 1 ] && printf ','
      printf '{"n":%s,"text":"' "$i"
      jesc < "$f"
      printf '"}'
      rm -f "$f"
    done < "$LIST"
    printf ']}\n'
  } > "$OUT"
  cat "$OUT"
  rm -f "$LIST" "$OUT"
  exit 0
fi

# ---------- Call the endpoint ----------
STYLE_RULE=""
case "$STYLE" in
  literal) STYLE_RULE="贴近原文结构，直译为主，术语保持前后一致。" ;;
  polish) STYLE_RULE="可读性优先，允许调整语序，术语保持前后一致。" ;;
  *) STYLE_RULE="准确、通顺，术语保持前后一致；保留原文的段落与列表结构。" ;;
esac
SRC_RULE=""
[ -n "$SOURCE" ] && SRC_RULE="源语言是 $SOURCE。"
SYS="你是专业翻译。把用户给出的文本翻译成${TARGET}。${SRC_RULE}${STYLE_RULE}${TARGET_NOTE}只输出译文，不要解释、不要加引号、不要输出原文。保留原文中的网址、代码、专有名词不译。"

OUT="$WORK/rt-$$.out.json"
: > "$OUT"
AI=0
ERR=""
while IFS= read -r f; do
  [ -f "$f" ] || continue
  AI=$((AI + 1))
  BODY="$WORK/rt-$$.req.json"
  {
    printf '{"model":"%s","temperature":0.2,"messages":[{"role":"system","content":"' "$MODEL"
    printf '%s' "$SYS" | jesc
    printf '"},{"role":"user","content":"'
    jesc < "$f"
    printf '"}]}'
  } > "$BODY"

  CODE=$(curl -s -o "$WORK/rt-$$.resp.json" -w '%{http_code}' \
    --max-time 90 -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $KEY" \
    --data-binary "@$BODY" "$BASE" 2>/dev/null)
  CODE=${CODE:-000}
  case "$CODE" in
    2??) ;;
    *)
      ERR="HTTP $CODE $(head -c 300 "$WORK/rt-$$.resp.json" 2>/dev/null | tr -d '\n')"
      break ;;
  esac

  # choices[0].message.content (a string). reasoning_content sometimes comes first, so cutting to after "message" is safer.
  TR=$(awk 'BEGIN { RSTART = 0 }
    {
      buf = buf $0 "\n"
    }
    END {
      pos = index(buf, "\"message\"")
      if (pos > 0) buf = substr(buf, pos)
      if (!match(buf, "\"content\"[ \t\r\n]*:[ \t\r\n]*\"")) exit 0
      i = RSTART + RLENGTH
      n = length(buf)
      out = ""
      while (i <= n) {
        c = substr(buf, i, 1)
        if (c == "\\") {
          d = substr(buf, i + 1, 1)
          if (d == "n") out = out "\n"
          else if (d == "t") out = out "\t"
          else if (d == "r") out = out ""
          else if (d == "\"") out = out "\""
          else if (d == "\\") out = out "\\"
          else if (d == "/") out = out "/"
          else if (d == "u") {
            hex = substr(buf, i + 2, 4)
            code = 0
            for (k = 1; k <= 4; k++) {
              ch = substr(hex, k, 1)
              v = index("0123456789abcdef", tolower(ch))
              if (v == 0) { code = 63; break }
              code = code * 16 + (v - 1)
            }
            if (code < 128) out = out sprintf("%c", code)
            else if (code < 2048) out = out sprintf("%c%c", 192 + int(code / 64), 128 + code % 64)
            else out = out sprintf("%c%c%c", 224 + int(code / 4096), 128 + int(code / 64) % 64, 128 + code % 64)
            i = i + 4
          } else out = out d
          i = i + 2
          continue
        }
        if (c == "\"") break
        out = out c
        i++
      }
      # Escaping: newlines in the decoded string are real newlines, and out here only assembles the JSON file (cat-ed below / placed into JSON)
      gsub(/\r/, "", out)
      gsub(/\\n/, "\001", out)   # placehold literal backslash+n first, so the next step does not escape it twice
      gsub(/\n/, "\\\\n", out)
      gsub(/\t/, "\\\\t", out)
      gsub(/\001/, "\\\\n", out)
      printf "%s", out
    }
  ' "$WORK/rt-$$.resp.json")

  if [ -z "$(printf '%s' "$TR" | tr -d ' \t\n\r')" ]; then
    # Some compatible endpoints reject system messages: put the instruction into user and try again
    BODY2="$WORK/rt-$$.req2.json"
    {
      printf '{"model":"%s","temperature":0.2,"messages":[{"role":"user","content":"'
      printf '%s\n\n---\n' "$SYS" | jesc
      jesc < "$f"
      printf '"}]}'
    } > "$BODY2"
    CODE=$(curl -s -o "$WORK/rt-$$.resp.json" -w '%{http_code}' \
      --max-time 90 -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $KEY" \
      --data-binary "@$BODY2" "$BASE" 2>/dev/null)
    CODE=${CODE:-000}
    case "$CODE" in
      2??) ;;
      *) ERR="HTTP $CODE $(head -c 300 "$WORK/rt-$$.resp.json" 2>/dev/null | tr -d '\n')"; break ;;
    esac
    TR=$(awk '{
      buf = buf $0 "\n"
    }
    END {
      pos = index(buf, "\"message\"")
      if (pos > 0) buf = substr(buf, pos)
      if (!match(buf, "\"content\"[ \t\r\n]*:[ \t\r\n]*\"")) exit 0
      i = RSTART + RLENGTH
      n = length(buf); out = ""
      while (i <= n) {
        c = substr(buf, i, 1)
        if (c == "\\") { d = substr(buf, i + 1, 1); if (d == "n") out = out "\n"; else if (d == "t") out = out "\t"; else if (d == "\"") out = out "\""; else if (d == "\\") out = out "\\"; else out = out d; i = i + 2; continue }
        if (c == "\"") break
        out = out c; i++
      }
      printf "%s", out
    }' "$WORK/rt-$$.resp.json")
  fi

  [ -n "$TR" ] || { ERR="empty_translation（接口返回里没有 content）"; break; }
  # Add only one newline between chunks; newlines inside a chunk stay exactly as the model returned them
  [ "$AI" -gt 1 ] && printf '\n' >> "$OUT"
  # The model sometimes writes newlines as a literal backslash+n: normalise them back to real newlines when joining chunks
  printf '%s' "$TR" | tr -d '\r' | sed 's/\\n/\n/g' >> "$OUT"
done < "$LIST"

if [ -n "$ERR" ]; then
  rm -f "$WORK"/rt-"$$".*
  printf '{"ok":false,"error":"api_failed","message":"%s"}\n' "$(printf '%s' "$ERR" | jesc)"
  exit 0
fi

{
  printf '{"ok":true,"mode":"translated","target":"%s","calls":%s,"translation":"' "$TARGET" "$AI"
  jesc < "$OUT"
  printf '"}\n'
} > "$OUT.json"
cat "$OUT.json"
rm -f "$WORK"/rt-"$$".*

if [ "$AI" -gt 0 ] && [ "$AI" -gt 3 ]; then
  # Only a run where every chunk succeeded counts as complete; one failed chunk broke out above
  :
fi
exit 0
