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

`Tool.kt:43-84`. `mutates` = "would replaying this with tools change state?
afterwards"; `needsApproval` = "may this call happen?" *before*. `mutates`
deliberately has **no default**: a default `false` on a writer is how a retry
with tools writes twice. Regeneration keeps the tool definitions for prompt-cache
matching but suppresses calls, so this flag is a record about replay-with-tools,
not a blanket refusal. `needsApproval` **takes the arguments**, because "writing a
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
stream closes; without it the record disappears on restart. It no longer gates
regeneration: the final-answer retry keeps definitions but suppresses calls.

### A12. `ToolsColumn` garbage decodes to "every tool"

`Entities.kt:163-182`: `""` → `null` → everything; `"[]"` → nothing; malformed →
`null` → **everything**. A typo widens permissions rather than narrowing them.
That is the safe direction and it is deliberate.

### A13. `null` and `[]` tool selections are different answers

`ToolCatalog.kt:66-92`. `null` = everything including tools added later; `[]` =
nothing. Collapsing them makes one of the two impossible to express.
`allSelection()` returns **`null`**, not the full list. A new assistant gets the
explicit ten-name list instead (`AgentRepository.kt:85-98`).

### A14. Workspace rows and directories are ordered for the sweep

`ConversationRepository.create` writes the temporary workspace row first, then
the conversation row, then `mkdirs()`; `delete` removes the directory first and
the rows second. That ordering is what makes the orphan sweep safe: an
interrupted operation leaves a directory with no workspace row, which the sweep
can find, rather than a row pointing at a missing directory, which nothing can.
A permanent workspace rows-first is also what lets an empty workspace survive
startup.

### A15. `runBlocking` appears only around two reads in the provider

`WorkspaceDocumentsProvider.kt`. Every method runs on a Binder thread that
DocumentsUI blocks on, hence `MAX_CHILDREN = 500` and the caps. The two are
`workspaces().byId(documentId)` — resolving the document id that the provider
itself publishes — and `workspaces().all()` for the root listing. Both are
primary-key or small-table reads; a conversation lookup must not be used here
because a permanent workspace can have several conversations.

### A16. The demo script reads the transcript, not a counter

`DemoProvider.kt:131-146`. A provider is stateless; a counter would desynchronise
the moment a reply was regenerated or a branch switched, then call delete on a
file that is already gone.

### A17. Vertical rhythm lives in `Layout.Space`

`Layout.kt:50-92`. It was literals once, and the transcript spent more vertical
space on the gaps than on the things: a one-line collapsed tool result stood 53dp
tall with an 18dp gap.

---

## B. Defects that were found here — and where each one now stands

Every entry below was a real defect when it was written. Most are fixed; each one
says what the fix was and how far it was verified, which is **not** the same for
all of them. A "FIXED" with no test named means build and the full suite only;
where a defect sits inside a stream, or needs a fake that does not exist, saying
so is better than a test that asserts nothing.

**Status: B1-B10 fixed, B11 mostly fixed, B12 fixed.** The dates matter less than
the method, so each fixed entry names what convinced it — a test, a device
observation, or neither.

### B1. The regenerate control was disabled for the rest of the session after any mutating turn — **FIXED**

Three expressions of one fact: a stored field on `ChatUiState`, a private method
on `ChatViewModel` with the same arithmetic, and the assignment in `reload()`.
The field was raised in `executeCalls` and never cleared, so after one mutating
turn every later reply claimed it could not be regenerated -- and reopening the
conversation did not help, because the chat view model is keyed and
activity-scoped, so the same instance came back without `reload()` running.

The fix is not to clear the flag in `send()`, which would break again the next
time a path starts a turn. It is to delete the field: `currentTurnMutated` is now
a **derived property** on `ChatUiState` (`ChatUiState.kt:117-122`) computed from
the transcript after the last user message, so there is no second copy to go
stale. `reload()` deliberately does not assign it, and the comment there says
why: rebuilding the transcript *is* recomputing it. `ChatUiStateTest` carries
five tests, including "the turn after a mutating turn can be regenerated" -- the
exact sequence that was broken.

