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

### `runTurn`, and the shared engine

`runTurn` resolves the provider (`DemoProvider()` or the assistant's profile) and
the model, refuses a missing key or model **before** a turn starts, then hands
the turn to `runTurnWithEngine`. There is no second loop: every provider turn,
SSH included, runs through `:core:engine`'s `ChatEngine`.

The two budgets are read once at the start of the turn from `toolRoundLimit()` /
`toolCallLimit()` — the Settings screen and the chat menu write the same
`SecureStore` keys, so they cannot disagree. Round limit default
`DEFAULT_TOOL_ROUNDS = 8` (4–64); call limit `DEFAULT_TOOL_CALLS = 32` (1–256).
A round is one model response plus its calls; a round that asks for more calls
than remain executes the rest and answers the remainder as skipped before
stopping with the call-limit cause.

`runTurnWithEngine` is the Android host. It builds the request history with
`buildWireHistory(includeCurrent = true)`: this path creates no assistant
placeholder before the request, so the default `dropLast(1)` would drop the
user's just-sent message — the "answered the previous turn" bug, B30. On a
text-only retry a real provider keeps the tool definitions so the prefix still
matches the cache, and a transient system instruction plus the engine's guard
stop execution; demo turns get no tools, because the script reacts to
`request.tools` rather than to instructions.

### Time hints on the wire

A user message may carry a `[YYYY-MM-DD HH:mm]` line at its start **in the
request only** (`buildWireHistory`, `app/.../TimeHints.kt`). The stored row and
the transcript keep the user's exact words, so search, Markdown export,
branches, Rewind and the auto-title are untouched; the request viewer shows
what the model actually received. The prefix is app-generated metadata, not
user input and not a capability — it cannot add a tool, change an approval or
widen the workspace.

The rule is a setting (**Settings → `12 / Time`**, `SecureStore.KEY_TIME_HINTS_MODE`
and `KEY_TIME_HINTS_GAP_MINUTES`): **after a gap** (the default), **every
message**, or **never**; the gap thresholds offered are 5/10/30/60 minutes,
default 10. `timeHintPrefixes`/`planTimeHints` are the pure half and
`TimeHintsTest` pins it.

The decision is deterministic from `messages.createdAt`: each message is
compared with the **previous user message**, so a message that qualified once
carries the same prefix on every later request and the provider's prefix cache
still matches. Two consequences are deliberate:

- The conversation's **first** user message always carries its time in gap mode:
  with no previous message there is no gap to compare, and without an anchor a
  conversation whose gaps are all short would never tell the model what day it
  is.
- The only timestamp taken from the clock is the **draft's**, because it has not
  been stored yet; the preview path (`previewNextRequest`) uses the same build,
  so a preview is exactly the body a send would produce.

Changing the setting — or the device's time zone, because the wall clock is
rendered in the current zone — rewrites the user-message part of the prefix and
costs **one cold request**, the same price as editing the `AGENTS.md` block. It
is the only way the hint can cause a cache miss: the prefix itself is stable
across turns. An install that upgrades into the on-by-default setting pays that
one cold request per existing conversation the first time it is used, because
the conversation's first message gains its anchor and any long gap gains its
line. The text carries no zone offset; the model needs the user's wall clock,
and an offset would be tokens on every marked message. Demo mode builds the
history the same way, so the prefix is inspectable without a key.

The engine's events are rendered and persisted here:

- **`RoundStarted`** closes the previous round's delta channel, appends the
  streaming placeholder and resets the accumulators. From round 2 on it first
  saves the checkpoint for the group that just completed, which is what makes an
  "after tool group N" Rewind point possible.
- **`ReasoningDelta` / `TextDelta`** append and publish through the same
  `DeltaCursor` and 40 ms throttle as before.
- **`AssistantMessage`** carries the assembled calls; **`RoundFinished`**
  persists the assistant row once, closes the channel, attaches usage and moves
  the context gauge. A round that produced no text, reasoning or calls persists
  nothing, so a failed provider request does not leave a blank row.
- **`ToolCallResult`** appends the result row, marks the chip `Done`/`Failed`,
  and records `mutated` for a mutating tool: the engine executed it, the host
  records it.
- **`ToolCallDeclined`** is the user's refusal: chip `Declined`, result carries
  `DECLINED_MARKER`, turn ends. It is deliberately not `Failed`; see the gate.
- **`ToolCallSkipped`** answers calls that will not run with `SKIPPED_MARKER`,
  because every recorded call needs a result on the wire.
- **`Failed`** carries an `EngineFailure` cause. The host translates
  `RoundLimit`, `CallLimit` and `ToolCallsSuppressed` into string resources;
  `Provider` keeps the provider's message. Routing real turns through the engine
  had briefly replaced those localised errors with the loop's English text.
- The **`finally`** is the Stop path: it publishes the last window, then, inside
  `NonCancellable`, persists whatever arrived when the flow was cancelled before
  `RoundFinished` (with `STOPPED_MESSAGE`), writes an empty error row if the
  first request failed before a token, or removes the placeholder when nothing
  arrived at all. The whole block is wrapped in `runCatching` and the failure is
  logged: a database error raised from it used to skip both the row close and
  `isStreaming = false`, leaving the composer disabled until the screen was
  recreated, with the exception escaping into the process.

### `gateEngineCall` — the one gate

Before every call the engine asks the host. `gateEngineCall` runs, in order:

1. Parse the arguments; a parse failure is a refused result, not an exception.
   `tool.normalize` unwraps provider envelopes — SSH's `{"arguments":"..."}`
   string and the `hostname` alias — so the gate, the consent dialogue and the
   tool all read the same object. That hook replaced `SshExecTool`'s duplicate
   normaliser and the legacy loop's separate call.
2. For a remote tool: the tool's required fields are checked **before** the
   dialogue (`SshArguments.missingRequired`). The consent dialogue is the
   strongest warning in the app and must not describe a call that cannot happen;
   a call missing host/username/`command`/paths gets an error result instead.
3. The global SSH switch is re-read, because Settings can change while a turn is
   running.
4. A persisted `conversations.sshAllowed` is re-read when the in-memory flag is
   true, so an off→on round trip cannot inherit a revoked consent.
5. Policy: `Deny`/`Blind` refuse with `REFUSED_MARKER`; the chip is `Failed`, and
   the turn continues so the model can adapt.
6. **SSH capability grant**: `pendingSshConsent` is set and `awaitSshConsent()`
   suspends. Allow persists `conversations.sshAllowed` and continues; Cancel
   returns `Deny(declined = true)` and ends the turn.
7. Ordinary approval (`policy == Ask` or `tool.needsApproval(parsed)`):
   `pendingApproval`, an `asked` audit row, and `awaitApproval()`. For SSH the
   dialog carries a `details` string built without credentials. Ten minutes
   without an answer is `timed_out`: the call does not run and the turn ends.
8. `mutates`: an irreversible tool flags the turn; a reversible one captures the
   `start` checkpoint, and a capture failure refuses the call.
9. `Running`, then `Allow`; the engine executes on `Dispatchers.IO`.

**Declined vs refused.** A policy refusal is a tool result (`ToolCallResult`,
chip `Failed`, `stopTurn = false`). A user refusal is a capability answer
(`Deny(declined = true)` → `ToolCallDeclined`, chip `Declined`, turn ends). The
two used to be one event with different text, which made a call the user stopped
look like one that ran and broke.

### Resuming an interrupted turn

The turn is persisted at round boundaries, so a process death leaves the active
branch ending at one of three shapes: a user message (no round saved yet), a
tool result (a round ran, the next did not), or an assistant row whose
`tool_calls` have no results. `isInterruptedTurn` (`InterruptedTurn.kt`, tested
by `InterruptedTurnTest`) recognises exactly those tails. An assistant row with
`error` is deliberately excluded: a provider failure or a user stop already has
Retry, and Continue would be a second retry path with different tool semantics.

The banner above the composer calls `continueInterruptedTurn`. It adds **no user
message**; it builds the wire history from the transcript, appends a transient
`CONTINUE_INSTRUCTION` system message (not stored), and runs the normal engine
turn. The new assistant row is a child of the tail, so the branch model is
unchanged; `closeDanglingCalls` and the reload-time `[interrupted]` repair make
the request valid.

What resumes is the last *saved* step. Text still streaming when the process died
was never written -- persistence is per round, not per token -- and the banner
says so rather than implying a seamless continuation. The original start
checkpoint is reused when it exists (`turnSnapshotReady` is set from
`hasStartCheckpoint`), so Rewind still goes back to before the user's message
instead of treating the resume point as the beginning of the turn.

### Concurrency ceilings

Three hard numbers, none of them a setting:

| Scope | Limit | Where it is enforced |
| --- | --- | --- |
| One conversation | 1 turn | `ChatViewModel.isStreaming`; `send`, `regenerate`, `regenerateFrom` and `continueInterruptedTurn` all refuse while it is true |
| The process | 2 turns | `TurnConcurrency.tryAcquire()` in `runTurn`, released in `finally`; the third turn gets `chat_error_too_many_turns` and is **not queued** |
| One turn | 1 tool at a time | The engine's sequential `for (call in calls)`; `ChatEngineTest`'s "two calls in one round run one at a time" measures overlap |

Two is one visible turn plus one in the background. A queue was rejected: the
user would believe a turn had started when it had not, and no screen would show
the queue. The permit is taken after the cheap refusals (no key, no model) and
released on stop, cancellation and failure, so a stopped turn cannot keep a slot.

**Subagents do not exist**, so their ceiling is 0. If one is ever added,
`docs/08-port-philosophy.md` Q4 already fixes its shape: one at a time, serial,
and no recursion. Parallel tool calls are likewise not ported: the engine's
approval, checkpoint and cancellation ordering all assume one call at a time,
and the "2–3" from that note was spent on concurrent *conversations* instead.

### The approval plumbing

One `CancellableContinuation<Boolean>` field, set by `awaitApproval()`.
`approvePendingCall` / `declinePendingCall` clear `pendingApproval` and resume
the continuation only if it is still active.

`awaitApproval()` returns `Allowed`, `Declined` or `TimedOut`; the third comes
from `withTimeoutOrNull` around the suspension, so a dialogue nobody answers
cannot hold the turn (and its foreground-service notification) forever. Before
`pendingApproval` is published, the gate writes an `asked` row to
`approval_audit`; each answer moves that row to its terminal value, guarded in
SQL by `decision = 'asked'`. Both writes are best-effort -- a failed audit never
changes the decision. The dialog names the winning rule(s) from
`PolicySet.resolve`, and the same names go into the audit row.

Because `pendingApproval` is a single field, several approval-needing calls
produce **sequential dialogs**, not a collected batch. The audit is readable
from the chat menu (**Approval history**).

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
conversation's grant, and the next SSH call re-reads the column so an off→on
round trip cannot inherit the old consent.

The gate lives in `ChatViewModel.gateEngineCall`; the engine only knows the
`EngineApproval` callback and `EngineDecision`, which is what keeps the shared
loop free of Android policy.

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
action row, beside Source, Copy and Speak. For a turn whose answer has not
arrived yet, the same icon falls back to the last user message. Tapping it opens
a picker of restart points; each point is a prefix that the next request can
reuse from the provider's cache, and everything after that point is removed:

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

The action row also carries **Speak** (a speaker icon) on every completed
assistant reply with text. It is a per-reply action, not a turn action. The
first tap shows a one-time paid notice and the voice picker; after that is
confirmed a tap **synthesizes and starts playback immediately** -- there is no
per-reply dialog. The speaker becomes Stop while that reply plays (Play again
while paused), and a one-row mini bar above the composer carries Pause/Resume
and Stop as icon buttons, the **sentence currently being read** as its label,
and a **seekable progress row** with `current / total` time under it. The bar
does not carry the voice picker: a picker costs a row, and a voice is chosen
before or after listening. Settings -> Network & media -> Reply read-aloud
keeps it, and the first-use dialog already offers it.

Playback also appears in the media notification and on the lock screen: the
screen owns one `MediaSession` and a `MediaStyle` notification while a reply is
being read, so the system's transport controls pause/resume/stop the same
player. One ticker publishes the position about once a second to both surfaces;
the media metadata carries the duration, the playback state carries the
position, and the notification shows the **caption currently being read** as
its second line, with the voice and `1:23 / 4:56` in the subtext. The in-app
mini bar shows the same caption, so the screen and the notification never
disagree about which sentence is playing. The caption comes from
`SpeechCaptions`: the projected reply is split into sentence-sized
pieces and the known duration is distributed over them by visible character
count, because the TTS API returns audio and a duration but no word timestamps.
It is an estimate for reading along, not forced alignment; the lock-screen
metadata title carries the same caption. The lock-screen scrubber is a seek, and
the caption is recomputed from the new position; pausing keeps the current
caption. The session and the notification are released when the chat screen
leaves, so audio never outlives the screen that started it. Starting another
reply stops the first; starting a new turn stops whatever was playing.

Synthesis is a view-model job, not a coroutine in this composition: switching
conversations does not cancel a request that has already been billed. The
completion event starts playback only if the screen is still there to collect
it; a screen that left misses the event, and the file waits in the workspace.
When the conversation is reopened, a reply with audio for the current voice
carries a filled speaker (tapping it is free), and the home list marks a
conversation whose workspace has any speech file with a small speaker.

Audio is cached by message id and voice under `generated/speech/`, so replaying
the same reply in the same voice makes no request and is instant; changing the
voice synthesizes a second file and re-reads the reply. The reply is projected
to speech text by `SpeechText` before it is sent: heading/bullet/emphasis/link
syntax is removed, and code blocks and tables are announced once rather than
read token by token. That projection is also what the billed character count
is based on, so Markdown syntax is not paid for.

**Auto-read** is a Settings switch (Settings -> Network & media -> Reply
read-aloud), on the same section as the voice. Enabling it accepts the paid
per-request billing and records the one-time consent. It fires only on the
streaming -> finished edge of a turn, skips a failed/stopped turn, and does not
replay the last answer when a conversation is merely opened. Voice and auto-read
are read from `SecureStore`, so the chat picks up a Settings change on resume.

The manual "text to speech" chat-menu dialog still exists for arbitrary text:
it keeps Play/Stop and Transcribe, and starts from the stored voice. It is a
separate path from the reply action, not a second implementation of it.

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

`DeltaCursor` holds exactly one number: `sent` (`:27-70`). It used to keep a
`seen` copy of the whole reply as well; the shrink case is decided by `sent`
alone, so that copy was written on every publish and never read — an O(n) copy
of a growing reply, 25 times a second. `initialText` is still never passed by
production code (`ChatViewModel.kt`), even though `DeltaCursorTest` covers it
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

### `MarkdownText`

While streaming: `rememberStreamingMarkdownState()` plus a manual
`for (snapshot in deltas) streaming.append(...)` loop, because
`collectAsStreamingMarkdownState` exists in the artifact but is not visible
through this module's Kotlin metadata. The channel carries accumulated
snapshots, and `DeltaCursor` slices them. The streaming `Markdown` call is still
**positional** because four overloads defeat named arguments; `components` and
`imageTransformer` are named.

The flicker fix is `retainState = true` on the non-streaming path. The switch to
the plain path does **not** happen when the stream ends: `ChatViewModel` leaves
`deltas` non-null, because the plain path parses asynchronously and a blank frame
was visible at the handover. The finished reply keeps its streaming state until
the row is rebuilt from the database (reopen, branch switch, reload), which has
no deltas and takes the plain path once. `isStreaming = false` is still passed so
the LaTeX rewrite can flush an unfinished formula.

**A rotation mid-stream no longer loses the text received before it.** The delta
channel is a queue with no replay, and a rebuilt composition starts with an empty
streaming state — so `MarkdownText` appends the message's text through a
`DeltaCursor` before it starts following the channel; the cursor emits only what
the renderer does not already have. The cursor's rewind cases have unit tests, but
the composition itself has no device observation, because this happens inside a
stream.

**Table cells wrap rather than truncate.** The renderer's 0.45.0 defaults are
`maxLines = 1` and `TextOverflow.Ellipsis` for every cell, so with its fixed
160dp column any cell longer than a few words became "…" — while the table
itself still scrolled horizontally, and scrolling only helps if there is content
left to scroll to. `chatMarkdownComponents` replaces only the `table` component:
the header and row blocks pass `maxLines = Int.MAX_VALUE` and `Clip`. It also
re-supplies the m3 checkbox, because `markdownComponents()` would otherwise fall
back to the base renderer's. Verified on both AVDs: the tablet renders the full
two-line cost cell, and the phone scrolls the same table horizontally and shows
that cell wrapped, not ellipsized.

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

**LaTeX is rendered on-device, including while the answer streams.**
`LatexMarkdown` rewrites `$...$` and `$$...$$` into Markdown image links, and
`MathImageTransformer` draws those links with JLaTeXMath-Android at the formula's
own size. The renderer already gives an inline image a real inline placeholder in
a paragraph, which is why this is an image link rather than a second layout path:
text on either side wraps around the formula, and a formula alone in its
paragraph becomes a block. A tall inline construct (a fraction, for example) is
promoted to its own line by the renderer; the sentence order is preserved.

The rewrite must satisfy the append-only streaming parser, so `LatexMarkdown`
withholds a construct whose closing delimiter has not arrived and `finished`
flushes it as literal text at the end. Code fences, inline code, `\$` and
currency-shaped text are left alone. `LatexMarkdownTest` asserts that every
longer prefix of a corpus extends the previous output, which is the property the
parser cannot check for itself. The formulas use bundled fonts only: there is no
network request, and the `.invalid` image host is intercepted before any loader
could see it. The font licences and the GPLv2 linking exception are in
`app/src/main/assets/THIRD-PARTY-NOTICES.md` and are readable from Settings →
Diagnostics → **Third-party notices**.

Mermaid is deliberately out for weight: it needs a JavaScript engine in a
WebView, and a WebView per diagram destroys scroll performance.

## The chat screen

`app/src/main/kotlin/com/inhuman/ui/chat/ChatScreen.kt`.

### Narrow reads

Nothing collects the whole state; each value is subscribed individually with
`fieldOf`, so a change to one field invalidates the scopes that read it rather
than the screen. `messages` is read inside the transcript's own lambda, and the
composer's draft and staged attachments are read inside `ChatBottomBar` — a
keystroke must invalidate the composer, not the screen body.

The per-message callbacks passed to `MessageBubble` read the latest transcript
through `rememberUpdatedState` rather than closing over the list instance, so a
reply growing does not make every visible row's lambda arguments change.

`parsedMarkdown` is a pre-parse for rows that will actually read it: only
messages with no delta channel. A streaming row takes the incremental path in
`MarkdownText` and returns before it consults a parsed state, so parsing each
intermediate prefix was work whose result could not be observed — and the map
retained one AST per prefix for the life of the screen.

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

### Finding and moving inside a conversation

The app bar carries **search** and **new conversation**; Files and Provider keys
moved into the overflow menu so a phone bar stays at three actions. The turn
index is a strip above the transcript rather than an overlay: a chip pinned to
the top-right would sit on the first user bubble.

- **Export** lives in the same overflow: one conversation as Markdown (a dialog
  chooses whether tool calls and results are included), a **conversation bundle**
  (`.zip`), and its workspace as zip. Export uses `ACTION_CREATE_DOCUMENT`, so the
  user chooses the destination; **Share** writes the same Markdown to a cache
  file and opens the system share sheet, with no server involved. The Markdown
  includes reasoning and attachment paths but not attachment bytes; the zip is
  the plain files, not an encrypted backup (`WorkspaceZip`, docs/05). The
  permanent-workspace detail menu offers the same zip, because a workspace can
  exist with no conversation to reach it from.
- **Conversation bundle** is the move, not the read: `conversation.json` keeps the
  active branch's messages with reasoning, signatures, provider state, tool calls
  and results, and `files/` carries the attachment bytes. Branches are
  deliberately absent — a conversation has one visible transcript. **Import
  conversation bundle** lives under Create: it creates a new conversation under
  the local default assistant, copies only the attachment paths the bundle's own
  messages reference through `WorkspaceTransfer`, and replays the messages in
  order. A bundle that cannot be decoded, or that has no messages, is refused. The
  codec and zip reader are in `ConversationBundle.kt` /
  `ConversationBundleZip.kt`; the zip reader caps the JSON, each file and the
  total, and drops unsafe entry names rather than resolving them.
- **Upload conversation** is a separate item with its own preview, not an
  export option: it names the configured server, says that the body is the
  transcript in plain Markdown, and has a tool-result checkbox that is **off by
  default** because a tool result can contain file or document text. The server
  has no authentication, so the dialog says LAN/Tailscale only. The upload is
  one explicit `POST /conversation`; the preview also says image bytes and
  attachment bytes are absent. The conversation endpoint is separate from the
  diagnostics `/log` because the bodies have different privacy contracts
  (`docs/10-diagnostics.md`).

- **In-conversation search** is a full-screen dialog, not an inline row. An
  inline bar plus the IME, the turn strip and the composer left no transcript
  at 640×360; the dialog owns its own window and inset handling instead. It
  reads the loaded transcript, not the database. `searchConversation` and
  `userTurnsOf` live in `ChatSearch.kt` and are pure list functions, so the
  match order and the "which row does this index mean" question are tested
  without a device. Tool results are skipped, matching the home screen's global
  search; reasoning is skipped because it is collapsed and a hit there would
  open a row whose text is not visible. The dialog lists the matches with a role
  label and a snippet; tapping one closes it and jumps to that row.
- **Turn jump** lists the user rows, numbered from one, with the first
  non-empty line as the preview. Tapping one scrolls to the question, not to the
  footer of its answer. The current turn is derived from
  `firstVisibleItemIndex` minus the compaction/compacting items.
- **New conversation** asks `ChatViewModel.createNewConversation`, which reuses
  the current assistant and shares the workspace only when it is permanent. A
  temporary workspace belongs to exactly one conversation; a new one gets its
  own. The callback carries the new id to `MainActivity`, which changes
  `openConversationId`; the old view model stays alive if its turn is still
  streaming.

The transient highlight now has one owner: a `LaunchedEffect(highlightedMessageId)`
clears it after 2.4s. The scroll effects only set the id, so a message arriving
during the delay cannot cancel the clear and leave a row highlighted forever.

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
off, and so does a `DragInteraction.Start` from the list's own interaction
source. The old code compared only `NestedScrollSource.UserInput`, but current
Compose reports a drag/fling as `Drag`/`Fling`, so following stayed on during a
manual scroll and the list snapped back. Following is turned back on only when a
gesture/fling ends at the bottom, never because a fast fling passed through the
bottom for a frame. A `programmaticScroll` flag keeps the app's own scroll from
being mistaken for a user gesture.

**The interaction source is the second detach signal.** A fling whose first
nested event arrives with a zero delta, or from a source the connection treats
as ours, used to leave `followLatest` true; a reply finishing then pulled the
reader to the newest turn ([07-gotchas.md](07-gotchas.md) B33). Collecting
`LazyListState.interactionSource` and clearing following on
`DragInteraction.Start` closes that gap; programmatic `scrollToItem`/`scrollBy`
do not emit a drag interaction, so the app's own scroll is unaffected.

**The first-index jump was a Markdown first-measure jump, not a fling.** The
0.8.6 frame log samples once per frame and records the pixels actually scrolled
in that frame plus every visible item's size. On the emulator, assistant item
`9c841108` grew from 380 to 3,864 px with `delta=0`; the row had been parsed from
an effect, so `LazyColumn` measured a short placeholder first. All fling speed
and distance caps were reverted in 0.8.6; they changed the feel without fixing
the layout ([07-gotchas.md](07-gotchas.md) B34).

0.8.8 fixes the first measure: the finished path calls the core `Markdown`
overload with `immediate = true`, which parses in the composition. The first
`LazyColumn` measure is the final height, so the row does not grow after it is
placed. The emulator log shows `lastSize=3778` on the first frame and no
`resized` line for the item. The streaming path is unchanged.

0.8.9 moves the finished parse off the composition: `ChatScreen` parses each
distinct answer on `Dispatchers.Default`, caches the resulting `State` by text,
and `MarkdownText` renders the ready state; a miss falls back to
`immediate = true`. The per-frame scroll sampler and the scroll/turn `Diag`
writes were removed, because they ran on the main thread at exactly the moment a
user message crossed the viewport ([07-gotchas.md](07-gotchas.md) B35).

**Only a vertical drag counts** (`detachesFollow`, `ChatScreen.kt`). A code
block wider than the screen scrolls sideways; that drag reaches the parent
connection with `y == 0`, and before the check it turned following off and put
"jump to latest" over a transcript that had not moved. Found by swiping a long
fence on the 360dp emulator, which is also why the predicate is a pure function
with its own test (`FollowScrollTest`): the bug leaves no mark in a screenshot.

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

Before the error banner exists, a transient provider failure is retried
automatically: `ChatEngine` re-sends the **current round** up to five times
(`RetryPolicy`), with 1 s → 15 s backoff and jitter. A round is the unit because
it is the last point at which no tool from that attempt has run; a retry can
never replay an earlier round's write. Only HTTP 408/425/429/5xx, an
`IOException`, and known transient messages qualify — authentication, billing,
context overflow and certificate failures go straight to the banner.

The failed attempt's text is discarded rather than continued. The row clears its
parts and swaps its `deltas` channel for a new one (the incremental Markdown
state is keyed by channel identity and cannot be rewound), then shows
**"Connection problem. Retrying n/5…"** beside the spinner. The message is transient
and never persisted. If all five re-sends fail, the last error is shown with
**"Still failed after n automatic retries"**, and the ordinary **Retry** button still
offers one more deliberate attempt. Pressing **Stop** during the backoff cancels
the turn exactly like any other stop.

`chatErrorAction` maps the provider/turn error string to one recovery:
`Retry`, `CompactAndRetry`, or `OpenProviderSettings`; the raw message remains
available under **Copy details**. The classification is conservative — an
ordinary UI error gets no action. **Retry** calls `regenerate()`, which keeps
the tool definitions for the cache prefix but suppresses tool calls, so a
failure after a write cannot execute it twice. **Compact and retry** persists
the summary before retrying, so a second failure does not throw away the paid
summarizer work.

A stopped turn is not an error banner: the partial answer keeps the
`STOPPED_MESSAGE` footer, and that footer carries a **Retry** button next to it.
It calls the same `regenerate()` path, so a deliberate Stop does not force the
user to retype the message and does not re-run tools.

### Follow-up suggestions

Follow-ups are a **tool**, not a block appended to the answer. The tool is
`ToolCatalog`'s `suggest_replies` (`paid = true`, `optIn = true`), and it is
gated twice:

- **Settings → Display → Follow-up suggestions** (`chat.suggestions_enabled`,
  **off by default**) makes the tool visible to the pickers and the approval
  table. While it is off the row is absent, not greyed.
- The assistant must then select `suggest_replies` explicitly. A null "all"
  selection never includes it (`ToolCatalog.permits` requires the name), so a
  new release cannot start spending an extra request in conversations that
  never chose it.

The assistant's tool call runs in the main turn, after the answer. The engine
executes it, persists its result like any other tool result, and stops because
`SuggestRepliesTool.endsTurn` is true — the suggestions are the result, and
sending it back to the model would only make it repeat them in a second
full-history round.

The tool's executor makes one completion against the assistant's suggestion
model (`suggestionProviderId`/`suggestionModel`/`suggestionPrompt`/
`suggestionFormat`, all on `agents`; null means inherit the assistant's). The
request carries only the last question and the finished answer, `thinking =
false`, `maxTokens = 300`, and a 10 s timeout. Its output is text lines or an
`emit_suggestions` tool call, whichever the assistant chose; the tool sanitises
either through `Advice.sanitize` (trim, strip bullets/numbering, cap at 3, 80
characters each, de-duplicate) and returns `{"suggestions":[...]}`. A missing
answer, a timeout, a provider failure or malformed JSON produces an empty list —
never a failed tool call, because the answer has already been delivered.

`ChatViewModel` reads the result when `ToolCallResult` arrives and sets
`ChatMessageUi.suggestions` on the answer row; the chips live there and
`useSuggestion` still only fills the composer. The result is persisted, so the
transcript keeps both the call and the list; the chips themselves are not
re-derived after a reload. `isInterruptedTurn` treats a `suggest_replies` tool
result at the tail as a finished turn, not as half a round asking for
**Continue**.

The extra request is shown where it is chosen: the PAID badge beside the tool
name, the billing notice above the tool list, and the assistant editor's own
sentence. It is one extra request per answer that calls the tool, not per turn
in general.

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
- the composed system message split into assistant prompt, compaction summary
  and workspace instructions;
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

### Workspace instructions (AGENTS.md)

A root `AGENTS.md` in the conversation's workspace is read on the conversation's
**first request** and pinned to the conversation row (schema v21). From then on
the stored block is appended to the end of the system message, after the
assistant's prompt and the compaction summary, and the file is not re-read. New
conversations pin the file as it is when they start. This is the coding-agent
convention with conversation-level stability: the model could read the file with
`read_file`, but only if it thought to, and a later edit cannot rewrite an
ongoing system prompt.

It is workspace content, not a capability. It cannot add a tool or change an
approval/SSH grant, and the block says so; the guarantee is still structural,
not the sentence. The agent **may** write the file (the user's choice): that
affects new conversations or an explicit reload, not the current one. The
changed-files footer still records the file and Rewind restores it.

`WorkspaceInstructions.kt` reads it through `SessionWorkspace.resolve`, so a
symlink is refused like any other agent path. It caps the file at 16 KiB, cuts
at the last newline inside the cap, refuses a single 16 KiB line, normalises a
BOM and CRLF, and treats missing, blank, binary and unreadable files as "no
block". The absolute workspace path never appears. The rendered block (header,
body, end marker) is stored whole, so a later change to the header does not
rewrite existing pins.

The pin is taken in `ChatViewModel.pinWorkspaceInstructions` before the engine
copies the message list; a preview does not pin, so it shows what the next turn
would pin. A missing workspace or a failed write leaves the conversation
unpinned and the next turn retries; a missing file pins `""` deliberately, so
adding one later needs the explicit reload. The chat menu shows
`AGENTS.md · N chars, pinned` and **Reload AGENTS.md**; the reload replaces the
pin and is the only way an edit reaches an existing conversation. It changes the
system message, so the next request is one deliberate cold start
([03-ai-provider.md](03-ai-provider.md)); an edit alone now costs nothing in the
conversation where it was made, and a new conversation is cold anyway.

### The approval dialog

Modal. `onDismissRequest = declinePendingCall`, so Back and an outside tap both
**decline** rather than leaving the call pending. The body is a sentence from
`describeCall` plus Allow / Decline. It also prints the arguments in monospace
— directly under a comment saying raw JSON is "practically useless", which is a
doc-vs-code contradiction rather than a bug: the arguments are the one place a
user can see what a call will actually do. For the SSH tools the view model
supplies `PendingApproval.details` instead: host, port, user, auth **type** and
the operation (command or the two transfer paths), never the password or private
key.

The dialog also names the rule that produced it (`PendingApproval.rule`,
localised from `PolicySet.resolve`) and states the ten-minute timeout. Before
this, "why is it asking?" and "how long will it wait?" had no answer on screen;
a user who walked away came back to a turn that had been spinning for as long as
the process lived.

`describeCall` special-cases `delete_file`, `write_file`, `web_search`,
`web_fetch`, `ssh_exec`, `ssh_upload` and `ssh_download`; the web tools also say
"(paid search/fetch API)", because an approval prompt is exactly the moment the
cost is worth repeating, and SSH names the host and first 160 characters of the
command or the source/destination paths. Everything else falls back to "Run
<name>?".

The arguments block is capped at 40% of the window height and scrolls. It was
unbounded, and a `write_file` call carries the whole file in `content`: on a
phone the arguments then pushed Allow and Decline off the bottom of the screen,
so the dialog whose entire purpose is to make someone read before answering was
the one where the answer could not be given. The cap follows the window height
rather than a constant so a landscape phone does not spend its whole height on
it.

### When the workspace self-check fails

`InhumanApp.onCreate` records the fence report before the first activity. A
failed report adds a persistent strip above the transcript (`ChatScreen`), with
a details dialogue listing each assertion and its reason; it sits outside the
scrolling list on purpose, because it changes what every turn can do. The same
flag is passed to `ChatViewModel`, which builds no workspace-dependent tool, and
to the tool pickers, which omit them rather than grey them. A stored selection
keeps the names, so a later healthy start restores the capability without the
user re-picking it.

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
commands"; only `ssh_write_file` -> text-write body; only probe/list/read/download
-> read-only text, no upload and no arbitrary commands. The warning changes with
it, so a read-only grant is not told that the agent can delete data or stop
services. Adding `ssh_exec`, `ssh_upload` or `ssh_write_file` to a grant that did
not have it clears `sshAllowed`, so the next call asks again; adding `ssh_probe`,
`ssh_list` or `ssh_read_file` does not.

The checkbox sits **above** the long warning, not after it. On a phone the body
scrolls; with the checkbox last it fell below the fold, so "Allow SSH" was
disabled with no visible reason. Verified at phone width and on the pad.

### SSH limits

Settings → **SSH limits** has four chip rows: command timeout, output per
stream, directory rows and text read. They are user ceilings, not defaults; the
agent can pass a smaller value to `ssh_exec`/`ssh_list`/`ssh_read_file`, and a
missing value uses the app default. Values above a ceiling are clamped and the
result starts with `limits: ...`. `ssh_probe` has no limit arguments and uses the
defaults. The settings are global and are read again for every turn, so a change
applies without reopening the conversation.

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

An inspection profile is just a selection: `ssh_probe` + `ssh_list` +
`ssh_read_file` + `ssh_download`, with `ssh_exec`, `ssh_upload` and
`ssh_write_file` unchecked. No mode, no second settings screen. `ssh_download`
still works without `ssh_exec`; the tools are registered independently. Add
`ssh_write_file` for small config edits and `ssh_upload` for artifacts, still
without giving the model a shell.

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

### Context compaction (classic mode)

`ContextCompaction.kt` / `context_summaries` / the gauge panel. The request
projection can replace older complete turns with a model-written summary:

> **This is the classic mode.** A conversation with
> `conversations.sessionMode = 'classic'` keeps the manual controls below.
> New conversations default to continuous mode, where the app maintains the
> projection and these controls are absent from the normal path; see
> "Continuous sessions" below. Both modes read and write the same
> `context_summaries` row, so switching keeps the current brief.

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
  history is sent. The assistant prompt, the summary and the workspace
  instructions are combined into the first system message.
- **Failure is a no-op.** No key, timeout, provider failure, blank summary or a
  changed transcript leaves the previous projection intact and reports the cause.
  Demo mode uses `demoCompactionSummary`, a deterministic list of questions and
  touched files, so the path is testable offline.
- **The summarizer obeys the conversation's thinking settings.** It used to send
  a private `thinking = false` with no effort, which is a lie on any provider
  whose reasoning mode does not carry that field: the 2026-11-09 log has main
  turns at `thinking=true effort=max` and the summarizer at
  `thinking=false effort=-`, so the gateway used its default and the model
  thought anyway. `thinking` and `reasoningEffort` are now taken from the
  conversation; the user lowers the effort there and compaction follows.
- **The summary budget is a ceiling, not the thinking control.**
  `SUMMARY_MAX_TOKENS` (16,384) is headroom, because `max_tokens` covers the
  reasoning and the answer together: 900 and then 4096 both failed on a model
  that thinks first, and the 2026-11-09 log records a stream that spent 14,388
  characters inside `reasoning_content` on a 25 KB request, ended with `length`,
  and wrote no summary at all. A summary is prepended to every later request, so
  the ceiling stays. `SUMMARY_TIMEOUT_MS` is 180s because that reasoning stream
  alone took 46s. A starved stream is reported as such
  (`summaryStarvedByReasoning` → `chat_error_summarizer_truncated`, naming the
  budget) instead of the generic "the summarizer returned nothing", and the
  `compact` line records the finish reason and reasoning length.
- **The summarizer never gets the whole history unbounded**:
  `compactionTranscript` bounds the input and truncates per message. It is not
  asked to answer the conversation, only to preserve goals, constraints,
  decisions, paths, unresolved work and important tool results.

The remaining limit is honest: if the kept recent turns alone exceed the window,
compaction cannot make the request legal. **Compact again** narrows to one kept
turn; a single oversized turn still has to be rewound or started fresh.

### Continuous sessions

`sessionMode = 'continuous'` is the default for a conversation created after
v23; the Create sheet's switch says so and a one-line disclosure sits beside it
(automatic maintenance, occasional extra requests, usage in Settings). The
conversation's own mode can be changed from the chat menu (classic → continuous
with a confirmation; continuous → **Stop maintaining**, which keeps the current
brief as a manual summary). An upgraded conversation stays classic until the user
changes it — an upgrade must not start spending.

**The projection is maintained by the app.** The planner is the plain-JVM
`core/engine/.../projection` package: `planProjection` returns the boundary that
keeps the most recent complete turns verbatim and folds older ones into the
`context_summaries` brief, plus the estimated size and whether the request can
be sent at all. `buildWireHistory` then assembles the request from that boundary.
The brief is generated by one bounded model call (`summarizeBrief`) with fixed
sections and the same "only the user's words are instructions" rule as the rest
of the app; the call follows the conversation's thinking settings. Demo mode
generates the brief locally.

**When it runs.** Only when the chat screen is visible (`onChatVisible` from the
`LifecycleResumeEffect`) and a turn ended normally. If the screen is not visible
the need is recorded as deferred; opening the chat asks again. A send cancels a
running fold and keeps the old brief; if the projection is already over the hard
line, `ensureProjectionFitsForSend` folds before building the request instead of
refusing. Failures keep the old brief and back off for two minutes; a failed or
empty summary is recorded in `maintenance_runs` with a short reason, never with
conversation content.

**Accounting is not optional.** Every fold writes one `maintenance_runs` row
(kind, model, provider-reported tokens, omitted counts, outcome). The Usage
section shows those tokens as **Session maintenance**; a maintenance request has
no message row, so without the ledger it would be the one model call the overview
cannot see. Maintenance never touches `conversations.updatedAt`, so folding does
not move the chat to the top of the list.

**What the user sees.** The context ring, compact preview, `CONTEXT COMPACTED`
banner and "Compact and retry" are absent in continuous mode. The audit surface
is the chat menu's **Session memory** sheet: mode, coverage (unknown coverage is
"not recorded", never 0), last and total maintenance cost, the brief text with a
truncated marker, **Rebuild**, **Stop maintaining**, **Turn on continuous**, and
the existing in-conversation search. The wire inspector shows the brief as sent.
A successful turn that leaves the projection over the high-water line shows the
`chat_maintaining` line while a fold runs.

