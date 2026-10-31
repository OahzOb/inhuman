# 09 — 审批与权限模型

配套：[08-port-philosophy.md](08-port-philosophy.md)（理念）、[05-files-sandbox.md](05-files-sandbox.md)
（沙箱契约）、[06-agent-loop-ui.md](06-agent-loop-ui.md)（当前审批门）。

**状态：已实现，并在设备上验证过。** 前五节保留的是"为什么这样设计"；§7 记录
哪些已经落地、§8 是仍未定的问题。判断"现在怎样"请看这两节，不要从前五节的
将来时推断。

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

### 唯一的例外是 SSH，它不适用"可逆副本"的论证

SSH 工具不在会话目录里执行。远端的一台机器不是副本，删掉的数据不会因为工作区
还在而回来，上传覆盖的远端文件也不会。所以 SSH 不能走"默认不问，反正可逆"的
论证，也不能只靠一个普通的单次审批。

处理方式是**能力授权**，不是逐条审批：

- Settings 的全局开关默认关；关着时每个 SSH 工具都不出现在任何工具列表、助手
  规则、全局审批表或 home 的 tools 摘要里，也不进入请求的 tool schemas；打开后
  助手仍须显式选中要用的每一个 SSH 工具；
- 每个会话第一次调用时，弹专用对话框，显示 host/user/第一个操作（命令，或传输的
  两端路径），要求勾选"可能造成不可逆损坏"，才允许；允许后写
  `conversations.sshAllowed`，覆盖该会话的所有 SSH 工具；
- 之后该会话的 SSH 调用走普通 `ToolPolicy`，默认 Free，不再逐条问；
- 撤销：对话顶栏菜单可撤销本会话；全局开关关闭会清掉所有会话的授权，
  重新打开后必须重新授权；
- 如果用户把 SSH 的规则设成 `Ask`，第一次调用会先过能力授权，再过单次审批；
  单次审批的 details 是脱敏的，不显示密码/私钥。

这不是把默认审批改成"每次问"，而是承认一个结构性例外：**默认边界仍然关闭，
打开后用户明确授权一个不可逆的能力。**

### 工具集合与审批是两件事

会话菜单里的“本会话工具”只改变**有哪些工具**，不改变**调用要不要问**。它不
新增审批层，也不覆盖上面的全局/助手/路径规则；选中的工具仍然按该会话的
`ToolPolicy` 执行。集合只能由用户改，从下一回合生效，模型没有可以调用的入口。
理由和边界见 [11-conversation-tools.md](11-conversation-tools.md)。

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

### 4.1 默认：全部不问

| 工具 | 默认 |
| --- | --- |
| 全部（含 `write_file`、`delete_file`、`move_file`） | **不问** |

见 §2：目录内的一切都是副本，问也没有新信息。
落地时改掉的一处：`DeleteFileTool.needsApproval` 从 `true` 改为 `false`。
现在没有任何工具的内置钩子要求审批，`FileToolsTest` 把"默认要问的工具集为空"
写成了断言。

**付费披露不是审批门。** `web_search`、`web_fetch` 以及图片/视频/语音/转写/决策
这组媒体工具默认仍是 `Free`；`PAID` 徽标只负责让成本可见
（[08-port-philosophy.md](08-port-philosophy.md) §1.4），要不要先问由这里的规则
决定。不要把"付费"做成默认 `Ask`：那会同时破坏"默认不打断"和"审批只在用户真的
要求时出现"这两条。

### 4.2 用户可收紧任何一项

全局、每 agent、每路径三处都可配，合成规则只有一条：**取最严**（§3）。
用户可以把 `delete_file` 配成要问，也可以把某个目录配成 `read-only`。

### 4.3 agent 自定义

沿用现有结构：助手本来带工具清单（`ConversationEntity.toolsJson`）。
**agent 的规则与用户的规则并列适用，取更严**——它不再是"只能收紧"的单向关系，
因为默认已经是最松的"不问"，所以两边都只能往上加。

### 4.4 路径策略

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

### 5.1 `delete_file` 的默认值反过来了

`DeleteFileTool.needsApproval` 当时硬编码 `true`（`FileTools.kt:316`），现在是
`false`；其余七个文件工具本来就是 `false`。这一改当时会让一处测试与一处 UI 断言
失效（`FileToolsTest.kt:80` 断言有工具需要审批、`ToolCallUi.State.Declined` 的
可达性）——处理方式是**换断言，不是删断言**："默认问"换成了"默认不问、配置后问"，
`Declined` 因此只能经由一条 `Ask` 规则到达。

### 5.2 路径策略要能"拒绝"，不只是"问"

