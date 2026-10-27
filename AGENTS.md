# Working on inhuman

Notes for whoever picks this up next, human or agent. The README explains what
the app is; this file explains how to change it without breaking what it
already gets right.

**Before reading the source, read `docs/`.** The numbered files plus the index
describe what the code actually does today, with a `file:line` for every claim,
so a session can start work without re-deriving the architecture from scratch.
`13-product.md` is the deliberate exception: a technology-neutral product
document with no code references. `08-port-philosophy.md` records intent rather
than implementation; the old feasibility studies are archived under
`docs/archive/`.

| File | Answers |
| --- | --- |
| `docs/01-overview.md` | What this is, what works, what is deliberately missing, the vocabulary |
| `docs/02-architecture.md` | Module graph, wiring at start-up, navigation, build toolchain, `tools/` scripts |
| `docs/03-ai-provider.md` | Provider profiles, the OpenAI-compatible adapter, SSE, demo mode, **the paid `web_search`/`web_fetch` backends**, **the per-tool media backends (image/video/speech/transcription/decision)**, **the SSH tools (`ssh_probe`, `ssh_list`, `ssh_read_file`, `ssh_exec`, `ssh_upload`, `ssh_write_file`, `ssh_download`)**, and the user-set `SshLimits` ceilings |
| `docs/04-data-persistence.md` | Room schema v18, orderings, migrations, repositories, `SecureStore`, the `DocumentsProvider`, encrypted backup |
| `docs/05-files-sandbox.md` | The sandbox, quotas, the file and document tools, import/export, the design layer |
| `docs/06-agent-loop-ui.md` | The turn lifecycle, the approval gate, cancellation, the context gauge, layout |
| `docs/07-gotchas.md` | The load-bearing invariants, the current defects, and comments that contradict the code |
| `docs/08-port-philosophy.md` | **Why** the runtime is ported, which options the project refuses to have, the seven design questions, the paid-disclosure rule (§1.4), and the SSH exception (§1.2.1/§1.5) |
| `docs/09-approvals.md` | The approval model: why the default is no prompts at all, how per-tool, per-assistant and per-path rules compose, and what of it is built |
| `docs/10-diagnostics.md` | The flight recorder: what is logged, what is deliberately not, where the file lives and how to share it |
| `docs/11-conversation-tools.md` | The conversation-level tool sheet: who may change it, the assistant ceiling, when it applies, and why it is not a new SSH exception |
| `docs/12-desktop.md` | **Windows x64 host**: architecture, feature map, security invariants, build/package/sign commands, verification and known limitations |
| `docs/13-product.md` | **Technology-neutral product document**: design principles, current features, future plans |
| `docs/14-documents-voice.md` | The documents/voice decision: format matrix and libraries, the neutral model and loss report, read/write limits, the three tools, Android-only voice |
| `docs/archive/` | Historical feasibility/design studies; not current documentation |

`docs/07-gotchas.md` is the one to read before changing anything in a hurry.
`docs/08-port-philosophy.md` is the one to read before proposing a new setting,
mode, or permission — it is where the project records which options it refuses.
Keep the docs current: a commit that changes a documented behaviour changes the
document in the same commit, or the doc becomes the next "comment that is the
bug".

## Default scope: Android, not the desktop host

The Android app is the product; `:desktopApp` is a second host of the same
shared core. **Unless a request explicitly names the desktop, Windows, or
`:desktopApp`, do not change the desktop host.** Do not port a new screen,
menu, string or feature to it "for parity" on your own initiative; that is a
separate product decision.

A change to a shared core module (`:core:ai`, `:core:files`, `:core:db`,
`:core:engine`, `:core:common`) can still affect both hosts. When it does, keep
the desktop compiling and its tests green, but do not extend its UI or feature
set unless asked.

### The desktop must not gate Android

The desktop is a consumer, not a gatekeeper. A one-off conflict is normal; a
repeated tax is not. Split the desktop into its own build/repository when any of
these persists across roughly two Android changes or one release cycle:

- an Android change is blocked or repeatedly delayed by `:desktopApp` compile,
  test, API or UI work;
- a shared-core API or schema change cannot land without desktop feature work;
- Kotlin/Compose/AGP upgrades are held back by the desktop host;
- desktop packaging or CI failures block an Android release;
- a desktop constraint forces a worse Android design.

One failed compile is not a trigger — fix the coupling. The trigger is a
repeatable cost that changes Android's schedule.

A split must preserve the design principles and capabilities: the workspace
boundary, no full-access mode, the per-turn tool set, default-free approvals
with an opt-in ask, paid/remote disclosure, SSH consent, and the data/backup
contract. Split the host first (the desktop consumes the core as an artifact);
if the core is reimplemented, add conformance tests for those invariants before
either side ships. A split that loses them is a product fork, not a host split.

