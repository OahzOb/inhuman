# 桌面端移植评估：Windows

状态：**评估，不含实现**。数字来自当前工作树，不是估算。

## 0. 结论

**可以移植，但不是把 APK 包一层。**

- 技术路线建议 **Compose Multiplatform（Windows/JVM）+ 共享 Kotlin 核心**；
- 现有分层帮了很大的忙：`:core:ai`、`:core:common`、`:core:design` **没有任何 `android.*` import**，`:core:files` 只有 2 处 `StatFs`；
- 真正要重做的是 **`:core:data` 的 Android 适配**（Room/Keystore/DocumentsProvider）和 **`:app` 的 Android glue**（Activity、SAF、通知、前台服务、图片解码）；
- 建议先做 **2 周技术验证（spike）**，通过再投入 MVP。直接开一个长期分支、边编译边改 20k 行 UI，是这场移植里最可能失败的方式。

一个人、熟悉这套代码的前提下：

| 目标 | 估计 |
| --- | --- |
| 2 周 spike | 证明 5 个关键点 |
| 桌面 MVP | 4–6 周 |
| 接近 Android 的功能完整度 | 再加 6–10 周 |
| 总计 | **约 3–4 个月**（不含跨平台/签名/长期维护） |

这不是“小功能”，但也不是“重写一个 app”。

---

## 1. 现有代码的可移植面

当前生产代码（`src/main`，不含测试）：

| 模块 | 文件 | 行数 | Android 直接依赖 | 判断 |
| --- | ---: | ---: | ---: | --- |
| `:app` | 45 | 19,522 | 70 个 `android.*` import | UI/平台 glue，需适配 |
| `:core:ai` | 35 | 5,402 | **0** | 高复用 |
| `:core:data` | 11 | 3,865 | 23 个 | 平台层重写/拆分 |
| `:core:files` | 5 | 1,043 | 2 个（`StatFs`） | 高复用，小改 |
| `:core:design` | 7 | 1,001 | **0** | Compose 组件，需 CMP |
| `:core:common` | 3 | 267 | **0** | 直接复用 |
| **合计** | **106** | **31,100** | 120 个 | |

测试侧还有 67 个文件、约 8,243 行；全量 449 tests / 55 suites。

这个分布说明：**领域层和 UI 层是分开的，Android 绑定集中在 data 和 app 的薄层**。这是移植能成立的前提。

同时要注意：行数不是工作量。UI 代码可以大量复用，但每一处 `R.string`、`LocalContext`、`rememberLauncherForActivityResult` 都要做一次平台决定；这不是改 import。

---

## 2. “移植”的几条路

| 方案 | 复用 | 时间 | 结果 | 评价 |
| --- | --- | --- | --- | --- |
| Android 模拟器 / WSA | 原 APK | 0 | 仍是 Android app | **不推荐**；WSA 已结束支持，文件、通知、性能都不对 |
| 浏览器 UI + 本地服务 | 后端高，UI 低 | 3–5 月 | 跨平台 Web app | 丢掉 20k 行 Compose UI，安全面变成“本机 HTTP 服务” |
| **Compose Multiplatform** | 后端高，UI 中高 | 3–4 月 | 原生窗口桌面 app | **推荐** |
| 新写 Compose Desktop UI + 共享后端 | 后端高，UI 低 | 2–3 月 MVP | 原生窗口，但没有现有 UI | 备选；适合只做核心工作流 |
| WinUI/WPF 原生重写 | 低 | 6+ 月 | 最“Windows” | 不值，双 UI 双维护 |

“在 Windows 上跑 Android app”不算移植：没有原生文件对话框、没有真正的桌面通知、没有窗口管理，也不会得到桌面用户期望的键盘/鼠标操作。

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

### 4.1 Compose 与资源迁移

当前是 Android Compose BOM + AGP；CMP 需要换插件、换 artifact、换资源模型。`core:design` 虽然零 `android.*` import，但 `LocalConfiguration` 仍是 Android Compose API；`app` 里大量 `R.string` 需要机械迁移。

这不是不可做，而是**触碰面广**：728 个 Compose import、597 条字符串。先做一个屏幕（聊天 + 设置）验证成本，再决定是否全量。

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

---

## 5. 建议的落地顺序

### Phase 0：2 周 spike，五个退出条件

1. **Compose Desktop 窗口**能复用 `InhumanTheme`/`Workbench` 的一个真实屏幕，并完成 CMP 资源的最小迁移；
2. **`:core:ai` 在 JVM 上**跑通一次流式回复 + 一个工具循环（OkHttp/sshj 不改协议）；
3. **Room KMP/JVM** 打开当前 schema v13，跑一条真实查询和一个 migration；
4. **DPAPI** 存/取一个 API key，SSH `identityFile` 仍从工作区读取；
5. **Windows 路径逃逸测试**：symlink/junction/大小写/UNC 都不能把 `SessionWorkspace.resolve` 带出工作区。

任何一项失败，都应该在 2 周内停下重新选路线，而不是继续堆 UI。

### Phase 1：4–6 周 MVP

- 聊天、流式、停止、错误恢复；
- provider/模型/密钥、assistant、conversation；
- workspace + 文件工具 + 导入/导出；
- Markdown、代码高亮、图片附件（先支持 JPEG/PNG/WebP）；
- SSH 五个工具 + 每会话授权 + limits；
- Settings、中英文资源、安装包（MSI/EXE，带 JRE）。

### Phase 2：6–10 周接近 parity

- snapshot/rewind、per-conversation tools、上下文压缩；
- 备份/恢复、通知/托盘、Open with/拖放；
- 图片 HEIC 决策、剪贴板/键盘完整化；
- Windows 签名、自动更新、崩溃报告、性能/内存/大 transcript 测试。

---

## 6. 建议

**值得做，但要按“新桌面目标”做，不要按“把 Android 工程改成能编译桌面”做。**

具体建议：

1. 保留 Android app 原样，新建 `:desktop`（Compose Desktop）和共享的 `:core` 提取层；
2. 先把 `:core:common`、`:core:ai`、`:core:files` 变成 JVM/KMP 共享模块，Android 继续依赖它们；
3. 再处理 `:core:data` 的 Room/SecureStore/备份平台实现；
4. UI 用 CMP 逐屏迁移，先聊天和设置，不要一次搬 20k 行；
5. 不添加本地 shell/终端工具；那会把“工作区隔离”变成“用户账户权限”；
6. 不在桌面版默认引入 host 列表/凭据仓库；SSH 的行为保持不变。

如果目标只是“我在 Windows 上偶尔用一下”，**Android 模拟器**更便宜；如果目标是“Windows 成为正式客户端”，上面这条 CMP 路线是合理的，但请先做 spike，不要先承诺 3 个月。
