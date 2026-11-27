# inhuman — the docs set

These files exist so that a later session — human or agent — can work on this
project **without reading the source first**. Most state what the code does and,
where useful, cite the file that decides the behaviour; `13-product.md` is the
deliberate exception, written at the product level with no code references.

The project is **Android-only**: the Android app (`:app`) is the product. The
Windows x64 desktop host (`:desktopApp`) that shared the JVM core was removed on
2026-11-20 and survives only in git history — it went unused, and the second
host's compile, test and UI work had begun to tax the Android build. The old
feasibility studies are in [`archive/`](archive/README.md) and are historical,
not current documentation.

`01`–`11` describe Android/shared behaviour. `13` is the technology-neutral
product document: principles, current features and future plans, with no stack or
implementation detail. `14` is the documents/voice decision.

They are not a replacement for the root `README.md`, and they are not a
tutorial. The root README explains *what the app is* and what it deliberately is
not; `AGENTS.md` states the non-negotiable working rules. These files explain
*how it is built*.

## Read in this order

| File | Answers |
| --- | --- |
| [01-overview.md](01-overview.md) | What this is, which module holds what, what the app does today, what is deliberately missing |
| [02-architecture.md](02-architecture.md) | Module graph, dependency rule, start-up wiring, navigation, build toolchain, build/verify commands |
| [03-ai-provider.md](03-ai-provider.md) | Provider interface and stream types, provider profiles, OpenAI-compatible / Anthropic / Gemini / Responses adapters, SSE, demo mode, paid web tools, SSH tools and limits |
| [04-data-persistence.md](04-data-persistence.md) | Room schema v30 and exported versions, repositories, ordering, key storage, `DocumentsProvider`, orphan sweeps |
| [05-files-sandbox.md](05-files-sandbox.md) | `SessionWorkspace`, path confinement, quotas, the file tools, import/export |
| [06-agent-loop-ui.md](06-agent-loop-ui.md) | Turn lifecycle, tool execution, approval gate, cancellation, context gauge, Compose layout |
| [07-gotchas.md](07-gotchas.md) | Load-bearing invariants, and mistakes that already cost someone a day |
| [08-port-philosophy.md](08-port-philosophy.md) | Why the port exists, the constraints that rule out a “full access” mode, design questions, paid-disclosure and SSH exceptions, and the authoring principle (§1.8: content may be authored, behaviour may not) |
| [09-approvals.md](09-approvals.md) | Approval model: no prompt by default, how per-tool/per-assistant/per-path rules compose, built status |
| [10-diagnostics.md](10-diagnostics.md) | Flight recorder: what is logged, what is left out, where it lives, how to share it |
| [11-conversation-tools.md](11-conversation-tools.md) | Conversation-level tool sheet: assistant ceiling, next-turn timing, why it is not a new SSH exception |
| [13-product.md](13-product.md) | **Product document, deliberately technology-neutral**: design principles, current features and future plans |
| [14-documents-voice.md](14-documents-voice.md) | Documents/voice decision: format matrix, libraries, loss reporting, limits, tools, Android-only voice, rejected alternatives |

`07-gotchas.md` is the one to read when you are about to change something and do
not have time to read the rest. Everything in it was a real bug.

`08-port-philosophy.md` is the one to read before proposing anything that adds a
setting, a mode, or a permission — it is where the project says which options it
refuses to have.

`13-product.md` is the one to read before a product, platform or stack decision —
it states what the product promises without naming a technology.

## Historical archive

These were written before the port was implemented and are kept for their
measurements, rejected options and phase estimates:

| File | What it is |
| --- | --- |
| [archive/port-assessment.md](archive/port-assessment.md) | Feasibility study with device measurements |
| [archive/port-design.md](archive/port-design.md) | Original layering/event/phasing design |
| [archive/README.md](archive/README.md) | Why they are archived, and where to go instead |

Do not use them to answer “what does the code do today”. The numbered documents
above are that answer.

