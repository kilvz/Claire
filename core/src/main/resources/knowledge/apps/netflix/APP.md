---
name: netflix
display: Netflix
packages: ["com.netflix.mediaclient"]
aliases: ["Netflix", "netflix", "网飞", "奈飞"]
open: Netflix
description: Film and TV streaming with title search, episode selection and my list
---
## What it is
Netflix is a global film and TV streaming app, package name `com.netflix.mediaclient`. It offers online playback and offline downloads of movies, TV series, documentaries and anime.

## UI
On the first launch or when switching apps, the 「谁在观看? (Who's watching?)」 profile selection page often appears.
- **Home**: the 「剧集」 (Series), 「电影」 (Films) and 「分类」 (Categories) pills stay at the top, with the cast icon and the search magnifier at the top right. The featured blockbuster poster is shown at the very top; the body lists 「继续观看 (Continue Watching)」, popular picks, the 「今日 Top 10 榜单」 (today's Top 10) and 「我的片单 (My List)」.
- **Bottom navigation**: contains 「首页」 (Home), 「热门与最新 (New & Hot)」 and 「我的 Netflix」 (My Netflix, including downloads and the list).
- **Title detail page**: a trailer or poster image at the top, below it the title, release year, rating badge, number of seasons and resolution (HD/4K), immediately followed by two large buttons, the white 「播放 (Play)」 and the grey 「下载 (Download)」. The lower part of the detail page has the episode picker (a dropdown to choose the season), similar titles and cast and crew information.
- **Full-screen player**: tap the screen lightly to bring up the control bar: pause/play, rewind 10 seconds and forward 10 seconds in the middle; the progress bar at the bottom; the toolbar below offers 「锁定」 (Lock), the episode list, 「音频与字幕 (Audio & Subtitles)」, playback speed (0.5x to 1.5x) and the next episode.

## Features
- **Navigating the profile selection**:
  - If the app opens on the profile grid, tap the target user's avatar to enter that profile's home page. If the profile has a 4-digit PIN set, enter the PIN on the number pad to unlock.
- **Searching titles and genres**:
  - Tap the search magnifier at the top right and type the title in Chinese or English, or an actor's or director's name. Results are shown as a poster grid.
- **Choosing episodes and seasons**:
  - Scroll down to the 「剧集 (Episodes)」 area on a series page, tap the 「第 1 季」 (season 1) dropdown to switch to the target season, then tap a specific episode row to start playback.
- **Setting audio and subtitles**:
  - Tap 「音频和字幕」 (audio and subtitles) in the playback control bar to slide out the options panel, then check the target original audio track (such as English 5.1) and the target subtitles (such as Simplified Chinese). After setting them, tap apply at the top right.

## Known issues
- **DRM makes the screen capture black (FLAG_SECURE)**: because of digital copyright protection, the video playback area appears completely black in accessibility screenshots. **This is normal protection, not a loading failure or a crash**. Operating the controls has to rely on the view element tree and on-screen actions.
- **Leaving the playback page**: in full-screen playback, press the system back key or tap the left arrow 「←」 at the top left to leave the player and return to the title detail page.
- **Offline download limits**: some titles have no download button for licensing reasons; on mobile data a 「仅限 Wi-Fi 下载」 (Wi-Fi downloads only) notice may appear, which is system protection.
