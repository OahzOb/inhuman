# RP 文本的呈现：调研与方案

> **状态：调研完成（2026-11-26）；B 阶段 MVP 已实现（同日）。** 决定记录：用户明确
> 「不要依靠模型自觉遵守格式」，所以**显示层优先、A 的格式契约不作为前提**。已实现
> `RoleplayText` + `RoleplayTypography`（识别引号/括号/星号/分隔线，display-only，
> 不要求模型做任何事）。A（提示词契约/场景预设）、OOC 视觉、状态栏仍为可选后续。
>
> 回答两个问题：
>
> 1. 成熟的角色扮演（RP）产品把「内容有渲染」这件事，到底是靠**要求模型输出可渲染格式**，
>    还是靠**一种机制**？
> 2. inhuman 现在为什么读起来是「一大段没有重点」，下一步该做什么？
>
> 本文只讨论**显示/排版**：不新增能力、不新增模式、默认不改请求；唯一的请求变化是
> §4-A 的静态格式说明（**已降级为可选**）。证据分级：**确证**（官方文档/源码可直接核对）、
> **社区**（第三方文档/教程/测评）、**推断**（多条证据归纳，未直接核对）。

---

## 0. 结论先行

1. **你的判断基本正确。** 主流消费级 RP 产品里，模型输出的仍然是**普通文本**，带一些
   人人都懂的文内约定——引号是对白、`*…*` 是动作、括号是旁白/神态。前端把这段文本按
   Markdown（少数允许受控 HTML）渲染。**剧情本身不走 JSON / 结构化输出。**
2. **但「只是提示词」并不完整。** 成熟产品的成熟之处在于**显示层是独立的一层**：
   SillyTavern 的 `messageFormatting()` 有 11 步（正则钩子 → 自定义正则 → Markdown 修复 →
   Showdown → DOMPurify，且有「只改显示、不改聊天文件」的选项）；Character.AI 只支持固定
   Markdown 子集；JanitorAI 是 Markdown；RisuAI 是显示正则 + markdown-it + DOMPurify + 主题。
   也就是说：**排版约定来自内容层（角色卡/预设/提示词），渲染来自前端，两者是分开的。**
3. **更「机制化」的只有旁路数据。** 状态栏/好感度在 SillyTavern 生态里是「模型输出占位符
   或变量更新指令 → 前端模板画 UI、数据从前端变量取」（MVU）；唯一的结构化剧情实现
   Covel 用 `submit-output` 提交 `segments`/`cues` JSON，代价是**没有草稿级流式**、要校验
   修复循环、且要求模型支持工具调用。两者都不是主流叙事通道。
4. **对 inhuman：** Markdown 渲染器已经存在且是流式增量的（`MarkdownText`），缺的是三件
   事——**(a) RP 的排版契约**（默认提示词没有要求任何格式，`scene.format` 默认空，角色卡
   没有格式字段）；**(b) 把契约变成视觉的显示层**（现在对白/旁白/动作/场景切换/OOC 全是
   同一种 `bodyLarge` 段落）；**(c) 中文语境适配**（中文没有斜体字形，`*…*` 的视觉区分很弱；
   中文 RP 的通行约定是括号动作 + 引号对白）。
5. 不建议为「一大段」上 JSON/工具调用或 HTML/WebView：前者伤文风、失去 token 级流式、
   要求工具调用；后者与本项目已经否决 WebView 的方向冲突（`docs/06` 的 Mermaid 一节）。
   用户已拍板：**不依赖模型遵守格式**，所以先做 B（显示层），A 仅作可选增强；
   C（状态栏侧信道）暂不做。B 的 MVP 已实现，见 §0.1。

---

## 0.1 已做出的决定与实现（2026-11-26）

**决定：不依赖模型自觉遵守格式。** 功能必须由 app 侧的识别与呈现保证，提示词契约
只能是可选增强，不能是前提。

**已实现（B 的 MVP）：**

- `app/.../ui/chat/RoleplayText.kt`：纯函数 `RoleplayTypography` 把一条回复切成
  旁白/对白/动作/强调/场景分隔，`RoleplayText` 用 Compose 分级呈现（旁白=次要色、
  引号对白=正文色+中等字重、括号或 `*…*` 动作=次要色斜体、`**…**`=粗体、`---`=细线）。
- 识别**模型本来就会写**的约定，不需要任何提示词改动：`"…"`、`“…”`、`「…」`、`『…』`、
  `（…）`/`(…)`、`*…*`、`**…**`、整段的 `---`/`***`/`___`。
- **display-only**：原文、请求、导出、搜索、复制和 Source 都不变；只隐藏 `*`/`**`
  这两个标记符（Markdown 渲染器同样隐藏），括号和引号是标点，保留。
- 流式：每条快照重解析（线性扫描、无 AST，代价远低于 Markdown 渲染），未闭合引号/括号
  在流式中按对白/动作显示、结束后按字面显示；未闭合 `*` 始终字面。
