# 本机 web 工具（免费 search / fetch）— 设计提案

> **状态：已实现（2026-11-27，手机端）。** 已拍板并落地：搜索与抓取拆成两个独立开关；本机
> 抓取按对话授权；**不做 App 内代理**，出口依赖用户手机上的系统 VPN（TUN）；本机抓取仅
> `https`；引入 jsoup 做正文提取；`render` 用 WebView 实现。
>
> 实现与下文提案的差异：consent 列是 **schema v30**（v29 已被内置工坊合并占用，迁移改名
> `MIGRATION_29_30`）；`render` 已包含（§4.3.1）；**客户端限速/每回合上限未实现**，记为
> 后续项（见 `docs/07-gotchas.md` A32）。当前行为以 `docs/03-ai-provider.md` 与
> `docs/08-port-philosophy.md` §1.7 为准，本文保留设计与决定记录。
>
> **这是一份"新例外"的提案。** 本机搜索只是 `docs/08-port-philosophy.md` §1.2 第三类
> （「用户显式打开、由 App 代发的只读内容源」）的新成员，和 B 站工具有先例可循；**本机抓取
> 则是 `download_url` 同一例外家族的新成员**——模型能让 App 在用户自己的网络上免费、不限次地
> 请求任意 URL。落地前必须回答 §1.2.1 / §1.5 / §1.6 的问题，并把 `AGENTS.md` 里
> "Two exceptions exist" 改成三个（或改写为"两个例外家族"）。本提案给出一版答案，供拍板。
>
> 实现时改：`docs/03-ai-provider.md`（机制）、`docs/08-port-philosophy.md`（§1.2 第三类
> 加成员、§1.7 新例外）、`docs/13-product.md`（功能列表与措辞）、`docs/README.md`
> （提案索引与测试表）、`docs/07-gotchas.md`（新的不变量）、`AGENTS.md`（例外数量与
> load-bearing 条目）、`README.md`。

---

## 0. 结论先行

- 新增两个工具，与现有付费工具**并列**而不是替换：
  - `web_search_local`：App 自己请求搜索页（DuckDuckGo html，备选 Bing RSS），免费、无 key；
  - `web_fetch_local`：App 自己 GET 一个页面（仅 `https`），jsoup 抽正文；可选
    `render=true` 用 Android WebView 渲染后再抽取；免费、无 key。
- 出口只用**手机的系统网络**：用户开系统 VPN（TUN）时请求自动走代理；App 不做代理设置、
  不读取 VPN 配置、不内置任何翻墙逻辑。VPN 关着时，工具看到的就是手机直连能看到的网络。
- 门禁两条，形状不同：
  - **搜索**：全局开关 + 助手显式选择（`optIn`），不做每对话授权——和 `bili_search` 同类，
    参数只有 query，没有 URL；
  - **抓取**：全局开关 + 助手显式选择 + **每对话一次授权**（`conversations.localFetchAllowed`，
    下一版 schema——暂记 v29；`proposals/workshop-merge.md` 也占用 v29，先落地者用 v29、
    另一个顺延。同时加 SSRF 守卫 + 仅 `https` + 公网地址 + GET-only + 无 cookie/凭据/自定义头 +
    诊断只记 host/字节数。这是 `download_url` 的同一套形状。
- 现有 `web_search` / `web_fetch` 与它们的 DeepSeek/Exa 后端**不动**。免费工具不能做成
  "第三个后端"，因为 `paid` 是静态元数据，被助手徽章、审批表、transcript 工具头、
  `UsageReport.paidToolNames` 共同依赖；把 `paid` 改成随设置变化会污染历史调用的语义。
- 不做：App 内代理、SearXNG/公共实例、点击/滚动/表单/登录等浏览器自动化、cookie、POST、
  付费/免费自动回退、把结果写进工作区。`render` 只做"加载页面并抽取"，不做交互。

---

## 1. 现状（代码事实）

### 1.1 今天的 web 工具全部是"提供商在远端执行"

