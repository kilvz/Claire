---
name: slack
display: Slack
packages: ["com.Slack"]
aliases: ["Slack", "slack"]
open: Slack
description: Workspace communication and collaboration: workspaces, channels, DMs and threaded replies
---
## What it is
Slack is a workspace communication and collaboration app for enterprises and teams, package name `com.Slack`. Content is isolated by workspace (Workspace), and inside it is organized by public/private channels, direct messages (DM) and threads (Thread).

## UI
The bottom navigation is usually four tabs: 「主页」 (Home), 「私信」 (DMs), 「动态/提及」 (Activity) and 「你」 (You) (some versions include a Catch Up to-do).
- **Home and the channel drawer**: top left is the current workspace avatar and name (tap or swipe left to bring up the workspace switcher and settings); in the middle or on the right is a search bar or magnifier; below are categories: unread messages, threads (Threads), public channels (prefix `#`), private channels (prefix a small lock 🔒), externally shared channels and canvases (Canvases).
- **Channel chat page**: the top shows the channel name, member count, search and a headphone icon (Huddle, ad-hoc audio chat); the middle is the message timeline; the bottom is the message input box, which supports quick input, `@` members, emoji reactions and attaching files with 「+」.
- **Thread reply page (Thread)**: enter by tapping 「X 条回复」 (X replies) below a message in a channel. The top is labeled 「Thread」 and the name of the channel it belongs to, on the right is a follow bell; the bottom input box has an 「同时发送到 #频道」 (Also send to #channel) checkbox.

## Features
- **Confirm the current workspace and channel**:
  - Users may be in several enterprise or community workspaces at once. When opening the app, always first check the workspace avatar in the top left and the current channel title. If the target channel is in another workspace, tap the top-left avatar to bring up the sidebar and switch to the target workspace.
- **Thread replies (Thread)**:
  - Team communication emphasizes replying inside the topic thread. Long-press a message or tap the reply count below the message card to enter the Thread. Type in the input box and send; if unnecessary, **never tick 「同时发送到 #频道」 (Also send to #channel)**, to avoid flooding the public channel.
- **Sending messages and rich text**:
  - The input box supports bold, italic, strikethrough, code blocks and bulleted lists. Use `@` to mention specific members or `@here`/`@channel` to notify everyone.
- **Searching for history**:
  - The global search supports syntax filters: `in:#channel-name` limits the channel, `from:@member` specifies the speaker, and `has:link` finds links.

## Known issues
- **Leftover state and deep threads**: after quitting and reopening you often land directly in a deep Thread or a DM chat, so the home channel list is not visible. Tap 「←」 in the top left to return step by step to the parent level.
- **Accidentally starting a Huddle**: the headphone toggle in the top right of a channel is an instant voice room; tapping it immediately turns on the microphone and broadcasts an entry tone, so never turn it on casually.
- **Permission and archive restrictions**: a channel archived by an admin shows 「This channel has been archived and is read-only」 at the bottom, and no new content can be entered.
- **Two-factor authentication and SSO interception**: on first use or when the token expires, a browser or authenticator may be brought up for SSO login authentication.
