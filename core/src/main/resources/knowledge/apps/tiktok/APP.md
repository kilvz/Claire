---
name: tiktok
display: TikTok
packages: ["com.zhiliaoapp.musically"]
aliases: ["TikTok", "tiktok", "抖音海外版"]
open: TikTok
pace: careful
description: Short video and live-streaming social app: recommendation feed, creation, search and private messages
---
## What it is
TikTok is a globally popular short video and live-streaming social app, package name `com.zhiliaoapp.musically`. It is the international counterpart of Douyin in China. It has strict automated risk-control detection, so browsing operations need to keep a moderate pace (`pace: careful`).

## UI
The bottom navigation contains five tabs: 「首页 (Home)」, 「朋友 (Friends)」, 「+ (创作发布)」 (Create), 「收件箱 (Inbox)」 and 「个人资料 (Profile)」.
- **首页 (Home)**: at the top center you can switch between 「为你推荐 (For You)」, 「正在关注 (Following)」, 「朋友 (Friends)」 and 「直播 (LIVE)」; in the top right is the search magnifier. The main body is single-column, full-screen, vertically swiped short videos.
- **Right-side interaction toolbar**: from top to bottom are the creator's avatar (with a follow red-ring plus sign), the heart like icon (showing the number of likes), the bubble comment icon (showing the comment count), the yellow bookmark favorite icon, the share arrow (repost, send a DM, copy link), and in the bottom right a spinning vinyl record of the original sound.
- **Search page**: enter from the magnifier in the top right of the home page; it supports filtering by 「综合 (Top)」, 「用户 (Users)」, 「视频 (Videos)」, 「原声 (Sounds)」, 「直播 (LIVE)」 and 「话题 (Hashtags)」.
- **收件箱 (Inbox)**: at the top are the DM conversation entry and online contacts, below is an aggregation of system notifications and interaction messages (likes, comments, new follows).

## Features
- **Full-screen swipe browsing and deciding to stay**:
  - Swipe up lightly to switch to the next recommended video, swipe down lightly to go back to the previous one.
  - Single-tap anywhere on the screen to pause/resume playback.
- **Interaction and reading comments**:
  - Double-tap the video picture or tap the heart on the right to like.
  - Tap the bubble icon to slide out a half-screen comment drawer from the bottom; scroll up and down to read top comments, like good comments, or write a reply in the 「添加评论...」 (Add comment...) box at the bottom. Swipe the drawer down or tap 「×」 in the top left to collapse the comments.
- **Following creators and original sounds**:
  - Tap the 「+」 plus sign below the author's avatar to follow directly; or tap the author's avatar/username to enter their profile and see all their published works and pinned works.
  - Tap the spinning record in the bottom right to enter the aggregation page for that background music (Sound) and see other popular videos using the same BGM.

## Known issues
- **Risk control and interception of too-fast swiping**: frequent page turning in a short time or rapid repeated taps can trigger a swipe freeze or a CAPTCHA jigsaw challenge, so you must follow `pace: careful`.
- **Forced login/sign-up interception pop-up**: after browsing several videos without logging in, a non-skippable full-screen 「登录 TikTok」 (Log in to TikTok) page often pops up (supporting Google, Facebook or phone-number login).
- **Live rooms mixed into the feed**: a room that is live occasionally appears in the recommendation feed (the picture has a red 「LIVE」 mark and the layout differs from regular short videos); swipe up to pass it quickly.
