# 工作期间的用户消息（排队 / 插入）— 设计提案

> **状态：已实现（2026-11-25）。** 排队与"插入下一次调用前"在同一次交付里落地：
> `ChatUiState.queued` + `TurnQueue.kt`、`EngineTurn.beforeRound`、schema v26 的
> `messages.injected`，以及所有 turn 判定改走 "turn root"（`fromUser && !injected`）。
> **未在真实账号上验证**四家 provider 的"工具结果后跟用户消息"（结构上合法，见 §1.5）。
> 当前行为以 `docs/06-agent-loop-ui.md`、`docs/07-gotchas.md` A29、`docs/04-data-persistence.md`
> 和 `docs/13-product.md` 为准，本文保留为设计与决定记录。
>
> 实现时改：`docs/01-overview.md`、`docs/06-agent-loop-ui.md`（回合生命周期与并发一节）、
> `docs/07-gotchas.md`（新增条目）、`docs/13-product.md`（对话与回答）、`docs/11-conversation-tools.md`
> （如果 B 落地，重申"插入不改变回合工具清单"）、`AGENTS.md`（Continue 不加用户消息那条旁边加限制）。

---

## 0. 结论先行

- **A 排队**：输入框保持可用，发出的消息进一个**可见的队列**，当前回合结束后自动作为下一轮发送。
  不改引擎、不改 schema、不新增能力；成本 S。它和 `docs/06-agent-loop-ui.md:191` 拒绝的
  "并发额度排队"不是同一件事——那条是第三个**并发回合**不排队；这里队列必须明示在界面上。
- **B 插入**：在工具组执行完、下一轮请求发出前，把用户文本拼进请求。wire 上四家 provider 都
  表达得出来，但**必须落库**，否则记录和模型看到的内容会不一致；这需要 schema v26 加标记列，
  并改 5 处 turn 判定。只有"这个回合还有下一次调用"时才可能插入，且未在四家真实账号上验证。
- **两条共同的硬约束**：工作期间发的消息**不得**改变本回合的工具、审批、SSH 授权、下载授权；
  工具清单仍然只在回合边界由用户改（`docs/11-conversation-tools.md`）。
- 如果 B 的成本现在不想付：**停止并插入**（先停当前回合，已收到的回答照常保存，再作为下一轮
  发送）是 S 成本的 80% 替代，代价是中断工具循环。

---

## 1. 现状（代码事实）

### 1.1 输入被禁用

`ChatScreen.kt` 的 `inputEnabled = enabled && !isStreaming && !isCompacting`，输入框、
麦克风、附件都跟着禁用；流式时发送键的位置换成停止键。所以今天工作期间用户能做的只有：
停止、暂存图片、滚动阅读；模型、工具表、导出等入口也都被拒。

### 1.2 没有队列

`ChatUiState` 里只有 `input`（草稿）和 `pendingAttachments`，没有待发送队列。草稿本身
**不落库**（`ChatViewModel.buildWireHistory` 的注释写明 "the draft has never been stored"），
进程死亡时未发送的文字本来就丢——这一点决定了 A 的队列放在内存里是与现状一致的，不是新增丢失。

发送入口 `ChatViewModel.send()` 在流式中被 `isStreaming` 门挡回；`TurnConcurrency` 的第三个
并发回合是"拒绝而不是排队"。注意两者都不是"同一对话里工作期间发的消息"。

### 1.3 回合模型："user 行出现在 turn 中间"有五个破点

现在一个 turn 没有字段，取的是"最后一个 user 行起的后缀"：

