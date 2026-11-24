---
name: wechat
display: WeChat
packages: ["com.tencent.mm"]
aliases: ["WeChat", "weixin", "微信", "wechat"]
sites: []
open: 微信
pace: careful
description: Chat, official accounts and social
---
## What it is
微信 (WeChat) is a chat and social app, package name `com.tencent.mm`.

## UI
The main structure is usually the chat list, contacts, discover and personal pages; the exact layout varies with version, account state and font settings. The top search returns contacts, group chats, chat history, mini programs and official accounts at the same time, with results shown by group.

## Features
- **Conversations**: unread markers, pinned chats, drafts, service notifications and ordinary messages are all in the list and easy to confuse. A contact's display name, remark name, group name and avatar may be similar; only the title after entering a conversation is the actual object. Text in the input box is only a draft; only after sending is there a bubble from you in the conversation. In group chats, each message has a speaker.
- **Official accounts**: content is separate from personal conversations.

## Known issues
- The chat list and chat page are custom-drawn interfaces, and on some device models the accessibility tree may not read content (`treeQuality=unusable`): elements are empty, and screen reading, tapping, typing and scrolling are all unavailable, so you can only fall back to "screenshot + visual coordinate location" to tap. This is related to WeChat turning off 「无障碍模式」 (Accessibility mode) by default.
- Turning on WeChat's 「无障碍模式」 (Accessibility mode) can significantly improve readability: open the accessibility-related options under 「我 → 设置 → 通用 → 无障碍」 (Me → Settings → General → Accessibility) in WeChat (menu names may differ slightly between versions). After turning it on, the controls on the list and chat pages can basically all be read.
- When the tree is empty and tapping has no response, first confirm that the window/page is the right one and that you are not stuck on a system pop-up (such as the 「选择要使用的应用」 (Choose an app to use) dual-app selector on some models), instead of repeatedly retrying the same position with different coordinates.

## Common flows
- **Posting to Moments**: from the bottom 「发现」 (Discover) enter 「朋友圈」 (Moments), choose photos with the camera in the top right, or long-press the camera to post text only; the menu names at each step before publishing are in `references/send-moments.md`.