- `web_search` / `web_fetch` 是 `Tool`，但请求由所选后端发出：
  `DeepSeekSearchApi`、`ExaSearchApi`、`DeepSeekFetchApi`、`ExaFetchApi`
  （`core/ai/.../provider/{deepseek,exa}/`）。工具注释明确写着 "These tools never open the
  page"（`docs/03-ai-provider.md:1143-1146`）。
- 接口只有两个方法：`SearchProvider.search(query, maxResults)`（`WebSearchTool.kt:27-42`）
  和 `FetchProvider.fetch(url)`（`WebFetchTool.kt:8-30`）。结果结构是
  `SearchResult(title, url, snippet, publishedAt)` 与 `FetchedPage(title, content)`。
- 后端按 key 注册：`WebToolProviders.search/fetch`（`app/.../web/WebToolProviders.kt:24-44`），
  `MainActivity.kt:623-624` 把闭包传给 `ChatViewModel`；没有 key 时工具**不注册**
  （`ChatViewModel.kt:6487-6488`），不是注册后一直失败。
- 两个工具在目录里是 `paid = true`（`ToolCatalog.kt:230-241`），`PAID` 徽章出现在助手编辑器、
  审批表、transcript 工具头和 `UsageReport` 的付费计数里（`docs/03-ai-provider.md:1086-1091`）。

### 1.2 已有三种可复制的门禁形状

| 形状 | 先例 | 门禁 |
| --- | --- | --- |
| 免费只读外部源 | B 站十工具（`ToolCatalog.kt:242-316`） | Settings 全局开关 + 助手 `optIn`；开关关闭时从 picker/请求/缺口报告**消失**，不是灰行；无每对话授权 |
| 免费任意 URL 通道 | `download_url`（`ToolCatalog.kt:400-410`） | 全局开关 + `optIn` + 每对话授权（`conversations.downloadAllowed`，`Entities.kt:455`）+ `DownloadUrl` 守卫；撤销在聊天菜单；全局关清空所有授权 |
| 缺配置即消失 | 媒体工具（`gatedByMediaConfig`） | 不是能力开关，本提案不适用 |

本机搜索照第一行，本机抓取照第二行。

### 1.3 可复用的 SSRF / 下载设施

- `DownloadUrl.validate`（`core/ai/.../tools/DownloadUrl.kt:37-121`）：scheme、长度 8192、
  无凭据、字面私网地址；`requireHttps` 参数已经存在。
- `UrlDownloader`（`app/.../ui/files/UrlDownloader.kt:65-140`）：`safeDns` 拒绝私网/回环/
  链路本地/CGNAT/保留段、`followRedirects(false)` 手动逐跳重校验、`CookieJar.NO_COOKIES`。
- `HttpDownloadFetcher`（`app/.../download/HttpDownloadFetcher.kt`）是 `download_url` 的
  `DownloadFetcher` 实现，复用的就是这个对象。**`docs/08` §1.6 要求不要出现第二份
  `DownloadUrl` 规则**，所以本提案把 guard 客户端抽到 `core:ai` 共用（§4.5）。
- 目前没有 jsoup 依赖（`gradle/libs.versions.toml` 无 `jsoup`；`core/ai` 只有 OkHttp 等）。

### 1.4 目录、工具族与调用点

- `ToolCatalog.visible(includeSsh, includeWorkspaceBound, includeSandbox, includeSuggestions,
  includeBilibili, includeBilibiliLogin, includeDownload, includePdfVision, mediaConfigured)`
  （`ToolCatalog.kt:502-565`）；`permits` 对 `remote || optIn` 要求显式选择，
  `null`（"全部"）不含它们（`:597-601`）；`everythingExplicit` 再过滤一次 `optIn`（`:629-639`）。
- 需要补参数的 6 个 App 调用点：`AgentsScreen.kt` 三处、`SettingsScreen.kt:3457`、
  `ConversationTools.kt:70`、`ChatViewModel.kt:6785`（缺口报告）。
- 工具族映射在 `app/.../ui/common/ToolGroups.kt:52-55`，目前按名字判断
  `web_search` / `web_fetch` 为 `Web` 族；人类可读 blurb 在 `ToolBlurbs.kt:18-53`。
