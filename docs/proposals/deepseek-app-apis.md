# DeepSeek 2026-11-13 应用 API 套件 — 可采用性评估

> **状态：提案，部分实现。** 本文回答"2026-11-13 DeepSeek 新开放的这批 API 里，哪些能接进 inhuman、以什么边界接、哪些不接"，不是当前行为。
> **2026-11-21 更新：ASR 与 TTS 已实现**（`DeepSeekSpeechApi` + `SpeechBackend`，见 `docs/03-ai-provider.md` 的媒体后端一节和 `docs/14-documents-voice.md` §6）；OCR 仍待产品决策，服务端 web tools、Files API、safety_check 仍不接。实现后的结论以 `docs/03`/`docs/14` 为准。
> 采纳后：ASR/TTS 结论并入 `docs/03-ai-provider.md` 的媒体后端一节和 `docs/14-documents-voice.md` §6；若做 OCR，还要先补
> `docs/14-documents-voice.md` §2/§8 和 `docs/13-product.md` 里"扫描件算不算第一批"的结论；`docs/08-port-philosophy.md` §1.2/§1.4 是判断依据。

---

## 0. 结论先行

| 2026-11-13 的新能力 | 能不能用进 inhuman | 判断 |
| --- | --- | --- |
| **ASR** `POST /v1/tools/asr` | **能，建议第一批** | 作为 Speech/Transcription 的一个新付费后端；录音路径（16 kHz 单声道 PCM WAV、≤120 s）与它的输入限制几乎逐项对齐 |
| **TTS** `POST /v1/tools/tts` | **能，建议第一批（先试听）** | 同上；输出 WAV 而非 MP3，两个音色，音质需要和现在的 Qwen TTS 对比后再定默认 |
| **OCR** `POST /v1/tools/ocr` | **有条件，建议第二批** | 扫描件/图片/PPT 的 opt-in 付费读取；它把**整份原始文件**发给 DeepSeek，属于"app 代发的付费外部读取"，要单独披露，且先要回答产品问题 |
| 服务端 **web tools**（`deepseek_web_search` / `deepseek_read_url`） | **不接** | DeepSeek 自己写的是"给 chatbot 用，agent 用 Search & Fetch API"；我们已经是后者。接进来会把"什么时候上网"从用户的工具清单挪进模型，和 §1.2/§1.4 的治理模型冲突 |
| **Files API / 文件解析**（上传 → `file_id`） | **不接** | 文件留在远端、每轮用 `file_id` 引用，和"文档是工作区里的本地文件、转换不联网"直接冲突；本地读不了的格式用 OCR 是更小的口子 |
| **`safety_check`**（模型内容安全） | **不接** | 面向公开服务的合规开关；会审输入、输出和工具结果，加延迟和 token 账单，还会在流中途拒绝。本产品是个人工具，不是公开服务 |
| `title` / 开源 demo chat client | 无关/暂不 | `title` 是可选的小功能不是工具；demo client 只是参考实现 |

**一句话**：这三个独立 API（ASR/TTS/OCR）是"app 代发、按量计费、只读/生成"的能力，和现有付费媒体工具、B 站只读同形，可以接；另外三个"聊天全家桶"方向（服务端 web tools（含 URL reading）、Files API、safety）是给 chatbot 的，接进来改的是能力边界和治理模型，不动。

---

## 1. 这次发布的是什么

