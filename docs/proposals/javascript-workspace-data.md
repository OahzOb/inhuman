# `run_javascript` 的工作区数据输入 — 设计提案

> **状态：提案，未实现（2026-11-27）。** 本提案只改 `run_javascript` 的**数据入口**，
> 不改它的执行环境：沙箱仍然没有文件系统、没有网络、没有模块 API、没有 host 回调。
>
> 与本轮的 `local-web-tools.md` 独立：`render` 是 WebView（浏览器引擎），本提案是 V8 沙箱
> 的数据输入；两者不共享运行时，也不互相调用。
>
> 实现时改：`docs/05-files-sandbox.md`（沙箱一节）、`docs/08-port-philosophy.md` §2.1/§2.2
> （"数据由宿主传入"落地）、`AGENTS.md`（`run_javascript` 不变量改写）、`docs/07-gotchas.md`
> （新增不变量）、`docs/13-product.md`（如需要产品措辞）、`docs/README.md`（提案索引与测试表）。

---

## 0. 结论先行

- `run_javascript` 增加**只读**的 `input_files: string[]` 参数：App 先读工作区文件，把内容作为
  `INPUT_FILES` 常量注入脚本，脚本照旧只返回一个字符串。工具仍 `mutates = false`。
- **不**把工作区挂进沙箱、**不**注入 `readFile()`/`fetch()` 之类的 host 函数、**不**传文件句柄、
  URL 或 key。probe 的断言（`fetch`/`require`/`process` 必须 `undefined`）保持不变。
- 三个前提同时成立才开放 `input_files`：workspace 可用（启动 fence 通过）、本会话允许
  `read_file`、每个路径逐条通过同一套 `SessionWorkspace` + `PolicySet`（Blind/Deny/Ask）检查。
  任一不成立时，`run_javascript` 退回成今天的纯计算器——schema 里根本没有这个参数。
- 上限：≤5 个文件、单文件 ≤64 KiB、总量 ≤256 KiB、仅文本；超限/二进制/解析失败是错误，
  不是"空成功"或静默截断。
- 可选后续：`output_path`（把结果写回工作区）是另一个决定（`mutates = true`、REWIND、
  `write_file` 同级规则），本提案**不做**。

---

## 1. 现状（代码事实）

### 1.1 工具与沙箱

- 工具：`app/.../sandbox/JavaScriptTool.kt`，参数只有 `script`，`mutates = false`
  （目录条目 `core/ai/.../tools/ToolCatalog.kt:220-224`）。
- 执行：`SandboxRuntime.run(context, script)`（`SandboxRuntime.kt:63-113`）——平台 V8
  隔离进程（`androidx.javascriptengine`），脚本 ≤20,000 字符（`:72`）、输出 ≤100,000 字符
  （`:96`）、16 MiB 堆（`:171-177`）、10 秒上限，每次调用新建 isolate、用后关闭。
- 探测：`SandboxRuntime.probe`（`:115-150`）断言 `fetch`/`XMLHttpRequest`/`WebSocket`/
  `require`/`process` 全是 `undefined`、`1+1` 正常、堆上限可设置；失败即移除工具。
- 注册：`ChatViewModel.kt:6503`，仅当 `SandboxRuntime.available(context)`；`visible(includeSandbox)`
  控制 picker（`ToolCatalog.kt:557`）。
- 环境边界：沙箱没有 `SessionWorkspace`、没有 host 回调；脚本里没有文件系统、没有网络。
  `docs/08-port-philosophy.md` §2.1 把这一点写成设计结论："**沙箱不该有文件系统，数据由宿主
  传入、结果由宿主取出**"；§2.2 说明"没有出口"是允许执行代码的前提。`AGENTS.md` 的
  load-bearing 条目也写着："The only data crossing is the script string in and one result
  string out. Never pass a file handle, a URL or a provider key into it."

### 1.2 工作区与策略

- 唯一的路径 → `File` 入口是 `SessionWorkspace.resolve`（`core/files/.../SessionWorkspace.kt:82`，
  无符号链接/junction、真实路径前缀检查）。
- 审批：`ChatViewModel.gateEngineCall` 在调用前用
  `policies.resolve(tool.name, policyPath)`（`ChatViewModel.kt:5823-5830`）；`policyPath` 来自
  `resolvedPolicyPath`（`:6993-7012`），后者**只读单数 `path` 参数**，并经
  `SessionWorkspace` resolve/relativize 后才匹配规则。`PolicySet.effective(tool, relativePath)`
  （`PolicySet.kt:45-96`）取全局/助手/路径三条规则中最严的一条。
- 因此本提案新增的"数组路径"必须补一条多路径解析，不能假设现有单路径逻辑覆盖它。

