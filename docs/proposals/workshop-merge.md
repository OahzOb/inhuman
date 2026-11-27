# 工坊合并与工作区管理收敛 — 评估与提案

> **状态：已拍板并实施（2026-11-27）。**
> 决定：合并两个工坊（§10）；工坊完全 app-owned（不可改名、不可删除）；旧配置工坊在
> v29 降级为普通永久工作区（对话和文件不丢）。发布作为 **0.33.0**（schema v29），
> 0.32.0 之后的工坊上下文修复一并包含；实际发布仍由用户点名。
> 关联：`rp-agent-studio.md`（RP 工坊 P0，已实现）、`config-workshop.md`（配置工坊 P0，
> 已实现）、`docs/07-gotchas.md` A31、`docs/13-product.md`、`docs/04-data-persistence.md`、
> `docs/06-agent-loop-ui.md`。
>
> 这份提案回答两个问题：工作区和相关管理设计要不要重做；RP 工坊和配置工坊能不能合成
> 一个。结论是：**基础的工作区概念不重做，工坊这一层合并，管理界面必须认识工坊。**

---

## 0. 结论先行

1. **工作区（workspace）本身不需要推翻。** "临时 / 永久 + 目录名是随机 id + 名字只是显示名"
   是产品的边界设计（`core/db/.../db/Entities.kt:280-331`），删除、共享、回滚、备份都建立在
   它上面，换掉它要动的是整个数据层，收益和风险不成比例。真正的混乱不在工作区，而在
   **工坊这一层**。
2. **RP 工坊和配置工坊合并成一个内置工坊（`workspaces.kind = "workshop"`）。** 两者今天
   已经是同一种东西：一个内置永久工作区 + 一组宿主附加的零能力辅助工具 + 一段宿主写的
   上下文。把它们拆成两个，只多了用户要选的门、两套入口和两套文档，没有带来任何能力或
   安全上的区别。
3. **合并后辅助工具取并集：3 个 RP 辅助 + 6 个配置辅助**，仍然不进 `ToolCatalog`、不进
   审批、不进缺口报告；场景里照旧一个都不出现。`Workshop context:` 也只剩一段，同时讲清
   两件事（"这是工坊不是场景" 和 "草稿不是修改"）。
4. **管理界面要显式认识工坊**：Workspaces 列表加"内置工坊"徽标、禁止删除、批量删除排除
   内置行；首页只留一个常驻入口；新建表只留一项；工坊页（目前就是普通工作区详情）加说明，
   并去掉"删除"。
5. **迁移用 schema v29 改写 `kind`**：有 roleplay 行就把它升级为 workshop；若同时存在
   studio 行，studio 行降级为普通永久工作区（**对话和文件都不动**，不丢数据）；只有 studio
   行时，它升级为 workshop。不做跨目录搬文件、不搬对话——那是迁移期最容易出事的部分。
6. **代价要写在明处**：一个工坊对话每回合多约 9.8 KB 的工具 schema（≈2.2–2.8k tokens，
   其中约 1.1 KB 是两份重复的角色卡 schema）；工坊对话里会同时出现两套辅助工具。这些代价
   只发生在工坊，普通对话、场景、能力边界都不变。

---

## 1. 现状：三个层次叠在一起

| 层次 | 是什么 | 谁创建 | 在哪里管理 |
|---|---|---|---|
| **工作区** | 一个目录 + 一行 `workspaces`；临时（随对话删除）或永久（跨对话共享） | 用户（永久）/ 创建对话时自动（临时） | 工作区列表、工作区详情、对话里的工作区菜单 |
| **内置工坊**（两个） | 两个永久工作区，`kind` 分别 `roleplay` / `studio`；首页两行、新建表两项；工坊对话额外拿到 3 个或 6 个宿主工具 | app 懒创建（`ensureWorkshop` / `ensureStudio`） | 复用工作区详情页，没有任何工坊专属界面 |
| **场景** | 一个冻结了角色卡和助手提示词的对话；从助手卡建 → 临时工作区；从资料文件夹建 → 该永久工作区；从工坊提案建 → 工坊 | 用户（对话框）或用户在提案卡上点"创建并打开" | 和普通对话一样 |

