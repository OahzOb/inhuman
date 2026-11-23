# 01 — Overview

## What the app is

An Android app where **every conversation owns a folder**, and the assistant can
read and write files inside that folder — and nowhere else (`README.md:3`).

The sandbox is the product. It is enforced by the interface rather than
requested in a prompt: a tool never receives the workspace root and never builds
a path itself, so it has no vocabulary for a location outside the conversation
(`core/files/src/main/kotlin/com/inhuman/core/files/SessionWorkspace.kt`, and
`README.md:207-234`). Prompt-level confinement is not the mechanism here and
should never be presented as one.

One capability can leave the device: the seven SSH tools (`ssh_probe`,
`ssh_list`, `ssh_read_file`, `ssh_exec`, `ssh_upload`, `ssh_write_file`,
`ssh_download`), which are **off by default** and require both the global
Settings switch and a per-conversation acknowledgement. The app can optionally
save one host and its credentials in the platform secret store for convenience,
without a security or privacy promise; there is no host list or fleet manager.
The agent can only reach a machine whose address and credentials the user
supplies. With SSH off — the
default — the paragraph above is true as written. See
[03-ai-provider.md](03-ai-provider.md) and [08-port-philosophy.md](08-port-philosophy.md) §1.2.1.

Single user. DeepSeek is the seeded provider; additional
OpenAI-compatible profiles can be configured per assistant. Open source under
Apache-2.0 (`LICENSE`): the fence around a conversation is a security claim, and
an unauditable one would be worth very little (`README.md:359-363`).

## Shape of the repository

Nine Gradle modules; `_research/` and `tools/` are not modules:

```
app                      UI, theme, entry point, all ViewModels
core/common              platform-free shared code (the diagnostics facade)
core/design              colour, type, theme, layout constants
core/data                Android adapters: Keystore secrets, Database.open, backup, DocumentsProvider
core/db                  Room schema + platform-free repositories       (plain JVM)
core/engine              the turn loop (plain JVM)
core/ai                  provider + streaming + tools                (plain JVM)
core/files               the workspace sandbox and the 11 file/document tools (plain JVM)
core/documents           CSV/TXT/DOCX/XLSX read/write, neutral model, loss report (plain JVM)
_research/               background investigations, kept as evidence — not compiled
tools/                   PowerShell device-driving and verification scripts
docs/                    this set
```

