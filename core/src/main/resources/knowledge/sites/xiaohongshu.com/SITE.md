---
name: xiaohongshu.com
display: Xiaohongshu
domains: ["xiaohongshu.com", "xhslink.com"]
aliases: ["小红书", "Xiaohongshu", "XHS", "xiaohongshu", "xhs"]
apps: ["xiaohongshu"]
open: xiaohongshu.com
mode: desktop
description: A community of image-text and video notes; search notes and read their body text, engagement counts and comments
---
## What it is
A content community centered on user notes; notes are image-text or video, and some of them are ads or requests for recommendations.

## Pages and URLs
- Search: `https://www.xiaohongshu.com/search_result?keyword=<编码关键词>&source=web_explore_feed`, which can be opened directly.
- Note details: `/explore/<笔记ID>` with an `xsec_token` parameter; it will not open without the token.
- `xhslink.com` is the sharing short link.

## Features
- **Search**: the results are cards containing the title, author, like count, cover, image-text or video type and link. The first screen has about 20 items, and scrolling down two or three times reaches fifty or sixty. A valid signed-in session is usually required.
- **Note details**: the body text, publish time, IP location, like, favorite and comment counts, and the comments (author, original words, like count). Clicking a card in the search results pops up a detail layer, and the card link carries its own `xsec_token`.
- **Mobile app**: see `xiaohongshu` for the corresponding documentation.

## Known issues
- Some cards have no title and show the opening passage of the body text instead.
- Comments initially load only the first page; only a comment count displayed as 0 means there are no comments.
- Abbreviated counts such as 「1.2万」 (12K) are displayed as they are.
- Publish time and update time are two fields, and some notes only show the latter.
- Ad cards are mixed into the search results; they are not notes.
- When the service is busy it reports that the network is busy, and retrying after a few seconds usually recovers.
