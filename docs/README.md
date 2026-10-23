# inhuman — the docs set

These files exist so that a later session — human or agent — can work on this
project **without reading the source first**. Each one states what the code does
and cites the file and line that says so, so a claim can be checked cheaply and
a stale claim can be spotted quickly.

Fifteen files in total: twelve numbered, two about the port, and this index. The
numbered series describes **what the code does today**, with two exceptions that
say so in their own first lines — `08-port-philosophy.md` and `port-design.md`
describe what the project *intends*, because the runtime part is not written yet.
`09-approvals.md` is both kinds in one file: it states the approval model, then
records in its §7 how much of it is built and verified. Keep the kinds apart: a
claim from the intent group is a decision, not a description.

They are not a replacement for the root `README.md`, and they are not a
tutorial. The root README explains *what the app is* and what it deliberately is
not; `AGENTS.md` states the non-negotiable working rules. These files explain
*how it is built*, in the order you need it.

## Where the root documents have fallen behind

Read `README.md` and `AGENTS.md` for intent — they are the best statement of what
this project is trying to be and what it refuses to become. Do **not** read them
for counts or for the current shape of a mechanism, because both have drifted,
and a future session that trusts them will start from a false premise:

| Claim | Where | Reality |
| --- | --- | --- |
| The cap makes a tablet layout readable | `WideDialog.kt:89-93`, `ConversationsScreen.kt:452-457`, `SettingsScreen.kt:96-100` | **Was** inert at those sites — the fill modifier came first. **Fixed**, and verified by measurement on the tablet AVD. See [07-gotchas.md](07-gotchas.md) B5. |
| "the chat screen applies [the cap] to the transcript *and* to the composer strip" | `AGENTS.md` | True for the transcript and composer, which share the 900dp `ChatMeasure`. User messages are 560dp bubbles; assistant replies have no bubble and a 900dp measure. Forms/settings still use 640dp. See [06](06-agent-loop-ui.md). |
| "`writeText` is wrapped in `NonCancellable`" | `AGENTS.md:151` (now corrected) | It is the row insert at `ChatViewModel.kt:1040`, not the tool-layer `writeText`. |

The tool table in `README.md` is worth trusting: eighteen tools, including
`read_time`, `web_search`, `web_fetch`, `ssh_probe`, `ssh_list`, `ssh_read_file`,
`ssh_exec`, `ssh_upload`, `ssh_write_file` and `ssh_download`. What has drifted
is the *prose* around mechanisms rather than the lists.

The pattern is worth naming, because it is the one thing this docs set exists to
prevent: **a description of a mechanism outlives the mechanism.** When a count, a
list or a "this is how X works" sentence appears in prose with no file and line
beside it, treat it as a hypothesis and check it against the code before relying
on it — including in these files, which is why every claim here carries a
citation.

## Read in this order

| File | Answers |
| --- | --- |
| [01-overview.md](01-overview.md) | What is this, which module holds what, what does the app actually do today, what is deliberately missing |
| [02-architecture.md](02-architecture.md) | Module graph, dependency rule, how the app is wired at start-up, navigation, build toolchain, build/verify commands |
| [03-ai-provider.md](03-ai-provider.md) | The provider interface and stream types, provider profiles, the OpenAI-compatible adapter, SSE decoding, demo mode, `web_search`/`web_fetch` and the paid backends behind them, and the SSH tools (`ssh_probe`, `ssh_list`, `ssh_read_file`, `ssh_exec`, `ssh_upload`, `ssh_write_file`, `ssh_download`) |
| [04-data-persistence.md](04-data-persistence.md) | Room schema v13 and its exported versions, repositories, ordering rules, key storage, `DocumentsProvider`, orphan sweeps |
| [05-files-sandbox.md](05-files-sandbox.md) | `SessionWorkspace`, path confinement, quotas, the 8 file tools, import/export |
| [06-agent-loop-ui.md](06-agent-loop-ui.md) | The turn lifecycle, tool-call execution, the approval gate, cancellation, context gauge, Compose layout rules |
| [07-gotchas.md](07-gotchas.md) | The load-bearing invariants, and the mistakes that already cost someone a day |
| [08-port-philosophy.md](08-port-philosophy.md) | **Why** a port is wanted at all, the three constraints that rule out a "full access" option, the seven design questions with the answers that were settled, and the paid-disclosure rule |
| [09-approvals.md](09-approvals.md) | The approval model: no prompt by default, how per-tool, per-assistant and per-path rules compose, and what of it is built and verified |
| [10-diagnostics.md](10-diagnostics.md) | The flight recorder: what is logged, what is deliberately left out, where it lives and how to get it off the device |
| [11-conversation-tools.md](11-conversation-tools.md) | The conversation-level tool sheet: the assistant ceiling, next-turn timing, and why it is not a new SSH exception |
| [12-desktop-port.md](12-desktop-port.md) | The measured Windows/desktop port assessment: reuse map, CMP/Room/DPAPI risks, spike and phased estimate |
| [port-assessment.md](port-assessment.md) | Feasibility of porting the DeepSeek Harness runtime, with device measurements |
| [port-design.md](port-design.md) | How it would be built: layering, the 43-kind event vocabulary, phases |

