# 04 — Data: Room, repositories, secrets, and the files outside Room

Module: `:core:data`. It re-exports `:core:files` and `:core:ai` with `api`,
because a conversation's lifecycle spans the sandbox that guards it and the
provider that answers it.

## The schema, version 10

`InhumanDatabase` (`core/data/src/main/kotlin/com/inhuman/core/data/db/InhumanDatabase.kt`):
`version = 9`, `exportSchema = true`, database file `inhuman.db`. Schemas are
exported to `core/data/schemas/` by KSP — configured in `core/data/build.gradle`
with the reason stated: without it the schemas exist only inside the build
directory and are lost on clean, which makes a missed migration impossible to
spot.

Six entities in three files: `ConversationEntity`, `MessageEntity` and
`AgentEntity` in `db/Entities.kt`, plus the three approval-policy tables
(`ApprovalDefaultEntity`, `AgentApprovalEntity`, `PathPolicyEntity`) in
`db/PolicyEntities.kt`, added by migration 7→8.

### `conversations` (`Entities.kt:103-150`)

| Column | Meaning |
| --- | --- |
| `id` | Row id, an app-minted UUID (`:111`). **Not** the folder name. |
| `title` | Shown in the list; also the folder name shown by the Files app. |
| `workspaceId` | The directory name under `filesDir/conversations/`, generated once from a UUID. Unique index `index_conversations_workspaceId`. Deliberately not the PK and never derived from the title — a title can change, and the directory name must not. |
| `agentId` | Nullable, indexed. Which assistant this conversation was started from. |
| `model`, `reasoningEffort`, `thinking` | **Copied from the assistant at creation**, so editing an assistant cannot rewrite what an existing conversation does. |
| `toolsJson` | The tool selection, also copied at creation — and for a second reason: a transcript holds calls that were legal when they ran, so the permission set that produced it has to be recoverable. |
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
| `attachmentsJson` | JSON array of `{"path","mime","size"}` pointing **into the workspace** — never bytes. Bytes are read only when a request is built. |
| `mutated` | Written when a turn actually changed the workspace. |

Two of those columns exist for reasons that are easy to undo by accident:

- **`reasoning` is not a UI nicety.** It is what makes a thinking turn replayable.
- **`mutated` is stored, not derived.** The column's own comment says why: tools
  get renamed and removed, and deriving it by asking today's tools whether a
  recorded call mutates would silently reclassify history — and then a future
  replay-with-tools would write a second time. It defaults to `false` on
  migration, which is itself a judgement: marking every old row as mutating
  would lose a fact about those turns. It no longer gates regeneration; that
  path sends no tools (`InhumanDatabase.kt:289-307`).

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

## Migration history 1 → 9

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

The 7→8 comment records a product change that is *not* in the migration:
deletion no longer asks for approval, and that lives in `DeleteFileTool`, not
here.

The seeding in 3→4 is not redundancy and the comment says so: `onCreate` runs
only when the database file is created, so an existing install migrating to that
version would otherwise arrive with an empty assistant picker. It was found on a
real device. `seedDefaultAgent` binds parameters — the prompt contains
apostrophes — and `INSERT OR IGNORE`s `DEFAULT_AGENT_ID`, and it is called from
both `onCreate` and `MIGRATION_3_4`.

**These now have device tests.** `core/data/src/androidTest/…/MigrationTest` runs
all nine upgrade steps against real SQLite, plus "the exported v1 schema can
build a database" and a policy round-trip. The 8→9 case asserts both that the
column exists and that an existing conversation arrives with `sshAllowed = 0`;
9→10 asserts the changed-files column exists and is null on old messages. The
v10 schema was exercised by installing over the existing database on the pad:
the app migrated and opened its conversation list with no data loss. The
instrumented suite was run on both the pad and the tablet AVD:

```
.\gradlew.bat :core:data:connectedDebugAndroidTest
→ 23046RP50C, 11 tests, 0 failures
→ dsh_tablet(AVD), 11 tests, 0 failures
```

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
- `providerOrNull` reads the key from `SecureStore` on **every** call and returns
  null rather than throwing when unset (`:101-110`).