**Verified on the emulator, because this one had a trap in the verifying.**
The demo script writes a file every turn, so *every* demo turn is legitimately
mutation-gated and shows "This reply changed files, so it cannot be regenerated."
That sentence appearing is therefore **not** evidence of the bug. The decisive
case is a turn that changes nothing, which the demo's `plain` path provides:

```
after a mutating turn   'cannot be regenerated' present
send "plain"            a turn with no tool calls
after it                'cannot be regenerated' absent, "Answer again" offered
```

A version of this entry previously described the two-named-flags bug as still
current, directly beneath the paragraph announcing the fix. Both halves were in
the same file for a week; the lesson is that fixing an entry means replacing it,
not appending to it.

**Superseded on 2026-10-18.** The derived `currentTurnMutated` property and its
UI sentence were removed entirely, together with the five `ChatUiStateTest`
cases that existed only for it. Regeneration now sends the request with
`includeTools = false`, so a pure-text reply can be retried after SSH or a file
write without any chance of replaying the tool. The old property was protecting
against a replay that is now structurally impossible.

### B2. The last ~40 ms of every reply was missing from the live transcript — **FIXED**

`publishIfDue` throttles to one update per 40 ms and was a **local function
declared inside the `try`**. The comment at the throttle claimed "the `finally`
block publishes regardless, so the last partial window is never lost", but a
local function is not in scope in the `finally`, so the finally only called
`updateMessage(… isStreaming, finishReason, usage)`. Up to 40 ms of tokens were
therefore absent from `plainText`, from `parts` and from the delta channel after
the turn ended, while the database row (written from the same `StringBuilder`s)
was complete. The bubble stayed a few words short until something reloaded the
transcript, which reads as a rendering delay rather than as lost state.

The fix hoists the publish into `publishParts(...)` and calls it from the
`finally` before the channel closes (`ChatViewModel.kt:1690-1707`), so the
throttle is no longer lossy. No test isolates the last 40 ms: the loss lives
inside a stream, which adb cannot sample at any rate, so this rests on the code
path rather than on a test that asserts nothing.

### B3. `MarkdownText` never switched to the plain renderer when the stream ended — **FIXED**

`MarkdownText.kt:48-52` documents a switch. Nothing performed it: `deltas` was
captured into the message when it was built, so removing the map entry at the end
of the turn left the field holding a closed channel — the reply kept rendering
through the incremental parser until something reloaded the transcript, and the
anti-flicker `retainState` path applied only to transcripts loaded from the
database.

Two fixes, because there were two causes. The closing update now sets
`deltas = null` with `isStreaming = false` (`ChatViewModel.kt:1009-1021`), which
is the switch the renderer's documentation describes. Separately, **a rotation
mid-stream lost the text received before it**: the delta channel is a queue with
no replay, and a rebuilt composition started empty while `text` was ignored. So
`MarkdownText` now appends `text` through a `DeltaCursor` before following the
channel (`:113-132`); the cursor only emits what the renderer does not already
have.

Adding the rewind case to `DeltaCursorTest` immediately caught a regression in
that fix — on a shrinking buffer it reset `sent` to zero and re-emitted the
prefix, which is one of the two failure modes that file's own documentation
names. Verified by build and the full suite. **Not verified on a device:** this
is inside a stream, which `AGENTS.md` records as unobservable through adb at any
sampling rate.

### B4. Declining the first of several tool calls persisted an unanswered call — **FIXED**

One helper, `answerSkippedCalls`, now gives every unrun call an answer of its
own, and it serves both paths: the early return on a decline, and `Stop`
unwinding the loop from inside the body. The result is marked `[skipped]` and the
chip goes to `Failed` rather than `Declined` -- the user refused one call, and
reporting the others as refused too would be untrue.

The stop path runs it from a `finally` under `NonCancellable`, because by then
the coroutine is already cancelled and each answer is an insert. Deliberately not
around the whole loop: that would make Stop unable to interrupt a tool.

The old shape was this: `executeCalls` returned immediately on the first decline
(`:1128`) while all calls of the round were already in `toolCallsJson`, so calls
after the declined one got no result; `Stop` during `tool.execute` left the same
gap. That transcript is a request the API rejects (`:1282-1292`), so the next user
message after either was likely a 400. `answerSkippedCalls` is what closes that
gap on both paths.

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

### B6. The provider could rename or delete a workspace folder — **FIXED**