---

## What this app is for, and what it refuses

The short version, stated once here so it is not re-litigated per feature: the
agent design of DeepSeek Harness is worth having on a phone, so it is being
ported — **with hard limits on what the agent can read, write, and run.** Those
limits exist to borrow the device's compute while isolating the filesystem. They
are **not** about disclaiming responsibility.

Two consequences bind every future change, and neither is negotiable:

- **There is no "full access" mode, and there will not be one.** Not "off by
  default", not "behind an advanced toggle". A privilege level that can be
  escalated is itself an attack surface, and a prompt injection does not need
  the user to click wrong — it needs to talk the model into *asking* for more.
  Capability is fixed **per turn**, and only the user can change a conversation's
  tool set. No tool may add tools, and a message or tool result cannot widen the
  set; a change takes effect on the next turn.
- **The capability boundary must be statable in one sentence a person can
  read.** The default sentence is: *code runs on your device, sees only what you
  hand it, cannot reach the network, and cannot reach your files.* One exception
  exists, and only one: **the SSH tools (`ssh_probe`, `ssh_list`,
  `ssh_read_file`, `ssh_exec`, `ssh_upload`, `ssh_write_file`, `ssh_download`),
  off by default, enabled globally, selected explicitly on the assistant or the
  conversation, and granted for that conversation's current SSH capability set.
  Adding `ssh_exec`, `ssh_upload` or `ssh_write_file` asks again rather than
  inheriting a narrower read-only grant.** When SSH is on, the agent
  can inspect a host, read files, write small text files, transfer files, and —
  only if `ssh_exec` is selected — run commands on a remote machine whose address
  and credentials the user supplies; the app can save **one** host for convenience,
  without a security or privacy promise (`docs/13-product.md`). SSH **credential**
  arguments (`password`, `privateKey`,
  `passphrase`) are redacted before storage. Command text, paths and
  `ssh_write_file` content
  are part of the call and remain in the transcript. Do not add another exception
  without answering `docs/08-port-philosophy.md` §1.2.1 and §1.5.

**Safety here comes from structure, not from prompts.** The agent can only see
and change the workspace the conversation is attached to, and what the user
brings in is a *copy* — the original never moves and is never in the directory.
A workspace is temporary (one conversation, deleted with it) or permanent
(shared, survives conversation deletion); its directory name is the opaque
workspace id and is never sent to the model. So everything
inside is already reversible, and nothing inside needs an approval prompt by
default. Asking "are you sure?" where the answer is always yes is not a safety
measure, it is friction, and friction trains people to click through.

That is why **no approval is required by default, including for deletion**. A
user who wants to be asked can configure it, globally, per assistant, or per
path, and where two rules apply the stricter one wins. `docs/09-approvals.md`
has the model.

**Cost is disclosed where it is chosen and where it is used.** A tool that
spends money at a third party carries `paid = true`, a filled `PAID` badge
beside its name, and a billing notice above the tool list; a price in a
description nobody opens is not disclosure. This is separate from approval — paid does not
mean "ask" by default. The rule and its reasoning are
`docs/08-port-philosophy.md` §1.4; apply it to every future paid capability, not
just the web and media tools.

**Remote capability is disclosed separately.** Every SSH tool carries `remote =
true`, a filled `REMOTE` badge, a notice above the tool list, and a dedicated
first-call consent dialog. The app can save one host and its credentials in the
platform secret store for convenience, never as a security or privacy promise;
there is no host list or fleet manager, and backups exclude the secret. SSH
**credential** arguments are redacted
before the transcript is stored; command text, paths and `ssh_write_file`
content are not. The ordinary approval dialog must never show a password or
private key — the SSH tools supply a redacted `details` string; `ssh_upload` and
`ssh_write_file` are also `NO REWIND` because they replace remote files.
The rules are `docs/08-port-philosophy.md` §1.2.1 and §1.5.

Full reasoning, plus the design questions and how each was answered, is in
`docs/08-port-philosophy.md`. Do not add a mode, a permission prompt, or a "pro"
escape hatch without answering it there first.

---

## Window ratios, always

**Every change is verified on both a phone-sized and a tablet-sized window, and
on the ratios in between.** Not "checked on one and reasoned about for the other"
— actually run them. Width alone is not a layout: a 16:9 phone in landscape is
640dp wide but only 360dp tall, and a square foldable is 700dp on both axes.
`WindowFrame` (`core/design/.../Layout.kt`) classifies width **and** height;
`usesTwoPane`, `isCompact` and `usesFab` are derived from both.