The rule the split exists to enforce, stated in `settings.gradle`: **a module
with no Android UI must not depend on one.** `:core:ai` is a plain HTTP +
streaming library, which is what keeps provider code testable on the JVM
without a device. Dependencies run one way only — `app` depends on six of the
seven `core` modules (`core:db` arrives through `core:data`'s API), and no
`core` module depends on `app` or on another `core` module's UI.

## One host

The Android app is the product. The Windows x64 desktop host that consumed the
same JVM core was removed on 2026-11-20 and survives only in git history: it went
unused, and the second host's compile, test and UI work had begun to tax the
Android build for no product reason. The core modules stay plain JVM because that
is what makes them testable without a device, not because a second consumer
exists.

## What works today

Confirmed by `README.md:17-29` and by the code:

- A conversation can be started against a chosen assistant. It uses a temporary
  workspace, or the permanent workspace it was started in.
- Replies stream, with reasoning shown and a live token/usage breakdown.
- The model **and the user** can read and write files in the workspace; files
  can also be browsed, imported, exported, and downloaded from a URL. A
  permanent workspace keeps its files when conversations are deleted, can be
  shared by several conversations, and can be created from the Workspaces
  screen or saved from a temporary conversation's menu. The user can view and
  copy the workspace's absolute app-private path; the model never sees it.
- The model can search the web and fetch a page through **DeepSeek or Exa**,
  selected independently for search and fetch. Each is a paid API with its own
  key, separate from the chat provider
  ([03-ai-provider.md](03-ai-provider.md)).
- The assistant can read Bilibili's public content anonymously — search videos,
  column articles, uploaders and shows; one video's details and related videos;
  one page of comments (video or column), including a single comment's thread;
  column text; an uploader's page; the ranking, popular feed and hot searches; a
  show's season and episode list; a live room's state; and a sample of a video's
  danmaku. No Bilibili account, no key, and the app makes the requests. Off by
  default and `optIn`; while the switch is off those rows are absent from every
  picker, request and gap report. Bilibili's web interface is unofficial, so
  failures are surfaced, not hidden ([03-ai-provider.md](03-ai-provider.md)).
- An **optional read-only Bilibili login** adds `bili_transcript` (subtitles, or
  Bilibili's own AI summary when there are none) and comment pages after the
  first. Settings → 02 / Web & media links it by QR; only the two read cookies
  are stored (never the write token), and the row is absent while no account is
  linked. Account data — history, favourites, followings, messages — is not
  read ([03-ai-provider.md](03-ai-provider.md)).
- The paid media tools — image, video, speech, transcription and decision — are
  configured one by one under **02 / Web & media**: keep the assistant's
  provider, choose a configured Qwen Token Plan provider and model, or enter a
  separate Qwen-compatible endpoint and key. A missing or unsupported backend
  leaves that tool absent rather than failing at call time.
- Chat runs against a provider profile: the built-in DeepSeek endpoint, a
  custom OpenAI-compatible HTTPS endpoint, Anthropic Messages, Google Gemini, or
  OpenAI Responses. Each assistant chooses a profile, and a conversation copies it at
  creation; model lists and keys are per provider. The conversation's model
  dialog can switch provider later. That switch is local to the conversation,
  clears model/effort/thinking, and leaves the assistant unchanged.
- Per-conversation model and reasoning effort, read from the provider.
- The model can run commands and move files on a remote machine over SSH, but only
  after the user turns the global switch on, gives the assistant the SSH tools,
  and allows it once per conversation. Settings holds an address book of hosts
  (alias, address, port, user, optional fingerprint -- no credential), and
  `ssh_hosts` shows a conversation the entries the user ticked; the connection and
  its key or password are supplied in the call itself, and SSH tool-call arguments
  are redacted before storage
  ([03-ai-provider.md](03-ai-provider.md)).
- SSH also has a read-only profile: `ssh_probe` takes a fixed host snapshot,
  `ssh_list` and `ssh_read_file` browse over SFTP without a shell (with optional
  tailing), and `ssh_download` copies one file into the workspace. An assistant
  can use those without `ssh_exec`; `ssh_write_file` adds small text edits, and
  adding command or write access later resets the conversation's consent.
- SSH resource use has user-set ceilings: timeout, output, directory rows and
  text read. The model may request less, never more; omitting the argument uses
  the app default rather than the ceiling
  ([03-ai-provider.md](03-ai-provider.md)).
- A conversation can change which of the assistant's tools it uses, from the chat
  menu, at a turn boundary. The assistant is the ceiling; the model cannot change
  the set; the next turn picks it up
  ([11-conversation-tools.md](11-conversation-tools.md)).
- Approval rules can tighten what the assistant may do, per tool, per assistant
  or per path; the default asks nothing, including for deletion. Every question
  and answer is written to the conversation's approval audit, the dialog names
  the rule that won, and an unanswered question times out as a refusal after ten
  minutes ([09-approvals.md](09-approvals.md)).
- The workspace fence is checked on every start, in a throwaway directory under
  the app cache. A failure -- or a check that cannot run -- removes every
  workspace-bound tool (files, media, SSH) from the request and the pickers and
  says so in the chat; the user's own Files access is unaffected
  ([05-files-sandbox.md](05-files-sandbox.md)).
- A turn cut off by a process death can be resumed from the last saved step: the
  chat offers **Continue** when the branch ends at a user message, a tool result
  or an unfinished tool call, adds no second user message, and reuses the
  original start checkpoint. Text that was still streaming at the kill was never
  persisted and does not come back ([06-agent-loop-ui.md](06-agent-loop-ui.md)).
- Concurrency is bounded, not tuned: one turn per conversation, two turns per
  process (the third is refused rather than queued), and one tool at a time
  inside a turn. Subagents are not implemented, so their ceiling is zero
  ([06-agent-loop-ui.md](06-agent-loop-ui.md), [08-port-philosophy.md](08-port-philosophy.md) Q4).
- A file can be copied from another workspace through Files. The source is
  unchanged, the copy is a snapshot with the same quota and " (2)" naming rules
  as an ordinary import, and nothing is mounted or shared
  ([05-files-sandbox.md](05-files-sandbox.md)).
- Another app can share one text, image or file into a conversation. A visible
  chat is the destination; otherwise a conversation picker appears. The item is
  copied immediately through the same transfer limits, with no import screen
  ([05-files-sandbox.md](05-files-sandbox.md)).
- System prompts can be saved to a library and chosen while editing an
  assistant. The library is text the user wrote for assistants, not context:
  no tool reads or writes it, and choosing an entry only fills the prompt box,
  which stays editable ([04-data-persistence.md](04-data-persistence.md)).
- Replies can end with two or three follow-up chips. Tapping one fills the
  composer and never sends. They come from `suggest_replies`, an opt-in tool
  that sends one extra request to the assistant's suggestion model and keeps its
  call and result in the transcript; it is off by default and must be enabled
  globally and selected on the assistant
  ([06-agent-loop-ui.md](06-agent-loop-ui.md)).
- The home screen can remind you that the encrypted backup is overdue (7/30/90
  days, default 30, or off). It only reminds: the export is still the button in
  Settings, and no background job writes an archive
  ([04-data-persistence.md](04-data-persistence.md)).
- LaTeX formulas (`$...$` and `$$...$$`) are rendered on the device, inline and
  as blocks, using bundled fonts. Nothing is downloaded; code and currency are
  left alone ([06-agent-loop-ui.md](06-agent-loop-ui.md),
  [THIRD-PARTY-NOTICES](<../app/src/main/assets/THIRD-PARTY-NOTICES.md>)).
- Answers can be retried. **Rewind** is the single restart control. **Answer
  again** keeps the tool **definitions** so the request prefix can still hit the
  context cache, but appends a transient system instruction forbidding a call;
  the app refuses any call before execution, so nothing is written or sent over
  SSH. Other Rewind points restart after the user message or any completed tool
  group in the last turn: the prefix and its results stay, the suffix is deleted
  and its checkpoint restored, and the request runs **with tools**
  ([07-gotchas.md](07-gotchas.md) B20, B23).
- Image attachments, chosen from a file — several at once — or taken with the
  camera. There is no app-side count ceiling: the provider's own request limits
  are the boundary. The camera writes into app cache through a dedicated
  FileProvider, and the untouched capture is deleted after its prepared copy is
  staged; it is not added to the gallery.
- **Continuous sessions are the default for new conversations**: the record is
  never deleted, older complete turns are folded into a viewable and rebuildable
  session brief, and maintenance runs only while the conversation is visible.
  In that mode a read-only `recall` tool lets the model search the complete
  active-branch record, so a lossy brief stays recoverable. The classic context
  ring, manual compaction and `CONTEXT COMPACTED` banner remain for conversations
  still in classic mode ([06-agent-loop-ui.md](06-agent-loop-ui.md)).
- A root `AGENTS.md` in the conversation's workspace is pinned on the
  conversation's first request and appended to the system prompt. Later edits
  apply to new conversations, or to the current one through the chat menu's
  reload. It is workspace content, not a capability: it cannot change the tool
  set, approvals or SSH consent. Missing, blank, binary or over-long files are
  ignored; the absolute workspace path never appears
  ([05-files-sandbox.md](05-files-sandbox.md), [06-agent-loop-ui.md](06-agent-loop-ui.md)).
- Long gaps between user messages are marked **on the wire only**: with the
  default setting, a user message at least ten minutes after the previous one —
  and the conversation's first message — is prefixed in the request with its
  send time (`[YYYY-MM-DD HH:mm]`). The stored text, search, export and Rewind
  never contain the prefix; the request viewer does. Settings → **12 / Time**
  chooses after-a-gap (5/10/30/60 minutes), every message, or never
  ([06-agent-loop-ui.md](06-agent-loop-ui.md)).
- A **Usage** section in Settings totals the token counts the providers reported
  and the paid tool calls still recorded, for today/7 days/30 days/all, by local
  day and by conversation. It is derived from the transcript, so deleted or
  rewound turns and compaction summaries are not counted, and no price is
  converted.
- **Plain-text export and share**: one conversation to Markdown (optionally with
  tool calls and results; reasoning is included, attachment paths are listed
  without their bytes), either saved to a user-chosen document, handed to the
  system share sheet, or posted to the configured LAN/Tailscale server's
  `/conversation` endpoint after a preview. A workspace exports to zip from the
  chat menu or the workspace detail screen. None of these is an encrypted
  backup.
- The app starts in **Chinese**. Settings → **11 / Language** switches to
  English, and the system per-app language screen does the same; the platform
  stores the choice, so a later launch does not reset it.
- An encrypted, passphrase-protected backup of the database and workspaces;
  import is staged and applied before Room opens on the next launch.
- A foreground service while a reply is streaming, so a long model/tool turn is
  not killed when the screen goes off. Its notification names the conversation,
  opens it on tap, and offers **Stop**.
- Global search over conversation titles and message text, with jump-to-message
  and a temporary highlight.
- Transient provider disconnects are retried automatically up to five times
  before the error banner appears; the row says which retry it is on, and a
  failed attempt's partial text is discarded rather than concatenated.
- Actionable chat errors: Retry, Compact and retry, or Provider settings, plus
  Copy details; no error is only a dead-end Dismiss.
- Encrypted backup import shows a preview (conversation/message/workspace
  counts, export time) before replacing anything, and keeps the replaced data
  for one **Undo last import**.
- A local diagnostic log records turns, tool calls, HTTP failures and crashes;
  Settings → Diagnostics can show its size, share it or clear it, and
  **Upload log** sits in Settings → Updates and logs beside the server URL
  ([10-diagnostics.md](10-diagnostics.md)).
- The Android app can check a user-run LAN/Tailscale server for an update,
  verify the APK's hash, package, version and signing certificate, then hand it
  to `PackageInstaller`; the system still asks before installing. The same
  server can receive an explicit **Upload log** POST from Updates and logs.

Phases 0–5 in `README.md:17-24` are all marked done. Work since then has been
incremental and is visible in `git log`.

## What is deliberately absent today

From the README's non-goals and planned-work section; these are decisions or
deferred items, not gaps. Do not "fix" the non-goals without a conversation.
LaTeX and native Anthropic/Gemini adapters (including thinking-signature replay)
are implemented in the Android app; see [13-product.md](13-product.md). The
notes below explain what is still absent.

- **No Linux/PRoot environment on Android.** Per-conversation files need no shell. PRoot
  also runs at the app's own UID, so "sandboxed" Linux code could read the
  conversation database and the stored API key.
- **No `MANAGE_EXTERNAL_STORAGE`**, and no `READ/WRITE_EXTERNAL_STORAGE`.
- **Native Anthropic/Gemini basics, thinking-signature replay, OpenAI Responses
  and generic OAuth PKCE exist; no provider OAuth client is bundled.** The
  Anthropic Messages adapter keeps the signed thinking block and replays it while
  thinking is enabled; Gemini keeps per-call `thoughtSignature`s. A history with
  an unsigned old turn falls back to thinking-off rather than a 400. The
  Responses adapter sends typed input items with `store=false`, requests
  encrypted reasoning when thinking is on, and replays the opaque reasoning items
  before the earlier call. OAuth uses a user-registered public client and the
  `inhuman://oauth` callback; no Claude/ChatGPT/Copilot/Kimi client ID ships. None
  is verified against a real account here (that needs a key or a registration).
- **The first batch of document formats is implemented.** CSV/TXT, DOCX, XLSX,
  PDF, DOC and XLS can be read, created, modified and converted through three
  workspace tools. DOC is written into a bundled empty Word 97 template; its
  tables and formatting are flattened and reported. DOCX/XLSX keep package parts
  this app does not understand and report what the neutral model cannot carry.
  The decision is [14-documents-voice.md](14-documents-voice.md).
- **Scanned pages can be read through two opt-in paid tools.** `read_pdf_vision`
  renders at most four pages and sends one image per page to the model that is
  already handling the conversation; `read_scanned` sends the selected pages (at
  most ten, cropped locally from the PDF) or one image to DeepSeek's OCR service
  and returns text. Neither is local OCR, neither saves the uploaded bytes, and
  neither is a hidden fallback: `read_document` still reports "no text layer",
  and the user must select the tool. See [03-ai-provider.md](03-ai-provider.md)
  for the OCR key, limits and tests.
- **Voice input is implemented.** A composer microphone records into
  memory, sends the WAV to the already-configured transcription backend, and
  inserts the text into the composer; it never writes a recording to the
  workspace and never sends automatically. No transcription backend means no
  microphone row, not a row that fails.

Two more that are stated as behaviour rather than as policy:

- **Compaction is explicit.** The whole active branch is sent unless the user
  opens the context gauge and chooses **Compact conversation…**. That keeps the
  last two turns verbatim, asks the model to summarize older complete turns, and
  sends that summary instead of those rows on later requests. The original
  transcript is never edited, a banner makes the projection visible, and **Use
  full history** restores the full request. The summarizer costs one extra call
  on a real provider; demo mode summarizes locally. A single recent turn larger
  than the window can still fail, which is why the status is not "solved" — but
  the old permanent stuck state is gone.
- **Rollback moves the transcript and the local workspace together.** The last
  user message has **Edit your message** in Rewind: its start checkpoint is
  restored, then that message and everything after it are deleted, and the
  text/attachments go back to the composer. A turn containing an irreversible
  tool (SSH) is **not** offered for rollback, because its remote effects cannot
  be undone. Separately, the newest answer can be retried from the same prefix:
  the tool definitions stay (so the context cache can hit), a transient
  instruction forbids calls, and the app refuses any call before execution.
  Other Rewind points can restart after the user message or any completed tool
  group in the last turn: the prefix is kept, the suffix is deleted, its
  checkpoint is restored, and the request runs with tools.
  Checkpoints are content-addressed; the chat menu lists each retained turn with
  its checkpoint count, size and timestamp, and lets the user keep 1/3/5/10
  turns. The same menu sets the per-turn tool-round limit; Settings → **04 /
  Turn limits** exposes the same global value, because it applies to every
  conversation and should not be discoverable only inside one.

## The vocabulary this codebase uses

Worth settling before reading the rest, because the words are overloaded:

| Term | Means here |
| --- | --- |
| **assistant** / **agent** | A stored person(a): name, system prompt, provider, model, reasoning effort, and an allowed-tool selection. Managed on the Agents screen. |
| **provider profile** | A named OpenAI-compatible endpoint and its encrypted key. The seeded DeepSeek profile is built in; a custom profile must use HTTPS. |
| **conversation** | One chat, its branch, and the workspace it uses. Copies the assistant's provider, model and effort at creation so editing an assistant cannot change what an existing conversation may do. |
| **branch** | A regenerated final answer is stored as a sibling branch; the user can switch between them. A mid-turn **Rewind** restart point replaces the suffix instead, because its tool results would no longer match the restored workspace. |
| **workspace** | A folder and the sandbox boundary. A **temporary** workspace is created with its one conversation and deleted with it; a **permanent** workspace survives its conversations and can be shared by several. The absolute directory is visible to the user in Files and workspace detail, but never to the agent. |
| **turn** | One user message and everything the model and its tools did in response. |
| **mutates** | "Replaying this turn with tools would change something." A record, not a blanket regeneration gate. |
| **reversible** | "The app can put this effect back." Local file tools are reversible; `ssh_exec` and `ssh_upload` are not, while `ssh_download` writes a workspace copy and is. Rollback requires it. |
| **needsApproval** | "This particular call needs the user's consent first." Gates execution. |

`mutates` and `needsApproval` are different questions and neither implies the
other — see [06-agent-loop-ui.md](06-agent-loop-ui.md) and the long argument in
`core/ai/src/main/kotlin/com/inhuman/core/ai/Tool.kt:43-84`.

## The non-obvious thing most people get wrong first

`web_search` and `web_fetch` are **paid third-party APIs**, not free helpers.
Search and fetch each choose DeepSeek or Exa in Settings, with a key that is
separate from the chat provider's key. For those two the app never opens a page
itself: the selected backend performs the request, so the sandbox still has no
network path of its own.

The anonymous Bilibili read tools (`bili_search`, `bili_video`,
`bili_comments`, `bili_article`, `bili_up`, `bili_trending`, `bili_danmaku`,
`bili_bangumi`, `bili_live`) are the same rule in its other shape: the app does
open a fixed Bilibili endpoint on the assistant's behalf, with no account and no
key, while the assistant still gets no network and no URL argument. They are off
by default, **absent** while off, and `optIn` on the assistant; a page they
return is untrusted third-party text, not instructions. Prices and rate limits
belong to the provider and can change; the mechanism is in
[03-ai-provider.md](03-ai-provider.md).

The second non-obvious thing: **the SSH tools are not local file tools.** They run
on another machine, so their effects are not a workspace copy and cannot be undone
by deleting the conversation. That is why the capability does not follow the
"default asks nothing" argument, and why it has a separate once-per-conversation
consent instead of only a `ToolPolicy`.
