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

One capability can leave the device: `ssh_exec`, which is **off by default** and
requires both the global Settings switch and a per-conversation acknowledgement.
The app still has no host configuration and no credential store; the agent can
only reach a machine whose address and credentials the user supplies. With SSH
off — the default — the paragraph above is true as written. See
[03-ai-provider.md](03-ai-provider.md) and [08-port-philosophy.md](08-port-philosophy.md) §1.2.1.

Single user, single device, DeepSeek only. Closed source.

## Shape of the repository

Six Gradle modules, one of which is a placeholder:

```
app                      UI, theme, entry point, all ViewModels
core/common              platform-free shared code (the diagnostics facade)
core/design              colour, type, theme, layout constants
core/data                Room, repositories, Keystore-backed secrets, DocumentsProvider
core/ai                  provider + streaming + tools      (no Compose, no Activity)
core/files               the workspace sandbox and the 8 file tools
_research/               background investigations, kept as evidence — not compiled
tools/                   PowerShell device-driving and verification scripts
docs/                    this set
```

The rule the split exists to enforce, stated in `settings.gradle`: **a module
with no Android UI must not depend on one.** `:core:ai` is a plain HTTP +
streaming library, which is what keeps provider code testable on the JVM
without a device. Dependencies run one way only — `app` depends on all five
`core` modules (`app/build.gradle:55-59`), and no `core` module depends on
`app` or on another `core` module's UI.

## What works today

Confirmed by `README.md:17-29` and by the code:

- A conversation can be started against a chosen assistant.
- Replies stream, with reasoning shown and a live token/usage breakdown.
- The model **and the user** can read and write files in the conversation's
  folder; files can also be browsed, imported and exported.
- The model can search the web and fetch a page through DeepSeek's paid Search
  and Fetch APIs, using the same key as the conversation
  ([03-ai-provider.md](03-ai-provider.md)).
- The model can run a command on a remote machine over SSH, but only after the
  user turns the global switch on, gives the assistant `ssh_exec`, and allows it
  once per conversation; there is no host list or credential store, and SSH
  tool-call arguments are redacted before storage
  ([03-ai-provider.md](03-ai-provider.md)).
- Approval rules can tighten what the assistant may do, per tool, per assistant
  or per path; the default asks nothing, including for deletion
  ([09-approvals.md](09-approvals.md)).
- Answers can be regenerated — except when the turn changed something; the guard
  is derived from the transcript, so it lifts on the next turn that changes
  nothing ([07-gotchas.md](07-gotchas.md) B1).
- Per-conversation model and reasoning effort, read from the provider.
- Image attachments.
- A context gauge above the composer.
- A local diagnostic log records turns, tool calls, HTTP failures and crashes;
  Settings → Diagnostics can show its size, share it or clear it
  ([10-diagnostics.md](10-diagnostics.md)).

Phases 0–5 in `README.md:17-24` are all marked done. Work since then has been
incremental and is visible in `git log`.

## What is deliberately absent

From `README.md:284-297`; these are decisions, not gaps. Do not "fix" them
without a conversation:

- **No Linux/PRoot environment.** Per-conversation files need no shell. PRoot
  also runs at the app's own UID, so "sandboxed" Linux code could read the
  conversation database and the stored API key.
- **No LaTeX.** The only viable Android renderer is GPLv2.
- **No `MANAGE_EXTERNAL_STORAGE`**, and no `READ/WRITE_EXTERNAL_STORAGE`.
- **No multi-provider abstraction.** DeepSeek only. The streaming pipeline is
  transport-agnostic so a second provider is an addition rather than a rewrite,
  but no abstraction is written for a provider that does not exist.

Two more that are stated as behaviour rather than as policy:

- **No compaction and no trimming.** The whole active branch is sent every turn,
  `ChatRequest.maxTokens` is never set, and nothing is dropped. When a
  conversation reaches the window the API returns a 400, the turn is not
  recorded, and the next message rebuilds the same oversized history — the
  conversation is stuck. The gauge exists to make that wall visible before it is
  hit; it does not remove it (`README.md:81-92`).
- **No rollback.** A reply that used a mutating tool cannot be regenerated,
  because regenerating re-runs the turn and would write a second time. The blast
  radius of a bad rollback is the user's files; refusing to replay cannot
  corrupt anything (`README.md:48-51`).

## The vocabulary this codebase uses

Worth settling before reading the rest, because the words are overloaded:

| Term | Means here |
| --- | --- |
| **assistant** / **agent** | A stored person(a): name, system prompt, model, reasoning effort, and an allowed-tool selection. Managed on the Agents screen. |
| **conversation** | One chat, its branch, and its workspace folder. Copies the assistant's settings at creation so editing an assistant cannot change what an existing conversation may do. |
| **branch** | A regeneration is stored as a sibling branch; the user can switch between them. |
| **workspace** | The per-conversation folder, and the sandbox boundary. |
| **turn** | One user message and everything the model and its tools did in response. |
| **mutates** | "Replaying this turn would change something." Blocks regeneration. |
| **needsApproval** | "This particular call needs the user's consent first." Gates execution. |

`mutates` and `needsApproval` are different questions and neither implies the
other — see [06-agent-loop-ui.md](06-agent-loop-ui.md) and the long argument in
`core/ai/src/main/kotlin/com/inhuman/core/ai/Tool.kt:43-84`.

## The non-obvious thing most people get wrong first

`web_search` and `web_fetch` are **DeepSeek's paid APIs**, not free helpers. Each
is one HTTP request to `https://api.deepseek.com/v1/tools/...`, billed per
successful call (¥5 per 1,000 at the time of writing) on the same account as the
conversation. They are not model turns any more, and the app never opens a page
itself: DeepSeek performs the search and the fetch, so the sandbox still has no
network path of its own. The mechanism and the cost rules are in
[03-ai-provider.md](03-ai-provider.md).

The second non-obvious thing: **`ssh_exec` is not a local file tool.** It runs on
another machine, so its effects are not a workspace copy and cannot be undone by
deleting the conversation. That is why it is the one capability that does not
follow the "default asks nothing" argument, and why it has a separate
once-per-conversation consent instead of only a `ToolPolicy`.
