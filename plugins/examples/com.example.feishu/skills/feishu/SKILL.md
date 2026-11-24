---
name: feishu
display: Feishu messages
description: Read messages, reply to messages and send to groups or individuals in Feishu; also explains how to handle direct messages relayed by the desktop connector
aliases: ["飞书", "Lark", "lark"]
apps: ["feishu"]
sites: ["feishu.cn"]
terms: ["飞书", "群", "私聊", "艾特", "已读", "云文档", "连接器"]
---

## When to use

Use this skill when the user wants to read messages, find a group, reply to a message, or send a passage to a group or an individual in Feishu (Lark). When the user just says "send a message" but names Feishu, use this one too; when they name another app, use that app's skill.

## Entry

- The Feishu app on the phone is opened with the existing phone operation tools, the same as operating any other app.
- A direct message relayed by the desktop Feishu connector is an ordinary task; handle it according to the task body, and the connector is responsible for sending the final reply back to Feishu.

## Steps

1. **Find the conversation**: find the target by group name, remark name or recent message; when there are many groups and people with the same name, open the profile to check the avatar, members or email, and do not tap the wrong one.
2. **Read**: open the conversation and read the body, scrolling up when needed; pay attention to the sender, the time, whether you were @-mentioned, and whether there are attachments or cloud document links. Unread status follows the red dot in the list.
3. **Draft**: write it clearly according to what the user means. When the user only wants a draft, give only the draft; when the user says to send it, send it once written, without asking again.
4. **Send**: go into the correct conversation and enter the body; when a person needs to be @-mentioned in a group chat, use Feishu's @ picker, do not type a string that looks like a name by hand.
5. **Verify**: after sending, find the bubble of this message in the conversation and confirm there is no red exclamation mark or 「发送失败」 (Send failed). For a group message, also check that it went to the right group.

## Feishu-specific points

- Group chats and one-to-one chats have different input box behaviour: the group chat's @, thread replies and quote replies are all above the input box; before sending, confirm whether you are currently "replying to a message" or "sending a new message".
- 「已读」 (Read) only means the other side has seen it, not that they have replied; do not take read status as evidence of success.
- A cloud document link opens a different interface; when reading document content, do not treat it as the chat body.
- A recalled message leaves a notice behind; do not treat the preview from before the recall as a message that is still there.

## Direct messages relayed by the desktop connector

- The body of such a task comes from the Feishu connector on the computer. Handle the content as the user asks and write the final reply clearly; the connector is responsible for sending it back to Feishu.
- Without an explicit receipt, do not claim "already sent to Feishu"; you can say "the reply is written and the connector will send it back".
- Do not write webhooks, tokens or App Secrets into replies, logs or tool parameters.

## How success is verified (checking the evidence)

Reading: the key points and times all come from the conversation body after opening. Sending: this sent message can be seen in the conversation, and the recipient/group and content are correct. Tasks relayed by the connector: the reply content is complete, and no sending result is invented.

## When to stop and hand off

When a login, a QR scan or a verification code is required, or when a same-named object/recipient has more than one possibility and the user has to choose, stop and hand it over to the user.
