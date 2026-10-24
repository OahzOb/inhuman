# 移植 DeepSeek Harness 到 Android：评估（第一部分：实测）

> **Historical document.** Written before the desktop host existed. It is kept
> for its measurements and rejected options, not as a description of the
> current code. Current implementation: [../12-desktop.md](../12-desktop.md).

状态：**进行中**。本文只写已经**在真实数据上量过**的部分。技术栈映射、Node 专有 API 的
可移植性、以及代码执行沙箱的现状，由另两项调研补入第二部分——那些地方我还没有证据，不写。

测量环境：Windows 上的 Node v24.20.0，数据是 `~/.dsh/sessions` 里**真实的会话日志**，
不是构造的样本。

---

## 0. 结论摘要

| 问题 | 结论 | 依据 |
| --- | --- | --- |
| 完整移植可行吗 | **不可行**，且不必——60% 的代码是桌面 Web UI | §2 |
| "在限制内尽可能移植"可行吗 | **可行**，待移植面约 45,000 行 JS | §2 |
| 最大的技术风险是什么 | 不是 API 差异，是 **Android 上的冷启动与内存** | §3、§4 |
| 会话日志能在手机上读吗 | **能，而且比预期好**——帧独立、可随机访问、可只读尾部 | §4 |
| 待验证 | ARM64 上的真实耗时、Kotlin API 映射、代码沙箱现状 | §6 |

---

## 1. 为什么要重新定义目标

"做一个 DSH 的 Android 原生移植"这句话里藏着两个差一个数量级的工程：

- **完整移植**：包括它的 Web UI 层。不可行，也没有意义——Android 有 Compose。
- **移植运行时**：agent 循环、会话日志、工具管线、压缩、token 计量，UI 用 Compose 重写。
  这是可定义的、可评估的、可分阶段交付的。

下面所有数字都按第二种口径。

---

## 2. 待移植面有多大

按包分组统计**打包后的运行时 JS**（排除 `lib/types/`，那是类型声明，不含逻辑）：

| 层 | 包数 | 行数 | 是否要移植 |
| --- | ---: | ---: | --- |
| `dsh-client-ui-*` | 55 | **320,690** | **不要**——桌面 Web 前端，Compose 重写 |
| `dsh-session*`（日志、格式、持久化、投影、查询） | 24 | 20,108 | 要 |
| `dsh-tool-*`（模型可见的工具） | 21 | 10,148 | 要（取舍见下） |
| provider / subagent | 8 | 7,469 | 要（subagent 可缓） |
| `dsh-agent*`（循环、注册表、指令） | 7 | 6,836 | 要 |
| `dsh-experimental-*` | 11 | 12,183 | 不要 |
| **合计待移植** | **60** | **≈ 45,000** | |

参照：**inhuman 现有全部 Kotlin 是 14,606 行**（75 个文件）。

所以这是一个**比现有 app 大 2~3 倍**的运行时。作为长期工程可做，作为"改造"不可做。

> 注：这 45,000 行是**打包产物**，是上界。没有 `src/` 可比（npm 包只发 `lib/`），
> 所以真实源码量不可知。这个数字的用途是**量级判断**，不是工作量估算。

---

## 3. 参考实现的存储设计（实测）

这是整个移植里最需要先搞清楚的一块，因为 DSH 里**一切从会话日志派生**。

### 3.1 物理格式

```
~/.dsh/sessions/<project-slug>/<session-id>/session.v4.jsonl.zstd
```

9 个会话，91 个文件，磁盘上 65.5 MB，**全部是 `.zstd`**。

### 3.2 不是一个 zstd 流，是几万个独立帧

对一个 2.46 MB 的会话实测：

```
真实帧数          2,364
压缩后            2.46 MB
平均帧大小        1,092 B
帧间距中位数      365 B   （最小 83 B，最大 29,361 B）
随机取 200 帧     3.6 ms，平均解压出 4,302 B
```

**每一行 JSONL 事件 = 一个独立的 zstd 帧。** 因为每帧自带魔数与帧头，任意一帧都能
**单独解压，且不需要任何前置状态**。

这一条对 Android 是决定性的，因为它是三件事同时成立的原因：