| Case | dp | expected shell |
| --- | --- | --- |
| 16:9 phone portrait | 360×640 | single pane, FAB |
| 20:9 phone portrait | 360×800 | single pane, FAB |
| 16:9 phone landscape | 640×360 | single pane, header Create |
| square foldable | 700×700 | single pane, header Create |
| 4:3 tablet | 1024×768 | two panes |
| 16:10 tablet | 1280×800 | two panes |
| large font phone | 360×640 @ 1.5× | overflow destinations |

```powershell
emulator -avd dsh_tablet        # Android 15, x86_64, Pixel Tablet
# one emulator at a time: the local host does not have memory for several
```

A single AVD can be resized with `adb shell wm size WxH` and
`adb shell wm density DPI`, then `wm size reset` / `wm density reset`. This is a
useful ratio test, not a substitute for the two device profiles. The physical
phone is deployable and inspectable, but HyperOS may refuse `adb shell input`.

The phone-sized case is not a smaller copy of the tablet case and must not be
treated as one. They differ in ways that have already caused bugs here: the
primary action is a FAB on a compact-width phone, a header Create on a medium
single pane, and a top-bar button on a two-pane tablet
(`prefersFabForPrimaryAction`); the gutter is 20dp against 32dp
(`comfortableGutter`); and 1280dp of width is enough for a paragraph to become
unreadable if nothing caps it.

### Space utilisation is the point, not the side effect

A tablet has roughly three times the horizontal room of a phone, and the lazy
answer — stretch everything — produces a 150-character line of prose and a
composer whose send button sits in a corner nobody looks at. That is worse than
the phone layout, not better.

The rule this project arrived at: **cap the measure, centre what is capped.**
`Layout.ReadableMeasure` (640dp) governs forms, settings and short prose. The chat
transcript is wider — 900dp — because it contains code and tables, and user
messages are narrower still at 560dp. On a phone every cap is inert
(`screenWidthDp` never reaches it), so the same code is correct at both sizes
without a form-factor branch. Prefer that shape: a maximum that binds on the
large screen and does nothing on the small one.

Two corrections to what this file used to claim, both verified — see
`docs/07-gotchas.md` B5:

- The chat transcript and composer share a **900dp** axis. Assistant replies have
  no bubble and no 640dp cap, but they do not run the full tablet width either;
  user messages are 560dp bubbles aligned to the end. On a tablet the home screen
  becomes two columns: a 320dp workbench rail and a conversation list capped at
  760dp.
- The cap **was inert at seven call sites** in five files (the conversations
  list, the agents list, files, settings, `WideDialog`), because they chained
  `fillMaxWidth`/`fillMaxSize` *before* `widthIn`. **Fixed**, and verified by
  measuring the rendered column on the tablet AVD: 576dp, centred. Three of
  those sites had carried a comment claiming the cap was what made the tablet
  layout readable — the comment was describing behaviour the code did not have.

**The composer must not consume `safeDrawing` before `imePadding()`.** That inset
contains the IME; consuming it first leaves the composer under the keyboard. This
was found by opening the keyboard on the phone AVD, not by reading the chain.

### Visual language: Graphite Workbench

The app is a working tool, not a chat template. The visual system is deliberately
small:

- **Warm paper (light) / warm graphite (dark)**, with a single burnt-orange
  signal accent used for the primary action, section codes and the assistant
  marker. Light uses a deeper accent so text and buttons keep contrast on paper.
- **Mono is for metadata**, not body text: wordmark, section codes, timestamps,
  model/workspace lines. Body text stays 16sp/26sp sans.
- **Hairlines, not cards**, separate list rows and sections. Corners are 3–10dp;
  dialog-large surfaces use 22dp. A filled card is for an actual raised object,
  not for every row.
- **Grid only on empty canvases**, at 12% alpha. It gives a large tablet empty
  state a sense of place; it is not wallpaper.
- Shared components live in `core/design/…/Workbench.kt`: `Wordmark`, `Eyebrow`,
  `Hairline`, `StatusDot`, `WorkbenchGrid`, `SectionHeading`, `MetaLine`.

When changing a screen, reach for those before adding a literal colour, radius or
spacing value.

**A modifier-order trap, hit once already:** `Modifier.fillMaxWidth()` is
greedy and consumes the incoming maximum, so `.fillMaxWidth().widthIn(max = …)`
caps nothing. Constrain first, fill second:

```kotlin
Modifier.widthIn(max = Layout.ReadableMeasure).fillMaxWidth()
```

### Spacing

Vertical rhythm lives in `Layout.Space`, not in literals at call sites. It was
literals once, and the transcript ended up spending more vertical space on the
gaps between things than on the things — a one-line collapsed tool result stood
53dp tall with an 18dp gap to the next.

