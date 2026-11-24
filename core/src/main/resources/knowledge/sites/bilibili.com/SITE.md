---
name: bilibili.com
display: Bilibili
domains: ["bilibili.com"]
aliases: ["B站", "哔哩哔哩", "bilibili", "b站"]
apps: ["bilibili"]
open: bilibili.com
mode: desktop
description: Video site: search hands-on, teardown and review videos and read comment-section feedback.
---
## What it is

A domestic video community with broad content coverage. There are many teardown, comparison and long-term-use videos for 3C digital products, and relatively few for large appliances, home and baby products. Among video creators there are both independent reviewers and accounts from manufacturers or with commercial partnerships. There is also a mobile app of the same name.

## Pages and URLs

- Search: `https://search.bilibili.com/all?keyword=<keyword>`; the keyword needs URL encoding. To limit the publish time, add `pubtime_begin_s` and `pubtime_end_s`, whose values are Unix timestamps in seconds.
- Video page: `/video/BV…`. The addresses of anime series and columns are not BV videos.

## Features

- **Video search**: results have the video link, title and publication date.
- **Video page**: the description body and publication date; the body does not include comments.
- **Comment section**: comments are rendered in a separate shadow DOM, so reading the text of the whole page or scrolling the outer page often doesn't get them. What can usually be read is the part sorted first by popularity; each has the author, body, like count, nested reply count, date and whether pinned; the page also has the total comment count.

## Known issues

- If comments are not read, it may be that the comment section is closed, there are no comments or loading failed.
- The comment API is signed, so requesting it directly outside the page is unstable.