1. **可流式追加**：写一个事件 = 压缩一个事件 = 追加一段字节。不需要重写整个文件。
2. **可随机访问**：要第 N 个事件，seek 过去解一帧即可，不必解压前 N-1 帧。
3. **可只读尾部**：冷启动只需要最近的历史。

代价是每帧约 13 字节的 zstd 帧头。2,364 帧约 31 KB，占文件的 1.3%。**换来随机访问，值。**

### 3.3 真实体量

| 会话 | 压缩后 | 解压后 | 事件数 | 最大单个事件 |
| --- | ---: | ---: | ---: | ---: |
| 本会话 | 2.41 MB | **8.53 MB** | 3,636 | 130 KB (`tool/result`) |
| 磁盘上最大 | 6.64 MB | **24.14 MB** | 9,610 | 110 KB (`assistant/message`) |

事件类型分布（最大那个会话，前 5）：

```
1,684  tool/result
1,664  tool/call
1,473  session-log-deepseek/delivery-accepted
1,470  step/start
1,470  assistant/message
```

注意 `step/start` 与本会话的 560 次 `step/start` 对应——**每一步都进日志**，包括生命周期
边界，不只是消息。日志比"聊天记录"大得多，因为它是**可重放的完整历史**。

### 3.4 读一次要多久（桌面）

| 操作 | 耗时 |
| --- | ---: |
| 全量走完并解析 9,610 个事件（24 MB） | 177 ms |
| 只读尾部 1 MB | **33 ms**（1,505 个事件） |
| 只读尾部 256 KB | 10 ms（359 个事件） |
| 只读尾部 64 KB | 5 ms（72 个事件） |
| 随机取 200 帧 | 3.6 ms |

**关键推论：冷启动不需要回放全部历史。** 读尾部 1 MB 拿到 1,505 个事件，桌面 33 ms；
手机上按 ARM64 慢 5~10 倍估计，约 **0.2~0.35 秒**——可接受。而全量回放 24 MB 在手机上
可能到 1~2 秒，也仍然可用，只要不阻塞主线程。

⚠️ **这两个估计是推断，不是测量。** §6 列为必须在真机上验证的第一项。

---

## 4. 这改变了什么问题

现有 inhuman 用 Room + `parentId`/`selectedChildId` 表达对话树，`ChatUiState` 在内存里
维护当前状态。DSH 的模型是：**追加事件、投影出视图**。

两者不是同一个东西，所以"照抄 DSH 的循环"在语义上不成立——循环依赖日志，日志依赖
投影，投影替代 `ChatUiState`。移植必须是**整层的**，不能只搬循环。

我原先以为障碍是 Node 专有 API（`AbortSignal`、`AsyncLocalStorage`、异步迭代器）。
现在看**不是**：那些有 Kotlin 对应物，而且 coroutines 的取消语义比 Node 更结构化。

**真正的障碍是两个：**

1. **体量**（§2）——45,000 行，是现有 app 的 2~3 倍。
2. **数据模型替换**——Room schema、分支切换、重新生成、tool 结果、`DocumentsProvider`
   全部要重建在事件日志之上。这是一次**迁移**，不是叠加。

---

## 5. Android 上的实测（第一项风险，已闭环）

基准测试：`app/src/androidTest/kotlin/com/inhuman/bench/SessionLogReadBenchmark.kt`，
用 `zstd-jni 1.5.7-21@aar` 在设备上读一个**按真实日志形状生成**的 fixture
（2,364 帧，每帧一个 JSON 事件，尺寸分布照抄实测：多数几百字节，每 137 个有一个
100 KB 级的 tool result）。

**两个设备都跑了，结论一致：**

| | 模拟器 x86_64<br>Pixel Tablet AVD, API 35 | **真机 ARM64**<br>Xiaomi 23113RKC6C, API 36 | 倍数 |
| --- | ---: | ---: | ---: |
| decompress all | 182.8 ms | **57.1 ms** | 3.2× |
| decompress+parse | 348.7 ms | **174.3 ms** | 2.0× |
| tail 1 MB | 114.3 ms | **60.6 ms**（924 events） | 1.9× |
| 200 random frames | 43.6 ms | **5.6 ms**（28 µs/帧） | 7.8× |
| heap, full read | 5.3 MB | **5.3 MB** | — |