## Proposals

Design proposals, mostly for features that are **not implemented**; an entry
marked implemented is kept as the design and decision record. They say what
should be built and why; they are not a description of current behaviour.

| File | What it proposes |
| --- | --- |
| [proposals/continuous-session.md](proposals/continuous-session.md) | A continuous-session mode, on by default for new conversations: the record is never deleted, the request projection becomes a rolling session brief plus recent turns, and the model can read the full record through a read-only recall tool |
| [proposals/deepseek-app-apis.md](proposals/deepseek-app-apis.md) | A triage of DeepSeek's 2026-11-13 application-API suite: the standalone ASR/TTS and OCR APIs were adopted in 0.13.0 as paid app-proxied backends (see `03-ai-provider.md`); the server-side web tools, the Files API and the safety check are refused because they change the capability boundary |
| [proposals/in-turn-user-message.md](proposals/in-turn-user-message.md) | **Implemented 2026-11-25**: a message written while a turn runs is queued for the next turn, or inserted before the current turn's next model call (schema v26, `messages.injected`); kept as the design and decision record |
| [proposals/user-cloud-sync.md](proposals/user-cloud-sync.md) | Let the user bring their own cloud storage: **Phase 1 implemented 2026-11-26** (mirrors the encrypted backup into a remembered SAF sync folder in one tap); Phase 2 is conversation-level two-way sync with stable ids, tombstones and explicit conflict copies |
| [proposals/rp-authoring-assistant.md](proposals/rp-authoring-assistant.md) | RP materials as workspace files plus two authoring tools: `rp_format` / `rp_validate` (Phase 0) and the folder-based scene-creation flow with the legacy-card export (Phase 1) — both implemented; the remaining multi-character/worldbook work is not part of this proposal |
| [proposals/rp-formatting.md](proposals/rp-formatting.md) | How the mature RP ecosystem renders replies (text conventions + a front-end Markdown/HTML layer, with display-only transforms) and why inhuman's default scene read as one flat block. Decision: do not depend on the model obeying a format — **B (a display-only RP typography layer) implemented 2026-11-26**; a formatting prompt contract / scene presets (A) and a status side-channel (C) remain optional. §9 also surveys external cards with HTML UIs (Chub sample: 2.75% raw HTML in card text; front-end cards live in `regex_scripts`) and records **P0a, external card import, implemented 2026-11-26** (PNG/JSON/CHARX, text mapping, HTML-to-text, explicit "not imported" reports); HTML rendering (P1), the WebView runtime (P2), URL import and extension execution were deliberately not taken |
| [proposals/rp-agent-studio.md](proposals/rp-agent-studio.md) | The original RP workshop proposal: one built-in permanent workspace (`workspaces.kind = roleplay`, schema v28) as the only place the role-play authoring helpers exist, with the persistent home row and Create-sheet entry, host-appended `rp_format`/`rp_validate`/`rp_create_scene`, the scene-proposal card and the tap-to-create-and-open path through `RoleplaySceneCreator`. **P0 was implemented 2026-11-27 and merged the same day**: `workshop-merge.md` turned that row into the single built-in workshop (schema v29). Kept as the design and decision record; v1's per-assistant opt-in design is the appendix's rejected alternative, and later phases (community-card file sources, assistant creation, entry decisions) are not implemented |
| [proposals/config-workshop.md](proposals/config-workshop.md) | The original configuration-workshop proposal: a second built-in permanent workspace (`workspaces.kind = studio`, same v28 column) whose six host-appended helpers list/read the user's assistants and each permanent workspace's root `AGENTS.md`, and prepare assistant or instruction drafts; the cards open the existing assistant or Files editor prefilled and write nothing, and proposals carry no tool/model/provider/approval fields. **P0 was implemented 2026-11-27 and merged the same day** into the single built-in workshop (`workshop-merge.md`, schema v29); on upgrade a leftover configuration-workshop row is demoted to an ordinary permanent workspace with its conversations and files kept. Kept as the design and decision record |
| [proposals/workshop-merge.md](proposals/workshop-merge.md) | **Implemented 2026-11-27**: merged the RP workshop and the configuration workshop into one built-in workshop (`workspaces.kind = "workshop"`, schema v29) — one home/Create entry, the union of the host-appended helpers (nine at the time; the saved-prompt set landed later in `prompt-library.md`), one `Workshop context:` block, and management that knows the workshop (badge, no rename/delete, excluded from bulk delete). On upgrade the old configuration workshop becomes an ordinary permanent workspace; its files and conversations are kept. Also reviews the wider workspace/management design and why the temporary/permanent model itself stays |
| [proposals/prompt-library.md](proposals/prompt-library.md) | **Implemented 2026-11-27**: the assistant editor's prompt import now offers two sources — the device file picker or a two-step browse of any workspace (permanent and temporary), read through that workspace's `SessionWorkspace` with the same 1 MiB guard, UTF-8 check and replace/append question as the device picker. The built-in workshop may now read the saved-prompt library (`app_list_prompts` / `app_read_prompt`) and prepare a new or updated entry (`app_propose_prompt`), but no tool writes: the proposal card's **Save to prompt library** tap calls `SavedPromptRepository.save`, the same title-unique upsert the assistant editor uses. Twelve host-appended helpers, no schema change (v30), no `ToolCatalog` change (46), no new capability. **Amends `config-workshop.md` §9's "the prompt library stays model-invisible"** |
| [proposals/conversation-references.md](proposals/conversation-references.md) | Let an agent read another conversation at the user's explicit gesture: **P0b copy-a-reference into the RP workshop workspace** (sanitised Markdown, no new capability) and **P1 a workshop-level allowlist plus a read-only `read_conversation` tool**. Not implemented; the design keeps the read/egress disclosure and excludes tool results, reasoning and attachments |
| [proposals/local-web-tools.md](proposals/local-web-tools.md) | **Implemented 2026-11-27** (phone): provider-free local web tools — `web_search_local` (DuckDuckGo/Bing public result pages requested by the app) and `web_fetch_local` (https-only, jsoup extraction, optional WebView `render`). Split switches, per-conversation consent for fetch (schema v30 `localFetchAllowed`), system-VPN egress only; the paid DeepSeek/Exa tools and their static `paid` metadata are untouched. Since 0.35.0 both tools share a fixed per-turn budget — 10 requests, at least 1 s apart, at most 3 rendered — and a call past it is refused without a request |
| [proposals/javascript-workspace-data.md](proposals/javascript-workspace-data.md) | **Proposed**: a read-only `input_files` argument for `run_javascript` — the host reads named workspace text files and injects them as data. The sandbox keeps no filesystem, network or host callback; every path goes through `read_file`'s permission and policy rules, and `output_path` (write-back) is explicitly deferred |
| [proposals/cast-run.md](proposals/cast-run.md) | **Proposed, requirement + feasibility only; the requirement is now a project principle (`docs/08-port-philosophy.md` §1.8)**: the multi-actor trial base for `authoring-rehearsal.md` — the main agent prepares a bounded plan of persona actors (character cards plus world-book hits, **no general tools**) and runs a multi-speaker simulation inside one parent turn, for role-play self-tests, simulated users, adversarial reviews or expert panels. Records why actors are not DSH subagents (no general capability, no persistent session, no waking turn), why the cost gate and the per-actor projection are the real work, and the **recorded direction (2026-11-27)** for world-book access: deterministic keyword injection first, plus one host-provided read-only lookup tool for the actor (no filesystem/network/exec), reusing the same entry data and visibility filter, deduplicated, budgeted and with a stable tool schema; the query default and per-run caps remain open. Also records that `roleplay.md`'s "auto-chat is not done" becomes "no unbounded auto-chat; bounded cast runs are allowed" |
| [proposals/authoring-rehearsal.md](proposals/authoring-rehearsal.md) | **Proposed, requirement only; now a project principle (`docs/08-port-philosophy.md` §1.8, `docs/13-product.md` #13)**: the app-wide need behind the cast-run work — users cannot write system prompts or configure cards, scenes or world books, so the agent should author user-configurable **content**, rehearse it in an isolated draft trial to get first-hand feedback, iterate within one authorized budget, and hand the user a proposal that already passed its cases. Records the three-tier boundary (content may be authored; tool/model/sampling choices may only be suggested; tool runtime, permissions, grants, keys and global settings are off-limits), a current-configurable-surface table, the `trial_run` shape (ephemeral draft, zero capability, stub tools, one authorization many iterations) and why this is neither a GM nor a subagent |

The role-play proposal (`proposals/roleplay.md`) was implemented on 2026-11-25
(single character, single scene; no multi-character turns, world books or card
import); current behaviour is described in
[06-agent-loop-ui.md](06-agent-loop-ui.md) and
[04-data-persistence.md](04-data-persistence.md). The in-turn message proposal
was implemented the same day (queue and insert-before-next-call, schema v26);
its current behaviour is in [06-agent-loop-ui.md](06-agent-loop-ui.md) and
[07-gotchas.md](07-gotchas.md) A29. The RP authoring proposal has been decided:
Phase 0 — the frozen scene persona (`conversations.rpSnapshotJson`) and the
`rp_format` / `rp_validate` authoring helpers — is implemented (schema v27),
and Phase 1, creating scenes from a workspace folder, is implemented as well.
The user-configured sync proposal still waits on a product decision; it records
its own open questions and suggested order. The RP workshop proposal's P0
(the built-in workshop workspace, the scene-proposal card and the tap-to-create
path) landed on 2026-11-27; its later phases and the conversation-reference
proposal are not implemented. The configuration-workshop proposal's P0 (the
second built-in workspace, its six read/draft helpers and the editor handoffs)
landed the same day. Both were superseded the same day: the workshop-merge
proposal replaced the two with one built-in workshop (schema v29), and is kept
as the decision record in `docs/proposals/workshop-merge.md`; assistant
deletion, proposal-card persistence and the conversation-reference proposal
remain unimplemented. The saved-prompt read/draft access landed on 2026-11-27
(`prompt-library.md`), amending the configuration-workshop proposal's §9 rule
that the library stays model-invisible.

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
  :core:ai:test :core:files:test :core:documents:test :core:common:test `
  --console=plain --rerun-tasks
```

