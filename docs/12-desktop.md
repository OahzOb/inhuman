# 12 — Windows x64 桌面端（当前实现）

状态：**已实现并通过最终验收**。目标平台是 Windows x64；Linux 明确不在本阶段范围内。
本文描述当前代码和运维方式；最早的移植可行性评估保存在
[archive/](archive/README.md)。

## 1. 范围与结论

同一个仓库、同一套 JVM core、两个 UI：

- `:app` 保留现有 Android Compose UI；
- `:desktopApp` 是 Windows 桌面宿主，使用 Compose Desktop；
- `:core:ai`、`:core:common`、`:core:db`、`:core:engine`、`:core:files` 由两端共享。

桌面端不是 APK 的包装层：它直接打开同一个 Room v19 数据库和同一套 workspace 布局，
数据库迁移、assistant/provider 种子、工具目录、turn loop、SSH 传输和备份格式都来自
共享模块。Android 仍然是手机上的主产品；桌面端覆盖同一套能力，并提供键盘、窗口、
托盘和原生文件选择器。

Linux 没有构建、没有验证。桌面代码本身尽量不依赖 Windows，但 DPAPI、托盘、原生
文件对话框和 MSI 是 Windows 优先实现。

## 2. 代码位置

| 文件 | 负责 |
| --- | --- |
| `desktopApp/src/main/kotlin/com/inhuman/desktop/Main.kt` | 窗口、会话栏、transcript、composer、设置/Assistants/Files/Rewind/Approval/SSH consent 窗口 |
| `desktopApp/.../DesktopStore.kt` | 打开共享数据库、会话/消息/assistant/provider、模型解析、SSH 设置、compaction、rewind、附件导入 |
| `desktopApp/.../DesktopChat.kt` | 桌面侧 turn 组装：工具注册、approval/SSH consent 注入、历史重建、tool result 持久化 |
| `desktopApp/.../DesktopBackup.kt` | `INHUMAN_BACKUP_1` 加密导出、校验、staging restore |
| `desktopApp/.../DesktopDiagnostics.kt` | `Diag` 日志、5 MB 轮转、脱敏 |
| `desktopApp/.../DesktopLanguage.kt` | `EN / 中文`、CompositionLocal、状态/错误翻译 |
| `desktopApp/.../DesktopAttachment.kt` | Android 兼容的 `attachmentsJson` 编解码 |
| `desktopApp/.../DesktopImages.kt` | ImageIO 降采样、白底 JPEG、读上限约束 |
| `desktopApp/.../DesktopTray.kt` | 托盘 Open/Exit 和 “Reply ready” 通知 |
| `desktopApp/.../SingleInstance.kt` | 每个数据目录的 `instance.lock` |
| `desktopApp/.../secret/DpapiSecretStore.kt` | 整文件 DPAPI 加密的 `secrets.bin` |
| `desktopApp/build.gradle` | Compose Desktop、jpackage、MSI、图标、单实例和 per-user 配置 |

## 3. 能力清单

| 区域 | 当前行为 |
| --- | --- |
| 会话 | 列表、按标题/消息搜索、固定、重命名、删除；删除临时 workspace |
| Assistant | 新建/编辑/删除（默认 assistant 不可删）、system prompt、provider、model、thinking、工具选择 |
| Provider | 列表、DPAPI API key、`/models` 拉取、按会话/assistant 解析模型 |
| 聊天 | 流式文本、reasoning、Markdown、Stop、工具调用行、tool result 卡、错误行 |
| 工具 | 共享 `ToolCatalog` 和 `FileTools`；`read_time`；web/SSH 工具按目录即可用范围出现 |
| 审批 | 工具级 `Free / Ask / Deny` 规则；Ask 弹窗；拒绝写 `[declined]` 并停止 |
| SSH | 默认关闭；全局开关、四项 `SshLimits`、assistant/conversation 显式选择、每会话 consent；七个工具 |
| Rewind | 多轮 picker；恢复到任意 user 回合前的 `start` checkpoint；删除该轮及后续；文本/图片回 composer；不可逆远程 suffix 拒绝 |
| Compaction | token gauge；保留最近 2 个 user turn；summary 写入 `context_summaries`；Clear 恢复完整投影 |
| 文件 | 浏览工作区、导入、导出、编辑保存（512 KB / 二进制保护）、用系统程序打开 |
| 永久工作区 | 列表、新建、重命名、仅无对话时删除、在资源管理器中打开真实目录；与 Android 共用数据库和目录布局 |
| 图片 | `Ctrl+O` / `ATTACH` / 拖放；最多 4 张；ImageIO 重编码；缩略图；请求侧 `ChatImage`；rewind 返回附件 |
| 备份 | 加密导出数据库 + 全部 workspace；密钥刻意不含；staging restore 在 Room 打开前应用 |
| Diagnostics | `inhuman-data/logs/inhuman.log`；打开目录、清空；不记 key/prompt/文件内容 |
| 桌面集成 | 中英文、托盘通知、快捷键、单实例、per-user MSI、内置 Java runtime |

## 4. 安全不变量

这些是桌面实现必须保持的边界，和 Android 共享同一套模型：

