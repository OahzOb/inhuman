# 配置工坊（内置 app 配置工作区）— 评估与提案

> **状态：P0 已实现（2026-11-27）。**
> 一个内置永久工作区（`workspaces.kind = "studio"`），首页常驻入口与新建入口；
> 工坊里的非场景对话由宿主附加 6 个辅助工具：读助手、准备助手草稿、读各永久工作区的
> 根 `AGENTS.md`、准备指令文件草稿。**所有草稿都只打开用户已有的编辑器预填，保存与否由
> 用户决定**，工具本身不写任何行、文件或审批规则。没有 schema 迁移：`workspaces.kind`
> 在 v28 已有，本次只新增一个取值。
> 关联：`rp-agent-studio.md`（同一套"工作区类型 + 宿主辅助"机制）、
> `docs/07-gotchas.md` A31/A32、`docs/11-conversation-tools.md`、
> `docs/13-product.md`、`docs/08-port-philosophy.md` §1.1/§1.2/§3.4。

---

## 0. 结论先行

- **可行，且不引入新的权限语义。** 配置工坊里被收口的这些东西和 RP 的辅助工具同类：
  读的是 app 自己的配置（助手行、各工作区根目录的 `AGENTS.md`），准备的是**草稿**；
  真正写入仍然只发生在用户自己的编辑器里（助手编辑器、Files 文本编辑器）。
- **关键设计：工具不写配置，连"点一下应用"都没有。** 提案卡的动作只是导航：
  - 助手提案 → `AgentsViewModel.openProposal`：更新时从现有行开始、只覆盖提案改动的
    内容字段；新建时用"新助手"的默认值，然后由用户在编辑器里点保存。
  - `AGENTS.md` 提案 → `FilesViewModel.openProposedEditor`：在 Files 的文本编辑器里打开
    预填文本；保存走原有 `WorkspaceTransfer`，配额、单文件上限、原子替换都不变。
  - 因此没有新增任何配置写入路径，也不需要讨论"提案被撤销后怎么回滚"。
- **提案不能携带能力字段。** 助手提案只有名字、系统提示词、角色卡；工具、服务商、模型、
  推理、建议回复设置、审批规则一律不在提案里（编辑器里由用户决定）。提示词库仍然对模型
  不可见。
- **工作区只用显示名，不用目录 id。** 指令提案里带的是工作区显示名；点按时才由 app 解析
  （`resolveInstructionWorkspace`）。`workspaces.id` 是目录名，仍不进请求（A31）。
- **明确不做**：agent 改工具/审批/SSH/下载/服务商/模型；删除助手；读写提示词库；
  自动重读 `AGENTS.md`；跨工作区读写 `AGENTS.md` 以外的任何文件。

---

## 1. 需求

> "能不能也创建一个内置 app 配置工作区，这里可以让 agent，创建，管理 agent，
> 管理提示词注入文件，之类的。"

拆开就是两类"内容配置"：

| 对象 | 今天在哪里配 | agent 能帮什么 |
|---|---|---|
| 助手（名字、系统提示词、角色卡） | 助手编辑器 | 读现状、起草新助手或改写；用户保存才生效 |
| 提示词注入文件（各永久工作区根 `AGENTS.md`） | Files 文本编辑器 / 导入 | 列出、读取、起草新版本；用户保存才生效 |

不包含：工具选择、审批规则、SSH、`download_url`、服务商与模型、提示词库。这些要么是
**能力**（只能由用户改，模型不该有"提议改能力"的入口），要么是产品已决定的模型不可见
数据（提示词库，`docs/13-product.md`）。

---

## 2. 为什么继续用"工作区类型"，而不是新的模式或开关

`docs/07-gotchas.md` A31 给 RP 工坊定下的三条理由原样成立：

- `workspaces.kind` 是**数据属性**（和 `isPermanent` 同类），不是权限档；
- 它只决定宿主附加哪些辅助工具；有能力的工具仍来自助手/对话的 `toolsJson`；
- 模型没有创建/进入/标记工作区的工具，进工坊是用户点入口。

新增的工作区类型 `STUDIO` 只加一个字符串常量，**不需要迁移**：v28 的列已经在了，旧安装
里没有行会带这个值，工坊由首页入口懒创建（`ConversationRepository.ensureStudio`，与
`ensureWorkshop` 共用同一把锁）。

同一套"零能力辅助"按下法收口：

| 类别 | 例子 | 本次处理 |
|---|---|---|
| 能力 | 文件、SSH、下载、B 站、媒体 | **不变**，仍由助手/对话决定；工坊不额外给 |
| 内容 | 助手名字/提示词/角色卡、`AGENTS.md` | 允许 agent 读并起草；写入仍由用户编辑器执行 |
| 宿主辅助 | 6 个 `app_*` 工具 | 只在工坊的非场景对话里附加；不进目录/审批/缺口报告 |
| 用户手势 | 保存、删除、重命名、Reload `AGENTS.md` | 只有用户能做 |

---

## 3. 六个辅助工具

