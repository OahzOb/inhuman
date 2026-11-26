# RP 创作助手 — 设计提案（工作区资料 + 写作工具）

> **状态：Phase 0 + Phase 1 已实现（2026-11-26）。**
> 已落地：场景创建时冻结助手提示词/名字/角色卡（`conversations.rpSnapshotJson`，schema v27）、
> `rp_format` / `rp_validate` 两个写作辅助工具、格式说明的单源生成与测试；
> **从永久工作区的资料文件夹创建场景**（浏览 `rp/<story>/card.json`、可选 `scene.json`，
> 同一套 `RoleplayValidator` 校验，快照与开场白、永久工作区绑定），
> 以及旧数据库角色卡的**导出到工作区**（`rp/<folder>/card.json`，不覆盖已有文件）。
> 未落地：Phase 2 的世界书/场景记忆、多角色，以及卡编辑器的最终去留（当前保留）。
> 本文保留为设计记录；当前行为以 `04` / `06` / `07` / `13` 为准。

---

## 0. 结论先行

- **工作区 = 写作资料区**：角色卡、场景、剧情大纲都是工作区文件；任何 agent 用现有文件工具
  读写，不需要导入步骤，也不需要专用助手。
- **app 只提供两件基础设施**：`rp_format`（查询格式、字段、上限、目录约定、示例）和
  `rp_validate`（检查文件/故事目录，只报结构性问题）。它们不是可选能力，只附着在已有的
  工作区文件能力上。
- **场景创建时冻结**：选资料区文件夹 → 用同一套 `RoleplayCodec` 校验 → 把助手提示词、
  名字和卡**快照**进会话。之后文件怎么改，已经开始的场景不变；显式"重新读取"才更新。
- **格式与校验同源**：`RoleplaySpec` 从 `RoleplayCard`/`RoleplayScene` 的序列化描述和
  `RoleplayLimits` 生成，`RoleplaySpecTest` 钉住字段与上限；`rp_validate`、场景创建、卡编辑器
  共用 `RoleplayCodec`，不存在第二份 schema。

---

## 1. 为什么不是"内置模板助手 + JSON 导入"

旧提案的流程是：从模板新建一个普通助手 → 访谈 → 写 JSON 到工作区 → 用户通过导入对话框
把 JSON 拷进 `agents.rpJson` → 再建场景。它正确但多两步，并把"卡"继续绑在助手上：

- 导入是纯手续：文件已经在工作区里，app 完全可以在建场景时直接读它。
- 卡属于助手导致同一张卡要给多个模型/助手用时必须复制；卡库的可发现性也只在助手的列表里。
- "任意 agent 都能创作"被模板入口限制住了；而格式说明本质上是**数据**，不该是一段
  每个对话都要背的提示词。

工作区文件方案把这些合并成一个动作：agent 写文件，用户建场景时选那个文件夹。

---

## 2. Phase 0：已实现

1. **schema v27 `conversations.rpSnapshotJson`**：场景创建时写入
   `RoleplaySnapshot(systemPrompt, characterName, card)`；空的含义是"尚未冻结"，升级前创建的
   场景在第一次回合时冻结当时的值；格式损坏按未冻结处理并修复。投影优先读快照，**冻结后
   不回退到实时助手**；场景设定的"重新读取角色卡与助手提示词"是唯一刷新入口（一次冷请求）。
2. **`rp_format`**：返回 `roleplay-files/1` 的卡/场景字段、逐字段上限、总上限、采样范围、
   `rp/<story>/card.json` + `scene.json` 的目录约定和一个合法示例。文本由
   `core/engine/.../roleplay/RoleplaySpec.kt` 生成。
3. **`rp_validate`**：`path` + `kind=card|scene|story`。路径经 `SessionWorkspace.resolve`；
   用 `RoleplayCodec` 解析、`RoleplayLimits` 校验；报告字段名、长度、JSON 类型、未知字段警告，
   **不回显文件正文**；`story` 检查 `card.json`（必需）与 `scene.json`（可选）。
4. **可用性**：两个工具在 allow-list 之后追加（和 `recall` 同类），只在对话已有工作区文件工具
   时出现；`rp_validate` 还要求 `read_file`/`list_files`。纯场景（`tools = []`）保持空工具集，
   验证器永远不会成为用户没授予的读路径。规则抽成 `RoleplayToolsPlan` 并单测。
