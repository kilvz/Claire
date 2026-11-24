# Evidence: output reference percent escapes not decoded + main answer not rendering cards

## 2026-11-20 morning (local + real device Mate X2, HarmonyOS 4.2 / API 31)

### A. Evidence of library behaviour (root cause of defect 2)

`commonmark-0.30.1-destinations.txt`: running the project's same-version commonmark-java 0.30.1 directly,
the destination of `[x](/workspace/my%20file.csv)` returns `my%20file.csv` verbatim, and the Chinese
`%E6%8A%A5%E5%91%8A.png` likewise verbatim — the parser does not decode, so if the application layer does not
decode either, it does not match the real file name.

### B. Real-device walkthrough (found defect 1)

Installed main (c4ef434c + decode fix) → connect-computer submitted the task "generate /workspace/my report.csv
(with a space) and /workspace/报告.png (Chinese name), deliver the reply as Markdown references, percent-encode
the link targets per URL rules" → the task completed, and the answer contained
`![…](/workspace/%E6%8A%A5%E5%91%8A.png)` and `[my report.csv](/workspace/my%20report.csv)`.

- **01-before-main-answer-no-cards.png**: below the answer there is only inline text, **no cards/thumbnails at
  all** — `OutputRefs` is only wired into interjection replies (FinishedReply), and the task main answer
  (ExchangeStatus.Done) is not wired. (This screenshot was taken on a build containing only the decode fix, to
  characterise the missing wiring.)

### C. Real-device retest (wiring + decoding, fully fixed build)

Reran the same task (the fix branch includes the StatusCard wiring):

- **02-fixed-cards-rendered.png**: a teal thumbnail and a file card appear below the answer.
- Pixel scan: teal RGB(0,150,136) bounding box 644×400px, aspect ratio 1.61 = 64×40 ✓
  (the decoded path reads the real image through the file gate, not a placeholder).
- **03-fixed-filecard-decoded-name.png**: the file card name shows `my report.csv`
  (CSV type badge + file name, not `my%20report.csv`).
- **04-fixed-open-csv-viewer.png**: tapping the file card → the app viewer opens, title `my report.csv`,
  content `name,score / Alice,90` correct (openSent → relative → file gate, the whole chain works).
- **05-fixed-open-photo-viewer.png**: tapping the image card → the full-size viewer opens (the teal image shown
  in full).

### D. Unit tests

- `MarkdownModelTest` +3, `WorkspaceRefTest` +2 (decoding shapes/bare form/literal + and invalid escapes/encoded
  out-of-bounds dropped/photo classification); both the targeted and the full `:app:testDebugUnitTest`,
  `:core:test` pass.