- 设置页 02 区块：付费后端 chips 与 key 字段、B 站开关（`SettingsScreen.kt:472-760`）；
  `SettingsViewModel` 持有状态并写 `SecureStore`（`KEY_WEB_SEARCH_BACKEND` /
  `KEY_WEB_DEEPSEEK_API_KEY` / `KEY_WEB_EXA_API_KEY`，`SecureStore.kt:196-198`；
  B 站开关 `:271`，下载开关 `:330`）。

---

## 2. 目标与非目标

**目标**

1. 不经过任何搜索/抓取 API 提供商，也能给模型一个可用的 `search` 和 `fetch`。
2. 出口是用户自己的网络/VPN，所以"用户浏览器能打开的站"与"工具能读的站"尽量一致。
3. 免费、匿名、只读；失败如实报告，不伪装成"没有结果"。
4. 门禁与现有项目规则一致，不新增"模式"、不做可升级的权限。

**非目标**

- 不解决模型层：抓回来的内容仍要发给对话模型；模型若在境内，内容仍经过它
  （`docs/13-product.md` 的产品措辞要写清这一点）。
- 不做登录墙 / cookie jar / POST / 表单 / 点击滚动等浏览器自动化；`render` 只做"加载页面
  并抽取"，不做交互。
- 不做 App 内代理设置（用户已确认用系统 VPN）；不读取系统 VPN 配置。
- 不做 SearXNG、公共实例、其他引擎插件；v1 只有 DDG html 与 Bing RSS。
- 不做"免费失败自动切付费后端"的静默回退；用户自己选工具。

---

## 3. 能力分类（必须先回答的问题）

### 3.1 本机搜索：§1.2 第三类的新成员，不是新例外

- 请求由 App 发出，助手自身仍没有网络；工具参数只有 `query`，没有 URL；免费、无凭据、
  匿名、只读——形状与 `bili_search` 完全相同（`docs/08-port-philosophy.md:61-72`）。
- 所以门禁照 B 站：Settings 全局开关（默认关）+ 助手显式选择（`optIn`）；关闭时工具从
  选择器、请求 schema、审批表与 `unavailable_tools` 里消失；`null` "全部"不包含它。
- 需要在文档里说清的新事实：**query 文本会经用户的网络离开设备**（B 站先例已接受
  "关键词离开设备"这一类）。

### 3.2 本机抓取：`download_url` 家族的新成员，边界句子要从"两个例外"变成"三个"

按 §1.6 的问题逐条回答：

- **为什么需要。** 付费 `web_fetch` 的页面由后端服务器抓取，受后端的位置、索引与合规
  策略限制；用户经常要读的正是这类页面。App 在用户自己的网络上抓，页面看到的是用户的
  出口（VPN 开着时即用户浏览器的出口）。
- **它为什么不是 `web_fetch`。** 执行位置不同（手机 vs 提供商）；免费且不限次；
  本机网络成为请求来源（SSRF/本机服务风险）；URL 可以携带数据出去。
- **它为什么需要和 `download_url` 同样的门禁。** `download_url` 的门禁不是为"文件"设的，
  是为"模型可以免费、不限次地让 App 发一个 URL"设的（`docs/08:162-168`）。本机抓取把
  这条通道从"字节进工作区"扩展到"正文进上下文"，风险同族，门禁必须同形：
  全局开关（默认关）+ `optIn` + 每对话一次授权 + 硬限制 + 诊断只记 host/字节。
- **为什么不是"只允许用户消息里的 URL"这种结构方案。** §1.6.1 已经评估并拒绝过一次；
  本机抓取不重新讨论，沿用同一结论。
- **能力句子怎么变。** 从"两个例外"变成三个：SSH、URL 下载、**本机抓取**（后两者属于
  同一族：模型可让 App 向外部发请求）。`AGENTS.md`、`docs/08` §1.2 与 §1.6 附近、
  `docs/13-product.md` 的网络段落都要同步改。

### 3.3 为什么不做 App 内代理

用户已确认系统 VPN（TUN）。App 读不到、也不需要读 VPN 配置；如果代理只是本地端口、
不走 TUN，本机工具就按手机直连能力工作——不静默换后端、不伪装成功。App 内代理是另一类
网络配置（"哪些客户端走代理"变成 App 状态、代理凭据进 `SecureStore`、需要绕过
LAN/Tailscale/更新地址），不是本次目标，记录为拒绝的替代方案（§9）。

