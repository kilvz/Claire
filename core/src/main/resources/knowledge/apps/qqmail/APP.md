---
name: qqmail
display: QQ Mail
packages: ["com.tencent.androidqqmail"]
aliases: ["QQ邮箱", "QQ Mail", "QQMail", "qq邮箱", "qqmail"]
sites: []
open: QQ邮箱
description: Send and receive mail, log in to several mailbox accounts at once, plus notepad, calendar and cloud file storage
---
## What it is
QQ Mail is an email app, package name `com.tencent.androidqqmail`. It can log in to several mailbox accounts at once and send and receive them together; it also includes contacts, notepad, calendar, cloud file storage and attachment management.

## UI
There is no bottom tab. The home page after opening is the 「邮箱」 (Mail) folder page:
- Top left is the avatar button (element name is 「返回」 (Back)), which opens the 「我的邮箱」 (My Mailboxes) page: account profile card (with 「编辑」 (Edit)), the 「我的账号」 (My accounts) list, 「添加账号」 (Add account) and 「设置」 (Settings).
- Top right is 「+」 (element name 「写邮件和设置等功能」 (Compose mail and settings, etc.)), which pops up a menu: compose mail, write a note, scan a file, settings, Time Mail.
- Below the title is a search box.
- The list is divided into several groups: 「常用文件夹」 (Common folders) (all inboxes, all unread mail, all sent mail, all starred mail), 「待发送」 (Outbox), 「我的邮箱」 (my mailboxes) (one row per logged-in account) and 「我的应用」 (My apps) (contacts, cloud file storage, attachment management, etc.). The unread count is shown on the right of the row.

## Features
- **Mail list**: tap a folder on the home page to enter it. At the top are back, the title 「全部账号」 (All accounts) (with a dropdown to switch which account to view) and a compose button; below is a row with a folder dropdown (such as 「全部收件箱」 (All inboxes)), 「未读」 (Unread) and 「管理」 (Manage). Each row has sender, date, subject and snippet; unread ones have a blue dot.
- **Search**: tap the search box on the home page to enter; at the top are the input box and 「取消」 (Cancel); below, 「搜索指定内容」 (Search specific content) lets you limit the search to mail, contacts, notepad, calendar, cloud file storage or documents.
- **Settings**: enter from the 「+」 menu or the 「我的邮箱」 page. Above are the logged-in accounts (each with 「升级」 (Upgrade)) and 「添加账号」; below are new mail notifications, general, mail, storage, a 「隐私」 (Privacy) group (personal information and permissions, personal information collection list, third-party information sharing list), gesture password and fingerprint, help and feedback.

## Known issues
- On the first open after a version update, several feature-introduction pages appear first, with 「跳过」 (Skip) in the top right.
- When the account login state expires, 「登录失效,请重新验证账号」 (Login expired, please verify your account again) pops up after opening, and the buttons are 「取消」 and 「设置」.
- Tapping the home page search box by element may not respond; tapping the position of the 「搜索」 (Search) text in the box enters the search page.
- The element list contains text that is not visible on screen: each folder row on the home page carries 「全标已读」 (Mark all as read) and 「首页不显示」 (Don't show on home page); each row of the mail list carries 「标为已读/标为未读」 (Mark as read/Mark as unread), 「拒收」 (Reject) and 「删除」 (Delete) (buttons revealed only by swiping left).
- At the very top of the inbox list there may be a promotion marked 「广告」 (Ad); it is not an email.
