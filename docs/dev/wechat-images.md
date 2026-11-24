# Sending and receiving WeChat images

The WeChat self private chat channel accepts text, images and multiple images within the same message, and can send back images explicitly exported by a task. Images still belong to the original channel task and do not create a new run state machine.

## Receiving

`WechatMessage.userContent()` merges the text content and preserves the original order of the image list. If a message containing only a text item quotes an image, the image is taken from `ref_msg.message_item` and imported together with the current question. If the message itself carries attachments, only the current attachments are processed; if it only has a quote ID, the original image is looked up among the 512 most recently registered source records for the same WeChat binding and conversation. The original message is registered by `message_id` (or by the first non-empty item-level `msg_id` when that is missing), and the quote is matched by `svr_id` (or by the quoted item's `msg_id` when that is missing). On import, the path, size and SHA-256 are saved atomically; after a restart the file is verified before being handed to the task. If the original image is missing or has changed, ask the user to resend it. `WechatChannel` first validates the currently bound self private chat, and checks the message deduplication ledger before downloading; it does not accept group messages. `WechatApi.downloadImage()` downloads from the WeChat CDN and decrypts according to iLink's AES-128-ECB / PKCS#7 rules.

The host `StoredWechatImages` stages the images one by one and, after Android decode bounds checks and any necessary scaling, calls the existing `Attachments.withTaskFiles` to import them into the current thread workspace. Attachments still use the `AttachmentLines` format, so new tasks, follow-up answers, the persistent queue and `read_file` all reuse the existing path. When the main model does not support images, `read_file` explicitly reports the capability limit according to the existing logic.

After the whole batch imports successfully, first write atomically to the WeChat pending ledger, then hand it to the task, and finally advance the cursor. Replaying pending items uses the original workspace paths and does not download again; the message is acknowledged only after the task is persistently registered. A download or import failure writes a separate failure notification, and the message does not degrade into a text task missing its images. Old imports during pause, rebinding or stop are rolled back for the files in that batch.

Mika's local resource limits: at most 8 images per message and at most 10 MiB per downloaded image; these are client limits, not limits of the WeChat server. Inbound images must be decodable by Android, with at most 64 MiPixels of original pixels and bounded sampling during decode; images handed to the task are at most 5 MiB with a longest edge of 4096 pixels. Plain PNG/JPEG/WebP keeps its original bytes when no adjustment is needed; GIF is imported as a static image. WeChat media references and keys are not written into the task prompt, and CDN requests carry no Bot authentication.

## Replying

Screenshots the user asks to be sent back can be saved as PNG files in the workspace using the `savePath` of `phone_screenshot` / `web_screenshot`, and then exported by calling the existing `share_file`. A main model without vision can also save screenshots; screenshot protection and screen access gating are the same as for the original tools.

`TaskService` picks images from the real `SharedFile` export records of this run and does not parse arbitrary file paths out of the final answer. `ResultDelivery` first persists the text and a separate delivery record for each image, then sends them item by item. `WechatChannel` only reads MediaStore export URIs, verifies the size and existing SHA-256, then calls `getuploadurl`, uploads the encrypted images, revalidates the binding state, and sends `image_item` through `sendmessage`.

A failed upload is recorded as Failed; when the image message has already been sent but the response is uncertain, it is recorded as Uncertain. Recovery sends only Pending items and does not resend Delivered or Uncertain items; a failure or uncertainty on any image never marks the whole batch result as fully delivered. Each image itself is at most 10 MiB; valid image formats are checked by content signature.

## Verification entry points

- Core: `WechatImagesTest` verifies encryption interoperability, CDN upload and download, media request construction, authentication isolation, size limits and cancellation; `ScreenshotExportTest` verifies screenshot saving and protection.
- App JVM: `WechatImageFilesTest` and `WechatImageChannelTest` verify real file import, message replay, failure notifications, binding changes and image replies; `ResultDeliveryTest`, `FileOutboxStoreTest` and `RunRecoveryTest` cover separate delivery and restart recovery.
- Device: `WechatImagePreparationTest` checks Android image decoding and sampling. Without a device, this test is only compiled, which does not mean it actually ran.
- WeChat joint debugging: send a single image in the self private chat, send text plus multiple images and have Mika read them; request a screenshot and send it back; pause/rebind during download; test failure and restart recovery. This needs a real binding and a phone; MockWebServer tests do not replace this acceptance.

The protocol basis is the Tencent official plugin [Tencent/openclaw-weixin](https://github.com/Tencent/openclaw-weixin/tree/0ad1ae91347a938f52f1e4e2abd6dcda51e05f9e): its [protocol document](https://github.com/Tencent/openclaw-weixin/blob/0ad1ae91347a938f52f1e4e2abd6dcda51e05f9e/docs/protocol_zh_CN.md), upload/download and image message construction implementation. Mika interoperates with its own Kotlin client, with no new OpenClaw runtime dependency.
