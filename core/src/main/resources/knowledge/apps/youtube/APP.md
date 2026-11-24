---
name: youtube
display: YouTube
packages: ["com.google.android.youtube"]
aliases: ["YouTube", "youtube", "油管", "YT"]
sites: ["youtube.com"]
open: YouTube
description: Long videos, Shorts, channel subscriptions and playlists
---
## What it is
YouTube is Google's global video streaming platform app, package name `com.google.android.youtube`. For website knowledge see `youtube.com`.

## UI
The bottom navigation usually contains five tabs: 「首页」 (Home), 「Shorts」, 「+ (创建)」 (Create), 「订阅内容」 (Subscriptions) and 「你 (个人中心与媒体库)」 (You (profile and library)).
- **首页 (Home)**: at the top left is the YouTube icon, on the right are the cast button, notification bell, magnifier search button and avatar. The main feed mixes long-video cards, Shorts carousel shelves and community posts.
- **Video playback page**: the upper half is the video playback window; the lower half shows, in order, the video title, the small arrow to expand the description, the channel avatar and name, the 「订阅」 (Subscribe) button, like/dislike, share, Remix, download and save-to-playlist buttons. Further below is the 「评论」 (Comments) overview entry (showing the total comment count and the top pinned comment).
- **Shorts interface**: a full-screen single-column vertical swipe feed, with like, dislike, comment, share, Remix and the original-sound spinning vinyl icon arranged from top to bottom on the right.
- **Mini player (Mini-player)**: when you swipe down or press back from the long-video playback page, the video shrinks into a bar or small window floating at the bottom, with play/pause and close buttons.

## Features
- **Handling leftover playback state**:
  - When opening the app, if the bottom or bottom right is still on the mini player from a video you did not finish last time, **do not mistake it for full-screen playback**. If you need to perform a new search, tap the magnifier at the top directly; if you need to close the old video, tap 「×」 on the right of the mini bar to close it.
- **Search and precise filtering**:
  - Tap the magnifier at the top to enter search. After entering keywords and searching, tap the funnel/filter button in the top right to filter by 「上传日期」 (Upload date) (last hour, today, this week, this year), 「类型」 (Type) (video, channel, playlist, movie) and 「时长」 (Duration) (under 4 minutes, 4-20 minutes, over 20 minutes).
- **Playback and control**:
  - Tap the playback window to show the control overlay: pause/play, fast-forward 10 seconds, rewind 10 seconds, previous episode, next episode.
  - The gear icon in the top right adjusts 「画质」 (Quality), 「播放速度」 (Playback speed) (0.25x to 2x), 「字幕(CC)」 (Subtitles (CC)) and 「循环播放」 (Loop).
- **Interaction and favorites**:
  - Like/dislike, and save to an existing or self-made 「播放列表」 (Playlist).
  - Tapping the 「评论」 (Comments) area slides a comment drawer up from the bottom, where you can write a reply in the input box or like other people's comments.

## Known issues
- **Pre-roll and mid-roll ads**: non-Premium users often get 5-15 second ads at the start or in the middle. For skippable ads, a 「跳过广告 (Skip)」 button appears in the bottom right after 5 seconds; wait until it is clickable and then tap it; for non-skippable ads you have to wait for them to finish.
- **Premium full-screen pop-up**: on first open or periodically a 「试用 YouTube Premium」 (Try YouTube Premium) promo overlay may pop up; tap 「×」 in the top left or 「不用了」 (Not now) to close it.
- **Losing the top bar when entering Shorts**: in Shorts mode the regular search bar and top bar are not shown; you must tap another bottom tab or press the system back key to exit Shorts and return to the main interface.
- **System notifications and picture-in-picture permission**: on first playback or when leaving, it may request 「画中画」 (Picture-in-picture) or system notification permission; the pop-ups are at the system level.
