# 01 — Overview

## What the app is

An Android LLM chat client where **every conversation owns a folder**, and the
assistant can read and write files inside that folder — and nowhere else
(`README.md:3`).

The sandbox is the product. It is enforced by the interface rather than
requested in a prompt: a tool never receives the workspace root and never builds
a path itself, so it has no vocabulary for a location outside the conversation
(`core/files/src/main/kotlin/com/inhuman/core/files/SessionWorkspace.kt`, and
`README.md:207-234`). Prompt-level confinement is not the mechanism here and
should never be presented as one.

One capability can leave the device: the SSH tools (`ssh_exec`, `ssh_upload`,
`ssh_download`), which are **off by default** and require both the global
Settings switch and a per-conversation acknowledgement. The app still has no host
configuration and no credential store; the agent can only reach a machine whose
address and credentials the user supplies. With SSH off — the default — the
paragraph above is true as written. See
[03-ai-provider.md](03-ai-provider.md) and [08-port-philosophy.md](08-port-philosophy.md) §1.2.1.

Single user, single device. DeepSeek is the seeded provider; additional
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
core/engine              the turn loop, shared with the desktop host (plain JVM)
core/ai                  provider + streaming + tools                (plain JVM)
core/files               the workspace sandbox and the 8 file tools  (plain JVM)
desktopApp               Compose Desktop chat and conversation rail, persisted through core:db
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
- Chat runs against a provider profile: the built-in DeepSeek endpoint or a
  custom OpenAI-compatible HTTPS endpoint. Each assistant chooses a profile, and
  a conversation copies it at creation; model lists and keys are per provider.
  The same assistant can be switched later, but existing conversations keep the
  provider they started with.
- Per-conversation model and reasoning effort, read from the provider.
- The model can run commands and move files on a remote machine over SSH, but only
  after the user turns the global switch on, gives the assistant the SSH tools,
  and allows it once per conversation; there is no host list or credential store,
  and SSH tool-call arguments are redacted before storage
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
  or per path; the default asks nothing, including for deletion
  ([09-approvals.md](09-approvals.md)).
- Answers can be retried. **Rewind** is the single restart control. **Answer
  again** keeps the tool **definitions** so the request prefix can still hit the
  context cache, but appends a transient system instruction forbidding a call;
  the app refuses any call before execution, so nothing is written or sent over
  SSH. Other Rewind points restart after the user message or any completed tool
  group in the last turn: the prefix and its results stay, the suffix is deleted
  and its checkpoint restored, and the request runs **with tools**
  ([07-gotchas.md](07-gotchas.md) B20, B23).
- Image attachments.
- A context gauge above the composer, with explicit, reversible compaction of
  older complete turns.
- An encrypted, passphrase-protected backup of the database and workspaces;
  import is staged and applied before Room opens on the next launch.
- A foreground service while a reply is streaming, so a long model/tool turn is
  not killed when the screen goes off. Its notification names the conversation,
  opens it on tap, and offers **Stop**.
- Global search over conversation titles and message text, with jump-to-message
  and a temporary highlight.
- Actionable chat errors: Retry, Compact and retry, or Provider settings, plus
  Copy details; no error is only a dead-end Dismiss.
- Encrypted backup import shows a preview (conversation/message/workspace
  counts, export time) before replacing anything, and keeps the replaced data
  for one **Undo last import**.
- A local diagnostic log records turns, tool calls, HTTP failures and crashes;
  Settings → Diagnostics can show its size, share it or clear it
  ([10-diagnostics.md](10-diagnostics.md)).

Phases 0–5 in `README.md:17-24` are all marked done. Work since then has been
incremental and is visible in `git log`.

## What is deliberately absent

From `README.md:335-357`; these are decisions, not gaps. Do not "fix" them
without a conversation:

- **No Linux/PRoot environment.** Per-conversation files need no shell. PRoot
  also runs at the app's own UID, so "sandboxed" Linux code could read the
  conversation database and the stored API key.
- **No LaTeX.** The obstacle is not the renderer's licence, which this file used
  to name: JLaTeXMath carries a linking exception that permits use here, and the
  app is Apache-2.0. The cost is the fonts it bundles — some GPLv2, some OFL,
  some under the Knuth Licence — and the `THIRD-PARTY-NOTICES` maintenance that
  follows from shipping them (`README.md:337-350`). Markdown and code
  highlighting cover the common case.
- **No `MANAGE_EXTERNAL_STORAGE`**, and no `READ/WRITE_EXTERNAL_STORAGE`.
- **No native Anthropic or Gemini protocol adapter.** Additional LLM providers
  are OpenAI-compatible profiles; a provider with a different wire protocol
  would need its own decoder rather than a base-URL entry.

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
separate from the chat provider's key. The app never opens a page itself: the
selected backend performs the request, so the sandbox still has no network path
of its own. Prices and rate limits belong to the provider and can change; the
mechanism is in [03-ai-provider.md](03-ai-provider.md).

The second non-obvious thing: **the SSH tools are not local file tools.** They run
on another machine, so their effects are not a workspace copy and cannot be undone
by deleting the conversation. That is why the capability does not follow the
"default asks nothing" argument, and why it has a separate once-per-conversation
consent instead of only a `ToolPolicy`.
