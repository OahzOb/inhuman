# 桌面端移植评估：Windows / Linux

状态：**评估，不含实现**。数字来自当前工作树，不是估算。

## 0. 结论

**可以移植，但不是把 APK 包一层。**

- 技术路线建议：**一个仓库，共享 JVM core，两个 UI**——Android 保留现有 Compose UI，Windows/Linux 共用一套 Compose Desktop UI。当前三个目标都是 JVM/Android，不需要引入 KMP 的 expect/actual；如果以后要 iOS/macOS 原生，再把 core 升级成 KMP；
- **不把 Android UI 立即迁到 Compose Multiplatform**。CMP 的桌面框架是 Stable，但当前 CMP 1.11.1 的 Material3 仍是 `1.11.0-alpha07`、Lifecycle 仍是 `2.11.0-beta01`；让一个稳定的 Android app 跟着 alpha/beta 组件走，风险大于收益；
- 现有分层帮了很大的忙：`:core:ai`、`:core:common`、`:core:design` **没有任何 `android.*` import**，`:core:files` 只有 2 处 `StatFs`；
- 真正要重做的是 **`:core:data` 的 Android 适配**（Room/Keystore/DocumentsProvider）和 **`:app` 的 Android glue**（Activity、SAF、通知、前台服务、图片解码）；
- 建议先做 **2 周技术验证（spike）**，通过再投入 MVP。直接开一个长期分支、边编译边改 20k 行 UI，是这场移植里最可能失败的方式。

一个人、熟悉这套代码的前提下：

| 目标 | 估计 |
| --- | --- |
| 2 周 spike | 证明 6 个关键点（含 Linux） |
| 桌面 MVP | 4–6 周 |
| 接近 Android 的功能完整度 | 再加 6–10 周 |
| 总计 | **约 3–4 个月**（不含签名/长期维护） |

这不是“小功能”，但也不是“重写一个 app”。

### Spike 进度（当前工作树）

已经落地并验证：

- `:core:common`、`:core:ai`、`:core:files` 已从 Android library 改成 **plain JVM**，并新增共享的 `:core:db`；Android app 继续依赖同一批 jar，全量 Android 测试仍通过，模拟器上也能启动；
- `:desktopApp` 已是可运行的 Compose Desktop 窗口，使用 `core:engine` 的共享回合循环和 `core:ai` 的 `DemoProvider` 流式生成回复，并依赖共享的 `SessionWorkspace`；
- `:core:engine` 已抽出共享 `ChatEngine`：流式收集、tool call 组装、approval hook、工具执行、assistant/tool 消息和 round/call 预算；桌面端使用它，Android 的 **demo 路径**已通过桥接接入真实 transcript/UI，真机和模拟器验证通过；真实 provider 的**所有回合（含 SSH）**也已切到 engine，SSH consent、审批、start checkpoint 和参数归一化由 `ChatViewModel.gateEngineCall` 通过 hook 注入，旧的 `runTurnLegacy` 已删除；DeepSeek live test 通过；
- Room 的 `Entities.kt`/`InhumanDatabase.kt` 已移到 `:core:db`；`core:db` 在 JVM 上打开 v13，并用导出的 `12.json` 构建 v12 数据库、跑完 `MIGRATION_12_13`。Android app 使用同一份生成的数据库实现，模拟器启动无 Room 错误；
- `ConversationRepository`、`AgentRepository`、`PolicyRepository`、`SnapshotStore` 已从 `:core:data` 移到 `:core:db`，改用注入的 `DataPaths` 和 `SecretStore` 接口，不再依赖 `Context`；`:core:data` 只剩 Android 适配（Keystore、`Database.open`、备份、DocumentsProvider）。`ConversationRepositoryTest` 在 JVM 上创建/删除会话、写消息、跑回滚事务；
- `:desktopApp` 已用真实 v13 文件库替代 spike 的内存列表：同一个 `InhumanDatabase`、同一份 `MIGRATIONS` 与 onCreate 回调（会种下默认 assistant/provider）、同一个 `ConversationRepository`、同一套 `files/conversations/<id>` 布局。关掉窗口重开，消息还在；`RepositoryOnDesktopTest` 用文件库验证写入、重开和 provider 密钥槽；
- 桌面窗口现在有会话栏：列出 `ConversationRepository.all()`、新建会话、切换时重新加载 transcript、按首条消息自动命名；`DesktopStoreTest` 覆盖列表/切换/重开，窗口内也手工验证了新建、切换和发送后落库；
- 真实的 `write_file`/`read_file` 工具已在桌面 JVM 上执行并通过测试；这证明共享 sandbox，但还不是完整的 `ChatEngine` 工具循环；
- Windows DPAPI 已经通过 JNA 的 `Crypt32Util` 验证加解密往返；Linux 明确不做“假加密”回退，Secret Service 绑定仍是待办；
- Windows **junction 逃逸**被 spike 的测试抓到并修复：`File.canonicalFile` 不解析 junction，`Files.isSymbolicLink` 也不识别它，现在对已存在组件使用 `toPath().toRealPath()`；
- `:desktopApp:createDistributable` 已产出 Windows app 目录，打包后的 exe 能启动；Msi/Deb/Rpm/AppImage 目标已声明，Linux 包尚未在 Linux 上构建。