条件：`conversation.workspaceId` 指向 `kind = "studio"` 的工作区，**且**该对话不是角色
扮演场景（`roleplayActive == false`，与 RP 辅助同一条规则：故事里不出现配置工具）。
全部 `mutates = false`、无网络、无执行、不进 `ToolCatalog`，因此不出现在助手编辑器、
会话工具 sheet、审批规则表和 `unavailable_tools`。

| 工具 | 作用 | 边界 |
|---|---|---|
| `app_list_assistants` | 列助手：名字、是否默认、提示词长度与预览、是否有可用角色卡及卡名、模型 id | 不返回工具名、审批、服务商 |
| `app_read_assistant` | 读一个助手的完整系统提示词（>64k 截断并标注）与角色卡 JSON | 只读内容字段；名字精确匹配；重名报歧义 |
| `app_propose_assistant` | 起草"新建助手"或"改名字/提示词/角色卡" | 见 §4；点按打开助手编辑器 |
| `app_list_instructions` | 列**永久**工作区：名字、类型、根 `AGENTS.md` 是否存在及大小 | 临时工作区不列（指令不可能活得比对话久） |
| `app_read_instruction` | 读一个永久工作区根 `AGENTS.md`（16 KiB 读上限） | 只读这一个文件名，经该工作区自己的 `SessionWorkspace` 解析 |
| `app_propose_instruction` | 起草写入/替换/清空某个工作区的 `AGENTS.md` | 省略 `workspace` 时用本对话自己的工作区；点按打开 Files 编辑器 |

所有提案都从**工具结果的完整 content** 解析、渲染成卡片，transcript 只留
`persistedContent` 的一行摘要。和 RP 提案一样，刷新/重进后卡片不可点（P0 限制）。

---

## 4. 助手提案：草稿，不是修改

`AssistantProposal`（`ConfigProposals.kt`）的字段：

| 字段 | 含义 |
|---|---|
| `agentId` | 要改的助手；null 表示新建 |
| `name` / `changeName` | 提议的名字；更新时只有确实改名才为 true |
| `prompt` / `changePrompt` | 完整新提示词；null 表示保留原提示词；空串表示清空 |
| `cardJson` / `changeCard` | 新角色卡（canonical 或空串=清空） |
| `summary` | 卡片上的一行预览 |

规则：

- **新建**：名字必填；提示词和/或角色卡至少有一个（与编辑器 `canSave` 同一条规则）；
  名字与现有助手重名时拒绝（避免此后"按名字更新"出现歧义）。
- **更新**：只把真正变化的字段放进提案；三者都没变时拒绝（"不会改变任何东西"）。
  清空提示词合法，仅当助手保留角色卡；清空角色卡用 `card: {}` 显式表达。
- 角色卡经 `RoleplayValidator`/`RoleplayCodec` 校验与规范化——和场景提案、`rp_validate`、
  助手编辑器同一套答案。
- 点按后的流程：`ChatScreen` 消费一次性 `openAssistantDraft` → `MainActivity` 打开助手屏
  → `AgentsViewModel.openProposal`：
  - 更新：`repository.byId(agentId)` 读现有行，`edit(agent)` 打开编辑器，再只覆盖提案里
    的字段；行已删除则显示错误而不新建。
  - 新建：`create()`（默认工具、默认服务商/模型、新 id）后覆盖内容字段；提示词为
    null 时留空，而不是编辑器默认文本。
  - **只有用户点保存才写 `agents` 行**；审批规则仍由编辑器自己的加载/保存路径处理。

---

## 5. 指令文件提案：命名工作区，不命名目录

`InstructionProposal` 带 `workspaceName`、`text`、`existingBytes`、`summary`，**不带
`workspaces.id`**。原因：id 就是目录名，A31 明确它不进请求；提案是会留在 transcript 里的
工具结果。

- 文本按读取端的规范化处理（去 BOM、CRLF/CR→LF、trimEnd），所以"已经一样"是真正的相等。
- 上限 16 KiB（`MAX_WORKSPACE_INSTRUCTIONS_BYTES`，和 pin 到 system prompt 的上限同一个），
  含 NUL 拒绝，空文本=清空。
- 点按时**重新**按显示名解析工作区：提案之后被改名/删除/出现重名，都在这里报错，绝不
  打开错误工作区的编辑器。
- 写入路径完全复用：`FilesViewModel.openProposedEditor` 只负责打开编辑器（允许文件尚不
  存在），保存仍走 `saveEditor` → `WorkspaceTransfer`（配额、单文件上限、原子替换、预留
  空间）。
- 生效语义沿用现状：保存只影响**新**对话；已有对话要点聊天菜单的 **Reload AGENTS.md**
  （一次冷请求）。本功能不添加文件 watcher，也不改 pin。

---

## 6. 不变量对照

