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

### `runTurn` (`:1444-1524`)

Builds the provider (`DemoProvider()` or `DeepSeekProvider(apiKey!!)`),
`buildTools()`, and loops up to the user's **tool-round limit** — Settings →
04 / Turn limits **or** the chat menu's Tool rounds per turn; both write the same
`SecureStore` key, so they cannot disagree. Default `DEFAULT_TOOL_ROUNDS = 8`,
bounded 4–64. A round is one model response plus its tool calls, so this is not a
count of individual calls. A second global budget,
`DEFAULT_TOOL_CALLS = 32` (1–256), counts every call across all rounds; a round
that asks for more calls than remain executes only the remaining ones and answers
the rest as skipped, then the turn stops with an explicit error. Empty
`toolCalls` ends the turn; falling out of either loop sets an explicit error
("Stopped after N tool rounds/calls…") rather than ending silently.

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

Per call, in order (line numbers from `ChatViewModel.kt`, which has moved twice
since this file was written — search the marker text if they are off):

1. Unknown tool → an error result and `continue`.
2. Arguments are re-parsed (they were parsed once at `ToolCallEnd`); a parse
   failure appends a result and `continue`s. For every remote tool the same
   normalization the tool uses is applied first (some models wrap the arguments
   in an `arguments` string); a call still missing host/user, or the tool's own
   required fields (`command`, `localPath`/`remotePath`), gets an error
   **without** a consent dialog.
3. The global SSH switch is re-checked for every remote tool, because it can be
   turned off while a turn is already running. Then **the configured rules are
   consulted**, and `Deny`/`Blind` are answered here as refusals rather than
   questions — `needsApproval` can only say "ask", and asking about something
   the user forbade would be the wrong question with a confusing answer. The
   result body is `REFUSED_MARKER + …` and the chip goes `Failed`, not
   `Declined`.
4. **SSH capability grant**, before the ordinary approval branch: if this
   conversation has not acknowledged SSH, `pendingSshConsent` is set and
   `awaitSshConsent()` suspends. Allow persists `conversations.sshAllowed` and
   continues; Cancel appends a declined result and ends the turn. A revoked
   grant (or a global off/on round trip) is re-read from the database before
   this check, not trusted from the in-memory flag.
5. `policy == Ask`, **or** the tool's own `needsApproval(parsed)` →
   `pendingApproval` in state, then **`awaitApproval()` suspends the turn**.
   `SshExecTool` returns false from `needsApproval`; after the capability grant
   its calls follow the ordinary policy (Free by default).
6. Declined → the `Declined` chip is set **before** the result is appended, the
   result body is `DECLINED_MARKER + …`, and the function returns `false`. The
   ordering is deliberate: without it the call stayed `Ready` forever, and a
   refused call rendered as one that was merely waiting — the one state a user is
   most likely to misread as "it is about to happen".
7. Approved → `Running` → `tool.execute` on `Dispatchers.IO` → `Done`/`Failed` →
   result appended.

**Only the execution moves off the main thread.** Approval stays on it, because
it suspends on the user and the continuation is resumed from the UI (`:1133-1146`).
The measured reason for the move: one `write_file` costs about 106 ms in a
2,000-file workspace, and a turn that writes several files pays it several times.

**Any `tool.mutates` call sets `mutated`, whether it succeeded or not**
(`:1155-1168`). A failed write still means the turn *tried* to change the
workspace. It is a record about replay-with-tools; the final-answer retry keeps
the definitions for cache matching but suppresses calls, so this flag is not
used to refuse a pure-text retry.

### The approval plumbing

One `CancellableContinuation<Boolean>` field, set by `awaitApproval()`
(`ChatViewModel.kt:1329`). `approvePendingCall` / `declinePendingCall` clear
`pendingApproval` and resume the continuation only if it is still active.

Two things to know: **the `pending: PendingApproval` parameter of
`continueAfterApproval` is never read** (`:1334`), so approval is not matched
against the call it names — and because `pendingApproval` is a single field,
several approval-needing calls produce **sequential dialogs**, not a collected
batch.

### The SSH consent plumbing

SSH has its own `CancellableContinuation<Boolean>` and its own state field,
`pendingSshConsent`, because it asks a different question. The ordinary gate
asks "may this call happen?"; this asks "may this conversation use SSH at all?".
`allowSshConsent`/`denySshConsent` clear the state and resume; `stop()` cancels
the continuation and clears the state, so a cancelled turn cannot leave a
dialog that does nothing.

The grant is persisted (`ConversationRepository.setSshAllowed`) and shown in
`ChatUiState.sshAllowed`; the top-bar overflow menu exposes **Revoke SSH
access** while it is true. Turning the global switch off clears every
conversation's grant, and the next SSH call re-reads the column so an
off→on round trip cannot inherit the old consent.

## Regeneration, without re-running tools

`regenerate()` refuses while streaming; targets the last non-user, non-tool
message; requires a key or demo mode; truncates state to just before the old
reply; runs the turn with the **tool definitions still present**; and then
patches the new reply's branch metadata instead of reloading the transcript.

The safety rule is no longer "offer no tools". The request keeps the same tool
definitions and history so its prefix matches the original tool-bearing prompt
and can hit the context cache. A transient system instruction (not persisted)
then asks for prose and forbids calls; if the model returns a call anyway, the
app refuses it before execution. Nothing is written and no SSH command runs.

