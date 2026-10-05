# 07 — Gotchas: what is load-bearing, and what is currently wrong

Read this one when you are about to change something and have not read the rest.
Every entry was verified against the source, and the file and line are given so
you can re-verify cheaply.

Three sections: **A** is rules that will silently break something if undone,
**B** is things that are wrong today, and **C** is places where a comment claims
something the code does not do. Section C is this repository's definition of a
bug, not a nitpick.

---

## A. Load-bearing — undoing these restores a bug

### A1. A failure chunk must be `trySend`-ed *before* `close()`

`DeepSeekProvider.kt:87-102`. A chunk sent after the channel closes is **dropped**,
and the collector then sees the flow end normally. It previously read
`close()` then `trySend(...)`, so every DNS failure, refused connection and TLS
error arrived at the view model as an empty successful stream: no error, no
transcript entry, and a reply bubble deleted for being empty. It looked like the
model had nothing to say. Pinned by `CallbackFlowFailureDeliveryTest`, because the
rule is a fact about the coroutines library rather than about that file.

### A2. The closing `appendMessage` is wrapped in `NonCancellable` when a turn ends

`ChatViewModel.kt:1040`. A cancelled coroutine refuses to suspend, so without it
`appendMessage` throws and the part of the answer already received never reaches
the database — after the user was billed for it.

### A3. `SessionWorkspace.resolve()` is the only place a path becomes a `File`

`SessionWorkspace.kt:41-113`, and the rule is restated in `WorkspaceTool.kt:7-13`.
No symlinks, ever, plus a canonical-prefix check with the `+ separator` that
closes the sibling-prefix hole. Nothing else may construct a path from model
input — including `WorkspaceDocumentsProvider`, which routes its ids through
`resolve` for exactly this reason (`WorkspaceDocumentsProvider.kt:249-256`).

### A4. Message order needs its tie-break, and it is expressed twice

`ORDER BY createdAt ASC, id ASC` in both message queries
(`InhumanDatabase.kt:83-84`, `:86-87`) must equal
`ConversationStore.SAID_ORDER` (`:424-425`). A tool result and the turn that
asked for it routinely share a millisecond. Two rules that disagree would render
the transcript in one order and choose branches by another.

Note the second expression: `ConversationRepository.branches` cannot see
`SAID_ORDER` (it is `private`) and reproduces "newest" as `children.last()`
(`ConversationRepository.kt:279`). If you change one, change both.

### A5. `mutates` and `needsApproval` are different questions

`Tool.kt:43-84`. `mutates` = "can this turn be replayed?" *afterwards*;
`needsApproval` = "may this call happen?" *before*. `mutates` deliberately has
**no default**: a default `false` on a writer leaves regenerate enabled and the
write happens twice. `needsApproval` **takes the arguments**, because "writing a
new file may be fine while overwriting an existing one is not".

### A6. The quota check walks the tree once

`SessionWorkspace.usage()` (`:155-167`). Asking for bytes and count separately
traversed the workspace twice per write: 106 ms per `write_file` in a 2,000-file
workspace, ~86 ms of it walks. `usedBytes()` and `fileCount()` each call `usage()`
again, so calling them in turn restores the cost — which `FilesViewModel.refresh()`
currently does (`FilesViewModel.kt:138-141`).

### A7. Tool-call arguments are fragments, and the first frame carries the opening one

`StreamChunk.kt:69-79` and `DeepSeekStreamDecoder.kt:130-160`. Parsing per
fragment is the most common way tool calling breaks. Emitting only the `Start`
chunk on the frame that opens a call drops the opening argument fragment, and the
assembled JSON then fails with a missing brace — a failure that only appears once
a tool is actually called with arguments. `ToolCallDelta` must also
**concatenate** the name fragments (`ChatViewModel.kt:945-949`); keeping only the
first produced a tool named `read_` instead of `read_file`.

### A8. `ToolCallStart.index` is the provider's ordering key, not arrival order

`StreamChunk.kt:56-62`. Parallel calls stream interleaved; keying on arrival order
silently corrupts every call after the first.

### A9. Reasoning must be persisted and replayed whenever thinking was on

`ChatTypes.kt:91-117`, measured: thinking on with `reasoning_content` omitted is
**HTTP 400 whether or not tools are present**. The documented rule ties it to
tools; the measurement says it follows the thinking state. A transcript that
drops reasoning works right up until a thinking turn is replayed, then fails with
a 400 that points at the wrong thing.

