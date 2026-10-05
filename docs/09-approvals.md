# 09 — 审批与权限模型

配套：[08-port-philosophy.md](08-port-philosophy.md)（理念）、[05-files-sandbox.md](05-files-sandbox.md)
（沙箱契约）、[06-agent-loop-ui.md](06-agent-loop-ui.md)（当前审批门）。

**状态：设计，未实现。** 本文定的是"应该怎样"，不是"现在怎样"。

---

## 1. 三个问题，三层回答

| 问题 | 回答 | 可配置？ |
| --- | --- | --- |
| 工具能碰到哪些**路径**？ | 会话目录，且仅此 | **不可**（`SessionWorkspace` 保证） |
| 每次调用**要不要问**用户？ | 三层策略，取最严 | **可** |
| 用户**自己**能不能读写会话目录？ | 能，通过 app 界面与 `DocumentsProvider` | 不受策略影响 |

第一条是 08 的硬边界，第三条是用户对自己的数据的主权。**中间那条是本文的主题**——
它包含一个我认为你必须知道的冲突。

---

## 2. 安全来自结构，不来自提示

**默认不需要任何审批。** 我没有立刻接受这一条，提了一个"删除永远要问"的地板，被否了——
而否决是对的，我原来的推理错在一个地方：

> 我把安全建在了**提示**上。这套设计把安全建在**结构**上。

结构是这样的：

```
agent 只能看见、只能改会话目录        ← SessionWorkspace，接口级围栏
用户上传是 copy，不是 move            ← 原件永远不动，也不在目录里
```

**推论：目录内的一切都已经是可逆的。** 删掉一个文件，删掉的是一份副本；
搞坏一个文件，原件还在用户那里。所以：

- 在这个目录里**打断用户去问"确定吗"没有意义**——因为答案永远是"确定"。
  一个总是得到同一个答案的对话框不是安全措施，是摩擦。
- 而且它**有害**：审批疲劳会让真正需要确认的时刻（如果有）被无脑点过。

**这个立场更强，不是更弱。** 它可检验：围栏失效时，`SessionWorkspace` 的测试会红；
而"用户会不会点错"无法检验。

### 唯一的例外不在目录内

`SessionWorkspace` 之外的东西——系统文件、其他 app 的数据、网络——
agent **根本够不到**，所以那里也不需要审批：没有可批准的动作。

**"审批"因此不是这套设计的主要安全机制，而是可选的收紧手段。**
用户想更谨慎时可以配，但默认不配，因为默认状态下没有需要防的东西。

---

## 3. 取最严：一条规则，不是三层特例

用户与 agent 各自的规则**同时适用，取更严的那个**。这不是第三套逻辑，
而是全部逻辑：

```
effective = strictest( 用户规则, agent 规则 )
```

"默认不需要审批"是这条规则在**两边都没配**时的取值。
用户配了"写要问"，agent 没配 → 问；agent 配了"删要问"，用户没配 → 问。

**没有地板，没有不可配置项。** 用户是这个目录的主人，包括决定删文件要不要问。

---

## 4. 默认值与配置

### 3.1 默认：全部不问

| 工具 | 默认 |
| --- | --- |
| 全部（含 `write_file`、`delete_file`、`move_file`） | **不问** |

见 §2：目录内的一切都是副本，问也没有新信息。
**现有的 `DeleteFileTool.needsApproval = true`（`FileTools.kt:316`）要改成 `false`。**

### 3.2 用户可收紧任何一项

全局、每 agent、每路径三处都可配，合成规则只有一条：**取最严**（§3）。
用户可以把 `delete_file` 配成要问，也可以把某个目录配成 `read-only`。

### 3.3 agent 自定义

沿用现有结构：助手本来带工具清单（`ConversationEntity.toolsJson`）。
**agent 的规则与用户的规则并列适用，取更严**——它不再是"只能收紧"的单向关系，
因为默认已经是最松的"不问"，所以两边都只能往上加。

### 3.4 路径策略

对会话目录内的**具体文件或目录**设规则，**只对 agent 生效**：

| 取值 | 含义 |
| --- | --- |
| `free` | 不问（**默认**） |
| `needs-approval` | 任何写/删都问 |
| `read-only` | agent 只能读；写与删**拒绝**（不是"问"） |
| `agent-blind` | agent 看不见它（不出现在 `list_files`，无法 `read_file`） |

`agent-blind` 比 `read-only` 更彻底——**文件名本身可能泄密**。

**路径规则限制的是 agent，不是用户。** 用户永远能读写自己的会话目录；
设成 `read-only` 是"agent 不许动它"，不是"我也不能动"。

---

## 5. 三个具体后果

### 4.1 `delete_file` 的默认值要反过来

现在的 `DeleteFileTool.needsApproval` 硬编码 `true`（`FileTools.kt:316`），
按 §4.1 要改成 `false`。其余六个工具已经是 `false`。

这一改会让现有的一处测试与一处 UI 断言失效（`FileToolsTest.kt:80` 断言有工具需要审批、
`ToolCallUi.State.Declined` 的可达性），要一并处理——**不是把测试删掉，
而是把一个"默认问"的断言换成"默认不问、配置后问"的断言**。