`read-only` 与 `agent-blind` 的语义是**拒绝**，而 `needsApproval` 只有"问/不问"
两态。当时的两个选项是：在钩子之外加一个"这个调用被策略禁止"的判定，或让每个
工具在 `execute` 里返回 `ToolResult.error(...)`。

**选了前者，而且已经实现**：拒绝发生在调用**之前**，由 `PolicySet` 统一裁决；
`Deny` 与 `Blind` 排在 `needsApproval` 之前处理（`ChatViewModel.gateEngineCall`，
拒绝文案带 `REFUSED_MARKER`）。这样就不需要每个工具各自记得查一次策略。

### 5.3 审批决定进会话审计

按 08 的 Q5（日志给用户看、要更详细），每一次"问了、用户答了什么"都应当是
**持久事件**，而不是只活在内存里的 UI 状态。

**已做（schema 14 → 15）。** 提问之前先写一行 `approval_audit`：会话、消息、
工具调用 id、工具名、规范化后的相对路径、规则来源、`askedAt`。拿到答案后把
同一行改成 `allowed` / `declined` / `timed_out` 并写 `decidedAt`；SQL 带
`WHERE decision = 'asked'`，所以超时和迟到的点击不会互相覆盖，先到的终态生效。
会话删除时级联删除；**回退某个回合不会删掉审计行**，因为 `messageId` 不是外键——
审计记的是"问过没有"，不是消息内容。

**规则来源也进审计，并显示在弹窗里**：`global` / `assistant` / `path` 来自
`PolicySet.resolve`，取最严时并列的来源全部列出；工具自身的 `needsApproval`
记为 `tool`。`PolicySet.effective` 仍然只有一个答案，两个函数不会分叉。

**等待有超时。** 10 分钟无人回答，按拒绝处理：审计记 `timed_out`，提示条
清掉，调用不执行，回合结束。不是"一直等"，也不是"超时后自动放行"——不回答
永远不等于同意。超时时间不是设置项，理由见 §8。

**审计写失败不改变任何决定**：两次写库都包在 `runCatching` 里，失败只进
诊断日志。审计是记录，不是闸门；让一个写不进库的日志去决定调用是否发生，
等于把审计变成第二个权限系统。

会话菜单 → **审批历史** 可以读这张表（最新的在前），显示时间、工具、路径、
规则和结果。SSH 首次授权不写这张表：它是能力授予，持久状态是
`conversations.sshAllowed`，另有诊断日志，两者不是同一个问题（§7）。

---

## 6. 存储