### 3.4 为什么不把免费实现塞进现有 `web_search` / `web_fetch`

`paid` 是静态元数据：助手徽章（`AgentsScreen.kt` 两处）、审批表（`SettingsScreen.kt:3457`
附近）、transcript 工具头（`ChatScreen.kt`）、`UsageReport` 的 `paidToolNames`
（`UsageReport.kt:131-142`）。让 `paid` 随"当前后端"变化，会让历史调用的付费归属
无法解释。新增名字、静态 `paid = false` 最干净，也最诚实。

---

## 4. 工具设计

### 4.1 名字与参数

| 工具 | 参数 | 返回 |
| --- | --- | --- |
| `web_search_local` | `query: string`（必填，关键词）；`max_results: integer`（1–10，默认 5） | 与 `web_search` 相同的文本列表：query — N results、编号、标题、URL、摘要；空结果 `No results for "…"`；失败 `search failed: …` |
| `web_fetch_local` | `url: string`（必填，绝对 `https` URL）；`render: boolean`（选填，默认 false；Android 端用 WebView 加载后抽取，核心模块通过注入的 renderer 接口调用） | 与 `web_fetch` 相同的格式：`# title` + URL + 正文；超过 20,000 字符加 `[truncated at 20000 characters]`；二进制/空正文是错误，不是成功 |

实现上给 `WebSearchTool` / `WebFetchTool` 增加 `name`、`description` 参数（默认值保持
现状，现有测试与调用点不变），格式化/截断逻辑复用同一份。

### 4.2 `web_search_local`

- **引擎一：DuckDuckGo html**（默认）——`https://html.duckduckgo.com/html/?q=<encoded>`；
  解析结果链接的 `uddg=<percent-encoded target>` 并解码；摘要去标签、解实体。
- **引擎二：Bing RSS**（设置可选）——`https://www.bing.com/search?q=<encoded>&format=rss`；
  上一轮实测 5–7 KB、10 条、`<item><title|link|description|pubDate>`，链接是直达 URL；
  解析时禁止 DOCTYPE（XXE），用 jsoup 的 XML parser 或等价受限读取。
- 共同规则：
  - 浏览器 UA + 设备 `Accept-Language`（`Locale.getDefault()`）；不发 cookie；
  - 超时 15 s；响应字节上限 1 MiB；结果按 URL 去重；取前 `max_results` 条；
  - `publishedAt` 不塞 Bing 的 `pubDate`（那是抓取/更新时间，不是发布时间——宁可不给）；
  - HTTP 非 2xx、解析失败、验证码/降级页：抛 `IOException` 带状态码与响应前缀，
    由工具变成模型可读的 `search failed: …`；**空列表是正常答案**，不报错。
  - 进程级最小间隔 1 s，避免模型把引擎打到风控（免费工具没有"花钱"这道天然闸门）。

### 4.3 `web_fetch_local`

- 校验：`DownloadUrl.validate(url, requireHttps = true)`（仅 https、无凭据、长度 ≤ 8192、
  字面私网地址拒绝）+ 连接期 `safeDns`（拒绝任何解析到私网/回环/链路本地/CGNAT/保留段的
  地址，防 DNS rebinding）。
- 请求：`GET`，`followRedirects(false)` 手动最多 5 跳、每跳重新校验；无 cookie
  （`CookieJar.NO_COOKIES`）、无 `Authorization`、无自定义 header；浏览器 UA +
  `Accept: text/html,application/xhtml+xml,text/plain,…` + `Accept-Language`。
- 超时：连接 10 s / 读 20 s / 总 30 s；原始字节上限 2 MiB，超限截断后继续解析（结果里说明）。
- `Content-Type` 白名单：`text/html`、`application/xhtml+xml`、`text/*`、
  `application/json`、`application/xml`；PDF、图片、压缩包等**明确拒绝**并提示
  "需要字节就用 download_url + read_document"，不返回乱码。