Folders carried `directoryFlags()`, so the Files app offered both. A workspace
directory's name *is* its `workspaceId`, so renaming it orphans the row -- the
next tool call recreates an empty workspace under the old id, and the renamed
directory matches no row, so the sweep eventually deletes the files -- and
deleting it leaves the inverse orphan the sweep cannot see.

Workspace roots now carry `workspaceFlags()`: create only, since creating files
inside the directory is the case the provider exists for. `deleteDocument` and
`renameDocument` also refuse a workspace root outright, because a flag is a
request and the provider should not rely on a caller honouring it. Deleting a
workspace is something the app does, through the repository, which removes
files and row together and refuses while conversations remain.

**The first version of that refusal checked the wrong id, so it never fired.**
A conversation row carries two UUIDs: `id`, the transcript's key, and
`workspaceId`, the workspace's directory name and the document id this provider
publishes. The refusal called `conversations().byId(id)`, which compares against
the first; a folder URI carries the second, so every check returned null and
execution fell through to `resolveFile(...)`. `includeDocument`'s "a bare id is a
conversation" branch had the same mismatch, so a re-queried folder fell through
to `includeFile` and reported its display name as a raw UUID rather than the
title.

The provider now resolves a bare document id through `WorkspaceDao.byId`, and
the root lists workspaces rather than conversations. That is the only correct
lookup once a permanent workspace can be named by several conversations; the old
unique `workspaceId` index is gone in schema v11. `ConversationLookupTest` still
pins the id distinction; `WorkspaceDocumentsProviderTest` covers a re-queried
folder keeping its name and both `deleteDocument` and `renameDocument` throwing
`FileNotFoundException` for the id the provider publishes.

The flags are what stop the system Files app offering rename or delete at all;
the guard is the second line for a caller that does not honour them. The
provider is additionally protected by `android.permission.MANAGE_DOCUMENTS`,
which limits callers to the system, so this was defence-in-depth rather than an
open door.

### B7. `import` was a quota side door — **FIXED**

It enforced only `maxWriteBytes` -- no `maxTotalBytes`, no `maxFileCount`, no
free-space check -- and its failure path deleted the target, which for a
destination resolving to an existing empty directory deleted that directory.

Import now streams into a staging file in the same directory and renames it into
place, so the destination is only replaced by a complete import and the cleanup
cannot reach anything the user already had. The quota arithmetic moved to
`SessionWorkspace.quotaRefusal`, shared with `WorkspaceTool`, because the rule
existed in one place and not the other.