仍未完成（下一步，不要误认为 spike 已完成）：

- 桌面端仍缺 assistant/设置/工作区/文件屏、Markdown 渲染、工具调用与审批、SSH consent、snapshot/compaction、通知/文件对话框/图片适配。数据层与 conversation rail 已经就绪；
- Linux Secret Service（libsecret）绑定，以及 Linux 包（AppImage/deb）在 Linux 上的启动验证；
- 桌面端的完整安全/通知/文件对话框/图片适配。

当前全量测试为 **482 tests / 67 suites / 0 failures / 3 skipped**。

### 为什么不是“所有 UI 也共用一套”

成熟产品里两种路线都存在，而且都能做到很大规模：

- **共享逻辑、UI 原生**：Doist/Todoist 共享 core logic；Workday 强调“share code where it makes sense, without sacrificing native experience”；Philips 把业务逻辑集中共享；Down Dog 采用“maximum shared Kotlin”，但保留 native-only views。
- **共享 UI**：Feres 共享 90% UI，Markaz 100+ 屏全部 CMP，Respawn 96% 共享，Fast&Fit 90% 含 UI，Wrike、Sony、BiliBili 也有 CMP 生产案例；JetBrains 官方的 KotlinConf app 在 Android/iOS/desktop/web 共享 UI。

没有“成熟 app 都选 A”这回事。选择取决于 **UI 相似度、平台团队、组件库成熟度**，不是框架热度。

对这个 app 来说，聊天/工作区结构在 Android 和桌面相似，但交互不同（触摸 vs 键盘/右键/拖放），而且 Android 现在依赖稳定的 Material3。**先共享引擎、后共享 UI** 是更稳的顺序；等 CMP Material3/Lifecycle 稳定、桌面 MVP 证明价值，再把设计系统和部分屏幕收进 commonMain。