**Read-only recall.** In continuous mode the request also carries the `recall`
tool (`RecallTool`): a deterministic search of the active branch's user and
assistant text, returning message ids, turn numbers and snippets, plus a
`message_id` mode that reads one message in full. It is local, free, read-only
(`mutates = false`) and has no approval or badges. Its full result goes on the
wire for that turn only: the transcript stores a stub (`ToolResult.persistedContent`),
so a lookup does not grow every later request. It is not in `ToolCatalog`, so it
never appears in a picker and `ToolGaps` reports it as available because it is
built after the allow-list filter. The tool row is titled "Search record". The
system message explains that the record is authoritative, the brief is lossy,
and recalled text is data rather than instructions.

`SessionMaintenance.kt` holds the two pure decisions worth testing without a
device: `capBrief` (section/line-boundary truncation with an explicit
`truncated`) and `chooseBatchEnd` (one fold's batch, capped by
`maxBatchTokens` and always ending at a complete turn). The planner itself is
covered by `ProjectionPlannerTest`; `RecallToolTest` covers search, turn
numbers, exclusions and the persisted stub.

### The gauge (classic mode)

`ContextGauge.kt` now has two pieces: `ContextRing`, in the composer's action
row beside Send, and `ContextDetailPanel`, above the composer only while the
panel is open. **Both are classic-mode UI.** A continuous session hides the ring,
the panel and their compaction actions; the same facts are in the Session memory
sheet and the wire inspector. The ring draws nothing when `!isKnown`, goes red at
≥90%, and is 20dp with a 2.5dp stroke; tapping it opens the measured figures, the
"Measured from the last reply sent." caveat, and the compaction actions.