| 约束 | 本设计怎么满足 |
|---|---|
| 没有 "full access"、能力不能提升（`docs/08` §1.1） | 没有权限等级/开关；六个工具不新增能力；文件/SSH/下载/审批全部不变 |
| 能力边界一句话（§1.2） | 仍成立：agent 只看见本工作区；读的是 app 自己交给它的配置（助手行、指令文件），写的是用户保存的编辑器 |
| 只有用户能改工具、没有 tool adds tools（`docs/11`） | 提案不携带工具/服务商/模型/审批；保存只发生在编辑器；`ToolCatalog` 不新增条目 |
| 助手是 ceiling、对话是复制 | 不变；工坊不改变任何对话的工具清单 |
| 花钱必须显眼（§1.4） | 本功能不发起额外请求；读取/提案都在当前回合内 |
| 回合/并发（Q4） | 不启动回合、不并行；点按只是导航 + 用户保存 |
| 可验证 | 工坊可见、提案卡显示对象与改动、编辑器可见全文；没有被隐藏的状态 |
| 可逆 | 提案不点=什么都没有；编辑器取消=不写；保存后可用原有编辑/删除/Reload 处理 |
| 诊断/备份（`docs/10`、`docs/04`） | 诊断只记失败与条目数，不记提示词/指令正文；kind 随 DB 备份，`AGENTS.md` 随工作区 |
| 工作区 id 不泄漏（A31） | 指令提案只带显示名；点按时解析 |

---

## 7. 改动面

| 文件 | 改动 |
|---|---|
| `core/db/.../db/Entities.kt` | `WorkspaceKinds.STUDIO`；kind 文档提及两个内建工坊 |
| `core/db/.../ConversationRepository.kt` | `studio()`、`ensureStudio()`；与 `ensureWorkshop` 共用 `builtInMutex` 和按 kind 查询 |
| `app/.../ui/chat/StudioToolsPlan.kt`（新） | 可用性纯函数：工坊 × 非场景 → 全部/全无；单测 |
| `app/.../ui/chat/ConfigProposals.kt`（新） | 两种提案的数据/校验/JSON/跨屏请求；工作区名解析；单测 |
| `app/.../ui/chat/StudioTools.kt`（新） | 6 个工具（读 2 + 提案 2 + 指令读 2）；宿主失败转成工具错误 |
| `app/.../ui/chat/ToolArguments.kt`、`RoleplayToolSchema.kt`、`ProposalSupport.kt`（新） | 与 `rp_create_scene` 共用的参数读取、角色卡 schema、预览/拒绝渲染 |
| `app/.../ui/chat/ChatViewModel.kt` | 按 kind 计算两种辅助；附加 6 个工具；解析提案；点按转成一次性 handoff |
| `app/.../ui/chat/ChatUiState.kt` / `ChatScreen.kt` | 消息上的两种提案 + 错误；两张提案卡；两个 handoff 的消费 |
| `app/.../MainActivity.kt` | pending draft/edit 状态；聊天 → 助手屏 / Files 的导航 |
| `app/.../ui/agents/AgentsViewModel.kt` / `AgentsScreen.kt` | `openProposal`：从现有行或新助手开始，只覆盖提案字段；屏幕消费 pending |
| `app/.../ui/files/FilesViewModel.kt` / `FilesScreen.kt` | `openProposedEditor`：允许文件尚不存在；屏幕消费 pending |
| `app/.../ui/ConversationsScreen.kt` / `ConversationsViewModel.kt` | 首页常驻"配置工坊"行 + 新建表入口；`openStudio` 懒创建 |
| 字符串 / demo | zh/en；demo 模式在配置工坊对话里用含 `config`（或"配置"）的消息输出两份提案 |
| 文档 | 本提案；`docs/01/02/04/05/06/07/11/13`、`docs/README.md`、`AGENTS.md` |

不需要改：`ToolCatalog`、助手编辑器既有字段、会话工具 sheet、审批规则表、schema/迁移。

---

## 8. 测试与验收

- 纯 JVM：`StudioToolsPlanTest`（真值表）；`ConfigProposalsTest`（新建/更新/无操作/
  重名/超限/清空/规范/JSON 往返/总量解析/工作区解析）；`StudioToolsTest`（六个工具的
  名称与非变异、读只返回内容字段、名字精确匹配与歧义、宿主失败转文本、提案摘要、
  工作区 id 不出现在结果里）；`WorkshopWorkspaceTest`（两种工坊各一行、互不混淆、幂等）。
- demo：`DemoProviderTest` 钉住两份 scripted call 和"结果回来之后不再重复提案"。
- `tools/ci.ps1` 无警告；360×640 与 1280×800 实跑：两个常驻入口、工坊中心、两张提案卡、
  助手编辑器预填与保存、Files 编辑器预填与保存、重名/删除后点按的错误态。

## 9. P0 明确不做 / 已知限制

- **提示词库仍对模型不可见**：不读、不写、不选；编辑器的"存入提示词库"仍由用户点。
  这是一个产品决定（`docs/13-product.md`），要改需要单独修订。
- **不删除助手**：删除是破坏性动作，留给助手列表；也不提供"设为默认"。
- **不持久化提案卡**：重载后只剩一行摘要（与 RP 场景提案一致的 P0 限制）。
- **不支持工作区重名时的自动选择**：显示名重复即报歧义，要求用户改名。
- 不做：agent 改工具/审批/SSH/下载/服务商/模型；收养现有工作区或多工坊；从工坊直接
  开一段普通对话；跨工作区读写 `AGENTS.md` 以外的文件；自动 reload。
