# 提示词库可读与草稿 + 从工作区文件导入系统提示词 — 已实现记录

> **状态：已实现（2026-11-27，手机端）。** 两项相关改动：
>
> 1. 助手编辑器的“导入系统提示词”现在有两个来源：**本机文件**与**工作区文件**；
> 2. 内置工坊可以**读**用户的提示词库并**准备一条新/更新草稿**，写入只发生在用户点
>    提案卡上的**存入提示词库**之后。
>
> 本提案**修订** `config-workshop.md` §9 的产品决定「提示词库仍对模型不可见」：库仍然是
> **用户配置**，不是能力；变化只是工坊可以读它、为它起草，而写仍由用户的手势完成。
> 没有 schema 变化（仍 v30），没有 `ToolCatalog` 变化（仍 46 个可选工具），也没有新能力：
> 三个辅助工具都 `mutates = false`，写入发生在 app 里，不在工具里。
>
> 关联：`config-workshop.md`（§9 被本提案修订）、`workshop-merge.md`（单一工坊，schema v29）、
> `docs/07-gotchas.md` A31、`docs/03-ai-provider.md`、`docs/06-agent-loop-ui.md`、
> `docs/11-conversation-tools.md`、`docs/13-product.md`、`docs/README.md`、`AGENTS.md`。

---

## 0. 结论先行

- **工坊可读、可起草，不可写、不可选。** 工坊的宿主附加辅助里新增三个提示词工具：
  - `app_list_prompts`：标题、长度、预览（`ConfigTools.kt:554-597`）；
  - `app_read_prompt`：一条提示词的全文，超过 64,000 字符按 `MAX_PROPOSED_PROMPT_CHARS`
    截断并在结果里说明（`ConfigTools.kt:606-661`、`ConfigProposals.kt:56`）；
  - `app_propose_prompt`：准备一条 `PromptProposal`（title、text、existingChars、summary），
    不写任何东西（`ConfigTools.kt:671-729`、`ConfigProposals.kt:235-282`）。
  三者都不在 `ToolCatalog`，都 `mutates = false`，因此不出现在助手编辑器、对话工具清单、
  审批表或能力缺口报告里（`WorkshopToolsPlan.kt:100-104`）。
- **唯一的写入是提案卡上的“存入提示词库”。** 工具只返回数据；`ChatScreen` 把它渲染成
  一张卡，`ChatViewModel.saveProposedPrompt(messageId)` 在用户点按后调用
  `SavedPromptRepository.save`（`ChatViewModel.kt:2054-2097`、`ChatScreen.kt:5902-5988`）。
  没点就没有行；同标题按库的 title-unique 规则**更新**那一条，不新增副本
  （`SavedPromptRepository.kt:37-53`）。
- **从工作区文件导入系统提示词。** “导入”先问来源（本机 / 工作区文件），工作区分支是
  两步选择（选工作区 → 浏览目录 → 点 `.txt`/`.md`）。路径全部经该工作区自己的
  `SessionWorkspace`；1 MiB 读上限、严格 UTF-8、替换/追加询问与设备选择器完全一致
  （`PromptImportDialogs.kt:33-246`、`AgentsViewModel.kt:646-850`）。
  临时与永久工作区都会列出。
- **边界不变**：模型仍不能删除助手或提示词，不能改工具/审批/SSH/下载/服务商/模型，
  也不能把某条提示词“设为”助手的系统提示——选择仍由用户在助手编辑器里做
  （`WorkshopPrompt.kt:54-70`）。

---

## 1. 旧决定与这次为什么改

`config-workshop.md` §9 当时写死了一条产品决定：

> **提示词库仍对模型不可见**：不读、不写、不选；编辑器的“存入提示词库”仍由用户点。
> 这是一个产品决定（`docs/13-product.md`），要改需要单独修订。

当时的理由是：提示词库是**用户写给助手看的文本**，属于助手配置，不是助手的能力；模型
读不到它，也就不会把它当成可改的配置，更不会在请求里“顺手”改掉自己的系统提示。
这个理由今天仍然成立——所以这次不是把库变成能力，而是承认它和助手、`AGENTS.md` 一样是
**用户内容**，工坊本来就在替用户读、替用户起草：