`07-gotchas.md` is the one to read when you are about to change something and do
not have time to read the rest. Everything in it was a real bug.

`08-port-philosophy.md` is the one to read before proposing anything that adds a
setting, a mode, or a permission — it is where the project says which options it
refuses to have.

## Conventions used here

- **Citations** are `relative/path.kt:LINE` from the repository root, and point
  at the code that decides the behaviour — not at a doc comment that describes
  it. Where a comment and the code disagree, the doc says so explicitly, because
  in this tree the comment is treated as the bug.
- **"Verified"** means the claim was checked against the source file at the
  cited line. Nothing here is inferred from a name, a test title, or an earlier
  doc.
- The defect lists in `07-gotchas.md` were found by reading the code, **not** by
  running the app. They are claims about the source with citations, and each one
  is worth reproducing on a device before it is fixed.
- **Line numbers are the weakest part of every citation, and they are treated as
  such.** `ChatViewModel.kt` grew past 1,600 lines and *every* line number in
  these docs went stale twice in one week of ordinary work — a fix in one place
  moves citations in five others. So:

  - When a citation points at a **file**, it is reliable.
  - When it points at a **line**, read it as "near here, as of the last pass".
  - Where a number would be fragile, the doc names a **searchable marker**
    instead — `MIGRATION_n_m`, `REFUSED_MARKER`, `DECLINED_MARKER`, `ToolPolicy.Ask`,
    `demoEnabled()`. Prefer adding markers over adding numbers.

  Do not spend a session re-deriving line numbers by hand. If a citation is
  wrong, the code it was describing is what to trust, and the fix is to make the
  citation coarser rather than more precise.
- Wording follows the repository's house style: say *why*, and name the failure
  that the code prevents when there is one.

## The state of the test suite

Measured by running it, not by counting files:

```powershell
$env:JAVA_HOME = 'C:\android-toolchain\jdk17'
.\gradlew.bat :core:common:testDebugUnitTest :core:ai:testDebugUnitTest `
  :core:files:testDebugUnitTest :core:data:testDebugUnitTest `
  :app:testDebugUnitTest --console=plain --rerun-tasks
```

**BUILD SUCCESSFUL, 449 tests in 55 suites, 0 failures, 2 skipped** — the skips
are `DeepSeekWebLiveTest`, which talks to the real paid Search and Fetch APIs and
needs `DEEPSEEK_LIVE_KEY`, and `SshLiveProbeTest`, which needs
`INHUMAN_SSH_HOST`/`USER`/`PASSWORD` for its read-only live check. Warn output
was empty. Suite by suite:

| Module | Suites | Tests |
| --- | --- | --- |
| `:core:ai` | 29 | 249 (2 skipped) |
| `:core:files` | 5 | 57 |
| `:app` | 16 | 110 |
| `:core:common` | 2 | 16 |
| `:core:data` | 3 | 17 |

The count grows with every fix that lands with a test, so treat the total as a
baseline to re-measure rather than a fact to quote: the command above is the
authority.