---

## Verifying on a device

```powershell
$env:JAVA_HOME = 'C:\android-toolchain\jdk17'
.\gradlew.bat :app:assembleDebug
adb -s <serial> install -r -t app\build\outputs\apk\debug\app-debug.apk
```

`tools/build-and-shot.ps1` builds, installs, launches and screenshots.
`tools/verify-*.ps1` drive the app through `uiautomator` + `adb shell input`.

### Demo mode is the offline verification path

With no API key, Settings → Demo mode gives a scripted provider. A custom
OpenAI-compatible provider needs only a name, an HTTPS base URL and a key; the
assistant editor chooses it per assistant. Web tools need their own keys and are
independent of the chat provider. When the assistant has file tools it creates a
file and then deletes it, so the tool loop, the results and the closing summary
are all reachable with no key, no network and no bill. **It no longer raises an approval prompt**, because
deletion does not ask by default — configure a rule on `delete_file` if you want
to exercise that path. **Put this in a message to get prose instead:**

```
plain
```

The long answers are written in Markdown deliberately — an unclosed fence, a
bold span arriving in pieces, a list whose markers precede their text — because
those are the constructs that are awkward while streaming.

### What adb cannot verify, and what to do instead

Screenshots and UI dumps are the right tool for layout and for end states.
They are the **wrong** tool for anything that happens inside a stream:

- One `uiautomator dump` + `adb pull` costs roughly 700ms of round trip.
- A demo reply streams for about 3.6s.
- So a sampling loop sees two or three frames of a reply, and the intermediate
  states of an incremental parse can never be observed this way. Not "hard to
  catch" — **not observable**, at any sampling rate the tooling allows.

When something lives inside the stream, do not try to see it. **Extract the
invariant into a pure function and test that**, then use the device only to
confirm the surrounding path runs and renders. `DeltaCursor` and
`ContextUsage` exist for exactly this reason, and their tests carry the
argument that the device cannot.

Also worth knowing: `adb shell input` is blocked outright on some OEM builds
(HyperOS refuses synthetic events without an extra developer setting), so a
physical phone may be deployable and inspectable but not drivable.

---

## Things that are load-bearing

Not style. Each of these was a bug, and undoing it silently restores the bug.

- **`SessionWorkspace.resolve()` is the only place a path becomes a `File`.**
  No symlinks, ever, plus a real-path prefix check. On Windows that check must
  use `Path.toRealPath()` for existing components: `File.canonicalFile` does
  **not** resolve a junction, and `Files.isSymbolicLink` does not report one.
  A junction escape was found by the desktop host and is pinned by
  `SessionWorkspaceTest`.
- **The workspace self-check runs before the first frame and fails closed.**
  `WorkspaceSelfCheck` asks the device the same questions the unit tests ask
  (traversal, absolute-looking paths, an outside file, a symlink, a Windows
  junction), in a cache probe it deletes itself. A failed or unrunnable check
  sets `AppCapabilities.workspaceOk = false`; `ChatViewModel.buildTools` then
  builds no workspace-dependent tool and `ToolCatalog.visible(...,
  includeWorkspaceBound = false)` omits them from every picker. Do not turn that
  into a warning or a greyed row, and do not let the failure take away the
  user's own Files access: the fence constrains the agent, not its owner.
- **A workspace export must not follow a link out of the workspace.**
  `WorkspaceZip` checks every entry's `toRealPath()` against the real root. A
  symlink is skipped even when it points back inside, and a Windows junction is
  caught by the real-path comparison, not by `Files.isSymbolicLink` (which does
  not report one). A directory whose real path repeats one already visited is
  skipped, so a junction cannot loop or write the same bytes twice. The result
  carries `skipped` and the UI shows it; a zip that dropped a file must not look
  complete.
- **A cross-workspace import is a copy, never a reference.** `WorkspaceCopy`
  re-resolves the source path through the *source* workspace (only a regular
  file), then hands the bytes to `WorkspaceTransfer.import` on the target, so
  the per-file ceiling, total quota, file count, free-space reserve and " (2)"
  naming are the ordinary import's rules. Do not add a mount or a shared file:
  one conversation's rollback must not be able to write into another's
  materials. The source browser filters symlinks, and workspace rules do not
  apply to the owner's own copy.
- **The share target is one item, an explicit MIME, and a copy.** The manifest
  registers `ACTION_SEND` only -- never `SEND_MULTIPLE`, never a catch-all
  wildcard -- and `SharedContent.kt` re-checks the action and MIME allowlist
  because an exported activity can be started explicitly. A visible chat is the
  destination; otherwise the user picks a conversation and the copy happens
  immediately. Bytes go through `WorkspaceTransfer.import`, so the per-file
  ceiling, quota, collision naming and free-space reserve are the ordinary
  import's; the URI is read at once and never retained. Do not widen this into a
  general import channel, and do not give the sending app any path back into the
  workspace.