| 位置 | 现有假设 | 一条 user 行插进 turn 中间会怎样 |
| --- | --- | --- |
| `ChatViewModel` 的 `lastUser` 相关函数 | 最后一个 user 行就是本回合根 | 根被前移，之前的行被排除出 rewind/regeneration 判定 |
| `TurnSummary.kt` | 遇 `fromUser` 即切新 turn | 一条插入消息会被画成两个回合，footer/wire 按钮错位 |
| 回合跳转列表（`ChatSearch.kt`） | 每个 user 行算一个回合 | 列表里多出一个不存在的回合 |
| `isInterruptedTurn` | 尾行是 user 就算中断 | 崩溃后尾行是插入消息，误出"继续" |
| `WireHistory.closeDanglingCalls` | 连续 `Role.Tool` 扫描 | 若插在 tool_calls 与工具结果之间，合成结果排到 user 之后，OpenAI 系顺序非法 |

起点检查点按 user id 建目录，插入消息没有、也不该有起点检查点（它发生在工具组之后，
工作区状态由本回合根的检查点覆盖）。这几处就是 B 落地时真正的工作量，不是 wire 本身。

### 1.4 引擎的注入点

`ChatEngine.run` 每一轮用**引擎私有的可变 history** 造 `ChatRequest`，host 只在
`RoundStarted` 事件里拿到 request 的副本，没有任何时刻能改请求；工具执行完把
`Role.Tool` 追加进 history，然后进入下一轮。所以 B 要在"工具组执行完 → 下一轮请求前"
加一个钩子（`EngineTurn` 增加 suspend 回调 + 一个事件让 host 落库并渲染）。

`CONTINUE_INSTRUCTION`（wire 上追加的 system 消息）、`KICKOFF`（请求最前面的用户起始行）、
`TimeHints`（user 内容前缀）、`closeDanglingCalls`（合成的工具结果行）都证明"wire-only 文本"
有先例；但它们**不落库**，而用户自己说的话不能走这条路。

### 1.5 wire 上工具结果后面能不能跟用户文本

| provider | 工具结果的位置 | 追加用户文本的方式 | 状态 |
| --- | --- | --- | --- |
| DeepSeek / OpenAI 兼容 | 独立 `role:"tool"` 消息 | 后面跟一条独立 `role:"user"` | 结构合法，未实测 |
| Anthropic | user 消息里的 `tool_result` block | 追加到同一 user 消息 `tool_result` 之后（adapter 合并相邻同 role） | 结构合法，未实测 |
| Gemini | user content 里的 `functionResponse` part | 追加到同一 `content.parts`（adapter 合并相邻同 role） | 结构合法，未实测 |
| OpenAI Responses | 独立 `function_call_output` item | 再放一个 `message` item | 结构合法，未实测 |

四家都没有在真实账号上测过"tool 之后紧跟 user"；`docs/03-ai-provider.md` 已记录部分 adapter
未连真实账号验证。这是 B 的验收项，不是设计缺陷。

---

## 2. 方案 A：排队到当前回合结束

### 2.1 语义与交互

- 流式中输入框保持可用；点发送 = 入队，不打断当前回合。
- 输入框上方显示一条可见的队列（例如"将在当前回复结束后发送 · 1 条"，可展开、可编辑、可取消）。
  **必须明示**：否则就是"用户以为已经发了"的误导（这正是 `docs/06` 拒绝隐藏队列的理由）。
- 队列上限建议 5 条；超出时提示"结束当前回复后再发"而不是静默丢弃。

### 2.2 发送时机与边界

- 当前回合结束（`runTurn` 的 `finally` 置 `isStreaming=false`）后自动发送队首；多条则逐条
  发送（每条都走正常 `send()` 门，因此不会并发）。
- 需要拍板的边界：用户按 **Stop** 后是否自动发（建议：发，因为用户已经点了发送）；
  回合**报错**后是否自动发（建议：不发，保留队列并在错误条旁提供"继续发送"，避免用一个
  已知失败的 provider 状态继续花钱）；进程死亡后队列丢失（与未发送草稿一致，界面上不承诺
  持久化）。

### 2.3 改动面

