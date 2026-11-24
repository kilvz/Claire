---
name: android-settings
display: Android Settings
packages: ["com.android.settings"]
aliases: ["系统设置", "手机设置", "Android Settings", "Settings", "设置"]
sites: []
open: 设置
description: Android system settings for managing network, display, permissions, accounts and system information
---
## What it is
The settings app that comes with Android, package name `com.android.settings`.

## UI
The home page is a list of categories with a search box at the top; tapping a category opens layered sub-pages and detail pages. The names, categories and levels of settings items differ with the system version and phone maker, and some makers add their own entries. The back button's text and appearance change from page to page; whether it returns to the home page is judged from the page title and structure.

## Features
- **Searching settings**: the search box at the top finds a setting directly by name.
- **Toggle settings**: Wi-Fi, Bluetooth, airplane mode, location and so on. A toggle's state is sometimes shown only by color and position, and the UI tree does not necessarily contain explicit 「开/关」 (on/off) text.
- **About phone**: model, system version and so on; the same page may also carry identifiers such as the serial number, IMEI and device name.
- **Permissions, developer options, accounts, security, system updates, install sources, reset**: these really change the device state once modified.

## Known issues
Tapping a toggle switches its state immediately, so when you only want to know the current state, read the accessibility properties or look at a screenshot — never rely on tapping.