- **The saved-prompt library is user configuration, not capability.** It is the
  `saved_prompts` table, written only by the assistant editor; no tool reads,
  writes or selects it, and a title is unique so re-saving updates rather than
  clones. Choosing an entry only fills the assistant's system-prompt field,
  which remains editable, and the assistant row keeps its own copy. Neither the
  title nor the text goes to diagnostics.
- **Advice is a parse of the same answer, never a second call and never
  consent.** The model appends a `<<<suggestions` block; `Advice.visible` hides
  it while streaming (holding back a trailing marker prefix, monotonically) and
  `Advice.parse` strips it before the row is written. Only a round with no tool
  calls may set `ChatMessageUi.suggestions`, the list is cleared when the next
  round starts, and it is not persisted. `useSuggestion` may only fill the
  composer; it must never send, and a suggestion is not an approval or a grant.
  The setting is a display preference, not a capability tier.
- **The backup reminder reminds; it never writes a backup.** `BackupReminderPolicy`
  is pure (no data, off, a snooze, a backward clock or an age under the period →
  nothing); the "never backed up" baseline is `backup.tracking_since`, written on
  first launch. `backup.last_export_at` is written only after a successful
  export. There is no worker and no notification: the home screen evaluates when
  it becomes visible, **Later** snoozes three days, and the export remains the
  user's button. Do not turn this into a background writer without answering the
  passphrase and retention questions in `docs/13-product.md`.
- **LaTeX is a rewrite into intercepted image links, and the rewrite must be
  prefix-stable.** `LatexMarkdown` turns `$...$` / `$$...$$` into
  `https://inhuman.invalid/math/...` image links; `MathImageTransformer` draws
  them with the bundled JLaTeXMath fonts and returns null for every other URL.
  The streaming parser is append-only, so an unfinished formula is withheld and
  only `finished` flushes it literally; every longer snapshot must extend the
  previous output. Do not remove that property, widen the `.invalid` prefix, or
  let a real image loader see these links. Code, `\$` and currency are not math.
  The fonts and the GPLv2 linking exception are in the bundled
  `THIRD-PARTY-NOTICES.md`; keep that asset and the Settings reader current when
  the dependency changes.
- **Thinking signatures are request state, not display state.** Anthropic's
  `messages.reasoningSignature` is parsed from `signature_delta`, persisted with
  the message, and replayed as a signed `thinking` block only when every
  reasoning/tool turn in the history has both text and signature. Gemini's
  `thoughtSignature` travels per `ToolCall` (inside `toolCallsJson`) and is
  echoed on the same function-call part. An unsigned old row disables thinking
  rather than producing a 400; do not "fix" that by sending an empty signature.
  Signatures are opaque provider data: do not log them, parse them, or include
  them in prompts.
- **OpenAI Responses reasoning state is opaque and replayed only with reasoning.**
  The adapter sends typed input items with `store=false`, requests
  `reasoning.encrypted_content` when thinking or an effort level is set, stores
  completed reasoning items in `messages.providerState` (a JSON array of raw
  items), and replays them before the earlier function call only when the next
  request also enables reasoning. Never parse, display or log
  `encrypted_content`; never send reasoning items on a non-reasoning request.
  The transcript stays local; do not replace this with `previous_response_id`.
- **OAuth tokens are secrets and no provider client is bundled.** The provider
  row's OAuth dialog is a generic authorization-code + PKCE flow for a
  user-registered public client; the app ships no Claude/ChatGPT/Copilot/Kimi
  client ID and must not start. `oauth.config.<id>`, `oauth.tokens.<id>` and
  `oauth.pending` live only in `SecureStore`, never in a backup, log or prompt.
  The callback is a public `inhuman://oauth` intent: verify the scheme, host and
  state, keep the pending flow on a forged callback, and exchange the code with
  the stored verifier. Refresh before expiry; do not add a webview, cookie jar or
  password field. A key and an OAuth token are alternatives, not two grants.
- **Document tools are workspace-bound and must report loss.** `read_document`,
  `write_document` and `convert_document` resolve paths through
  `SessionWorkspace`, use the same quota/free-space checks, and disappear with
  the file tools on a failed fence check. An unreadable or unsupported file is a
  refusal with a reason, never an empty success. A DOCX/XLSX write must copy the
  package parts it does not understand and preserve `[Content_Types].xml` and
  relationships; a conversion must return a `LossReport`. PDF and legacy Office
  adapters are host-supplied and must run on the device before their format is
  claimed; the Android host covers PDF text/creation, XLS read/write and DOC
  read/write. DOC writing uses the bundled blank Word 97 template; never emit a
  renamed DOCX/RTF as `.doc`, and flatten tables/formatting with a stated loss
  rather than pretending the structure survived. Do not add a cloud converter or
  a second runtime.