### A10. `image_url` is an object, not a string

`DeepSeekRequest.kt:114-124`. The bare-string form returns 422. And **vision is
checked before the image is built**, because sending an image to a text-only
model returns **200** and the model answers about `[Unsupported Image]` while
sounding like it looked (`ChatTypes.kt:55-61`).

### A11. `mutated` is stored, and written *after* the row exists

`Entities.kt:298-318`. Derived flags would silently reclassify history when a
tool is renamed or removed, and then re-run a real write. `markMutated` is a
separate statement (`InhumanDatabase.kt:101-108`) because tools run after the
stream closes; without it the guard disappears on restart.

### A12. `ToolsColumn` garbage decodes to "every tool"

`Entities.kt:163-182`: `""` → `null` → everything; `"[]"` → nothing; malformed →
`null` → **everything**. A typo widens permissions rather than narrowing them.
That is the safe direction and it is deliberate.

### A13. `null` and `[]` tool selections are different answers

`ToolCatalog.kt:66-92`. `null` = everything including tools added later; `[]` =
nothing. Collapsing them makes one of the two impossible to express.
`allSelection()` returns **`null`**, not the full list. A new assistant gets the
explicit ten-name list instead (`AgentRepository.kt:85-98`).

### A14. The workspace is opened with `mkdirs()` before the row is inserted

`ConversationRepository.kt:139-140`, and the delete does the reverse (directory
first, rows second, `:147-159`). That ordering is what makes the orphan sweep
safe: an interrupted operation leaves a directory with no row, which the sweep can
find, rather than a row pointing at a missing directory, which nothing can.

### A15. `runBlocking` appears only around two reads in the provider

`WorkspaceDocumentsProvider.kt:40-45`. Every method runs on a Binder thread that
DocumentsUI blocks on, hence `MAX_CHILDREN = 500` and the caps. The two are
`conversations().byId(documentId)` (`:209`), a primary-key lookup, and
`conversations().all()` (`:113`), a full scan of a table holding one row per
conversation.

### A16. The demo script reads the transcript, not a counter

`DemoProvider.kt:131-146`. A provider is stateless; a counter would desynchronise
the moment a reply was regenerated or a branch switched, then call delete on a
file that is already gone.

### A17. Vertical rhythm lives in `Layout.Space`

`Layout.kt:50-92`. It was literals once, and the transcript spent more vertical
space on the gaps than on the things: a one-line collapsed tool result stood 53dp
tall with an 18dp gap.

---

## B. Wrong today — verified defects

These are real and current. None of them is a style opinion.

### B1. The regenerate control is disabled for the rest of the session after any mutating turn

**The one most likely to be reported as a bug.**

There are two things named `currentTurnMutated` and the UI reads the wrong one.
The private **function** (`ChatViewModel.kt:536-541`) is correct — it scopes to
the last user message, so "a file written three exchanges ago is not rewritten by
answering again, and must not block anything". The **state flag**
(`ChatUiState.kt:101`, read at `ChatScreen.kt:171`, used at `:852`) is assigned in
`reload()` (`:498`) and set `true` in `executeCalls` (`:1177`), and **`send()`
never clears it** (verified: `:708-719`).

So after one mutating turn, every later reply says "This reply changed files, so
it cannot be regenerated." Reopening the conversation does not fix it, because
the chat ViewModel is keyed and activity-scoped (`MainActivity.kt:233`) — the same
instance comes back, `init` does not re-run and `reload()` is not called. Only a
process restart or a branch switch recalculates it.

### B2. The last ~40 ms of every reply is missing from the live transcript

`ChatViewModel.kt:867-868` says "The `finally` block publishes regardless, so the
last partial window is never lost." **The `finally` never calls
`publishIfDue`/`updateParts`** — only `updateMessage(… isStreaming, finishReason,
usage)` at `:1020`. Up to 40 ms of tokens are therefore absent from `plainText`,
from `parts` and from the delta channel after the turn ends, while the database
row (written from the `StringBuilder`s at `:1048`) is complete. The bubble stays
short until something reloads the transcript.

### B3. `MarkdownText`'s "switch to the plain renderer when the stream ends" is not implemented

