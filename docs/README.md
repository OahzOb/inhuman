# inhuman — the docs set

These files exist so that a later session — human or agent — can work on this
project **without reading the source first**. Most state what the code does and,
where useful, cite the file that decides the behaviour; `13-product.md` is the
deliberate exception, written at the product level with no code references.

There are now **two hosts** in one repository:

- the Android app (`:app`), which is the original product;
- the Windows x64 desktop app (`:desktopApp`), which shares the JVM core.

`01`–`11` describe Android/shared behaviour. `12` is the current Windows desktop
document. `13` is the technology-neutral product document: principles, current
features and future plans, with no stack or implementation detail. The old
feasibility studies are in [`archive/`](archive/README.md) and are historical,
not current documentation.

They are not a replacement for the root `README.md`, and they are not a
tutorial. The root README explains *what the app is* and what it deliberately is
not; `AGENTS.md` states the non-negotiable working rules. These files explain
*how it is built*.

## Read in this order

| File | Answers |
| --- | --- |
| [01-overview.md](01-overview.md) | What this is, which module holds what, what the app does today, what is deliberately missing |
| [02-architecture.md](02-architecture.md) | Module graph, dependency rule, start-up wiring, navigation, build toolchain, build/verify commands |
| [03-ai-provider.md](03-ai-provider.md) | Provider interface and stream types, provider profiles, OpenAI-compatible adapter, SSE, demo mode, paid web tools, SSH tools and limits |
| [04-data-persistence.md](04-data-persistence.md) | Room schema v14 and exported versions, repositories, ordering, key storage, `DocumentsProvider`, orphan sweeps |
| [05-files-sandbox.md](05-files-sandbox.md) | `SessionWorkspace`, path confinement, quotas, the file tools, import/export |
| [06-agent-loop-ui.md](06-agent-loop-ui.md) | Turn lifecycle, tool execution, approval gate, cancellation, context gauge, Compose layout |
| [07-gotchas.md](07-gotchas.md) | Load-bearing invariants, and mistakes that already cost someone a day |
| [08-port-philosophy.md](08-port-philosophy.md) | Why the port exists, the constraints that rule out a “full access” mode, design questions, paid-disclosure and SSH exceptions |
| [09-approvals.md](09-approvals.md) | Approval model: no prompt by default, how per-tool/per-assistant/per-path rules compose, built status |
| [10-diagnostics.md](10-diagnostics.md) | Flight recorder: what is logged, what is left out, where it lives, how to share it |
| [11-conversation-tools.md](11-conversation-tools.md) | Conversation-level tool sheet: assistant ceiling, next-turn timing, why it is not a new SSH exception |
| [12-desktop.md](12-desktop.md) | **Windows x64 host**: architecture, feature map, security invariants, build/package/sign commands, verification and known limitations |
| [13-product.md](13-product.md) | **Product document, deliberately technology-neutral**: design principles, current features and future plans |

`07-gotchas.md` is the one to read when you are about to change something and do
not have time to read the rest. Everything in it was a real bug.

`08-port-philosophy.md` is the one to read before proposing anything that adds a
setting, a mode, or a permission — it is where the project says which options it
refuses to have.

`12-desktop.md` is the one to read before changing or packaging the Windows host.
`13-product.md` is the one to read before a product, platform or stack decision —
it states what the product promises without naming a technology.

## Historical archive

These were written before the desktop implementation and are kept for their
measurements, rejected options and phase estimates:

| File | What it is |
| --- | --- |
| [archive/port-assessment.md](archive/port-assessment.md) | Feasibility study with device measurements |
| [archive/port-design.md](archive/port-design.md) | Original layering/event/phasing design |
| [archive/README.md](archive/README.md) | Why they are archived, and where to go instead |

Do not use them to answer “what does the desktop code do today”. That answer is
`12-desktop.md`.

## Conventions used here

- **Citations** are `relative/path.kt:LINE` from the repository root, and point
  at the code that decides the behaviour — not at a doc comment that describes
  it. Where a comment and the code disagree, the doc says so explicitly, because
  in this tree the comment is treated as the bug.
- **“Verified”** means the claim was checked against the source file at the
  cited line. Nothing here is inferred from a name, a test title, or an earlier
  doc.