关键事实：

- 工坊身份只存在于 `workspaces.kind` 一列（`Entities.kt:310-328`、`:344-369`），用户看不到、
  也改不了；它只影响 `ChatViewModel.buildTools` 追加哪些辅助工具（`ChatViewModel.kt:6464-6479`、
  `:6711-6765`）和 `WorkshopPrompt` 写哪段系统消息（`WorkshopPrompt.kt:47-79`）。
- 两个工坊在管理界面里和普通永久工作区**完全一样**：Workspaces 列表按 `isPermanent` 查询，
  行里没有 kind，没有徽标（`WorkspacesScreen.kt:403-449`）；详情页是通用页；两行都可以
  **改名**，空的时候都可以**删除**；批量删除工作区会**连带删除其中的对话、场景、回滚快照和
  文件**（`ConversationRepository.kt:783-820`）。
- 两个工坊出现在**每一个**永久工作区选择器里：新建对话→选择工作区、场景的"工作区资料"、
  角色卡"导出到工作区"，以及系统文件管理器（`WorkspaceDocumentsProvider`）。
- "工坊中心"就是普通工作区详情：标题是名字，正文是配额、绝对路径、Files、对话列表，
  **没有一句说明这里能干什么**（`.shots/workshop-hub-phone.png`、`studio-hub-phone.png`）。
- 首页两行常驻入口（`ConversationsScreen.kt:1442-1523`）和新建表两项（`:762-776`）是两套
  几乎一样的代码；`onOpenWorkshop` / `onOpenStudio` 也是两个近似方法
  （`ConversationsViewModel.kt:490-507`）。

---

## 2. 混乱是从哪来的（按严重程度）

### 2.1 把一个功能拆成了两个工作区
两个工坊的**机制完全相同**，区别只是追加哪组辅助工具。于是同一个东西有了两个入口、两个
kind、两套 `ensure`、两套文档，而用户要自己判断"这次该进哪个门"。这个判断本身并不稳定：
配置工坊的 `app_propose_assistant` 能起草带角色卡的助手，RP 工坊的 `rp_create_scene` 能
**按助手名字**开场景——"改一个角色"和"改一个助手"的边界天然模糊。合并后用户只有一个门：
**把想准备的东西告诉工坊，用哪个工具由工具描述和上下文决定。**

### 2.2 工坊在管理界面里"隐身"
- Workspaces 列表里两行和普通工作区没有区别，改过名之后尤其看不出是内置的；
- 空工坊可以从普通工作区详情页删除，下次点入口又会长出一个空的（`ensureWorkshop`）；
- **批量删除可以选中工坊并连带删掉其中所有场景和资料**——这是当前最危险的一条管理路径，
  因为它用"删除共享文件夹"的提示删掉的是产品功能的家。
- 除了首页两行，没有任何地方解释它是什么。

### 2.3 身份是隐藏数据
`kind` 既不可见也不可改：用户无法知道"这段对话为什么多了几个工具"，也无法把某段对话搬进
或搬出工坊（对话创建后 `workspaceId` 不再改变，仓库没有搬运 API）。这本身可以接受（模型
也不能创建/进入工坊），但前提是**入口足够清楚、工坊本身在界面上可见**。今天两条都不成立。

### 2.4 入口重复
首页两行 + 新建表两项 + 所有工作区选择器里两项 = 同一样东西最多四个入口，而且每处的名字
和说明还不一样（RP workshop / 配置工坊 / "Character cards, materials and scenes in one
permanent place" / "The assistant drafts; you save…"）。

### 2.5 工坊页就是普通工作区页
打开工坊看到的是配额、绝对路径、"Open in Files app"，以及一个通用的"New conversation"。
没有"这是工坊"的说明，没有"你可以让助手做什么"的提示，也没有到场景/助手/指令的入口。

### 2.6 术语漂移
- 同一对象：`workshop` / `studio` / `工坊` / `工作台`（workbench）/ `工作区`；
  代码里 `StudioEntryRow`、`ensureStudio`、`StudioTools`、`studio_*` 资源，用户界面从来看不到
  "studio" 这个词；