All of it runs on the JVM, against temp directories rather than a device. That is
why the pure-invariant tests (`DeltaCursor`, `ContextUsage`, `activePathOf`)
carry arguments the device could not make — see
[02-architecture.md](02-architecture.md).

Eight suites do **not** run on the JVM, and are not in the table above because
they only run against a device:

| Suite | Why it needs a device |
| --- | --- |
| `app/src/androidTest/…/SessionLogReadBenchmark` | How long a session log takes to read on ARM64 — the number a port's cold start depends on |
| `app/src/androidTest/…/JavaScriptSandboxProbe` | Whether the platform JavaScript sandbox is available, and what it withholds (`fetch`, `require`, `process`) |
| `app/src/androidTest/…/WebToolProvidersTest` | The web keys are independent: a chat key alone registers no tool, and Exa/DeepSeek select the right provider |
| `core/data/src/androidTest/…/MigrationTest` | Room migrations against a real SQLite, including 11→12 and 12→13 |
| `core/data/src/androidTest/…/WorkspaceLifecycleTest` | Temporary workspaces die with their conversation; permanent ones survive and promotion does not move the directory |
| `core/data/src/androidTest/…/ConversationLookupTest` | The `id` / `workspaceId` distinction the documents provider's folder lookup depends on; both are UUIDs, and the wrong one returns null rather than failing |
| `core/data/src/androidTest/…/WorkspaceDocumentsProviderTest` | The provider's actual folder ids: a re-queried folder keeps its title, and delete/rename are refused |
| `core/data/src/androidTest/…/ProviderProfileTest` | A conversation copies its assistant's provider; a referenced provider cannot be deleted; key slots are separate |
| `app/src/androidTest/…/RealProviderProbeTest` | One real `GET /models` when the device already holds a DeepSeek key; a no-op without one rather than a fake pass |
| `app/src/androidTest/…/ImagePreprocessorTest` | A 4000×3000 JPEG is bounded to 1568×1176 and 1 MiB; an 800×600 image is not enlarged; a transparent PNG becomes WebP. This is the device half of the image pipeline, since `Bitmap` does not exist on the JVM || `core/data/src/androidTest/…/ConversationSearchTest` | Message and title search, ordering, and literal `%` handling |

Run them with `:app:connectedDebugAndroidTest` / `:core:data:connectedDebugAndroidTest`,
or `adb shell am instrument`. The `:core:data` run is **27 tests**, and the app
suite is **11 tests** (including the two deterministic notification-service
tests and the three image-preprocessor tests), at the time of
writing. The first two have their measured
results in [port-assessment.md](port-assessment.md); the rest are the evidence
behind [07-gotchas.md](07-gotchas.md) B8 and B6.

## Which files own which behaviour

The shortest path from a symptom to the code that decides it. Every entry is a
path from the repository root, and `…/` stands for `src/main/kotlin/com/inhuman/`
throughout.

