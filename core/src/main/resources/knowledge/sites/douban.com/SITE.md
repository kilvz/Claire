---
name: douban.com
display: Douban
description: Rating distributions and short reviews for book, movie and music entries, plus group discussion posts
domains: ["douban.com"]
aliases: ["豆瓣", "Douban"]
apps: []
open: douban.com
mode: desktop
---
## What it is
A rating and review community for books, movies and music, which also has interest-based group discussions (such as rental groups for each city).

## Pages and URLs
- Category search: `https://search.douban.com/<book|movie|music>/subject_search?search_text=<encoded name>`. The old path of the site-wide search cannot read ordinary links; use category search.
- Group discussion search: `https://www.douban.com/group/search?cat=1013&q=<encoded keyword>`. Search results are visible without logging in.
- Group posts: `https://www.douban.com/group/topic/<post-id>/`. When not logged in it shows 「你没有权限访问这个页面。请登录后重试。」 (You do not have permission to access this page. Please log in and try again.).

## Features
- **Entry search**: book, film/TV and music entries. Many works share a name: films and TV are distinguished by year between the main feature and sequels, books by author and edition, music by creator and album.
- **Ratings and short reviews**: overall rating, number of raters, 1–5 star distribution, and the author, date, helpful count and body of popular short reviews. Works with the same overall score may have a lopsided or a polarized distribution.
- **Group posts**: title, posting time, body, reply count. The post content is the poster's own account; rental posts are very short-lived, and sublets, rental requests and agents posing as individuals all exist.

## Known issues
- Choosing the wrong category gives empty results.
- Group search results mix in unrelated discussions that touch on the keyword (relationships, buying a home, etc.); filter by title and the group they belong to.
- To find personal listings you can search the 「<城市>租房」 (rentals in <city>) group; when several groups share a name, the one with more members is more active.