- 用户已经在工坊里让 agent 读助手内容、读各永久工作区的 `AGENTS.md`，再起草助手或指令；
  提示词库是同一类对象，却被单独排除在外，结果是要改一条提示词，用户得先把它从库里
  复制到别处，或者凭记忆重写。
- 用户明确要求“工坊也能看提示词库、帮我存一条”，并同意写入仍由自己点按确认。
  因此这次修订的是**可读/可起草**，不是**可写**。

旧决定的撤销方式见 §9：`config-workshop.md` 保留原文，只在顶部和 §9 加修订说明，
由本提案记录新规则。

---

## 2. 从工作区文件导入系统提示词

### 2.1 形状

助手编辑器的提示词字段仍只有一个 **Import** 动作；点它先出现来源选择：

- **本机文件**（`From this device…`）——原有的系统文件选择器，行为不变；
- **工作区文件**（`From a workspace file…`）——新的两步选择器：
  1. 选一个工作区（永久在前，临时也列，因为提示词可能写在仍开着的对话里）；
  2. 浏览该工作区的目录，点一个文件。

选择器是 `PromptImportChoiceDialog` 与 `WorkspacePromptImportDialog`
（`PromptImportDialogs.kt:33-72`、`:83-246`）；`AgentsScreen` 在提示词字段的
`onImport` 处打开来源选择（`AgentsScreen.kt:691`），工作区分支把选中的文本一次性交回
屏幕，再由屏幕走与设备选择器相同的“替换/追加”分支（`AgentsScreen.kt:577-599`）。

### 2.2 围栏、上限与文本规则

工作区来源不是第二条读路径，它复用原有的读文件规则：

- 每个路径都经**该工作区自己的** `SessionWorkspace.resolve`，被拒绝的路径原样报错，
  不会退化成裸 `File`（`AgentsViewModel.kt:812-830`、`:832-850`）；
- 列表过滤符号链接，目录在前、名称不区分大小写排序，最多 500 行
  （`AgentsViewModel.kt:822-829`）；
- 只接受 `.txt` / `.md` / `.markdown`，或 provider 报告 `text/*` MIME——和设备选择器
  同一条 `isPromptText` 规则（`PromptImport.kt:20-24`）；
- 读取上限 **1 MiB**（`MAX_PROMPT_IMPORT_BYTES`，`PromptImport.kt:10`、`:33-47`）。
  这是防止误点视频/数据库进内存的**读保护**，不是提示词长度上限；
- 严格 UTF-8，坏字节直接报错而不是塞满 U+FFFD；BOM 去掉，空白文件拒绝
  （`PromptImport.kt:57-71`）；
- 若提示词框已有文本，仍然问 **替换 / 追加 / 取消**，与设备来源一致
  （`AgentsScreen.kt:580-594`）。

### 2.3 为什么不做成第二个按钮或第二条导入路径

提示词字段所在的是一个已经很密的编辑器；再放一个“从工作区导入”按钮会把两个来源
并排展示，用户每次都要先读两个按钮。来源选择放在一次点击之后，Import 仍然只有一个。
工作区选择器也不是“文件管理器”：它只列一个工作区内的条目、只读文本，不写、不删、
不跨工作区。

---

## 3. 工坊读提示词库并准备草稿

### 3.1 三个辅助工具

| 工具 | 参数 | 返回 | 写入 |
|---|---|---|---|
| `app_list_prompts` | 无 | 每条 `{title, chars, preview}`，按标题排序 | 无 |
| `app_read_prompt` | `title` | `{title, text, truncated, chars}`；超过 64,000 字符截断 | 无 |
| `app_propose_prompt` | `title`, `text` | `PromptProposal` JSON（`kind = "prompt"`） | 无 |

- 三者都是 `ConfigTools.kt` 里的 `Tool`，追加在 allow-list 之后，只在工坊的非场景对话里
  出现（`ChatViewModel.kt:6989-7000`）；`planWorkshopHelpers` 在工坊且非场景时把
  `listPrompts` / `readPrompt` / `proposePrompt` 置为 true（`WorkshopToolsPlan.kt:74-105`）。
