# 移植 DeepSeek Harness 到 Android：设计

配套文档：[`port-assessment.md`](port-assessment.md)（可行性、实测数据、结论依据）。
本文是**怎么做**，那篇是**为什么值得做、代价多大**。

状态：设计草案，未实现。凡标注「待验证」的都是尚未取得证据的判断。

---

## 0. 一句话

**按 DSH 的事件溯源语义重写一个 Android 原生 agent 运行时，存储层换成 SQLite，
沙箱层保留 inhuman 现有实现，UI 用 Compose。**

不是"把 TypeScript 翻成 Kotlin"。§6 的证据表明参考实现的**文件格式**在 Android 上是错的，
而它的**语义**（追加、投影、水位线、可重放）是对的。

---

## 1. 边界：移植什么、重写什么、不要什么

| DSH 层 | 行数 | 处置 | 理由 |
| --- | ---: | --- | --- |
| `dsh-client-ui-*` | 320,690 | **不要** | 桌面 Web 前端，Compose 重写 |
| `dsh-session*` | 20,108 | **重写（语义对齐）** | 语义要留，格式要换（§6） |
| `dsh-tool-*` | 10,148 | **部分移植** | 见 §3 的工具清单 |
| provider / subagent | 7,469 | **重写** | HTTP 与流式已有 Kotlin 实现 |
| `dsh-agent*` | 6,836 | **重写** | 循环语义对齐，见 §4 |
| `dsh-experimental-*` | 12,183 | **不要** | 未定型的实验特性 |

待移植面约 45,000 行（打包产物，上界）。目标 Kotlin 规模**不做估算**——现有 app 是
14,606 行，而这是一次数据模型迁移，比例不可线性外推。

---

## 2. 分层

```
┌─ app (Compose) ─────────────────────────────────────────────┐
│  会话列表 · 对话 · 文件 · 助手 · 设置                          │
│  只读投影，不持有状态机                                        │
└──────────────────────────────────────────────────────────────┘
┌─ agent 运行时 ───────────────────────────────────────────────┐
│  Turn/Step 状态机 · inbox · 工具调度 · 取消                    │
│  每步 = 投影(log) → 请求 → 执行 → 追加(log)                    │
└──────────────────────────────────────────────────────────────┘
┌─ 工具管线 ───────────────────────────────────────────────────┐
│  Tool 契约 · 超时预算 · 并行/独占分类 · 审批门                  │
│  ★ 通过现有的 Tool 接口调用现有的文件工具（一行不改）            │
└──────────────────────────────────────────────────────────────┘
┌─ 会话日志（唯一真相） ────────────────────────────────────────┐
│  session_events 追加表 · 水位线投影 · 压缩 · token 计量         │
└──────────────────────────────────────────────────────────────┘
┌─ 沙箱（不动） ───────────────────────────────────────────────┐
│  SessionWorkspace · WorkspaceLimits · WorkspaceTransfer       │
└──────────────────────────────────────────────────────────────┘
```

**唯一的硬边界在倒数第二层**：新的运行时通过 `Tool` 接口消费文件工具，
而 `Tool` 契约（`Tool.kt`）不需要为移植改一个字节。这是整件事里最省力的部分，
因为 `SessionWorkspace` 的保证是**接口级**的（构造私有、root 不外传），
与谁的循环在调用它无关。

---

## 3. 工具清单：21 个包里要哪几个

按"Android 上有意义"筛选，不是按包大小。

| 要 | 理由 |
| --- | --- |
| `read` `write` `edit` | 已有等价物（`read_file`/`write_file`），**保留现有的** |
| `web_search` `web_fetch` | 两个都已实现：`web_search` 走 DeepSeek Search API，`web_fetch` 走 DeepSeek Fetch API，均按成功调用付费（¥5/千次） |
| `pwsh` / `bash` | **不要**——Android 无 shell，且现有工具的收敛路径正确 |
| `todo` `goal` | 要，但排在会话日志之后 |
| `subagent` `workflow` | 要，但依赖运行时稳定之后 |
| `present` `jobs` `skill` `mcp` `str_replace_editor` | 暂不要，各有前置 |

