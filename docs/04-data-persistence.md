# 04 — Data: Room, repositories, secrets, and the files outside Room

Module: `:core:db` for the schema and the platform-free repositories, `:core:data`
for the Android adapters. `:core:data` re-exports `:core:files`, `:core:ai` and
`:core:db` with `api`, because a conversation's lifecycle spans the sandbox that
guards it, the provider that answers it and the schema that stores it.

`:core:db` is a plain JVM module: it owns the Room entities, DAOs, database,
migrations and the repositories (`ConversationRepository`, `AgentRepository`,
`PolicyRepository`, `SnapshotStore`). The repositories take an `InhumanDatabase`,
a `DataPaths` and -- for the agent repository -- a `SecretStore`, rather than an
Android `Context`. `:core:data` holds the Android side: `Database.open(context)`,
the Keystore-backed `SecureStore`, the encrypted backup and the
`DocumentsProvider`. This is what keeps the repositories testable on a plain JVM:
a test can create a conversation and append messages against the same schema and
directory layout without a device.

The app installs `InhumanDatabase.MIGRATIONS` and
`InhumanDatabase.callback()`: Android's `Database.open(context)` adds the
`Context` builder. The callback seeds the DeepSeek provider and the default
assistant on create, so an install does not start from an empty schema.

## The schema, version 27

`InhumanDatabase` (`core/db/src/main/kotlin/com/inhuman/core/data/db/InhumanDatabase.kt`):
`version = 27`, `exportSchema = true`, database file `inhuman.db`. Schemas are
exported to `core/db/schemas/` by KSP — configured in `core/db/build.gradle`
with the reason stated: without it the schemas exist only inside the build
directory and are lost on clean, which makes a missed migration impossible to
spot.

Twelve entities in five files: `ProviderProfileEntity`, `WorkspaceEntity`,
`ConversationEntity`, `MessageEntity`, `AgentEntity` and
`ContextSummaryEntity` in `db/Entities.kt`, the three approval-policy tables
(`ApprovalDefaultEntity`, `AgentApprovalEntity`, `PathPolicyEntity`) in
`db/PolicyEntities.kt` (added by migration 7→8), the approval audit
(`ApprovalAuditEntity`) in `db/ApprovalAuditEntity.kt` (14→15), the
saved-prompt library (`SavedPromptEntity`) in `db/SavedPromptEntity.kt` (15→16),
and the maintenance cost ledger (`MaintenanceRunEntity`) in
`db/MaintenanceRunEntity.kt` (22→23).

### `messages.reasoningSignature` — added in 16→17

The opaque signature Anthropic streams after a thinking block. Nullable and
additive: an old row, or a row from any other provider, has none. The Anthropic
adapter treats a missing signature on a reasoning/tool turn as "thinking cannot
be enabled for this history" and falls back to a thinking-off request, because
replaying the block without it is rejected. Tool-call signatures (Gemini's
`thoughtSignature`) live inside `toolCallsJson` per call rather than in a
separate column, since they belong to the call records.

### `messages.providerState` — added in 17→18

A JSON array of opaque provider input items that belong to one assistant row.
OpenAI Responses with `store=false` returns reasoning items whose
`encrypted_content` must be passed back to continue a tool turn; the adapter
writes those raw items here and replays them before the row's function calls
when reasoning is enabled. The column is nullable and never interpreted by the
app: Anthropic and Gemini use their own typed fields, and an old row has no
state. It is not displayed and not logged.

### `agents.suggestion*` — added in 18→19

Four columns on the assistant, all nullable except the format: the provider
profile, model, prompt and output shape for `suggest_replies`. Null means
"inherit the assistant's own provider/model/prompt", so an upgraded install has
no second configuration to explain and the tool is inert until someone selects
it. `suggestionFormat` defaults to `'tool'` and an unknown stored value reads as
`tool`; the suggestion model is given no tools either way, and the format only
chooses how its output is parsed.

### `conversations.workspaceInstructions` — added in 20→21

The rendered `AGENTS.md` block pinned for the conversation, stored whole so the
system message is not rebuilt from a file that may have changed. Three states:

| Value | Meaning |
| --- | --- |
| `NULL` | Not pinned yet. The first request pins the file (or `""` if there is none); a preview reads the file live but does not pin. |
| `''` | Pinned with no instructions: the file was missing, blank or unreadable at pin time. Adding one later requires the chat menu's reload -- it is not picked up automatically. |
| block text | Pinned instructions. The app sends this exact string; editing the file does not change the conversation. |

The block includes its header and `BEGIN/END AGENTS.md` markers. Nullable and
additive: an upgraded row is unpinned and pins on its next request; a new
conversation pins on its first. It is configuration, not a message, so it does
not appear in the conversation Markdown export; the encrypted backup carries it
with the row, and the workspace zip carries the file itself.

### `conversations.sshHostsJson` — added in 21→22

The ids of the saved SSH hosts this conversation may use, encoded by
`HostsColumn`. Empty string and `[]` both mean **no hosts**; there is no "all"
state, unlike `toolsJson`, because adding a host in Settings must not silently
widen a conversation. The column holds ids only: the hosts themselves (address,
user, credentials) live in `SecureStore` as one JSON document
(`ssh.hosts`), so a database backup carries which machines were selected but
never how to reach them. Only the chat menu's host sheet writes this column, a
change applies to the next turn, and it resets `sshAllowed`.

### `conversations.sessionMode`, summary coverage, and `maintenance_runs` — added in 22→23

Three changes that make a continuous session auditable without moving the record:

- **`conversations.sessionMode TEXT NOT NULL DEFAULT 'classic'`** — `classic`
  keeps today's manual compaction; `continuous` lets the app maintain the
  request projection. Read through `SessionMode.parse`, which maps an unknown or
  blank value to `classic`: a typo must not turn on automatic spend. New
  conversations default to the saved Create-sheet preference, but the column
  itself defaults to classic so a caller that forgets the argument fails toward
  doing nothing.
- **`context_summaries` gains `kind`, `coveredTurns`, `coveredMessages`,
  `summaryTokens` and `truncated`** — the brief is still a derived projection,
  and the new columns describe it rather than the transcript. Coverage is
  nullable: a manual summary written before this version recorded no coverage,
  and `0` would claim it covers nothing. `truncated` means the brief was cut to
  fit, and the session-memory sheet shows it.
- **`maintenance_runs`** — one row per request the app spent maintaining a
  projection: `kind` (auto/manual/rebuild), model, provider-reported input and
  output tokens, omitted messages/turns, outcome and a short failure reason.
  It has a cascade foreign key on the conversation and an index on
  `(conversationId, at)`. Rewinding a turn does not delete its rows: the money
  was spent, and the usage overview reads this table because a maintenance call
  has no message row to carry its tokens. Deleting the conversation removes
  them.

