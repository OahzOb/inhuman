# 04 — Data: Room, repositories, secrets, and the files outside Room

Module: `:core:data`. It re-exports `:core:files` and `:core:ai` with `api`,
because a conversation's lifecycle spans the sandbox that guards it and the
provider that answers it.

## The schema, version 13

`InhumanDatabase` (`core/data/src/main/kotlin/com/inhuman/core/data/db/InhumanDatabase.kt`):
`version = 13`, `exportSchema = true`, database file `inhuman.db`. Schemas are
exported to `core/data/schemas/` by KSP — configured in `core/data/build.gradle`
with the reason stated: without it the schemas exist only inside the build
directory and are lost on clean, which makes a missed migration impossible to
spot.

Nine entities in four files: `ProviderProfileEntity`, `WorkspaceEntity`,
`ConversationEntity`, `MessageEntity`, `AgentEntity` and
`ContextSummaryEntity` in `db/Entities.kt`, plus the three approval-policy tables
(`ApprovalDefaultEntity`, `AgentApprovalEntity`, `PathPolicyEntity`) in
`db/PolicyEntities.kt`, added by migration 7→8.

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
| `sshAllowed` | The conversation-level SSH acknowledgement, default `0`. Added in 8→9. Per conversation, not per assistant: consenting in one thread must not grant another thread the same capability. The app has no host list or credential store; SSH tool-call arguments are redacted before storage, so this boolean is the only persistent state the SSH feature adds. |

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

### `SAID_ORDER` and the active branch

The transcript order is expressed twice — once in SQL and once in Kotlin — and
they **must** agree:

```kotlin
private val SAID_ORDER: Comparator<MessageEntity> =
    compareBy<MessageEntity> { it.createdAt }.thenBy { it.id }
```

`InhumanDatabase.kt:424-425`. Two rules that disagree would render the transcript
in one order and choose branches by another (`:416-423`). It is `private`, so
`ConversationRepository` cannot use it — which is why `branches()` re-implements
"newest" as `children.last()` (`ConversationRepository.kt:279`). That is two
implementations of one rule, and the second is not compile-checked against the
first.

`activePathOf` (`InhumanDatabase.kt:446-478`) is a **pure function** — no
database, no Android — which is why it can be tested on the JVM:

- Roots are `parentId == null` sorted by `SAID_ORDER`; the earliest wins if there
  are several.
- It follows `selectedChildId` when that names an existing child, otherwise it
  takes `children.maxWithOrNull(SAID_ORDER)` — the newest, which is what a fresh
  append looks like.
- A `seen` set breaks parent-link cycles.

`ConversationStore` (`:401-503`) is where multi-table writes are transactional:
`append` upserts the message, sets `selectedChildId` on the parent, and touches
the conversation with the message's own `createdAt`. `deleteConversation` deletes
the messages explicitly before the row **even though the FK cascades** — the
comment says that keeps the behaviour obvious if the cascade is ever switched
off (`:496-502`).

## Migration history 1 → 12

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
| 8→9 | `conversations` gains `sshAllowed INTEGER NOT NULL DEFAULT 0`. Additive and defaulted off, so every existing conversation arrives without SSH consent. `MIGRATION_8_9` is also the first migration with no new table: the SSH feature stores no host list or credential store, only this per-conversation acknowledgement, and tool-call secrets are redacted before the message row is written. |
| 9→10 | `messages` gains `changedFilesJson`, nullable: an old reply has no file list and must not claim one. |
| 10→11 | `workspaces` created, one temporary row per existing conversation, and the unique `index_conversations_workspaceId` replaced by a non-unique one. No directory moves. `ownerConversationId` is the conversation id; the display name starts as its title. |
| 11→12 | `provider_profiles` created and the built-in `deepseek` row seeded with `baseUrl = https://api.deepseek.com` and `reasoningMode = deepseek`; `agents.providerId` and `conversations.providerId` added with default `deepseek`. The legacy chat key slot is reused, so no key is copied and no key is lost. |

The 7→8 comment records a product change that is *not* in the migration:
deletion no longer asks for approval, and that lives in `DeleteFileTool`, not
here.

The seeding in 3→4 is not redundancy and the comment says so: `onCreate` runs
only when the database file is created, so an existing install migrating to that
version would otherwise arrive with an empty assistant picker. It was found on a
real device. `seedDefaultAgent` binds parameters — the prompt contains
apostrophes — and `INSERT OR IGNORE`s `DEFAULT_AGENT_ID`, and it is called from
both `onCreate` and `MIGRATION_3_4`.