```kotlin
// 全局：每工具一个值。缺席 = 内置默认（全部不问）
approval_defaults(toolName TEXT PRIMARY KEY, policy TEXT NOT NULL)

// 每 agent：与全局并列适用，取更严；缺席即无规则
agent_approval(agentId TEXT, toolName TEXT, policy TEXT NOT NULL,
               PRIMARY KEY(agentId, toolName))

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

**已实现**：

- `ToolPolicy` + `PolicySet`（`:core:ai/policy/`）—— 四档策略与"取最严"合成，
  `resolve` 同时给出生效来源，**19 个测试**
- 存储：schema **7 → 8**，三张新表（`approval_defaults`、`agent_approval`、`path_policy`），
  **未改动任何既有表**
- `PolicyRepository` —— 装载与写入
- `DeleteFileTool.needsApproval` 默认 `false`（§5.1）
- **执行循环里的裁决**（`ChatViewModel.gateEngineCall`）：`Deny` / `Blind` 是**拒绝**，
  因此排在 `needsApproval` **之前**——那个钩子只会说"问"，而对一件用户已经禁止的事
  发问，是答非所问。`Ask` 与钩子合入同一分支。
- **三处编辑器**：全局规则在设置页，按助手的规则在 Agents 的编辑对话框，按路径的
  规则在文件浏览器每一行的锁按钮上。三者写的是同一套表，界面上显示的就是工具调用
  会取到的值。
- **`Blind` 真的隐藏**：`FileTools.all` 接受 `hidden` 判定，`ChatViewModel.buildTools`
  从已解析的 `PolicySet` 供给它；`ListFilesBlindTest` 把"名字也不出现"写成断言。
- **SSH 的第一道门是能力授权**（schema 8→9 的 `conversations.sshAllowed`），
  不是 `needsApproval`。`ChatViewModel.gateEngineCall` 在普通策略分支之前处理它；
  `SshExecTool.needsApproval` 返回 `false`。授权后按普通策略执行，撤销/全局关闭
  会清掉持久授权；全局关闭后再打开，下一次 SSH 调用必须重新授权。
- **审批审计**（schema 14→15 的 `approval_audit`）：提问前写 `asked`，回答后写
  `allowed` / `declined` / `timed_out` 与 `decidedAt`；规则来源一并存储。10 分钟
  无回答按 `timed_out` 拒绝。会话菜单可读历史。写库失败只记诊断，不改变决定。

三个不在 diff 里显眼的细节：

- **路径先经 `SessionWorkspace` 解析再 `relativize`，绝不用模型写的原文。**
  规则按规范化的相对路径存储，拿原始参数去比对，会让 `a/../private/x.md`、
  前导 `./`、或绝对路径命中一个含义不同的键——和 `resolve()` 存在的理由相同，
  只是这里用在查表而不是开文件。沙箱拒绝的路径返回 null，即该文件的规则不适用：
  因为那个文件不存在。
- **`Blind` 与 `Deny` 给模型的是同一句话。** 暴露差别等于邀请它去试探差别。
- **每轮读一次规则**，不是每次调用；读失败返回空规则而不是抛异常——
  读不到配置不是拒绝所有工具调用的理由，未配置本来就是出厂状态。

### 已在设备上端到端验证

在全新的 v8 库里直接插入 `delete_file=Deny` 与 `write_file=Blind`，开启 demo 模式、
工具全开，发一条消息，transcript 读回：

```
Used 2 tools
2 failed
write_file — [refused] This path or tool is not available in this conversation.
delete_file — [refused] This path or tool is not available in this conversation.
```

两条规则都命中，循环继续而没有中止，chip 显示 `failed`。

**`Ask` 也验证过了，而且是唯一一个由用户决定的策略**，所以它最值得驱动一次。
用设置界面设 `delete_file=Ask`，`write_file` 不动，让 demo 脚本能走到删除那一步。
数据库里的时间线：

```
[refused] ×2                              早先 Blind/Deny 的测试
Created demo/notes.txt (21 B, 1 lines)    write_file 无规则 → 跑了
[declined] The user declined this call.   Ask → 拒绝
Replaced demo/notes.txt (21 B, 1 lines)   第二次跑到写入
Deleted demo/notes.txt                    Ask → 允许
```

**两个方向都是对着文件系统确认的，不是只看 transcript。** 拒绝之后
`notes.txt` 仍在、21 字节；允许之后 `find` 无输出、workspace 目录为空。
**一道只记录决定而动作照旧发生的门，在 transcript 上看起来一模一样**——
所以要查文件，而不是看 chip。

对话框本身读回的是：

```
Allow this action?
Delete demo/notes.txt from this conversation's folder?
{"path":"demo/notes.txt"}
```

**另外查了一件因为代码形状而值得怀疑的事。** 旧循环在没跑完时从 `finally` 里以
`NonCancellable` 补写未执行调用的结果，而一个挂起中的 `awaitApproval` 正是"恢复时
可能重复回答同一个调用"的形状。实测没有：`demo-call-0` 一条结果、`demo-call-1`
一条结果，没有多余的 `[skipped]` 行。现在这一步由 engine 的
`EngineEvent.ToolCallSkipped` 显式发出，顺序由事件流保证，不再依赖 `finally` 的补写。
**这是查出来的而不是推出来的**——重复的工具结果只会以"下一条消息 400"的形式暴露。

至此审批层没有未验证的行为了。

### 独立复验（新增，非原作者所做）

**目的**：不靠前一节的叙述。一个断言在文档里写过一遍之后，第二个人读到的只是那句话，
所以这里重新跑的是**默认值**这条路径——它是整个模型里最该被独立确认的一条。

```
设备    dsh_tablet AVD (2560x1600)，全新安装（pm clear）
步骤    设置 → Demo mode 开 → 新建会话 → 发一条消息
transcript 读回:

  Used 2 tools
  write_file — Created demo/notes.txt (21 B, 1 lines)
  delete_file — Deleted demo/notes.txt
  Demo mode with tools. Creating a file, then deleting it.
  Configure a rule on delete_file if you want to be asked first.
```

**没有弹窗**，删除真的执行了，两条工具结果都在。这一条把"默认不问"从设计意图
变成了设备上的观测。demo 提供者的文案也已随之更新（它自己就会告诉用户去哪儿配规则）。

**这次的范围**：默认值这条路径是新跑的。三种规则源（`approval_defaults`、
`agent_approval`、`path_policy`）各自通过自己的界面设置并在设备上核对过，
`Blind` 的 `list_files` 断言也在前文记录里——本次没有重跑那三条，它们的状态以前文为准。

**`Ask` 一档后来补跑了**（同一台 `dsh_tablet` AVD，全新安装），而且两个方向都对着
文件系统核对：

```
设置里把 delete_file 设成 ask first → 发消息
  弹窗出现:
      Allow this action?
      Delete demo/notes.txt from this conversation's folder?
      {"path":"demo/notes.txt"}