`MarkdownText.kt:48-52` documents a switch. `updateParts` (`:1444-1466`) never
sets `deltas` back to `null`, so the reply keeps rendering through the incremental
renderer until something reloads the transcript. The anti-flicker `retainState`
path (`:126-141`) therefore applies only to transcripts loaded from the database.

Related, and worse for a user: **a rotation mid-stream permanently loses the text
received before it.** The delta channel is a queue with no replay, so a rebuilt
composition starts empty and only sees later deltas — and `text` is ignored while
`deltas != null`. `DeltaCursor(initialText = …)` appears to have been written for
this and is never called with an initial text in the app (`ChatViewModel.kt:875`
builds a bare `DeltaCursor()`; only `DeltaCursorTest` passes one).

### B4. Declining the first of several tool calls persists an unanswered call

`executeCalls` returns immediately on the first decline (`:1128`), but all calls of
the round are already in `toolCallsJson`. Calls after the declined one get no tool
result. `Stop` during `tool.execute` leaves the same shape. The app's own comment
says such a transcript is a request the API rejects (`:1282-1292`), so the next
user message after either is likely a 400.

### B5. The 640dp measure cap was inert at seven call sites — **FIXED**

`fillMaxWidth()`/`fillMaxSize()` before `widthIn(max = 640.dp)` sets
`minWidth = maxWidth = incoming max`, so the following `widthIn` clamps back and
the cap does nothing. The sites were: `WideDialog.kt:86-87`,
`AgentsScreen.kt:123-124`, `FilesScreen.kt:206-207`,
`ConversationsScreen.kt:315-316` and `:450-454`, `SettingsScreen.kt:95-98` and
`:189-190`; two of them (`WideDialog` and `ConversationsScreen`) carried comments
claiming the cap is what makes the tablet layout readable, which is section C's
problem in its purest form — the comment described behaviour the code did not
have.

All seven now constrain first and fill second, plus a check that no new site
reintroduces the order. The chat screen's two sites (`ChatScreen.kt:349-351` and
`:387-389`) were already correct and carry the original measurement in their
comment (2208px vs 1232px).

**Verified on the tablet AVD** (`emulator-5554`, 2560x1600 @ 320dpi = 1280dp),
measuring the conversations empty state through `uiautomator`:

```
text column    1152px = 576dp, centred at 1280px (the screen centre)
```

576dp is `ReadableMeasure` (640dp) less the 32dp gutter on each side, which is
the expected result and exactly what the cap was supposed to produce. Before the
fix this text ran the full 2560px.

### B6. The provider can rename or delete a conversation's folder, and lose the files

`WorkspaceDocumentsProvider.directoryFlags()` (`:234-237`) is returned for
conversation rows too (`:190`), so the system Files app offers rename and delete on
a conversation folder:

- `renameDocument` renames the directory whose name **is** the `workspaceId`
  (`:162-169`). The row keeps pointing at the old name, so the next tool call
  recreates an *empty* workspace — and the renamed directory matches no row, so
  the sweep will eventually delete the user's files.
- `deleteDocument` deletes the workspace and leaves the row (`:156-160`) — the
  inverse orphan, which the sweep cannot see.

Also: the conversation-folder branch of `includeDocument` is **dead code**, because
`includeConversation` publishes `workspaceId` as the document id (`:184`) while
`includeDocument` looks a bare id up with `conversations().byId(id)` (`:209`),
which matches the conversation id — a different UUID. A re-queried conversation
folder therefore reports its display name as a raw UUID rather than the title.

### B7. `import` is a quota side door

`WorkspaceTransfer.kt:38-80` enforces only `maxWriteBytes`. No `maxTotalBytes`, no
`maxFileCount`, no free-space check. Its failure path deletes the target
(`:74-79`), which for a destination resolving to an existing empty directory
deletes that directory.

### B8. `schemas/…/1.json` is not a real v1

`core/data/schemas/…/1.json` and `2.json` differ only in the `version` field, and
`1.json` already declares `toolCallsJson`, `toolCallId` and `toolName` — the three
columns `MIGRATION_1_2` adds. So the exported v1 is v1's schema re-taken after the
entities gained those fields. The migration is correct for a genuine
pre-migration database; the artefact is not. Anything that validates 1→2 against
this file is validating a fiction. There is **no migration unit test** in the
tree — no `MigrationTestHelper`, no `room-testing`, no `androidTest` source set in
`:core:data`; the only check is the device script
`tools/seed-and-verify-migration.ps1`.