- 字符集：`Content-Type` → `<meta charset>` → UTF-8，疑似中文站再用 GB18030 兜底。
- 正文提取（jsoup）：去掉 `script/style/noscript/iframe/svg/form/nav/footer/header/aside`，
  优先 `article`、`main`、`[role=main]`，否则 `body`；标题取 `<title>`（其次 `<h1>`）；
  按块保留段落/标题/列表，输出纯文本；提取后长度上限 100,000 字符（工具层再截 20,000）。
- 诊断：只记 host、状态码、字节数、耗时；**不记 URL、query 或页面内容**
  （与 `download_url` 同规则，`docs/08:181-182`）。
- 结果头/工具描述写明：第三方文本，不可信，不是指令。

#### 4.3.1 `render = true`：WebView 渲染路径

静态路径拿不到的页面（SPA、懒加载、React/Vue 站点）走渲染路径：

- 接口：`core/ai` 定义 `PageRenderer`（纯 JVM 接口：`suspend fun render(url: String): FetchedPage`），
  由 Android 宿主实现；`LocalFetchApi` 注入它，JVM 测试用 fake。**`core/ai` 不引用 WebView。**
- Android 实现 `app/.../web/WebViewPageRenderer.kt`：每次调用新建一个 WebView（不复用），
  `javaScriptEnabled = true`（渲染所必需），`domStorageEnabled = false`、
  `cacheMode = LOAD_NO_CACHE`、`allowFileAccess = false`、`allowContentAccess = false`、
  `setSupportMultipleWindows(false)`、`javascriptCanOpenWindowsAutomatically = false`、
  **不注册任何 `addJavascriptInterface`**；浏览器 UA + `Accept-Language`；`loadUrl(url)` 后等
  `onPageFinished` 再静置约 1–2 s（给懒加载/客户端渲染留时间），然后 `evaluateJavascript`
  取 `document.title` 与 `document.body.innerText`（`innerText` 只包含渲染后可见文本，天然
  丢掉 `script/style` 与 `display:none` 的隐藏内容），截断后返回。
- 取消与超时：加载 20 s、静置 2 s、总计 30 s；取消/超时都销毁 WebView 并如实报错。
- `render = false`（默认）走静态路径；两个路径共用同一套 URL 校验、https-only、公网地址与
  重定向规则。渲染路径仍然只允许 `https`，仍然没有 cookie/凭据注入。
- 成本：渲染比静态抓取慢一个数量级、更耗电；建议每回合渲染抓取次数上限可配（默认 3）。
- 失败：WebView 不可用、加载超时、`innerText` 为空 → 明确错误；**不回退到静态路径假装
  成功**（模型可以自己再发一次 `render=false` 的调用）。

### 4.4 渲染路径的边界（写进工具描述与设置文案）

- `render=true` 会执行页面自己的脚本——这既是渲染的方式，也是风险所在：页面可以发自己的
  网络请求（统计/追踪），也可以尝试读 `localStorage`；App 不注入 JS bridge、不提供应用数据、
  不共享登录态，WebView 用后即销毁，cookie 与 storage 在调用结束后清掉。
- 仍然只返回**文本**（`innerText`），不返回 raw HTML；页面内容对模型仍是不可信文本。
- 静态路径抓回的可能是骨架或一小段文本；工具描述要求模型在结果明显不完整时说明，
  或改用 `render=true` 再试一次，而不是假装读到了整页。
- 不做点击、滚动、表单、登录等浏览器自动化；`render` 是"加载并抽取"，不是无头浏览器。

### 4.5 共用的 guard 客户端与依赖

- 把 `UrlDownloader` 里的 `safeDns`、手动重定向循环、无 cookie 客户端抽到
  `core/ai/.../tools/GuardedHttp.kt`；`UrlDownloader` 与 `HttpDownloadFetcher` 改为调用它，
  行为不变（现有 `DownloadUrlTest` / `DownloadIntegrationTest` 必须继续绿）。
- 本机 fetch 复用同一份实现，只加"最大响应字节"参数（下载路径不设上限，由调用方流式处理）。
- `core/ai/build.gradle` 加 `org.jsoup:jsoup`（纯 Java，MIT）；`gradle/libs.versions.toml`
  记版本与理由；`THIRD-PARTY-NOTICES` 更新。