A tool-call-only tail is safe for the same reason: the model has the tools in
context but is instructed not to use them, and the app enforces that instruction
at the execution boundary. The old answer is kept as a sibling and remains
reachable through the branch switcher.

## Rollback and checkpoint regeneration

Before the first **reversible mutating** call of a turn, the app captures a
content-addressed `start` checkpoint. After each completed tool group it
captures another checkpoint keyed by the last tool-result id. If the start
capture fails, the mutation is refused rather than run without an undo record.
Read-only turns need no checkpoint.

The last turn has one **Rewind** action (an undo icon) on the newest answer's
action row, beside Source and Copy. For a turn whose answer has not arrived yet,
the same icon falls back to the last user message. Tapping it opens a picker of
restart points; each point is a prefix that the next request can reuse from the
provider's cache, and everything after that point is removed:

- **Answer again** keeps every tool result and asks only for a new final answer.
  The tools stay in the request for prefix-cache matching, but calls are
  suppressed, so no SSH command, write or paid search can run again.
- **From your message** restores the start checkpoint, removes the turn's tools
  and answer, and runs the turn again **with tools**, so new calls are possible.
- **After tool group N** restores that group's checkpoint, keeps the prefix and
  its tool results, and asks again with tools. The suffix is replaced, not kept
  as a branch, because its tool results would no longer match the restored
  workspace.
- **Edit your message** restores the start checkpoint, removes the user message
  and every descendant, clears the parent's selected child, and puts the text and
  attachments back in the composer. A restore failure leaves the rows untouched.
  Only that turn's checkpoint directory is deleted; older turns remain according
  to the retention setting.

Points whose discarded suffix contains an irreversible tool are disabled with the
reason shown. The same picker is the only place regenerate and rewind now live;
there is no separate refresh or history icon.

`Tool.reversible` / `ToolCatalog.Entry.reversible` is the gate. `ssh_exec` and
`ssh_upload` are the irreversible tools: a turn containing either shows the reason
and **no Rewind**. `ssh_download` is remote but writes a workspace copy, so it is
reversible. A checkpoint after an SSH call can still be selected if only
reversible work is discarded; the SSH call stays in the prefix.

The chat menu lists every retained turn with its checkpoint count, size and
timestamp, plus the total, and lets the user keep 1/3/5/10 turns. Rewinding one
turn replaces only that turn; older turns are not truncated. A mid-turn
regeneration trims manifests the rewritten transcript no longer references, so
that turn's checkpoints are replaced.

The two regeneration paths have different cache behaviour, and the turn's usage
line says so. The final-answer refresh keeps the tool definitions and appends a
transient no-tool instruction after the prefix, so the prefix can hit (measured
`prompt=2148 cache=1920`, 89%). A checkpoint regeneration keeps the tools and
prefix without the instruction; it measured `cache 93%`. A retry that removed
the definitions used to produce `prompt=191 cache=0`, which was a different
request rather than a cache reset. Zero is displayed rather than hidden; the
expanded line shows `from cache N of M`. A multi-round turn sums its rounds, so
its percentage is the turn's cache share rather than any one request's.

The final assistant row carries the turn's changed paths (`changedFilesJson`,
schema v10), computed by hashing the workspace against `start`. They are shown
under the answer and listed in the Rewind confirmation.

`ConversationRepository.deleteSubtree` walks the conversation rows and deletes
the root plus descendants in one transaction; `deleteDescendants` keeps the
chosen boundary and removes what is below it. An empty conversation keeps its
stored title until the next send updates it.

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

### `MarkdownText` (`app/src/main/kotlin/com/inhuman/ui/chat/MarkdownText.kt:118-233`)

While streaming: `rememberStreamingMarkdownState()` plus a manual
`for (delta in deltas) streaming.append(delta)` loop, because
`collectAsStreamingMarkdownState` exists in the artifact but is not visible
through this module's Kotlin metadata (`:154-161`). The streaming `Markdown`
call is still **positional** because four overloads defeat named arguments;
only `components` is named (`:199-205`).

The flicker fix is `retainState = true` on the non-streaming path (`:224`).
The switch its doc comment (`:100-106`) describes now happens: when the stream
ends, `ChatViewModel` updates the reply with `deltas = null` alongside
`isStreaming = false` (`ChatViewModel.kt:1009-1021`), so the finished reply is
handed to the plain renderer and `retainState` applies to it too.

**A rotation mid-stream no longer loses the text received before it.** The delta
channel is a queue with no replay, and a rebuilt composition starts with an empty
streaming state — so `MarkdownText` appends the message's `text` through a
`DeltaCursor` before it starts following the channel (`:165-190`); the cursor
emits only what the renderer does not already have. Verified by reading the code;
the cursor's rewind cases have unit tests, but the composition itself has no
device observation, because this happens inside a stream.

**Table cells wrap rather than truncate.** The renderer's 0.45.0 defaults are
`maxLines = 1` and `TextOverflow.Ellipsis` for every cell, so with its fixed
160dp column any cell longer than a few words became "…" — while the table
itself still scrolled horizontally, and scrolling only helps if there is content
left to scroll to. `chatMarkdownComponents` (`:37-66`) replaces only the `table`
component: the header and row blocks pass `maxLines = Int.MAX_VALUE` and
`Clip`. It also re-supplies the m3 checkbox, because `markdownComponents()`
would otherwise fall back to the base renderer's. Verified on both AVDs: the
tablet renders the full two-line cost cell, and the phone scrolls the same
table horizontally and shows that cell wrapped, not ellipsized.