- "工作区"也被叫"文件夹 / shared folder"（英文 `home_create_new_workspace_detail: "Create a
  shared folder for files."`，中文却说"创建一个可以共享文件的工作区"）；
- 对话 / 会话 / session / chat / thread 混用（全局问题，本次不展开）；
- 场景 / 故事 / story folder、助手 / 角色混用。
- 模型侧又是第三套名字：`workspaceKindLabel` 返回 `"roleplay workshop"` /
  `"configuration workshop"`（`ConfigProposals.kt:507-512`），工具描述里写
  "configuration workshop"，而 UI 是 "Config workshop"。

### 2.7 场景的工作区生命周期按来源不同，但界面不说明
同一个"新场景"，从助手卡建是**临时工作区**（对话删除即消失），从资料文件夹建是**选择的
永久工作区**，从工坊提案建是**工坊**。场景设置里没有"这段场景的工作区"一行，只有工作台
菜单里的"工作区：永久/临时"。合并工坊不会改变这一点，但会在文档里写清楚（后续可单独做
"场景信息"入口）。

### 2.8 零碎但真实的毛病
- 工作区管理的几个错误提示是硬编码英文（`WorkspaceDetailViewModel.kt:173,184`、
  `WorkspacesViewModel.kt:75,112,122,166,176`），中文界面下直接露出英文；
- 工坊名在创建时按当时的界面语言落库，之后切语言不会变（`ConversationsViewModel.kt:483-486`
  记录过这个坑）；
- 首页工坊行用固定字符串，改名后首页仍显示旧名，和工坊页标题不一致
  （`ConversationsScreen.kt:1459-1464` vs `WorkspaceDetailScreen.kt:151-157`）；
- `chat_scene_menu` 字符串声明了但没被渲染。

---

## 3. 建议的新设计

### 3.1 概念模型

| 概念 | 是什么 | 谁创建 | 在哪里管理 |
|---|---|---|---|
| **工作区**（workspace） | 文件容器；临时（一个对话，随它删除）或永久（跨对话共享） | 用户 / 新建对话 | 工作区列表、详情、对话菜单 |
| **工坊**（workshop） | **唯一**一个内置永久工作区，`kind = "workshop"`；工坊对话拿宿主辅助，场景照旧被排除 | app 懒创建；用户不能创建/删除/搬入 | 首页一个入口；工作区列表里带"内置工坊"徽标；工坊页有说明 |
| **对话**（conversation） | 一段记录 + 它绑定的一个工作区 | 用户 | 首页列表 |
| **场景**（scene） | 冻结了 persona 的对话；工作区按来源决定 | 用户（对话框/提案卡） | 和普通对话一样 |

没有新的权限层级、没有新的模式开关、没有"把某个工作区提升为工坊"的动作。工坊仍然是
**数据属性**（`docs/07-gotchas.md` A31/A32），模型不能创建、进入或改写它。

### 3.2 工坊：一个，而不是两个

- `WorkspaceKinds`：`NONE = ""`、`WORKSHOP = "workshop"`；删除 `ROLEPLAY` / `STUDIO`。
- 仓库：`ensureWorkshop(name)` 一处懒创建（保留同一把 `builtInMutex`）；删除
  `ensureStudio` / `studio()`。
- 入口：首页一个常驻行 + 新建表一项；两者都走同一个 `openWorkshop`。
- 工坊页：仍是工作区详情页，但当 `kind = workshop` 时
  - 顶部加一块说明（这是什么、可以拿它做什么、草稿不是修改）；
  - 菜单里**去掉改名和删除**；
  - "New conversation" 保留（这是工坊对话的创建入口）。
- **完全 app-owned**：不能改名，不能单删，也不能被批量删除；Workspaces 列表的多选模式不勾选
  它，也不显示勾选框。原因见 §2.2：删除会把功能的家连同场景和资料一起删掉，改名会让首页
  入口、列表和工坊页三处名字对不上。想清理时删对话和文件即可；入口永远指向同一个工坊。
  首页行显示**工作区行里的名字**（没有行时用本地化默认名）；行存在后名字不再变化。
