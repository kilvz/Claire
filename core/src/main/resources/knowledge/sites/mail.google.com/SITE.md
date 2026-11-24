---
name: mail.google.com
display: Gmail
description: Email search, reading, composing and labels in the Gmail web version
domains: ["mail.google.com"]
aliases: ["Gmail", "Google Mail", "谷歌邮箱"]
apps: ["gmail"]
open: mail.google.com
mode: desktop
---
## What it is
Google's webmail, which requires signing in to a Google account.

## Pages and URLs
Entry `https://mail.google.com/`. Interface: a search box at the top; on the left, Compose plus Inbox, Sent, Drafts, Spam, Trash and custom labels; in the middle, the message list. The interface language follows the account settings, so button names are not necessarily in English. Appending `#search/<编码后的查询>` to the current account's address searches directly, and the account index in the address (such as `u/0`) corresponds to the currently signed-in account. See `references/gmail-search-syntax.md` for the search syntax.

## Features
- **List and search**: each row has the sender, subject, date and unread state, but no body. The list is virtualized: as you scroll down, the rows above are removed, and the number of rows scrolled past is not the total number of messages in the mailbox.
- **Message body**: opening a message shows the sender, subject, body, time and attachments. Opening a message may change its unread state.
- **Compose, reply, forward**: use Compose to write a new message; to reply or forward, first open the corresponding message. When replying, the recipient and subject are filled in automatically. After sending, the message appears in Sent; a closed compose window does not mean the message was sent.

## Known issues
Signing in to a Google account and 「确认是你本人」 (Confirm it's you) are two different verification steps.
