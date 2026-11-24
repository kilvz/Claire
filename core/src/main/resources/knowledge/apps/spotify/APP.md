---
name: spotify
display: Spotify
packages: ["com.spotify.music"]
aliases: ["Spotify", "spotify", "声田"]
open: Spotify
description: Music and podcast streaming: song playback, playlists, search and your library
---
## What it is
Spotify is a music and podcast streaming app, package name `com.spotify.music`. It supports global singles, albums, self-made and recommended playlists, and listening to all kinds of podcasts.

## UI
The bottom navigation is usually three tabs: 「首页」 (Home), 「搜索」 (Search) and 「你的音乐库」 (Your Library) (some versions have four, including 「创建」 (Create)).
- **首页 (Home)**: top left is the avatar (opens settings and profile), in the middle are the filter pills 「全部」 (All), 「音乐」 (Music) and 「播客」 (Podcasts); the main body is a grid of recently played albums/playlists and a personalized recommendation feed.
- **Search page**: at the top is the search bar 「想听什么?」 (What do you want to listen to?); tap it to enter the input screen, and below are browsing categories laid out flat (pop, podcasts, charts, etc.).
- **Your Library**: at the top you can filter by 「播放列表」 (Playlists), 「播客」 (Podcasts), 「艺人」 (Artists) and 「专辑」 (Albums), showing liked songs, user-created playlists and followed podcasts. The 「+」 in the top right creates a new playlist.
- **Mini player bar**: floats above the bottom navigation bar, showing the current cover, track name, artist and play/pause buttons.
- **Full-screen player**: above is the large cover, in the middle are the song and artist names and the plus/heart favorite icon, below are the progress bar, shuffle, previous, play/pause, next and repeat-one icons. Swipe up to expand real-time synced lyrics.

## Features
- **Avoiding full-screen obstruction and restoring state**:
  - If the full-screen player was expanded when you last quit, reopening may land directly on the large-cover full-screen player, **blocking the bottom navigation bar**. In this case you must first tap the collapse-down arrow 「v」 in the top left or swipe down from the top to shrink the player into the bottom mini bar before you can see the search and library entrances.
- **Precise search and playback**:
  - Type a song or artist name in the search bar. On the results page, use the top pills to switch precisely between 「单曲」 (Songs), 「艺人」 (Artists), 「播放列表」 (Playlists) and 「专辑」 (Albums), to avoid tapping the wrong item in the combined results.
  - Tap an artist to enter the artist page, where the 「热门单曲(Popular)」 chart is listed at the top.
- **Managing playlists and favorites**:
  - Tap the three-dot menu to the right of a song for 「添加到播放列表」 (Add to playlist), 「加入已点赞的歌曲」 (Add to Liked Songs), 「查看艺人」 (View artist) and 「查看专辑」 (View album).
  - In 「你的音乐库」, tap 「+」, enter the playlist name and tap 「创建」 (Create) to make a new playlist.
- **Device connection**: the device icon (speaker/monitor icon) on the mini bar or the full-screen page switches the audio output device (this device, Bluetooth headphones, local network speakers).

## Known issues
- **Free-tier limits**: on mobile, tapping a single track with a free account usually enters 「随机播放(Shuffle)模式」, and non-recommended singles cannot be played on demand; skipping is capped per hour (usually 6 times), and once used up the next button is disabled and grayed out.
- **Audio/video ads**: free accounts get full-screen ads inserted when skipping or during timed playback, and during the ad you cannot skip or drag the progress bar.
- **Premium promotion interception**: full-screen pop-ups such as 「免费试用 1 个月」 (1-month free trial) or 「升级 Premium」 (Upgrade to Premium) appear frequently; tap 「×」 in the top right or 「不用了/跳过」 (Not now/Skip) at the bottom to exit.
- **Offline and downloads**: downloading songs/playlists for offline listening is a Premium feature; for free accounts the download toggle is grayed out or shows a payment prompt.