| Symptom, or thing you want to change | Go to |
| --- | --- |
| Work out a rough plan at all | [`01-overview.md`](01-overview.md), then [`07-gotchas.md`](07-gotchas.md) |
| Startup, dependency wiring, navigation, Back, ViewModel keys | `app/src/main/kotlin/com/inhuman/MainActivity.kt` |
| Streaming, tool loop, approval, cancellation, regenerate, context gauge, compaction | `app/src/main/kotlin/com/inhuman/ui/chat/ChatViewModel.kt` (the file everything routes through) |
| Visible chat layout, bubbles, composer, tool rows, the dialog | `app/src/main/kotlin/com/inhuman/ui/chat/ChatScreen.kt` |
| Incremental Markdown, the delta channel, the renderer switch, unclosed fences | `ui/chat/MarkdownText.kt`, `ui/chat/DeltaCursor.kt`, `ui/chat/MarkdownFence.kt` |
| The context ring, its three cases and the compaction projection | `ui/chat/ContextUsage.kt`, `ui/chat/ContextGauge.kt`, `ui/chat/ContextCompaction.kt` |
| Assistants, the tool picker, system-prompt import | `ui/agents/AgentsScreen.kt`, `ui/agents/AgentsViewModel.kt`, `ui/agents/PromptImport.kt` |
| Workspaces, lifetime, promoting a temporary one, absolute path | `ui/workspaces/WorkspacesScreen.kt`, `ui/workspaces/WorkspaceDetailScreen.kt`, `core/data/…/ConversationRepository.kt` |
| The conversation list, pinning, delete confirmation | `ui/ConversationsScreen.kt`, `ui/ConversationsViewModel.kt` |
| Browsing, import, export, file preview | `ui/files/FilesScreen.kt`, `ui/files/FilesViewModel.kt` |
| Downloading a URL; SSRF, redirect and file-name rules | `ui/files/UrlDownloader.kt` |
| The provider list/editor, web backends and keys | `ui/settings/SettingsScreen.kt`, `ui/chat/SettingsViewModel.kt`, `web/WebToolProviders.kt` |
| `web_search` / `web_fetch` — backends, response parsing, URL cleaning | `provider/deepseek/DeepSeekSearchApi.kt`, `provider/exa/ExaSearchApi.kt`, `tools/WebFetchTool.kt` |
| Anything a model can call; `mutates`; approval policy | `core/ai/src/main/kotlin/com/inhuman/core/ai/Tool.kt` |
| Which tools exist at all; the static catalogue | `core/ai/…/tools/ToolCatalog.kt` |
| The eight file tools' behaviour | `core/files/src/main/kotlin/com/inhuman/core/files/tools/FileTools.kt` |
| Quotas and every guarded read/write/list | `core/files/…/WorkspaceTool.kt`, `core/files/…/WorkspaceLimits.kt` |
| A path becoming a `File`; the confinement rules | `core/files/…/SessionWorkspace.kt` |
| Import/export and name collisions | `core/files/…/WorkspaceTransfer.kt` |
| HTTP, SSE, the decoder, model list, chat request shape | `core/ai/src/main/kotlin/com/inhuman/core/ai/provider/deepseek/` |
| Provider profiles, provider-specific model cache, key slots | `core/data/…/AgentRepository.kt`, `ui/chat/SettingsViewModel.kt` |
| Demo mode's script and its magic words | `provider/demo/DemoProvider.kt` |
| What was recorded before a bug, and how to get the log | `core/common/src/main/kotlin/com/inhuman/core/common/diag/`, `app/src/main/kotlin/com/inhuman/diagnostics/AppDiagnostics.kt` |
| Tables, queries, ordering, migrations | `core/data/src/main/kotlin/com/inhuman/core/data/db/InhumanDatabase.kt`, `db/Entities.kt` |
| Creating/deleting conversations, the orphan sweep | `core/data/…/ConversationRepository.kt` |
| Assistants' persistence and the model cache | `core/data/…/AgentRepository.kt` |
| The API key at rest | `core/data/…/secret/SecureStore.kt` |
| What the system Files app can do to a workspace | `core/data/…/provider/WorkspaceDocumentsProvider.kt` |
| Theme, type, hairlines, measure, wordmark | `core/design/src/main/kotlin/com/inhuman/core/design/` |
| Driving the app and reading its UI | `tools/build-and-shot.ps1`, `tools/device-flow.ps1`, `tools/UiDump.psm1` |

Three files carry more weight than their size suggests, and breaking either of
the first two restores a bug rather than adding one: `SessionWorkspace.kt` (the
confinement gate) and `Tool.kt` (the `mutates` / `needsApproval` distinction).
`ChatViewModel.kt` is where the agent loop actually lives, so most "why did the
turn do that?" questions end there.

## Keeping these current

These docs are part of the change, not an afterthought: a commit that alters a
behaviour described here should alter the description in the same commit. Doc
comments in this repository have been found making claims the implementation did
not honour, and [07-gotchas.md](07-gotchas.md) section C lists the ones that still
stand, which is the argument for the rule rather than an exception to it.

The cheapest way to check the drift-prone parts is to re-read the files cited in
`07-gotchas.md` — those are the ones where being wrong is expensive.