- 选择器：工坊仍出现在"永久工作区"选择器里（场景资料、角色卡导出、新建对话选工作区）——
  它是真实的永久工作区，资料和场景就在那里；不在 UI 层做特殊过滤，只在列表/详情里给徽标。

### 3.3 辅助工具：并集，场景照旧没有

非场景的工坊对话追加 9 个辅助（`rp_validate` 仍要求对话已有 `read_file`/`list_files`）：

| 组 | 工具 | 说明 |
|---|---|---|
| RP | `rp_format` | 常量格式文本，无条件有 |
| RP | `rp_validate` | 只在对话已有读取工具时出现 |
| RP | `rp_create_scene` | 只准备提案卡，点按才创建 |
| 配置 | `app_list_assistants` / `app_read_assistant` | 读助手内容字段 |
| 配置 | `app_propose_assistant` | 只准备草稿，打开助手编辑器 |
| 配置 | `app_list_instructions` / `app_read_instruction` | 列/读永久工作区根 `AGENTS.md` |
| 配置 | `app_propose_instruction` | 只准备草稿，打开 Files 编辑器 |

规则不变：不进 `ToolCatalog`、不进助手编辑器/会话工具表/审批规则/缺口报告；`mutates = false`；
不新增网络、执行或工作区外读取；模型不能改工具、审批、SSH、下载授权、服务商或模型，不能
删助手、不能读提示词库。

**成本（必须披露）**：并集工具 schema 约 9.8 KB（RP 组 ~4.0 KB + 配置组 ~5.8 KB，其中
`rp_create_scene.card` 和 `app_propose_assistant.card` 重复了同一份 11 字段角色卡 schema，
约 1.1 KB）。按 3.5–4.5 字符/token 估算约 **2.2–2.8k tokens/请求**，每回合都随请求发送；
工具定义稳定，缓存前缀不动。只发生在工坊对话里，普通对话与场景一分钱不多。合并后一个工坊
对话比今天的单工坊对话贵约一倍，这是"一个门"的代价；如果实测不可接受，再考虑把两组工具
做成"工坊里的两个工作台"，但那是后话（见 §5）。

### 3.4 一段 `Workshop context:`

`WorkshopPrompt.kt` 只保留一个函数、一个 kind、一段文本（草稿，实施时再定稿）：

```text
Workshop context: this conversation runs in the user's workshop, a built-in
permanent workspace where the host lets you prepare two kinds of work for the
user. Role-play: use rp_format to write a card, rp_validate to check a card or
scene file when that helper is attached, and rp_create_scene to prepare a scene.
This is the workshop, not a scene: no character is in play, and the scene is a
separate conversation you cannot enter. App configuration: use app_list_* and
app_read_* to read the user's assistants and each permanent workspace's root
AGENTS.md, and app_propose_* to prepare drafts of an assistant or an AGENTS.md.
Nothing you prepare takes effect by itself: a scene proposal creates nothing
until the user taps Create and open; a draft opens the user's existing editor
prefilled and is written only when the user saves there. Never announce a scene,
a character, a saved instruction or an applied setting as if it already existed.
You cannot change tools, approval rules, SSH or download grants, providers or
models, delete an assistant, or read the saved-prompt library.
```

长度约 1.0 KB，比今天两段拼接（1.4 KB）短；marker 不变，wire inspector 的 `splitSystem`
（`WireSheet.kt:144-184`）仍然只认第一个 marker，不需要新的分段类型。

### 3.5 场景、资料与 `AGENTS.md`

- 从工坊提案创建的场景仍绑定工坊这个永久工作区（`ChatViewModel.kt:1817`），资料和故事
  留在一起；场景里没有工坊辅助、也没有 `Workshop context:`。
- 工坊自己的根 `AGENTS.md` 会被它的新对话、也会被**从工坊创建的新场景** pin（工作区指令
  的现有语义）。因此配置辅助里 `app_propose_instruction` 若给工坊自己起草指令，保存后会
  进入新场景的系统消息——这是合理的（用户可以放"用中文写"这类场景规则），但提案卡和
  工具结果要把这层影响说清楚，不能只说"影响新对话"。
