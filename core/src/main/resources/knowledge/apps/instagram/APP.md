---
name: instagram
display: Instagram
packages: ["com.instagram.android"]
aliases: ["Instagram", "instagram", "IG", "ins", "照片墙"]
sites: ["instagram.com"]
open: Instagram
pace: careful
description: Photo and Reels social app with feed, stories, explore and direct messages
---
## What it is
Instagram is a global photo and short video social platform app, package name `com.instagram.android`. For website knowledge see `instagram.com`. Because the platform has strict anti-scraping mechanisms, browsing needs a moderate pace (`pace: careful`).

## UI
The bottom navigation has five tabs: 「首页 (Feed)」, 「搜索与探索 (Explore)」, 「+ (创建)」 (create), 「Reels 短视频」 (Reels short videos) and 「个人资料 (Profile)」.
- **Home (Feed)**: the Instagram wordmark on the left of the top bar, and on the right the notification heart and the direct message chat entry (paper plane / Messenger icon). At the very top is the horizontally scrolling 「快拍 (Stories)」 row of circular avatars; the body is a single-column image-text/video feed.
- **Post card**: the poster's avatar, username, location and three-dot more menu at the top; a single image, a multi-image carousel (with a small dot indicator at the bottom) or a video in the middle; below it are the heart like, speech bubble comment, paper plane share and bookmark save actions, and below those the like count, the caption and 「查看全部 X 条评论」 (view all X comments).
- **Explore page**: a grid of popular images and short videos, with the global search box at the top.
- **Reels screen**: a full-screen immersive vertical video feed, with vertically stacked like, comment, share, save and original audio entries on the right.
- **Direct Messages**: entered through the paper plane at the top right of the home page, containing the recent conversation list, the circles of active followers and a search box.

## Features
- **Avoiding leftover state and going back up levels**:
  - If the app opens on the full-screen Reels playing page or deep in a direct message conversation, the bottom navigation may be hidden. Tap the back arrow 「←」 at the top left or press the system back key to leave the current module and return to the main home page.
- **Browsing Stories**:
  - Tap an avatar circle to enter the story. Each story is shown for a few seconds and then switches to the next automatically; tap the right half of the screen to jump quickly to the next one, tap the left half to go back to the previous one, and swipe down from the top to close the story.
- **Browsing multi-image carousels**:
  - For a carousel post with a small dot indicator, **swipe horizontally to the left** on the image area to browse the following images or the attached video.
- **Interacting and commenting**:
  - Double-tap the image or tap the heart icon to like it; tap the speech bubble icon to slide out the comment drawer, write a reply in the bottom input box and tap 「发布」 (Post).

## Known issues
- **Screenshot behavior and all-black screens (FLAG_SECURE)**:
  - Most regular feeds and public post pages can be read normally through screenshots on most devices, so screenshots and visual analysis can be attempted as usual.
  - However, in direct messages with vanish mode, view-once media, or the strict privacy mode on some devices, the system enables `FLAG_SECURE` screenshot protection, which makes the screenshot all black.
  - **If you get an all-black screen, don't be confused and don't mistake it for a crash or a freeze**: you can switch seamlessly to reading the accessibility node text (`interfaces`) to identify the interface elements and text content, or just press the back key to leave the protected screen. Taking a screenshot of view-once media in direct messages may also notify the other person, so don't trigger it unless necessary.
- **Rate limits and risk control (Pace Careful)**: liking at high speed continuously, swiping quickly and frequently or sending many direct messages in a short time triggers a 「请稍后再试」 (please try again later) restricted-operation block, so leave an interval between operations.
- **The DM entry is not in the bottom bar**: the Instagram bottom bar has no separate DM tab, and the DM entry is fixed at the top right of the home page. If you are not currently on the home tab, you must first switch to the first tab, 「首页」 (Home), to see the DM entry.
- **Save login info and sync contacts pop-ups**: after signing in or updating, a full-screen prompt 「是否保存你的登录信息?」 (save your login info?) or 「寻找你的 Facebook 好友」 (find your Facebook friends) often pops up; skip it with 「以后再说 / Not Now」.
- **Full-screen posting mode**: tapping 「+」 in the middle enters the full-screen camera or gallery picker, and you need to tap 「×」 at the top left to cancel and exit.