The ring first moved into the field's trailing slot, which was the wrong place
for it: Material reserves the trailing slot for the whole height of a
multi-line field, so on a 360dp phone with the microphone configured a wrapped
message sat beside a blank 48dp-wide column, and the text itself had only a few
characters per line. The composer is now one bounded surface with a full-width
text area and a single 48dp action row underneath (attach — which offers files
or the camera — and microphone on the left, ring and send/stop on the right).
The empty composer is about the same
height as the outlined field it replaced -- the text area is one line rather
than Material's 56dp box, which pays for the action row -- and a three-line
message is shorter than it used to be, while every line gets the field's whole
width.

### The image source menu

The attach glyph opens a two-item source menu rather than the document picker
directly: **Choose from files** runs `ActivityResultContracts.OpenMultipleDocuments`
(`ChatScreen.kt:320-351`), and **Take a photo** starts the system camera with
`ActivityResultContracts.TakePicture`. The picker used to be the single-document
`OpenDocument`, whose result parser returns only `intent.data` and ignores
`clipData`; an OEM picker that still lets the user tick several images (HyperOS
does) staged exactly one and dropped the rest silently. The composer has always
held a list -- the contract was the one-image ceiling.

There is no app-side image-count ceiling: every picked URI goes through the same
staging pipeline, one image at a time so a large selection does not decode every
bitmap at once (`ChatViewModel.attachImages`, `ChatViewModel.kt:1397-1404`), and
the provider's documented request limits are the only boundary (DeepSeek: 600
images and a 48 MiB body per request). Staged chips sit in one horizontally
scrolling row (`ChatScreen.kt:4641-4688`), so a large pick stays reachable
instead of growing the composer into the transcript.

