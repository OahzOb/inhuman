# 06 — The agent loop, the chat UI, and the other screens

Module: `:app`. All ViewModels live here, which is deliberate: there is no DI
framework and the graph is small enough to read at the root.

## The turn lifecycle

Everything below is in `app/src/main/kotlin/com/inhuman/ui/chat/ChatViewModel.kt`.

### `send` (`:683-743`)

Refuses empty text with no attachments, or a send while streaming (`:688`);
refuses without a key and without demo mode; takes `parent` = the last message id
(`:697`); publishes the user message and clears `input` **and**
`pendingAttachments` only at that commit, because clearing earlier would lose the
attachments if the send were refused (`:708-719`).

**The job is assigned to `streamJob`** (`:729`). The comment records why that
matters: it once was not, so `stop()` cancelled a field that was null for the
life of the view model and the button did nothing at all — the answer went on
growing, and because `isStreaming` was cleared the composer unlocked, so a second
turn could start while the first was still writing into the same message tree
(`:721-728`).

### `runTurn` (`:751-789`)

Builds the provider (`DemoProvider()` or `DeepSeekProvider(apiKey!!)`),
`buildTools()`, and loops up to **`MAX_TOOL_ROUNDS = 8`** (`:1527`). Empty
`toolCalls` ends the turn; falling out of the loop sets an explicit error
("Stopped after 8 tool rounds…", `:782-788`) rather than ending silently.

### `streamOneTurn` (`:802-1081`)

The ordering inside this function is load-bearing in four places:

1. **The delta `Channel` is registered before the placeholder message exists**
   (`:814-827`). The row's first render already needs the channel.
2. **`buildWireHistory()` is called after the placeholder exists**, and
   compensates with `messages.dropLast(1)` (`:1351`).
3. **Tool calls are keyed by the provider's `index`**, in a
   `linkedMapOf<Int, ToolCallUi>()` (`:832`).
4. **`ToolCallDelta` concatenates both `nameDelta` and `inputDelta`** (`:945-949`)
   rather than using `?:`. The comment records the bug: a decoder that sent the
   name once and this line that kept only the first fragment disagreed, and the
   result was a tool named `read_` instead of `read_file`.

State publication is **throttled to `STREAM_PUBLISH_INTERVAL_MS = 40`**
(`publishIfDue`, `:876-889`) because every publish copies the whole accumulated
answer — over one reply, publishing per chunk is quadratic (`:855-868`).
`ToolCallStart` and `ToolCallEnd` force a publish; token deltas do not.

Usage fields are kept separate during the stream and assembled once at the end
(`:962-972`, `:1003-1019`), so intermediate numbers never flicker.

**`Failure` sets the message error and the screen error but does not break the
loop** (`:979-983`).

The `finally` (`:993-1070`) does, in order: snapshot the finished calls; remove
and close the delta channel; capture `wasStopped = !isActive` while cancellation
is still observable; assemble `UsageUi`; clear `isStreaming` and attach
`finishReason`/`usage`; `refreshContext()` so the gauge moves per round trip; and
then, inside **`withContext(NonCancellable)`**, insert the assistant row with the
full answer, reasoning, model, usage and `toolCallsJson` (`:1041-1065`) — or, if
nothing at all arrived, remove the placeholder and persist nothing (`:1067`).

The `NonCancellable` block is the one that matters most and its comment says why:
a cancelled coroutine refuses to suspend, so without it `appendMessage` would
throw and the part of the answer already received would never reach the database.
The user would watch a paragraph appear, press Stop, and find it gone the next
time the conversation was opened — while still having been billed for it
(`:1028-1039`).

### `executeCalls` (`:1094-1189`)

Per call, in order:

1. Unknown tool → an error result and `continue` (`:1099-1102`).
2. Arguments are re-parsed (they were parsed once at `ToolCallEnd`); a parse
   failure appends a result and `continue`s (`:1104`).