- `models(force)` checks an in-memory cache under a `Mutex`, fetches outside the
  lock, and caches on success only (`:118-131`). Memory-only on purpose: a stale
  on-disk model list would keep offering models that no longer exist.
  `invalidateModels` is called when the key changes.
- `effortLevels(null)` is empty; otherwise the advertised list **in provider
  order**, never mapped onto a local enum (`:146-150`).

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
  intended recovery path, not a swallowed bug: `app/src/main/AndroidManifest.xml`
  sets `android:allowBackup="true"` with no extraction rules, so a restored
  backup carries the ciphertext while the Keystore key cannot come with it. Null
  re-prompts the user; an exception would fail to start.
- The `SecretKey` handle is cached per process under `@Volatile` + `synchronized`
  (`:68-77`). The previous version did a Keystore round trip on the main thread
  both on screen open and on Send. The ciphertext is still decrypted on every
  call, so an invalidated key still fails in `doFinal` — the cache is of the
  handle, not of the plaintext.
- Keys: `KEY_DEEPSEEK_API_KEY = "deepseek.api_key"` (`:158`) and
  `KEY_DEMO_MODE = "demo.mode"` (`:167`) — the second is not a secret but lives
  here deliberately, so there is one place that knows about persisted settings
  with a secrecy requirement.

## The files outside Room

### Layout

```
filesDir/conversations/<workspaceId>/…     one directory per conversation
filesDir/rollback/<conversationId>/<user-id>/…  last turn's pre-mutation snapshot
```

`Database.workspacesRoot` creates the parent on every call and `workspaceDir`
joins the id (`core/data/src/main/kotlin/com/inhuman/core/data/db/Database.kt:81-88`).
The name is the opaque `workspaceId`, never the title.

### Rollback snapshots

`Database.rollbackDir` is outside `conversations/` on purpose: the snapshot is
not workspace content, must not appear in Files, and must not be reachable by
the sandbox. `RollbackSnapshot` copies the workspace before the first reversible
mutating tool of a turn; `ConversationRepository` refuses that mutation if the
copy fails. The token is the last user message id.

Only the last turn is retained. A new send replaces the snapshot, a successful
rollback discards it after restoring, and deleting a conversation removes the
directory. The copy refuses symlinks rather than following them, matching the
sandbox rule. Restoring deletes the current workspace children and copies the
snapshot back; `deleteSubtree` runs only after a successful restore, so a
restore failure leaves the transcript intact.

### The orphan sweep

`ConversationRepository.sweepOrphanedWorkspaces` (`:188-196`) collects every
`workspaceId` from the table and asks the **pure** `orphanedWorkspaces(root, known)`
(`:207-209`) which directories are not known. The pure part is what
`OrphanedWorkspacesTest` covers, and one of its cases is the important one: **a
plain file in the root is left alone**, because failing toward deletion is the
wrong direction.

It runs once at process root in a `LaunchedEffect` on `Dispatchers.IO` wrapped in
`runCatching`, silently, after the first composition (`MainActivity.kt:83-87`).

Safety rests on UUIDs never being reused. **There is a residual window**: `create`
does `mkdirs()` (`:139`) and only then inserts the row (`:140`), so a sweep
interleaved exactly there would delete the brand-new directory. The comment
acknowledges the ordering as the mitigation, not as a guarantee.

### The document id bug in `WorkspaceDocumentsProvider`

`core/data/src/main/kotlin/com/inhuman/core/data/provider/WorkspaceDocumentsProvider.kt`
exposes each conversation's workspace to the system Files app. The document-id
scheme is `root` → `<workspaceId>` → relative segments, deliberately not real
paths (`:47-52`), and a nested id is resolved through `SessionWorkspace.resolve`,
so the provider inherits the model's confinement rather than being a second,
weaker door (`:249-270`). The first segment is the exception: it is used as a
directory name without going through `resolve` (`:261-262`), so a `..` there
makes the parent directory the "workspace" root.