`ChatUiState` 增加队列状态；`ChatViewModel` 增加 enqueue/dequeue/cancel/flush（`finally`
里触发）；`ChatScreen` composer 的 `inputEnabled`、发送键行为（流式中"发送并排队"与
"停止"两个动作要分开，不能共用一个按钮位）；测试覆盖排队→自动发送→取消；文档改 01/06/07/13。

### 2.4 验收

- 流式中输入并发送 → 队列条出现 → 当前回复结束后自动作为下一轮发送，记录里只有一条用户消息。
- 流式中入队后点停止 → 队列按拍板结果发送或保留。
- 回合报错 → 不自动发送，队列仍可见。
- 没有新增工具、没有新增权限、没有 schema 变化。

---

## 3. 方案 B：插入本回合下一次调用前

### 3.1 语义

用户在 agent 工作期间点"立即插入"：这条消息当前回合**不结束**，在最近的工具组执行完、
下一次模型请求发出前被模型看到。回合结束后它作为一条真实用户消息留在记录里，
位置在插入发生的回合内。

### 3.2 引擎钩子

`EngineTurn` 增加类似 `beforeRound: suspend (round: Int) -> String?` 的回调（或注入通道），
`ChatEngine` 在工具组执行完、下一轮 `ChatRequest` 构造前 drain；host 收到后：
先落库（拿到消息行/渲染），再把文本返回给引擎拼进 history。事件顺序必须是"先落库、后请求"，
否则崩溃后模型看过的话在记录里不存在。

### 3.3 必须落库：schema v26 与 turn 判定

新增标记（例如 `messages.injected` 或 `messages.turnRootId`），让插入行：

- 不算新的 turn 根，归入当前 turn；
- 不计入回合跳转列表；
- 不参与 rewind/regeneration 的根选取（回退到这个 turn 的根，而不是插入行）；
- 不触发 `isInterruptedTurn` 的"user 尾行 = 中断"判定——或者明确它触发 Continue 时
  的行为（Continue 不新增用户消息，直接从已落盘状态继续，语义仍然成立，但要从尾行是插入
  消息的 case 走一遍）；
- 不破坏 `closeDanglingCalls` 的连续 Tool 扫描（插入只在工具组**之后** drain，天然满足）。

导出/搜索/请求查看器要能显示"工作期间插入"。测试至少覆盖：`TurnSummary`、回合跳转、
`InterruptedTurn`、`WireHistory`、`ChatEngine` 的钩子顺序，以及四个 adapter 的 wire 断言。

### 3.4 退化条件（必须诚实）

- 本回合**没有工具调用**、已到工具/轮次上限、或正在输出最终回答：没有"下一次调用"可插，
  界面应把"立即插入"置灰或降级为"结束后发送"。
- **RP 场景默认无工具**，所以 B 对 RP 几乎无用；它的价值在工具型长回合。
- 后台回合（通知栏）不提供插入入口，保持简单。

### 3.5 风险

- 模型可能把插入文本当成新的最终问题而放弃原计划——系统提示需要一句"用户可能在工作中
  补充要求，先完成当前任务再响应补充"，但不能让插入文本变成指令注入面（它来自用户本人，
  不是工具结果）。
- 追加在 history 尾部不影响前缀缓存；但一次插入会让本次请求的尾部与上一轮不同，
  这是预期内的。
- 四家 provider 的 live 行为未验证（§1.5），上线前至少用 DeepSeek 真机跑一次工具回合。

### 3.6 成本

M–L：引擎钩子小，adapter 改动小；主要成本在 schema v26、5 处 turn 判定、UI 的两种发送语义
与文档。建议在 A 上线并使用一段时间后再决定。

---

## 4. 廉价替代：停止并插入

点"插入"时先停止当前回合（已收到的回答照常保存、工具组已执行的结果也保留），然后把消息
作为下一轮发送。成本 S，不需要任何 schema/引擎改动，用户能立刻把信息交给 agent；
代价是工具循环被中断，模型看到的是"半截回答 + 用户消息"。

它不适合当默认，但适合作为 B 落地前的过渡，或者作为"这一回合反正也没在跑工具"时的入口。

