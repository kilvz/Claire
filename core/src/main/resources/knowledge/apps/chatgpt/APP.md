---
name: chatgpt
display: ChatGPT
packages: ["com.openai.chatgpt"]
aliases: ["ChatGPT", "chatgpt", "OpenAI"]
open: ChatGPT
description: OpenAI's official conversational AI, with text Q&A, voice conversation and multimodal image recognition
---
## What it is
ChatGPT is the AI assistant app released by OpenAI, package name `com.openai.chatgpt`. It provides natural language conversation, text creation, code answers, image recognition and real-time voice conversation.

## UI
The main interface is extremely simple and divided into three main areas:
- **Top bar**: the hamburger menu of two horizontal lines on the left (tap to expand the side list of past conversations and settings), the current model switcher pill in the middle (such as 「ChatGPT」 or a specific model label), and the new-chat icon (a pencil icon) at the top right.
- **Side drawer**: slides out from the left, with a box for searching chat history and 「新建聊天 (New Chat)」 at the top, past conversation titles grouped by time in the middle, and the personal account information and settings gear at the very bottom.
- **Main conversation window**: the message Q&A stream in the middle (the user's question bubble on the right, ChatGPT's replies on the left with copy, retry, read-aloud and rating controls).
- **Bottom input bar**: the 「+」 attach button (camera, pick an image from the album, upload a document or search), the headphone icon (enters real-time Advanced Voice Mode), the text input box 「发消息 (Message)」 and the up-arrow send button on the right.

## Features
- **Starting a new conversation and switching history**:
  - Tap the pencil icon at the top right to clear the current screen and start a brand-new topic.
  - Open the left drawer and tap a target title in the history list to return to a past conversation and keep asking.
- **Typing and asking**:
  - Type the question into the bottom input box and tap the send arrow on the right. While an answer is being generated the button turns into a black square (the stop-generating icon); tap it to interrupt the answer.
- **Multimodal image recognition**:
  - Tap 「+」 to the left of the input box and choose 「相机 (Camera)」 to take a photo on the spot, or choose 「相册 (Photos)」 to pick a saved screenshot, then add a note in the input box and send.
- **Advanced Voice Mode**:
  - Tap the headphone icon to enter the full-screen animated voice-ball mode, where you talk to the AI in real time through the microphone without typing. Tap the circle microphone at the bottom to mute, and tap 「×」 at the bottom right to leave voice mode and return to text chat.

## Known issues
- **Advanced voice covers the whole screen**: after entering voice mode the text interface is hidden behind the black animated background, and text is only readable again after tapping 「×」 to exit.
- **Model quota notices**: if an advanced reasoning model is used frequently or the account is unpaid, the interface may show 「已达到该模型的限制,已为您切换至普通模型」 (the model's limit has been reached, so you have been switched to the standard model).
- **Sign-in state checks**: on the first launch or when authorization expires, 「继续使用 Google」 (Continue with Google), 「使用 Apple 登录」 (Sign in with Apple) or an email prompt pops up; this is the usual sign-in security check.