**One branch of that scheme is broken.** `includeConversation` publishes
`COLUMN_DOCUMENT_ID = conversation.workspaceId` (`:184`), but `includeDocument`
then looks a bare id up with `db.conversations().byId(documentId)` (`:209`) —
and `byId` matches the **primary key**, which is the conversation id, a different
UUID (`ConversationRepository.create` mints both). The comment "A bare workspace
id is a conversation" (`:207`) does not match the key it queries.

Consequence: re-querying a conversation folder (rather than reading it from the
parent listing) falls through to `resolveFile`, so the folder reports its display
name as the raw `workspaceId` UUID instead of the title. The parent listing is
correct; a deep link or a refresh is not.

**The more dangerous half is that conversation folders advertise
`FLAG_SUPPORTS_RENAME` and `FLAG_SUPPORTS_DELETE`.** `directoryFlags()`
(`:234-237`) is returned for conversation rows too (`:190`), and both operations
then act on the real directory:

- `renameDocument` renames the directory whose name **is** the `workspaceId`
  (`:162-169`). The `conversations` row keeps pointing at the old name, so the
  next tool call recreates an *empty* workspace — and the renamed directory now
  matches no row, which makes it an orphan the sweep will eventually delete.
  **The user's files are the thing that disappears.**
- `deleteDocument` deletes the workspace directory and leaves the conversation
  row (`:156-160`). That is the inverse orphan, and the sweep cannot see it: it
  looks for directories with no row, not rows with no directory.

So the Files app is, today, a way to lose a conversation's files without
deleting the conversation. Anything that touches these flags should either
withdraw them for conversation folders or make the row follow the rename.

Three more properties of this provider worth knowing, all visible in the code:

- `resolveFile` returns **any** existing directory under `filesDir/conversations`
  without consulting the table (`:257-270`), so orphaned workspaces are
  browsable and deletable through the Files app.
- For a nested id it calls `SessionWorkspace.open` (`:265`), which `mkdirs()`s
  the root — so **probing a non-existent workspace id creates a directory** that
  the sweep will later delete.
- `resolve` does not require existence, so `queryDocument("<id>/ghost.txt")`
  returns a phantom row (size 0, with the MIME type its extension implies —
  `text/plain` here, not the octet-stream fallback) that `openDocument` then
  fails to open.

And two that are deliberate but surprising: `queryRoots` advertises
`FLAG_SUPPORTS_CREATE` (`:69-74`) while `createDocument` on the root throws,
because `resolveFile(ROOT_ID)` returns null (`:258`) and `resolveDirectory`
therefore does too (`:145-146`) — and `includeConversation` calls `mkdirs()`
(`:182`), a write side effect inside a Binder query, whose stated reason is that
a conversation restored from a backup may have no directory yet.

The provider's own doc comment on `resolveFile` (`:249-256`) is worth quoting
because it is the rule to keep: ids go through `SessionWorkspace` rather than
string joining, "so this provider inherits exactly the confinement the model's
tools get. A provider resolving ids on its own would be a second, weaker door
into the same files."

## Tests in this module

Two files, both JVM, no device:

| Test | Locks in |
| --- | --- |
| `ConversationStoreTest` | `activePathOf`: straight-line order, the *older* of two replies winning when the parent names it, newest child shown when nothing is selected, a stable order for rows sharing a millisecond, a three-way sibling tie resolving deterministically under input reversal (that case asserts the `id` tie-break is what decides), several roots → earliest, empty → empty, and a parent cycle → empty because no root exists. |
| `OrphanedWorkspacesTest` | An unnamed directory is doomed; a named directory is kept even when empty; a healthy install sweeps nothing; **a plain file in the root is left alone**; a missing root returns empty rather than throwing. |

The cycle case does **not** exercise the `seen` guard — that needs a root whose
`selectedChildId` points into a cycle. Worth adding if you touch that function.