- **`run_javascript` is a calculator, not a shell.** It is registered only after
  `SandboxRuntime.probe()` proves `fetch`/`XMLHttpRequest`/`WebSocket`/`require`/
  `process` are `undefined`, `1+1` works, and an isolate with a 16 MiB heap can
  be created. A failed probe removes the tool; do not leave a warning and a
  working tool. It has no `SessionWorkspace`, no host callback and no network
  bridge; the only data crossing is the script string in and one result string
  out. Timeout is 10 seconds and closing the isolate is what stops a runaway.
  Never pass a file handle, a URL or a provider key into it.
- **A failure chunk must be `trySend`-ed *before* `close()`.** A chunk sent
  after the channel closes is dropped and the collector sees a clean end, so
  the failure becomes indistinguishable from "the model said nothing".
- **The closing `appendMessage` is wrapped in `NonCancellable` when a turn ends.**
  A cancelled coroutine refuses to suspend, so without it `appendMessage` throws
  and stopping a reply loses the part already received — after the user was
  billed for it. (It is the row insert at `ChatViewModel.kt:1040`, not the
  tool-layer `writeText`.)
- **Message order needs its tie-break.** `ORDER BY createdAt ASC, id ASC`, and
  `ConversationStore.SAID_ORDER` must agree with it; a tool result and the turn
  that asked for it routinely share a millisecond.
- **`mutates` and `needsApproval` are different questions.** The first decides
  whether replay-with-tools would change something; the second whether a call
  may happen.
- **An approval is recorded before it is shown, and an unanswered one is never a
  yes.** `approval_audit` gets an `asked` row before `pendingApproval` is
  published, and the same row moves to `allowed` / `declined` / `timed_out`
  under `WHERE decision = 'asked'`. The 10-minute timeout is a refusal; the
  audit write is best-effort and must never change the decision. `messageId` is
  deliberately not a foreign key: rewinding a turn must not erase the record
  that its call was asked about. Deleting the conversation is the retention
  boundary.
- **Continue resumes the transcript; it does not add a user message or
  overwrite the turn's start checkpoint.** `continueInterruptedTurn` is offered
  only when `isInterruptedTurn` sees a tail with no finished answer -- a user
  message, a tool result, or an assistant row with unexecuted calls -- and not
  when the last assistant row carries an error (that tail belongs to Retry).
  The transient `CONTINUE_INSTRUCTION` is never stored. `turnSnapshotReady` is
  taken from `hasStartCheckpoint` for the original user message, so the gate
  must not capture the post-interruption workspace over it. Persistence is per
  round: text still streaming at a kill is gone, and the banner says so.
- **The final-answer retry keeps tools but suppresses calls.** The **Answer
  again** choice in Rewind rewrites final text from existing results; keeping the
  definitions preserves the cache prefix, while a transient system instruction
  and an app-side guard prevent execution. Other Rewind points keep a
  checkpointed prefix and rerun **with tools**, so only a suffix whose tools are
  reversible may be discarded. Do not collapse the two paths.
- **Rollback restores the workspace before deleting the turn.** The **Edit your
  message** choice in Rewind restores the `start` checkpoint, then removes the
  message and all descendants and restores text/attachments. A restore failure
  must leave the rows intact **and the workspace exactly as it was** — the
  restore builds the checkpoint beside the workspace and swaps it in, rather
  than emptying the workspace first, because a copy that failed halfway used to
  leave half a checkpoint and none of the user's files while returning `false`.
  A turn containing `reversible = false` (`ssh_exec`,
  `ssh_upload`, `ssh_write_file`) gets no Edit/rollback choice at all.
- **Checkpoints are content-addressed, per turn and per completed tool group.**
  `start` plus group manifests live under
  `filesDir/rollback/<conversation>/<user-id>`. Retention is 1/3/5/10 turns; the
  chat menu shows count/bytes. Rewinding one turn deletes only that turn's
  directory; a mid-turn regeneration trims only manifests the rewritten
  transcript no longer references. Do not reintroduce a single mutable
  per-conversation snapshot.