- 2026-11-13 的 [Change Log](https://api-docs.deepseek.com/updates/)：把 DeepSeek App（含网页版）的会话能力开放为 API——**服务端联网搜索 / 深度思考搜索 / 图像搜索、文件输入、URL 读取、模型内容安全**，并把 **Search & Fetch、OCR、ASR、TTS** 作为独立 API 开放，另开源一个 demo chat client。
- Search & Fetch 其实在 2026-10-17 就已开放，本仓库已经接入（`docs/03-ai-provider.md:839`），所以这批里真正的新东西是：**服务端 web tools、Files API、OCR、ASR、TTS、safety_check**。
- 价格（[Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing.md)）：Search/Fetch $0.75/千次成功请求；文件解析与 OCR $0.225/千页；TTS $0.45/万计费字符（限时 5 折）；ASR $0.116/小时（限时 5 折）。**2026-11-18 12:00（北京时间）起，模型侧触发的服务端搜索/抓取不再单独计费**，结果按输入 token 计费（`pricing.md` 的说明）。
- 注意：这些能力大多明确写"用 `deepseek-flash`"或只描述对话场景；它们不是"给 agent 的通用工具"，官方对 agent 的指引仍是 Search & Fetch（[Web Tools](https://api-docs.deepseek.com/guides/web_tools.md) 开头第 3–5 行）。

---

## 2. 逐项判断

### 2.1 ASR — 推荐第一批（已实现）

> **2026-11-21：已实现。** `core/ai/…/provider/deepseek/DeepSeekSpeechApi.kt` 与 `speech/SpeechBackend.kt`；DeepSeek kind 现在声明 `Speech`，`speak`/`transcribe` 和语音输入共用同一后端解析。实现说明见 `docs/03-ai-provider.md` 的媒体后端一节。

**它能干什么**：`POST https://api.deepseek.com/v1/tools/asr`，`multipart/form-data` 的 `file` 字段；只收 **16-bit PCM WAV 或 Ogg Opus**，≤8 MiB、**≤120 秒**；21 种语言，无语言参数（自动检测）；整段返回文本和 `duration_ms`；$0.116/小时（限时 $0.058），按秒计费；单账号最多 50 个在途请求。

**为什么它和现在的录音路径几乎逐项对齐**：
- 录音是 16 kHz、单声道、16-bit PCM 的 WAV（`app/…/ui/chat/WavPcm.kt:13,15-42`），上限 120 秒（`VoiceRecorder.kt:129-130`），字节数约 3.84 MB < 8 MiB。格式、声道、采样率、时长、体积全部满足，不需要转码。
- 现有转写已经是"把音频字节交给媒体后端，拿回文本"：`ChatViewModel.transcribe(relativePath)` / `transcribeVoice(wav)`，经 `SpeechBackend`（`core/ai/…/speech/SpeechBackend.kt`）到具体实现；实现时把原来的 `QwenTranscribeTool` 换成了与后端无关的 `TranscribeTool`。
- 语音输入本来就把"转写"当作一个**付费媒体后端**披露（`docs/14-documents-voice.md` §6）；换成 DeepSeek ASR 不增加任何新的能力类别：录音仍只在内存、转完即丢、只把文本送进输入框、绝不自动发送。

**限制与要处理的地方**：
- **不收 MP3**。`transcribe` 工具接受 mp3/wav/opus，但后端可以拒绝（`SpeechBackend.acceptsFormat`）。DeepSeek 后端对 mp3 必须明确拒绝并说明原因，不能静默换后端（"选中的后端不能做"要说出来）；wav 和 Ogg Opus 可以直传。实现后 `TranscribeTool` 和 `ChatViewModel.transcribe` 都是这样做的。
- **120 秒上限**。工作区里的长音频要么按 WAV 头部切段（纯本地、可测），要么在工具结果里说明超限；Opus 不做切分。录音路径本来就 ≤120 s，不受影响。
- **没有模型参数**。现有 `MediaBackend` 只有一个 `model` 字符串，DeepSeek 的 ASR 用不到它；后端的差异应该由"后端类型"表达，而不是把模型 ID 当开关。
- **不要复用聊天客户端的 90 秒 header 超时**。`DeepSeekProvider.shared` 装了 `HeaderTimeoutInterceptor`（`DeepSeekProvider.kt:341-346`，测的是"响应头之前"），搜索/抓取复用它没问题；ASR/OCR 是"上传完才给响应"的接口，最长可能超过 90 秒。新客户端要么用自己的 OkHttp 实例，要么显式关掉这个拦截器。这是 `docs/07-gotchas.md` 里那条 11 分钟故事的同一类坑，别再踩一次。

**怎么接（实现方向）**：
- 把 `MediaBackend(client: QwenTokenPlanClient, model)`（`app/…/ui/chat/MediaTools.kt:146-150`）收窄成真正按能力服务的小接口，例如 `SpeechBackend.transcribe(...)` / `synthesize(...)`；图片/视频/decision 继续用 Qwen 客户端，不受影响。
- `ProviderCapabilities.of("deepseek")` 现在只给 Chat（`core/ai/…/capability/ProviderCapabilities.kt:20-26`）。给 DeepSeek kind 加 `Speech`，媒体设置里的"Assistant / Provider"两种模式就能选到它；`MediaMode.Direct` 仍按 Qwen 协议解释，不为它硬编码 URL 猜测。
- 新增 `DeepSeekSpeechApi`（`core/ai/…/provider/deepseek/` 下，与 `DeepSeekSearchApi`/`DeepSeekFetchApi` 并列），只做 ASR/TTS 两个端点。密钥不新增规则：媒体后端本来就在 `SecureStore` 里按能力存一份配置；`Assistant` 模式沿用当前对话服务商的 key（和 Qwen Token Plan 的 one-key 设计一致），`Provider`/`Direct` 模式用对应 profile/填写的 key。是否复用 `web.deepseek.api_key` 是待定问题（见 §5），不要默认是同一把。
- 披露保持不变：`transcribe` 已是 `paid = true`、`workspaceBound = true`（`core/ai/…/tools/ToolCatalog.kt:292-298`），`speak` 同样（`ToolCatalog.kt:383-387`）；设置文案说"由所选后端按音频时长/字符计费"，**不要硬编码某家价格**（价格会变，§1.4 明确禁止）。

### 2.2 TTS — 推荐第一批，先做一次试听（已实现）

> **2026-11-21：已实现。** 与 ASR 同一提交；输出扩展名跟随后端（Qwen `mp3` / DeepSeek `wav`），音色对比仍是后续的体验决定。

**它能干什么**：`POST /v1/tools/tts`，JSON `{text, voice_id?, format?, filter_markdown?}`；`voice_id` 只有 `stella`（女，默认）和 `tide`（男）；`format` 是 `wav`（默认）或裸 PCM，**没有 MP3**；文本最多 10,000 个计费字符（一个汉字算 2），请求体 ≤64 KiB，单次最多 30 分钟音频；会做内容审查，失败返回 `400 Content Exists Risk`；$0.45/万计费字符（限时 5 折）。WebSocket 流式是可选项，当前场景不需要。

**和现状的差异**：
- 实现前 `speak` 工具和"菜单 → 文字转语音"都把结果硬编码成 `generated/audio-*.mp3`；`GeneratedAudio` 已经带 `mimeType`。实现时改成扩展名跟随后端（Qwen `mp3` / DeepSeek `wav`），"Open/Play" 用结果里的 MIME 打开，"转写生成的音频"按钮回送 WAV（两条后端都收）。
- **音色只有两个**：现在的 Qwen TTS 默认 `longanhuan_v3.6`。DeepSeek 的中文音质/自然度没有实测，**先分别合成同一段中英文听一遍再决定默认**；如果不如现有后端，就把它保留为可选项而不是默认。
- 计费单位是字符不是 token；本地应先按"汉字 ×2 + 其他 ×1"检查 10,000 的上限，超限在工具里直接拒绝（免费、可读），不要发出去等 400。
- 内容审查拒绝是 TTS 自己的失败，不是对话的失败：作为工具错误返回一句可读的话，不要说成"工作区写失败"。

**披露**：和 ASR 同类。`speak` 已是 `paid`、`mutates`、`workspaceBound`；音频是工作区副本，可回退，没有新增权限。

### 2.3 OCR — 有条件，建议第二批

**它能干什么**：`POST /v1/tools/ocr`，`multipart` 的 `file` + `purpose`（`text_recognition` 或 `rich_layout_recognition`）；支持 PDF、DOC/DOCX、PPT/PPTX 和常见图片；≤100 MiB、≤1000 页、总时长 180 秒、单账号 15 个在途；按**成功页数**计费 $0.225/千页；返回 `pages[].text`，按页带 `<｜ref｜>`/`<｜box｜>` 这类定位标记和 0–1000 归一化坐标，`finish_reason: length` 表示该页被截断。官方说明：**文件不进入 Files API、也不会交给聊天模型**（[OCR guide](https://api-docs.deepseek.com/guides/ocr.md) 开头）。

**为什么值得考虑**：
- `docs/13-product.md:274-281` 把"扫描件、拍照件（没有文本层的 PDF）算不算第一批"留成了待定项；现在的答案是 `read_pdf_vision`——把最多 4 页渲染成 JPEG，交给**当前对话的视觉模型**（`PdfVisionTool.kt:30-50,164-181`，`docs/14` §2）。这条路对"一次读几十页扫描件"既慢又贵，而且要求聊天的模型能看图。
- OCR 一次能覆盖更多页、对模型没有视觉要求、按页单价低，还能读 PPT/PPTX（本地文档第一批没有 PPT）和图片。

**为什么它必须先过产品决策**：
- `read_pdf_vision` 发出去的是**渲染后的页面图片**，去的是用户已经选定的对话服务商；OCR 发出去的是**整份原始文件**，去的是 DeepSeek 的 OCR 服务。两者都不是"助手自己联网"（请求仍由 app 发，agent 没有网络、没有 URL 参数、没有远端写），但后者是新的付费数据路径，必须按"在选它的地方和使用它的地方都看得见费用与去向"披露（§1.4 的同一把尺），并 `optIn`。
- `docs/14` §1/§8 明确拒绝了"云转换"，并把 OCR 排除在第一批之外（"加本地 OCR 引擎仍是单独决策"）：理由是文档离开设备。OCR 不是转换（不写文件、不落远端存储、不回传文档），但它确实把文档内容送出去，所以不能悄悄作为 `read_document` 的 fallback——那会变成"文档工具背着用户上网"。
- 如果做，建议**单独一行工具**（例如 `read_scanned`/`ocr_document`），`paid = true`、`optIn = true`、`workspaceBound = true`，而不是把 OCR 藏进 `read_pdf_vision` 的后端选择里。第一次调用就要让用户看见"文件会发给 DeepSeek 的 OCR 服务、按页计费"；`ToolCatalogTest` 的付费集合、助手编辑器/审批行/结果行的 `PAID` 徽标按现有机制走。
- 参数与边界：只读工作区文件、页范围可指定、单次页数有上限（例如 ≤10 页）、单文件字节有上限、结果里剥掉 `<｜/box｜>` 定位标记（那是给裁剪/定位用的，进上下文是噪音）、明确报告 `page_count`/截断页；密码保护、格式不支持、超时、限流分别给可读错误，非瞬态错误不重试。180 秒的请求同样要避开 90 秒 header 超时（见 2.1）。
- 工作量：比 ASR/TTS 大（新工具 + 新设置项 + 披露文案 + 页面截断/标记清洗 + 离线 fixtures + 真机测试）；在"扫描件到底重不重要"没有答案之前，不建议先写代码。

### 2.4 服务端 web tools（`deepseek_web_search` / `deepseek_read_url`）— 不接

**它是什么**：在 Messages / Responses / Chat Completions 的 `tools` 里声明 `{"type": "deepseek_web_search"}` / `{"type": "deepseek_read_url"}`，**模型自己决定**是否搜索、是否读用户消息里的链接；搜索/打开页面由服务端执行，答案带引用。深度思考搜索在一次请求里最多约 20 次工具调用，支持图像搜索；多轮必须把上一轮的 `server_tool_use`/`web_search_tool_result`/`server_tool_calls`（含 `signature`）原样传回，否则之前读过的页面不再进上下文。

**不接的理由**（按重要性排序）：
1. **能力边界和治理模型对不上。** 这条路径没有"工具行"：不在助手的工具清单、对话工具表、审批表、缺口报告里，也不会有 `PAID` 徽标；什么时候上网由模型决定，而不是用户选中的工具。§1.2 要求能力能被一句话说清，§1.4 要求在"选它的地方和使用它的地方"都看得见费用——这个形状没有那两个地方。要接，先得在 `docs/08` 里给一个新的例外/披露设计，而不是加一个设置。
2. **官方文档自己划了界。** Web Tools 开头：这些工具是给对话/chatbot 的；在 agent 或自己的代码里搜索、抓取，请用 Search & Fetch API（`web_tools.md` 第 3–5 行）。本仓库已经按后者的形状做了 `web_search`/`web_fetch`（`docs/03-ai-provider.md:839-971`）。
3. **真正的增量很小。** 现在拿不到的主要是**图像搜索**（Search API 还不支持，见 `search.md` 第 12 行）和"深度思考搜索"的多步质量；后者用现有工具多调几次也能做，而且每次调用可见、可审批、可打进账单。图像搜索目前没有产品场景。
4. **工程成本不低。** 请求侧要在 `DeepSeekRequest` 的 tools 里加 server tool 对象；流式侧要在 `DeepSeekStreamDecoder` 里处理 `server_tool_event`、最后一个 chunk 的 `server_tool_calls` 和 `annotations`；持久化要给 assistant 消息新增字段（Room schema 迁移）并在下一轮原样回放；UI 要渲染搜索步骤和引用；还要一个助手级/会话级开关。搜到的内容按输入 token 计费（11-18 起搜索本身免费），也就没有"每次付费"的徽标位置。
5. **边角语义要单独处理**：用 web tools 的回复不能走 prefix 续写（400）；`tool_choice` 不能强制 server tool；`server_tool_calls` 丢了请求仍成功但上下文丢页面。这些都要在引擎/适配器里写清楚。

**什么时候再看**：产品决定要有"无工具聊天模式"或"服务商级别：服务端联网搜索（含图像搜索）"的那一天。到那天先补 `docs/08` §1.2/§1.4 的例外，再谈实现。

### 2.5 Files API / 文件解析 — 不接

**它是什么**：先把文件 `POST /files` 上传并按 `purpose` 解析，拿 `file_id`；聊天请求里用 `file_id` 引用，解析后的内容进上下文、跨轮保留；原始文件和解析结果都不能下载；单请求最多 20 个文档、文本合计 ≤8 MiB；文件 ≤100 MiB/1000 页；txt/xlsx/epub 这类走 `user_data`，上传不收费。

**不接的理由**：
- 它把"文档是工作区里的文件"改成"有些文档存在 DeepSeek、用 id 引用"。工作区围栏、配额、`LossReport`、回滚检查点、备份/导出都假设文档在本地（`docs/14` §1/§3，`docs/04`）；引入远程文档等于在这套边界上开一个洞，收益只是"本地读不了的格式也能问"。
- 本地已经有 `read_document`/`write_document`/`convert_document`（PDF/DOC/DOCX/XLS/XLSX/CSV），而 PPT、扫描件、图片这类缺口用 §2.3 的 OCR 就能覆盖，且不需要远端存储、不需要一个新的文档生命周期。
- 上传不收费不等于没成本：解析结果按输入 token 计入每轮请求，和本地读出来再发给模型没有本质差别。

### 2.6 `safety_check`（模型内容安全）— 不接

**它是什么**：三个协议都支持的请求参数 `safety_check: {level: "high"}`；审查模型输入（含 `tool_result`）和输出；输入不过返回 `400 Content Exists Risk`，输出不过以 `refusal`/`content_filter` 结束；**已经流式输出的文字收不回来**；审查 token 按输出 token 计费；服务端会在生成完后保持连接做审查。

**不接的理由**：它是给"对公众提供服务"的合规开关（官方原文如此），不是个人客户端的功能。接入后：输入会被拒（可能包括用户自己的材料）、流会中途变拒绝（现有 UI 只期待文本/工具调用，要加拒绝态）、账单上涨、延迟增加；而且它会审查 `tool_result`，等于给工具结果加了一层内容策略。本产品的内容策略是"工作区 + 工具 + 审批 + 用户选服务商"，不是平台级审查。如果将来要公开发布，再按公开发布的要求重新评估。

### 2.7 其余

- **`title`**：`message_delta` 里可以顺带生成一个会话标题。本仓库的标题现在是创建时的 fallback、之后由用户改（`ConversationsViewModel.kt:192-196`），没有自动命名；它可以作为自动标题的一个可选来源，但需要 provider 级开关，现在不做，等有明确的命名体验问题时再说。
- **`latest_reminder`**：不适用。
- **开源 demo chat client**：只作参考；不引入第二个聊天运行时。
- **Search & Fetch 本身**：已经接（`docs/03-ai-provider.md:839`）。11-18 的"服务端搜索/抓取不再单独计费"只影响未来若接 server tools 的成本模型，对现有直连 Search/Fetch 的 `PAID` 披露没有影响（直连仍按成功请求计费）。Search API 仍不支持图像搜索，这是唯一值得记住的缺口。

---

## 3. 若做第一批（ASR + TTS），要守住的约束

- **能力不扩张**：请求仍由 app 发出，agent 仍然没有网络、没有 URL/主机参数、没有远端写；音频来自内存（录音）或工作区（`transcribe`），结果进输入框或落工作区副本。它们和 B 站只读、付费媒体工具同形，不应改变 §1.2 的那句话。
- **没有隐藏 fallback**：选中的后端做不了（MP3、>120 s、字符超限）就拒绝并说明，不要悄悄换回 Qwen；"工具在、但总是失败"和"偷偷换后端"都会让模型和用户看错发生了什么。
- **付费披露不降级**：现有 `PAID` 徽标、计费说明、结果行徽标全部保留；设置里显示"当前后端 + 是否已配置"，文案按服务计费，不写死价格。
- **密钥规则不变**：媒体后端按能力存一份配置（`MediaTools.kt:132-144`），`Assistant` 模式沿用对话服务商的 key（Qwen 的 one-key 设计），`Provider`/`Direct` 模式用对应配置；DeepSeek kind 加 `Speech` 只是让这两种模式能列出它，**不改变** §1.4 里 web tools 的"chat key 不授权付费 web 工具"规则，也不默认复用 `web.deepseek.api_key`。
- **超时/取消**：新客户端不要复用带 `HeaderTimeoutInterceptor` 的 `DeepSeekProvider.shared`；取消要能关掉 socket；TTS 流式响应的"中途断流=失败"要当失败处理（`tts.md` 明确说截断即失败）。
- **测试**：离线的 multipart/JSON 请求形状与解析 fixtures；`INHUMAN_*`/`DEEPSEEK_LIVE_KEY` 门控的真服务测试沿用 `DeepSeekWebLiveTest` 的模式；录音的 WAV 头、120 s 上限已有测试可复用；TTS 的音质只能人工试听，测试只能钉住"字节是 WAV/失败可读"。
- **文档**：`docs/03-ai-provider.md` 的"Media backends"一节、`docs/14-documents-voice.md` §6、`docs/13-product.md` 的已做/未来列表，在同一个提交里更新。

---

## 4. 建议顺序

1. **ASR 后端**：价值最高（只配一个 DeepSeek key 就能用语音输入、格式天然匹配、比现有链路便宜），工作量最小。**已实现（0.12.0）。**
2. **TTS 后端**：同一套抽象，试听后决定是否设为默认。**已实现（0.12.0）。**
3. **OCR**：等"扫描件/拍照件算不算第一批"的产品答案；如果做，单独一行 opt-in 付费工具 + 完整披露，不做隐藏 fallback。
4. **不做**：server web tools、Files API、safety_check。

## 5. 待定问题

- DeepSeek 的 ASR/TTS 要不要复用 `web.deepseek.api_key`（一个 "DeepSeek tools" key），还是像现在这样按能力各存一份？前者少一次输入，后者保住"付费媒体后端按能力独立配置"的整齐（也避免把 web tools 的 key 语义扩散到语音）。
- DeepSeek TTS 的中文音质是否达到 Qwen 的水平；如果不如，是否仍提供（便宜、英文/Cantonese 覆盖更好）？
- 扫描件需求的真实频率：没有它时 `read_pdf_vision` 够不够用？如果够，OCR 可以一直不做。
- 图像搜索/深度思考搜索是否值得一个 provider 级例外？我的判断是不值得，但这是产品决定，不是技术限制。

## 6. 参考

- 发布说明：<https://api-docs.deepseek.com/updates/>（2026-11-13、2026-10-17）
- Web tools：<https://api-docs.deepseek.com/guides/web_tools.md>
- 文件解析：<https://api-docs.deepseek.com/guides/document_parsing.md>
- OCR：<https://api-docs.deepseek.com/guides/ocr.md>
- ASR：<https://api-docs.deepseek.com/guides/asr.md>
- TTS：<https://api-docs.deepseek.com/guides/tts.md>
- 内容安全：<https://api-docs.deepseek.com/guides/safety.md>
- Search & Fetch：<https://api-docs.deepseek.com/guides/search.md>
- 价格：<https://api-docs.deepseek.com/quick_start/pricing.md>