- 它们和已有的六个配置辅助一样**不需要任何文件工具**：读的是 app 自己的库，不是工作区
  文件，所以 `rp_validate` 的“需要已有读工具”规则不适用于它们。
- `app_propose_prompt` 的校验很小：标题非空且不超过标题上限、正文非空、不含 NUL、
  不超过 `MAX_PROPOSED_PROMPT_CHARS`（64,000）；如果同标题的现有正文与新正文完全相同，
  直接拒绝，避免让用户保存一个无操作（`ConfigProposals.kt:292-336`）。
- 三者都 `mutates = false`：重放带工具的回合不会因为它们产生副作用，这也是
  `docs/07-gotchas.md` A31 要求的不变量。

### 3.2 提案卡与唯一的写

`ChatViewModel` 解析 `app_propose_prompt` 的工具结果，把 `PromptProposal` 挂到消息上
（`ChatViewModel.kt:5582-5587`、`ChatUiState.kt:603-616`）；`ChatScreen` 渲染成
`PromptProposalCard`：

- 标题（“新提示词 / 更新提示词”）与字符数：新建显示 `text.length`，更新显示
  `existingChars → text.length`（`ChatScreen.kt:5910-5923`）；
- 摘要预览最多三行（`ChatScreen.kt:5946-5954`）；
- **存入提示词库** 按钮，保存中/已保存/失败三种状态；**暂不** 丢弃提案
  （`ChatScreen.kt:5955-5986`）；
- 保存成功后卡片改为“已存入提示词库”，按钮禁用，不再提供第二次写。

写路径是 app action，不是工具：`saveProposedPrompt` 先检查不在流式/压缩中、消息上有
提案且未保存过，然后 `repository.savedPrompts().save(title, text)`
（`ChatViewModel.kt:2054-2097`）。`SavedPromptRepository.save` 用标题查现有行：有就更新
正文、保留 `createdAt`，没有就新建——这正是助手编辑器“存入提示词库”的同一规则
（`SavedPromptRepository.kt:37-53`），所以提案保存不会产生同名副本。

保存失败（标题或正文为空、数据库错误）显示在卡片上，可重试；`dismissPromptProposal`
只是把提案从消息上拿掉，不写任何东西（`ChatViewModel.kt:2099-2104`）。

### 3.3 工坊上下文文本

宿主写的那段 `Workshop context:` 也一起更新了：它现在明确说工坊可以读助手、各永久工作区
根 `AGENTS.md` 与**保存的提示词库**，并用 `app_propose_*` 起草；草稿只有用户在编辑器里
保存、或点提案卡上的 **Save to prompt library** 才写；模型不能改工具/审批/SSH/下载/
服务商/模型，也不能删助手或删提示词（`WorkshopPrompt.kt:54-70`）。
这段文本仍由 `workspaces.kind` 推导，每回合相同，不移动缓存前缀
（`docs/03-ai-provider.md`）。

---

## 4. 为什么写入仍然只能由用户点击

这不是“加一个审批”，而是把写放在工具之外：

- **工具没有写 API。** `app_propose_prompt` 只返回 `PromptProposal`；它拿到的
  `loadPrompts` 是只读的 `suspend () -> List<SavedPromptEntity>`，没有保存闭包。
  即使模型反复调用、编造参数，也只能得到一张卡或一个错误。
- **写发生在用户手势之后。** `saveProposedPrompt` 是 `ChatViewModel` 的普通方法，由
  `PromptProposalCard` 的按钮调用；它不经过引擎，不是工具调用，也不进入下一轮请求。
  重放带工具的回合不会重放这次写。
- **同标题更新，不新增副本。** 库的 title-unique 规则由 `SavedPromptRepository` 统一
  执行，提案保存和编辑器保存走同一条路径，不存在“提案专用”的写规则。
- **没有删除。** 三个工具都没有删除；删除仍在助手编辑器的提示词库面板里，由用户点。
- **没有选择。** 保存不会把这条提示词设成任何助手的系统提示；选择仍由用户在助手编辑器
  里点，选中只填进提示词框，仍可编辑。

这样做的理由和 `config-workshop.md` 一致：**写入权没有转移**。工坊把用户配置当内容
交给 agent 起草，但“保存”始终是用户已有的那一个手势。

