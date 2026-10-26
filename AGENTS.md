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
| `docs/03-ai-provider.md` | Provider profiles, the OpenAI-compatible adapter, SSE, demo mode, **the paid `web_search`/`web_fetch` backends**, **the SSH tools (`ssh_probe`, `ssh_list`, `ssh_read_file`, `ssh_exec`, `ssh_upload`, `ssh_write_file`, `ssh_download`)**, and the user-set `SshLimits` ceilings |
| `docs/04-data-persistence.md` | Room schema v14, orderings, migrations, repositories, `SecureStore`, the `DocumentsProvider`, encrypted backup |
| `docs/05-files-sandbox.md` | The sandbox, quotas, the eight file tools, import/export, the design layer |
| `docs/06-agent-loop-ui.md` | The turn lifecycle, the approval gate, cancellation, the context gauge, layout |
| `docs/07-gotchas.md` | The load-bearing invariants, the current defects, and comments that contradict the code |
| `docs/08-port-philosophy.md` | **Why** the runtime is ported, which options the project refuses to have, the seven design questions, the paid-disclosure rule (§1.4), and the SSH exception (§1.2.1/§1.5) |
| `docs/09-approvals.md` | The approval model: why the default is no prompts at all, how per-tool, per-assistant and per-path rules compose, and what of it is built |
| `docs/10-diagnostics.md` | The flight recorder: what is logged, what is deliberately not, where the file lives and how to share it |
| `docs/11-conversation-tools.md` | The conversation-level tool sheet: who may change it, the assistant ceiling, when it applies, and why it is not a new SSH exception |
| `docs/12-desktop.md` | **Windows x64 host**: architecture, feature map, security invariants, build/package/sign commands, verification and known limitations |
| `docs/13-product.md` | **Technology-neutral product document**: design principles, current features, future plans |
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
  and credentials the user supplies; the app currently has no host/credential
  configuration. Saving connection details for convenience is planned, without a
  security or privacy promise (`docs/13-product.md`). SSH **credential**
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
just the two web tools.

**Remote capability is disclosed separately.** Every SSH tool carries `remote =
true`, a filled `REMOTE` badge, a notice above the tool list, and a dedicated
first-call consent dialog. The app currently does not configure a host or a
credential store; the user gives them to the agent. Saving connection details for
convenience is planned, without a security or privacy promise
(`docs/13-product.md`). SSH **credential** arguments are redacted
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
  :core:common:test `
  :desktopApp:test `
  --console=plain --rerun-tasks
```

`--rerun-tasks` matters: with the build cache warm, a plain run can report
`BUILD SUCCESSFUL` without compiling the file you just changed. Check that the
warning output is empty as well as the error output — this tree currently
compiles clean, and that is worth keeping. `:core:common`, `:core:ai`,
`:core:files`, `:core:db` and `:core:engine` are plain JVM modules now;
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
