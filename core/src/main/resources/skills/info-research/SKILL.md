---
name: info-research
display: Get to the bottom of something
description: Find out about something clearly on the web: find a direction, read the original source, cross-check several sides, and state the source and time
aliases: ["调研", "查一下", "帮我了解"]
apps: ["xiaohongshu", "zhihu", "bilibili", "weibo"]
sites: ["zhihu.com", "tieba.baidu.com", "weibo.com", "douban.com"]
terms: ["调研", "查一下", "了解", "背景", "对比", "靠不靠谱", "出处", "最新"]
---
## Entry

First pin down the object to research: full name, region, time period, version or model. When there are duplicate names or ambiguity in the wording, ask the user once, and while waiting for the answer, first research the parts unaffected by that question. When the user has not given a condition (such as a time range), pick a common set yourself and state at the beginning of the conclusion which set was used.

To start, use `web_navigate` to open a search page (Baidu, Bing), or use in-site search in the relevant app.

## Steps

1. **Find a direction**: first do two or three broader searches to see what claims there are about this matter, which sites discuss it, and whether there is an authoritative publisher. For in-site search, use two to four keywords, not a whole-sentence question.
2. **Find the original source**: for the fact the user directly asks about, trace it back to where it was first published: the publisher's own website, an official announcement, the original document, the original paper, or the page of the product or service itself. Treat news and reprints only as clues, and follow the links or names they give to the original text.
3. **Add experience and the other side**: for real usage experience, look at communities and video platforms (Xiaohongshu, Zhihu, Tieba, Weibo, Bilibili, Douban); for problems, look at complaint platforms. Search once specifically for negative terms ("problem", "complaint", "drawback").
4. **Cross-check**: read the original text of a key conclusion in at least two unrelated sources. When the two sides disagree, look at the publication time and at which side is closer to the matter, and write the disagreement itself out.
5. **Write the conclusion**: after each conclusion, write the source (website or publisher) and the time of that piece of content; keep what the original text says separate from your own inference; for things you checked but did not find, state clearly where you looked and what is still missing.

## Common pitfalls

- A search result snippet is not the original text and is often truncated or out of date; use `web_read_page` to open and read the body, and see clearly which website you finally land on.
- The same press release gets reprinted by many sites, so it looks like many sources but in fact has only one origin.
- Promotional articles, reviews with purchase links and anonymous leaks have low credibility, and should be labeled as such when used.
- A date on a page may be the date of reprinting or updating, not necessarily the time the event happened.
- Not finding anything, a page failing to open, or being blocked by a sign-in page all only mean that you did not read it this time, and cannot be taken as "no such thing".

## How success is verified

Every conclusion can be traced back to some page actually read this time, with the source and time written; key conclusions have two independent sources; weak or contradictory points have their reasons stated.

## When to stop and hand off

When the content to be read requires signing in to view, or when the next step turns into something like booking, buying or signing up that the user must decide on in person or pay for, hand the findings to the user.