### 3.1 `web_fetch` 曾经是缺口

上一轮的会话数据：46 次工具调用里 **42 次是 `web_search`**。那个会话的失败形态是
模型反复换关键词、每次一个模型轮次。DSH 有 `web_fetch`——搜到链接后**去读那一页**。
这个缺口已经在 2026-10-17 补上（DeepSeek 开放 Fetch API 之后），下面的论证保留为
当时的依据。

对"查官方规格"这类任务，`web_search` 给的是俄语新闻稿和百度知道，而 `web_fetch`
能让模型直接打开厂商页面。**这不是锦上添花，是当前失败模式的直接解药之一。**

---

## 3.5 会话事件词表（实测规格）

**从 102 个真实会话、113,129 个事件里提取的**，不是从文档抄的。
脚本：`.tmp/port-study/event-vocabulary.mjs` 与 `durable-vs-transient.mjs`。

### 信封

```jsonc
{ "type": "tool/call", "seq": 1234, "time": 1790348406004, "data": { … } }
```

`seq` 是**单调序号**，也是"这个事件是持久的"的判据；`time` 是毫秒时间戳。
只有 `session` 一个事件用另一套字段（`version`/`id`/`createdAt`/`cwd`/…）且**没有 `seq`**——
它是文件头，不是日志的一部分。

### 持久 vs 瞬时，判据是字段不是名字

| | 种数 | 事件数 | 体积 |
| --- | ---: | ---: | ---: |
| **持久**（带 `seq`） | **43** | 89,019 | **242.7 MB** |
| 瞬时（`seq0`，无 `seq`） | 3 + `session` | 24,122 | 6.3 MB |

`reasoning-chunks`、`text-chunks`、`tool-call-chunks` 用的是 **`seq0`/`time0`**——
**它们不是持久日志的一部分**，只是流式渲染的加速记录。移植时**可以先整个跳过**，
代价是流式重放需要从 `assistant/message` 重新分块。

### 按必要性分层（这才是要实现的规格）

| 层 | 事件 | 说明 |
| --- | --- | --- |
| **核心，必须有** | `session` `turn/start` `turn/end` `step/start` `step/end` `user/message` `assistant/message` `tool/call` `tool/result` `agent/inbox/spliced` | 没有这些就没有对话、没有工具循环、没有 turn/step 边界 |
| **交互，很快需要** | `approval/asked` `approval/decided` `session/title` | 审批门与标题——现有 app 已有对应行为 |
| **策略，第 2 阶段** | `sandbox/mode` `permission/preset` `approval/policy` `model/selection` `workspace/changes` | 设置类事件，可在投影里给默认值 |
| **压缩** | `compaction/start` `compaction/summary` `compaction/end` `compaction/prune` | 对应 assessment §6 的压缩层；`compaction/summary` 平均 **28.5 KB** |
| **可选特性** | `todo/write` `goal/change` `subagent/*` `deliverables/presented` `command/*` `tool-workflow/*` | 各对应一个尚未移植的工具 |
| **Vendor 专属，跳过** | `session-log-deepseek/delivery-accepted`（11,510 个）`web/deepseek-search-llm-request` `session/title-llm-request` `request/header` `request/context` | 遥测/账单/标题生成，与移植无关 |

### 体积分布，对存储设计的影响

最大的单个事件：

```
assistant/message   531,233 B   ← 比基准测试里的 130 KB 大 4 倍
tool/result         149,833 B
tool/call           125,778 B
assistant/chunk      30,305 B
compaction/summary   34,022 B
```

**这直接说明 assessment §5 的性能数字是下界**：基准 fixture 的"大事件"是 40–130 KB，
真实日志里有 531 KB 的。而且 `assistant/message` **平均 13,370 B**——
即每轮对话一个十几 KB 的行，43 种类型里它才是常态。

对 §6 的 SQLite 建议有两条直接后果：

1. **100 KB 不是上限，531 KB 才是。** BLOB 内外分界（250 KiB–1 MiB）**已经越过了**，
   所以大 payload 应当**外置或压缩**，不能假设"小 BLOB 内联更快"。