3. `needsApproval(parsed)` → `pendingApproval` in state, then **`awaitApproval()`
   suspends the turn** (`:1106-1114`).
4. Declined → the `Declined` chip is set **before** the result is appended
   (`:1121-1128`), the result body is `DECLINED_MARKER + …` (`:1509`), and the
   function returns `false`. The ordering is deliberate: without it the call
   stayed `Ready` forever, and a refused delete rendered as a call that was
   merely waiting — the one state a user is most likely to misread as "it is
   about to happen".
5. Approved → `Running` → `tool.execute` on `Dispatchers.IO` (`:1147`) →
   `Done`/`Failed` → result appended.

**Only the execution moves off the main thread.** Approval stays on it, because
it suspends on the user and the continuation is resumed from the UI (`:1133-1146`).
The measured reason for the move: one `write_file` costs about 106 ms in a
2,000-file workspace, and a turn that writes several files pays it several times.

**Any `tool.mutates` call sets `mutated`, whether it succeeded or not**
(`:1155-1168`). A failed write still means the turn *tried* to change the
workspace, and regenerating would try again.

### The approval plumbing (`:1193-1205`)

One `CancellableContinuation<Boolean>` field. `approvePendingCall` /
`declinePendingCall` (`:669-679`) clear `pendingApproval` and resume the
continuation only if it is still active.

Two things to know: **the `pending: PendingApproval` parameter is never read**
(`:1200-1205`), so approval is not matched against the call it names — and because
`pendingApproval` is a single field, several approval-needing calls produce
**sequential dialogs**, not a collected batch, which contradicts the comment at
`:768-769`.

## Regeneration, and the sticky-flag bug

`regenerate()` (`:543-600`) refuses while streaming; targets the last non-user,
non-tool message; requires a key or demo mode; truncates state to just before the
old reply; runs the turn; and then `reload()`s so the two replies are seen as
siblings.

**There are two different things called `currentTurnMutated`, and the UI reads
the wrong one.**

| | What it is | Where |
| --- | --- | --- |
| `currentTurnMutated()` | A **private function**, correctly scoped to the last user message: "a file written three exchanges ago is not rewritten by answering again, so it must not block anything" | `:536-541` |
| `state.currentTurnMutated` | A **UI flag** on `ChatUiState` (`ChatUiState.kt:101`) | read by `ChatScreen.kt:171`, used at `:852` |

The function is right. The flag is **sticky**: it is assigned in `reload()`
(`:498`) and set to `true` in `executeCalls` (`:1177`), and **nothing in `send()`
clears it** (verified: `:708-719`). So once any reply in a conversation has
mutated a file, every later reply renders "This reply changed files, so it cannot
be regenerated." — even though the button's own guard would allow it.

Reopening the conversation does **not** fix it: the chat ViewModel is keyed
(`MainActivity.kt:233`, `key = "chat-$id"`) and activity-scoped, so returning to
the same conversation returns the *same* instance — `init` does not re-run and
`reload()` is not called. Only a process restart, or a branch switch (which does
reload), recalculates it.

The UI also **replaces** the button with that sentence rather than disabling it,
which is deliberate (`ChatScreen.kt:852-868`) — a disabled control invites
guessing at why.

## The stream-inside-the-UI invariants

`AGENTS.md` explains why these are pure functions: a demo reply streams for about
3.6s and a UI dump costs ~700ms, so intermediate states are unobservable at any
sampling rate the tooling allows. Extract the invariant, test that, and use the
device only to confirm the path renders.

### `DeltaCursor` (`app/src/main/kotlin/com/inhuman/ui/chat/DeltaCursor.kt:27-57`)

One invariant: **the slices it emits, concatenated, equal the reply, exactly once
each.** "Send the part that is new" has two ways to be wrong — re-sending a
prefix duplicates the reply, skipping a slice loses text — and after the stream
both look identical in a screenshot. A shrinking buffer is treated as a new
document (`:44-48`), which is what a regeneration or a reload looks like.

