---
name: discord
display: Discord
packages: ["com.discord"]
aliases: ["Discord", "discord", "DC", "开黑"]
open: Discord
description: Community and voice/text chat with servers, channels, DMs and friends
---
## What it is
Discord is a community communication app, package name `com.discord`. Content is organized by server (Guild) and channel (Channel), and it also supports direct messages (DM) and voice calls.

## UI
The bottom navigation usually has four tabs: 「服务器」 (Servers), 「私信」 (DMs), 「通知」 (Notifications) and 「你(个人资料)」 (You, your profile) (some versions have a separate search tab).
- **Server and channel drawer**: the server list on the left (the DMs entry at the top, joined server icons below, and 「+」 (Add) and 「探索」 (Explore) at the bottom). Tapping a server expands its categories and channel list (#text channels, 🔊 voice channels, 📢 announcements, 💬 forums).
- **Chat interface**: the top shows the current channel name (`#channel-name`) or the DM recipient, with the back/drawer hamburger button on the left and search, notifications and the member list on the right. The bottom is the input area: the attach file 「+」, Nitro gift, GIF, stickers, and the text input box 「发消息至 #...」 (Message #...).
- **DMs page**: search and a 「新私信」 (New DM) button at the top, showing friends' online status and recent DM conversations.

## Features
- **Telling where you are (don't be misled by leftover state)**:
  - Opening the app often restores the screen from when you last quit (such as a deep channel, thread or DM on a particular server).
  - **Don't post unrelated messages or search for unrelated things in the channel you happen to be on**. To send a DM, first switch to the 「私信」 (DMs) tab at the bottom; to go to another server, first tap the back/drawer button at the top left to return to the server list, then switch to the target.
- **Finding and sending channel messages**:
  - Pick a text channel in the server's channel list and enter it.
  - Type the text into the bottom input box and tap send. To reply to a specific message, long-press that message and choose 「回复」 (Reply).
- **How search scope differs**:
  - The search bar at the top of a channel defaults to the current channel (`in:#...`) or the current server.
  - To search people or servers globally, use the search/explore entry in the bottom navigation.
- **Voice channels**: tapping a voice channel pops up a preview panel, and only tapping 「加入语音」 (Join Voice) connects; a green call bar then stays at the top, and tapping the hang-up button at the top right disconnects.

## Known issues
- **Leftover state**: quitting and reopening stays on the last channel. To run a new task, always look at the top title and the back button at the top left first, go back to the top level and then navigate.
- **No permission to speak**: in read-only channels (such as announcements and rules) the bottom input box changes to 「你没有在当前频道发送消息的权限」 (you do not have permission to send messages in the current channel) and is greyed out; never try to type there.
- **Slow mode**: a countdown bar shows below the input box, and messages cannot be sent continuously until the countdown reaches zero.
- **New server rules confirmation**: right after joining a new server, a prompt often appears at the bottom saying 「在发言前需要完成几个步骤」 (you need to complete a few steps before speaking); tap 「完成」 (Finish) and accept the rules to unlock typing.
- **Forums and threads**: a forum channel requires tapping a specific post topic first, and only then can you reply inside the post.
- **Full-screen image/file picker**: when tapping 「+」 opens the media picker, the back key usually closes the picker first.