**Malformed Markdown stays readable, and is never silently rewritten.** If a
finished reply leaves a fence open, `openFence` (`MarkdownFence.kt`) finds the
unmatched opening line and the answer shows a small banner: the rest is being
displayed as code. **Render rest** removes that stray opening fence for the
rendered view only; the Source icon switches the answer to the raw Markdown,
selectable in a monospace block. The stored message is untouched in both cases,
which is the point: a transcript is a record of what the model said, not a
document the app is entitled to repair. Appending a closing fence at the end was
the first idea and is wrong for the shape that prompted this — a model that
writes one opening fence too many and then continues with headings and a table;
the close must be removed, not moved to EOF. `MarkdownFenceTest` covers the
paired, unclosed, info-string and longer-close cases.

No LaTeX and no Mermaid, deliberately (`MarkdownText.kt:110-115`). For LaTeX the
licence is not the obstacle (JLaTeXMath carries a linking exception); the fonts
and the notices file are — see the root `README.md`.

## The chat screen

`app/src/main/kotlin/com/inhuman/ui/chat/ChatScreen.kt`.

### Narrow reads

Nothing collects the whole state (`:86-99`); each value is subscribed
individually, `messages` is read inside the `LazyColumn` lambda (`:302`), and the
transcript is the only fast-changing value.

### Layout, and where the axis breaks

The transcript is a `LazyColumn` with `comfortableGutter()` padding and
`Space.BetweenMessages` between items, capped at **900dp** and centred on the
window. A new conversation gets a left-aligned welcome panel inside the same
measure: provider, model and workspace are read as metadata rather than as a
chat greeting.

**Assistant answers are the document, not a bubble.** User messages are capped
at **560dp** and aligned to the end, with a `YOU` label and a hairline-bordered
surface. An assistant reply has no bubble, no background and a **900dp** measure:
prose, code and tables get room, but a 1,200dp line is not allowed to become a
wall. Tool-result rows use the same 900dp measure. The composer and error row are
centred at 900dp too, so on a tablet the transcript and the input share an axis.

The 640dp `ReadableMeasure` still governs forms and settings screens; the chat
column is wider because it contains code and tables, not just prose. The cap was
inert at seven other sites; all now constrain first and fill second, and the
576dp tablet measurement is in [07-gotchas.md](07-gotchas.md) B5.

### Window ratios, not just width

`WindowFrame` (`core/design/.../Layout.kt`) classifies both axes:
`Compact/Medium/Expanded` width (`<600/<840/≥840`) and height
(`<480/<900/≥900`), plus font scale. The derived facts are the ones the shell
actually asks: `isCompact` if either axis is phone-sized, `usesTwoPane` only for
Expanded width with at least Medium height, and `usesFab` only for Compact width.

That fixes two concrete failures found by running the matrix:

- **16:9 landscape phone, 640×360.** Width-only logic called it a tablet and
  produced a two-pane rail with almost no vertical room. It is now a single
  pane, with Create in the header rather than an overlapping FAB.
- **700×700 square foldable.** Width-only logic also gave it the tablet shell.
  It is now a single pane; the header has Create because a FAB over a short list
  covers a row.
- **360×640 @ 1.5× font.** Three 48dp header destinations plus the wordmark
  pushed Settings off-screen. The header now uses one overflow menu when
  `usesHeaderOverflow` is true.

`WideDialog`'s body ceiling is derived from the window height instead of a fixed
420dp, so a form opened in a 360dp-tall landscape window still has visible
actions. The Wire sheet uses `isCompact` for both its field layout and its
height, which is why a wide, short window stacks fields rather than trying to
draw tablet columns.

The ratio matrix is in `AGENTS.md` and is part of verification, not an optional
extra.


**The composer must not consume `safeDrawing` before `imePadding()`.** That
inset includes the IME; consuming it first left the composer under the keyboard.
This was found by opening the keyboard, not by reading the modifier chain.

### Scrolling

The transcript has a **follow intent**, not an `atBottom` condition alone.
`followLatest` starts true; any nested-scroll source except `SideEffect` turns it
off. The old code compared only `NestedScrollSource.UserInput`, but current
Compose reports a drag/fling as `Drag`/`Fling`, so following stayed on during a
manual scroll and the list snapped back. Following is turned back on only when a
gesture/fling ends at the bottom, never because a fast fling passed through the
bottom for a frame. A `programmaticScroll` flag keeps the app's own scroll from
being mistaken for a user gesture.

One stable layout observer replaces the old effect keyed on message count, text
length, last-item height and offset. Those geometry keys changed *because of*
the programmatic scroll, cancelling and restarting the effect — the visible
jump/skip. The observer conflates layout changes and scrolls only while
`followLatest` is true, no programmatic scroll is running, the user is not
scrolling, and the list is not already at the bottom. This also covers opening a
long conversation before its first layout has been measured.

When the newest row is already visible, `scrollToLatest` does not call
`scrollToItem`: that aligns its top with the viewport top and the following
`scrollBy` has to run back down, which used to jump on every streaming tick.
When `followLatest` is false, a `FilledIconButton` with a down arrow overlays the
transcript's bottom-end; it sets `followLatest = true` before walking forward in
finite `scrollBy` steps.