### B9. `SecureStore.hasUsable` is not name-accurate, and the demo vision check disagrees with the composer

`SecureStore.kt:145` defines `hasUsable` as `get(key) != null`, so an empty stored
value reads as configured while `send()` rejects it. The app's own settings screen
never writes one — `SettingsViewModel.save()` removes the key when the field is
blank — so reaching that state needs a value written outside the UI.

Separately, in demo mode the composer's attach button is **enabled** (the demo
model advertises image input, and `publishModelState` falls back to the demo model
list) while `attachImage` consults only `agents.models()` and refuses without a
key (`ChatViewModel.kt:386-402`) — so picking an image always yields "The model in
use cannot read images."

### B10. `FilesViewModel` blocks the main thread and walks twice

`runBlocking` for a database read (`:96-100`) on every refresh/open/delete/import,
and `refresh()` calls `usedBytes()` and `fileCount()` (`:138-141`), which is two
tree walks — the exact cost A6 exists to remove.

### B11. Smaller ones

- `ModelPickerDialog`'s error branch is dead: `reloadModels()` swallows failure
  and returns an empty list (`ChatViewModel.kt:333-334`), so a key-less user sees
  an empty picker with no explanation. Selecting a model also does not dismiss the
  dialog.
- A failed turn with no content leaves no trace: the placeholder is removed and
  nothing is persisted (`ChatViewModel.kt:1067`), so only the dismissible error row
  shows it.
- `pendingApproval` is a single field, so several approval-needing calls produce
  **sequential** dialogs and the `pending` parameter is never read
  (`:1200-1205`) — contradicting the comment at `:768-769`.
- `DeepSeekSearchProvider.DEFAULT_MODEL` is `"deepseek-v4-flash"`
  (`DeepSeekSearchProvider.kt:156`), which is **not** an id the provider
  advertises; a live `GET /models` returns `deepseek-flash`
  (`DeepSeekModelsTest.kt:33-53`). The search provider is never given the
  conversation's model. Check here first if search starts failing with a 400 naming
  the model.
- `ConversationsScreen`/`AgentsScreen` list orderings have no `id` tie-break, so
  rows with equal `updatedAt` can swap between emissions
  (`InhumanDatabase.kt:19-20`, `:159-163`).
- Dead code, verified repo-wide: `DeepSeekProvider.json`, `DeepSeekRequest.EmptyArray`,
  `ReasoningType.Raw`, `RemoteModel.maxOutputTokens` (read only by
  `DeepSeekModelsTest`), `ConversationRepository.observeConversation`,
  `MessageDao.update`, `MessageDao.delete`, `ConversationDao.observe`,
  `SecureStore.has`, `Database.DEFAULT_SYSTEM_PROMPT` (a duplicate of the
  companion's), `DeltaCursor.seen`, `TurnOutcome.text/reasoning/finish/failure`,
  `FilePreview.truncated`. `SseFrame.id`/`retryMillis` are read only by
  `SseParserTest`, and `StreamChunk.Raw` is never emitted — its only reference is
  the `when` branch that ignores it (`ChatViewModel.kt:989`).
- `ChatViewModel`'s `replyDeltas` doc comment is copy-pasted twice (`:124-145` and
  `:146-173`), and the KDoc for `regenerate` (`:507-517`) and `selectBranch`
  (`:602-608`) are each followed by a second KDoc block, so neither documents
  anything.
- `DeltaCursor.seen` is written three times and never read
  (`DeltaCursor.kt:30`, `:45-55`).
- `TokenFormat` avoids `String.format` for the Turkish-locale reason
  (`ContextUsage.kt:122-124`), but `UsageLine.kt:102,114,143-144` and
  `ChatScreen.kt:1039` use it anyway.

---

## C. Comments that contradict the code

By this repository's standard, the comment is the bug. These were all verified.