- Markdown 块结构（代码块/表格/标题/引用/短横线列表）回退到 `MarkdownText`。
- 验证：`RoleplayTypographyTest` 19 个测试（约定、未闭合、流式前缀、raw round-trip、
  fallback）；CI 全绿；1280×800、360×640、360×640@1.5× 模拟器截图确认。

**仍未做：** A 的固定格式契约与场景预设（可选）、OOC 整条旁注化、状态/好感度侧信道、
多角色说话人样式。当前阶段只保证「模型写什么文风都能被分级显示」，不要求它改变写法。

---

## 1. inhuman 现状：为什么是一大段

**渲染路径本身是通的。**

- assistant 的每条文本都走 `MarkdownText`（`app/.../ui/chat/ChatScreen.kt:5104`），用户自己
  的文本按字面显示（`:5094-5102`）——不解释用户打的星号。
- Markdown 支持斜体/粗体/标题/列表/引用/代码/表格/LaTeX，流式增量（`MarkdownText.kt:126-279`），
  且对不闭合代码块有 display-only 修复（`ChatScreen.kt:5082-5093`，`docs/06` 的
  「Malformed Markdown stays readable」）。
- demo 的 RP 回答已经示范了这种写法（`DemoProvider.kt:372-380`）：`*动作*` + 引号对白，
  说明「动作/对白」的渲染路径不需要新造。

**缺的是契约与视觉。**

- RP 的 system 规则没有一句排版要求：`renderRules` 只说「保持角色、别替用户写、`/ooc`
  是场外话」（`RoleplayPrompt.kt:184-211`）。
- `RoleplayScene.format` 是 1 KiB 的自由文本（`Roleplay.kt:82-95`、`FORMAT_MAX`
  `:268`），编辑器提示只有「例如：第二人称，两段短段落。」（`values-zh-rCN/strings.xml:836`）
  ——它描述的是人称和长度，不是**视觉结构**。
- 角色卡没有格式字段（`Roleplay.kt:30-71`）；`rp_format` 对 `format` 的描述也只是
  「narration person, length and style」（`RoleplaySpec.kt:104-108`）。
- 于是默认场景里，模型拿到的唯一排版线索是空字符串，它自然写成一整段；即使写了
  `*动作*`，Markdown 的斜体在中文里也只是合成倾斜，和正文区分不强。
- OOC 视觉在设计里是 Phase 4 的打磨项（`docs/proposals/roleplay.md:343`），尚未实现：
  `/ooc` 的用户消息和普通消息、及其回复，现在外观完全一样。

**一句话：** 不是「没有渲染机制」，而是「渲染器在等一个约定，而约定没人写、写了也没
针对 RP 的视觉语言做过设计」。

---

## 2. 外部调研：成熟产品到底怎么做

### 2.1 各家的渲染管线