The button is gated on **`!followLatest`**, not **`!atBottom`**. During a stream
the growing last row moves below the viewport between automatic scrolls, so
`atBottom` alternates and the button appeared/disappeared on every token. The
intent flag stays true across content growth; only user input releases it.

### Errors are actions

`chatErrorAction` maps the provider/turn error string to one recovery:
`Retry`, `CompactAndRetry`, or `OpenProviderSettings`; the raw message remains
available under **Copy details**. The classification is conservative — an
ordinary UI error gets no action. **Retry** calls `regenerate()`, which keeps
the tool definitions for the cache prefix but suppresses tool calls, so a
failure after a write cannot execute it twice. **Compact and retry** persists
the summary before retrying, so a second failure does not throw away the paid
summarizer work.

### Search and jump-to-message

Home's search field searches conversation titles and user/assistant text through
`ConversationRepository.search` (escaped `LIKE`, newest first; title matches
first, inactive branches excluded). Tapping a message hit opens the conversation
with `focusMessageId`; `ChatScreen` waits until the list has measured, disables
follow, scrolls to that item and keeps a subtle primary-tinted band for 2.4 s. A
title hit has no message id and opens the conversation normally.

### Wire inspector

`WireSheet.kt`. The chat menu offers **Preview next request** and **Inspect last
request**. The transcript has **one `{}` action per completed turn**, on its
final assistant row; the sheet lists every assistant round of that turn, so a
five-round tool turn no longer shows five wire buttons. The sheet is a readable
projection of the real body, not a second serializer:
`ChatProvider.previewRequest` must call the same builder as `stream`, and the
last body is kept in `ChatViewModel.wire` in memory. It shows:

- request parameters (`model`, `stream`, `thinking`, `reasoning_effort`,
  `stream_options`, `user_id`);
- the composed system message split into assistant prompt and compaction
  summary;
- tool names, descriptions, typed parameter tables and an optional raw schema;
- numbered messages with role, text, `content: null`, `reasoning_content`,
  `tool_calls` (call id, name, parsed fields, raw arguments) and linked
  `role: tool` results.

Long system blocks, message bodies, reasoning, tool results and arguments are
collapsed to a few lines with **Show more**. An expanded block is bounded to
240dp with its own scroll and puts **Show less** at the *top*, so leaving a long
prompt no longer requires scrolling to its end. Images are shown as
`[image data URL · base64 omitted]`; raw JSON also redacts the data URL. A
**Raw JSON** toggle remains available for protocol work, and every sheet has
**Copy JSON**. Demo mode has no provider body and says so rather than inventing
a fake request.

The `{}` action shares the existing bottom action row (source, copy, rewind)
when that row exists, so the turn footer adds no extra line; a tool-only final
round with no text and no rewind uses the assistant header instead. The old
separate **View wire format** text link inside the tool list was removed as a
duplicate, and the action is no longer gated on `toolCalls`: `content`,
`reasoning_content` and `tool_calls` all need the same inspector.

On compact widths the sheet switches from parameter tables to stacked
key/value blocks, so a search query is not squeezed into a ribbon beside a fixed
label column. Tool calls lead with the tool name and a shortened call id; the
exact id remains in **Raw JSON**. The sheet is capped at `Layout.ChatMeasure`
on a tablet rather than spanning the whole 1280dp window. The phone header puts
**Raw JSON** and close beside the title and moves the subtitle onto its own line,
because the previous row wrapped both.

The request snapshot is deliberately not persisted: it is a view of one
in-flight/last request, not another copy of the transcript. The assistant raw
view is reconstructed from the already-persisted `toolCallsJson`, so it survives
a reload.

### The approval dialog (`:423-456`)

Modal. `onDismissRequest = declinePendingCall`, so Back and an outside tap both
**decline** rather than leaving the call pending. The body is a sentence from
`describeCall` plus Allow / Decline. It also prints the arguments in monospace
— directly under a comment saying raw JSON is "practically useless", which is a
doc-vs-code contradiction rather than a bug: the arguments are the one place a
user can see what a call will actually do. For the SSH tools the view model
supplies `PendingApproval.details` instead: host, port, user, auth **type** and
the operation (command or the two transfer paths), never the password or private
key.

`describeCall` special-cases `delete_file`, `write_file`, `web_search`,
`web_fetch`, `ssh_exec`, `ssh_upload` and `ssh_download`; the web tools also say
"(paid search/fetch API)", because an approval prompt is exactly the moment the
cost is worth repeating, and SSH names the host and first 160 characters of the
command or the source/destination paths. Everything else falls back to "Run
<name>?".

### The SSH consent dialog

Separate from the ordinary approval dialog, deliberately. It is a capability
grant, not a question about one call: the text says the agent can reach **any**
host it is given credentials for and will not ask again in this conversation.
It shows the first command or transfer, warns that remote damage can be
irreversible, and warns that a pasted password/key goes into the conversation and
to DeepSeek (while a workspace key file is read locally). The checkbox is required
before "Allow SSH"; Cancel and Back both decline.

The body follows the selected tools. `ssh_exec` present -> "run any command";
`ssh_upload` without exec -> read/download/upload, explicitly "cannot run
commands"; only list/read/download -> read-only text, no upload and no commands.
The warning changes with it, so a read-only grant is not told that the agent can
delete data or stop services. Adding `ssh_exec` or `ssh_upload` to a grant that
did not have it clears `sshAllowed`, so the next call asks again; adding
`ssh_list`/`ssh_read_file` does not.

