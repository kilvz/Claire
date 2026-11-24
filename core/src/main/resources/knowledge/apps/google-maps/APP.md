---
name: google-maps
display: Google Maps
packages: ["com.google.android.apps.maps"]
aliases: ["Google Maps", "Google 地图", "谷歌地图", "maps", "Maps"]
sites: ["maps.google.com"]
open: Google Maps
description: Map navigation, route planning, place search and nearby exploration
---
## What it is
Google Maps is Google's maps and navigation app, package name `com.google.android.apps.maps`. For website knowledge see `maps.google.com`. It offers worldwide map browsing, place search, route planning by several travel modes and live voice navigation.

## UI
The top is the global search box 「在此处搜索 (Search here)」, with a microphone and the personal account avatar on the right. Below the search box are category shortcut pills (dining, gas, convenience stores, coffee, hotels and so on).
- **Map main controls**: the top right of the map has layer switching buttons (default, satellite, terrain, transit, live traffic), and the middle right has a compass and the 「定位到当前位置」 (locate to the current position) crosshair icon.
- **Bottom navigation**: 「探索 (Explore)」, 「出行 (Go)」, 「已保存 (Saved)」, 「贡献 (Contribute)」 and 「最新 (Updates)」.
- **Place info drawer**: after tapping a landmark pin on the map or picking a place from search, a half-screen detail card slides up from the bottom showing the place name, overall rating and review count, business status, category, directions, start navigation, call, save and share. Drag it up to expand the detailed opening hours, website URL, menu, user photos and the crowd popularity chart.
- **Route planning panel**: two input boxes at the top (the start defaults to 「你的位置」, your location; the end is the destination), with travel mode switching icons below (driving, transit/subway, walking, ride-hailing, cycling). Each mode lists recommended routes, estimated time, distance and traffic colors (green for clear, yellow for slow, red for congested).
- **Turn-by-turn navigation**: tap the green 「开始 (Start)」 to enter the full-screen dark HUD navigation; the top shows the next turn arrow and distance, the bottom shows the remaining time, estimated arrival time and distance, and on the right are the mute speaker, traffic search and a red exit 「退出 (X)」 button.

## Features
- **Avoiding leftover state and dismissing overlays**:
  - If the app starts while still on the large place card or a route panel from the last query, the map is covered over a large area. Tap the back 「←」 at the top left, or pull the bar at the top of the half-screen card down to collapse it, and global search is back.
- **Place search and reading details**:
  - Type a merchant, building or full address into the search bar and tap the target directly in the dropdown match list.
  - To see the opening hours and phone, swipe up to expand the place detail page and tap the 「营业时间」 (business hours) dropdown arrow to see the specific opening times for Monday to Sunday.
- **Comparing routes across modes**:
  - Tap the blue 「路线 (Directions)」 button to enter the planning page and tap the transit, walking or driving icon as the task requires.
  - In transit mode, tapping a specific option expands the detailed transfer stops, scheduled times, walking directions and fare information.
- **Starting and stopping navigation**:
  - Tap 「开始 (Start)」 to enter live simulated or on-device guidance; if you only need the route instructions without navigating, tap 「步骤与更多 (Steps)」 to see all the text steps.
  - **Leaving navigation**: in the HUD you must tap the cross 「X」 at the bottom left or bottom right and confirm 「退出导航」 (exit navigation); never run other unrelated tasks directly in the navigation flow.

## Known issues
- **Location permission and GPS prompts**: when precise location is not granted or system GPS is off, a low-level system dialog 「要继续,请开启设备位置信息」 (to continue, turn on device location) often pops up, and you need to tap 「确定」 (OK) or 「使用应用时允许」 (allow while using the app).
- **Overlapping pins and multiple targets**: searching in dense commercial areas brings up several red dot pins; swipe left and right on the bottom card to switch between and view the different results.
- **Offline map reminders**: with an unstable network or on the first long-distance route, it may suggest 「下载离线地图」 (download offline map); tap 「稍后」 (Later) to skip.