The camera is another way to produce an image, not another attachment kind, so
it lives behind the same icon; a second camera glyph in the same 48dp row would
compete with the microphone and the context ring over a distinction the menu
states in words.

The camera receives a write grant for one empty file in `cache/camera/` through
the `.capture` FileProvider (`CameraCapture.kt`, `capture_paths.xml`), not a
MediaStore item. A photo taken for a message is an attachment: putting it in
the gallery would leave an image behind when the message is never sent. No
`CAMERA` or storage permission is declared. On return the image goes through the
same `ChatViewModel.attachImage` pipeline as the picker — vision check,
decode/EXIF/resize, quota, `attachments/` naming — and `onFinished` deletes the
cache original once the prepared copy is staged (`ChatViewModel.kt:1236-1381`).
A cancelled camera, or one that reports success without writing, deletes the
empty file and stages nothing.

The capture file name is `rememberSaveable`: the camera activity routinely
outlives the process, and the result is then delivered to a recreated launcher
that no longer has the `remember` that created the file.

The provider is `CaptureFileProvider`, a distinct subclass of FileProvider, not
a second `androidx.core.content.FileProvider` declaration. PackageManager keys
providers by component name, so the same class twice drops the second entry
with "already defined"; the diagnostics instance then serves the `capture`
authority and the camera's write fails with "The authority
com.inhuman.capture does not match the one of the contentProvider:
com.inhuman.diagnostics". That was observed on the tablet, with the camera app
crashing and no attachment staged. `CameraCaptureTest` pins the write/read
round trip.