Two dead-ish details: `seen` (`:30`, `:45-55`) is written three times and **never
read**; the class only needs `sent`. And `initialText` is never passed by
production code (`ChatViewModel.kt:875`) even though `DeltaCursorTest` covers it
"as it does after a reload" — no reload path passes it.

### `ContextUsage` (`app/src/main/kotlin/com/inhuman/ui/chat/ContextUsage.kt:34-93`)

Three-case arithmetic, because a ring drawn naively from `used/window` turns
"unknown" into "0% used" (`:3-15`):

1. No window → no percentage at all.
2. A window with nothing measured yet → an empty ring, and `isKnown` is true
   while the fraction is not computable.
3. A real number → `percent` rounds **down** but never shows 0% for a non-zero
   amount, and clamps at 100 (`:59-77`).

`window` treats `0` as absent (`:39`); `summary` degrades to a bare token count
when there is no window (`:87-93`). `TokenFormat` avoids `String.format` on
purpose — Turkish locale (`:122-124`). That rule is **not** followed at
`UsageLine.kt:102,114,143-144` and `ChatScreen.kt:1039`.

### `MarkdownText` (`app/src/main/kotlin/com/inhuman/ui/chat/MarkdownText.kt:63-149`)

While streaming: `rememberStreamingMarkdownState()` plus a manual
`for (delta in deltas) streaming.append(delta)` loop, because
`collectAsStreamingMarkdownState` exists in the artifact but is not visible
through this module's Kotlin metadata (`:98-107`). The `Markdown` call is
**positional** because four overloads defeat named arguments (`:117-122`).

The flicker fix is `retainState = true` on the non-streaming path (`:126-141`).
Its doc comment (`:48-52`) says the renderer switches to the plain path "once per
reply, when the stream ends" — **that switch is not implemented**: `deltas` is
never set back to null (`updateParts` at `:1444-1466` does not touch it), so the
force-closed channel object stays in state and the reply keeps rendering through
the incremental renderer for the rest of the session. The `retainState` path
therefore only applies to transcripts loaded from the database.

**A rotation mid-stream loses the text received before it.** The delta channel is
a queue with no replay, so a rebuilt composition starts with an empty streaming
state and only sees deltas sent after the rotation — and `text` is ignored while
`deltas != null`. `DeltaCursor(initialText = …)` looks like it was written for
exactly this and is never called. `AGENTS.md` already notes that a rotation
mid-reply is a live issue for other reasons (`MainActivity.kt:91-98`).

No LaTeX (GPLv2) and no Mermaid, deliberately (`MarkdownText.kt:54-61`).

## The chat screen

`app/src/main/kotlin/com/inhuman/ui/chat/ChatScreen.kt`.

### Narrow reads

Nothing collects the whole state (`:86-99`); each value is subscribed
individually, `messages` is read inside the `LazyColumn` lambda (`:302`), and the
transcript is the only fast-changing value.

### Layout, and where the axis breaks

The transcript is a `LazyColumn` with `comfortableGutter()` padding and
`Space.BetweenMessages` between items (`:310-317`). Below it, a
`Column(horizontalAlignment = CenterHorizontally)` holds the error row and the
composer, each `widthIn(max = ReadableMeasure).fillMaxWidth()` (`:349-351`,
`:387-389`) — **constrain first, fill second**, with the measurement in the
comment: the wrong order put the field at 2208px on a 1280dp tablet while the
messages stopped at 1232.

**The cap does not put the transcript on that axis.** Bubbles are capped at 640dp
but aligned to the list's start/end inside a full-width item, so on a tablet an
assistant bubble starts at the 32dp gutter while the centred composer starts at
320dp. Only the composer and the error row share an axis; `AGENTS.md` describes
the intent as the tutorial and the composer being on the same axis, and that is
true of the composer only.

The cap is also **inert** on five other screens — see
[05-files-sandbox.md](05-files-sandbox.md) for the list and the mechanism.