- 不改变 `AGENTS.md` 的 pin/Reload 语义，也不在场景里额外过滤。

### 3.6 术语

本次只统一**工坊/工作区**这一片：

| 概念 | 中文 | 英文 | 不再使用 |
|---|---|---|---|
| workshop | 工坊 | Workshop | studio、配置工坊、RP 工坊、工作台（指工坊时） |
| workspace | 工作区 | Workspace | 共享文件夹（指工作区时） |
| permanent / temporary workspace | 永久 / 临时工作区 | permanent / temporary workspace | folder（指工作区时） |
| scene | 场景 | scene | story（场景对象的意义上） |
| assistant | 助手 | assistant | agent（用户界面） |

模型侧：`workspaceKindLabel` 对 workshop 返回 `"workshop"`，普通工作区返回 `"ordinary"`；
六个配置工具的描述从 "configuration workshop" 改为 "workshop"。

全局的"对话/会话/线程"清理不在本次范围（见 §9）。

---

## 4. 迁移：schema v29

`workspaces.kind` 列已经在 v28；v29 只改写**值**，不动表结构。Room 迁移按顺序执行：

```sql
-- 1. 有 roleplay 行：它升级为 workshop（RP 场景绑着它，必须让它活下来）
UPDATE workspaces SET kind = 'workshop' WHERE kind = 'roleplay';

-- 2. 如果现在已有 workshop，studio 行降级为普通永久工作区
--    （对话、文件、场景绑定都不动；旧配置对话不再有辅助工具）
UPDATE workspaces SET kind = ''
 WHERE kind = 'studio'
   AND EXISTS (SELECT 1 FROM workspaces WHERE kind = 'workshop');

-- 3. 没有 roleplay 行时，studio 行升级为 workshop
UPDATE workspaces SET kind = 'workshop' WHERE kind = 'studio';
```

| 升级前 | 升级后 |
|---|---|
| 只有 roleplay | 它变成 workshop；场景照旧绑定它 |
| 只有 studio | 它变成 workshop；旧配置对话继续可用，且现在也能用 RP 辅助 |
| 两者都有 | roleplay 变成 workshop；studio 变成普通永久工作区（名字/文件/对话保留，旧对话失去辅助与上下文段） |
| 都没有 | 首次点入口时懒创建一个 |

边界与说明：

- **不搬文件、不搬对话、不删行**。跨目录迁移要处理配额、重名、失败回滚，是迁移期最容易
  丢数据的一步；降级是零拷贝、零丢失的。降级后可能留下一个空的"配置工坊"普通工作区，
  用户可以在 Workspaces 里按普通工作区规则删除。
- 迁移只改写已知的两个值；未知 kind 仍然按普通工作区处理（现有约定）。
- 备份恢复：备份里的数据库是旧版本号，打开时同样跑 v29 迁移，结果一致。
- 旧配置对话的 `workspaceInstructions` 是**已 pin 的文本**，不随迁移变化；它的系统消息不会
  突然多出或缺少工坊段（工坊段是每回合按 kind 现算的，所以升级后的新回合会按新身份出现）。
- 工坊行不可删除的规则在仓库层兜底：单删拒绝、批量删除跳过 `kind != NONE` 的行（UI 也不
  提供入口），并加单测。

---

## 5. 备选与为什么不做

| 备选 | 不做的理由 |
|---|---|
| **保留两个工坊，只把管理界面做清楚** | 用户仍要在两个门之间做选择；两套 kind/入口/文档/测试继续存在；边界模糊的问题（§2.1）没解决。它只缓解"隐身"，不解决"为什么要选"。 |
| **一个入口 + 两个工坊"标签页"/两种对话模式** | 还是要用户选"这次是 RP 还是配置"，只是把选择挪到创建对话时；多一列状态、多一处文案，没有真正减少概念。 |
| **迁移时把 studio 的文件/对话搬进 roleplay 行** | 配额、重名、失败回滚都要在迁移期处理，收益只是少一个可删除的空工作区；风险远大于收益。 |
| **按助手 opt-in 工具（v1 设计）** | 已经评估并拒绝过：配置分散到每个助手、工具目录/审批表/缺口报告增长、功能出现在任意工作区（`rp-agent-studio.md` 附录 A）。 |
| **允许把任意永久工作区"设为工坊"** | 多工坊的发现性和管理问题会回来，而且引入"哪一个是内置的"的新状态；P2 再谈收养。 |
| **彻底去掉 kind，工坊改成普通对话特性** | 会失去"一个永久资料家 + 场景绑定 + 模型不可进入"的结构；kind 是当前零能力辅助的正确挂载点。 |