---

## 5. 模型仍然不能做什么

- 不能删除助手，也不能删除提示词；
- 不能修改工具选择、审批规则、SSH/下载授权、服务商、模型、推理强度或建议回复设置；
- 不能把某条提示词设为助手的系统提示，也不能替用户选它；
- 不能直接写助手、`AGENTS.md` 或提示词库：这三处都只能由用户在编辑器里保存，或点
  提案卡上的 **存入提示词库**；`app_list_prompts` / `app_read_prompt` /
  `app_propose_prompt` 不碰文件、不联网、不执行；
- 不能进入、创建或改写工作区，不能改变工坊本身。

这些都写进了 `WorkshopPrompt.kt:54-70` 的边界句，并由 `WorkshopPromptTest` 钉住。

---

## 6. 代码落点

| 文件 / 函数 | 作用 |
|---|---|
| `app/.../ui/agents/PromptImportDialogs.kt` | 来源选择（`:33`）与工作区两步选择器（`:83`） |
| `app/.../ui/agents/PromptImport.kt` | 1 MiB 读保护、文本类型、严格 UTF-8（`:10`、`:20`、`:33`、`:57`） |
| `app/.../ui/agents/AgentsViewModel.kt` | `openPromptImport`（`:646`）、`choosePromptImportWorkspace`（`:679`）、`enterPromptImportDirectory`（`:685`）、`promptImportUp`（`:692`）、`promptImportChangeWorkspace`（`:703`）、`pickPromptImportFile`（`:728`）、`closePromptImport`（`:719`）、`consumePromptImportPicked`（`:724`）；读取经 `SessionWorkspace`（`:832-850`） |
| `app/.../ui/agents/AgentsScreen.kt` | 提示词字段 `onImport`（`:691`）、来源选择与一次性回填、替换/追加（`:577-614`） |
| `app/.../ui/chat/ConfigTools.kt` | 三个提示词工具（`:49-51`、`:554-729`）；全部 `mutates = false` |
| `app/.../ui/chat/ConfigProposals.kt` | `PromptProposal`（`:235`）与 `PromptProposals.prepare`（`:292`）；64,000 字符上限（`:56`） |
| `app/.../ui/chat/WorkshopToolsPlan.kt` | 计划里的 `listPrompts` / `readPrompt` / `proposePrompt`（`:42-44`、`:100-104`） |
| `app/.../ui/chat/WorkshopPrompt.kt` | 工坊上下文文本（`:54-70`） |
| `app/.../ui/chat/ChatViewModel.kt` | 工具注册（`:6989-7000`）、提案解析（`:5582-5587`）、`saveProposedPrompt`（`:2054`）、`dismissPromptProposal`（`:2100`） |
| `app/.../ui/chat/ChatScreen.kt` | `PromptProposalCard`（`:5902-5988`）与卡片接线（`:1932`、`:5112-5118`） |
| `app/.../ui/chat/ChatUiState.kt` | 消息上的提案与 busy/saved/error 字段（`:603-616`） |
| `core/db/.../SavedPromptRepository.kt` | 按标题 upsert 的唯一写入口（`:37-53`） |
| `core/ai/.../provider/demo/DemoProvider.kt` | demo 的 `config` 分支现在返回三份草稿（`:320-344`、`:538-558`） |

---

## 7. 没有 schema 变化，也没有能力变化

- **schema 仍 v30。** `saved_prompts` 表自 v16 就在（`docs/04-data-persistence.md`），
  本次只加读取与提案卡，没有列、索引或迁移变化。
- **可选工具仍 46 个。** 三个提示词辅助和已有的 RP/配置辅助一样不进 `ToolCatalog`，
  所以不影响 46 这个计数，也不出现在任何选择器、审批表或缺口报告里。
- **工坊辅助从九个变成十二个**（3 个 RP + 6 个配置 + 3 个提示词），仍然只在工坊的非场景
  对话里出现，仍全部 `mutates = false`。
- **没有新例外。** 工具只读写 app 自己的用户内容（助手行、`AGENTS.md`、提示词库），
  没有网络、执行或任意文件路径；提示词库的写发生在 app 内、用户点按之后。

