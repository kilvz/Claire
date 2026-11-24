---
name: baijiahao
display: Baijiahao
packages: ["com.baidu.baijia"]
aliases: ["百家号", "Baijiahao", "baijiahao", "百度百家号"]
sites: []
open: 百家号
description: Baidu's content creator app for publishing posts and videos, managing works, and checking stats and earnings
---
## What it is
Baijiahao is Baidu's creator app, package name `com.baidu.baijia`. It is used to publish and manage your own content, check reads, followers and earnings data, and handle comments and notifications.

## UI
Bottom tabs: Home, Manage, the middle 「+」 (publish), Messages, Me.
- **Home**: the title 「百家号」 (Baijiahao), below it 「热门事件」 (Trending events), which can be switched by channel such as all-network, city, entertainment and sports (the button on the right changes the channel).
- **Manage**: data cards at the top: cumulative reads, earnings this month, total followers, message analytics; below, your works are listed under the tabs All, Posts, Articles, Videos, Short videos and Live, and each one has a title, publish time, status (such as 「已发布」, published), a few interaction counts and a 「…」 action menu; the search button is at the top right of the list.
- **Messages**: the top splits into Interactions, Notifications and Announcements, with message settings at the top right; 「互动」 (Interactions) is grouped into comments, follows, likes, saves and shares, with the message list below.
- **Me**: a profile card (followers, following, vitality score, credit score), with scan and QR code at the top right; 「常用功能」 (Common features): My benefits, My verification, My info, Comment management, Material management, Topic management, Showcase management, Blocklist management, My favorites, Host center, Baijia+; 「更多功能」 (More features): Posting settings, Message settings, System settings, Help & feedback.

## Features
- **Publishing**: the 「+」 at the bottom opens the publish panel, which shows how many posts you can still publish today; it offers Articles, Posts, Live, Photo sets, Shoot video, Upload video, Video collections and Drafts, with 「推荐活动」 (Recommended activities) below; there is a close button at the bottom.
- **System settings**: Clear cache, About (version number), List of personal information collected, List of third-party information sharing, Log out.

## Known issues
- When opened, it may pop up 「检测到您尚未打开通知功能……」 (notifications are not enabled...), with the buttons 「取消」 (Cancel) and 「去开启」 (Turn on).
- Quite a few buttons have no name and only a resource ID: the back button (`left_button`), scan (`scan_it`), QR code (`qr_code`) and the work action menu (`operation_menu_layout`).
- The numbers below a work on the Manage page are only numbers in the elements, with no indication of which metric each one is; look at the icons on screen.