### Scrolling

Keyed on `messageCount` plus the last message's text length, and only when
already within two items of the bottom (`:202-224`), using `scrollToItem` rather
than an animated scroll (`:220-222`). Auto-scrolling unconditionally would fight
a user reading back.

### The approval dialog (`:423-456`)

Modal. `onDismissRequest = declinePendingCall`, so Back and an outside tap both
**decline** rather than leaving the call pending. The body is a sentence from
`describeCall` (`:1270-1274`) plus Allow / Decline. It also prints the raw
`argumentsJson` in monospace (`:429-443`) — directly under a comment saying raw
JSON is "practically useless", which is a doc-vs-code contradiction rather than a
bug: the raw arguments are the one place a user can see what a call will actually
do.

`describeCall` only special-cases `delete_file` and `write_file`; everything else
falls back to "Run <name>?" (`:1270-1274`).

### Tool rows

One collapsed section per message ("Used N tools", with "N failed" trailing,
`:757-768`). Each line shows the name, the first of `path|query|pattern|name`
(regex-labelled and cached in `argumentPatterns`, `:596-612`) and a status word
(`:533-576`); a declined call renders "declined" in the outline colour
(`:540-546`). Tool *results* are one collapsed row each, with the `— N results`
suffix moved into the trailing slot and `padding(vertical = 0.dp)` because the
header already carries its inset (`:691-719`).

### The gauge

`ContextGauge.kt`: draws nothing when `!isKnown` (`:61`), goes red at ≥90%
(`:66-70`), 20dp ring with a 2.5dp stroke. Tapping opens a panel with the
measured figures and the caveat "Measured from the last reply sent."
(`:161-186`). `contextOf` scans backwards for the last reply that reported
`promptTokens` (`:323-330`) — which is why the figure is one turn behind, and the
panel says so.

`UsageLine.kt`: one line (tok/s · out · in · cache%) with the full breakdown
behind a tap. "speed: too short to measure" is **shown rather than omitted**
(`:111-139`), with the floor at `MIN_SPEED_WINDOW_MS = 100`
(`ChatUiState.kt:226-243`).

## The other screens

### Conversations

`ConversationsScreen.kt` / `ConversationsViewModel.kt`. The primary action is a
FAB on a phone and a top-bar "+" on a tablet, chosen by
`prefersFabForPrimaryAction()` (`:110-111`, `:126-150`).

Deleting asks first, and the **file count is fetched before the question is
shown** (`:88-90`) so the dialog can name what goes with the conversation — the
files are not visible from that screen, and "delete this conversation?" reads
like it removes a list entry. One shared `closeDialog` serves all three dismissal
paths (Cancel, outside tap, Back) because the first version handled them
inconsistently (`:189-204`). The dialog self-dismisses if its conversation
disappears (`:105-108`).

### Agents — what an "assistant" is

`AgentsScreen.kt` / `AgentsViewModel.kt`. An assistant is an `AgentEntity`: name,
verbatim system prompt, optional model, optional free-text effort, thinking flag,
`toolsJson`, timestamps, `isDefault`. The editor is a `WideDialog` with
`LongTextField` for the prompt, model chips including "provider default",
thinking as one row of level chips with "off", and the tool picker.

**Tool picker semantics, which are the subtle part:**

| stored | UI reads | means |
| --- | --- | --- |
| `null` ("All" → `ToolCatalog.allSelection()`) | "Tools — all" | everything, **including tools added in a later release** |
| `[]` ("None") | "Tools — 0 of 10" | nothing |
| a list | "Tools — N of 10" | exactly those |

Toggling any row while in the `null` state first converts the selection to the
explicit ten-name list, so the next tap turns one thing off rather than leaving
"everything" ambiguous (`:476-483`). New assistants get the explicit ten-name
list, never `null` (`AgentRepository.kt:85-98`) — `null` means "everything
forever", which is right for an assistant saved before the choice existed and
wrong as a default.