**These have device tests.** `core/data/src/androidTest/…/MigrationTest` runs
every upgrade step against real SQLite, plus "the exported v1 schema can build a
database" and a policy round-trip. The latest cases assert that 9→10 is null on
old messages, 10→11 keeps the conversation's workspace and drops the unique
index, and 11→12 seeds DeepSeek while pointing existing conversations at it.
The instrumented suite was run on both AVDs after 11→12:

```
.\gradlew.bat :core:data:connectedDebugAndroidTest
→ dsh_tablet(AVD), 23 tests, 0 failures
→ inhuman_phone(AVD), 23 tests, 0 failures
```

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

`core/data/src/main/kotlin/com/inhuman/core/data/ConversationRepository.kt`. It
holds `appContext = context.applicationContext` (`:76`) for a stated reason: the
caller passes an Activity from a composable while the repository is held by a
view model, and retaining the Activity leaked a destroyed view hierarchy
(`:65-75`).

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

`core/data/src/main/kotlin/com/inhuman/core/data/AgentRepository.kt`.

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

`core/data/src/main/kotlin/com/inhuman/core/data/secret/SecureStore.kt`.

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

## The files outside Room

### Layout

```
filesDir/conversations/<workspaceId>/…     one directory per workspace
filesDir/rollback/<conversationId>/<user-id>/
  meta.json
  blobs/<sha256>
  manifests/<checkpoint>.json
```

`Database.workspacesRoot` creates the parent on every call and `workspaceDir`
joins the id (`core/data/src/main/kotlin/com/inhuman/core/data/db/Database.kt:81-88`).
The name is the opaque `workspaceId`, never the display name.

The directory name still says `conversations/` for compatibility: migration
10→11 moved no files, and keeping the path meant every existing workspace
continued to resolve. It is a workspace root now; a future pure-path migration
can rename it without touching any row.

Android Auto Backup is deliberately disabled (`android:allowBackup="false"`):
these directories, the database, rollback snapshots, diagnostics and any SSH
identity file kept in a workspace are private content, and backup would upload
them. Moving a device is an explicit export, not an invisible copy.

### Rollback checkpoints

`Database.rollbackDir` is outside `conversations/` on purpose: checkpoints are
not workspace content, must not appear in Files, and must not be reachable by
the sandbox. A turn directory is keyed by the user message id. Its `blobs/` are
content-addressed file versions; a manifest maps relative paths to hashes. The
`start` manifest is captured before the first reversible mutation, and each
completed tool group gets a manifest keyed by its last tool-result message id.
`ConversationRepository` refuses a local mutation if the start capture fails.

This is not one full copy per checkpoint: unchanged files share a blob, so ten
checkpoints after ten small writes store ten small deltas rather than ten
workspaces. Symlinks are refused. Restore validates every blob before deleting
anything, then rebuilds the workspace; `deleteSubtree`/`deleteDescendants` runs
only after a successful restore.

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
IV; the passphrase is never stored. API keys are **not** in the archive because
they live in the Keystore-backed `SecureStore`. Rollback snapshots are omitted;
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

## Tests in this module

Three JVM files and five device files:

| Test | Locks in |
| --- | --- |
| `ConversationStoreTest` | `activePathOf`: straight-line order, the *older* of two replies winning when the parent names it, newest child shown when nothing is selected, a stable order for rows sharing a millisecond, a three-way sibling tie resolving deterministically under input reversal (that case asserts the `id` tie-break is what decides), several roots → earliest, empty → empty, and a parent cycle → empty because no root exists. |
| `OrphanedWorkspacesTest` | An unnamed directory is doomed; a named directory is kept even when empty; a healthy install sweeps nothing; **a plain file in the root is left alone**; a missing root returns empty rather than throwing. |
| `SnapshotStoreTest` | Checkpoint addressing and pruning behind the per-turn rewind. |
| `MigrationTest` (device) | Every migration through 10→11, including the temporary workspace row, the dropped unique index, and the canary message surviving. |
| `WorkspaceLifecycleTest` (device) | A temporary workspace dies with its conversation; a permanent one survives and can hold two; promotion changes the row without moving the directory. |
| `ConversationLookupTest` (device) | The `id` / `workspaceId` distinction the documents provider depends on. |
| `WorkspaceDocumentsProviderTest` (device) | A re-queried workspace folder keeps its display name, and delete/rename of the workspace root are refused. |
| `ConversationSearchTest` (device) | Message text, title-first ordering, and literal `%` escaping in global search. |

The cycle case does **not** exercise the `seen` guard — that needs a root whose
`selectedChildId` points into a cycle. Worth adding if you touch that function.