> 参考：[Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/)、[KMP case studies](https://kotlinlang.org/case-studies/?type=multiplatform)、[Android → KMP migration](https://kotlinlang.org/docs/multiplatform/migrate-from-android.html)、[KMP stability](https://www.jetbrains.com/help/kotlin-multiplatform-dev/llms.txt/)。

---

## 1. 现有代码的可移植面

当前生产代码（`src/main`，不含测试）：

| 模块 | 文件 | 行数 | Android 直接依赖 | 判断 |
| --- | ---: | ---: | ---: | --- |
| `:app` | 45 | 20,175 | 61 个 `android.*` import | UI/平台 glue，需适配 |
| `:core:ai` | 36 | 5,924 | **0** | 已是普通 JVM，高复用 |
| `:core:data` | 4 | 1,255 | 15 个 | Android 平台适配层（Keystore、`Database.open`、备份、DocumentsProvider） |
| `:core:db` | 9 | 3,084 | **0** | schema/DAO/迁移 + 平台无关 repositories，普通 JVM，共享 |
| `:core:engine` | 1 | 400 | **0** | 共享回合循环，普通 JVM |
| `:core:files` | 5 | 1,153 | **0** | 已是普通 JVM，高复用 |
| `:core:design` | 7 | 1,072 | **0** | Compose 组件，需 CMP |
| `:core:common` | 3 | 303 | **0** | 已是普通 JVM，直接复用 |
| **合计** | **110** | **33,366** | 76 个 | |

测试侧还有 67 个文件、约 8,243 行；当前全量 482 tests / 67 suites。

这个分布说明：**领域层和 UI 层是分开的，Android 绑定集中在 data 和 app 的薄层**。这是移植能成立的前提。

同时要注意：行数不是工作量。UI 代码可以大量复用，但每一处 `R.string`、`LocalContext`、`rememberLauncherForActivityResult` 都要做一次平台决定；这不是改 import。

---

## 2. “移植”的几条路

| 方案 | 复用 | 时间 | 结果 | 评价 |
| --- | --- | --- | --- | --- |
| Android 模拟器 / WSA | 原 APK | 0 | 仍是 Android app | **不推荐**；WSA 已结束支持，文件、通知、性能都不对 |
| 浏览器 UI + 本地服务 | 后端高，UI 低 | 3–5 月 | 跨平台 Web app | 丢掉 20k 行 Compose UI，安全面变成“本机 HTTP 服务” |
| **共享 JVM core + 两个 UI**（Android Compose / 桌面 CMP） | 后端高，UI 中 | 3–4 月 | 原生窗口，Android UI 不动 | **推荐**；Windows/Linux 共用桌面 UI |
| CMP 共享 core + 共享 UI | 高 | 4–6 月 | 一套屏幕代码 | 长期目标；当前 Material3 alpha/Lifecycle beta，Android 回归风险 |
| WinUI/WPF 原生重写 | 低 | 6+ 月 | 最“Windows” | 不值，双 UI 双维护 |

“在 Windows 上跑 Android app”不算移植：没有原生文件对话框、没有真正的桌面通知、没有窗口管理，也不会得到桌面用户期望的键盘/鼠标操作。

Windows 和 Linux **共用同一个桌面 UI**，所以“两个 UI”是 Android 一个、桌面一个，不是三个。这是拆 UI 仍然可控的关键。

---

## 3. 复用地图

### 3.1 可以直接复用（约 6.7k 行）

- `:core:common`：日志、诊断、通用工具；
- `:core:ai`：provider 接口、OpenAI 兼容适配、SSE、工具协议、SSH/sshj、web 工具；
- `:core:files`：`SessionWorkspace`、路径约束、配额、文件工具、导入导出核心逻辑（把 `StatFs` 换成 `FileStore`/注入的 free-space 函数）。

`:core:ai` 是纯 JVM（OkHttp、sshj、BouncyCastle），只是当前模块声明为 Android library；改成 JVM/KMP 不需要改协议代码。`kotlinx-coroutines-android` 换成 `-core`，Android 端仍可提供 `Dispatchers.Main`。

### 3.2 需要平台抽象（约 20k 行 UI/design）

- 当前 597 条字符串、22 个 plurals，`R.string` 遍布 UI；CMP 要迁到 `composeResources` 或一个生成的 `Strings` 层；
- `LocalConfiguration`（窗口宽高）、`LocalContext`（剪贴板/资源/文件选择）、`rememberLauncherForActivityResult`（文件导入、图片选择、通知权限）、`BackHandler`（返回键）、`collectAsStateWithLifecycle`；
- `Material3`、`material-icons-extended`、Markdown renderer 需要切到 CMP 对应 artifact；Markdown 库本身已是 multiplatform，这是有利的；
- 触摸式交互（长按、滑动、FAB、overflow）需要桌面化：键盘快捷键、右键菜单、hover、拖放、窗口尺寸。

UI 不是“原样搬过去”：现有 tablet 双栏布局是一个很好的起点，但桌面端必须重新审视窗口最小尺寸、焦点、Esc/Enter、复制粘贴和多显示器。

### 3.3 需要重写（约 4k 行平台层）

- **Room**：当前 `Room.databaseBuilder(context, ...)` + Android SQLite；桌面要用 Room KMP/JVM 或等价 JDBC 层，迁移和 schema 测试要重建。Room 2.8 已有 KMP 能力，但这是 spike 要验证的第一件事，不是假设。
- **SecureStore**：Android Keystore → Windows DPAPI（`CryptProtectData`，通常经 JNA）或 Credential Manager。没有 TEE 等价物，不能说“和 Android 一样安全”；只能保证 at-rest 加密。
- **DocumentsProvider/SAF**：桌面没有 SAF；换成文件对话框、拖放、`java.nio.file`。工作区仍然必须是 app 私有目录，导入仍是复制，不是引用。
- **备份/恢复**：当前依赖 Android `SQLiteDatabase` + `Uri`；桌面用 JDBC/zip + 文件路径重写。
- **通知/前台服务**：`TurnForegroundService` 在桌面没有对应物；变成窗口/托盘通知、关闭窗口后的后台策略、单实例锁。
- **图片**：Android `ImageDecoder`/EXIF 预处理 → `ImageIO` + EXIF 库；HEIC 在标准 Java 上没有解码器，要么加插件，要么明确不支持。
- **剪贴板、Toast、Intent/Open with…**：AWT/系统集成重写。

---

## 4. 最难的几件事

### 4.1 Compose 与资源

桌面 UI 用 Compose Multiplatform，Android UI 继续用 Android Compose。CMP 需要自己的插件/artifact/资源模型；`core:design` 虽然零 `android.*` import，但 `LocalConfiguration` 仍是 Android Compose API，必须先抽掉。`app` 里 597 条字符串不必迁 Android 的 `R.string`，但桌面要生成一份 `composeResources`，两边的 key/占位符一致性要有测试。

这不是不可做，而是**触碰面广**：728 个 Compose import 里，只有将来决定共享 UI 时才会碰到 Android 部分。先用桌面聊天 + 设置两屏验证成本，再决定是否把 Android UI 也迁到 commonMain。

### 4.2 Room / 数据层

Android Room 的 `Context`、`RoomDatabase.Callback`、schema assets、instrumentation migration tests 都要改。推荐保留 schema 和 DAO 形状，换 builder/driver；不要换成“自己写一套 SQL”作为第一选择，迁移风险更大。

### 4.3 Windows 文件路径

`SessionWorkspace.resolve()` 的 canonical-prefix 检查在 Android 上已经是最重要的边界。Windows 必须额外测试：

- 大小写不敏感、短路径（8.3）、UNC、驱动器相对路径；
- NTFS junction / symlink / reparse point；
- 交替数据流（`file.txt:stream`）、保留名（`CON`、`NUL`）、长路径；
- 检查与使用之间的 TOCTOU。

如果这些没有测试，桌面版的“工作区隔离”只是 Android 版本的假设，不是事实。

### 4.4 安全边界

Android 有 app sandbox + scoped storage；Windows 进程以当前用户权限运行。桌面版仍然可以保持：

- 工具只接受工作区相对路径；
- 导入是复制，导出由用户选择；
- 没有本地 shell 工具，只有远端 SSH；
- SSH 仍不建立 host/credential 仓库。

但**不能声称**和 Android 一样安全：一个 app 进程的 bug 在 Windows 上可以读到用户能读的文件。若需要更强隔离，得另做 Windows Sandbox/AppContainer/受限账户，那是独立工程，不是这次移植的默认项。

### 4.5 桌面 UX

当前 UI 是“手机优先 + tablet 双栏”，不是桌面优先。至少要补：

- 键盘快捷键（发送、停止、搜索、切对话、Esc 关闭对话框）；
- 右键菜单、hover 状态；
- 文件拖放；
- 窗口最小尺寸和 DPI/多显示器；
- 关闭窗口时正在跑的回合怎么办（继续/取消/托盘）；
- 通知点击回到对应会话。

### 4.6 Linux 不是“Windows 加个包”

- **先做 Linux x64**；ARM64（树莓派等）要单独验证 Compose/Skiko 和打包，不要默认支持。
- **密钥存储**：没有 DPAPI；走 Secret Service（libsecret / D-Bus）。没有 keyring 时要有明确降级策略，不能静默写明文。
- **通知/托盘**：D-Bus `org.freedesktop.Notifications`；GNOME 托盘可能需要扩展，窗口内提示必须是 fallback。
- **文件对话框**：AWT/GTK，X11 和 Wayland/XWayland 都要测。
- **路径**：XDG 目录、大小写敏感、符号链接、权限；与 Windows 的 junction/UNC 测试分开。
- **打包**：AppImage + deb/rpm，自带 JRE；Flatpak 是额外沙箱选项，不是默认。
- **输入法/字体**：Linux 桌面必须实际验证中文输入和字体 fallback。

---

## 5. 建议的落地顺序

### Phase 0：2 周 spike，六个退出条件

1. **Compose Desktop 窗口**能复用 `InhumanTheme`/`Workbench` 的一个真实屏幕，并完成桌面 UI 资源的最小迁移；**不改 Android UI**；
2. **`:core:ai` 在 JVM 上**跑通一次流式回复 + 一个工具循环（OkHttp/sshj 不改协议）；
3. **Room KMP/JVM** 打开当前 schema v13，跑一条真实查询和一个 migration；
4. **DPAPI 和 libsecret** 各存/取一个 API key，SSH `identityFile` 仍从工作区读取；
5. **Windows 路径逃逸测试**：symlink/junction/大小写/UNC 都不能把 `SessionWorkspace.resolve` 带出工作区；
6. **Linux 包能启动**：AppImage（或 deb）在干净环境启动，中文字体/输入法至少手工验证一次。

任何一项失败，都应该在 2 周内停下重新选路线，而不是继续堆 UI。

### Phase 1：4–6 周 MVP

- 聊天、流式、停止、错误恢复；
- provider/模型/密钥、assistant、conversation；
- workspace + 文件工具 + 导入/导出；
- Markdown、代码高亮、图片附件（先支持 JPEG/PNG/WebP）；
- 七个 SSH 工具 + 每会话授权 + limits；
- Settings、中英文资源、安装包（MSI/EXE、AppImage/deb/rpm，带 JRE）。

### Phase 2：6–10 周接近 parity

- snapshot/rewind、per-conversation tools、上下文压缩；
- 备份/恢复、通知/托盘、Open with/拖放；
- 图片 HEIC 决策、剪贴板/键盘完整化；
- Windows 签名、Linux 包签名/仓库、崩溃报告、性能/内存/大 transcript 测试。

---

## 6. 建议

**值得做，但要按“新桌面目标”做，不要按“把 Android 工程改成能编译桌面”做。**

具体建议：

1. **一个仓库**，不要拆两个 repo；保留 Android app 的 Compose UI；
2. 新建 `:desktopApp`，Windows/Linux 共用一套 **Compose Desktop UI**；
3. 先把 `:core:common`、`:core:ai`、`:core:files` 变成 plain JVM 共享模块，Android 继续依赖它们；如果以后要 iOS/macOS 原生，再升级 KMP；
4. 再处理 `:core:data` 的 Room/SecureStore/备份平台实现，以及 `:core:app` 的对话/工具/授权引擎；
5. **不要现在把 Android UI 迁到 CMP**；等 CMP Material3/Lifecycle 稳定、桌面 MVP 证明值得，再按屏幕迁移；
6. 不添加本地 shell/终端工具；那会把“工作区隔离”变成“用户账户权限”；
7. 不在桌面版默认引入 host 列表/凭据仓库；SSH 的行为保持不变。

如果目标只是“我在 Windows 上偶尔用一下”，**Android 模拟器**更便宜；如果目标是“Windows 和 Linux 成为正式客户端”，先做 spike，再按上面的顺序推进，不要先承诺一套共享 UI。

**为什么这次不直接上 KMP**：Android、Windows、Linux 三个目标都是 JVM/Android，共享模块里的 `java.*`、OkHttp、sshj 都能直接用；KMP 会引入 `expect/actual` 和 source-set 成本，收益要到 iOS/macOS 原生目标才出现。这不是放弃一套代码——仍然是同一个仓库、同一份 core；只是共享机制用 JVM 而不是 KMP。`docs/12` 的测试数/进度见下。