| Comment | Reality |
| --- | --- |
| `ChatViewModel.kt:867-868`: "The `finally` block publishes regardless" | It never calls `publishIfDue`. See B2. |
| `MarkdownText.kt:48-52`: the renderer switches when the stream ends | `deltas` is never nulled. See B3. |
| `WideDialog.kt:41-46`: "The cap is `Layout.ReadableMeasure`, so the dialog grows… and then stops" | The cap is inert — `fillMaxWidth` comes first. See B5. |
| `ConversationsScreen.kt:452-454`: "The measure cap is what makes the tablet layout readable" | Same, and the empty state's own order is the wrong one. See B5. |
| `WorkspaceTool.kt:63-65`: "A ranged read never loads the whole file" | `file.readText()` is called before the slice (`:77`), and the ceiling compares UTF-16 chars against a byte limit (`:91-95`). |
| `SessionWorkspace.kt:151-153`: "anything behind a symlink is not charged to its quota" | Only symlinked *directories* are skipped; symlinked files are still counted (`:158-165`). |
| `SessionWorkspace.kt:20-22`: "[root] is never exposed" | `rootPath()` exposes it (`:175-184`) — deliberately, for `StatFs`, with the argument written out. |
| `SessionWorkspace.kt:26-33`: a symlink "can never be swapped in between the check and the open()" | The walk refuses to *traverse* links; nothing stops another same-UID writer creating one, and there is no re-check or `O_NOFOLLOW` at open. |
| `WorkspaceDocumentsProvider.kt:207`: "A bare workspace id is a conversation" | The lookup underneath is `byId`, which matches the *conversation* id. See B6. |
| `SearchProvider` in `WebSearchTool.kt:10`: "an interface with nothing behind it yet" | `DeepSeekSearchProvider` has been behind it since `b4e503f`. |
| `Tool.kt:73`: "There are eight tools" | `ToolCatalog.all` has ten. |
| `Tool.kt:81-82`: "Anything that writes, deletes or reaches outside the workspace should override this" | Only `delete_file` overrides `needsApproval` — which `Tool.kt:62` itself concedes. |
| `AgentsScreen.kt:723-738`: "Hand-rolled rather than pulling in the FlowRow API" | It calls `androidx.compose.foundation.layout.FlowRow`. |
| `ChatScreen.kt:419-422`: raw JSON is "practically useless" | The dialog prints the raw `argumentsJson` seven lines later (`:429-443`). |
| `themes.xml:9`: "a bare Material3 parent" | It is the platform `android:Theme.Material.NoActionBar`, with a hard-coded dark `windowBackground` while a light scheme exists. |

---

## D. Traps in the build and the verification loop

1. **AGP 9 has built-in Kotlin.** No `kotlin-android` plugin, no kapt, and
   compiler options go in a top-level `kotlin { compilerOptions {} }` with
   `jvmTarget` deliberately unset. A comment is at `app/build.gradle:41-52` and
   the reason is in `README.md:109-133`.
2. **`compileSdk = 37` is required** by current AndroidX AAR metadata;
   `platforms;android-37.0` is not in a default SDK install. `targetSdk` is 36 on
   purpose.
3. **The Gradle wrapper points at a local `file:` distribution.** Regenerating it
   needs `tar -a -c -f` — `Compress-Archive` omits directory entries and the
   wrapper's unzip dies on the first file.
4. **`--rerun-tasks` matters.** With the build cache warm, a plain
   `testDebugUnitTest` can report `BUILD SUCCESSFUL` without compiling the file
   you changed. Check that the warning output is empty too.
5. **`xml` comments in `AndroidManifest.xml` cannot contain `--`.**
6. **Build scripts are Groovy, not Kotlin DSL.**
7. **Symlink tests are vacuous where symlinks cannot be created**
   (`SessionWorkspaceTest.kt:151-154`, `:165-167`), and the Windows drive-letter
   test is Windows-only green. The confinement guarantee effectively rests on
   Linux CI.
8. **Parse UI attributes, not quote characters.** uiautomator switches to single
   quotes when a value contains a double quote, which the delete dialog's title
   does. "The title did not change" was reported three times on the strength of a
   value that was never read (`tools/UiDump.psm1:1-17`).
9. **Remember `UiDump.psm1` with `Import-Module`**, not by dot-sourcing: dot
   sourcing runs it without putting its functions in the caller's scope.
10. **`adb shell input` is blocked on some OEM builds** (HyperOS), so a physical
    phone may be deployable and inspectable but not drivable.
11. **Screenshots cannot see inside a stream** — ~700ms per dump against a ~3.6s
    demo reply. Extract the invariant and test it instead.