Latest full run (2026-11-03, before the desktop host was removed; the full
`--rerun-tasks` list from `AGENTS.md`):

```
BUILD SUCCESSFUL, 793 tests in 118 suites, 0 failures, 4 skipped
warnings 0, errors 0
```

`tools/ci.ps1` runs this list (plus `setup-machine.ps1`) and fails on any
compiler warning, so a CI host has one command to call instead of a copied
argument list.

The skips are external-credential live tests: `DeepSeekWebLiveTest`,
`EngineLiveTest`, `SshLiveProbeTest` and `QwenTokenPlanLiveTest`. Suite by suite:

| Module | Suites | Tests |
| --- | --- | --- |
| `:core:ai` | 41 | 327 (3 skipped) |
| `:core:files` | 9 | 84 |
| `:core:documents` | 2 | 18 |
| `:app` | 36 | 243 |
| `:core:common` | 2 | 16 |
| `:core:db` | 9 | 34 |
| `:core:engine` | 2 | 13 (1 skipped) |
| `:core:data` | 3 | 19 |

The totals above are the historical run and include the removed `:desktopApp`
module's 14 suites and 39 tests; the surviving modules are 754 tests in 104
suites. The total grows with every fix that lands with a test; treat it as a
baseline to re-measure rather than a fact to quote. The command above is the
authority.

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
| `app/src/androidTest/…/ui/chat/CameraCaptureTest` | The camera's FileProvider write/read round trip and stale-capture sweep |
| `app/src/androidTest/…/ui/chat/ConversationShareTest` | The share provider reads back the Markdown handed to the chooser |
| `app/src/androidTest/…/ui/chat/PdfVisionRenderTest` | PDFBox rasterizes a page to a bounded JPEG |
| `app/src/androidTest/…/ui/chat/PdfVisionToolTest` | An image-only PDF is rendered, sent as one image part to a fake provider, and its text is returned |
| `core/data/src/androidTest/…/DocumentCodecAndroidTest` | DOCX/XLSX zip and DOM path on Android's parser |
| `app/src/androidTest/…/ui/documents/AndroidPdfBackendTest` | PDFBox-Android text extraction and platform PDF creation |
| `app/src/androidTest/…/ui/documents/LegacyPoiProbeTest` | POI BIFF8 round trip, legacy adapter, DOC-write refusal |
| `app/src/androidTest/…/ui/chat/VoiceRecorderTest` | Microphone permission and the in-memory WAV container |
| `app/src/androidTest/…/sandbox/SandboxRuntimeTest` | JS arithmetic, absent network APIs and runaway timeout |
| `core/data/src/androidTest/…/db/MigrationTest` | Room migrations against real SQLite |
| `core/data/src/androidTest/…/WorkspaceLifecycleTest` | Temporary/permanent workspace lifetime and promotion |
| `core/data/src/androidTest/…/db/ConversationLookupTest` | `id` / `workspaceId` lookup distinction |
| `core/data/src/androidTest/…/provider/WorkspaceDocumentsProviderTest` | DocumentsProvider folder ids and refusal rules |
| `core/data/src/androidTest/…/ProviderProfileTest` | Conversation provider copy and key-slot rules |
| `core/data/src/androidTest/…/ConversationSearchTest` | Message/title search, ordering, literal `%` |