- 渲染器：`core/ai` 只定义 `PageRenderer` 接口；Android 实现在
  `app/.../web/WebViewPageRenderer.kt`（真 WebView 只能在设备上验证）；JVM 测试注入 fake。

---

## 5. 目录、设置与接线

### 5.1 ToolCatalog

- 新 `Entry`：`web_search_local`、`web_fetch_local`，均为 `mutates = false`、
  `paid = false`、`workspaceBound = false`、`optIn = true`；
- 新 gate 标志（拆开，便于两个开关独立控制可见性）：`gatedByLocalSearch`、
  `gatedByLocalFetch`；
- `visible(...)` 增加 `includeLocalSearch = false`、`includeLocalFetch = false`；
- `permits` 不需要改（`optIn` 已经要求显式选择）；
- `everythingExplicit` 自动排除（`optIn`）。

### 5.2 工具族与 blurb

- `ToolGroups.toolFamilyOf`：两个名字 → `ToolFamily.Web`（与 `web_search` / `web_fetch` 同族）；
- `ToolBlurbs.kt` + `strings.xml` / `values-zh-rCN/strings.xml` 加两条人工 blurb。

### 5.3 ChatViewModel 与 6 个可见性调用点

- 构造参数增加：`localSearchEnabled: () -> Boolean`、`localFetchEnabled: () -> Boolean`、
  `localSearchProvider: () -> SearchProvider?`、`localFetchProvider: () -> FetchProvider?`
  （默认 `{ false }` / `{ null }`，测试不用改）；
- `buildTools`：`if (!demoEnabled()) { if (localSearchEnabled()) add(WebSearchTool(…, name = "web_search_local", …)); if (localFetchEnabled()) add(WebFetchTool(…, name = "web_fetch_local", …)) }`，
  末端的 `ToolCatalog.permits` 过滤自动挡住未勾选的；demo 模式保持离线（网络工具不注册）；
- `unavailable_tools` 的 `visible(...)` 传两个新参数：开关关闭时名字不进"缺口"（§1.2.1 的
  "不能说出来的名字"规则）；
- 助手编辑器（三处）、`SettingsScreen` 审批表、`ConversationTools` 补两个参数。

### 5.4 本机抓取的每对话授权（schema 下一版）

照 `download_url` 的 `gateEngineCall` 分支（`ChatViewModel.kt:5896-5960`）复制形状：

- 工具名 `web_fetch_local` 且开关开着时：先 `DownloadUrl.validate(raw, requireHttps = true)`，
  无效 URL 直接 Deny（免费本地错误），然后检查 `localFetchAllowed`（内存 + 持久化双读），
  未授权则发布 `pendingLocalFetchConsent`（显示 host 与"URL 会经你的网络发出、可以携带
  数据出去"的说明）并 `await`；拒绝 = `Declined` 并停止本回合，允许 = 持久化
  `conversations.localFetchAllowed = 1`；之后走普通 `ToolPolicy`；
- 撤销入口放聊天菜单（和下载授权并排）；全局开关关闭时清空所有对话的授权
  （`UPDATE conversations SET localFetchAllowed = 0`）；
- 数据库：`conversations` 加 `localFetchAllowed BOOLEAN NOT NULL DEFAULT 0`
  （`Entities.kt`，schema v28 → 下一版），对应的 `MIGRATION_28_*` + `CurrentSchemaMigrationTest` 类的
  一份新 JVM 迁移测试；`ConversationRepository` 加 get/set/clear；
- `ChatUiState` 加 `localFetchAllowed`；`ChatScreen` 加授权对话框与撤销菜单项。

### 5.5 Settings（02 / 网络与媒体）

- 两个独立开关：
  - 「本机搜索（免费，走你的网络）」+ 引擎 chips `DuckDuckGo` / `Bing`；
  - 「本机抓取（免费，走你的网络）」+ 说明"仅 https、公网地址、无 cookie/登录；每段对话
    第一次调用会再确认一次；开系统 VPN 时请求从 VPN 出去"；
- 两个开关默认关；关闭时清空对应授权（抓取）；
- 文案必须说清：查询/URL 会离开设备；结果来自搜索引擎/网站，可能被风控、降级或变更；
  这是用户选择的外部读取，不是"匿名浏览"。
