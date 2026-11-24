---
name: content-transcribe
display: Turn content into text
description: Turn videos, podcasts and articles into text: summaries, chapters, quotes, or a rewrite into an article
aliases: ["总结视频", "整理播客", "文章摘要", "提取字幕"]
apps: ["bilibili", "xiaohongshu"]
sites: ["youtube.com", "xiaoyuzhoufm.com"]
terms: ["摘要", "总结", "转录", "字幕", "播客", "章节", "要点", "引文", "文字稿"]
---
## Entry

First clarify which kind of result is wanted: a full transcript, a summary, chapters, quotes, or a rewrite into an article. If the user does not say, produce a summary of five to ten sentences.

Take the text from where the content lives: for an article, read the page body; for a video, take the subtitles or the transcript in the description; for a podcast, check whether the episode page has a transcript or show notes. When there is only audio, first transcribe it with `audio_transcribe` (`/workspace/inbox/` or the downloaded file); if this tool is not provided, it means the user has not yet configured a provider transcription model, so ask the user for a transcript or subtitle file.

## Steps

1. **Get the full text**: use `web_read_page` to read the page body; subtitle or transcript files the user sends are in `/workspace/inbox/`, read them with `read_file`; when there is only audio, transcribe it with `audio_transcribe`.
2. **Read in sections**: when the content is long, read it in several sections, leaving a little overlap between sections, remove the duplication after reading, and confirm that no section was missed from beginning to end.
3. **Write in the requested form**: see `references/transcript-output-formats.md` for how to write each form and for samples, and read it once before writing. The skeleton of a summary is: one or two sentences of overview; three to eight topics in order of appearance, each with an approximate start time and two or three sentences of content; finally, the viewpoints, figures, and books or works mentioned that are worth noting down.
4. **Write in the language of the original content**, or follow the user's own requirement if there is one.
5. **Re-read**: after writing, go through it against the original: is the order right, is any topic missing, can the times and quotes be found in the original.

## Common pitfalls

- Subtitles contain only text; charts, demonstrations and on-screen text in the picture cannot be read, so when writing you cannot claim to have seen the visuals.
- Automatically generated subtitles often contain typos and wrong sentence breaks; proper nouns, names and numbers should be checked against the context or the description.
- Times are estimated from subtitle positions, so write "around minute 23" and not to the precise second.
- Segments that are inaudible or have missing subtitles should be marked out; do not fill them in.

## How success is verified

The result covers every section of the original content; every quote and time can be found in the subtitles or body text obtained.

## When to stop and hand off

When the content requires signing in or paying to see, or when there is only audio and `audio_transcribe` is not provided or transcription fails, tell the user what is missing and ask the user to provide a transcript.
