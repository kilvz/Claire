# In-app realtime voice call · design (all in one go)

Date: 2026-11-22
Status: Pending approval
Research basis: in-session research notes (a close read of the whole PR chain of a reference implementation from the same source + a comparison with this repository's engine layer; the notes are not checked in, and all conclusions have already been folded into this spec)

## Intent and why

Talk to the assistant by realtime voice inside the app, **and get things done by voice**: full duplex, interruptible, hands-free, kept alive by a foreground service; what the user says goes through a strict safety gate into the existing task system, progress scrolls in the call panel, and the model restates the result; the call text is written back into the conversation.
The engine layer (`VolcRealtimeClient`/`CallPlaybackQueue`/`CallInputPacer`/`PcmRecorder`/`PlaybackTickets`/`CallVoiceProfileStore`) already exists in this repository and is newer than the reference implementation — **do not port the other client** (it is a fork of our old engine, so porting would be a downgrade); the additions are concentrated in the shell, the safety gate, routing and the UI, about 1,600–1,900 new lines of code + 600–800 lines of tests.
The user has already decided: no phasing, do it all at once; execute in worktree isolation.

## Acceptance (all are gates)

1. JVM all green: all safety-gate branches, state machine transitions, protocol frames (fake service), route decisions, transcript aggregation, echo guard, backpressure.
2. androidTest smoke: full call-page chain (connect→listen→play→interrupt→hang up→retry→limit) + permission-denied path + fake task execution routing.
3. **Real-device audio matrix (hard gate)**: speaker/earpiece/Bluetooth headset × interruption/echo; VOICE_COMMUNICATION availability and AEC; isSilenced; hot plugging; incoming-call interruption; focus stolen; lock-screen behavior; measured false blocks/misses of the echo guard.
4. **Full-chain real-device verification of getting things done by voice**: say one sentence→execute→status line→restatement; every anti-mis-execution measure (forged function_call, echo impersonating an instruction, cross-turn replay) does not take effect.

## Feature semantics (full UX chain)

- **入口**:聊天输入栏听筒按钮(与按住说话共存,进通话模式);悬浮胶囊菜单「语音聊天」显式 intent 直开。前置 = 实时语音凭据已配置(复用 AI 电话配置)+ 麦克风权限。系统数字助手入口与我们 IA 不同,本规格不做。
- **权限流**:通话面板里用户点按钮才请求 RECORD_AUDIO(不自动弹);拒绝后按钮变「去设置」跳应用详情页,ON_RESUME 重查;凭据缺失时按钮跳实时语音配置页。
- **接通**:Connecting(可取消)→ 等界面 RESUMED(3 秒未回前台报「请回到前台」)→ 麦克风型前台服务拿租约 → 申请音频焦点 → 开麦校验(首块 3 秒内非空且未被系统静音)→ 扬声器起播 → 本地两音提示音 → 每次会话只播一次问候语 → 「已连接」绿点 + mm:ss 计时 + 红色挂断钮。
- **对话**:用户说话 → 服务端 VAD 出转写 → 模型被提示词约束:对每段完整话语只调一个 `mika_request` 工具并沉默 → App 侧严格校验后交给既有聊天/工作入口执行 → 进度以短状态行滚动(最多 12 行,不含坐标/日程标题等细节)→ 结果作为 function result 回模型 → 模型只复述结果一次。面板有文字输入框,打字与说话同路。
- **打断(barge-in)**:用户一开口(服务端 speech_started)→ 播放队列代次作废 + AudioTrack pause/flush/play + 取消尚未完成的本地任务(不声称撤销已生效操作)。
- **免提**:Android 12+ `setCommunicationDevice`(有线/蓝牙耳机优先于听筒;没有私密听音设备时留扬声器),更老系统用 speakerphone 开关;选中态读系统实际通信设备,热插拔经 AudioDeviceCallback 刷新;挂断恢复原音频模式。
- **结束**:挂断钮 / 通知栏挂断 / 来电(MODE_IN_CALL/RINGTONE 或电话功能忙 → busy 错误)/ 静默挂断(通话里设定的分钟数没有新内容——没人说话、没人打字、Claire 没说话、任务没进展——就挂断,显示「X 分钟没有动静，通话已结束」,不自动重试;「通话方式」里可选 2 / 5 / 10 分钟、自定义 1–120 分钟或不自动挂断,默认 5 分钟;到点前 30 秒 Claire 先问一句还在不在,这句提醒本身不算新内容;没有总时长上限,AI 电话不受它管)/ 页面 ON_STOP 且不允许后台时收起。通话双方文字在 finally 里 NonCancellable 批量写回发起会话。
- **通话和对话的关系**(2026-11-24 定):对话是主链路,通话只是对话里的一段。
  - **聊天页**是完整的对话:通话里每说完一轮就存进当前对话(轮次带 `callId`),和打字的轮次一样一句句排开;这一段前后各一行浅色小字「📞 语音通话 · 9:41」「通话结束 · 3:12」(静默挂断的写「太久没说话」)。一句话没说的通话只留一行「语音通话 · 0:05 · 没有说话」,按接通时刻排进对话。
  - **通话页**只显示这一通:每次打开都从空白开始,顶部一行「上次通话 · 9:41 ›」点了回聊天里那一段。
  - **通话记录**:通话页右上角 ⋯ →「通话记录」列出这个对话的每一通(接通时刻、时长、没说话的注明),点一条回到聊天里那一通的开头并亮一下,不另开回放页;⋯ 里还有「通话设置」进「通话方式」。
  - 记录存在对话旁边的 `<对话>.json.calls`(接通时记一条、挂断时补上结束和原因),删对话一起删。
  - **通话页是普通的一页**:进页面栈,返回键 / 收起钮退到上一页,通话继续;此时其余每一页顶上有一条绿条「通话中 · 回到通话」和计时,点了回通话页。从通话页点任务卡进任务时间线,返回回到通话页。
- **错误 UX**:鉴权、连接超时、断连、限流、服务商错误、麦克风无输入、音频焦点失败、播放失败,各占一条独立文案 + 就地重试(attempt++ 重跑整个连接流程)。

## Audio pipeline and full duplex (established facts, implement accordingly)

Microphone AudioRecord (16k mono PCM16, 320 samples/20ms chunk) → WebSocket (realtime duplex service, STT+LLM+TTS all server-side) → downlink 24k mono PCM16 → ring playback queue → AudioTrack (USAGE_VOICE_COMMUNICATION / CONTENT_TYPE_SPEECH, MODE_STREAM, non-blocking writes). VAD is entirely server-side; the client does no local VAD and no push-to-talk. Uplink and downlink run in independent coroutines; recording uses a `Channel<ShortArray>(100)` for backpressure, accumulating at most 5 seconds during connection setup, and overflow fails outright rather than silently dropping samples; uplink 20ms frames are paced to absolute deadlines; the playback queue is capped at 20 seconds (#695 lesson), and a single downlink packet is ≤1 second. Prompt tone: locally synthesized 720/960Hz sine, 65ms each with a fade-in/fade-out envelope, written directly to the playback track and not counted in the level meter.

## Component list

All new code follows generic AOSP design: public API capability detection + fallback chain, with no special cases for any device model; code/comments/docs/commit messages do not contain any external project names.

1. **`voice/PcmRecorder`(+~15,改)**:`inputSource`(通话传 VOICE_COMMUNICATION 触发平台 AEC/NS,初始化失败回退 MIC)、`chunkSamples`、`isSilenced` 读数(能力检测,不支持恒 false)。现有语音输入路径行为不变。
2. **`calls/VolcRealtimeProtocol`(+~80,改)**:`mikaActions` 配置项、`mika_request` 工具注册帧、`functionResult` 帧、`synthesize` 帧(朗读复用)、`response.done` 事件解析。
3. **`calls/VolcRealtimeClient`(+~60,改)**:`VolcRealtimeConfig.agentTools`、`sendFunctionResult(callId, text)`、`synthesize(text)`、`VolcRealtimeEvent.ResponseDone`。
4. **`voicecall/VoiceCallSession`(~350,新)**:通话编排,**显式枚举状态机**(Connecting→MicCheck→Active→Ended/Error;禁布尔拼接,`ensureActive()` 区分外部取消):RESUMED 等待、焦点、开麦校验(3s 首块 + isSilenced + VOICE_COMMUNICATION→MIC 回退)、提示音、问候语一次、播放 writer(复用 CallPlaybackQueue)、打断(队列作废+pause/flush/play+通知路由取消任务)、静默挂断(见上)、8 类错误文案 + 就地重试、回声守卫(播音时与助手已说内容 500 字环形缓冲互为子串的「用户转写」丢弃,阈值可调)。
5. **`voicecall/VoiceCallRequest`(~120,新)**:执行安全闸——只有「当轮、final 且 complete」的用户转写能喂执行;`utterance` 参数与转写做归一化**单向**包含校验——utterance 必须落在当轮转写里,比转写还长(模型添了用户没说的话)一律拒(不匹配不消费该转写);callId 一次性(含重连重放);助手回声剔除;转写每轮一次性消费。按语义自行重写。
6. **`voicecall/VoiceCallRoute`(~200,新)**:语音请求→既有聊天/工作任务入口的一次性目的地路由(先到先得);订阅 TaskState 出短状态行;任务需确认/接管时提示「去任务页看」;打断取消已启动未完成任务;结果回 `sendFunctionResult`。
7. **`voicecall/VoiceCallTranscript`(~50,新)**:事件→Turn(问/答/时间)聚合,结束写回发起会话。
8. **`voicecall/VoiceCallService`(~130,新)**:麦克风型前台服务 + CATEGORY_CALL ongoing 通知(含挂断动作);`select` 竞速通话协程与通知挂断;Android 14+ mic FGS 只能前台起;锁屏不开通话页。
9. **`voicecall/VoiceCallAudioRoute`(~120,新)**:免提路由双路径 + 选中态读系统实际设备 + 热插拔刷新 + 挂断恢复;决策抽纯函数供 JVM 测。
10. **`voicecall/ui/VoiceCallPage`(原 VoiceCallOverlay)**:页面栈里的一页(`Route.VoiceCall`,2026-11-24 起不再是全屏 Dialog),不透明深色;右上角 ⋯(通话记录 / 通话设置,`VoiceCallRecords`);其余页面顶上的绿条在 `VoiceCallBar`;中央呼吸球随双向电平起伏(**自己画**,复用 VoiceWave/Breathing 语言,不引入第三方粒子引擎);状态行滚动区(≤12 行)+ mm:ss + 挂断 + 免提 + 折叠 + 文字输入框。
11. **入口(各 ~30,改)**:输入栏听筒按钮进通话模式;胶囊菜单「语音聊天」直开。
12. **提示词约束**:session instructions 让模型对每段完整话语只调一个 `mika_request` 并沉默、只复述结果一次;进 prompts 资源。
13. **字符串**:约 40 条新 key × 12 个目录(values + 11 locale),问候语本地化,不硬编码。

## Execution safety (the core a port must preserve)

The model can forge a `function_call` — **only a current-turn complete transcript may trigger execution**: final+complete validation, parameter-transcript compatibility check (on mismatch not even the transcript is consumed), single-use callId to prevent reconnection replay, echo removal to prevent the assistant's voice from impersonating an instruction, single consumption of each turn's transcript to prevent duplicate execution. Better to refuse execution than to execute wrongly.

## Test strategy

- JVM: all VoiceCallRequest branches (echo/compatibility/single-use/cross-turn), all state machine transitions (retry attempt++, external cancellation vs internal failure), protocol frame construction and parsing (MockWebServer fake realtime service: tool registration/downlink audio/function_call/interruption), route decision pure functions, transcript aggregation, backpressure overflow, error→copy mapping.
- androidTest: call page smoke (injecting a fake runCall full chain), permission-denied path, fake task execution→status line→function result.
- Real-device matrix + full get-things-done chain: see acceptance 3, 4; record each item in the PR description.

## Risks and mitigations

- **音频路径设备差异(最大)**:AEC 质量、AudioTrack 假死、isSilenced、setCommunicationDevice 兼容性——能力检测 + 回退链,真机矩阵是验收门槛。
- **回声自激固有残留**:平台 AEC 失效时守卫是概率性的(≥4 字子串启发式,可能误拦用户重复助手的话);保守方向优先(播音时降低执行敏感度),阈值真机调。
- **权限与政策**:RECORD_AUDIO 运行时权限;后台持续录音必须挂麦克风型 FGS(Android 14+ 只能前台起);退后台 FGS 保活 + 通知挂断。
- **耗电发热**:持续 16k 采集 + 24k 播放 + 长连;音频线程 `THREAD_PRIORITY_AUDIO`;静默挂断兜底(没人用时不会一直开着;默认 5 分钟)。
- **服务端计费/限流**:按时长计费;错误文案区分「可重试」与「去配置」。
- **凭据分用途**:通话读默认实时语音档案;试听/朗读书面凭据走错 API 的坑已知晓,按用途分开存取。
- **多播放源叠音**:进程内互斥 + 代次令牌拦迟到回调(PlaybackTickets 已是正解)。