### 1.3 今天处理工作区数据的替代方案都很差

- `read_file` 读全文再让模型心算：大文件不可靠，且内容占上下文；
- 把文件内容塞进 20,000 字符的脚本：稍大的 CSV/JSON 直接超限；
- 让 JS 自己读文件：不允许（也是本提案不做的部分）。

---

## 2. 为什么做

- CSV/JSON/日志的表单统计、过滤、排序、编码转换、正则提取，正是 JS 擅长而模型心算容易错的活。
- 与"让沙箱碰文件"不同：文件由宿主读取、以**数据**形式传入，沙箱仍然没有路径、没有句柄、
  没有目录、没有网络；它只是对一段数据做计算。
- **不是新能力**：agent 本来就能用 `read_file` 读同一批文件（前提是被允许）。本提案只是把
  "读进来再算"做成一次调用：省上下文、绕开 20k 脚本上限，且读文件这件事仍然走
  `read_file` 的同一套权限与策略。

---

## 3. 设计

### 3.1 参数

| 参数 | 类型 | 约束 |
| --- | --- | --- |
| `script` | string，必填 | 与今天一致：≤20,000 字符，必须在函数里 `return` 结果 |
| `input_files` | string[]，选填 | 0–5 个工作区相对路径；单文件 ≤64 KiB、总量 ≤256 KiB；仅文本 |

返回值保持不变：一个字符串（非字符串结果 JSON 化，≤100,000 字符）。

**schema 是条件性的**：只有"workspace 可用 + `read_file` 被允许 + 文件工具确实已构建"三者
同时成立时，工具实例才在 `parameters` 里带上 `input_files`；否则模型看不到这个参数，即使
调用里硬塞也会被 `execute` 拒绝。这样老助手/没有读文件能力的会话不会多出一条读路径。

### 3.2 读取与注入

- 路径逐条 `SessionWorkspace.resolve`：拒绝穿越、符号链接、junction、非普通文件；大小与
  文本检查在读入时做。
- 文本判定：UTF-8 解码后拒绝 NUL 与高比例替换字符；二进制文件返回明确错误。
- 注入方式：最终执行源 = `const INPUT_FILES = <JSON>;` + 现有的 IIFE 包装。JSON 由
  `kotlinx.serialization` 生成；额外转义 U+2028/U+2029；`INPUT_FILES` 的键是相对路径，
  值是文件文本。
- 新上限：脚本 20,000 字符**不变**，输入文本总量 256 KiB，求值源总长 ≤320,000 字符
  （防止用"输入"把 20k 脚本上限变成任意大执行）；沙箱堆 16 MiB、10 秒超时**不变**
  （256 KiB 输入约 20 倍余量）。
- 输出与截断规则不变。
- 诊断：只记文件数、总字节数、脚本长度、结果长度；**不记路径、不记文件内容**
  （路径相对安全，但这一层没有记它们的理由）。

### 3.3 权限与策略（关键）

`input_files` 是新的读路径，必须与 `read_file` 完全同权：

1. **前提**：`ToolCatalog.permits(selected, "read_file")` 为真，且 file tools 真的构建了
   （workspace fence 通过）。
2. **逐路径**：在 `gateEngineCall` 里对每个路径做 `SessionWorkspace.resolve` →
   `workspace.relativize` → `policies.resolve("read_file", relativePath)`，取最严：
   - 任一 `Blind` 或 `Deny` → 整次调用拒绝（**不读任何文件**，避免"部分读取"泄漏）；
   - 任一 `Ask` → 一次普通审批（与 `read_file` 同一条规则、同一个对话框），拒绝则整次拒绝；
   - 全部 `Free` → 执行。
3. `resolve` 拒绝的路径 → 工具错误；不是忽略、不是跳过。
4. `input_files` 会出现在 transcript 里（和 `read_file` 的 `path` 一样），用户可见。
5. 这条多路径解析要作为纯函数抽出并单测（见 §6），不能只靠 `resolvedPolicyPath` 的单路径逻辑。

### 3.4 环境不变（写死，不做）

- 沙箱仍然：无文件系统、无网络、无模块 API、无 host 回调、无文件句柄、无 URL、无 key；
  probe 断言不变。
- workspace 不可用或 `read_file` 不被允许时：**保留纯计算器**，不要因此隐藏整个工具
  （不能把纯计算也一起拿走）。
- 不做目录读取、文件列表、写回、跨调用状态、文件监听。
- 不做"自动注入工作区所有文件"：那是环境性访问，只注入调用里显式列出的文件。

### 3.5 工具描述、目录与 UI

- 工具描述增加一句："可选 `input_files`（只读、受与 `read_file` 相同的审批规则）；沙箱内部
  仍然没有文件系统。"