5. **测试**：`RoleplaySpecTest`（字段/上限/示例）、`RoleplayValidatorTest`（字段级错误、
   不回显正文、采样范围）、`RoleplayToolsTest`（有效/无效/缺文件/越界/故事目录）、
   `RoleplaySnapshotTest`、迁移测试、仓库快照测试。

---

## 3. Phase 1：从工作区文件夹创建场景（已实现）

最终落点（2026-11-26）：

1. **选择器**：`NewSceneDialog` 顶部有"助手角色卡 / 工作区资料"两个来源。工作区来源先选
   **永久工作区**（只列永久的：临时工作区会随对话删除，选它等于把资料一起删掉），再逐级
   浏览文件夹；进入文件夹用 `RoleplayStoryLoader.list`，选中当前文件夹用 `RoleplayStoryLoader.load`。
   同一个 `RoleplayValidator`/`RoleplayCodec` 做校验，错误逐字段显示（字段名走本地化文案）。
2. **创建**：读 `card.json`（必需）、可选 `scene.json`；卡文件的 JSON **原样**写入
   `rpSnapshotJson`，`scene.json` 归一化后写 `sceneJson`（无场景写 `"{}"`）；开场白仍写成
   根 assistant 消息；工具仍为显式空列表。对话绑定所选**永久工作区**，以后给文件工具时
   还能改同一批资料。
3. **名字**：卡新增可选的 `name` 字段（`RoleplayLimits.NAME_MAX`，`rp_format` 已发布）。
   场景标题、`{{char}}` 和快照 `characterName` 依次取：卡里的 `name` → 所选助手名 → 文件夹名。
   卡不带名字也能用，只是会提示用了哪个兜底。
4. **助手仍要选**：创建流程是"选资料文件夹 + 选助手（模型/工具上限）"；卡不再来自助手，
   但 provider/model/tools 仍从助手复制，助手名也是名字兜底。
5. **旧卡**：`agents.rpJson` 保留 legacy 读取；`RoleplayCardEditor` 增加"导出到工作区…"，
   把当前卡（含未保存编辑）写成 `rp/<folder>/card.json`。导出走 `WorkspaceTransfer`，
   **不覆盖**已有 `card.json`，是副本而不是引用。编辑器暂时保留（是否下线见 §6）。
6. **场景文件语义**：`scene.json` 只是创建模板；建完后场景归会话，`SceneSettingsDialog`
   继续编辑会话副本，不回写文件，避免文件与记录两份真相。

---

## 4. 不变量

- RP 是内容配置，不是能力：卡/场景只进提示词，不改变工具、审批、SSH/下载授权、工作区边界。
- 快照一旦存在，**不回退实时助手**；旧场景的 prompt 不被外部编辑（包括资料文件）改写。
- `rp_validate` 只能报结构（字段/长度/类型），不引用正文；它只在已有文件读取能力时出现。
- 纯场景工具集为空；辅助工具不入目录、不出现在选择器里，因为它们没有可授予的能力。
- 格式说明、校验、场景创建三方必须共用同一个 codec；`RoleplaySpecTest` 是防漂移的闸。
- 新场景的模型/工具仍来自助手；卡文件不能携带 provider、密钥或工具选择。
- 文件夹来源只接受永久工作区；读文件、导出文件都经 `SessionWorkspace.resolve` 与
  `WorkspaceTransfer`，与用户自己的文件操作遵守同一套配额和路径围栏。

---

## 5. 已否决 / 不做

- **给所有 agent 内置 RP 提示词段落**：每个对话都付 token，且格式说明会与 codec 漂移；
  工具按需返回格式更好。
- **工作区 `AGENTS.md` 作为格式说明**：依赖创建顺序与 pin 时机，且换工作区就要重来。
- **专用 RP 创作助手**：格式是数据，不需要一个被特化的助手；任何有文件工具的 agent 都可以。
- **让模型直接写 `agents.rpJson` / 数据库**：agent 没有数据库句柄，"模型不能改自己的配置"
  仍是硬边界；文件是资料，导入/快照是 app 侧动作。
- **卡库只存在于工作区且没有发现/校验/冻结**：旧提案拒绝的形态，仍不采用。
- **让场景编辑回写 `scene.json`**：会立刻出现两个可写真相；场景文件固定为创建模板。

---

## 6. 待定

1. `RoleplayCardEditor` 的去留：当前保留为 app 内编辑 + 导出；以后是否只留"查看/导出"。
2. 升级时是否自动把旧 `agents.rpJson` 导出到某个永久工作区；目前是手动导出。
3. 多角色、世界书/场景记忆等 Phase 2/3 内容，见 `docs/proposals/roleplay.md`。
