---
name: weibo.com
display: Weibo
domains: ["weibo.com"]
aliases: ["微博", "Weibo", "weibo"]
apps: ["weibo"]
open: weibo.com
mode: desktop
description: Social media; search recent events, user posts and spread data by keyword and date
---
## What it is
A public social media platform in China; the content is short posts published by users, with repost, comment and like counts. It can be used to search recent events and user complaints.

## Pages and URLs
- Search: `https://s.weibo.com/weibo?q=<编码关键词>&xsort=hot`, where `xsort=hot` sorts by popularity.
- Restrict by date: add `timescope=custom:YYYY-MM-DD:YYYY-MM-DD`.

## Features
- **Search**: result cards have the author, publish time, body text, and repost, comment and like counts.
- **Original post**: clicking through shows the complete original text, the author and the counts. Reposts can be traced back to the first publisher.
- **Mobile app**: it opens into the current interface; see `weibo` for the corresponding app documentation.

## Known issues
- The top of popularity-sorted results often contains marketing accounts and reposts.
- Repost and like counts only reflect spread, and do not equal the number of people who had the same experience.