2. `assistant/message` 平均 13 KB、共 12,143 个 → 仅这一类就是 **约 160 MB**。
   单会话最大 24 MB 的量级与实测吻合。

---

## 4. 运行时：要对齐的四条语义

DSH 的循环本身很简单（call model → run tools → repeat）。值得对齐的是它的**保证**：

### 4.0 先回答两个前提：`Tool` 契约与 Node 环境态

**`Tool` 契约不用改。** 已核实，不是推断：

- `Tool.execute` 是 **`suspend`** 函数，签名里**没有**超时/取消参数（`Tool.kt:94`）。
- 全仓共 **10 个实现**（`FileTools.kt` 里 8 个 + `TimeTool` + `WebSearchTool`）
  和 2 个调用点。都不需要改。
- Kotlin 的 `withTimeout` **就是**取消机制，所以"每工具超时预算"是**调用方**的事，
  不必进契约。已在 `ToolTimeoutSemanticsTest` 里逐条钉住。

**但有一条限制**（同一份测试实测）：`withTimeout` **只在挂起点取消**。
一个阻塞线程的工具**完全无视 deadline**——实测 `withTimeoutOrNull(60)` 包住
`Thread.sleep(400)`，超时**根本没有触发**，调用跑满 400ms 并返回了**成功结果**。

> 超时策略能保证**调用方**按时拿回控制权，**不能**保证工具停下来。

现有文件工具走 `Dispatchers.IO`，其中 `SessionWorkspace.resolve()` 是同步系统调用，
**不会在 deadline 处中断**。这是实现超时策略时要正面处理的事实。

**而且我一直在追的那个"Node 环境态"问题，前提是错的。** `AsyncLocalStorage`
在 DSH 的发布代码里**零命中**——我 grep 了全部 `@deepseek-ai/*` 的 `lib/**/*.js`。
Cordis 的 Context 是 `Reflect` + `Proxy` 上的**键控服务注册表**
（`Reflect.get`/`ownKeys`/`getOwnPropertyDescriptor`），不是请求作用域环境态。

所以映射清单收敛成：**没有环境态要映射**。DSH 显式地用 `TypertContext` 传递上下文，
而在 Kotlin 里对应的就是构造参数 + 结构化并发的 `CoroutineContext`——
两者都是显式的，没有"隐式传递"这一层需要移植。

`Flow` 与异步迭代器的映射也已在现有代码里存在（`DeepSeekProvider.stream` 返回
`Flow<StreamChunk>`，SSE 解析与取消都已工作），不需要新机制。

### 4.1 追加即提交（append-then-derive）

> 每一个被接受的事实，都在下一次推导之前追加进日志。

Kotlin 里这是**写顺序的约束**，不是抽象：

```kotlin
// 错的顺序：先更新界面，再写日志。崩溃时界面有了、日志没有。
_state.update { ... }
repository.append(event)

// 对的顺序：日志先落地，投影从日志来。
repository.append(event)      // 事务
project(state)                // 纯函数：List<Event> -> UiState
```

这条对齐了，就顺带解决了 inhuman 里反复出现的同一个毛病——**错误只活在内存里**
（网络失败、上下文超限、轮次上限，三次都是）。错误是事件，事件进日志，日志落库。

### 4.2 取消是协作的，且已交付的内容要保留

DSH：*"Cancellation preserves streamed text already delivered to the user."*

现有实现已经做到一半（`stop()` 修好了，`NonCancellable` 保证收尾落库）。
要对齐的是把"取消"本身变成事件，而不是一个只改内存标志的动作。

### 4.3 未答复的工具调用必须有结果（即使是错误）

DSH 明确区分并各写一条结果进历史：

- `TOOL_OUTCOME_UNKNOWN` —— 已记录调用但没有提交结果：*"Its outcome is unknown."*，
  只允许重试只读/幂等操作
- `TOOL_NOT_STARTED` —— 连调用记录都没有

**理由值得抄下来**：这样后续请求能带着**配对的**工具历史继续，
而不会自动重试结果不确定的写操作。现有实现在这条上是空白。

### 4.4 没有轮次上限，但要有收尾轮

DSH 自己写着 *"No built-in turn budget"*。所以**不加轮次上限**是对的——
上一轮数据里第一轮用了 7 轮后自己停下，不是被截断的。