---

## 6. 风险与代价

| 风险 | 说明 | 处理 |
|---|---|---|
| 请求变大 | 工坊对话每回合多约 9.8 KB 工具 schema（含 1.1 KB 重复角色卡 schema） | 只在工坊；文档写明；实测请求查看器确认；后续可评估精简 schema |
| 模型选错工具 | 两套辅助同时在场 | 上下文段明确按"RP / app 配置"分流；工具名和描述本身就是两组；demo 和真机各验一次 |
| 旧配置对话失去辅助 | 迁移把 studio 降级为普通工作区 | 发布说明写清楚；对话和文件不丢；用户可在新工坊里继续 |
| 工坊不可改名/删除 | 与今天"可改名、空时可删"的行为不同 | 有意的：删除会连带场景和资料，改名会让入口与列表对不上；说明块写明，清理靠删对话和文件 |
| 工坊 `AGENTS.md` 影响新场景 | 合并后配置辅助可以起草它 | 提案卡/工具结果披露；文档写明；pin/Reload 语义不变 |
| 空降级工作区留下 | 两者都存在的用户会多一个普通工作区 | 发布说明；用户按普通工作区删除 |
| 迁移边界 | 重复 kind 行理论上不存在（app 只在这两处写 kind） | 迁移语句对单行假设；有 v29 迁移测试 |

---

## 7. 改动面

| 区域 | 改动 |
|---|---|
| `core/db/.../db/Entities.kt` | `WorkspaceKinds` 改为 `NONE` / `WORKSHOP`；kind 字段文档改写 |
| `core/db/.../db/InhumanDatabase.kt` | schema v29 + `MIGRATION_28_29`（§4 SQL）；迁移列表 |
| `core/db/.../ConversationRepository.kt` | 删 `studio()`/`ensureStudio`；`ensureWorkshop` 只认 workshop；单删拒绝内置工坊；批量删除跳过内置工坊 |
| `app/.../ui/chat/RoleplayToolsPlan.kt`、`StudioToolsPlan.kt` | 合并为 `WorkshopToolsPlan.kt`（一个 data class + 一个纯函数），单测合并 |
| `app/.../ui/chat/ChatViewModel.kt` | 一个 `workshopHelpers` 计划；追加九个工具；`workspaceKind == WORKSHOP`；`buildWireHistory` 用新上下文；demo 分流顺序 |
| `app/.../ui/chat/WorkshopPrompt.kt` | 一个 kind、一段合并文本；测试更新 |
| `app/.../ui/chat/ConfigTools.kt`（原 `StudioTools.kt`）、`ConfigProposals.kt` | 文件/类名去掉 studio；描述去掉 "configuration workshop" 说法；`workspaceKindLabel` 改 `workshop`；指令提案披露场景影响 |
| `app/.../ui/chat/WireSheet.kt` | 分段逻辑不变，测试更新（一个工坊块） |
| `app/.../ui/ConversationsScreen.kt`、`ConversationsViewModel.kt` | 首页一行、新建表一项；删除 studio 行/入口/VM 方法 |
| `app/.../MainActivity.kt` | 一个 `openWorkshop` 导航；删除 studio 分支 |
| `app/.../ui/workspaces/WorkspacesScreen.kt`、`WorkspaceDetailScreen.kt` | 徽标、批量删除不勾选内置工坊、详情说明块、隐藏改名/删除 |
| 字符串（en + zh-rCN） | 一个工坊名称/说明/徽标/详情说明；删除 studio 的 4 组字符串 |
| 测试 | `WorkshopKindMigrationTest` 增加 v29 用例（4 种组合）；`WorkshopWorkspaceTest` 改单工坊并加改名/删除/批量删除守卫；`RoleplayToolsPlanTest`/`StudioToolsPlanTest` 合并为 `WorkshopToolsPlanTest`；`WorkshopPromptTest`/`WireSheetSystemSplitTest`/`ConfigToolsTest` 更新 |
| 文档 | 本提案；`docs/01`（词表 + 工坊）、`docs/02`（导航/入口）、`docs/03`（上下文段）、`docs/04`（v29）、`docs/06`（wire 分段/提案卡）、`docs/07`（A31/A32 合并改写）、`docs/11`（如需）、`docs/13`（产品说明）、`docs/README.md` 提案索引；`rp-agent-studio.md`/`config-workshop.md` 头部加"已合并"说明；`AGENTS.md` 两条工坊 bullet 合并成一条（同时给它腾出空间，它已经接近 64 KiB 上限） |