The brief stays in `context_summaries` (one row per conversation, overwritten);
the cost ledger is append-only. `ContextSummaryEntity.kind` is `manual` for the
classic compaction path, so the old behaviour and its UI are unchanged.

### `agents.rpJson` and `conversations.sceneJson` — added in 23→24

Two additive text columns with an empty default. Empty means "not role-play":
an upgraded assistant stays a plain assistant and an upgraded conversation
stays an ordinary conversation, so nothing changes until the user edits an
assistant or starts a scene.

- **`agents.rpJson`** holds the character card as JSON: description,
  personality/voice, default scenario, greeting, example dialogue, boundaries,
  author note, and optional `temperature` / `topP` / `maxTokens`. It lives on
  the assistant because an assistant is already "persona + model + tools +
  approvals", and a card is that row with character content attached; a second
  table would immediately raise which row owns the model. The name is the
  assistant's, not a card field. `RoleplayCodec` decodes it; a blank or
  malformed value reads as "no card" rather than breaking the assistant, and
  `""` is the value that turns the feature off. The fields are capped (16 KiB
  total, per-field limits) because the card is prepended to every request in
  every scene using it; the editor refuses an over-limit card instead of
  truncating it.
- **`conversations.sceneJson`** holds the scene: world, premise, user persona,
  narration format and notes. It belongs to one conversation because the same
  character can be dropped into unrelated stories. Like the card it is prompt
  content, not capability: no tool, approval, SSH or workspace check reads it,
  and a malformed value reads as "no scene".
- **A scene with every field blank is `{}`, not `""`.** `""` keeps meaning "this
  conversation is not a role-play scene"; storing a blank scene as `""` silently
  turned it back into an ordinary conversation, which renamed it after the first
  message and made the persona snapshot unreloadable. `RoleplayCodec.decodeScene
  ("{}")` is an empty scene, which is what a blank New-scene dialog means. The
  `setScene` writer normalises it too, so clearing a scene's fields does not
  remove the scene.
- **The greeting is a message, not a column.** `ConversationRepository.create`
  accepts a `greeting` and writes it as the only root **assistant** message in
  the same transaction as the conversation, so the transcript says who spoke.
  A history that would start with an assistant turn is made legal on the wire
  by `RoleplayPrompt.KICKOFF`, never by faking a user row.
- **Scene creation passes `tools = emptyList()`**, which stores `"[]"` (a real
  "no tools" selection). `""` would mean every tool, and `null` would copy the
  assistant's set; both are wrong for a scene default. The conversation tool
  sheet remains the only post-creation writer.
- **Editing a scene does not bump `conversations.updatedAt`**, for the same
  reason as the tool or session-mode setters: setting is configuration, not new
  conversation activity.

### `messages.injected` — added in 25→26

A boolean column, false by default, marking a user message the user sent *while
the turn was already running* and that the engine showed to the model before the
next round (`EngineTurn.beforeRound`). The default is the contract: a transcript
written before this release contains no insertions, so every existing user row
stays a turn root and every Rewind gate keeps working.

The row is a real user message -- it is what the model read, and search/export
render it as the user's words -- but it is not a new turn. Turn grouping,
the turn jump list, the rewind gate (`MessageTurnRow.injected`),
compaction/projection boundaries (`ProjectionMessage.injected`) and
`continueInterruptedTurn` all read the flag; `docs/07-gotchas.md` A29 lists what
each one would break without it. The queue of not-yet-delivered messages is *not*
in the database: it lives in the view model, exactly like the composer draft,
because neither has ever been persisted and a durable queue would need a
reconciliation with the turn it was queued against.

### `conversations.rpSnapshotJson` — added in 26→27

The frozen role-play persona of a scene: the assistant prompt, display name and
character card as JSON (`RoleplaySnapshot`). Empty means "not pinned yet": a
scene created before this column existed pins on its first turn after the
upgrade, and a conversation that is not a scene never pins. Once written, the
request projection reads the snapshot instead of the live assistant row, so
editing a character card or an assistant prompt does not rewrite a story
already in progress; the scene-settings dialog's **Reload card and assistant
prompt** replaces it deliberately. The card is kept as JSON text inside the
snapshot so an unknown field from a newer release survives the copy. Prompt
content, not capability: no tool or approval reads the column.

### `saved_prompts` — added in 15→16

| Column | Meaning |
| --- | --- |
| `id` | Row UUID. |
| `title` | What the picker shows. Trimmed by the repository; **unique**, so saving under an existing name updates that entry. |
| `text` | The prompt body, stored verbatim (whitespace can be deliberate). |
| `createdAt`, `updatedAt` | Creation and last save. |

The library is global, not per conversation or per assistant: it is text the
user wrote for assistants, and the assistant row keeps its own copy of whatever
was chosen. No tool reads or writes this table; it enters a request only as the
system prompt the user put in an assistant. The text is never logged, and the
title is not logged either — a title can reveal as much as the body.

### `approval_audit` — added in 14→15

| Column | Meaning |
| --- | --- |
| `id` | Row UUID; the same row moves from asked to its terminal answer. |
| `conversationId` | Foreign key, cascade on conversation delete. |
| `messageId`, `callId` | The assistant row and the provider's tool-call id; deliberately **not** foreign keys. |
| `toolName`, `path` | What was asked; `path` is the normalised workspace-relative path, or null. |
| `rule` | Comma-joined sources: `global`, `assistant`, `path`, `tool`. |
| `decision` | `asked`, `allowed`, `declined`, `timed_out`. |
| `askedAt`, `decidedAt` | Milliseconds; `decidedAt` is null while the dialogue is open. |

The audit is deliberately outside `messages`: it is not sent to the model, not
searchable as conversation content, and has no branch position. Retention
follows the conversation, not the message, so rewinding a turn does not erase
the record that its call was asked about. The update is guarded by
`decision = 'asked'`, so the first terminal answer wins even if a timeout and a
late tap race.

### `provider_profiles.modelTags` — added in 13→14

`{"wan2.7-image":["image"]}`, one entry per model the user has labelled.
`core/ai/.../model/ModelCapability.kt` (`ModelTagCodec`) is the only reader and
writer. A model with no entry is *untagged*, and the picker guesses from the id
(`ModelCapabilities.infer`); the column exists because that guess is sometimes
wrong and the correction has to survive a release. The editor carries the value
through every save, so renaming a provider does not wipe its labels.

