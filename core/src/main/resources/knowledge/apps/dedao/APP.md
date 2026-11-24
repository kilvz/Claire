---
name: dedao
display: Dedao
packages: ["com.luojilab.player"]
aliases: ["得到", "Dedao", "dedao", "得到App"]
sites: []
open: 得到
description: Knowledge courses, audiobooks, e-books and a learning community
---
## What it is
Dedao is a knowledge learning app, package name `com.luojilab.player`. It has paid courses, audiobooks, e-books, live streams and a learning community.

## UI
Bottom tabs: Home, Listen & Read, Knowledge City, Study, and the profile page at the far right (it shows 「未登录」 (Not signed in) when signed out). Most tabs have a search box at the top, with credits, recent learning, messages or settings icons on the right.
- **Home**: to the right of the search box are growth tasks / credits-for-gifts, recent learning and the message center; below are channels such as Recommended, Courses, Long talks and the AI learning circle (activity channels are sometimes inserted); the Recommended page has campaign banners, a row of entries (Dedao charts, Dedao courses, Listen & read, Dedao credits, All categories and so on), column content and live stream previews.
- **Listen & Read**: at the top My membership, Audiobooks, E-books and so on; there is an audiobook membership sign-up bar, new book arrivals, masters explaining books, museum treasures, knowledge maps and category entries, with daily new arrivals and major new books below.
- **Knowledge City**: community posts, split into Videos, Following and Recommended; each one has the author, time, body text (which can be 「展开」 (expanded)), reposts, comments, likes and shares.
- **Study**: your own learning content; when signed out it only has 「登录 / 注册」 (Sign in / Sign up).
- **Profile page**: avatar (tap to sign in/sign up), study credits, today's study, medals, certificates; 「我的账户」 (My account: account balance, cards and coupons, orders, cart, membership, event tickets, credits); 「我的内容」 (My content: notes, recent learning, downloads, favorites/lists, subscription notices); invite friends. The gear at the top right is settings.

## Features
- **Search**: tap the search box at the top to enter. There is an input box, a voice button and 「取消」 (Cancel); below are search history (with delete), then rankings under the tabs Hot search, Discover, Courses, Audiobooks and E-books.
- **Settings**: account/binding settings, address management, security center, personal identity verification, privacy settings, background playback optimization, night mode, message notifications, one-tap note taking, software update, add home screen widget, version notes, clear cache, about us.

## Known issues
- When opened while signed out it first shows the phone-number sign-in page (send verification code, password sign-in, WeChat sign-in); the back arrow at the top left can skip it, and you can browse without signing in.
- After opening, a full-screen campaign ad often appears, with a close button at the bottom (resource ID `close`, no name).
- The first time you switch to some tabs a guide tip may appear (such as 「最近在学移到这里啦」, your recent learning moved here), blocking taps; dismiss the tip first, then switch.
- The words in the search box rotate, and element names change with them, so tapping by element may report that the element has expired; tap by position instead.
- Most icons on the top right (credits, recent learning, settings) have no name and only a resource ID, such as `settingImageView` for settings.
- The bottom tabs do not show in the elements which one is selected; look at the screen.