The checkbox sits **above** the long warning, not after it. On a phone the body
scrolls; with the checkbox last it fell below the fold, so "Allow SSH" was
disabled with no visible reason. Verified at phone width and on the pad.

### Conversation tools

Chat overflow -> **Conversation tools**. The sheet lists
`conversationToolPlan(sshEnabled, agentTools, conversationTools)`: the assistant's
tools plus the conversation's existing ones, filtered through
`ToolCatalog.visible(sshEnabled)`. It shows the same badges as the assistant
picker, and the footer warns when a selected tool is remote or irreversible.
Apply writes `conversations.toolsJson`; Cancel changes nothing. The row is
disabled while a turn streams, and `ChatViewModel.setConversationTools` refuses
the call as a second guard. Changes apply to the next turn, not the request
already built.

There is **no second confirmation dialog**. The sheet plus Apply is the
deliberate action; adding a modal on top would be the per-change approval this
project avoids. The rule that matters is that the model cannot call anything to
change the set. The ceiling is why a conversation cannot invent a tool its
assistant never had; restoring an old tool the conversation already held is
allowed. A stored remote name survives an edit made while SSH is off, so
switching SSH off does not silently forget the selection. See
[11-conversation-tools.md](11-conversation-tools.md).

An inspection profile is just a selection: `ssh_list` + `ssh_read_file` +
`ssh_download`, with `ssh_exec` and `ssh_upload` unchecked. No mode, no second
settings screen. `ssh_download` still works without `ssh_exec`; the tools are
registered independently.

### Tool rows

One section per message ("Used N tools", with "N failed" trailing,
`:779-790`). Each line shows the name, the first of
`path|host|query|pattern|name` (regex-labelled and cached in
`argumentPatterns`, `:596-612`) and a status word (`:533-576`); a declined call
renders "declined" in the outline colour (`:540-546`). `host` is in the key list
specifically so an SSH line never falls back to raw JSON that could contain a
credential.

**The newest working section is open; older ones collapse.** This applies to
`Thinking` and to the tool-call group alike: while a turn streams, the section
being produced is expanded, and it closes as soon as a newer one — the next
thinking block, the next tool call, or the answer text — exists. The rule is
`liveAssistantSection` (`ChatUiState.kt`), unit-tested in
`LiveAssistantSectionTest`: only the latest assistant message of a streaming
turn has a live section, a tool call supersedes reasoning, and the answer closes
both. A preamble before the first call ("I'll search for that") is text but not
the answer: `ChatMessageUi.answerAfterCalls` records text that arrives *after* a
call started, so the tool section stays open while the calls run. Without that
distinction the section named after the tools closed the moment the model
announced it was about to use them.

`CollapsibleSection.autoExpand` applies the decision only when it *changes*, so a
user who opens an old section keeps it open and a user who closes the live one is
not overridden on the next token.

Tool *results* are one collapsed row each, with the `— N results` suffix moved
into the trailing slot and `padding(vertical = 0.dp)` because the header already
carries its inset (`:700-721`). They are not part of the live-section rule: a
result can be a whole page, and expanding it while a turn runs would bury the
next thing being produced.

### Context compaction

`ContextCompaction.kt` / `context_summaries` / the gauge panel. The request
projection can replace older complete turns with a model-written summary:

- **Manual, not automatic.** A summary costs a model call on a real provider and
  changes what the model sees; the user opens it from the gauge panel and sees a
  preview of how many messages/turns will be covered before confirming.
- **The transcript is untouched.** Only `context_summaries` changes. A
  `CONTEXT COMPACTED` banner and **View summary / Use full history** expose and
  reverse it.
- **Boundaries are turn boundaries.** `planContextCompaction` keeps the recent
  turns verbatim (two, then one on a second pass) and summarizes complete older
  turns. A tool call and its results are never split.
- **The wire gets a system summary plus the suffix.** `buildWireHistory` checks
  that `throughMessageId` is still on the active path; if it is not, the full
  history is sent. The system prompt and summary are combined into the first
  system message.
- **Failure is a no-op.** No key, timeout, provider failure, blank summary or a
  changed transcript leaves the previous projection intact and reports the cause.
  Demo mode uses `demoCompactionSummary`, a deterministic list of questions and
  touched files, so the path is testable offline.
- **The summarizer never gets the whole history unbounded**:
  `compactionTranscript` bounds the input and truncates per message. It is not
  asked to answer the conversation, only to preserve goals, constraints,
  decisions, paths, unresolved work and important tool results.

The remaining limit is honest: if the kept recent turns alone exceed the window,
compaction cannot make the request legal. **Compact again** narrows to one kept
turn; a single oversized turn still has to be rewound or started fresh.

### The gauge

`ContextGauge.kt`: draws nothing when `!isKnown` (`:61`), goes red at ≥90%
(`:66-70`), 20dp ring with a 2.5dp stroke. The one-line summary leads with the
percentage, then the compact counts (`25% · 262K / 1M`); without a reported
window there is no percentage and only the count is shown. Tapping opens a panel
with the measured figures and the caveat "Measured from the last reply sent."
(`:161-186`). `contextOf` scans backwards for the last reply that reported
`promptTokens` (`:323-330`) — which is why the figure is one turn behind, and the
panel says so.

