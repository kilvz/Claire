# In-app real-time voice call · on-device acceptance checklist

Date: 2026-11-22
Scope: the `worktree-voice-call` branch (design in `docs/design/specs/2026-11-22-voice-call-design.md`).
How to use: run through each item on a real device; tick `[x]` when it passes, and record the symptom plus model/Android version when it fails. **Any single failure means this version does not pass acceptance.**

Preconditions: real-time voice credentials configured in Settings (the same ones as for AI phone calls); microphone permission granted.

## 1. Connecting and the basic chain

- [ ] 输入栏话筒旁的听筒按钮能进通话页;胶囊菜单「语音聊天」也能进
- [ ] 接通过程:球是网形态 → 转「已连接」绿点,计时开始走
- [ ] 首次会话听到问候语,且**只播一次**(同一通内不再重播)
- [ ] 本地「叮咚」两音提示音在问候语之前响
- [ ] 说话有波形反应(球变波浪形态)
- [ ] 助手说话时球随其声音起伏
- [ ] 挂断:红色挂断钮 → 落「已结束」,计时停在最终时长,底部输入/免提/挂断消失,只剩左上收起
- [ ] 点收起或按返回 → 回到上一页,通知栏仍有一条通话通知(带挂断),通话继续;每一页顶上有绿条「通话中 · 回到通话」+ 计时,点了回通话页
- [ ] 收起后再返回,不会又弹回通话页
- [ ] 通知栏挂断 → 通话结束,页面若开着也收掉

## 2. Audio routing matrix (every item must be tried)

- [ ] Speaker: with no headset plugged in, the default loudspeaker works normally, without echo explosions in either direction
- [ ] Earpiece: holding it to the ear sounds normal (if there is a hands-free toggle, switching over and back both work normally)
- [ ] Wired headset: plugged in before connecting = sound comes straight from the headset; plugging in mid-call switches to the headset automatically; unplugging switches back automatically
- [ ] Bluetooth headset: connecting switches to Bluetooth automatically; disconnecting switches back automatically
- [ ] Hands-free button: both on and off genuinely change the output device (the selected state matches the system's actual device)
- [ ] After hanging up the audio mode is restored (music/video volume and output path for loudspeaker playback return to what they were before the call)

## 3. Interruption and echo

- [ ] The user cuts in while the assistant is speaking → the assistant stops immediately and the old audio no longer resurfaces
- [ ] Interrupt 5 times in a row, and never once does "the second half of an old sentence suddenly play"
- [ ] Quiet room: the assistant's speech misdetected by VAD as user transcription → triggers no execution at all (echo guard)
- [ ] Room at maximum loudspeaker volume: same as above, holds

## 4. Interruptions and abnormal cases

- [ ] 来电(MODE_IN_CALL/RINGTONE)→ 通话以「来电」结束并说明
- [ ] 其他 App 抢音频焦点(如放视频)→ 按设计降级或结束,不出现双声道叠音
- [ ] 断网 → 落「连接中断」,就地重试能重新接通且 attempt 增加
- [ ] 静默挂断(设 2 分钟)→ 一直不说话:第 90 秒左右 Claire 问一句还在不在;接着不理,第 2 分钟挂断并显示「2 分钟没有动静，通话已结束」,不自动重试;中途说一句、打字或任务有进展都重新计时
- [ ] 锁屏:通话继续(前台服务保活);**锁屏不会自己弹出通话页**
- [ ] 系统静音/被别的 App 占用麦克风 → 报「麦克风无输入」类文案,不是静默卡住
- [ ] 退到后台再回来:页面状态、计时、状态行都还在
- [ ] 转屏:通话**不掉线**(MainActivity 已声明 configChanges),页面布局正常
- [ ] 系统深色/浅色切换、字体缩放、分屏:主界面即时重绘正常(全 App 行为变更,顺带确认)

## 5. The full voice task-execution chain

- [ ] 说「明天早上八点叫我起床」→ 状态行滚动进度 → 闹钟真被订上 → 助手复述结果
- [ ] 办事期间状态行最多 12 行,滚动不糊
- [ ] **状态行不含隐私细节**:不出现坐标、日程标题、联系人姓名、具体文本内容
- [ ] 任务需要用户确认/接管(如要密码、要人操作)→ 提示「去任务页看」,不假装已办完
- [ ] 办事中打断 → 已启动未完成的任务被取消(已生效的操作不声称撤销)
- [ ] 面板文字输入框:打字发出去与说话同一条路
- [ ] 通话里每说完一轮,聊天页就多一轮(问/答/时间),挂断后不重复写
- [ ] 聊天页里这一段前后有「语音通话 · 时刻」「通话结束 · 时长」两行浅色小字;一句没说就挂的只有一行「语音通话 · 时长 · 没有说话」
- [ ] 再打开通话页是空白的,只显示这一通;顶部「上次通话 · 时刻 ›」点了回到聊天里那一段
- [ ] 通话页右上角 ⋯ →「通话记录」列出这个对话的每一通;点一条回到聊天里那一通开头并亮一下;⋯ →「通话设置」进通话方式
- [ ] 通话页点任务卡进任务时间线,返回回到通话页

## 6. Guarding against wrong execution (safety gates; all of these must not take effect)

- [ ] The model fabricates a `function_call` (with no complete user transcription in that round) → not executed
- [ ] Replaying the same `call_id` across rounds → not executed
- [ ] A `function_call` whose parameters do not match that round's transcription → not executed, and that transcription is not consumed
- [ ] The assistant's own voice taken as a user instruction → not executed
- [ ] One complete utterance is executed only once (no duplicate orders/duplicate messages)

## 7. Failures and gates

- [ ] Credentials not configured: the call page shows "Configure real-time voice first" + a go-to-configure button, which opens the configuration page when tapped
- [ ] Microphone permission not granted: a button in the page requests permission; after a refusal it changes to "Go to settings", and coming back from system settings connects directly
- [ ] The provider returns a rate-limit/auth error: each has its own wording, auth ones guide the user to configuration, rate-limit ones can be retried

## Record

| Item | Model | Android version | Result | Notes |
|---|---|---|---|---|
|  |  |  |  |  |