```
fixture: 2667 KB, 2364 frames, 4466 KB plain, ratio 1.7:1
```

### 换算到真实会话（按帧数线性外推）

| 路径 | 最大真实会话<br>9,610 帧 / 24.1 MB | 本会话<br>3,636 帧 / 8.5 MB |
| --- | ---: | ---: |
| 全量解压 | **150 ms** | 57 ms |
| 全量解压 + 解析 | **约 440 ms** | 约 170 ms |
| 尾读 1 MB | **61 ms** | 61 ms |

**结论修正：全量冷启动约 0.44 秒，不是先前预估的 1.5–4 秒。** 那个预估假设
"ARM64 比 x86_64 慢 5–10 倍"，实测**方向相反**——模拟器才是慢的那个（软件渲染 +
非原生指令路径）。尾读 61 ms。

**所以"帧级随机访问 + 只读尾部"的架构在 Android 上不仅成立，而且宽裕。**
冷启动全量回放甚至可以直接放在主线程之外随便跑。

⚠️ 两项保留：fixture 比真实日志**更易压**（1.7:1 vs 3.5:1），真实解压只会更慢；
以及"按帧数线性外推"假设每帧成本恒定，而真实日志含 100 KB 级大帧，单帧成本更高。
两者都使这些数字成为**下界**。

---

## 6. 存储层：一个推翻直觉的结论

调研（`androidx.room3` 当前线、SQLite 官方 fsync 基准、zstd seekable format 现状）
指向一个和"照搬参考实现"相反的结论：

> **不要移植 per-line-zstd 的 JSONL 文件格式。换成 SQLite 追加表。**

理由，按权重：

1. **seek table 让追加变成重写。** zstd 的 seekable format 至今仍是
   **Version 0.1.0 / 2017**，活在 `contrib/` 下，而且**把索引放在文件尾**。
   追加一帧就作废索引 → 必须重写它。这正是追加日志存在的意义所否定的操作。
2. **每行一帧恰好落在 zstd 最不擅长的区间。** 官方文档：*"Dictionary gains are
   mostly effective in the first few KB"*——而每帧只有几百字节到 2.4 KB，**每帧
   都在付冷启动成本**，没有历史可用。实测 77 µs/帧，其中很大一部分是固定开销。
3. **SQLite 的官方白皮书恰好把我们需要的当卖点。** §3.7/§3.9：SQLite
   *"can read and write smaller BLOBs (less than about 100KB)"* 比文件系统更快，且
   *"instead of having to read and parse the entire document into memory, the
   application can do queries to extract only the information needed for the
   initial screen"*——**"冷启动不全量加载"是内建属性，不用自己写索引。**
4. **fsync 的实测是 10 倍量级。** SQLite 自己的基准：*"invoking fsync() … on each
   file written causes direct-to-disk storage to be about 10 times or more slower
   than writes to SQLite"*。而"每次追加一个事务"是 SQLite 变慢的原因
   （`--multitrans` 实测），那个**由我们控制**——批量提交即可。
5. **少一个 JNI 依赖、少一份 ABI 风险。** per-line 方案要多带 zstd 的
   `.so`（四个 ABI 共约 2 MB）、一条 R8 keep 规则（其文档明确：
   *"The Java classes cannot be renamed/minimized/relocated"*）、以及 16 KB 页对齐
   风险。而 SQLite 已经在 app 里了。

**推荐形态**（全部为推断，待验证）：

```
session_events(sessionId TEXT, seq INTEGER PRIMARY KEY AUTOINCREMENT,
               kind TEXT, payload TEXT, ts INTEGER)
```
- **不要**用 `InvalidationTracker.createFlow` 驱动投影——它是**表级**的
  （`Flow<Set<String>>`），每次追加都会触发，诱导你重新折叠全部 9,610 个事件。
  改为持久化 `lastFoldedSeq` 水位线，只折叠增量。
- 追加**批量提交**，不要一事一事务。
- 100 KB 级事件接近文档记载的 BLOB 内外分界（250 KiB–1 MiB），需实测。

**次选**：不压缩的 JSONL + 定期 `fd.sync()`。它保留了参考实现的磁盘格式和
`tail -f` 互操作，但**没有索引化的增量读**（冷启动要从 0 扫）、**没有多事件原子提交**。