- **Line numbers are the weakest part of every citation.** When a citation
  points at a file it is reliable; when it points at a line, read it as “near
  here, as of the last pass”. Where a number would be fragile, the docs name a
  searchable marker instead (`MIGRATION_n_m`, `REFUSED_MARKER`,
  `DECLINED_MARKER`, `ToolPolicy.Ask`, `demoEnabled()`).
- Wording follows the repository's house style: say *why*, and name the failure
  that the code prevents when there is one.

## The state of the test suite

Measured by running it, not by counting files:

```powershell
$env:JAVA_HOME = 'C:\android-toolchain\jdk17'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest `
  :core:data:testDebugUnitTest :core:db:test :core:engine:test `
  :core:ai:test :core:files:test :core:common:test `
  :desktopApp:test --console=plain --rerun-tasks
```

Latest run (2026-10-25):

```
BUILD SUCCESSFUL, 549 tests in 83 suites, 0 failures, 4 skipped
warnings 0, errors 0
```

The skips are external-credential live tests: `DeepSeekWebLiveTest`,
`EngineLiveTest`, `SshLiveProbeTest` and `QwenTokenPlanLiveTest`. Suite by suite:

| Module | Suites | Tests |
| --- | --- | --- |
| `:core:ai` | 36 | 271 (3 skipped) |
| `:core:files` | 5 | 58 |
| `:app` | 18 | 130 |
| `:core:common` | 2 | 16 |
| `:core:db` | 4 | 10 |
| `:core:engine` | 2 | 10 (1 skipped) |
| `:core:data` | 3 | 17 |
| `:desktopApp` | 13 | 37 |

The total grows with every fix that lands with a test; treat it as a baseline to
re-measure rather than a fact to quote. The command above is the authority.

### Device-only suites

These are not in the JVM total because they need an emulator or a phone:

| Suite | Why it needs a device |
| --- | --- |
| `app/src/androidTest/…/TurnNotificationsTest` | Notification channels and service behaviour |
| `app/src/androidTest/…/bench/SessionLogReadBenchmark` | Session-log read time on ARM64 |
| `app/src/androidTest/…/bench/JavaScriptSandboxProbe` | Whether the platform JavaScript sandbox exists and what it withholds |
| `app/src/androidTest/…/web/WebToolProvidersTest` | Web keys are independent of the chat provider |
| `app/src/androidTest/…/web/RealProviderProbeTest` | One real `GET /models` when a key is stored; no-op otherwise |
| `app/src/androidTest/…/EngineAndroidLiveTest` | One real engine completion; skips without a key |
| `app/src/androidTest/…/ui/chat/ImagePreprocessorTest` | Android `Bitmap` decode/downscale/WebP pipeline |
| `core/data/src/androidTest/…/db/MigrationTest` | Room migrations against real SQLite |
| `core/data/src/androidTest/…/WorkspaceLifecycleTest` | Temporary/permanent workspace lifetime and promotion |
| `core/data/src/androidTest/…/db/ConversationLookupTest` | `id` / `workspaceId` lookup distinction |
| `core/data/src/androidTest/…/provider/WorkspaceDocumentsProviderTest` | DocumentsProvider folder ids and refusal rules |
| `core/data/src/androidTest/…/ProviderProfileTest` | Conversation provider copy and key-slot rules |
| `core/data/src/androidTest/…/ConversationSearchTest` | Message/title search, ordering, literal `%` |

Run them with `:app:connectedDebugAndroidTest` /
`:core:data:connectedDebugAndroidTest`, or `adb shell am instrument`. Counts
depend on which opt-in live tests are enabled. The desktop counterpart runs on
the JVM; see [12-desktop.md](12-desktop.md).

## Which files own which behaviour

The shortest path from a symptom to the code that decides it. Paths are from the
repository root; `…/` stands for `src/main/kotlin/com/inhuman/`.

| Symptom, or thing you want to change | Go to |
| --- | --- |
| Work out a rough plan at all | [`01-overview.md`](01-overview.md), then [`07-gotchas.md`](07-gotchas.md) |
| Startup, dependency wiring, navigation, Back, ViewModel keys | `app/src/main/kotlin/com/inhuman/MainActivity.kt` |
| Streaming, tool loop, approval, cancellation, regenerate, context gauge, compaction | `app/…/ui/chat/ChatViewModel.kt` |
| Visible chat layout, bubbles, composer, tool rows, dialogs | `app/…/ui/chat/ChatScreen.kt` |
| Incremental Markdown, delta channel, unclosed fences | `app/…/ui/chat/MarkdownText.kt`, `DeltaCursor.kt`, `MarkdownFence.kt` |
| Context ring, three cases, compaction projection | `app/…/ui/chat/ContextUsage.kt`, `ContextGauge.kt`, `ContextCompaction.kt` |
| Assistants, tool picker, prompt import | `app/…/ui/agents/AgentsScreen.kt`, `AgentsViewModel.kt`, `PromptImport.kt` |
| Workspaces, lifetime, promotion, absolute path | `app/…/ui/workspaces/`, `core/db/…/ConversationRepository.kt` |
| Conversation list, pinning, delete confirmation | `app/…/ui/ConversationsScreen.kt`, `ConversationsViewModel.kt` |
| Browsing, import, export, preview | `app/…/ui/files/FilesScreen.kt`, `FilesViewModel.kt` |
| URL download; SSRF, redirect and file-name rules | `app/…/ui/files/UrlDownloader.kt` |
| Provider list/editor, web backends and keys | `app/…/ui/settings/SettingsScreen.kt`, `app/…/ui/chat/SettingsViewModel.kt`, `app/…/web/WebToolProviders.kt` |
| `web_search` / `web_fetch` backends | `core/ai/…/provider/deepseek/DeepSeekSearchApi.kt`, `provider/exa/ExaSearchApi.kt`, `tools/WebFetchTool.kt` |
| Anything a model can call; `mutates`; approval policy | `core/ai/…/Tool.kt` |
| Which tools exist; the static catalogue | `core/ai/…/tools/ToolCatalog.kt` |
| File tools' behaviour | `core/files/…/tools/FileTools.kt` |
| Quotas and guarded reads/writes/lists | `core/files/…/WorkspaceTool.kt`, `WorkspaceLimits.kt` |
| A path becoming a `File`; confinement | `core/files/…/SessionWorkspace.kt` |
| Import/export and name collisions | `core/files/…/WorkspaceTransfer.kt` |
| HTTP, SSE, decoder, model list, request shape | `core/ai/…/provider/deepseek/` |
| Provider profiles, model cache, key slots | `core/db/…/AgentRepository.kt`, `app/…/ui/chat/SettingsViewModel.kt` |
| Demo mode's script and magic words | `core/ai/…/provider/demo/DemoProvider.kt` |
| Diagnostics and the log file | `core/common/…/diag/`, `app/…/diagnostics/AppDiagnostics.kt`, `desktopApp/…/DesktopDiagnostics.kt` |
| Tables, queries, ordering, migrations | `core/db/…/db/InhumanDatabase.kt`, `Entities.kt` |
| Creating/deleting conversations, orphan sweep | `core/db/…/ConversationRepository.kt` |
| Assistants' persistence and model cache | `core/db/…/AgentRepository.kt` |
| Workspace/rollback directories | `core/db/…/DataPaths.kt` |
| API key at rest | `core/data/…/secret/SecureStore.kt`, `desktopApp/…/secret/DpapiSecretStore.kt` |
| What the system Files app can do | `core/data/…/provider/WorkspaceDocumentsProvider.kt` |
| Theme, type, hairlines, measure, wordmark | `core/design/…/` |
| Windows desktop window/chat/store | `desktopApp/…/Main.kt`, `DesktopChat.kt`, `DesktopStore.kt` |
| Windows packaging and signing | `desktopApp/build.gradle`, `tools/sign-msi.ps1`, `docs/12-desktop.md` |

Three files carry more weight than their size suggests: `SessionWorkspace.kt`
(the confinement gate), `Tool.kt` (the `mutates` / `needsApproval` distinction)
and `ChatViewModel.kt` (where the Android agent loop lives).

## Keeping these current

These docs are part of the change, not an afterthought: a commit that alters a
behaviour described here should alter the description in the same commit. Doc
comments in this repository have been found making claims the implementation did
not honour, and [07-gotchas.md](07-gotchas.md) section C lists the ones that still
stand — which is the argument for the rule rather than an exception to it.

When the desktop changes, update [12-desktop.md](12-desktop.md) in the same
commit. When a historical decision is superseded, leave the archive alone and
change the current document instead.