`UsageLine.kt`: **one line per completed turn**, at its end, prefixed "Turn
total": tok/s · out · in · cache%, with the full breakdown behind a tap. It sums
the turn's rounds; the first-token figure comes from the first round, while
generation time and token counts are added. "speed: too short to measure" is
**shown rather than omitted** (`:111-139`), with the floor at
`MIN_SPEED_WINDOW_MS = 100` (`ChatUiState.kt:226-243`). The grouping and the sums
are `turnSummariesOf` / `aggregateUsage` in `TurnSummary.kt`, tested without
Compose because the per-message repetition was a layout decision, not a
rendering fact.

## The other screens

### Conversations

`ConversationsScreen.kt` / `ConversationsViewModel.kt`. The primary action is a
FAB on a phone and a top-bar "+" on a tablet, chosen by
`prefersFabForPrimaryAction()` (`:110-111`, `:126-150`).

Deleting a conversation asks first, and the **file count is fetched before the
question is shown** (`ConversationsViewModel.kt:118`, read at
`ConversationsScreen.kt:187`) so the dialog can name what goes with the
conversation — the files are not visible from that screen, and "delete this
conversation?" reads like it removes a list entry. One shared `closeDialog`
serves all three dismissal paths (Cancel, outside tap, Back) because the first
version handled them inconsistently. The dialog self-dismisses if its
conversation disappears.

**This confirmation is not the tool-approval gate and is not affected by the
approval rules.** It is the user deleting their own conversation, so there is no
question about who decided. Do not remove it on the grounds that "deletion no
longer asks" — that sentence is about the model's `delete_file` call, not about
a person pressing delete in a list.

The home list also has **Select**: row taps toggle checkboxes, long-press enters
selection, and Back cancels it. Delete asks once for the whole selection and
says explicitly that temporary workspace files go while a permanent workspace
and its files stay. The bulk action calls `ConversationRepository.deleteConversations`,
which applies the same per-conversation cleanup rather than a shortcut that
could skip rollback snapshots.

### Agents — what an "assistant" is

`AgentsScreen.kt` / `AgentsViewModel.kt`. An assistant is an `AgentEntity`: name,
verbatim system prompt, optional model, optional free-text effort, thinking flag,
`toolsJson`, timestamps, `isDefault`. The editor is a `WideDialog` with
`LongTextField` for the prompt, model chips including "provider default",
thinking as one row of level chips with "off", and the tool picker.

**The prompt can also be imported from a file.** `Import` opens SAF and reads a
`.txt`/`.md` as strict UTF-8, stripping a BOM; a blank file, invalid UTF-8 or a
non-text pick is an error rather than a prompt full of replacement characters.
The 1 MiB read ceiling is a guard against pulling a mis-tapped video into memory,
not a prompt limit — there is no stored or sent length cap. If the prompt already
has text, the import asks **Replace / Append / Cancel**. Files with an unknown
extension are accepted when the provider reports a `text` MIME type, because
Android providers disagree about `.md`; the extension check keeps those files
usable when the MIME type is `application/octet-stream`.

**Tool picker semantics, which are the subtle part:**

| stored | UI reads | means |
| --- | --- | --- |
| `null` ("All" → `ToolCatalog.allSelection()`) | "Tools — all" | everything, **including tools added in a later release** |
| `[]` ("None") | "Tools — 0 of N" | nothing |
| a list | "Tools — N of M" | exactly those |

`M` is the **visible** catalogue: `ToolCatalog.visible(sshEnabled)`. While the
global SSH switch is off, every SSH tool is absent from the assistant picker, the
per-assistant rules, the global approval table, the "Preview request" footer and
the home rail's tool summary. It is not shown as a disabled row: a greyed entry
still tells the user, and any prompt built from the label, that remote execution
exists. When enabled they reappear and remain opt-in; "All" still excludes them
(`ToolCatalog.visible`).

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
(`AgentsViewModel.kt:69`). A new assistant is pre-named "New assistant" (with a
numeric suffix if taken) and starts from `Database.DEFAULT_SYSTEM_PROMPT`, so
Save is enabled immediately; clearing either field disables it, and the editor
says whether the name or the prompt is missing. The screen's FAB is
unconditional — `prefersFabForPrimaryAction()` is used only by
ConversationsScreen (`:133`).

"Preview request" shows the assembled prompt plus a model/thinking/tool footer
(`:594-703`), which is the only place a user can see what the model is actually
sent.

### Workspaces

`WorkspacesScreen.kt` / `WorkspaceDetailScreen.kt`, with their view models. A
workspace is a container for files and conversations, not a conversation
property:

- **Create** is the single creation entry on the home screen: a FAB on a phone, a
  top-bar button on a tablet. It opens a three-row sheet — **New conversation**
  (temporary workspace), **New workspace** (hands off to the Workspaces screen and
  its name dialog), and **Choose a workspace** (a second-level list of permanent
  workspaces). Choosing one carries its `workspaceId` through the assistant
  picker; the conversation row is then inserted in that workspace. Each row has
  one short description, and there is no workspace list on the first level.
- **Workspaces** is reachable directly from the home top bar (the folder icon on
  a phone, the text action on a tablet); it lists permanent workspaces with name,
  conversation count, and update time. The home top bar no longer has an overflow
  menu: Workspaces, Assistants and Settings are direct destinations on both form
  factors.