点 Decline
  磁盘   demo/notes.txt 仍在（21 B）
  正文   delete_file — [declined] The user declined this call.

再发一条，点 Allow
  磁盘   workspace 目录为空
  正文   write_file — Replaced demo/notes.txt (21 B, 1 lines)
         delete_file — Deleted demo/notes.txt
```

**拒绝之后文件仍在、允许之后文件消失。** 这是补跑的重点：一道只记录决定、而动作照旧
发生的门，在转录上看起来完全一样，所以必须查磁盘而不是看 chip。

**界面**：全局规则在设置页（"Before a tool runs"），按路径规则在文件浏览器
（每行的锁按钮，四档按名字列出：Agent may change this / asks first / cannot
read or change / Hidden from the agent）。两者都写库后重读，不在内存里打补丁——
行上显示什么，必须是工具调用真正会取到的值。

**`Deny` 的文案曾经名不符实，已修。** 文件浏览器把这一档写成 "Agent may look,
not change"，而 `gateEngineCall` 对 `Deny` 的**任何**调用都返回拒绝——读也一样。
设置页一直写的是 "refuse"；两处不一致，错的是能读到的那一处。现在两处都说
"cannot read or change"/"助手不能读写这个文件"。

**`Blind` 曾经名不符实，已修。** 它的文档承诺路径"不出现在 `list_files`"，
而 `ListFilesTool` 从不查策略，所以它当时和 `Deny` 行为完全相同：按名拒绝，
但**名字仍然可见**——而名字正是会泄漏的那部分。现在 `FileTools.all` 接一个
`hidden` 判定，`ChatViewModel.buildTools` 从已解析的 `PolicySet` 供给它，
`ListFilesBlindTest` 把这条承诺写成行为断言。

**设备上验证了接线**（单元测试只测过滤器本身，接错线它照样形同虚设）：给
`pol-conv` 的 `demo` 路径设 `Blind` 后，模型对 `demo/notes.txt` 的写入与删除
**都被拒**——规则按祖先传播到了里面的文件，这是对的。

**顺带确认了一件我一开始误判的事。** 看到 `[refused]` 出现在 `demo/notes.txt`
上时，我以为拒绝消息泄漏了路径。**不是**：模型是先自己猜的路径（demo 脚本里
硬编码的），拒绝消息**不含路径**——这正是当初把 `Deny` 与 `Blind` 写成同一句话
的理由。模型知道路径，是因为它自己写的，不是因为被回了什么。

**per-assistant 规则后来也接上了界面**：Agents 的编辑对话框为每个工具给出四档，
草稿存在 `AgentsViewModel` 里，Save 时在助手行写入之后逐条写 `agent_approval`
（先有助手、后有规则，否则一条指向不存在助手的规则既看不见也删不掉）。三处界面
都在模拟器上设过并读回数据库核对，包括三者同时设置的情况。

**这同时补上了之前那个"demo 工具循环跑不起来"的欠账**：当时发不出消息的真正原因是
`pm clear` 把 demo 模式开关也重置了，不是默认助手缺文件工具——`toolsJson = ''`
其实**允许全部十一个工具**。

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

1. **审批疲劳。** 这不是默认状态的问题（默认不问），而是**用户配了要问之后**
   的问题：一次覆盖 10 个文件会问 10 次。要不要"同一 turn 内对同一目录的同类动作
   批量确认"？我倾向**先不做**，等有人真的这么配了再说。
2. **超时。已定：10 分钟，算拒绝。** 用户不回答时，`awaitApproval` 用
   `withTimeoutOrNull` 结束等待，审计记 `timed_out`，调用不执行，回合结束。
   不选"取消"是因为取消在 UI 上和"用户按了停止"分不开；不选"回到 pending"
   是因为那只是把一直等改成每一轮都来问一次。**"不回答就执行"从未是一个选项。**
   10 分钟不是设置项：设置项会变成一个新的档位，而这里要的不是可调策略，是
   一个不会永远挂起的默认值；被通知到的用户十分钟足够回到对话框。
3. **用户规则与 agent 规则的冲突展示。已做。** 审批弹窗显示生效规则的本地化
   来源（全局设置 / 助手设置 / 路径规则 / 工具本身），并列时全部列出；
   `PolicySet.resolve` 是唯一来源映射，`effective` 由它派生，所以弹窗说的和
   裁决用的是同一个答案。审计行存的是同样的来源名。