The old full-width strip above the field is gone. It carried a
`25% · 262K / 1M` summary, but on a phone the row cost roughly the height of the
composer to say one proportion; the ring alone answers "how much room is left",
and the counts are one tap away. An unknown window still draws nothing rather
than an empty ring that would read as "0% used". `contextOf` scans backwards for
the last reply that reported `promptTokens` — which is why the figure is one turn
behind, and the panel says so.

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

Multi-select is list-level: the header shows the checked count and a **Select
all / Deselect all** action. The set is derived from the conversation list at
tap time rather than stored as an "all" flag, so a conversation created by a
running turn is not selected without a tap, and the delete dialog names exactly
the rows that were checked. **Deselect all** keeps selection mode open;
**Cancel** leaves it.

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
- **Import workspace (.zip)** is on the Workspaces list and always creates a new
  permanent workspace named from the document's display name with its `.zip`
  extension removed; it never merges into an existing one. The archive is
  untrusted input: entry names are normalised, directories and duplicate names
  are skipped, at most 2,000 files are accepted, and each file goes through
  `WorkspaceTransfer` so the workspace quota, per-file ceiling and free-space
  reserve apply exactly as they do to a user-picked file. A failure stops the
  import and keeps what already arrived (with the error shown); an import that
  wrote nothing removes the empty workspace it had just created. Rules are in
  docs/05.
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