- **Workspace detail** shows usage, the session list, **Files**, and **New
  conversation**. New conversations created here use that workspace and ask for
  an assistant first, exactly like the home flow.
- **Save workspace as permanent** is in the temporary conversation's overflow
  menu. It asks for a name and changes only the workspace row; the directory does
  not move, so the action is immediate and idempotent. After promotion the menu
  item disappears and the Workspaces screen can add more conversations.
- Deleting a conversation in a temporary workspace deletes the workspace and its
  files, and the dialog says so. In a permanent workspace the dialog says the
  files are kept. Deleting a workspace with conversations is refused; the Delete
  button is disabled and the dialog names the blocker.
- **Workspace location** is a user-facing absolute path with a copy action,
  shown in Files and on the workspace detail, plus **Open in Files app** (the
  system DocumentsUI on the workspace root). It is deliberately absent from
  tools, prompts, and diagnostics. `Files` without a conversation hides the
  per-path policy control: policies are conversation-scoped, and showing a rule
  that would not be consulted would be worse than hiding it.

Shared file state makes two conversation features unsafe, and the chat handles
that explicitly: if a permanent workspace has more than one conversation,
file rollback and the checkpoint restart points in Rewind are disabled with a
reason, because restoring one transcript's snapshot would overwrite another
conversation's files. Snapshot capture still runs; the guard is at the
restore/regenerate entry points.

### Files

`FilesScreen.kt` / `FilesViewModel.kt`. Browse, preview text (binary and oversize
are *reported*, not rendered as mojibake, `FilesViewModel.kt:146-177`), import via
`OpenDocument`, export via `CreateDocument`, delete with a dialog, and a header
showing the workspace quota as `files · bytes`. All paths go through
`SessionWorkspace.resolve` (`:96-100`, `:189-198`).

The same screen opens from a chat (conversation-scoped path rules) and from a
workspace detail (no conversation, so no path-rule control). Its **Workspace
location** action shows the absolute directory for the user and copies it; it
never enters a request. The empty state says "workspace" rather than
"conversation's folder" when opened without a conversation.

A `.md` or `.markdown` preview is rendered through the same `MarkdownText` the
transcript uses, with the Source icon as a rendered/raw toggle: the default is the
readable form, and the raw file is one tap away for a fence that does not close
or a table the renderer lays out badly. `isMarkdownName` decides by extension
rather than MIME, because providers disagree about the MIME type of `.md` and the
name is what the list shows. Everything else stays monospace.

Two problems used to sit here and are fixed: `workspace()` is now `suspend` and
reads the conversation off the main thread, and `refresh()` calls `usage()` once
instead of walking the tree separately for bytes and file count.

### Model picker

`ModelPickerDialog.kt` + `ChatViewModel.models()`. The dialog reads the cached
model list **without making a request**, shows it immediately, and refreshes in
the background. The cache is populated when the conversation resolves its
effective model, so the common case has a list before the dialog is drawn.
Blocking on a forced refetch was the "Model" dialog spinning forever: the
information to fill it was already in memory, but the UI discarded it and waited
for the network.

A genuinely cold list shows "Loading models…" and is bounded twice: the HTTP
call created for `/models` has a **20-second call timeout** (the shared client's
eleven-minute read timeout exists for reasoning replies and must not apply here),
and the dialog has a 25-second guard around the whole load. On failure or
timeout with a cached list, the list stays usable and the refresh failure is
logged; with no list, the error is shown with a **Retry** button. Selecting a
model dismisses the dialog.

### Settings

`SettingsScreen.kt` / `SettingsViewModel.kt`. **Model providers** lists the
profiles; each opens an editor with name, kind, HTTPS base URL, password-style
key, reasoning-field mode, stream-usage switch and fallback model ids. Save writes
the row and key through `AgentRepository.saveProvider`; a blank key removes it.
"Test connection" calls `GET /models` with the unsaved values and reports what it
sees. The built-in DeepSeek profile cannot be deleted. **Web tools** selects the
search and fetch backend independently (DeepSeek/Exa) and stores their keys
separately; a backend without a key shows a warning and its tool stays
unregistered. Demo mode and the SSH switch live below those sections.

Settings is grouped into foldable sections: Providers stays open; Web tools,
Demo & SSH, Turn limits, Backup, Approvals and Diagnostics start collapsed.
Each header states its own state (key presence, current round count, configured
rule count) so a folded row still answers the question that brought the user
there. Open state uses `rememberSaveable`, so rotation does not reset it.

**Turn limits** (`04 / Turn limits`) exposes the same global tool-round setting
as the chat menu. Both write `SecureStore.KEY_TOOL_ROUNDS`; `SettingsViewModel`
reads and writes it through one helper, and `ChatViewModel.refreshConfiguration`
re-reads it when a conversation is entered. A round is one model response plus
the calls it requested, so the label says "Tool rounds per turn"; the section
also owns the separate total-call budget (`KEY_TOOL_CALLS`, default 32, range
1–256). Raising either budget does not change which tools are allowed.

**Display** (`08 / Display`) is a `LayoutPreference` of Auto / Compact /
Comfortable. Auto derives the shell from the window; Compact forces the
single-column shell, and Comfortable keeps the wide shell. It is supplied at the
root through `LocalLayoutPreference`, so rotation and split-screen still work
under Auto, and is persisted under `layout.preference`. It is a reading
preference, not a per-ratio selector.