### `context_summaries` — added in 12→13

| Column | Meaning |
| --- | --- |
| `conversationId` | Primary key. One active projection per conversation. |
| `throughMessageId` | Last active message covered by the summary. Everything after it is sent verbatim. |
| `summary` | Model-written (or demo-local) summary text inserted as a system message. |
| `createdAt`, `updatedAt` | Display and debugging. |

Unlike every other row in the schema, this one is **derived**: the original
`messages` are never removed. Clearing the row restores the full-transcript
request. A boundary that has fallen off the active branch is ignored, so a
branch switch cannot apply a stale summary to the wrong prefix. Deleting the
conversation cascades.

### `provider_profiles` — added in 11→12

| Column | Meaning |
| --- | --- |
| `id` | Stable id; `deepseek` for the seeded profile, a UUID for a custom one. It is also the SecureStore slot suffix. |
| `name`, `kind` | Display name and `deepseek` / `openai-compatible`. |
| `baseUrl` | HTTPS API root; `/chat/completions` and `/models` are appended to it. |
| `reasoningMode` | `none`, `effort` or `deepseek`; which reasoning fields the endpoint accepts. |
| `includeStreamUsage` | Whether the request asks for usage on a streamed response. |
| `manualModels` | One fallback model id per line, for endpoints with no usable `/models`. |
| `isBuiltIn` | The seeded DeepSeek profile cannot be deleted. |
| `createdAt`, `updatedAt` | Ordering and display. |

The key is **not** in this table. It lives in `SecureStore` under
`provider.<id>.api_key`, except the built-in DeepSeek profile, which keeps the
legacy `deepseek.api_key` slot so an existing install continues to work. Deleting
a profile is refused while an assistant or conversation still references it; the
key is removed only after the row is deleted.

### `agents` / `conversations`: `providerId`

`agents.providerId` selects the profile; `conversations.providerId` copies it at
creation, beside `model`, `reasoningEffort` and `thinking`. A conversation keeps
the provider it started with even if the assistant is later edited. Both columns
default to `deepseek` in 11→12, so every existing row keeps working.

### `workspaces` (`Entities.kt`) — added in 10→11

| Column | Meaning |
| --- | --- |
| `id` | Workspace id, an app-minted UUID. It is also the directory name under `filesDir/conversations/`; never derived from the display name. |
| `name` | What the user sees. A permanent workspace keeps this name across renames of its conversations. A temporary workspace is kept in step with its conversation title. |
| `isPermanent` | `0` while the workspace is owned by one conversation and deleted with it; `1` once promoted or created from the Workspaces screen. |
| `ownerConversationId` | The single owner of a temporary workspace; null for a permanent one. A temporary workspace deliberately holds exactly one conversation, so deleting its owner cannot orphan another conversation's files. |
| `createdAt`, `updatedAt` | Ordering and display. |

`conversations.workspaceId` points at `workspaces.id` and is **not unique**: a
permanent workspace is shared. The old unique index encoded the
one-workspace-per-conversation model and is dropped in the v11 migration.

The lifetime rule is in the row, not the directory:

- delete a conversation whose workspace is temporary → delete the rows, the
  rollback snapshots, the workspace directory, and the workspace row;
- delete a conversation whose workspace is permanent → delete only that
  conversation's rows and snapshots; the workspace and its files remain;
- delete a permanent workspace → only when it has no conversations. The UI
  refuses while conversations remain; a cascade is deliberately not offered in
  this version.

### `conversations` (`Entities.kt:120-177`)

| Column | Meaning |
| --- | --- |
| `id` | Row id, an app-minted UUID (`:111`). **Not** the folder name. |
| `title` | Shown in the list; for a temporary workspace, the Files app folder name follows it. |
| `workspaceId` | The `workspaces.id` whose directory this conversation uses. Deliberately not the PK and never derived from the title. Non-unique index `index_conversations_workspaceId`. |
| `agentId` | Nullable, indexed. Which assistant this conversation was started from. |
| `providerId` | The `provider_profiles.id` copied at creation; default `deepseek` in 11→12. |
| `model`, `reasoningEffort`, `thinking` | **Copied from the assistant at creation**, so editing an assistant cannot rewrite what an existing conversation does. |
| `toolsJson` | The tool selection. Copied from the assistant at creation, then editable **by the user only** from the conversation's tool sheet; an edited conversation stores an explicit list and the change applies on the next turn. It is also what makes a transcript's tool calls recoverable: they were legal under this set when they ran. |
| `createdAt`, `updatedAt`, `pinned` | Ordering and list state. |
| `sshAllowed` | The conversation-level SSH acknowledgement, default `0`. Added in 8→9. Per conversation, not per assistant: consenting in one thread must not grant another thread the same capability. Room stores no credential; the address book lives in `SecureStore`, and this column is the conversation's acknowledgement that the agent may open SSH connections at all. |
| `sshHostsJson` | The ids of the saved hosts this conversation is told about by `ssh_hosts`, `[]`/empty meaning none. Added in 21→22. Only the chat's host sheet writes it; a change applies to the next turn and does **not** touch `sshAllowed` — the list is information, not a capability, and no tool resolves it into a connection. Unlike `toolsJson` there is no "all" state: an entry added in Settings must not silently appear in an existing conversation. |
| `downloadAllowed` | The conversation-level URL-download acknowledgement, default `0`. Added in 24→25. Defaulted off is the only safe meaning for a consent flag: an upgraded conversation starts with no grant. Persisted by the first `download_url` consent dialogue and cleared by the chat menu or by turning the global switch off; Room stores no URL or credential. |

### `messages` (`Entities.kt:207-319`)

| Column | Meaning |
| --- | --- |
| `id`, `conversationId` | PK and FK; `ON DELETE CASCADE`, indexed. |
| `parentId` | Tree link. `NULL` = a first turn, i.e. a root. |
| `role` | The **wire** role: `user`, `assistant`, `system`, `tool`. |
| `content` | Visible text. |
| `reasoning` | **Part of the request, not display state.** Replaying a thinking turn without `reasoning_content` is HTTP 400 — see [03-ai-provider.md](03-ai-provider.md). |
| `model`, `finishReason`, `createdAt` | Provenance. |
| `promptTokens`, `completionTokens`, `cachedPromptTokens`, `reasoningTokens` | All nullable, so `0` and "not reported" stay distinguishable. |
| `timeToFirstTokenMs`, `generationMs` | Measured latency. |
| `selectedChildId` | Which child branch is shown, stored **on the parent**. |
| `error` | A failed turn's message. |
| `toolCallsJson` | The assistant turn's requested calls, as a JSON array in a TEXT column — text because it is only ever read and written whole. |
| `toolCallId` | Which call a tool result answers. Answering the wrong one is worse than answering none. |
| `toolName` | For display. |
| `changedFilesJson` | The turn's changed paths, `{"path","kind"}`; written on the final assistant row after tools run. |
| `attachmentsJson` | JSON array of `{"path","mime","size"}` pointing **into the workspace** — never bytes. The path is the prepared image (resized and re-encoded), not the original; bytes are read only when a request is built. |
| `mutated` | Written when a turn actually changed the workspace. |