`FilesScreen.kt` / `FilesViewModel.kt`. Browse, preview text and images (binary
and oversize are *reported*, not rendered as mojibake), search the current
folder, sort by name/modified/size, import via `OpenDocument`, export via
`CreateDocument`, delete with a dialog, and a header showing the workspace quota
as `files · bytes`. A row's export and delete live behind one overflow menu. All
paths go through `SessionWorkspace.resolve`.

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

The dialog also has a **Provider** section. Each profile is a chip; selecting one
calls `ChatViewModel.setProvider`, which updates `conversations.providerId`,
clears `model`, `reasoningEffort` and `thinking` in the same statement, and
reloads the model list for the new provider. The transcript is unchanged — the
switch only affects later turns. It is refused while a reply is streaming.
Switching away from the assistant's provider also stops the agent's model from
being used as a fallback: `publishModelState` resolves the assistant's model only
while `agent.providerId == conversation.providerId`.

### Settings

`SettingsScreen.kt` / `SettingsViewModel.kt`. **Model providers** lists the
profiles. Adding one opens the known-provider picker first; a known provider
shows only the API key field, while **Custom provider** (or **Customize
endpoint**) opens the editor with name, kind, HTTPS base URL, password-style
key, reasoning-field mode, stream-usage switch and fallback model ids. Save
writes
the row and key through `AgentRepository.saveProvider`; a blank key removes it.
"Test connection" calls `GET /models` with the unsaved values and reports what it
sees. The built-in DeepSeek profile cannot be deleted. **A stored key can be
deleted without deleting its provider**: the editor has a **Delete saved key**
action when a key is stored and the field is empty, and the web-tools section has
one for the DeepSeek-tools and Exa keys together. Media backends have the same
action in their dialog; `saveMedia` keeps an existing key when the field is
blank, so clearing it needs its own control. OAuth sessions are a separate
credential and are ended by the existing **Sign out**. **Web & media tools**
selects the search and fetch backend independently (DeepSeek/Exa) and stores
their keys separately; a backend without a key shows a warning and its tool stays
unregistered. Below them, the five paid media tools each have a row with an
**Assistant / Configured provider / Separate API** backend. Provider mode lists
only profiles whose kind supports the capability and fetches their models,
narrowed by the model tags where they exist; Separate API takes an HTTPS endpoint,
a Keystore-stored key and a model id. Demo mode and the SSH switch live below
those sections.