- `ToolCatalog` 条目：仍 `mutates = false`；`workspaceBound` **保持 false**——工具在无 workspace
  时仍是可用的计算器，只是没有 `input_files`；blurb 更新（`en` / `zh-rCN`）。
- `visible()` 不需要新参数；`includeSandbox` 照旧。
- 不需要新的 Settings 开关、不需要 `optIn`：这是现有工具的扩展，不是新的外部数据路径；
  读取权限由 `read_file` 的选择与策略表达。
- 审批对话框在 `Ask` 时应列出文件名（与 `read_file` 的 path 同等披露）。

---

## 4. 安全分析

- **读路径**：无符号链接/穿越，逐路径策略，总量上限；文件内容不进日志；transcript 只有
  路径与脚本。
- **注入的数据是数据**：文件内容对脚本只是字符串；脚本没有出口，不能把内容发出去。
- **提示注入**：如果脚本原样 `return` 文件文本，它进入模型上下文——与 `read_file` 的结果
  同一信任级；工具描述沿用"内容不可信"的措辞。脚本本身不会执行内容。
- **资源**：16 MiB 堆对 256 KiB 输入足够；10 秒超时不变；总源长上限防止"用输入绕过脚本上限"。
- **不变量改写**：`AGENTS.md` 里那句"the only data crossing is the script string in and one
  result string out"必须改成类似："script + explicitly named, read-only, policy-checked
  workspace texts in; one result string out; never a file handle, URL, key or host callback."
  `docs/08` §2.1/§2.2 补一段把这个数据流写清楚。

---

## 5. 拒绝的替代方案

1. **把虚拟文件系统挂进沙箱**（`_research/android-sandbox/` 的旧设计）：拒绝，理由见
   `docs/08-port-philosophy.md` §2.1——沙箱不该有文件系统。
2. **注入 `readFile()`/`fetch()` 之类的 host 函数**：拒绝。能力被放进沙箱、probe 无法再断言
   "没有 host 回调"、提示注入可操纵模型写的代码去读任意文件；"代码没有出口"的论证被破坏。
3. **新工具 `analyze_data` / `run_javascript_files`**：拒绝。与 `run_javascript` 重复，模型要
   学两个相似入口；扩展同一工具更简单。
4. **`output_path` 写回工作区**：本提案不做。它是写能力（`mutates = true`、REWIND、配额、
   `write_file` 同级规则），单独决定。
5. **自动注入整个工作区**：拒绝。只注入调用里显式列出的文件。

---

## 6. 测试

**JVM（纯函数）**

- `JavaScriptInvocation`（把 script + inputs 组装成最终求值源）：JSON 键/值、U+2028/U+2029
  转义、上限（文件数/单文件/总量/总源长）、空输入、重复路径；
- `input_files` 的 schema 条件：没有 `read_file` 时参数不出现、`execute` 收到时拒绝；
- 多路径策略解析（纯函数）：取最严、任一 Blind/Deny 整次拒绝、Ask 汇总。

**设备（androidTest）**

- `SandboxRuntimeTest` 增加：注入 `inputs` 后 `INPUT_FILES["a.csv"]` 可读；脚本无法看到文件
  系统（`typeof require` 仍 `undefined`）；超限/二进制拒绝；10 秒超时不变；probe 仍通过。
- 集成（ChatViewModel/gate）：允许、Blind、Deny、Ask 四条路径；没有 `read_file` 时参数不存在；
  fence 失败时纯计算仍可用、`input_files` 消失。

**文档**：`docs/README.md` 测试表；`docs/05` 沙箱一节；`AGENTS.md` 不变量。

---

## 7. 实施顺序与工作量

1. 纯函数 `JavaScriptInvocation` + 单测（约 0.5 天）。
2. `SandboxRuntime.run(script, inputs)` + 设备测试（probe 不变，约 0.5 天）。
3. `JavaScriptTool` 参数/执行、`ChatViewModel` 构建条件与 gate 的多路径策略（约 0.5–1 天）。
4. 文档（05、08、07、13、AGENTS、README）+ 设备验证（约 0.5 天）。

总计约 2–2.5 天。

---

## 8. 开放问题

1. 上限照 §3.1（5 个 / 单 64 KiB / 总 256 KiB）实现？
2. 同一文件重复列出：拒绝还是去重？（建议去重并说明，不静默多读。）
3. `Blind` 路径：整次拒绝（建议）还是跳过？——跳过会让脚本看到的信息取决于哪些路径被隐藏，
   不建议。
4. 审批对话框列出全部文件名（建议）还是只显示数量？
5. `output_path` 是否列入下一份提案（现在不做）。