### 4.2 路径策略要能"拒绝"，不只是"问"

`read-only` 与 `agent-blind` 的语义是**拒绝**，而现在的 `needsApproval` 只有
"问/不问"两态。所以要么：

- 让 `needsApproval` 之外多一个"这个调用被策略禁止"的判定，或
- 在工具 `execute` 里返回 `ToolResult.error(...)`

**我倾向前者**：拒绝应当发生在调用**之前**、由策略层统一决定，而不是让每个工具
自己去查策略——否则八个工具就是八处可能忘记检查的地方。

### 4.3 审批决定应当进会话日志

按 08 的 Q5（日志给用户看、要更详细），每一次"问了、用户答了什么"都应当是
**持久事件**，而不是只活在内存里的 UI 状态。

这也直接修掉 B4：现在用户拒绝一轮里的第一个工具调用时，同一个 round 里
**后面的调用不会被处理**（`ChatViewModel.kt:1128` 直接 return），于是
`toolCallsJson` 里留着没有结果的调用——app 自己的注释说这种 transcript 是
API 会拒绝的请求（`:1282-1292`）。事件化之后，"这次调用没有结果"本身是一个可
补写的记录，而不是一个缺口。

---

## 6. 存储

```kotlin
// 全局：每工具一个值。缺席 = 内置默认（全部不问）
approval_defaults(toolName TEXT PRIMARY KEY, policy TEXT NOT NULL)

// 每 agent：与全局并列适用，取更严；缺席即无规则
agent_approval(conversationId TEXT, toolName TEXT, policy TEXT NOT NULL,
               PRIMARY KEY(conversationId, toolName))

// 每路径：只作用于 agent。缺席 = free
path_policy(conversationId TEXT, relPath TEXT, policy TEXT NOT NULL,
            PRIMARY KEY(conversationId, relPath))
```

`relPath` 用**相对路径**而不是绝对路径：会话目录可以整体移动，绝对路径会失效。
裁决时必须走 `SessionWorkspace.resolve()` 得到规范化路径再比对——
**不能用字符串前缀匹配**，否则 `a/../b` 这类输入会绕过策略。

这与 05 的既有规则一致：`resolve()` 是路径变成 `File` 的唯一入口。

---

## 7. 实现状态

**已实现**（`41d4e5b`）：

- `ToolPolicy` + `PolicySet`（`:core:ai/policy/`）—— 四档策略与"取最严"合成，**14 个测试**
- 存储：schema **7 → 8**，三张新表（`approval_defaults`、`agent_approval`、`path_policy`），
  **未改动任何既有表**
- `PolicyRepository` —— 只读装载；写入接口等有界面时再加
- `DeleteFileTool.needsApproval` 默认 `false`（§4.1）

**未实现，两端都缺**：没有界面设置规则，也**没有任何地方在工具调用前查询 `PolicySet`**。
所以**当前配置一条规则不会有任何效果**。模型、存储、迁移都在且有测试，
但两端没接线。

### 迁移已实测（这是有真实数据风险的一步）

不是"Room 会校验所以应该没问题"，而是真跑了负载测试：
用 `git worktree` 构建 v7 版本 → 装入模拟器 → 在 v7 库里种入 canary 会话与两条消息 →
`VACUUM INTO` 导出已 checkpoint 的单文件 → 推回设备 → 升级到 v8 构建。结果：

```
version        8          （从 7 升上来）
canary conv    1          MIGRATION CANARY
canary msgs    2          the needle that must survive
                          the reply that must survive
new tables     3
WAL            0 bytes    （已 checkpoint）
错误/崩溃       无
```

数据完好，三张新表创建，无 schema 校验失败。

**顺带学到的**：Android 上 `am force-stop` **不触发 WAL checkpoint**，所以
`exec-out run-as cat databases/inhuman.db` 拉到的可能只是刚创建的空壳（4 KB、版本 0），
真实数据在 `-wal` 里。判断 schema 版本必须读 **db + wal 合起来**——用 `sqlite3` 打开
目录里的两个文件即可，或用 `VACUUM INTO` 导出合并后的单文件。
我第一次读到"版本 0"就是这个原因，差点得出错误结论。

---

## 8. 待定

1. **`agent-blind` 要不要现在就做。** 它是四档里唯一需要新增行为的，
   实现面也最大（要影响 `list_files` 的输出，不只是拦截一次调用）。
2. **审批疲劳。** 这不是默认状态的问题（默认不问），而是**用户配了要问之后**
   的问题：一次覆盖 10 个文件会问 10 次。要不要"同一 turn 内对同一目录的同类动作
   批量确认"？我倾向**先不做**，等有人真的这么配了再说。
3. **超时。** 用户不回答怎么办？现在的行为需要查证（`06-agent-loop-ui.md`
   描述的是"停等"），但**绝不能不回答就执行**。
4. **用户规则与 agent 规则的冲突展示。** 取最严是规则，但用户看不到"为什么这次
   问了"。要不要在审批对话框上说明是哪条规则触发的？我倾向要——否则用户会以为
   是 bug。
