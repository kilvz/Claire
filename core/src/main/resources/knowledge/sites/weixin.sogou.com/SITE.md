---
name: weixin.sogou.com
display: Sogou WeChat
domains: ["weixin.sogou.com"]
aliases: ["搜狗微信", "Sogou Weixin"]
apps: []
open: weixin.sogou.com
mode: desktop
description: Search official-account articles and official accounts; results come with summary cards and redirect links
---
## What it is
WeChat official account content search provided by Sogou. The results are article summary cards and do not contain the full text.

## Pages and URLs
- Article search: `https://weixin.sogou.com/weixin?type=2&query=<编码关键词>`.
- Use the full name, or a core term qualified by the business, as the keyword; common short words are very noisy.

## Features
- **Official-account article search**: each card has an article snippet, the official-account name and a redirect link.

## Known issues
- The links in the cards are encrypted redirect addresses that expire, and several cards may point to the same address; they are not stable article URLs.
- See `references/article-links.md` for how to tell the cards apart.