- **SSH consent is a capability grant, not an approval.** It is persisted in
  `conversations.sshAllowed`, checked before the ordinary policy, revoked by the
  chat menu and by the global Settings switch. Do not replace it with
  `needsApproval`, and do not let `toolsJson = null` mean SSH consent. While the
  global switch is off, every SSH tool is **absent** from every picker, approval
  table, home tool summary and request schema — not a disabled row. Use
  `ToolCatalog.visible(sshEnabled)` for user-facing lists; a greyed row still
  advertises the capability. Adding `ssh_exec`, `ssh_upload` or `ssh_write_file`
  to an existing grant resets `sshAllowed`; adding `ssh_probe`, `ssh_list` or
  `ssh_read_file`, and removing any tool, does not.
- **`ssh_probe` is a fixed script, not a second exec.** It accepts connection
  details only; do not add a command, path or argument that the model can vary.
  The moment it can run model-supplied input it is `ssh_exec` with a better name.
- **Conversation tools are changed only by the user, only from the sheet, and
  only for the next turn.** No tool changes tools, and no message or tool result
  can widen the set. The sheet offers the assistant's tools plus the
  conversation's existing ones (`conversationToolPlan`); it is disabled while a
  reply streams; `setConversationTools` is the only writer of
  `conversations.toolsJson` apart from creation. An edited conversation stores
  an explicit list, so a later release adding a tool does not silently grant it.
  Hidden remote names survive an edit made while SSH is off.
- **SSH limits are user ceilings, not model settings.** `SshLimits` clamps every
  model request to `[floor, min(userCeiling, hardMax)]`; a missing value uses the
  app default, not the ceiling, so omitting a parameter cannot select the maximum.
  The four keys under `ssh.*` are written only by Settings. Do not expose the
  ceiling itself as a tool argument.
- **Every assistant `tool_calls` block needs tool results on the wire.** A
  process death while approval/consent is open leaves the assistant row without
  results; `closeDanglingCalls` inserts `[interrupted]` results in the request
  history. Without it, every later turn is HTTP 400 and the conversation is
  effectively bricked.
- **The quota check walks the tree once**, via `SessionWorkspace.usage()`.
  Asking for bytes and count separately traversed the workspace twice per write.
- **Backup import swaps files before Room opens.** A staged backup is decrypted
  and validated while the app runs, then `InhumanApp.onCreate` replaces the
  database and workspace directory before any repository opens Room. The current
  files are renamed aside first and restored if the swap fails. Never perform
  this while a Room connection is open. API keys are intentionally absent.
- **Turns are bounded: one per conversation, two per process, one tool at a
  time.** `isStreaming` is the per-conversation gate; `TurnConcurrency` is the
  process gate, acquired in `runTurn` after the no-key/no-model refusals and
  released in `finally`, so a stop or a throw cannot leak a slot. The third turn
  is **refused with an error, not queued** -- a queue nobody can see is a turn
  the user thinks has started. The engine executes calls sequentially and a test
  measures overlap at one; do not port the desktop's parallel calls or add a
  subagent without answering `docs/08-port-philosophy.md` Q4 (subagents: one,
  serial, non-recursive).
- **Turn notifications are keyed by conversation.** `ChatViewModel` reports a
  `TurnStatus`; `MainActivity` passes it to `TurnNotifications`; the foreground
  service renders one notification per active turn, and a summary only when
  several run. Do not reintroduce a single notification id/stop handler: it
  would overwrite or stop the wrong conversation. Do not tie the service to a
  screen's composition, or navigating back would stop it while the turn keeps
  billing.
- **Diagnostics never records a key, a prompt or file content, and never throws
  into the call site.** A logger that can fail a turn is a second bug; one that
  keeps the user's text is a leak with a retention policy. `docs/10-diagnostics.md`
  is the contract.
- **The updater verifies before it installs.** It checks the manifest's
  SHA-256, the APK's package name and version code, and that the signing
  certificate set matches the installed app; then `PackageInstaller` still asks
  the user. HTTP is accepted only for private/LAN/Tailscale hosts. Do not
  replace this with a URL check or a silent install. The log upload uses the
  same boundary and only runs when the user taps it; the agent still has no
  network path.
- **The eleven-minute chat read timeout is for the body, not for silence before
  the response headers.** `HeaderTimeoutInterceptor` cancels a call that has not
  received headers within 90 seconds. The 2026-10-25 log had two requests with no
  `HTTP 200` line and a spinner until the app was restarted; removing this
  interceptor restores that. Do not fold it into `readTimeout`. Its
  `HeaderTimeoutException` message contains "no response", which is what makes
  `chatErrorAction` offer Retry.

---

## Verification before calling something done

```powershell
.\gradlew.bat :app:assembleDebug `
  :app:testDebugUnitTest `
  :core:data:testDebugUnitTest `
  :core:db:test `
  :core:engine:test `
  :core:ai:test `
  :core:files:test `
  :core:documents:test `
  :core:common:test `
  :desktopApp:test `
  --console=plain --rerun-tasks
```