`WorkspaceTransferTest` carries seven tests, two of them stated as behaviour
rather than as implementation ("the original file still exists and still has its
contents", "the directory is still there"). It also caught two bugs in the fix
itself: `usage()` counts the staging file, so a 2 KB import into a 3 KB
workspace was told 4 KB was over the limit, and the file count was wrong the
same way. The caller now declares what it staged and what it replaces.

### B8. `1.json` was not a v1, and the fix was smaller than I claimed — **FIXED**

`core/data/schemas/…/1.json` and `2.json` differed only in the `version` field,
and `1.json` already declared `toolCallsJson`, `toolCallId` and `toolName` — the
three columns `MIGRATION_1_2` adds. The exported v1 was v1's schema re-taken after
the entities gained those fields.

**It was functional, not cosmetic.** `MigrationTestHelper.createDatabase` builds
the starting database *from that file*, so a test beginning at v1 failed with
`duplicate column name: toolCallsJson` — the migration could not run against the
schema the export claimed. A test that cannot start is worse than no test,
because it looks like coverage.

**Two things I got wrong along the way, both worth keeping.**

First, I claimed `5.json` also drifted because it "already contained
`thinking`/`agentId`". It should: `MIGRATION_3_4` adds both, so v5 has them. That
was a correct file read as a defect, and only an audit against what each migration
adds — run for all eight versions at once — showed all of them clean on that
axis.

Second, I said fixing this needed "a derived entity class per historical version,
because the `identityHash` cannot be hand-written". The experiment says otherwise:
`MigrationTestHelper` does **not** verify the stored hash when building a starting
database. It reads the file's `version` and its DDL. Removing the three columns
from `1.json`'s `createSql` and `fields` was enough — the v1 path now builds, and
`the exported v1 schema can build a database` is the test that keeps it that way.

So the entry cost two lines of JSON and a wrong estimate of a day's work. The
`identityHash` is genuinely stale in that file (`26d8a4e9…`, identical to
`2.json`'s, which cannot be right for two different schemas); it simply is not
consulted on this path, and recomputing it would need Room to export the file
itself.

**Verified:** eleven device tests in `:core:data/src/androidTest/…/MigrationTest.kt`,
including the new schema check, plus an audit of all eight exports against the
columns each migration adds. The other eight still build their own DDL rather than
trusting the exports, which is the safer default even now that `1.json` is
correct.

**The one remaining caveat:** a schema file that is right about its columns and
wrong about its hash is right by accident. Anything that later starts verifying
hashes will reject `1.json` and `2.json`, and the fix then is to let Room re-export
them from genuine per-version entities. Recorded rather than pre-empted.

### B9. Two sources of truth for "can this model see" — **FIXED**

Two defects under one heading, both the same shape.

**`SecureStore.hasUsable` was not name-accurate.** It was `get(key) != null`, so
a stored-but-empty value read as configured while `send()` rejected it -- the
composer enabled, the turn refused, nothing on screen explaining the
disagreement. It is now `!get(key).isNullOrBlank()`. The settings screen cannot
produce that state (`SettingsViewModel.save()` removes the key when the field is
blank), so nothing in the app reached it; it is fixed because `get` is public and
the invariant is the one the callers already assume.

**The vision check consulted a different source than the button did.** In demo
mode the composer's attach button was **enabled** -- the demo model advertises
image input and `publishModelState` falls back to the demo model list -- while
`attachImage` called `agents.models()` itself and could not find that model, so
every attempt was refused with "The model in use cannot read images."

`publishModelState` already computed the answer and put it in state as
`modelSupportsVision`; `attachImage` simply did not read it. It does now, so the
button's enablement and the refusal come from one expression. Note which way the
fix went: the demo model genuinely accepts images
(`DemoProvider.listModels` declares `inputModalities = listOf("text", "image")`),
so the button was right and the refusal was wrong -- the published answer was the
authoritative one, which is why the fix is to read it rather than to make the
button lie.

### B10. `FilesViewModel` blocked the main thread and walked twice — **FIXED**

`workspace()` wrapped a database read in `runBlocking`, and every caller --
refresh, open, delete, import -- reached it from `viewModelScope`, whose
dispatcher is `Dispatchers.Main.immediate`, so each parked the thread that draws
the UI. It is now `suspend` and reads on `Dispatchers.IO`.

`refresh()` also called `usedBytes()` and `fileCount()` separately, and both call
`usage()`, so every refresh traversed the whole workspace twice -- the cost A6
exists to remove, reintroduced here. It now asks for `usage()` once and takes
both numbers off the result.

### B11. Smaller ones — **MOSTLY FIXED** (this entry corrected twice)

Fixed in this pass, each verified by reading the code rather than by the commit
message:

- `ConversationDao.observeAll` and both `AgentDao` orderings gained `id ASC` as a
  tie-break (`InhumanDatabase.kt:25`, `:167`, `:170`). Two rows saved in the same
  millisecond compare equal on `updatedAt`, and SQLite may return them in either
  order, so an emission could reorder equal keys and make the list appear to
  shuffle with nothing having changed.
- The model picker's error path is reachable: `reloadModels()` now returns a
  `Result` (`ChatViewModel.kt:326`) and the dialog reads
  `result.exceptionOrNull()?.message` into a visible error line
  (`ModelPickerDialog.kt:82`, `:104-110`). Selecting a model also dismisses, in
  both the default option and a listed one (`:118-121`, `:150-153`).
- The two-sources-of-truth bug behind "cannot read images" in demo mode is gone:
  `attachImage` reads `_state.value.modelSupportsVision`, the same published
  answer the attach button was enabled from (`ChatViewModel.kt:376`, `:388`).
- The failed turn that left no trace is fixed: a turn that fails before
  producing text, reasoning or tool calls now gets a row with the reason and
  empty content (`ChatViewModel.kt:1067-1078`). **No test covers it** — nothing in
  any test source mentions the case — so this one rests on the code comment and
  the rendered path, not on a green suite. Recorded that way on purpose.

Still open, each re-verified in the code as of this pass:

- **`pendingApproval` is a single field** (`ChatUiState.kt:40`), written at one
  place (`ChatViewModel.kt:1216`) against a single `approvalContinuation`
  (`:1327`), so several approval-needing calls produce **sequential** dialogs
  rather than a collected batch. The `pending` parameter of
  `continueAfterApproval` is accepted and never read (`:1334`).
- **Dead code, verified repo-wide** (`grep` over every `.kt`, excluding `build/`
  and `_research/`): five fields of `TurnOutcome` are constructed and never read
  — `text`, `reasoning`, `usage`, `finish`, `failure` (`ChatViewModel.kt:764-772`;
  the only construction site passes `usage = null`). Also unreferenced:
  `DeepSeekProvider.json`, `DeepSeekRequest.EmptyArray` (a declaration and nothing
  else, `:167`), `ReasoningType.Raw`, `ConversationRepository.observeConversation`
  (definition only — `:82`), `MessageDao.update`, `MessageDao.delete`,
  `ConversationDao.observe`, `SecureStore.has`, `Database.DEFAULT_SYSTEM_PROMPT`
  (a duplicate of the companion's, which is the one bound at
  `InhumanDatabase.kt:468`; the copy in `Database.kt` is unreferenced),
  `FilePreview.truncated`. Two are **test-only reads** rather than dead:
  `RemoteModel.maxOutputTokens` (read by `DeepSeekModelsTest`) and
  `SseFrame.id`/`retryMillis` (read by `SseParserTest`). `StreamChunk.Raw` is
  never emitted — its only reference is the `when` branch that ignores it.
- **`TokenFormat` avoids `String.format` for a Turkish-locale reason**
  (`ContextUsage.kt:122-124`) — `%` formatting is locale-sensitive and `"%.1f"`
  renders a comma decimal separator in some locales. The rule is not followed
  where a user actually reads the number: `UsageLine.kt:102`, `:105`, `:114`,
  `:143-144` all use it, as does `ChatScreen.kt`.

Three things that *were* on this list and are now clean, recorded because a
stale entry is how this list loses its authority (each was re-read, not assumed):

- the duplicated doc comments on `replyDeltas`, `regenerate` and `selectBranch`
  are gone — each KDoc is now single and describes its own function;
- the `attachImage` / composer disagreement about vision is gone with the
  single-source fix;
- `DeltaCursor`'s `seen`/`sent` pair looked like dead state in a diff, but it is
  not: `seen` holds the text and `sent` is the index into it
  (`DeltaCursor.kt:29-33`), and a grep for reads of `sent` finds the slice
  computation. It is cited here because "written and never read" was a plausible
  wrong conclusion.

### B12. Files showed a stale list after the assistant wrote a file — **FIXED**

The report: the transcript said a file was saved, and Files was empty. Both were
true at their moment. `FilesViewModel` refreshed only in `init`, and the view
model is activity-scoped and keyed by conversation (`files-$id`), so leaving the
screen and returning reused the same instance with the list from before the
write. The workspace on disk held `notes/g-dropping-用法笔记.md`; the screen was
still showing the state from its first composition.

The fix has two halves. `FilesScreen` refreshes on every resume
(`LifecycleResumeEffect`), and `FilesViewModel` no longer refreshes in `init`;
otherwise the first open would walk the workspace twice. `refresh()` also logs
the path, entry count, file count and bytes, so "the screen is stale" can be
told from "the file is not there" in the log.

**Verified on the pad**: reopening Files on that conversation shows `notes` and
`1/5000 files · 5 KB`; inside, `g-dropping-用法笔记.md`, 5 KB. The log line is
`D/files refresh path=/ entries=1 files=1 bytes=5440`. No unit test covers this;
the invariant is a screen-resume lifecycle, which a JVM test cannot drive.

### B13. SSH was green on the JVM and failed on Android at key exchange — **FIXED**

The 8-test `SshClientTest` passed against embedded MINA SSHD, including Ed25519
and TOFU, and the APK built. The first real device connection — an otherwise
valid password-auth server on the other side of `adb reverse` — failed with:

```
no such algorithm: X25519 for provider BC
```

Android ships a **stripped** BouncyCastle under the provider name `BC`; sshj
checks `SecurityUtils.isBouncyCastleRegistered()`, sees that name and assumes the
full provider. On the JVM the full provider is on the test classpath, so no test
could see the difference. The fix is in `SshClient.ensureBouncyCastle()`: replace
the stripped `BC` with the full `org.bouncycastle.jce.provider.BouncyCastleProvider`
before the first connection. Verified on the pad: `installed full BouncyCastle
provider`, handshake, TOFU fingerprint written, command returned exit 0.

The lesson is not "test on a device" in general; it is that a JVM SSH test
answers questions about sshj and the server, not about Android's security
providers.

### B14. A model wrapped the whole SSH argument object, and consent showed an empty call — **FIXED**

After a failed command, the model began emitting:

```json
{"arguments":"{\"hostname\":\"127.0.0.1\", ...}"}
```

— the function-call envelope as an argument, plus `hostname` instead of `host`.
`SshExecTool` was made tolerant (unwrap one string level, accept `hostname`), but
the consent dialogue read the raw parsed object and rendered "connect to @",
with a blank first command. It could therefore ask the user to authorize a call
that the tool would then reject.

`SshExecTool.normalizeArguments` is now the single normalisation, used by both
the tool and `ChatViewModel` before the consent gate, the policy check and
`describeCall`. A call still missing host/user/command gets an error result and
**no** consent dialog. Verified on the pad by re-sending after the fix: the
dialog showed `tester@127.0.0.1` and `uname -a`, then the command ran.

### B15. Global SSH off→on inherited a revoked consent — **FIXED**
`sshAllowed` was mirrored in `ChatViewModel` and only reloaded with the
transcript. Turning the global switch off cleared the database column but not
that field; turning it back on made the next call trust the stale `true` and skip
the consent dialog the user had just revoked.

The call path now re-reads `conversations.sshAllowed` before the consent gate
when the in-memory flag is true. A revoked row clears the flag and asks again.
`SettingsViewModel.setSshToolEnabled(false)` clears every row. Verified on the
pad: off→on, next SSH call showed the consent dialog again.

### B16. Killing the app during consent made every later turn HTTP 400 — **FIXED**

The assistant row with `tool_calls` is persisted as it streams; the tool rows are
not. Force-stopping the app while the SSH consent dialog was open left the
conversation with two calls and no results. The next request was rejected:

```
An assistant message with 'tool_calls' must be followed by tool messages
responding to each 'tool_call_id'. (insufficient tool messages ...)
```

`ChatViewModel.closeDanglingCalls` now validates the **wire history**: after each
assistant `tool_calls` block it keeps the real tool results and inserts an
`[interrupted]` result for any call id still missing. This works even when a
later user message exists, where appending to the database tail would put the
result in the wrong place. `repairDanglingToolCalls` separately closes a dangling
tail in storage so the transcript can be reopened cleanly.

The residual is display-only: a mid-history dangling call can still render as an
unanswered call in the transcript; the request is valid, but the UI does not
rewrite history. That is preferable to branching the conversation by inserting a
synthetic child and changing which message is selected.

### B17. A live Thinking/tool accordion expanded below the fold without following — **FIXED**

Reported as: "拖到底部时默认跟随最新，手动移动后停住，点回到底部也跟随；但 thinking
之类的临时展开部分似乎不会跟随最新." The follow effect was keyed only on
`messageCount` and the last answer's text length. A `Thinking` or tool accordion
expanding or collapsing changes the row's measured height without changing
either key, so the transcript stayed put while the newest content grew below the
fold.

Two changes in `ChatScreen`:

1. `followLatest` is now an explicit intent. `NestedScrollConnection` turns it
   off on `NestedScrollSource.UserInput`, and a scroll-end observer turns it
   back on if the gesture landed at the bottom. Content growth never changes it;
   deriving follow from `atBottom` would stop following when a growing row
   pushed its own bottom below the viewport. A `programmaticScroll` flag keeps
   the jump/follow callbacks from counting as user input; without it the jump
   requested follow and its own scroll immediately cancelled it.
2. The follow effect also keys on the last visible row's measured height and
   offset, and `atBottom` now requires the actual last item's bottom at the
   viewport bottom, not "within two items".

Verified on the pad during a live thinking answer: no jump button appeared while
the accordion expanded, a manual swipe up held its position for five seconds of
continued streaming, and the jump button restored follow.

### B18. Rollback deleted the turn but left tool effects behind — **FIXED**

The first rollback implementation was transcript-only: Edit deleted the user
message and descendants and restored the composer, but files written by the turn
stayed written and SSH commands stayed run. That is not a rollback; it is
hiding the evidence. The correction is structural:

- before the first **reversible mutating** tool of a turn, capture a
  content-addressed `start` checkpoint under
  `filesDir/rollback/<conversation>/<user-id>/`; if it fails, refuse the
  mutation; after each completed tool group capture another checkpoint;
- rollback restores `start` **before** deleting any rows; a failed restore
  leaves the conversation intact;
- `ssh_exec` is tagged `reversible = false`; a turn containing it gets no Edit,
  only the reason. Regenerate is still available.

Snapshots are per turn and per tool group. The chat menu lists each stored turn
with its checkpoint count, bytes and timestamp, totals them, and sets retention
(1/3/5/10); pruning keeps the newest turns and compacts unreferenced blobs. Rewinding a turn deletes only that turn's
directory, leaving older snapshots intact. A checkpoint's existence is also
treated as evidence of a mutation, because a process can die after the file
changed and before `markMutated` was written; relying on the row alone would
recreate the same half-rollback.

Verified on the pad with a throwaway conversation: a real `write_file` created
`notes.txt`; Edit restored the empty `start` checkpoint, removed `notes.txt`,
deleted the turn, and restored the prompt.

### B19. Rewind was described only in prose, and the changed files were invisible

Three related UI failures, reported together:

- Whether a turn could be rewound lived in the tool description, which is
  collapsed by default. The name `ssh_exec` says what it runs, not that its
  effects are permanent. Every mutating tool now carries **REWIND** or **NO
  REWIND** in the same badge row as PAID and REMOTE, in the assistant editor,
  the approval-rule row, and the approval dialog. `ssh_exec` shows both REMOTE
  and NO REWIND.
- Edit restored a snapshot without naming what it would revert. The turn's
  changed paths are now compared against the snapshot by content hash, stored
  on the final assistant row (`messages.changedFilesJson`, schema v10), shown
  under the answer as `+`/`~`/`- path`, and listed in the confirmation dialog.
  The comparison is content-based because checkpoints store hashes, not
  modification times.
- Agent answers were still 640dp chat bubbles. User messages remain bubbles
  because they are short; an assistant answer is the document, so it has no
  background or width cap and fills the transcript. Tool-result rows and the
  composer remain capped.

Verified on the pad: the file turn showed `Changed files / + notes.txt`, the
dialog repeated the same path under "Files that will be restored", and the
assistant text ran to the transcript edge. On the phone AVD the assistant
answer did the same while the user bubble stayed capped; the tool editor showed
`REWIND` on file tools and `REMOTE` + `NO REWIND` on `ssh_exec`. The persisted
list survived a force-stop and relaunch.

### B20. Regeneration could only retry the last text, and one snapshot overwrote all history

The next report was about control and history:

- The refresh button could only produce another **final text** answer. It could
  not resume from after the first tool call, or before the second, even when
  everything discarded was reversible. The answer is a checkpoint picker:
  `Regenerate from…` lists `From your message` and each completed tool group.
  Selecting one restores its checkpoint, deletes the suffix, and reruns **with
  tools**. A suffix containing SSH disables the point.
- One snapshot per conversation meant the previous turn disappeared on the next
  send, and there was no way to see or choose how much history was kept.
  Checkpoints are now content-addressed per turn and per tool group; the chat
  menu lists each turn's checkpoints/bytes and offers 1/3/5/10.
- Rewinding called a full-conversation discard, deleting every snapshot. It now
  deletes only the rewound turn's directory. A later regeneration of that turn
  trims manifests the rewritten transcript no longer references, so the turn's
  checkpoints are replaced rather than accumulating or truncating older turns.

Verified on the pad with two tool rounds: the picker showed `After tool group 1:
write_file` and `…2…`; selecting group 1 restored `a.txt` without `b.txt`,
deleted the old suffix, and reran with tools. The second turn was then rewound:
snapshot count went `2 turns` → `1 turn`, proving only the rewound turn was
discarded. The old second-group manifest was replaced, leaving `start` plus the
two current group manifests.

### B21. The transcript flashed when the reply completed

Two independent causes, found in sequence.

The completion path called `reload()` after the last token. `reload` re-reads
every row and replaces the whole `messages` list, so the streamed row was
swapped for an equal reconstructed row and the LazyColumn re-laid out -- a
visible flash at the exact moment the user starts reading.

The transcript is already current and persisted when the stream ends. The send
and mid-turn-regeneration paths now call `refreshTurnMeta()` instead: it
recomputes only checkpoints, rollback availability, the changed-file section's
source state and the context gauge, and does not touch `messages`. Final-answer
regeneration also avoids `reload`: it fetches the new reply's branch metadata
with `refreshReplyBranches()` and patches that one row. A full `reload` remains
for paths that genuinely replace the tree (initial load, branch switch,
rewind).

After that fix the regenerate path still flashed. The remaining switch was
`deltas = null`: it handed the finished message to `rememberMarkdownState`,
which parses asynchronously. For one composition the new state was empty and
the reply disappeared. The completed message now keeps its closed streaming
channel and its `StreamingMarkdownState`; the plain parser is used only when a
row is rebuilt from the database, before it is drawn. `MarkdownText`'s claim
that the swap is invisible was the comment that was the bug.

Keeping the streaming state exposed a latent duplicate in the delta protocol.
When the first composition landed after the first token, the catch-up appended
the accumulated `text` and the still-queued first delta was appended again:
the reply read `HiHi there!`. The channel now carries accumulated snapshots
(conflated) rather than slices; `DeltaCursor` decides the missing suffix, so a
late catch-up and a queued snapshot reconcile instead of overlapping. Verified
after a regenerate: the reply read `Hi there!`, not `HiHi`. The branch switcher
still showed `3 of 3` without a reload.

### B22. Rewind deleted the user row's descendants but kept the user row

Adding `deleteDescendants()` for mid-turn regeneration accidentally changed
`deleteSubtree()` to `doomed - rootId`, so Edit restored the workspace, deleted
the assistant/tool subtree, and left the user message on screen. The two
operations are now distinct: `deleteSubtree` deletes the root plus descendants;
`deleteDescendants` keeps the selected boundary and removes the rest.

### B23. The final-answer retry removed its tool definitions and lost the cache

The retry was "tool-less": it dropped the `tools` definitions to guarantee no
call. DeepSeek's cache is a prefix cache, so that changed the request from
`prompt=2201 cache=2048` to `prompt=191 cache=0` after a tool turn. The cache
was not reset; the cached tool-bearing prefix simply was not the request.

Keeping the definitions but appending a **transient** system instruction after
the cached prefix preserves it. The same retry then measured
`prompt=2148 cache=1920` (89%) and returned no tool calls. The app still refuses
any call before execution, so the cache is not paid for with a replayed write.
If the model violates the instruction, the turn stops with an error; nothing is
executed.

---

## C. Comments that contradict the code

By this repository's standard, the comment is the bug. These were all verified.
An entry leaves this table when the fix makes its comment true, rather than
staying as history.

| Comment | Reality |
| --- | --- |
| `WorkspaceTool.kt:63-65`: "A ranged read never loads the whole file" | `file.readText()` is called before the slice (`:77`), and the ceiling compares UTF-16 chars against a byte limit (`:91-95`). |
| `SessionWorkspace.kt:151-153`: "anything behind a symlink is not charged to its quota" | Only symlinked *directories* are skipped; symlinked files are still counted (`:158-165`). |
| `SessionWorkspace.kt:20-22`: "[root] is never exposed" | `rootPath()` exposes it (`:175-184`) — deliberately, for `StatFs`, with the argument written out. |
| `SessionWorkspace.kt:26-33`: a symlink "can never be swapped in between the check and the open()" | The walk refuses to *traverse* links; nothing stops another same-UID writer creating one, and there is no re-check or `O_NOFOLLOW` at open. |
| `Tool.kt:81-82`: "Anything that writes, deletes or reaches outside the workspace should override this" | **No tool overrides `needsApproval` to return true**, `delete_file` included — it returns `false` (`FileTools.kt:355`). The advice is now historical: approval is decided by the user's configured rules, and `needsApproval` survives as an unused "must always ask" hook. |
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