Run them with `:app:connectedDebugAndroidTest` /
`:core:data:connectedDebugAndroidTest`, or `adb shell am instrument`. Counts
depend on which opt-in live tests are enabled.

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
| Workspace `AGENTS.md` pinned per conversation; reload and its cache cost | `app/…/ui/chat/WorkspaceInstructions.kt`, [`03-ai-provider.md`](03-ai-provider.md), [`06-agent-loop-ui.md`](06-agent-loop-ui.md) |
| The wire-only time hint on user messages; mode and gap threshold | `app/…/ui/chat/TimeHints.kt`, `ChatViewModel.buildWireHistory`, [`06-agent-loop-ui.md`](06-agent-loop-ui.md) |
| Assistants, tool picker, prompt import (device file or workspace file) | `app/…/ui/agents/AgentsScreen.kt`, `AgentsViewModel.kt`, `PromptImport.kt`, `PromptImportDialogs.kt` |
| Workspaces, lifetime, promotion, absolute path | `app/…/ui/workspaces/`, `core/db/…/ConversationRepository.kt` |
| Conversation list, pinning, delete confirmation | `app/…/ui/ConversationsScreen.kt`, `ConversationsViewModel.kt` |
| Browsing, import, export, preview | `app/…/ui/files/FilesScreen.kt`, `FilesViewModel.kt` |
| URL download (user-initiated); SSRF, redirect and file-name rules | `app/…/ui/files/UrlDownloader.kt`, shared rules in `core/ai/…/tools/DownloadUrl.kt` |
| Agent URL download (`download_url`): tool, limits, consent | `core/ai/…/tools/DownloadTool.kt`, `ToolCatalog.kt`, `ChatViewModel.gateEngineCall` |
| Agent URL download: the Android fetch and the workspace sink | `app/…/download/HttpDownloadFetcher.kt`, `WorkspaceDownloadSink.kt` |
| Provider list/editor, web backends and keys | `app/…/ui/settings/SettingsScreen.kt`, `app/…/ui/chat/SettingsViewModel.kt`, `app/…/web/WebToolProviders.kt` |
| Media tool backends, per-tool provider/model or separate API | `app/…/ui/chat/MediaTools.kt`, `app/…/ui/settings/MediaToolDialog.kt` |
| Usage totals, paid-call counting, day buckets | `app/…/ui/settings/UsageReport.kt`, `UsageSection.kt`, `:core:db` `MessageDao.usageRows()` |
| Conversation Markdown; workspace zip; honest failed-export reporting | `app/…/ui/chat/ConversationMarkdown.kt`, `core/files/…/WorkspaceZip.kt` |
| `web_search` / `web_fetch` backends | `core/ai/…/provider/deepseek/DeepSeekSearchApi.kt`, `provider/exa/ExaSearchApi.kt`, `tools/WebFetchTool.kt` |
| Local (free, keyless) web tools: DDG/Bing result pages, https fetch + jsoup extraction, WebView render, shared SSRF guard, per-conversation consent | `core/ai/…/provider/local/`, `core/ai/…/tools/GuardedHttp.kt`, `HtmlText.kt`, `app/…/web/LocalWebProviders.kt`, `WebViewPageRenderer.kt`, `ChatViewModel.gateEngineCall`, `conversations.localFetchAllowed` (schema v30) |
| Anything a model can call; `mutates`; approval policy | `core/ai/…/Tool.kt` |
| Which tools exist; the static catalogue | `core/ai/…/tools/ToolCatalog.kt` |
| Role-play card, scene, codec and limits | `core/engine/…/roleplay/Roleplay.kt` |
| Role-play system prompt, rules, story-brief prompt, start line | `core/engine/…/roleplay/RoleplayPrompt.kt` |
| Role-play card editor; new-scene flow (assistant card or workspace folder); scene settings; card export | `app/…/ui/agents/RoleplayCardEditor.kt`, `app/…/ui/NewSceneDialog.kt`, `app/…/ui/chat/ChatScreen.kt`, `app/…/ui/chat/ChatViewModel.kt`, `app/…/ui/ConversationsViewModel.kt`, `app/…/ui/agents/AgentsViewModel.kt` |
| Role-play persona snapshot; authoring format, story-folder loader and validator tools | `core/engine/…/roleplay/Roleplay.kt` (`RoleplaySnapshot`, card `name`), `RoleplaySpec.kt`, `RoleplayValidation.kt`, `RoleplayStory.kt`, `RoleplayTools.kt`; `conversations.rpSnapshotJson`; `app/…/ui/chat/WorkshopToolsPlan.kt`, `ChatViewModel.pinRoleplaySnapshot` |
| The built-in workshop: entry/hub, kind, helper union, context block, app-owned guards | `app/…/ui/ConversationsScreen.kt`, `ConversationsViewModel.kt`, `app/…/ui/workspaces/`, `app/…/ui/chat/WorkshopToolsPlan.kt`, `WorkshopPrompt.kt`, `ConfigTools.kt`, `ConfigProposals.kt`, `ChatViewModel.buildTools`, `core/db/…/ConversationRepository.kt`, `core/db/…/db/Entities.kt` (`WorkspaceKinds`), `InhumanDatabase.kt` (`MIGRATION_28_29`) |
| Messages written while a turn runs: queue, insertion, turn roots | `app/…/ui/chat/TurnQueue.kt`, `app/…/ui/chat/ChatViewModel.kt`, `app/…/ui/chat/ChatScreen.kt`; `core/engine/…/ChatEngine.kt` (`EngineTurn.beforeRound`) |
| File tools' behaviour | `core/files/…/tools/FileTools.kt` |
| Quotas and guarded reads/writes/lists | `core/files/…/WorkspaceTool.kt`, `WorkspaceLimits.kt` |
| A path becoming a `File`; confinement | `core/files/…/SessionWorkspace.kt` |
| Copying a file between workspaces | `core/files/…/WorkspaceCopy.kt` |
| The share sheet target (one item, copied now) | `app/…/ui/files/SharedContent.kt`, `app/…/MainActivity.kt` |
| Saved system prompts (the library), including the workshop's read/draft helpers and the proposal card's tap-gated save | `core/db/…/SavedPromptEntity.kt`, `SavedPromptRepository.kt`, `app/…/ui/agents/AgentsScreen.kt`, `app/…/ui/chat/ConfigTools.kt` (`app_list_prompts` / `app_read_prompt` / `app_propose_prompt`), `ConfigProposals.kt` (`PromptProposal`), `ChatViewModel.saveProposedPrompt`, `ChatScreen.kt` (`PromptProposalCard`) |
| Follow-up suggestions (`suggest_replies`) | `app/…/ui/chat/Advice.kt`, `SuggestRepliesTool.kt`, `ChatViewModel.kt`, `ChatScreen.kt` |
| The backup reminder | `app/…/ui/backup/BackupReminder.kt`, `MainActivity.kt`, `ui/settings/SettingsScreen.kt` |
| LaTeX rendering (rewrite + transformer) | `app/…/ui/chat/LatexMarkdown.kt`, `MathImageTransformer.kt` |
| OpenAI Responses (typed items, reasoning state) | `core/ai/…/provider/openai/`, `messages.providerState` |
| Thinking / thought signature replay | `core/db/…/MessageEntity.kt`, `ToolCallsColumn.kt`; `core/ai/provider/anthropic/AnthropicMessages.kt`, `gemini/GeminiMessages.kt` |
| Third-party licences and font notices | `app/src/main/assets/THIRD-PARTY-NOTICES.md` |
| The startup fence check; what a failure removes | `core/files/…/WorkspaceSelfCheck.kt`, `app/…/AppCapabilities.kt` |
| Resuming a turn a process death interrupted | `app/…/ui/chat/InterruptedTurn.kt`, `ChatViewModel.continueInterruptedTurn` |
| Import/export and name collisions | `core/files/…/WorkspaceTransfer.kt` |
| HTTP, SSE, decoder, model list, request shape | `core/ai/…/provider/deepseek/` |
| Provider profiles, model cache, key slots | `core/db/…/AgentRepository.kt`, `app/…/ui/chat/SettingsViewModel.kt` |
| Demo mode's script and magic words | `core/ai/…/provider/demo/DemoProvider.kt` |
| Diagnostics and the log file | `core/common/…/diag/`, `app/…/diagnostics/AppDiagnostics.kt` |
| Tables, queries, ordering, migrations | `core/db/…/db/InhumanDatabase.kt`, `Entities.kt` |
| Creating/deleting conversations, orphan sweep | `core/db/…/ConversationRepository.kt` |
| Assistants' persistence and model cache | `core/db/…/AgentRepository.kt` |
| Workspace/rollback directories | `core/db/…/DataPaths.kt` |
| API key at rest | `core/data/…/secret/SecureStore.kt` |
| What the system Files app can do | `core/data/…/provider/WorkspaceDocumentsProvider.kt` |
| Theme, type, hairlines, measure, wordmark | `core/design/…/` |

Three files carry more weight than their size suggests: `SessionWorkspace.kt`
(the confinement gate), `Tool.kt` (the `mutates` / `needsApproval` distinction)
and `ChatViewModel.kt` (where the Android agent loop lives).

## Keeping these current

These docs are part of the change, not an afterthought: a commit that alters a
behaviour described here should alter the description in the same commit. Doc
comments in this repository have been found making claims the implementation did
not honour, and [07-gotchas.md](07-gotchas.md) section C lists the ones that still
stand — which is the argument for the rule rather than an exception to it.

When a documented behaviour changes, update the document in the same commit.
When a historical decision is superseded, leave the archive alone and change the
current document instead.