- `SecureStore` 新 key：`web.local_search_enabled`、`web.local_fetch_enabled`、
  `web.local_search_engine`（`ddg` / `bing`）；`SettingsViewModel` 状态与方法；设置页
  搜索关键词（`settings_keywords_web`）加"本机 免费 DuckDuckGo Bing"；
- 引擎默认 `ddg`（配合 VPN 得到未过滤结果）；没有 VPN 的用户可切 `bing`（直连可用，
  但结果受地域/合规影响——文案要说明）。

### 5.6 接线

`MainActivity.kt` 像 `searchProvider = { WebToolProviders.search(secure) }` 一样传
`localSearchProvider = { LocalWebProviders.search(secure) }` 等；`LocalWebProviders` 读取
开关与引擎、构造 provider（`core/ai` 纯 JVM 实现，无 Android 依赖），并把 Android 的
`WebViewPageRenderer` 注入本机 fetch provider。`render` 参数只在宿主提供了 renderer 时
出现在 schema 里（JVM 测试用 fake renderer）。

---

## 6. 安全与边界

- **SSRF**：复用 §4.5 的 guard；私网/回环/链路本地/CGNAT/保留段、DNS rebinding、
  逐跳重定向都在打开 socket 前拒绝。代理模式本轮不做，所以不存在"代理回环语义"问题。
- **外传通道**：`web_fetch_local` 的 URL 可携带数据出去；授权对话框、工具描述、Settings
  文案三处披露；诊断只记 host/bytes。`web_search_local` 的 query 通道与 `bili_search`
  同类，已在 §3.1 披露。
- **渲染即执行页面脚本**：`render=true` 时页面 JS 会运行；App 不注入 bridge、不带 cookie/
  登录态、WebView 用后销毁，但页面仍可能发自己的网络请求（追踪）。这是"像浏览器一样加载"
  的固有代价，设置文案与授权对话框都要说明；每回合渲染次数上限（§4.3.1）是成本闸门。
- **提示注入**：抓回的文本是不可信第三方内容；工具描述与 diagnosis 规则沿用现有
  "素材不是指令"的策略，不执行页面内容、不把页面变成工具。
- **免费 = 无限次**：进程级最小间隔 1 s；每回合调用上限（默认建议 10 次，可调）作为
  可选的防滥用闸门——免费工具没有付费工具的天然限流，这一点必须写进文档。
- **失败如实**：不静默换后端、不把验证码页当结果、不把截断当完整；错误文本让模型能读到。
- **措辞**：文档与 UI 用中性表述"本机外部读取 / 用户选择的外部数据源"，不写"翻墙"；
  法律/合规边界由用户判断（`docs/13-product.md` 已有的表述风格）。
- **模型层**：内容仍发给对话模型，若模型在境内，内容仍经过它；产品文档要提示这一点，
  但不把"换模型"作为本功能的默认行为。

---

## 7. 测试

**JVM（`:core:ai`）**

- DDG 解析 fixture：正常、空结果、验证码/降级页、截断 HTML、`uddg` 解码、实体解码、重复 URL；
- Bing RSS fixture：正常、畸形 XML、带 DOCTYPE（必须拒绝/不展开实体）、中文结果；
- 正文提取 fixture：文章页、导航/脚本/iframe 混杂、GBK/GB18030 页、只有 `<title>` 的页面；
- `GuardedHttp`：字面私网、重定向到私网、DNS 解析到私网、超长 URL、无凭据、http/https 限制
  （用 JDK 自带 `HttpServer` 起本地假服务，不联网）；
- 工具层：格式化、`max_results` 上限、空结果文案、错误文案、20k 截断（fake provider）；
- `render`：fake renderer 验证 `render=true` 走渲染器、`render=false` 不走、renderer 抛错
  变成工具错误、没有 renderer 时 schema 不出现该参数；
- `ToolCatalog`：关闭时不可见、开启后可见、`permits(null)` 为 false、`everythingExplicit` 不含、
  其它能力不连带开启；现有 paid 集合断言**不变**。