不需要改：`ToolCatalog`（工具总数仍是 44）、助手编辑器、会话工具表、审批规则、SSH/下载/
媒体配置、场景创建器、备份格式、能力边界。

---

## 8. 测试与验收

- 迁移（`:core:db` 纯 JVM 或现有迁移测试位置）：
  - v28 只有 roleplay → v29 为 workshop，`kind` 唯一；
  - 只有 studio → workshop；
  - 两者都有 → roleplay 行 workshop、studio 行 `""`，两行的对话数、场景绑定、`AGENTS.md`
    都不变；
  - 都没有 / 普通工作区 → 不受影响；
  - 迁移后 `ensureWorkshop` 幂等。
- 纯 JVM：`WorkshopToolsPlan` 真值表（工坊×场景×读工具）、`WorkshopPrompt`（普通/未知/
  场景返回 null；文本含两组关键词）、`WireSheetSystemSplitTest`（一个块仍能和摘要/AGENTS.md
  顺序共存）。
- 仓库：内置工坊单删拒绝；批量删除跳过内置行且不影响普通行；`ensureWorkshop` 仍是唯一写入者。
- demo：合并后的工坊对话里 `scene` 触发场景提案、`config`/`配置` 触发两份草稿；两者同时
  出现时按文档顺序只触发一种（测试钉住）。
- 设备：360×640 与 1280×800 各跑一遍——一个首页入口、一个新建项、工坊页说明、Workspaces
  徽标、批量删除勾选、提案卡、场景创建、wire inspector 的单个 Workshop context 段；
  `tools/ci.ps1` 无警告。
- 发布前确认请求查看器里工坊请求的工具 schema 增加量与 §3.3 估算一致。

---

## 9. 明确不做 / 后续

- 不改工作区的临时/永久模型，不改目录 id 与显示名的关系，不做对话跨工作区搬移。
- 不合并"场景"和"对话"两个概念；不统一场景的工作区生命周期（另案）。
- 不做全局的对话/会话/线程术语清理（另案，影响面大）。
- 不实现 RP 工坊 P0b/P1/P2（引用旧对话、社区卡文件来源、实时读取、收养/多工坊、助手创建/
  删除），它们的状态不变；合并后这些功能若做，只作用于唯一的工坊。
- 不改变任何能力：辅助工具仍是零能力宿主附加，能力仍只来自助手/对话的工具清单，审批/SSH/
  下载/媒体/提示词库全部不变。
- 不新增"工坊开关"或权限档。

---

## 10. 决定（2026-11-27）

1. **合并**：两个工坊合成一个 `kind = "workshop"`。已实施。
2. **工坊的寿命**：完全 app-owned —— **不可改名、不可单删、不可批量删除**。已实施。
3. **迁移**：旧的配置工坊在 v29 降级为普通永久工作区（对话和文件不动）。已实施。
4. **发布**：作为 **0.33.0**（schema v29）发布；0.32.0 之后的工坊上下文修复一并包含。
   发布仍等用户点名，本次实施不构建 release。

（§3.3 的工具 schema 成本、§6 的"工坊不可改名/删除"是这版设计里最值得记住的两点；其余
按推荐默认值执行。）