`--rerun-tasks` matters: with the build cache warm, a plain run can report
`BUILD SUCCESSFUL` without compiling the file you just changed. `tools/ci.ps1`
runs exactly this list and fails on the first compiler warning; a CI host should
call that script rather than duplicate the arguments. Check that the
warning output is empty as well as the error output — this tree currently
compiles clean, and that is worth keeping. `:core:common`, `:core:ai`,
`:core:files`, `:core:documents`, `:core:db` and `:core:engine` are plain JVM modules now;
`:desktopApp` is the full Compose Desktop Windows host and consumes the same
jars; its build, packaging and verification details are in
`docs/12-desktop.md`.
`:core:db` owns the Room schema *and* the platform-free repositories
(`ConversationRepository`, `AgentRepository`, `PolicyRepository`,
`SnapshotStore`); `:core:data` is the Android adapter (Keystore `SecureStore`,
`Database.open(context)`, backup, `DocumentsProvider`). Repositories take a
`DataPaths` and, where needed, a `SecretStore` -- never a `Context` -- which is
what lets the desktop open the same database and workspace layout.
`:core:engine` is the turn loop; the desktop window and every Android provider
turn use it, SSH included. SSH consent, approval, the start checkpoint and
argument normalisation are injected through the engine's approval hook from
`ChatViewModel.gateEngineCall`; a missing provider or model is refused before
the engine starts. There is no second turn loop.

Three tests are skipped by default, all requiring external credentials:
`DeepSeekWebLiveTest` talks to the real Search and Fetch APIs and needs a key (and
spends money). `EngineLiveTest` makes one small real chat completion when
`DEEPSEEK_LIVE_KEY` is set; it is skipped otherwise. `SshLiveProbeTest` lists
`/tmp`, downloads `/etc/hostname` and runs the fixed `ssh_probe` snapshot from a
real host when `INHUMAN_SSH_HOST`/`INHUMAN_SSH_USER`/`INHUMAN_SSH_PASSWORD` are
set; it never uploads, writes or runs an arbitrary remote command.
`RealProviderProbeTest` runs only when the device already has a DeepSeek key; it
makes one `/models` call and is a no-op otherwise. The `:core:data`
instrumentation suite (migrations, workspace lifetime, provider profiles, the
documents provider) needs a device; its list is in
`docs/README.md`.

The desktop app compiles to Java 17 but **runs and packages on Java 21+**: the
Markdown renderer is Java 21 bytecode. Set `INHUMAN_DESKTOP_JAVA_HOME` to a
**full** JDK 21+ with `jpackage` (Temurin 21 here; Android Studio's JBR has the
runtime but no `jpackage`) before `:desktopApp:run`, `createDistributable` or
`packageMsi`; tests and compilation still use the Gradle JDK 17. A run without it
fails at the first rendered reply, not at build time.

Building the MSI also needs WiX 3 (`candle.exe`/`light.exe`). Point `WIX_PATH` at the
WiX directory (`C:\android-toolchain\wix314` here); the Compose plugin then uses it
instead of trying to download WiX from GitHub. The MSI is per-user: it installs to
`%LOCALAPPDATA%\inhuman` and the app keeps its database/workspaces in the sibling
`%LOCALAPPDATA%\inhuman-data`, so uninstalling the app does not delete the data.
It also carries `desktopApp/src/main/resources/icon.ico` and a single-instance
file lock (`instance.lock` in the data directory).

`tools/sign-msi.ps1 -Msi <file> -Pfx <pfx> -Password <password>` signs the built
MSI; `-Thumbprint` works for a certificate already in `CurrentUser\My`. The
checked-in workflow uses a self-signed development certificate under `.signing/`
(not trusted by default, and gitignored); distributing to other machines needs a
real code-signing certificate.

Release builds sign from `keystore.properties` when present and fall back to the
debug key otherwise. The keystore and properties are gitignored; losing them
means no future release can update an installed build.

---

## House style

The comments in this codebase explain **why**, and the ones worth reading are
usually about something that went wrong. Match that. If you fix a bug, the
comment should say what the bug *was* and how it presented, not just what the
code now does — several of the most useful paragraphs here exist because
somebody wrote down a failure that would otherwise have been reintroduced.

Where a doc comment and the code disagree, the comment is the bug. One was
found making claims the implementation did not honour (the Markdown renderer's
incrementality), and a sweep that had never been written was another;
`docs/07-gotchas.md` section C carries the full list, which is now long enough
that finding one is expected rather than remarkable.
