---
name: toutiao
display: Toutiao
packages: ["com.ss.android.article.news"]
aliases: ["今日头条", "头条", "Toutiao", "toutiao"]
sites: []
open: 今日头条
description: News and information feed, short videos and mall
---
## What it is
Toutiao is a news and information app, package name `com.ss.android.article.news`. It has an article and image feed, vertical-screen videos and a mall.

## UI
Bottom tabs: 首页 (Home), 视频 (Video), the middle 「+」 (publish), 商城 (Mall), and on the far right the personal page (shows 「未登录」 (Not logged in) when not logged in).
- **Home**: at the top are the search box (the box rotates through recommended hot terms) and 「豆包 AI」 (Doubao AI) on the right; below is a row of channels: 关注 (Following), 推荐 (Recommended), 热榜 (Hot list), 发现 (Discover), the current city, 视频 (Video), etc., and on the far right the 「听一听开关」 (Listen toggle). The content is a recommendation feed, each item with a title, source and comment count.
- **Video**: full-screen vertical videos, swipe up and down to switch; at the top are a search button, channels (关注, 精选 (Featured), 推荐, 找短剧 (Find short dramas)), publish and a more panel; on the right are the author's avatar (follow), like, comment, favorite and share.
- **Mall**: the top channels are 直播 (Live), 商城 and 国家补贴 (National subsidy); below are a product search box, cart and my orders, check-in, claim coupons, followed shops, shopping messages and other entries, and further down is the product feed.
- **Personal page**: when not logged in, tapping it shows the login page, with 「设置」 (Settings) in the top right.

## Features
- **Search**: tap the home page search box to enter. At the top are back, the input box, a camera icon and a 「搜索」 (Search) button; below are recommended terms, search history (with delete), and the groups 「猜你想搜」 (You may want to search), 「头条热榜」 (Toutiao hot list) and 「影视热搜榜」 (Film and TV hot search list); at the bottom are an 「AI 搜索」 (AI search) toggle, 「语音搜索」 (Voice search) and a 「无痕搜索模式」 (Incognito search mode) toggle.
- **Settings**: scan, privacy settings, dark mode, elder mode, font size, article detail swipe mode, auto-enable reading mode, clear cache, audio settings, playback and network settings, push notification settings, safe browsing settings. When not logged in, enter from the top right of the login page.

## Known issues
- When not logged in, opening the app shows the login page (「一键登录」 (One-tap login) uses a Douyin account, and there is also 「其他登录方式」 (Other login methods)); 「关闭」 (Close) in the top left closes it, and you can view the home page and videos without logging in.
- Right after opening, the app's own notification prompt may be overlaid: 「是否允许"今日头条"发送通知?」 (Allow "Toutiao" to send notifications?), with the buttons 「始终允许」 (Always allow) and 「暂不开启」 (Not now).
- The element name of the search box is the hot term currently being rotated, not the content the user typed. The search page input box also pre-displays a recommended term.
- In the home feed, titles are often split into one element per character; the title of the whole item is in the name of the outer clickable element.
- Entering the mall may pop up a large check-in cash dialog (「关联抖音并领取」 (Link Douyin and claim)), with a close 「×」 below; the elements of this pop-up cannot be read.
- The element list of the mall page basically only has the top channels and bottom tabs; for the search box, entries and products you have to look at the screen. When not logged in, product prices are not fully shown, and the bottom prompts 「登录查看商品价格」 (Log in to view product prices).