Two of those columns exist for reasons that are easy to undo by accident:

- **`reasoning` is not a UI nicety.** It is what makes a thinking turn replayable.
- **`mutated` is stored, not derived.** The column's own comment says why: tools
  get renamed and removed, and deriving it by asking today's tools whether a
  recorded call mutates would silently reclassify history — and then a future
  replay-with-tools would write a second time. It defaults to `false` on
  migration, which is itself a judgement: marking every old row as mutating
  would lose a fact about those turns. It no longer gates regeneration; that
  path keeps the definitions for prompt-cache matching but suppresses calls
  (`InhumanDatabase.kt:289-307`).

The **Usage** overview is a projection over `messages`, not a stored ledger.
`MessageDao.usageRows()` selects the four token columns, `toolName`, and
`substr(content, 1, 24)` — enough to recognise the app's unrun-call markers
without loading message text. Summing those rows can therefore show only calls
whose row still exists; a deleted or rewound turn has no row, and a compaction
summary lives in `context_summaries` rather than `messages`. That is why the
screen calls the result usage rather than a bill, and why `docs/13-product.md`
does not promise a lifetime total. The read is deferred until the Usage section
is expanded (`SettingsFold`'s `onExpand`), not run when Settings opens: the
WHERE clause has no index to use and a long install pays a full scan of
`messages` for numbers nobody asked to see.

### `agents` (`Entities.kt:30-88`)

`id`, `name`, `systemPrompt` (stored and shown verbatim), `model` (nullable),
`reasoningEffort` (nullable, **free text** because the levels come from
`GET /models`), `thinking`, `toolsJson`, `createdAt`, `updatedAt`, `isDefault`.
No indices.

### How a tool selection is encoded — `ToolsColumn`

`Entities.kt:163-182`. The encoding distinguishes four stored forms, and the
`""`-versus-`"[]"` distinction is the one that matters:

| stored | decodes to | means |
| --- | --- | --- |
| `""` (`ALL`) | `null` | **every** tool |
| `"[]"` (`NONE`) | `[]` | no tools |
| unparseable JSON | `null` | every tool |
| valid JSON that is not an array (`{}`, `null`, `123`) | `[]` | no tools |

So **a typo in that column widens permissions rather than narrowing them**,
which is the safe direction and is why `decode` returns `null` for garbage
(`:176-180`) — but only for JSON that fails to *parse*. `{}` takes the
`as? JsonArray` branch instead and decodes to `[]`, so the not-an-array case
narrows rather than widens. It is an allow-list, not a deny-list, so a future
tool defaults to "not yet" rather than "already permitted".

## Queries and ordering

`ConversationDao` (`InhumanDatabase.kt:16-65`):

- `observeAll`/`all` are `ORDER BY pinned DESC, updatedAt DESC` — pinned first,
  then most recently touched. **No `id` tie-break**, so two rows sharing a
  timestamp can swap order between emissions.
- `setReasoning(effort, thinking)` is **one** statement, because the two are only
  meaningful as a pair.

`MessageDao` (`:67-154`):

- `observeAll`/`all` are `ORDER BY createdAt ASC, id ASC`. **The `id` tie-break is
  load-bearing**: a tool result and the turn that asked for it routinely share a
  millisecond (`:70-82`).
- `markMutated` (`SET mutated = 1`) is a separate statement issued *after* the
  row exists, because tools run after the stream closes (`:101-107`).
- `branchParents` finds every parent with more than one child in **one** query
  for the whole transcript, rather than roughly two queries per message
  (`:122-147`).

`AgentDao` (`:156-182`): `ORDER BY isDefault DESC, updatedAt DESC`; `delete` is
`WHERE id = :id AND isDefault = 0`, so the default assistant is protected in SQL
as well as in `AgentRepository.delete`. `defaultAgent()` is named that way
because KSP rejects `default` as a Java keyword (`:168-173`).

### The branch walk and the order rule

The transcript order is expressed twice — once in SQL and once in Kotlin — and
they **must** agree:

```kotlin
val order = compareBy<T> { createdAt(it) }.thenBy { id(it) }
```

`InhumanDatabase.kt`, inside `ConversationStore.walkActive`. Two rules that
disagree would render the transcript in one order and choose branches by
another. The Kotlin comparator is `private`, so `ConversationRepository` cannot
use it — which is why `branches()` relies on `childrenOf`'s
`ORDER BY createdAt ASC, id ASC` and takes `children.last()` as "newest". That
is still two expressions of one rule, but both name the same two columns in the
same order, and `ConversationRepositoryTest` pins that the switcher and the walk
agree about which reply is selected.

`walkActive` is a **pure function** — no database, no Android — which is why it
can be tested on the JVM:

- Roots are `parentId == null` sorted by the comparator; the earliest wins if
  there are several.
- It follows `selectedChildId` when that names an existing child, otherwise it
  takes the newest child — which is what a fresh append looks like.
- A `seen` set breaks parent-link cycles.

One walk serves three row shapes, so they cannot disagree about the active
branch: `activePathOf(MessageEntity)` for the transcript, `activePathOfBranches`
for global search's structural projection, and `activePathOfTurnRows` for the
post-turn rewind gate. The last two exist because both callers used to read full
rows — `content`, `reasoning`, tool results — to make a decision about four
columns.

`ConversationStore` (`:401-503`) is where multi-table writes are transactional:
`append` upserts the message, sets `selectedChildId` on the parent, and moves the
conversation's `updatedAt` **at most once a second** (`touchIfOlder`). The
throttle matters because the home screen observes `SELECT * FROM conversations`:
a tool-heavy turn used to re-query the whole list once per appended message for
a timestamp whose only job is ordering. `deleteConversation` deletes the
messages explicitly before the row **even though the FK cascades** — the comment
says that keeps the behaviour obvious if the cascade is ever switched off
(`:496-502`).

## Migration history 1 → 23

All hand-written. There is **no `fallbackToDestructiveMigration` anywhere** and
the reason is stated at `MIGRATION_1_2`: it would delete every conversation on
upgrade — acceptable while nothing has shipped, a data-loss bug the moment
something has.

Line numbers are deliberately absent from this table: eight migrations' worth of
edits have moved them twice, and a wrong line number is read as a wrong claim.
Search for `MIGRATION_n_m` in `InhumanDatabase.kt`; the order in the file is the
order below.

| | What changed |
| --- | --- |
| 1→2 | `messages` gains `toolCallsJson`, `toolCallId`, `toolName` — all nullable, so the migration is additive and no existing row needs interpreting. |
| 2→3 | `messages` gains `timeToFirstTokenMs`, `generationMs`. Stored rather than recomputed, because a figure that only exists in memory vanishes on reload and comparing two models means wanting the older row as much as the newer. |
| 3→4 | `agents` table created; `conversations` gains `agentId`, `reasoningEffort`, `thinking INTEGER NOT NULL DEFAULT 0`; `index_conversations_agentId` created; **and the default assistant is seeded here as well as in `onCreate`**. |
| 4→5 | `messages` gains `attachmentsJson`. |
| 5→6 | `messages` gains `mutated INTEGER NOT NULL DEFAULT 0`. |
| 6→7 | `agents` and `conversations` gain `toolsJson TEXT NOT NULL DEFAULT ''`. `''` decodes to `null` = every tool, so pre-existing assistants keep everything. |
| 7→8 | Three new tables and **no change to any existing one**: `approval_defaults(toolName PK, policy)`, `agent_approval(agentId, toolName PK, policy)`, `path_policy(conversationId, relPath PK, policy)`. Created empty, which is both the correct migration and the correct product default — absence of a rule means no restriction. The statements are written by hand to match what Room generates, and the safety net is Room's own schema-hash check on open. |
| 8→9 | `conversations` gains `sshAllowed INTEGER NOT NULL DEFAULT 0`. Additive and defaulted off, so every existing conversation arrives without SSH consent. `MIGRATION_8_9` is also the first migration with no new table: Room stores no host list or credentials, only this per-conversation acknowledgement; the optional saved host is in `SecureStore`, and tool-call secrets are redacted before the message row is written. |
| 9→10 | `messages` gains `changedFilesJson`, nullable: an old reply has no file list and must not claim one. |
| 10→11 | `workspaces` created, one temporary row per existing conversation, and the unique `index_conversations_workspaceId` replaced by a non-unique one. No directory moves. `ownerConversationId` is the conversation id; the display name starts as its title. |
| 11→12 | `provider_profiles` created and the built-in `deepseek` row seeded with `baseUrl = https://api.deepseek.com` and `reasoningMode = deepseek`; `agents.providerId` and `conversations.providerId` added with default `deepseek`. The legacy chat key slot is reused, so no key is copied and no key is lost. |
| 12→13 | `context_summaries` created, empty: a derived projection, so an upgraded install starts with no compaction and the full transcript. |
| 13→14 | `provider_profiles` gains `modelTags TEXT NOT NULL DEFAULT ''`, so an upgraded install has every model untagged and the picker falls back to the name-based guess. |
| 14→15 | `approval_audit` created, empty: an install that never sees a prompt has nothing to show, and an upgraded one has no old questions to import. The table has one index `(conversationId, askedAt)`, for the history dialogue, and a cascade on the conversation. |
| 15→16 | `saved_prompts` created, empty, with a unique index on `title`. No existing row is interpreted; an install that never saves a prompt keeps a table nobody reads. |
| 16→17 | `messages.reasoningSignature` added, nullable. Old rows read as null, which the Anthropic adapter treats as "do not enable thinking on this history"; an empty string would look like a signature and produce a 400. |
| 17→18 | `messages.providerState` added, nullable: a JSON array of opaque OpenAI Responses reasoning items. It is additive, never interpreted by the app, and null on every old row and every other provider. |
| 18→19 | `agents` gains the four `suggestion*` columns: provider, model and prompt nullable, format `TEXT NOT NULL DEFAULT 'tool'`. Complete by construction — every assistant has an inert default until `suggest_replies` is selected. |
| 19→20 | Data only, no schema change: `provider_profiles` rows whose `baseUrl` is an OpenCode Zen/Go endpoint and whose `reasoningMode` is still the old default `none` become `opencode`. Without it the effort chips appear but `reasoning_effort` is dropped on the wire. A non-`none` mode on the same URL is a deliberate choice and is left alone. |
| 20→21 | `conversations` gains nullable `workspaceInstructions TEXT`: the rendered `AGENTS.md` block pinned on the conversation's first request. Null means unpinned and the first turn pins the file (or `""` if there is none); an upgraded row is unpinned by construction. The column is configuration, not a message, so it is not in the Markdown export but is in the encrypted database backup. |
| 21→22 | `conversations` gains `sshHostsJson TEXT NOT NULL DEFAULT ''`: the ids of the saved SSH hosts this conversation may use. Empty is the safe default — an upgraded conversation has no host selected, so its SSH tools stay unregistered until the user picks one. The earlier single saved host is *not* inferred as a grant to any conversation; it is converted into the host list in `SecureStore` on first read. |
| 22→23 | `conversations` gains `sessionMode TEXT NOT NULL DEFAULT 'classic'`; `context_summaries` gains `kind TEXT NOT NULL DEFAULT 'manual'`, nullable `coveredTurns`/`coveredMessages`/`summaryTokens` and `truncated INTEGER NOT NULL DEFAULT 0`; `maintenance_runs` is created empty with a cascade and `(conversationId, at)` index. Every upgraded conversation is classic and every upgraded summary is manual with unknown coverage — the feature is opt-in per conversation, and an upgrade must not start spending. |
| 23→24 | `agents` gains `rpJson TEXT NOT NULL DEFAULT ''` and `conversations` gains `sceneJson TEXT NOT NULL DEFAULT ''`: the role-play character card and the scene. Empty is "not role-play", so an upgraded assistant stays a plain assistant and an upgraded conversation stays an ordinary one; both values are prompt content only (no capability check reads them), and a malformed value decodes as "not role-play" rather than blocking the row. |
| 24→25 | `conversations` gains `downloadAllowed INTEGER NOT NULL DEFAULT 0`: the per-conversation URL-download acknowledgement. Additive and defaulted off, so an upgraded install behaves exactly as before until the user switches the capability on globally, selects it on an assistant, and consents in a conversation. |

The 7→8 comment records a product change that is *not* in the migration:
deletion no longer asks for approval, and that lives in `DeleteFileTool`, not
here.

The seeding in 3→4 is not redundancy and the comment says so: `onCreate` runs
only when the database file is created, so an existing install migrating to that
version would otherwise arrive with an empty assistant picker. It was found on a
real device. `seedDefaultAgent` binds parameters — the prompt contains
apostrophes — and `INSERT OR IGNORE`s `DEFAULT_AGENT_ID`, and it is called from
both `onCreate` and `MIGRATION_3_4`.

**The migration chain is tested with two different tools.** The device
`MigrationTest` builds old databases from hand-written DDL and runs the steps it
has DDL for (through 12→13), plus "the exported v1 schema can build a database"
and a policy round-trip. That file has no DDL for later versions, so the upper
half lives in plain-JVM tests: `CurrentSchemaMigrationTest` builds a v12
database, opens it through every production migration to the current version and
asserts the newest columns; `ContinuousSessionMigrationTest` runs
`MIGRATION_22_23` directly against a minimal v22 database (an old conversation
reads `classic`, an old summary reads `manual` with null coverage, and
`maintenance_runs` exists and cascades); `CurrentSchemaTest` opens the current
schema and queries the new DAO. `tools/ci.ps1` runs the JVM suite; the device
suite still needs an emulator.

The app-level suite adds 3 tests for web-key isolation; see
[README.md](README.md).

### Two migration traps worth knowing before touching this

1. **`1.json` used to be v2's export re-labelled.** It declared `toolCallsJson`,
   `toolCallId` and `toolName` — the three columns `MIGRATION_1_2` adds — so it
   could not be the schema that migration was written against, and
   `MigrationTestHelper` could not build a v1 database from it (`duplicate column
   name`). Fixed by deleting those columns from the export; `MigrationTest` now
   includes "the exported v1 schema can build a database". One caveat remains:
   the file's `identityHash` is stale, and nothing currently consults it —
   [07-gotchas.md](07-gotchas.md) B8 carries the full story.
2. **Migrations write `DEFAULT 0` for `thinking` (3→4) and `mutated` (5→6) while
   the entity declarations carry no default**, so a column created by migration
   and the same column created by Room from scratch are not identical. It has not
   bitten, but it is the kind of divergence a future `NOT NULL` addition turns
   into a failure.

## Repositories

### `ConversationRepository`

`core/db/src/main/kotlin/com/inhuman/core/data/ConversationRepository.kt`. It
takes the database and a `DataPaths` rather than a `Context`: the directories are
`DataPaths(filesDir)`, so Android opens the same `files/conversations/<id>` and
`files/rollback/<conversationId>` layout it always did. The old constructor
retained `context.applicationContext` for
a stated reason — the caller passes an Activity from a composable while the
repository is held by a view model, and retaining the Activity leaked a destroyed
view hierarchy — which `DataPaths` removes rather than works around.

- `Conversation(entity, messages)` exposes only the **active branch**, not every
  row (`:16-26`).
- `create` (`:117-142`) mints **two independent UUIDs** — the row id and the
  `workspaceId` — copies the assistant's `model`/`reasoningEffort`/`thinking` and
  `ToolsColumn.encode(agent?.tools)`, and `mkdirs()` the directory **before** the
  row insert. That ordering is what makes the orphan sweep safe (`:176-183`).
- `delete` (`:147-159`) removes the **directory first, rows second**. The residue
  is then an orphan the sweep can find; the reverse order would leave rows
  pointing at a missing directory, which nothing can find.
- `selectBranch` is one column on one row. The alternatives are continuations of
  the same point, so switching changes the whole tail (`:212-227`).
- `branches(parentId)` (`:268-290`) returns null for a null parent, null when
  there are fewer than two children, and otherwise falls back to
  `children.last()` when nothing valid is selected — an unknown
  `selectedChildId` is treated as unset, so the `index < 0` guard below it can
  no longer fire.
  `branchesIn` keys the map by **parent id**, so a row looks its own up by
  `parentId` (`:238-259`).
- `autoTitle` collapses whitespace, truncates at 48 characters, and appends `…`
  only when the trimmed source was longer (`:298-305`).
- **`observeConversation` (`:82`) is dead code.** Verified repo-wide: nothing
  collects it; the chat screen uses the one-shot `conversation(id)` instead. It
  would also be the wrong tool — it is built on `messages().observeAll(id)`, so
  Room would only re-emit on a **message** change, and a rename, a pin or a model
  change would not appear.

### `AgentRepository`

`core/db/src/main/kotlin/com/inhuman/core/data/AgentRepository.kt`. It takes the
database and a `SecretStore`; the key slot for the built-in DeepSeek provider is
`SecretStore.KEY_DEEPSEEK_API_KEY`, and Android's `SecureStore` re-exports the
same constant so existing call sites did not move.

- `DEFAULT_NEW_AGENT_TOOLS` is an **explicit 10-name list**, deliberately not
  `null` (`:81-98`). `null` means "every tool, including future ones" — right for
  a pre-existing assistant, wrong as a default, because it would silently grant
  capabilities that did not exist when the choice was made.
- `saveProvider` writes the row and, when supplied, the key; `null` leaves the
  key alone and an empty string removes it. It drops the model cache for all
  providers.
- `providerFromRow` returns null when the profile has no usable key. `models`
  checks a **per-provider** in-memory cache under a `Mutex`, fetches outside the
  lock, and caches only on success. A failed or empty `/models` falls back to the
  profile's manual ids, which have no context/vision metadata.
- `invalidateModels(providerId)` drops one provider, or all when null. It is
  called when a provider or key changes.
- `effortLevels(modelId, providerId)` returns the advertised list **in provider
  order**, never mapped onto a local enum.
- `deleteProvider` refuses the built-in DeepSeek row and any profile still
  referenced by an assistant or conversation. There is no cascade. The key is
  removed after the row.

## `SecureStore`: where the API key lives

`core/data/src/main/kotlin/com/inhuman/core/data/secret/SecureStore.kt`. It
implements `SecretStore`
(`core/db/src/main/kotlin/com/inhuman/core/data/secret/SecretStore.kt`), the
platform-free interface the repository uses, so the repository does not depend on
Android's Keystore.

- Backing store is a **plain `SharedPreferences`** named `inhuman.secure`
  (`:44-48`) holding one value per key: `base64(iv):base64(ciphertext)`. Nothing
  else about the scheme is secret, and the file says so.
- Crypto is `AES/GCM/NoPadding` with a 128-bit tag, alias `inhuman.secret.v1`,
  key generated in `AndroidKeyStore` on first use (`:79-100`). **No
  `setUserAuthenticationRequired`**, so the key survives screen lock and no
  prompt appears on foreground (`:92-96`).
- `get` returns null if the value is missing, is not exactly two `:`-separated
  parts, or on **any** exception during decrypt (`:114-136`). That is the
  intended recovery path, not a swallowed bug: the Keystore key can be
  invalidated by removing the device credential, and a blob can be corrupt.
  `android:allowBackup="false"` means Auto Backup no longer carries the
  ciphertext off the device at all; the null contract stays because an explicit
  export/restore could still move the file, and because key invalidation is
  independent of how the prefs arrived. Null re-prompts the user; an exception
  would fail to start.
- The `SecretKey` handle is cached per process under `@Volatile` + `synchronized`
  (`:68-77`). The previous version did a Keystore round trip on the main thread
  both on screen open and on Send. The ciphertext is still decrypted on every
  call, so an invalidated key still fails in `doFinal` — the cache is of the
  handle, not of the plaintext.
- Keys include the legacy chat key (`deepseek.api_key`), the provider key slots
  (`provider.<id>.api_key`), and the web-tool slots
  (`web.deepseek.api_key`, `web.exa.api_key`, `web.search_backend`,
  `web.fetch_backend`). The web keys are deliberately separate; a chat key never
  authorizes a paid search. The backend preferences are not secrets but live here
  for the same reason `demo.mode` does: one place owns persisted settings with a
  secrecy requirement.
- OAuth uses three more slots: `oauth.config.<provider>` (the user's
  registration), `oauth.tokens.<provider>` (access and refresh tokens) and
  `oauth.pending` (state and PKCE verifier for the one in-flight sign-in). They
  are never included in a backup and never logged. A provider key and an OAuth
  token are alternatives: saving a key forgets the OAuth session, and signing in
  leaves the key unused while the token is present. Deleting the provider
  deletes both.
- The SSH address book lives under `ssh.hosts`, as one JSON document: alias,
  host, port, username and an optional pinned fingerprint per entry, and **no
  password or private key**. It is absent from backups and diagnostics, and no
  tool resolves it into a connection -- `ssh_hosts` only shows the entries a
  conversation was given. The single host an earlier release wrote to
  `ssh.saved_connection` is converted once, keeping its address and dropping its
  secrets.
- `chat.new_conversation_continuous` is the Create sheet's saved default for
  new conversations (`"0"` off; missing or anything else on). It is a UI
  preference, not a secret, and lives here for the same reason `demo.mode` does:
  one place owns persisted settings. It is read only when a conversation is
  created; the chat menu's switch changes `conversations.sessionMode` and never
  this key, so one conversation's exception does not flip every later one. It is
  not in a backup, so a restore returns to "on" for new conversations while
  existing rows keep their own mode.

## The files outside Room

### Layout

```
filesDir/conversations/<workspaceId>/…     one directory per workspace
filesDir/rollback/<conversationId>/<user-id>/
  meta.json
  blobs/<sha256>
  manifests/<checkpoint>.json
```

`DataPaths` (`core/db/src/main/kotlin/com/inhuman/core/data/DataPaths.kt`) creates
the parents on access and joins the id; the Android `Database.workspacesRoot`
remains for the backup code and the instrumentation tests. The name is the opaque
`workspaceId`, never the display name.

The directory name still says `conversations/` for compatibility: migration
10→11 moved no files, and keeping the path meant every existing workspace
continued to resolve. It is a workspace root now; a future pure-path migration
can rename it without touching any row.

Android Auto Backup is deliberately disabled (`android:allowBackup="false"`):
these directories, the database, rollback snapshots, diagnostics and any SSH
identity file kept in a workspace are private content, and backup would upload
them. Moving a device is an explicit export, not an invisible copy.

### Rollback checkpoints

`DataPaths.rollbackDir` is outside `conversations/` on purpose: checkpoints are
not workspace content, must not appear in Files, and must not be reachable by
the sandbox. A turn directory is keyed by the user message id. Its `blobs/` are
content-addressed file versions; a manifest maps relative paths to hashes. The
`start` manifest is captured before the first reversible mutation, and each
completed tool group gets a manifest keyed by its last tool-result message id.
`ConversationRepository` refuses a local mutation if the start capture fails.

This is not one full copy per checkpoint: unchanged files share a blob, so ten
checkpoints after ten small writes store ten small deltas rather than ten
workspaces. Symlinks are refused. Restore validates every blob, builds the
checkpoint in a staging directory beside the workspace, and swaps it in with two
renames (putting the old tree back if the second rename fails); the workspace is
never emptied first, so a restore that fails partway leaves the user's files
exactly as they were. `deleteSubtree`/`deleteDescendants` runs only after a
successful restore.

The chat menu lists the retained turns (checkpoint count, bytes, timestamp) and
the total. Retention (1/3/5/10 turns) is in
`SecureStore.KEY_SNAPSHOT_TURNS`; pruning keeps the newest N turn directories,
always including the current one, and compacts unreferenced blobs. Rewinding a
turn deletes only that turn's directory, leaving older snapshots intact. A
mid-turn regeneration trims manifests that the rewritten transcript no longer
references, so it replaces that turn's checkpoints rather than accumulating
them.

### The orphan sweep

`ConversationRepository.sweepOrphanedWorkspaces` collects the known
**workspace** ids from the `workspaces` table and asks the **pure**
`orphanedWorkspaces(root, known)` which directories are not known. The pure part
is what `OrphanedWorkspacesTest` covers, and one of its cases is the important
one: **a plain file in the root is left alone**, because failing toward deletion
is the wrong direction.

A permanent workspace with no conversations is **not** an orphan and is kept.
A temporary workspace whose owner conversation is gone is stale: its row and
directory are both removed before the directory sweep, so a crash during delete
converges on the next launch.

It runs once at process root in a `LaunchedEffect` on `Dispatchers.IO` wrapped in
`runCatching`, silently, after the first composition (`MainActivity.kt:83-87`).

Safety rests on UUIDs never being reused. For a temporary workspace, `create`
writes the workspace row first, then the conversation row, then `mkdirs()`, so
the sweep cannot see a directory whose owner row is not yet durable.

### `WorkspaceDocumentsProvider`

`core/data/src/main/kotlin/com/inhuman/core/data/provider/WorkspaceDocumentsProvider.kt`
exposes **workspaces** to the system Files app. The document-id scheme is
`root` → `<workspaceId>` → relative segments, deliberately not real paths
(`:47-52`). A bare id is looked up in the `workspaces` table, not in
`conversations`: a permanent workspace can be named by several conversations,
and a `LIMIT 1` conversation lookup would pick an arbitrary one. Nested ids are
resolved through `SessionWorkspace.resolve`, so the provider inherits the
model's confinement rather than being a second, weaker door.

The root lists one folder per workspace, including a permanent workspace with no
conversations and a temporary workspace under its conversation title. Directory
flags on a workspace root are **create only**. The directory name is the
workspace id, so a rename or delete must update the row and the files together;
the app's repository is the only thing that does that. The provider refuses both
operations even if a caller ignores the flag.

Three properties worth knowing:

- `resolveFile` returns an existing directory under `filesDir/conversations`
  without consulting the table, so an old orphaned directory is browsable until
  the sweep removes it.
- For a nested id it calls `SessionWorkspace.open`, which `mkdirs()`s the root —
  probing a non-existent workspace id creates a directory that the sweep later
  removes.
- `resolve` does not require existence, so `queryDocument("<id>/ghost.txt")`
  returns a phantom row that `openDocument` then fails to open.

`queryRoots` advertises `FLAG_SUPPORTS_CREATE`, but `createDocument` on the root
throws because there is no workspace to create into; creation is offered inside
a workspace folder.

## Encrypted backup

`BackupRepository.kt`. Export snapshots the database with `VACUUM INTO` (a
standalone, checkpointed copy, not a live file plus WAL), zips it with
`filesDir/conversations`, and encrypts the ZIP with AES-256-GCM and
PBKDF2-HMAC-SHA256 (210,000 iterations). The header carries the magic, salt and
IV; the passphrase is never stored. API keys and the web/media backend
selections are **not** in the archive because they live in the Keystore-backed
`SecureStore`. Rollback snapshots are omitted;
import clears the current snapshot directory rather than offering a rewind to
files that are no longer present.

Import is deliberately two-phase:

1. The archive is decrypted into `filesDir/backup-restore-staging` and validated
   (SQLite header, path traversal, only `inhuman.db` / `conversations/*`). A
   marker records that a restore is pending.
2. `InhumanApp.onCreate` calls `applyPendingRestore` **before** Room opens. It
   copies the staged files beside the current ones, renames the current database
   and workspace directory aside, installs the staged pair, and only deletes the
   old copies after both renames succeed. A failure restores the old pair. The
   database is installed under `context.getDatabasePath("inhuman.db")` —
   `databases/`, **not** `filesDir`; the first version wrote it to `filesDir`,
   which a clean install exposed as a no-op. The app then starts Room normally,
   so migrations run against the restored database.

Before any of that, **Inspect backup** decrypts to a cache directory and opens
the archived database read-only to show conversation/message/workspace counts,
the export time and the database version. It writes no marker, so cancelling a
preview cannot apply the backup later. The passphrase is held only between the
preview and the confirm button.

A successful normal import keeps the replaced database and workspaces under
`filesDir/backup-undo/` and shows **Undo last import**. Undo copies that pair into
the same staging path with `backup-undo.pending`; the next launch swaps it back
and then deletes it. A new import replaces the undo slot. The copy is
app-private and is not part of an export; it exists so a mistaken restore has a
way back.

The UI says the app must restart for this reason. There is no hot restore: no
open Room connection is ever replaced underneath its callers.

### The backup reminder

`ui/backup/BackupReminder.kt` turns the export into something the app can notice
is overdue. It stores four non-secret values in `SecureStore`:
`backup.last_export_at` (written only after a successful export),
`backup.reminder_days` (7/30/90, or 0 for off; default 30),
`backup.tracking_since` (the first launch that ran this code, so an install that
has never exported has a baseline) and `backup.snoozed_until` (three days after
**Later**).

`BackupReminderPolicy.evaluate` is pure: no data, off, a pending snooze, a
clock that moved backwards or an age under the period produce null; otherwise it
returns the age and whether it is measured from first use. The home screen asks
once when it becomes visible and after returning from Settings. A due reminder is
a banner with **Back up** and **Later**; Back up opens Settings with the backup
fold expanded.

**It never writes a backup.** A useful archive needs the user's passphrase, and
the app intentionally keeps neither the passphrase nor a copy of the archive; a
background writer would have to invent a credential store and a retention
policy, both unresolved product questions. So there is no WorkManager job and no
notification: the reminder is evaluated when the app is opened, and the export is
still the button in Settings. Recording the export time only on success also
means a failed or cancelled export does not clear the reminder.

## Tests in this module

The JVM and device files below:

| Test | Locks in |
| --- | --- |
| `ConversationStoreTest` | `activePathOf`: straight-line order, the *older* of two replies winning when the parent names it, newest child shown when nothing is selected, a stable order for rows sharing a millisecond, a three-way sibling tie resolving deterministically under input reversal (that case asserts the `id` tie-break is what decides), several roots → earliest, empty → empty, and a parent cycle → empty because no root exists. |
| `OrphanedWorkspacesTest` | An unnamed directory is doomed; a named directory is kept even when empty; a healthy install sweeps nothing; **a plain file in the root is left alone**; a missing root returns empty rather than throwing. |
| `SnapshotStoreTest` | Checkpoint addressing and pruning behind the per-turn rewind. |
| `MigrationTest` (device) | Every migration it has DDL for, through 12→13, including the temporary workspace row, the dropped unique index, and the canary message surviving. |
| `CurrentSchemaMigrationTest` | A v12 database opens through the production migration list to the current version, and the newest columns/definitions are readable. |
| `ContinuousSessionMigrationTest` | `MIGRATION_22_23` directly: an old conversation reads classic, an old summary is manual with unknown coverage, and `maintenance_runs` exists with its index. |
| `RoleplayMigrationTest` | `MIGRATION_23_24` directly: an old assistant's `rpJson` and an old conversation's `sceneJson` both read as empty, which is the "not role-play" state. |
| `ConversationRepositoryTest` | Conversation creation copies settings; `sessionMode` defaults to classic and round-trips; a scene is created with an explicit empty tool selection, its `sceneJson`, and its greeting as the only root assistant row (a blank greeting writes no row); brief coverage fields persist; maintenance runs are newest-first, survive a rewind and die with the conversation. |
| `WorkspaceLifecycleTest` (device) | A temporary workspace dies with its conversation; a permanent one survives and can hold two; promotion changes the row without moving the directory. |
| `ConversationLookupTest` (device) | The `id` / `workspaceId` distinction the documents provider depends on. |
| `WorkspaceDocumentsProviderTest` (device) | A re-queried workspace folder keeps its display name, and delete/rename of the workspace root are refused. |
| `ConversationSearchTest` (device) | Message text, title-first ordering, and literal `%` escaping in global search. |

The cycle case does **not** exercise the `seen` guard — that needs a root whose
`selectedChildId` points into a cycle. Worth adding if you touch that function.