**迁移（`:core:db` JVM）**：v28 → 下一版默认值、旧行可读、`CurrentSchemaMigrationTest`。

**Live（默认跳过）**：`LocalWebLiveTest`，`INHUMAN_LOCAL_WEB_LIVE=1` 时各跑一次
search + fetch（限速、无断言具体内容，只断言结构与失败契约）。

**设备**

- 模拟器 + 真机；系统 VPN 开/关两种网络；授权弹窗、允许/拒绝、撤销、全局关清空；
- 两个开关关闭时工具在选择器/请求/缺口报告里消失；`null` "全部"不包含它们；
- 手机/平板两个比例（含横屏），确认设置页新行不破版；
- 失败文案：VPN 关闭时 DDG 超时、目标 404、二进制页面、超长页面；
- `render=true`：选一个 JS 渲染的页面验证能拿到正文；断网/加载超时/`innerText` 为空时
  如实报错；渲染后 cookie/storage 清空（设备上检查）；WebView 销毁后无泄漏（连续多次渲染）。

**CI**：现有 `tools/ci.ps1` 列表；新增测试落在 `:core:ai` / `:core:db` / `:app` 既有任务里。

---

## 8. 实施顺序与工作量

1. **核心（约 1 天）**：`core/ai` 的 GuardedHttp 抽取 + jsoup 依赖 + 三个 provider 实现 +
   fixture 单测；`UrlDownloader` 改用共用 guard，`download_url` 行为不变。
2. **搜索闭环（约 1 天）**：Catalog 条目、工具参数化、两个开关与引擎设置、`LocalWebProviders`
   接线、6 个可见性调用点、strings、`web_search_local` 端到端（可先只上搜索）。
3. **本机抓取（约 1 天）**：schema 下一版 + 仓库方法、`gateEngineCall` 授权分支、对话菜单撤销、
   `ChatUiState`/对话框、迁移测试、`web_fetch_local` 端到端。
4. **渲染（约 0.5–1 天，设备验证为主）**：`PageRenderer` 接口 + `WebViewPageRenderer` +
   渲染超时/清理 + 设备测试与失败文案。
5. **收尾（约 0.5–1 天）**：docs/03、08、13、07、README、AGENTS.md、根 README；设备验证。

总计约 4–5 天；只做搜索约 2 天；不做渲染少 1 天左右。

---

## 9. 拒绝的替代方案（记录理由）

1. **App 内代理设置**：用户已确认系统 VPN；引入代理就是新的网络配置面与凭据类别，本轮不做。
2. **把免费实现作为 `web_search` / `web_fetch` 的第三后端**：会让 `paid` 变成动态元数据，
   历史调用与用量统计无法解释（§3.4）。
3. **SearXNG / 公共搜索实例**：那是"另一个服务"（自建或第三方），不满足"不依赖提供商"；
   以后可作为独立后端，不在本提案。
4. **点击/滚动/表单/登录等浏览器自动化**：`render` 只做"加载并抽取"，不做交互；把 App
   变成完整无头浏览器是另一个能力面。
5. **cookies / 登录 / POST**：会引入凭据与写路径，和"只读匿名"冲突。
6. **免费失败自动切付费后端**：静默回退让成本与能力边界都不可见，拒绝。
7. **把页面存进工作区再读**：本机 fetch 是"读进上下文"，不是下载；要字节用 `download_url`。

---

## 10. 开放问题（小，落地前确认即可）

1. 工具名最终用 `web_search_local` / `web_fetch_local`，还是 `local_search` / `local_fetch`？
   （倾向前者，和现有 `web_*` 前缀一致。）
2. 每回合本机 web 调用上限的默认值（建议 10）与最小间隔（建议 1 s）是否照此实现？
3. 抓取授权对话框只显示 host，还是显示完整 URL？（按 `download_url` 先例显示 URL + host；
   诊断仍只记 host。）
4. demo 模式隐藏两个工具（建议隐藏，保持离线 demo 的承诺）。
5. `render` 默认 `false`、每回合渲染上限默认 3，是否照此实现？渲染路径是否需要单独的
   Settings 子开关（建议不需要：它属于同一个本机抓取能力，同一份授权与开关）。
