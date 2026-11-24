---
name: gmail
display: Gmail
packages: ["com.google.android.gm"]
aliases: ["Gmail", "谷歌邮箱", "Google 邮箱", "gmail"]
sites: ["mail.google.com"]
open: Gmail
description: Google's mail app for sending and receiving mail, organizing by category and label, with meetings too
---
## What it is
Gmail is Google's mail app, package name `com.google.android.gm`. Other mail accounts can also be added and managed alongside.

## UI
Two tabs at the bottom: 「邮件」 (Mail) and 「会议」 (Meet).
- **Mail**: the menu button (「打开抽屉式导航栏」, open the navigation drawer) at the top left, the search box 「搜索邮件」 (Search mail) in the middle, and the avatar (「账号和设置」, accounts and settings) on the right. The list starts with the current inbox name (such as 「主要」, Primary), then summary rows for categories such as 「动态」 (Updates) and 「推广」 (Promotions) (with new mail counts), and then the messages; the 「写邮件」 (Compose) button is at the bottom right.
- **Drawer**: inbox categories (Primary, Promotions, Social, Updates); under 「所有标签」 (All labels) are Starred, Snoozed, Important, Sent, Scheduled, Outbox, Drafts, All mail, Spam, Trash, Manage subscriptions, Create new and self-created labels; under 「Google 应用」 (Google apps) are Calendar and Contacts; 「设置」 (Settings) and 「帮助和反馈」 (Help & feedback) are at the very bottom and only visible after scrolling down.
- **Meet**: 「发起新会议」 (Start a new meeting) and 「加入会议」 (Join a meeting).

## Features
- **Mail list**: one row per message, and the element name joins 「未读」 (unread), the sender, the subject and the snippet together; the date and the star are separate elements. Tapping the sender's avatar selects this message rather than opening it.
- **Search**: tap the search box at the top to enter; it has a back button, an input box and filter buttons: label, from, to, attachment.
- **Account**: tap the avatar to open the account page: manage your Google Account, switch account, add another account, manage accounts on this device, storage usage.
- **Settings**: entered at the very bottom of the drawer; the first level is 「常规设置」 (General settings), one row per account, and 「添加帐号」 (Add account).

## Known issues
- On the first launch or after an update, these appear one after another: the 「Gmail 换新颜」 (Gmail has a new look) introduction page (「知道了」, Got it), the page for managing all email addresses (「转至 GMAIL」, Go to Gmail), the system notification permission prompt (「禁止」, Don't allow; 「始终允许」, Always allow), and the card introducing the meeting feature (「了解详情」, Learn more; 「知道了」, Got it), and there is also a hint card (「关闭」, Close) at the top of the list.
- The system notification permission prompt cannot be read from the elements and cannot be closed with the back key, so you can only tap the buttons on screen.
- The first time you enter the 「会议」 (Meet) tab, 「允许Gmail获取手机状态」 (allow Gmail to access phone state) pops up (「取消」, Cancel; 「授予权限」, Grant permission), and it cannot be read from the elements.
- The account page cannot be read from the elements; look at the screen.
- While the drawer or the search page is open, the mail list behind them is still in the element list, and the drawer's items come after the messages.
- Pressing the back key in the mail list exits the app.