`ToolCatalog.permits` additionally requires membership in the catalogue, so a
stale or renamed name can never pass (`ToolCatalog.kt:89-92`).

Deleting the default assistant is impossible in the UI **and** in the repository
(`:133`, `AgentRepository.kt:65`). Saving requires a non-blank name *and* prompt
(`AgentsViewModel.kt:52`). The screen's FAB is unconditional —
`prefersFabForPrimaryAction()` is used only by ConversationsScreen (`:133`).

"Preview request" shows the assembled prompt plus a model/thinking/tool footer
(`:594-703`), which is the only place a user can see what the model is actually
sent.

### Files

`FilesScreen.kt` / `FilesViewModel.kt`. Browse, preview text (binary and oversize
are *reported*, not rendered as mojibake, `FilesViewModel.kt:146-177`), import via
`OpenDocument`, export via `CreateDocument`, delete with a dialog, and a header
showing the workspace quota as `files · bytes`. All paths go through
`SessionWorkspace.resolve` (`:96-100`, `:189-198`).

Two problems: `workspace()` calls **`runBlocking`** for a database read on the
main thread (`:96-100`), on every refresh/open/delete/import; and `refresh()`
walks the workspace tree **twice** (`usedBytes()` and `fileCount()`, `:138-141`)
— the exact cost `SessionWorkspace.usage()` exists to avoid.

### Settings

`SettingsScreen.kt` / `SettingsViewModel.kt`. One API-key field
(password-transformed, pre-filled from the store), Save (a blank value removes
the key and calls `invalidateModels`), "Test connection" calling `GET /models`
and reporting the models it sees, and the Demo-mode switch.

**Demo mode is the offline verification path.** `secure.get("demo.mode") == "1"`
(`ChatViewModel.kt:647`); `configured` is `demo || hasKey` (`:641-645`). With
file tools on, the demo creates a file and then asks to delete it, so the tool
loop, the approval gate and the declined state are all reachable with no key, no
network and no bill. Send `plain` in a message to get prose instead of a tool
turn.

## No API key, and no demo mode

- The composer is disabled with the placeholder "Add an API key to start"
  (`ChatScreen.kt:371`, `:1001`), and attach and send are disabled with it.
- `send()` refuses with "Add a DeepSeek API key first, or switch on demo mode."
  (`ChatViewModel.kt:690-695`); `regenerate()` refuses likewise.
- Demo mode counts as configured **without** a key, and the demo model list is
  fetched separately by `demoModelsIfEnabled` (`:301-302`) only so the context
  gauge has a denominator.
- The model picker's error branch is **dead**: `reloadModels()` swallows the
  failure and returns an empty list (`:333-334`), so `error` is never set and a
  key-less user gets an empty picker with no explanation
  (`ModelPickerDialog.kt:71-77`). Selecting a model also does not dismiss the
  dialog (`:97-103`).

`AndroidManifest.xml` declares **no permissions** and only `MainActivity`.
`INTERNET` arrives by manifest merging from `core/ai`, and
`WorkspaceDocumentsProvider` from `core/data`.

## Tests, and what they carry

| Test | Asserts |
| --- | --- |
| `DeltaCursorTest` | The property "the pieces joined are the whole reply, however it is cut", over every chunk size; no emission when nothing is new; no duplicate on a replayed prefix; a restart on shrink; the `initialText` start; silence for an empty reply. |
| `ContextUsageTest` | Window-without-measurement is known but not computable; no window ⇒ no fraction and `summary` is a bare count; neither ⇒ nothing drawn; 25% of 1M ⇒ `262K / 1M`; a non-zero amount never shows 0%; over-window clamps to 100% / 0 left; `contextWindow = 0` treated as absent; `TokenFormat` grouping, including "10K" for 9,960 and never a comma. |

Both are pure JVM logic for the reason `AGENTS.md` gives: the device cannot
observe these states, so the argument has to live in a test.