但要有 DSH 结构里隐含的东西：**撞到任何预算时，先给模型一次不带工具的作答机会**。
「花了钱 + 零输出」这个结果形态必须被消灭。

---

## 5. 不做的事（明确写下来，避免反复讨论）

| 不做 | 理由 |
| --- | --- |
| 移植 Cordis 插件宿主 | **已核实不必**：Cordis 本体仅 1,842 行，但 DSH 用它做的事是"把折叠器注册进投影表"——而投影表本身是 `SessionProjectionStateMap` 的普通接口。Android 是单 app 单进程，工具注册用构造函数即可。**事件词表（§3.5）才是要实现的规格，插件图不是。** |
| 移植 Web UI | 320,690 行，零收益 |
| 文件格式照搬 per-line-zstd | assessment §6：seek table 让追加变成重写 |
| 移植 vendor 遥测事件 | `session-log-deepseek/delivery-accepted` 等 5 类共 1.2 万事件，与运行时语义无关 |
| 加轮次上限 | DSH 没有，实测也不需要 |
| 换掉现有沙箱 | 接口级保证比 DSH 的 OS 级更适合 Android，且已通过对抗性审查 |
| 自建 QuickJS 执行沙箱 | **前提已变**：`androidx.javascriptengine:1.1.1` 已在两台真机验证可用（assessment §8） |

---

## 6. 阶段划分

每一阶段**独立可验证**，且不破坏现有 app（新增模块，不改既有路径）。

| 阶段 | 内容 | 验收标准 |
| --- | --- | --- |
| **P0** | `session_events` 表 + 水位线投影 + 压缩 | 能把现有对话导入事件日志、投影回原样的 transcript；纯 JVM 可测 |
| **P1** | 运行时骨架：Turn/Step、inbox、取消 | 用 demo provider 跑通一轮工具循环，事件全部落库 |
| **P2** | 工具管线：超时预算、并行/独占分类 | 搜索挂住时整轮不再卡死；超时给模型明确错误 |
| **P3** | UI 改读投影 | 对话页从 `ChatUiState` 切到 `project(events)`，行为不变 |
| **P4** | 旧 schema 迁移 | 真实设备上的 v7 → 事件日志，一次性且可回滚 |
| **P5** | token meter + 压缩（`web_fetch` 已接，见 §3.1） | 对齐全 §4 的四条语义 |

**P0 是唯一的地基**，也是唯一天然适合先做的：它能独立测试，不依赖运行时，
而且做完之后 P3/P4 的形态就定了。

---

## 7. 待验证

| # | 未知数 | 影响 | 状态 |
| --- | --- | --- | --- |
| 1 | **ARM64 上的真实耗时** | 冷启动预算 | **已闭环**：全量 0.44 s、尾读 61 ms（assessment §5） |
| 2 | **`JavaScriptSandbox.isSupported()` 真机结果** | 决定 §5 最后一行走官方还是自建 | **已闭环**：两设备均 true（assessment §8） |
| 3 | 扩展 `Tool` 契约的成本 | §2 的省力论证建立在它上面 | **已闭环**：契约可不动，限制见 §4.0 |
| 4 | Node 原语 → Kotlin 映射 | §4.2/§4.3 的写法 | **已闭环**：`AsyncLocalStorage` 在 DSH 里零命中，没有环境态要映射；取消与流式见 §4.0 |
| 5 | 100 KB 事件在 SQLite 里的行为 | 大 payload 是否外置 | 待实测（P0 副产品） |
| 6 | 现有 7 个迁移的等价改写 | P4 的风险 | 待评估 |
| 7 | 官方沙箱 vs 自建 QuickJS 的性能 | 若官方慢一个量级，考虑自建 | 未测 |
| 8 | 隔离进程能否读宿主传入的 fd | 文件通道走 MessagePort 还是 fd | 未测 |
| 9 | `provideNamedData` 的消费端函数名；WASM 能否编译 | 字节如何进出沙箱 | 未测 |

**三项已闭环，其中第 1 项推翻了我自己的预估**（真机比模拟器快 2–8 倍）。