- **workspace 是唯一的文件面**：路径只能经 `SessionWorkspace.resolve()` 变成 `File`；
  Windows junction 也要用 `toRealPath()` 校验，`canonicalFile` 不够。
- **审批默认不打断**：默认 `Free`；`Ask` 只在配置后出现；拒绝不会执行工具。
- **SSH 是唯一离开设备的能力**：默认关；`toolsJson = null` 不等于 SSH 同意；关闭全局
  开关清空所有会话 grant；增加 `ssh_exec`/`ssh_upload`/`ssh_write_file` 重置 grant；
  consent 只显示 host/user/operation 和凭据脱敏后的摘要。
- **不可逆远程调用不能 rewind**：suffix 含 `ssh_exec`、`ssh_upload` 或
  `ssh_write_file` 时拒绝恢复，因为本地 snapshot 无法撤销另一台机器。
- **API key 只进 DPAPI**：`secrets.bin` 整文件加密；备份包不包含它。
- **backup restore 在 Room 打开前完成**：staging + marker；失败回滚旧文件。
- **diagnostics 永不记录 key、prompt 或文件内容**，且日志失败不影响 turn。

## 5. 构建与安装

环境（本机路径示例）：

```powershell
$env:JAVA_HOME = 'C:\android-toolchain\jdk17'
$env:INHUMAN_DESKTOP_JAVA_HOME = 'C:\android-toolchain\jdk21'  # 必须带 jpackage
$env:WIX_PATH = 'C:\android-toolchain\wix314'                  # WiX 3
```

运行与测试：

```powershell
.\gradlew.bat :desktopApp:run
.\gradlew.bat :desktopApp:test --console=plain --rerun-tasks
```

全量验证：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest `
  :core:data:testDebugUnitTest :core:db:test :core:engine:test `
  :core:ai:test :core:files:test :core:common:test `
  :desktopApp:test --console=plain --rerun-tasks
```

打包、签名、安装：

```powershell
.\gradlew.bat :desktopApp:packageMsi
tools\sign-msi.ps1 `
  -Msi desktopApp\build\compose\binaries\main\msi\inhuman-0.2.0.msi `
  -Pfx .signing\inhuman-dev.pfx -Password inhuman-dev-signing

msiexec /i desktopApp\build\compose\binaries\main\msi\inhuman-0.2.0.msi
```

安装位置与数据位置**分离**：

```
%LOCALAPPDATA%\inhuman\        exe、runtime、app jars
%LOCALAPPDATA%\inhuman-data\  inhuman.db、files/、secrets.bin、logs/、instance.lock
```

MSI 是 per-user 的，自带 Java runtime。2026-10 验收时安装出的
`runtime/release` 为 `JAVA_VERSION="21.0.12.1"`，`runtime/bin/server/jvm.dll` 存在；
应用不依赖机器上另装的 Java。

## 6. 验证记录

最终全量测试：

```
743 tests / 110 suites / 0 failures / 4 skipped
warnings 0, errors 0
```

其中桌面端 `14 suites / 39 tests`。4 个 skip 都是需要外部凭据的 live 测试：

- `DeepSeekWebLiveTest`：真实付费 Search/Fetch；
- `EngineLiveTest`：真实 chat completion；
- `SshLiveProbeTest`：真实 SSH 主机；
- `QwenTokenPlanLiveTest`：真实 Qwen token 套餐接口。

验收过的路径：

- 开发构建：会话搜索/固定/重命名/删除；真实 provider + DPAPI key；工具循环与审批；
  SSH consent（fake transport）；compaction（Demo 三轮后压缩）；图片附件（选择、缩略图、
  发送落库）；多轮 rewind（测试）；备份/恢复（测试）；诊断日志。
- 安装构建：MSI 安装、启动、内置 runtime、数据目录分离、第二次启动被单实例锁挡住。
- 没有可用的真实 SSH 主机，因此桌面 SSH 没有 live 结果；`SshClient` 本身由
  `:core:ai` 的 MINA 测试覆盖。

## 7. 分发限制

MSI 已用 `.signing/inhuman-dev.pfx` 自签名。该证书是开发证书，根不在 Trusted Root，
所以本机 `Get-AuthenticodeSignature` 报 `UnknownError`；这不影响安装和启动，但其他
用户会看到未知发布者。正式分发必须替换为真实代码签名证书，并建议加时间戳：

```powershell
tools\sign-msi.ps1 -Msi <msi> -Pfx <real.pfx> -Password <password> -TimestampUrl <url>
```

桌面端当前版本是 `0.2.0`（Android 端为 `0.7.2`；两端版本号独立）。`upgradeUuid` 已配置；后续发版需要提升版本号才能覆盖升级。

## 8. 已知限制

- 没有真实 SSH live 验证环境；
- 少量 provider/DB 原始错误和工具描述仍以英文显示，主 UI 已双语；
- 正式分发缺少真实代码签名证书；
- Linux 未构建、未验证；
- 桌面端有永久 workspace 管理窗口，但没有 Android `DocumentsProvider` 那样的
  content-URI 文件面；它通过资源管理器打开 workspace 的真实目录，Android 仍是
  文件集成的主产品。