**zstd 仍然有用**，但用在**大 payload 的内容**上（字典训练、一次一帧），
不是每行一帧。

---

## 7. 不变的东西：硬性限制

移植**不动**沙箱。`SessionWorkspace` 的契约（构造私有、root 不外传、`resolve()` 是
路径变成 `File` 的唯一入口、拒绝符号链接、canonical 前缀兜底）与 DSH 的会话/工具模型
**正交**：DSH 的沙箱是操作系统级（Windows ACL / 受限令牌），Android 上没有对应物，
而现有实现是接口级的，且经过对抗性审查未发现绕过。

**结论：这一层保留原样，新的运行时通过同样的 `Tool` 接口调用它。** 这是移植中唯一
"不追、反而领先"的部件。

---

## 8. 代码执行：一个部分推翻既有调研的发现

`_research/android-sandbox/`（2026-10-04）的结论是：要执行模型代理写的代码，**只能自己带
QuickJS、写 JNI、设计 Binder 协议和虚拟文件系统**，并判定

> *"there is no public API to cap a child's CPU or memory"*

**这个前提有一半过时了。** 证据是我自己下载 AAR 读出来的，不是从文档推断：

```
androidx.javascriptengine:javascriptengine:1.1.1
  发布      2026-09-23（latest == release）
  版本线    1.0.0-alpha01 … beta01 … rc01 → 1.0.0 → 1.1.0 → 1.1.1
  minSdk    26        （本项目 34，无障碍）
  manifest  不含 <service>  →  隔离进程由平台实现，app 不注册任何东西
  公开类    JavaScriptSandbox · JavaScriptIsolate · MessagePort ·
            IsolateStartupParameters · TerminationInfo · MemoryLimitExceededException
  底层      org.chromium.android_webview.js_sandbox.common.IJsSandboxService
            org.chromium.android_webview.js_sandbox.common.IMessagePort
            org.chromium.android_webview.js_sandbox.common.IJsSandboxIsolate
```

**它就是 WebView 的 JS 沙箱：V8 跑在隔离进程里。** feature flag 里两条直接回答旧调研的未解问题：

| flag | 意义 |
| --- | --- |
| `JS_FEATURE_ISOLATE_MAX_HEAP_SIZE` | **堆上限是官方 API**——旧调研判定"没有公开 API 限制子进程内存，只能靠引擎内部堆限制" |
| `JS_FEATURE_WASM_COMPILATION` | **支持 WebAssembly**，不必只限于 JS |
| `JS_FEATURE_ISOLATE_TERMINATION` | 可终止（配 `TerminationInfo`） |
| `JS_FEATURE_MESSAGE_PORTS` | `MessagePort`，与隔离进程的结构化通道 |
| `JS_FEATURE_EVALUATE_FROM_FD` | 可从 fd 取代码——与旧调研"宿主传 fd"同构 |

配套的异常类型说明这些限制是**被强制**的：`MemoryLimitExceededException`、
`EvaluationResultSizeLimitExceededException`、`IsolateTerminatedException`、
`SandboxUnsupportedException`。

### 这意味着什么

旧调研里"1 MB 引擎 + 虚拟文件系统 + Binder 协议 + 网络代理"这一整套自建工程，
**大部分由平台提供了**，退化成一次 `createConnectedInstanceAsync(context)` 加通信设计。
内存上限——旧调研里唯一判定为无解的资源限制——现在有官方 API。

### 已在设备上验证（两个设备，结论一致）

探针：`app/src/androidTest/kotlin/com/inhuman/bench/JavaScriptSandboxProbe.kt`

| | emulator-5554 | **真机 23113RKC6C** |
| --- | --- | --- |
| API | 35 | **36** |
| WebView | 124.0.6367.219 | **153.0.8010.36** |
| `isSupported()` | **true** | **true** |
| 带堆上限创建 isolate | **accepted** | **accepted** |
| `1+1` / `Date` / `JSON` | ✓ | ✓ |
| `require` / `process` | undefined | undefined |
| `fetch` / `atob` / `setTimeout` / `TextEncoder` | **全 undefined** | **全 undefined** |
| `console` / `WebAssembly` | object | object |

**四条对设计有直接影响的结论：**

