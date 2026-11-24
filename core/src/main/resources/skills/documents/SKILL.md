---
name: documents
display: Work with PDF documents
description: Read the text of a PDF, extract it by page, view document information, and hand the split or merged file to the user
aliases: ["读 PDF", "PDF 转文字", "合并 PDF"]
terms: ["PDF", "文档", "提取文字", "按页", "合并", "拆分", "扫描件"]
---
## Entry

First see where the document is:
- A public link on the web: open it with `web_navigate`; if the built-in browser can display it directly, reading the page is enough.
- An attachment the user sends: in `/workspace/inbox/`.
- A file downloaded from a web page: in `/workspace/downloads/`, and `web_downloads` can be used to check the download records.

## Steps

1. For a text-based PDF, use `run_bundled_script` to run `scripts/extract_pdf.py`, with `source` set to `skills/documents` and the file path written relative to `/workspace`:
   - Full text: `args: ["inbox/a.pdf"]`
   - Specified pages (counted from 1; `3`, `1-5`, `1,4,7-9` may be written): `args: ["inbox/a.pdf", "--pages", "1-5"]`
   - Information such as title, author and page count (JSON): `args: ["inbox/a.pdf", "--metadata"]`
   - For a long document, save it as text first: `args: ["inbox/a.pdf", "--out", "a.txt"]`, then read it in sections with `read_file`, or find keywords with `search_files`.
   - For one with a password: add `--password` and the password.
2. In the output, each page starts with `--- page N ---`; cite page numbers according to it.
3. To split, merge or extract pages, use `run_command` to write a short piece of Python calling pypdf; put the new file under `/workspace`.
4. Hand the finished file to the user with `share_file`.

## Common pitfalls

- If the script exits with code 3 and reports that there is no extractable text, it is a scanned image-based PDF; this machine has no text recognition, so you can only look at the page images or tell the user this. When some pages have no text, the script reports those pages separately.
- The order extracted from tables and multi-column layouts is often jumbled; check numbers quoted from a table against the original page first.
- If the page range exceeds the number of pages in the document, the script reports an error directly; use `--metadata` first to check the page count.

## How success is verified

The text given can be matched to the original page in the script output, with page numbers written; the file handed over is shared successfully with `share_file`, and its page count and content match expectations (you can run `--metadata` again on the new file to check the page count).

## When to stop and hand off

When the document has a password and the user has not given it, or when the user needs to decide which pages to keep and in what order to merge them, ask the user first.