Settings is grouped into foldable sections: Providers stays open; Web & media,
Demo & SSH, Turn limits, Usage, Backup, Approvals, Diagnostics, Local, Display,
Language and Time start collapsed. Each header states its own state (key
presence, current round count, configured rule count, current language, when the
time hint is added) so a folded row still answers the question that brought the
user there. Open state uses `rememberSaveable`, so rotation does not reset it.

**Usage defers its read.** `SettingsFold` reports when a section becomes
expanded, and `UsageSection` uses that to call `refreshUsage()` — opening
Settings no longer scans `messages` for a report the user may never look at. A
folded Usage row says "Open to count local usage" until it has been opened,
rather than claiming a read that has not started.

**Turn limits** (`04 / Turn limits`) exposes the same global tool-round setting
as the chat menu. Both write `SecureStore.KEY_TOOL_ROUNDS`; `SettingsViewModel`
reads and writes it through one helper, and `ChatViewModel.refreshConfiguration`
re-reads it when a conversation is entered. A round is one model response plus
the calls it requested, so the label says "Tool rounds per turn"; the section
also owns the separate total-call budget (`KEY_TOOL_CALLS`, default 32, range
1–256). Raising either budget does not change which tools are allowed.

**Display** (`10 / Display`) is a `LayoutPreference` of Auto / Compact /
Comfortable. Auto derives the shell from the window; Compact forces the
single-column shell, and Comfortable keeps the wide shell. It is supplied at the
root through `LocalLayoutPreference`, so rotation and split-screen still work
under Auto, and is persisted under `layout.preference`. It is a reading
preference, not a per-ratio selector.