Settings moved **Backup** to `05`, **Approvals** to `06`, **Diagnostics** to
`07` and **Display** to `08`.

**Demo mode is the offline verification path.** `demoEnabled()` reads
`secure.get(SecureStore.KEY_DEMO_MODE) == "1"` (`ChatViewModel.kt:615`), and
`refreshConfiguration()` publishes `configured = demo || hasKey` (`:609-612`). With
file tools on, the demo creates a file and then deletes it, so the tool loop, the
tool results and the closing summary are all reachable with no key, no network
and no bill.

**It no longer reaches the approval gate on its own.** Deletion does not ask by
default, so the demo's delete call runs. To exercise the dialog and the declined
chip, configure a rule — `delete_file = Ask` — and the same script walks into it.
That is a deliberate consequence of the approval model in
[09-approvals.md](09-approvals.md), not a gap in the demo. Send `plain` in a
message to get prose instead of a tool turn.

## No API key, and no demo mode

- The composer is disabled with the placeholder "Add an API key to start"
  (`ChatScreen.kt:371`, `:1001`), and attach and send are disabled with it.
- `send()` refuses with "Add an API key for <provider> first, or switch on demo
  mode." (`ChatViewModel.kt`); `regenerate()` refuses likewise. The provider name
  comes from the conversation's copied `providerId`, not from a hardcoded vendor.
- Demo mode counts as configured **without** a key, and the demo model list is
  fetched separately by `demoModelsIfEnabled` (`:301-302`) only so the context
  gauge has a denominator.
- The model picker's error branch is reachable: `models()` returns the failure,
  so a key-less user gets an explanation rather than an empty dialog
  (`ModelPickerDialog.kt`). Selecting a model dismisses the dialog.

`AndroidManifest.xml` declares `INTERNET` explicitly (the app's own clients need
it; the sandbox's no-network rule applies to code the model writes), and
`WorkspaceDocumentsProvider` lives in `core/data`.

## Tests, and what they carry

| Test | Asserts |
| --- | --- |
| `DeltaCursorTest` | The property "the pieces joined are the whole reply, however it is cut", over every chunk size; no emission when nothing is new; no duplicate on a replayed prefix; a restart on shrink; the `initialText` start; silence for an empty reply. |
| `ContextUsageTest` | Window-without-measurement is known but not computable; no window ⇒ no fraction and `summary` is a bare count; neither ⇒ nothing drawn; 25% of 1M ⇒ `25% · 262K / 1M`; a non-zero amount never shows 0%; over-window clamps to 100% / 0 left; `contextWindow = 0` treated as absent; `TokenFormat` grouping, including "10K" for 9,960 and never a comma. |
| `WireHistoryTest` | A dangling assistant `tool_calls` gets its missing tool result inserted **before** the next user message, not at the end; a complete block and a call-free assistant message are untouched. Also that SSH tool arguments lose `password`/`privateKey`/`passphrase` before storage, including the wrapped form. This is the HTTP-400-on-every-later-turn repair and the credential-persistence guard. |
| `TurnSummaryTest` | Rounds of one turn sum (prompt, completion, cache, generation); a missing field stays missing; a turn still holding a tool result, a running tool call, or a streaming answer is not complete; two turns get independent footers. This keeps telemetry at the turn end rather than after every round. |
| `ConversationToolsTest` | The assistant ceiling with `null` and explicit selections; a conversation keeps a tool the assistant no longer has; SSH off hides the row but preserves the stored name; applying stores catalogue order. This is the "new tool reaches an old conversation without inventing a capability" rule. |
| `ImageSizingTest` / `ImagePreprocessorTest` | Fitting a 4032×3024 photo into 1568×1176, never upscaling a small image, and choosing a power-of-two sample size, on the JVM; then the same decode/scale/encode on a device, including the 1 MiB ceiling and transparent-PNG-to-WebP. This is the phone-side image pipeline that replaced accepting a 4 MiB file and silently dropping it at send. |
| `SnapshotStoreTest` | `start` and a later tool checkpoint restore their own file versions; added/deleted/modified paths are diffed by content; stats count turns and pruning keeps the newest; `turns` lists each retained turn with its checkpoint count, newest first. This is the file half of checkpointed rollback. |
| `WindowFrameTest` | A 640×360 landscape phone and a 900×400 wide-short window stay single-pane; a 700×700 foldable is Medium/single-pane; 1024×768 and 1280×900 use two panes; a 360dp phone uses a FAB and the 1.5× font case uses the header overflow. This is the axis-classification half; the screenshot matrix is still required for composition mistakes. |
| `ToolCatalogVisibilityTest` | every `remote = true` tool is absent from `ToolCatalog.visible(false)` and present only in `visible(true)`; ordinary tools remain in both. This is the one list every user-facing picker and approval table reads. |
| `TurnNotificationsTest` (instrumentation) | Drives two real `TurnStatus` records through the service: summary plus two children, finishing one demotes to the surviving child, finishing both clears them. Also asserts an awaiting-approval turn has **Review** and **Stop**, and no one-tap Approve/Deny. |

These are pure JVM logic for the reason `AGENTS.md` gives: the device cannot
observe these states, so the argument has to live in a test.