1. **`isSupported()` 在两个设备上都是 true**，WebView 版本相差 29 个版本号仍一致，
   所以这不是某个版本的偶然。§8 的整段结论**成立**。
2. **堆上限真的能设**：`IsolateStartupParameters.maxHeapSizeBytes` 被接受。
   旧调研判定"没有公开 API 限制子进程内存"——**这一条被推翻**，而且是编译期
   （重载存在）加运行期（accepted）双重确认。
3. **沙箱里连不出网**：`fetch` 是 `undefined`。这正是旧调研设计中"网络必须由宿主代理"
   那一层，但**由平台强制**，不是我们自己加的。宿主代理因此从"安全措施"变成
   "唯一可用的网络路径"。
4. **沙箱没有定时器**（`setTimeout` undefined）、**没有 `atob`**、**没有 `TextEncoder`**。
   要在这个沙箱里跑一个像样的运行时，这些都得自己补——这是一份**明确的缺口清单**，
   不是猜测。

**还有一条未解**：宿主把字节传进 isolate 的 API 名我猜错了（`consumeNamedData` 不存在），
所以 WASM 那条没测成。`WebAssembly` 对象存在，但"能否编译"仍未验证。

### 仍未测

- **性能对比**：官方沙箱 vs 旧调研自建的 QuickJS。旧调研有 x86_64 实测可作基准。
- **文件通道**：隔离进程能否读宿主传入的 fd。这决定文件进出走 `MessagePort` 还是 fd。
- **`provideNamedData` 的消费端函数名**，以及 WASM 编译是否真的可用。
- **runaway 只能由调用方超时**（实测 `TimeoutException`），平台不会自动终止；
  要终止得显式调 `close()` 或 `JS_FEATURE_ISOLATE_TERMINATION`。

---

## 9. 待验证

| # | 未知数 | 不成立会怎样 | 状态 |
| --- | --- | --- | --- |
| 1 | ARM64 上的真实耗时 | 冷启动预算 | **已闭环**：全量 0.44 s、尾读 61 ms（§5） |
| 2 | `JavaScriptSandbox.isSupported()` | 决定走官方还是自建 QuickJS | **已闭环**：两设备均为 true（§8） |
| 3 | Node 原语 → Kotlin 映射 | §4 的写法细节 | 调研中 |
| 4 | 100 KB 事件在 SQLite 里的行为 | 大 payload 是否外置 | 待实测 |
| 5 | 现有 7 个迁移的等价改写 | P4 的风险 | 待评估 |
| 6 | 哪些 `dsh-tool-*` 值得移植 | 待移植面收敛 | 设计文档 §3 已有初稿 |
| 7 | 官方沙箱 vs 自建 QuickJS 的性能 | 若官方慢一个量级，考虑自建 | 未测 |
| 8 | 隔离进程能否读宿主传入的 fd | 文件通道走 MessagePort 还是 fd | 未测 |
| 9 | `provideNamedData` 的消费端函数名；WASM 能否编译 | 决定字节如何进出沙箱 | 未测 |

第 1、2 项**都已闭环**，而且第 1 项推翻了我自己的预估（见 §5）。

---

## 9. 目前的态度

**倾向于做，但按"运行时移植 + 存储层改用 SQLite + 分阶段交付"，而非照搬。**

支持：

- 60% 的代码（Web UI，320,690 行）不需要，待移植面收敛到约 45,000 行。
- §5 证明帧级随机访问在 Android 上成立，§6 给出了一个比参考实现**更适合移动端**的
  存储形态——而且这个形态是**现有 app 已经在用的技术**（Room）。
- 沙箱层不用动，且是现有实现里最好的部分。
- 现有 `_research/android-sandbox/` 的结论（`isolatedProcess` + 文件描述符传递）
  正好是运行时里"执行模型代理写的代码"所需机制，可直接复用。

反对或存疑：

- 45,000 行仍是现有 app 的 2~3 倍，且是**打包产物**，真实工作量不可知。
- 这是一次**数据模型迁移**，不是叠加：分支切换、重新生成、tool 结果、
  `DocumentsProvider` 都要重建在事件日志之上。
- §6 的推荐意味着**不照搬参考实现的存储**——那么"移植"这个词的准确含义变成
  "按它的事件溯源语义重写，但自己选存储"。这比"抄"更贵，也更可能对。