---

## 5. 两条共同的能力边界

- 工作期间发的消息**不改变本回合的工具清单、审批规则、SSH 授权、下载授权**；这些仍然只在
  回合边界由用户改，改动从下一回合生效（`docs/11-conversation-tools.md`）。
- 消息来源是用户本人，不是工具结果或第三方内容；不需要新的披露或授权。

---

## 6. 待拍板

1. A 的边界：Stop 后是否自动发？报错后是否自动发？队列上限？
2. B 是否值得：愿意为"不打断的插入"接受 schema v26 + 四家 provider 真机验证 + 5 处 turn
   判定改动吗？还是先用 A + "停止并插入"？
3. B 的 UI：每条待发消息让用户选"结束后发 / 立即插入"，还是全局一个开关？
4. 后台回合（应用不在前台）是否也要插入入口？

## 7. 建议顺序

A（S）→ B（M–L）。两项一次交付，因为 B 依赖 A 的队列状态与 UI 位置；没有 B，"工作期间
发消息"只解决一半——工具型长回合里用户最想说的那句仍然要等整轮结束。

## 8. 决定记录（2026-11-25）

1. **A + B 都做，一次交付。** 用户明确要求"排队发送 + 插入到下一次模型调用前"；
   B 不再等 A 的使用反馈。
2. **B 的记录语义**：插入消息是真实用户消息，落库（新增标记列，schema v26），归入当前 turn；
   它不是新的 turn 根，不参与回退根选取，不出现在回合跳转列表。
3. **没有下一次调用时诚实退回**：本轮无工具调用/已到轮次上限/正在写最终回答时，插入意图
   自动退回为"结束后发送"，界面上说明原因，不假装已插入。
4. **能力边界不变**：插入不改变本回合工具、审批、SSH/下载授权；工具清单只在回合边界由用户改。
5. 仍待拍板的实现细节：Stop 后是否自动发（建议发）、报错后是否自动发（建议不发、保留队列）、
   队列上限、插入按钮的文案与位置。

## 9. 实现记录（2026-11-25）

已按 §8 落地，未做第二次设计评审。实现时的具体选择和留下的边界：

- **数据**：schema v26 的 `messages.injected`（默认 false，v25→v26 只加一列）；
  `MessageTurnRow.injected` 与 `ProjectionMessage.injected` 让结构性读取和折叠边界不必加载正文。
- **引擎**：`EngineTurn.beforeRound: EngineBeforeRound?`，在 `round > 1` 的循环顶部、构造请求
  之前调用；host 在 hook 内先落库、再把文本返回。`ChatEngineTest` 三个新用例钉住：只在后续
  轮次调用、返回空不加消息、单轮回答不调用。
- **记录语义**：插入行是真实 user 行，归入当前 turn；新增 `isTurnRoot`
  （`fromUser && !injected`），替换了五处 `fromUser`：turn 分组、回合跳转、
  reload/refreshTurnMeta 的 `lastUser`、压缩/投影边界、Continue 的 anchor。
- **交付**：`QueuedMessage` + `TurnQueue.kt`（内存队列，上限 5）；流式期间输入框可用；
  发送键=排队，新增"插入"TextButton 与每行的"插入"升级；队列表可见、可取消；
  Stop 后队列继续发送；失败回合保留队列、由用户逐条发送；没有下一次调用时插入降级为排队。
- **未验证**：四家 provider 真实账号上"工具结果后跟随用户消息"的 live 行为（结构合法，
  引擎测试断言了顺序）；设备侧只在模拟器（平板 profile，以及 360×800 / 640×360 / 700×700
  调整尺寸）验证，物理手机当时不可用。
- **已知限制**：640×360 横屏 + 软键盘打开时，输入法吃掉剩余高度，composer 被遮住。这是
  短横屏下 IME 的既有限制（普通打字同样出现），不是这次新增按钮或队列表造成的，本次不修。