---

## 8. 测试与验收

JVM 测试：

- `PromptImportTest`：文本类型判定（`.md` 的 MIME 分歧）、1 MiB 读保护、严格 UTF-8、
  BOM、空白文件（`app/src/test/.../ui/agents/PromptImportTest.kt:17-80`）。
- `WorkshopToolsPlanTest`：工坊且非场景时十二个辅助都为 true，普通工作区与场景为
  `NONE`，校验器仍需已有读工具，配置/提示词辅助不需要文件能力
  （`app/src/test/.../ui/chat/WorkshopToolsPlanTest.kt:45-104`）。
- `WorkshopPromptTest`：上下文文本覆盖 `Save to prompt library`、`saved-prompt library`
  与能力边界（`app/src/test/.../ui/chat/WorkshopPromptTest.kt:40-68`）。
- `ConfigToolsTest`：三个提示词工具的名称与非变异、列表排序与预览、读取截断与未找到、
  提案新建/更新/同文拒绝（`app/src/test/.../ui/chat/ConfigToolsTest.kt:451-547`）。
- `ConfigProposalsTest`：`PromptProposals.prepare` 的标题/正文/上限/同文校验与 JSON
  往返（`app/src/test/.../ui/chat/ConfigProposalsTest.kt:452-515`）。
- `DemoProviderTest`：`config` 消息在工坊里产生三份草稿（助手、`AGENTS.md`、提示词），
  且结果回来之后不再重复提案（`core/ai/src/test/.../DemoProviderTest.kt:281-309`）。

手工验收（按 `AGENTS.md` 的窗口比例清单，在 360×640 与 1280×800 两种窗口上各跑一遍）：

- 助手编辑器：来源选择出现两项；工作区选择器列出永久与临时工作区；文件夹可进可退；
  非文本文件被拒；1 MiB 以上文件报错；已有提示词时问替换/追加；空提示词直接填入。
- 工坊：含 `config`（或“配置”）的消息产生三张卡；提示词卡显示新建/更新与字符数；
  点“存入提示词库”后卡片显示已存入，助手编辑器的提示词库列表出现该条；再发一次同标题
  提案并保存会更新而不是新增；不点则库里没有变化。

验证命令仍是 `tools/ci.ps1`（它会跑 `:app:assembleDebug` 与全部 JVM 测试并拒绝编译警告）；
本次只改文档，没有重跑 gradle。

---

## 9. 对 `config-workshop.md` §9 的修订

`config-workshop.md` 保留为历史设计与决定记录，正文不改。它在顶部与 §9 加了指向本提案的
修订说明；具体变化是：

| 旧规则（`config-workshop.md` §9） | 新规则（本提案） |
|---|---|
| 提示词库仍对模型不可见：不读、不写、不选 | 工坊**可以读**列表与单条全文，并**准备**新/更新草稿；仍不能写、不能删、不能选 |
| 编辑器的“存入提示词库”仍由用户点 | 仍由用户点；提案卡的 **Save to prompt library** 是另一条同样的用户手势，也是唯一的写入 |
| 要改需要单独修订 | 本次即单独修订，记录在 `docs/proposals/prompt-library.md` |

`docs/07-gotchas.md` A31 的对应不变量同步改写为“tap-gated read/draft”，并新增一条：
提示词提案只有在卡片 Save 点按后才写。

---

## 10. 明确不做 / 已知限制

- **不删除、不重命名、不选择提示词。** 删除和选择仍在助手编辑器；工具没有这些动作。
- **不持久化提案卡。** 重开对话只剩持久化的摘要行，与助手/指令提案一致的已知限制。
- **不做批量读取或批量起草。** `app_read_prompt` 一次一条；`app_propose_prompt` 一次一条。
- **不把提示词库做成工具输出里的全文列表。** `app_list_prompts` 只给标题、长度和预览，
  全文必须显式 `app_read_prompt`，避免每回合把整个库塞进请求。
- **工作区导入不跨工作区。** 一次只浏览一个工作区；从别的工作区取文件仍走 Files 的复制。
- **不扩展成通用文件导入。** 只接受 `.txt`/`.md`/`text/*`，只读 1 MiB，不写回工作区。