| 产品 | 模型输出被当成什么 | 用户/卡作者能改渲染吗 | display-only 机制 | 证据 |
| --- | --- | --- | --- | --- |
| SillyTavern | 原始文本 → 正则 → Showdown Markdown → DOMPurify → `innerHTML` | 能：Regex 扩展可选作用对象（用户输入/AI 回复/世界书/推理）、深度、顺序，可注入 HTML/CSS | **能**：两个复选框分别只改显示 / 只改提示词；都不勾则不可逆改写 chat JSONL | `script.js` 的 `messageFormatting()` 注释（11 步）、[Regex 文档](https://docs.sillytavern.app/extensions/regex/)、[#3996](https://github.com/SillyTavern/SillyTavern/issues/3996) |
| Character.AI | 固定 Markdown 子集：粗体/斜体/反引号高亮/删除线/1–5 级标题/代码块 | 不能改渲染规则；动作/旁白/对白靠文本约定 | —（官方不承诺 display-only；约定写在卡里） | [Markdown Styling](https://book.character.ai/character-guide/advanced-creation/beyond-plain-text/markdown-styling)、[Parenthetical Comments](https://book.character.ai/character-guide/advanced-creation/beyond-plain-text/parenthetical-comments) |
| JanitorAI | 基础 Markdown（斜体/粗体/列表/分隔线/引用/行内代码/代码框）；官方指南面向角色定义，聊天端渲染 Markdown 是社区共识 | 未见自定义渲染文档（推断：不能） | — | [官方 Markdown 指南](https://help.janitorai.com/en/article/text-formatting-markdown-guide-1km1qo9/) |
| RisuAI | 显示正则 → CBS 宏 → markdown-it → DOMPurify（允许 iframe/style） | 能：显示正则、CBS 变量/条件、主题/CSS | **能**：Regex 脚本的 `Modify Display`「只改显示、不改聊天数据」 | [README](https://github.com/kwaroran/RisuAI)、[Regex Script wiki](https://github.com/kwaroran/RisuAI/wiki/Regex-Script)、[parser.svelte.ts](https://github.com/kwaroran/RisuAI/blob/main/src/ts/parser/parser.svelte.ts) |
| NovelAI | **纯文本约定**：`your_name: Hello`、`*…*`、`>`、`***` 分节等；编辑器按来源着色，不是富文本（后一句为推断） | 无渲染层可改 | — | [Chat Format](https://docs.novelai.net/en/text/chatformat)、[Special Symbols](https://docs.novelai.net/en/text/specialsymbols) |
| KoboldAI / KoboldCpp | 文本补全前端，Markdown 可选（KoboldCpp 有 Enable Markdown） | 交给 ST 这类前端 | — | [KoboldCpp wiki](https://github.com/LostRuins/koboldcpp/wiki) |
| Agnai | 与 ST 同构：showdown + DOMPurify | 自托管可改代码 | — | [markdown.ts](https://github.com/agnaistic/agnai/blob/dev/web/shared/markdown.ts) |

**关键观察：** 口径最严的 CAI 也只是「固定 Markdown 子集」；最花哨的 ST/RisuAI 的 HTML/CSS
来自**卡作者写的模板 + 前端正则**，不是模型直接生成富文本。渲染管线本身是一段确定的
代码（Markdown 解析 + 消毒），模型只负责文本。

### 2.2 格式约定是谁写的？

- **SillyTavern 的默认提示词里没有任何格式要求。** 默认预设的 Main Prompt 全文是
  `Write {{char}}'s next reply in a fictional chat between {{char}} and {{user}}.`
  （[Default.json](https://github.com/SillyTavern/SillyTavern/blob/release/default/content/presets/openai/Default.json)）。
  格式来自角色卡、作者注、世界书、预设里的自定义 prompt，或用户自己写——**内容层的事**。
- **CAI 官方创作指南**把「（动作）」「`*动作/情绪*`」「"对白"」写成创作约定，并提醒
  星号之间的内容应保持单行才显示正常——说明这是渲染器约束，约定由创作者遵守。
- 社区角色卡也普遍在卡里写格式段（例如「所有动作、移动、肢体语言必须斜体；对白用引号；
  重要细节加粗」这类规则）。

结论：成熟产品的「会渲染」= **默认/创作者写约定 + 前端固定渲染**；平台本身通常不替
创作者发明格式，也不强制模型输出结构。

### 2.3 富 UI（状态栏/好感度）是怎么做的

SillyTavern 的 MVU（MagVarUpdate）是最完整的样本：

1. 提示词/世界书要求模型在回复**末尾**输出 `<UpdateVariable>` 块（内含 `_.set('角色.好感度', 33, 35)`
   一类更新语句）；
2. 角色卡里两条正则：一条 `promptOnly` 把 `<UpdateVariable>…</UpdateVariable>` 从发给 AI 的
   上下文里删掉，一条 `markdownOnly` 把 `<StatusPlaceHolderImpl/>` 替换成前端 HTML；
3. 前端脚本（MVU）扫描更新、按楼层存变量；状态栏本体是卡自带的 **HTML/CSS 模板**，
   数字/条状 UI 从变量取值；
4. 进阶用法用第二个模型解析变量，交互面板走 TavernHelper 的 iframe + JS（其 README 自己
   警告会泄露 API key/聊天记录）。

即：**模型只出占位符/变量更新指令，UI 由前端模板画**；连 JSON schema 都不用。
（[MVU 教程](https://github.com/MagicalAstrogy/MagVarUpdate/blob/master/doc/tutorial.md)、
[卡侧正则示例](https://github.com/ai4rpg/tavern-cards/blob/main/tavern-cards/references/ui/regex-scripts.md)、
[第三方管线笔记](https://github.com/LiarMTTT/TavernWeave)）

### 2.4 有没有真正结构化的剧情通道？

有，但不是主流：

- **Covel**（开源 AI RPG 框架，早期访问版）用 `submit-output` 让模型提交 JSON：
  `segments`（narration / dialogue / action，对白和动作带 speaker）+ `cues`（present/scene/mood）；
  框架校验 + 视角复核，不合格可修正两次，仍不合格整轮失败；舞台模式的名牌直接读
  segment 的 speaker。代价写得很清楚：**故事在提交、校验、落库之后才出现，等待期间只有
  「正在生成」状态，没有草稿级流式文本**。（[story 文档](https://github.com/ackness/covel/blob/main/docs/en/builtin/story.md)、
  [narrator 插件](https://github.com/ackness/covel/blob/main/plugins/narrator/PLUGIN.md)）
- **SillyTavern 有 structured outputs**，但文档限定只有部分 Chat Completion API 支持、
  失败返回 `{}`，用途写明是「扩展需要结构化数据，例如状态跟踪、数据分类」——**核心聊天
  不用**。（[Writing Extensions](https://github.com/SillyTavern/SillyTavern-Docs/blob/main/For_Contributors/Writing-Extensions.md#structured-outputs)）
- 学术侧的 JSON 结构化出现在**评估/裁判**环节（例如 RoleArena 用 JSON 给多维打分），
  不是剧情文本本身。（[RoleArena](https://aclanthology.org/2026.findings-emnlp.1160.pdf)）

### 2.5 中文 RP 产品

- **星野**（MiniMax）：媒体报道其回复大量使用括号描写场景/动作/心理，用户戏称「括号战神」；
  后续版本上线「当前对话回复偏好」让用户调括号描写频率——**文本约定 + 提示词开关**。
  （[媒体报道](https://baijiahao.baidu.com/s?id=1822005744307712751)，社区证据）
- **猫箱**（字节）：测评提到「对话自带括号神态、动作」，输入框提供快捷括号工具——
  同样是文本约定 + 输入辅助。（[36kr 测评](https://ai.36kr.com/note-detail/3568010593722934)，社区/测评证据）
- **筑梦岛**（阅文）：官方角色页开场白就是「（动作）+ 台词」的普通段落。
  （[角色示例](https://m.zhumengdao.com/character?id=6125225056)，产品页证据）
- **Talkie**：创作者指南要求用星号包动作，并说明括号「不会把词变斜体」——说明 `*动作*`
  走 Markdown 斜体渲染，其余仍是约定。（[创作者指南](https://www.talkie-ai.com/zh-Hant/chat/profile/how-to-create-369397252841789)，社区证据）
- 没有公开的排版管线文档；可观察到的「层次」主要来自 app 侧的气泡/语音/立绘/输入辅助
  （推断）。

### 2.6 归纳

1. **文本唯一真源**：主流产品不改写「模型说了什么」，渲染是派生层；连 display-only 正则
   都刻意与存档分离（ST 的 ephemerality）。
2. **约定来自内容层**：卡、预设、提示词、用户习惯；平台固定支持一个很小的 Markdown 子集。
3. **富 UI 走侧信道**：占位符/变量指令 + 前端模板，而不是让模型画整段 UI。
4. **结构化剧情是实验路线**：Covel 证明可行，但用「没有草稿流式 + 校验修复循环 + 依赖
   工具调用」换；对手机端、对多后端兼容都不划算。

---

## 3. 回答你的问题

- **成熟的「渲染」是要求模型输出可渲染格式吗？** 是，但只对了一半：模型输出的是带约定
  的**普通文本**（引号/星号/括号），前端把它当 Markdown/受控 HTML 渲染。约定由内容层
  （卡/预设/提示词）写，渲染由前端固定实现。
- **那「机制」在哪里？** 在显示层：Markdown 解析、HTML 消毒、display-only 正则、状态变量
  与占位符模板。这些是真正的机制，成熟产品看起来「会渲染」正是因为它们有这一层。
- **inhuman 缺什么？** 缺契约（没人要求模型写结构）和显示层（有结构也长得一样）。
  只加提示词能改善，但天花板是模型服从度；要稳定解决，需要 app 侧把「对白 vs 旁白 vs
  动作 vs OOC vs 场景切换」从文本里识别出来并赋予样式。这比 JSON 便宜得多，也不挑模型。

---

## 4. 方案选项

### A. 排版契约 + 场景预设（prompt 层，静态前缀）

- `RoleplayPrompt.renderRules` 增加一段固定的排版说明，例如：
  - 旁白/场景描写：普通段落；
  - 对白：引号（中文「」或“”，英文 `"…"`），一段一个说话人；
  - 动作/神态：（中文括号）或 `*…*`（英文），保持短句；
  - 强调：`**…**`，克制使用；
  - 时间/地点跳跃：单独一行 `---`；
  - 不写用户的台词/心理/动作（已有规则）。
- `scene.format` 保留自由文本，但给三个预设 chips：**小说体**（旁白为主 + 引号对白）、
  **剧本体**（角色名: 对白，动作用括号）、**聊天体**（短句、多对白）。预设只是填入
  `format` 的文本，不改变机制。
- 优点：改动小（纯函数 + 字符串 + 测试）；与现有 Markdown 渲染器完全兼容；静态前缀，
  不破坏缓存（改 `format` 本来就是一次冷请求，`docs/proposals/roleplay.md` §5.1 已有说明）；
  与成熟做法一致。
- 缺点：依赖模型服从；中文斜体弱（所以中文推荐括号而非星号）；不同模型漂移；导出仍是
  Markdown 原文。

### B. RP 显示层（app 侧机制，display-only）

- 纯函数把一条回复切成块/跨度：旁白、对白（引号跨度）、动作（括号或星号跨度）、
  OOC（整条，由用户消息是否 `/ooc` 开头决定，不靠文本猜）、场景分隔（`---` 独立行）。
  Compose 渲染时给不同样式：对白用更醒目的颜色/字形，动作次要色，分隔线用 `Hairline`，
  OOC 用引用样式 + 小标。
- **display-only**：存储的原文、发给模型的请求、导出/搜索/备份都不变；Source 按钮仍看原文。
  这与现有 `withoutOpenFence` 的显示修复同一原则（`docs/06`「Malformed Markdown stays
  readable」）。
- 两种实现形态，需原型验证：
  - **B1（推荐先试）**：前缀稳定的 rewriter，把约定重写成现有 Markdown 能区分的结构
    （如把纯对白段落变成引用），复用 `MarkdownText` 的流式增量。约束与 `LatexMarkdown`
    相同：每条更长前缀的输出必须扩展前一条（`LatexMarkdownTest` 已有这种断言模式）。
  - **B2**：自绘 `AnnotatedString` 段落渲染器，流式时只重绘最后一段。控制力最强，但与
    Markdown 的代码/表格/LaTeX 混合需要回退规则。
- 优点：不依赖模型服从——引号/括号是模型天然会写的；中文视觉最稳；OOC/场景切换能做成真 UI。
- 缺点：流式增量 + Markdown 混合是真正的工程；要双尺寸/大字体真机验证；样式不能太花，
  否则与 Graphite Workbench 的克制风格冲突。

### C. 状态栏/好感度（旁路数据，暂不做）

仿 MVU：模型在回复末尾输出受控块（如 ` ```status ` 或 `<state>…</state>`），到齐后解析、
从正文隐藏，Compose 原生画条；要持久化状态并在下一轮注入。能做得好看，但加 token、加
schema、加失败模式，且与「记录即真相」有摩擦（状态存哪、回退怎么办）。等 A/B 稳定后
再单独立项。

### D. 明确不做

- **JSON / 工具调用输出剧情**：Covel 是反例（无草稿流式、校验循环、依赖工具调用），且伤
  文风与自由发挥；不支持工具调用的模型（我们的很多 provider/演示/本地后端）直接不可用。
- **HTML/CSS/WebView/iframe**：本项目已因 WebView 性能与安全成本否决 Mermaid（`docs/06`
  「Mermaid is deliberately out」）；RP 文本是模型输出，HTML 注入面更大。
- **正则改写历史/存档**：ST 默认把改动写进 JSONL 并明确说不可逆，我们不能抄这一半；
  display-only 可以抄。
- **新「RP 模式/权限」**：RP 是内容配置，格式只是显示（`AGENTS.md`、`docs/13-product.md`）。

**按已拍板的顺序：B 先行（已完成 MVP），A 改为可选增强。** 只加提示词能改善但天花板
是模型服从度；B 不依赖服从，且兼容所有 provider。若以后想让「场景切换/强调」更丰富，
再加 A 的契约和预设即可，功能不依赖它。

---

## 5. 实现草案（给动手时）

### Phase A：契约与预设

1. `RoleplayPrompt.renderRules` 增加固定排版段（英文 system 文本，要求输出语言跟随玩家/场景）。
   中文与英文各给一套推荐约定，但说明「两种都接受」——模型写哪种都能被 Markdown 渲染。
   草稿（~120 tokens，静态前缀，可被缓存；相对 RP 历史是噪声级）：

   ```text
   [ROLE-PLAY FORMAT — how the reply should read]
   Write in the language of the scene. Show structure in the text itself:
   - Narration, scenery and inner beats: plain paragraphs.
   - Spoken words: double quotes ("..."). In Chinese use 「...」 or “...”.
   - Physical actions, gestures and expressions: a short parenthetical （...）
     in Chinese, or *...* in English. Keep each one short.
   - Emphasis: **...**, used sparingly.
   - A time or place jump: a line containing only ---.
   - Plain paragraphs, no headings, no lists, no code fences.
   Never write the player's words, thoughts or actions.
   ```
2. `RoleplayScene.format` 保留自由文本，UI 增加三个预设 chips（小说体/剧本体/聊天体）；
   预设只是填入文本，不新增字段、不改 schema。
3. 更新 `RoleplayPromptTest`；demo provider 的 RP 答复改为展示新约定（中英各一条），
   用于离线/无密钥验证。
4. 文档：`docs/08` 不需要改（不是能力）；`docs/06` / `docs/13` 补一句「排版来自提示词契约
   + Markdown 渲染」，`docs/proposals/roleplay.md` 勾掉 Phase 4 的一部分。

### Phase B：显示层

1. 纯函数 `RoleplayTypography`（放 app 层还是 core 层取决于是否要 JVM 测试；按
   `DeltaCursor`/`LatexMarkdown` 的先例，纯逻辑 + 单测）。
2. 识别规则（宽容，不猜）：
   - 段落；引号跨度：`"…"`、`“…”`、`「…」`、`『…』`（成对才生效，未闭合按字面）；
   - 动作：`（…）`/`(…)` 且处在段首或独立短句，或 `*…*`/`_…_`（延续 Markdown 斜体）；
   - 场景分隔：整段只有 `---` / `***`；
   - OOC：**整条消息**，由前一条用户消息以 `/ooc` 开头判定（语义层，不由文本猜）。
3. 样式（克制）：对白 = 正常色 + 引号强调；动作 = `onSurfaceVariant` + 斜体（英文）；
   旁白 = 正文；场景分隔 = `Hairline`；OOC = 引用块 + `OOC` 小标。
4. 流式策略决策：先做「流式中按已到达前缀渲染、完成后不重排」还是「完成时重排一次」？
   前者需要前缀稳定函数；后者简单但会跳一下。建议 B1 前缀稳定 + 与 `LatexMarkdownTest`
   同样的「更长前缀必须扩展前一条」测试，避免跳变。
5. 测试与验收：
   - 纯函数：分段、成对引号、未闭合标记、中英混排、括号动作的边界；
   - 前缀稳定性：对每条样本的每个前缀断言输出是前一条的扩展；
   - 显示层不改请求：断言 `ChatRequest`/存储文本与改动前一致（只换渲染）；
   - 设备：1280×800 平板 + 360×640 手机（含 1.5× 字体）截图；流式、完成、Source、导出对照。

---

## 6. 需要拍板

1. ~~先做 A 还是一起做 B？~~ **已定：不依赖模型，B 先行**（MVP 已实现）。
2. **中文排版约定**：当前解析器宽容支持「」/“”/`""` 与 `（）`/`*…*`，无需选择；若以后
   加提示词契约（A），再决定按语言推荐哪一种写法。
3. **OOC 视觉要不要？** 推荐要：`/ooc` 是现成语义，可以把整条回复旁注化；还没做。
4. **状态/好感度面板进不进路线图？**（推荐暂不进 C。）
5. **Phase B 的流式**：当前实现是每次快照重解析；实测没有可见卡顿，若长回复变慢再考虑
   增量（需要前缀稳定 + 更细的脏区管理）。

---

## 7. 来源

**确证（官方文档/源码）**

- SillyTavern 格式化管线：`messageFormatting()` 的 11 步注释
  <https://github.com/SillyTavern/SillyTavern/blob/release/public/script.js>
- SillyTavern 默认 Main Prompt（无格式要求）：
  <https://github.com/SillyTavern/SillyTavern/blob/release/default/content/presets/openai/Default.json>
- SillyTavern Regex 扩展（display-only / prompt-only）：
  <https://docs.sillytavern.app/extensions/regex/>
- SillyTavern structured outputs 的定位（扩展数据，不是聊天）：
  <https://github.com/SillyTavern/SillyTavern-Docs/blob/main/For_Contributors/Writing-Extensions.md#structured-outputs>
- Character.AI Markdown 子集：
  <https://book.character.ai/character-guide/advanced-creation/beyond-plain-text/markdown-styling>
- Character.AI 括号/斜体/引号约定：
  <https://book.character.ai/character-guide/advanced-creation/beyond-plain-text/parenthetical-comments>
- JanitorAI Markdown 指南：
  <https://help.janitorai.com/en/article/text-formatting-markdown-guide-1km1qo9/>
- RisuAI README（Regex Script / 主题 / 表情图）：
  <https://github.com/kwaroran/RisuAI>
- RisuAI Regex Script wiki（Modify Display）：
  <https://github.com/kwaroran/RisuAI/wiki/Regex-Script>
- Covel 结构化剧情与代价（无草稿流式）：
  <https://github.com/ackness/covel/blob/main/docs/en/builtin/story.md>
- NovelAI Chat Format / Special Symbols（纯文本约定）：
  <https://docs.novelai.net/en/text/chatformat>、<https://docs.novelai.net/en/text/specialsymbols>

**社区/第三方（标注为分析或教程）**

- Mess 渲染与正则管线（第三方设计笔记，与源码注释互相印证）：
  <https://github.com/LiarMTTT/TavernWeave>
- MVU 变量教程：<https://github.com/MagicalAstrogy/MagVarUpdate/blob/master/doc/tutorial.md>
- 卡侧正则示例（占位符 → HTML 状态栏）：
  <https://github.com/ai4rpg/tavern-cards/blob/main/tavern-cards/references/ui/regex-scripts.md>
- AgnAI Markdown 实现：<https://github.com/agnaistic/agnai/blob/dev/web/shared/markdown.ts>
- KoboldCpp wiki（Enable Markdown）：<https://github.com/LostRuins/koboldcpp/wiki>

**中文产品（媒体/测评/产品页证据）**

- 星野「括号战神」与回复偏好：<https://baijiahao.baidu.com/s?id=1822005744307712751>
- 猫箱测评：<https://ai.36kr.com/note-detail/3568010593722934>
- 筑梦岛角色页：<https://m.zhumengdao.com/character?id=6125225056>
- Talkie 创作者指南：<https://www.talkie-ai.com/zh-Hant/chat/profile/how-to-create-369397252841789>

**补充：学术侧 JSON 用于评估而非剧情**

- RoleArena：<https://aclanthology.org/2026.findings-emnlp.1160.pdf>

---

## 9. 外部角色卡与 HTML 兼容（追加调研，2026-11-26）

用户提出：外部社区的角色卡可能自带 HTML 界面，导入时是否要兼容。下面是数据、局限和建议。

### 9.1 先分清两种「HTML 卡」

- **A. 卡文本里的 HTML**：作者把 `<div style=...>`、`<details>`、内联颜色直接写进 greeting/description。它随卡文本一起进上下文（费 token），由前端 Markdown/HTML 渲染器画出来。这是「早期/朴素」形态。
- **B. 前端卡 / 美化包**：卡里 `data.extensions.regex_scripts` 存「显示正则 + 几百行 HTML/CSS 模板」，模型每轮只输出纯文本 marker（如 `[状态|好感|85]`），regex 在**显示期**把 marker 替换成 HTML；更复杂的还要 TavernHelper/MVU 之类 JS 运行时、变量和按钮 API。HTML 不进上下文，但**离开对应运行时就不完整**。CCv3/CHARX 时代的 UI 卡主要走这条路。

### 9.2 有多少卡带 HTML（可量化部分）

对 HuggingFace 上的 Chub 卡快照 `raincandy-u/chub-ai-cards`（6448 张，2023-03 到 2026-02，全部 CCv2，**不含 extensions/regex 字段**）做本地统计：

| 特征 | 卡数 | 占比 | 备注 |
| --- | --- | --- | --- |
| 卡文本含原始 HTML 块标签（div/span/style/table/details） | 177 | **2.75%** | 其中首条消息（greeting）里带 HTML 的 41 张，0.64% |
| 明确提到 status bar / status panel / 状态栏 | 26 | **0.40%** | 平均 nChats 906，全体 394（样本小，只能算提示） |
| 明确写引号/星号/斜体等**格式约定** | 480 | **7.44%** | 平均 nChats 610，说明「格式约定」比「HTML 界面」常见一个数量级 |
| MVU/regex 标记 | 0 | 0% | 数据集没抓 `extensions`，**不代表没有**；这是下界 |

局限：Chub 英文样本、快照偏旧、不含 `regex_scripts`；所以 2.75% 是「带 HTML 痕迹」的下界，**不是**「前端卡占全部卡的比例」。当前 CCv3 卡把状态栏/变量/选项写在 `data.extensions.regex_scripts` 里（`dsh-tavern` changelog 原话），这类卡的 HTML 完全不在上表里。

### 9.3 生态与竞品证据

- 中文酒馆社区明确把卡分「**纯文字 / 前端**」两类，前端卡做状态栏、手机聊天界面；有「前端卡零基础讲解」类教程；有创作者文章称美化（状态栏、折叠思维链、主题皮肤）是中文酒馆社区最有生命力的手艺，但这套生态默认长在桌面浏览器里。
- 手机端已有产品支持：**Tavo**（App Store/Google Play：Spec V1/V2/V3、PNG/JSON/CHARX、HTML & CSS 标准渲染、JavaScript）；**Foreverse/新梦**（marker → 正则 → WebView 三段链路、内联 CSS/JS、禁外部资源、每围栏独立作用域、失败回退原文；另有原生 `fv-card` 小卡协议）。
- DSH 生态有 `dsh-html-block-renderer`：安全白名单 `dsh-html`（过滤标签/属性/CSS/URL，按钮只能触发受控聊天动作）+ 沙箱 iframe `dsh-html-full`（用户点击才执行 JS，`sandbox="allow-scripts allow-modals"`，无 same-origin）；配合 Tavern bundle 可应用卡的 `regex_scripts`、解析 `UpdateVariable`。
- 开源 Lumiverse 的导入清单代表「成熟导入」：PNG / JSON（CCSv1–3）/ CHARX、Chub/CharacterHub/Janitor URL 导入、内嵌世界书自动导入、CHARX modules；渲染侧有 display-only 正则，可把输出变成 HTML。

### 9.4 对 inhuman 的判断

1. **第一刚需不是 HTML，是导入兼容。** 我们现在只认自家 `rp/<story>/card.json`；社区卡是 PNG（内嵌 CCv2 JSON）/ JSON / CHARX，还带 alternate greetings、character_book、extensions。不先支持这些，用户下载的卡根本进不来。
2. **HTML 不是「加个渲染器」。** 前端卡的 HTML 依赖 `regex_scripts` + JS/变量才有内容；只渲染静态 HTML 会得到一半空/坏的面板，体验可能比诚实降级更差，而且卡内容是不可信第三方输入，执行 HTML/JS 是安全面。
3. **本项目已明确不要 WebView**（Mermaid 先例），所以「完全兼容」= WebView + 正则引擎 + TavernHelper/MVU 兼容层，是一个产品级决定，成本/风险远大于本次显示层。
4. 一个中间形态是可行的：**安全的静态 HTML 子集 → Compose 原生渲染**（白名单标签、有限 CSS、无 JS、无外部资源；按钮只做用户可控的填输入框/发送），相当于 `dsh-html` 的 Android 原生版。它覆盖「状态卡/UI 卡」的静态外观，不引 WebView，但需要 HTML/CSS 子集解析器与安全审查，且对完整美化包仍然不完整。

### 9.5 建议路线

- **P0（必做，先于 HTML）**：导入兼容 + 安全降级。CCv2/v3 PNG/JSON/CHARX；字段映射（`first_mes`→greeting、`mes_example`、`alternate_greetings` 取第一张、`character_book`、`extensions` 作为「未使用部分」报告）；卡文本里的 HTML 在导入时清洗成可读文本；明确提示哪些部分没有导入。**P0a 已实现**（见 §9.6）；「原样保留 extensions/原始文件」推迟到 P0b，因为现在的产品没有正则/世界书功能可读它们，存着只会成为死数据。
- **P1（可选）**：安全静态 HTML 子集的原生渲染（覆盖状态卡外观，不执行脚本/外部资源）。先只支持白名单标签与 inline style 的有限属性，不做任意 CSS。
- **P2（暂不做）**：沙箱 WebView + `regex_scripts` + TavernHelper/MVU 兼容的「完整前端卡」路线。只有当明确要支持美化包生态、并接受 WebView 与兼容层成本时再做。
- **不做**：直接执行卡里的任意 HTML/JS；也不要把 HTML 写进系统提示或请求（「HTML 不占 token」靠的是显示期替换，不是把模板发给模型）。

待拍板：P0 的导入格式优先级（PNG / JSON / CHARX / URL）——**已定：三种文件格式都做，URL 导入不做**；是否连 character_book（世界书）一起导入——**暂不做，导入时明确报告条数**；P1 是否要；P2 是否进入长期路线。

### 9.6 实现状态：P0a 外部卡导入（2026-11-26）

**已实现**（`core/engine/.../roleplay/RoleplayCardImport.kt` + 助手角色卡编辑器的「从文件导入…」）：

- 格式：**JSON**（CCv1 扁平 / CCv2 / CCv3 / 本应用自己的 `card.json`）、**PNG**（`tEXt`/`iTXt` 的 `chara` base64 载荷；也支持带前导数据的 ZIP）、**CHARX**（ZIP 或 JPEG+ZIP，取 `card.json`）。
- 映射：`description`、`personality`、`scenario`、`first_mes`→greeting、`mes_example`→exampleDialogue；`{{char}}` 替换为助手名，`{{user}}` 原样保留（由既有 RP 提示解释）。
- 清理：映射文本里的 HTML 转为纯文本（script/style 内容丢弃、保留文字、`<START>` 变行分隔、实体解码）；**不执行、不加载外部资源、不保留标签**。
- 上限：文件 16 MiB；字段按 `RoleplayLimits` 截断并**逐项报告**，卡名占同一总量预算；不会静默截断。
- 报告（弹窗逐条显示，zh/en）：卡片名、HTML 已清理、截断字段、备选开场白/仅群聊开场白条数、内嵌世界书条数、作者注记、`system_prompt`、`post_history_instructions`、扩展数据（含 regex/HTML UI）、素材条数——全部「未导入」而非假装成功。
- 数据不落盘到 UI 之外：导入结果只填进当前卡编辑对话框的草稿，用户点「保存」才写入；原始文件不留在应用里（用户手上还有）。

**未做（P0b/P1/P2）**：alternate greetings 选择器、世界书/正则/HTML 数据的保留与启用、CHARX 素材（头像/表情）、URL 导入、安全静态 HTML 子集渲染、WebView 兼容层。

**验证**：`RoleplayCardImportTest` 13 个 JVM 测试（三种格式、v1/v2/v3、HTML 清理、截断、占位符、失败原因、PNG/CHARX/JPEG+ZIP）；CI 全绿；模拟器导入一张 CCv2 JSON 卡，界面逐条显示警告并填入字段，平板与手机宽度截图检查。

### 9.7 本节来源

- 统计样本：<https://huggingface.co/datasets/raincandy-u/chub-ai-cards>（本次用 pyarrow 本地统计，脚本与数据不进仓库）
- 中文社区「纯文字/前端卡」分类与例子：<https://www.pttweb.cc/bbs/AC_In/M.1756447993.A.17A>
- 前端卡入门教程（小红书）：<https://www.xiaohongshu.com/discovery/item/68ab09f1000000001d01697c>
- 美化包桌面生态与手机渲染链路（Foreverse/新梦）：<https://foreverse.app/zh/blog/card-beautify-on-phones>
- 手机竞品 Tavo（HTML/CSS/JS、PNG/JSON/CHARX、Spec V1–V3）：<https://play.google.com/store/apps/details?id=app.bitbear.tav>、<https://apps.apple.com/cn/app/id6744438897>
- DSH HTML 卡渲染插件（安全白名单 + 沙箱 iframe、Tavern regex 兼容）：<https://www.npmjs.com/package/dsh-html-block-renderer>
- CCv3 把状态栏/变量/选项放进 `data.extensions.regex_scripts`（dsh-tavern changelog）：<https://cdn.jsdelivr.net/npm/dsh-tavern@2.8.1/CHANGELOG.md>
- Lumiverse 导入格式与 display-only 正则：<https://github.com/prolix-oc/lumiverse/blob/d60addb2f0064b358bff7edf51d1c8bfc815908e/user-docs/docs/characters/importing-characters.md>、<https://github.com/prolix-oc/lumiverse/blob/d60addb2f0064b358bff7edf51d1c8bfc815908e/user-docs/docs/customization/regex-scripts.md>
- CHARX（ZIP/JPEG+ZIP 的卡+素材包）：<https://www.npmjs.com/package/@character-foundry/charx>
- 状态栏/HTML 模板的技术笔记（TavernWeave C3）：<https://github.com/LiarMTTT/TavernWeave/blob/main/skills/consult-tavernweave-library/references/st-guides/C3_HTML%E7%BE%8E%E5%8C%96%E4%B8%8ECSS.md>