**Language** (`11 / Language`) sets the system-owned per-app locale through
`LocaleManager`: 中文 or English. Chinese is the default, set by
`InhumanApp.ensureDefaultLanguage()` only when the system has no per-app locale
yet; a non-empty choice is never overwritten. Nothing is stored in
`SecureStore` — the platform persists the locale and recreates the activity,
which is why the chips reflect the live value rather than a copied one.

**Time** (`12 / Time`) decides whether a request marks when each user message
was sent: after a gap (default, 5/10/30/60 minutes), every message, or never.
It is request formatting, not a capability, and it is global rather than
per-conversation: the prefix is derived from stored timestamps, costs one cold
request when it changes, and a per-conversation copy would add a schema column
and a second place to look for the same decision. The mechanism, the anchor for
the first message and the cache argument are in "Time hints on the wire" above.

Settings is numbered `01` Models, `02` Web, `03` Runtime, `04` Turn limits,
`05` Usage, `06` Backup, `07` Approvals, `08` Diagnostics, `09` Local,
`10` Display, `11` Language, `12` Time.

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
  (`ChatScreen.kt:4513-4515`), and attach and send are disabled with it.
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
| `TimeHintsTest` | The time-hint rule: off adds nothing; every message carries its own send time; in gap mode the first message is the anchor and the threshold is inclusive; the gap is measured from the previous **user** message; a backwards clock does not qualify; a missing timestamp is not rendered as 1970; the wall clock follows the device zone; the two stored preference values round-trip, clamp and fall back to the default when unreadable; and the plan keys hints by stored id, leaves non-user rows alone and measures the unstored draft after the last stored user message. This is the half of the feature a device cannot re-observe, since a request built days ago is gone. |
| `TurnSummaryTest` | Rounds of one turn sum (prompt, completion, cache, generation); a missing field stays missing; a turn still holding a tool result, a running tool call, or a streaming answer is not complete; two turns get independent footers; the range covers exactly the turn's rows, a transcript that begins mid-turn still forms one turn, and slicing is clamped against a shorter list. The range cases exist because the summary used to carry copies of those rows and the wire inspector now slices the transcript with the indices instead. |
| `ConversationToolsTest` | The assistant ceiling with `null` and explicit selections; a conversation keeps a tool the assistant no longer has; SSH off hides the row but preserves the stored name; applying stores catalogue order. This is the "new tool reaches an old conversation without inventing a capability" rule. |
| `ImageSizingTest` / `ImagePreprocessorTest` | Fitting a 4032×3024 photo into 1568×1176, never upscaling a small image, and choosing a power-of-two sample size, on the JVM; then the same decode/scale/encode on a device, including the 1 MiB ceiling and transparent-PNG-to-WebP. This is the phone-side image pipeline that replaced accepting a 4 MiB file and silently dropping it at send. |
| `CameraCaptureTest` (instrumentation) | The `.capture` FileProvider writes and reads the URI the camera receives, a saved capture name resolves to the same file, and an abandoned capture older than the ceiling is swept by the next one. This is the half of "take a photo" a screenshot cannot prove: a misconfigured provider only shows up as an empty file after the camera closes. |
| `ConversationShareTest` (instrumentation) | The `.share` FileProvider reads back the Markdown the chooser is handed. A wrong authority or path resource only shows up as a `SecurityException` in the receiving app. |
| `ConversationUploaderTest` | The upload posts the Markdown to `/conversation` with the version header, and a non-2xx response is a failure naming the status. A loopback `HttpServer` stands in for the LAN server. |
| `PdfVisionPagesTest` | The PDF-vision page arithmetic: default first four pages, explicit range, per-call ceiling, page beyond the document, backwards/zero pages. |
| `PdfVisionRenderTest` / `PdfVisionToolTest` (instrumentation) | PDFBox rasterizes a page to a bounded JPEG; an image-only PDF goes through the whole tool with a fake provider, one image part on the wire and the transcription text returned. |
| `ConversationMarkdownTest` | The header names title, time, model and tool policy; omitting tools removes their sections and says so; including them keeps fenced JSON arguments and fenced text results; reasoning is a blockquote; attachments are listed without bytes; an error follows the answer; a tool result containing a triple backtick gets a longer fence; export names lose filesystem-reserved characters and keep unicode. |
| `WorkspaceZipTest` | Relative paths and contents survive; an empty directory gets an entry; an empty workspace makes a valid empty zip; a symlinked file or directory outside the workspace is skipped and counted; a Windows junction is not followed. |
| `SnapshotStoreTest` | `start` and a later tool checkpoint restore their own file versions; added/deleted/modified paths are diffed by content; a checkpoint whose blob has gone leaves the workspace byte-for-byte as it was; a restore leaves no staging directory behind; stats count turns and pruning keeps the newest; `turns` lists each retained turn with its checkpoint count, newest first. This is the file half of checkpointed rollback. |
| `WindowFrameTest` | A 640×360 landscape phone and a 900×400 wide-short window stay single-pane; a 700×700 foldable is Medium/single-pane; 1024×768 and 1280×900 use two panes; a 360dp phone uses a FAB and the 1.5× font case uses the header overflow. This is the axis-classification half; the screenshot matrix is still required for composition mistakes. |
| `FollowScrollTest` | A horizontal drag does not stop the transcript following the newest content; a vertical one does; and the app's own `SideEffect`/programmatic scroll never does. This is the half of "jump to latest" that a screenshot cannot show. |
| `ToolCatalogVisibilityTest` | every `remote = true` tool is absent from `ToolCatalog.visible(false)` and present only in `visible(true)`; ordinary tools remain in both. This is the one list every user-facing picker and approval table reads. |
| `TurnNotificationsTest` (instrumentation) | Drives two real `TurnStatus` records through the service: summary plus two children, finishing one demotes to the surviving child, finishing both clears them. Also asserts an awaiting-approval turn has **Review** and **Stop**, and no one-tap Approve/Deny. |

These are pure JVM logic for the reason `AGENTS.md` gives: the device cannot
observe these states, so the argument has to live in a test.
