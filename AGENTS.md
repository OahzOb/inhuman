# Working on inhuman

Notes for whoever picks this up next, human or agent. The README explains what
the app is; this file explains how to change it without breaking what it
already gets right.

**Before reading the source, read `docs/`.** `docs/README.md` is the index: one
line per numbered file, in reading order, plus the proposals. The ones to read
before touching anything are **07** (the load-bearing invariants and the
mistakes that already cost someone a day) and **08** (why the runtime is ported
and which options the project refuses — the authoring principle is §1.8). **13**
is the product document and deliberately has no code references; **14** is the
documents/voice decision; the feasibility studies in `docs/archive/` are
historical. Keep the docs current: a commit that changes a documented behaviour
changes the document in the same commit, or the doc becomes the next "comment
that is the bug".

## Scope: Android only

The Android app is the product. The Windows x64 desktop host that once consumed
the same core was removed on 2026-11-20: it was not used, and its compile, test
and UI work had begun to tax the Android build for no product reason. Its source
is in git history before that date, and `docs/archive/` keeps the historical port
studies. Do not reintroduce a second host, a desktop module, or a "for parity"
port without an explicit product decision.

A change to a shared core module (`:core:ai`, `:core:files`, `:core:db`,
`:core:engine`, `:core:common`, `:core:documents`, `:core:design`) is still a
shared-core change: keep it platform-free where it already is, and keep its JVM
tests green. Those modules are plain JVM because that is what makes them testable
without a device -- not because a second host exists.

---

## What this app is for, and what it refuses

The short version, stated once so it is not re-litigated: DeepSeek Harness's
agent design is worth having on a phone, so it is being ported — **with hard
limits on what the agent can read, write and run.** They borrow the device's
compute while isolating the filesystem; they are **not** about disclaiming
responsibility.

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
  hand it, cannot reach the network, and cannot reach your files.* **Three
  exceptions exist, and all are off by default and granted per conversation.**
  The first is **the SSH tools (`ssh_probe`, `ssh_list`,
  `ssh_read_file`, `ssh_exec`, `ssh_upload`, `ssh_write_file`, `ssh_download`),
  off by default, enabled globally, selected explicitly on the assistant or the
  conversation, and granted for that conversation's current SSH capability set.
  The pickers split SSH into two families with separate master controls —
  **read-only** (`ssh_hosts`, `ssh_probe`, `ssh_list`, `ssh_read_file`,
  `ssh_download`) and **exec/write** (`ssh_exec`, `ssh_upload`,
  `ssh_write_file`) — so choosing one never implies the other; that split is
  what keeps the read-only profile a single gesture. Adding
  `ssh_exec`, `ssh_upload` or `ssh_write_file` asks again rather than
  inheriting a narrower read-only grant.** When SSH is on, the agent
  can inspect a host, read files, write small text files, transfer files, and —
  only if `ssh_exec` is selected — run commands on a remote machine. The app
  keeps an address book of aliases, addresses, ports, users and optional
  fingerprints — **never a credential** — and each conversation decides which of
  those `ssh_hosts` may show it, without a security or privacy promise
  (`docs/13-product.md`).
  The second is **`download_url`**: the app fetches one `https` URL the agent
  names and saves the bytes under `downloads/` in the workspace. The agent still
  has no socket of its own, but a URL can carry data out, so it has its own
  global switch (default off), `optIn` flag and per-conversation acknowledgement
  (`conversations.downloadAllowed`); https only, public addresses only, 64 MiB
  per file, into the ordinary quota, never overwriting, nothing executed. Do not
  add another exception without answering `docs/08-port-philosophy.md` §1.2.1,
  §1.5 and §1.6.

**Role-play is content configuration, not a mode.** A character card
(`agents.rpJson`) and a scene (`conversations.sceneJson`) are text rendered into
the system message by `core/engine/.../roleplay/`; they add no tool, approval,
SSH grant or network path, and nothing in the capability checks reads them. Do
not turn role-play into a permission level, a global switch or a second turn
loop: single character and single speaker is the implemented shape, and
multi-character scenes (several calls per turn, `messages.speakerId`) are a
separate design that answers `docs/08-port-philosophy.md` Q4. A greeting is a
real assistant row and the request's wire-only start line must stay wire-only;
see `docs/07-gotchas.md` A26.

**The agent authors content, never behaviour.** It may draft and rehearse
prompts, cards, scenes, world-book entries, saved prompts and a permanent
workspace's root `AGENTS.md`; tool selection, provider/model, sampling and
approval rules are only suggestions and never enter a proposal; how tools run,
the sandbox, permissions, grants, keys and global settings are untouchable. A
rehearsal is bounded, ephemeral and tool-free (stubs only), it grants nothing,
and it proves only "these cases did not fail". The user's tap is the only writer.
Do not add a tool that writes a prompt, card, scene or `AGENTS.md`; do not
rehearse with real tools. `docs/08-port-philosophy.md` §1.8 (mechanism not built
yet), `docs/13-product.md` #13.

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
first-call consent dialog. Settings holds an **address book** — alias, address,
port, username, optional fingerprint, **never a password or key** — as one
document in the platform secret store; backups exclude it, and there is no
stored credential for any call to use. **`ssh_hosts`** (`optIn`, hidden while the
switch is off, not `remote`) shows a conversation the entries the user ticked
(`conversations.sshHostsJson`); that is information, so a change applies to the
next turn and does **not** reset the SSH grant. The connection is in the call:
`host`/`port`/`username` plus `identityFile` or `password`, so the agent can only
use what the user handed it, and a password sent that way is part of the
conversation. Command text, paths and `ssh_write_file` content are not redacted.
The ordinary approval dialog must never show a password or private key;
`ssh_upload` and `ssh_write_file` are `NO REWIND` because they replace remote
files. The rules are `docs/08-port-philosophy.md` §1.2.1 and §1.5.

**App-proxied downloads are disclosed separately too.** `download_url` carries
`optIn` and `gatedByDownload` in the catalogue, a global Settings switch that is
off by default, and a dedicated first-call acknowledgement showing the URL and
host. It is not `remote` (it changes nothing outside the device) and not `paid`
(the app pays no third party). It is `REWIND` because it writes a workspace copy,
but the request itself is an outbound channel: the consent text, the Settings
copy and the tool description all say that a URL can carry data out, and
diagnostics record only host and byte count. The rules are
`docs/08-port-philosophy.md` §1.6 and `docs/07-gotchas.md` A27.

**Local web tools are app-proxied reads, and the page fetch is a third exception.**
`web_search_local` reads DuckDuckGo's or Bing's public result page from the device and
is the same shape as the Bilibili reads: no key, no bill, a query and no URL,
global switch plus the assistant's explicit `optIn`, absent when off, no
conversation grant. `web_fetch_local` is the `download_url` shape: free and
unlimited, the model names the URL, so it has a global switch (default off), its
own `optIn` row, and a once-per-conversation acknowledgement persisted in
`conversations.localFetchAllowed` (schema v30, `MIGRATION_29_30`); turning the
switch off clears every conversation's grant. It is **https-only**, public
addresses only, GET-only, no cookie/credential/custom header, and uses the one
`GuardedHttp` guard (DNS check at connect, redirect re-validation) shared with
`download_url` — do not add a second SSRF path. `render = true` loads the page in
a fresh WebView: page scripts run (the point), so there is no JS bridge, cookie
injection, file access or persisted storage, and the WebView is destroyed per
call; the Settings copy, tool description and consent dialog all say what leaves
the device. Diagnostics record host/bytes only. Both local tools share a fixed
per-turn budget — 10 requests, at least a second apart, at most 3 rendered; a
call past it is refused before any request and says so. Rules:
`docs/08-port-philosophy.md` §1.7, mechanism: `docs/03-ai-provider.md`.

Full reasoning, plus the design questions and how each was answered, is in
`docs/08-port-philosophy.md`. Do not add a mode, a permission prompt, or a "pro"
escape hatch without answering it there first.

---

## Window ratios, always

**Every change is verified on both a phone-sized and a tablet-sized window, and
on the ratios in between** — actually run them, not "checked on one and reasoned
about for the other". Width alone is not a layout: a 16:9 phone in landscape is
640dp wide but only 360dp tall, and a square foldable is 700dp on both axes.
`WindowFrame` (`core/design/.../Layout.kt`) classifies both axes and derives
`usesTwoPane`, `isCompact` and `usesFab`. The matrix, the expected shell per case
and the bugs it fixed are in `docs/06-agent-loop-ui.md` § "Window ratios, not
just width".

```powershell
emulator -avd dsh_tablet        # Android 15, x86_64, Pixel Tablet
# one emulator at a time: this host does not have memory for several
```

The phone is not a smaller tablet: the primary action moves from a FAB to a
header Create to a top-bar button, the gutter is 20dp vs 32dp, and an uncapped
paragraph at 1280dp is unreadable. The tablet AVD currently fails to load its GL
driver; the resized-phone workaround is `docs/07-gotchas.md` C12.

### Space, type and the visual system

**Cap the measure, centre what is capped.** `ReadableMeasure` 640dp governs forms
and settings; the transcript and composer share 900dp, user bubbles 560dp. A cap
binds only when `widthIn` comes **before** `fillMaxWidth` — the reverse order sets
`minWidth = maxWidth` and the cap silently does nothing (seven sites once did
this). The full rules, the measurement that made it stick, the Graphite Workbench
system (warm paper / warm graphite, one signal accent, mono only for metadata,
hairlines not cards, grid at 12% only on empty canvases, shared components in
`core/design/.../Workbench.kt`) and `Layout.Space` are in
`docs/05-files-sandbox.md` § "The design layer".

The composer is one hairline-bounded surface — a full-width text area over a
48dp action row, attach and microphone left, context ring and send/stop right —
and it must not consume `safeDrawing` before `imePadding()`. Both were bugs found
by running the app; see `docs/07-gotchas.md` B32 and `docs/06-agent-loop-ui.md`.

---

## Verifying on a device

```powershell
$env:JAVA_HOME = 'C:\android-toolchain\jdk17'
.\gradlew.bat :app:assembleDebug
adb -s <serial> install -r -t app\build\outputs\apk\debug\app-debug.apk
```

`tools/build-and-shot.ps1` builds, installs, launches and screenshots (it checks
mean brightness, because `screencap` succeeds on a black frame);
`tools/verify-*.ps1` drive the app through `uiautomator` + `adb shell input`. The
script table, the `UiDump.psm1` quote lesson and the emulator commands are in
`docs/02-architecture.md` § "Running it on a device" and § "The `tools/` scripts".

### Demo mode is the offline verification path

With no API key, Settings → Demo mode gives a scripted provider: with file tools
on it writes a file and deletes it, so the tool loop, the results and the closing
summary are reachable with no key, no network and no bill. **It raises no
approval prompt**, because deletion does not ask by default — configure a rule on
`delete_file` to exercise that path. A message containing `plain` gives prose. In
a workshop conversation it writes nothing: `scene` proposes a scene, and `config`
(or `配置`) proposes an assistant, an instruction and a prompt; `scene` wins when
a message names both. The script and why each case exists are in
`docs/03-ai-provider.md` § "Demo mode: a provider that answers from a script".

### What adb cannot verify

Screenshots and dumps are for layout and end states, not for anything inside a
stream: a demo reply streams for ~3.6s and one dump costs ~700ms, so an
incremental parse's intermediate states are not observable at any sampling rate.
Extract the invariant into a pure function and test it, then use the device only
to confirm the path runs and renders — `DeltaCursor` and `ContextUsage` exist for
that reason. More: `docs/02-architecture.md` § "What adb cannot verify".

### The LAN server: updates and uploads

`tools/dev-server.py` serves `dist/` and accepts `POST /log` and
`POST /conversation` into `logs/`. No authentication: LAN or Tailscale only,
never a public port. The app keeps two independent addresses — the update server
(usually the GitHub Releases feed) and the upload server (the LAN/Tailscale dev
server); chips fill the **update** field only.

```powershell
# The interpreter matters: Windows firewall allows C:\python314\python.exe
# inbound, and not the conda interpreter that plain `python` resolves to.
C:\python314\python.exe tools\dev-server.py --port 8787 --dist dist --logs logs
```

**Publishing needs the version bump first** (`app/build.gradle`; the updater
offers only `versionCode >`), then `.\tools\release-apk.ps1 -Notes '...'`. The
note must not contain typographic quotes: PowerShell 7 treats `“ ”` as string
delimiters, so the argument splits. Without `keystore.properties` the release APK
is debug-signed — it updates an earlier `dist/` install, not one signed with
another key. The updater verifies SHA-256, package name, version code and the
signing certificate before `PackageInstaller` asks. Details: `README.md` § "Local
updates, log and conversation upload".

Logs the phone uploads land in `logs/` here. What that file does and does not
contain is `docs/10-diagnostics.md`.

---

## Things that are load-bearing

Not style. Each of these was a bug, and undoing it silently restores the bug.

- **`SessionWorkspace.resolve()` is the only place a path becomes a `File`.**
  No symlinks, ever, plus a real-path prefix check. On Windows that check must
  use `Path.toRealPath()` for existing components: `File.canonicalFile` does
  **not** resolve a junction, and `Files.isSymbolicLink` does not report one.
  A junction escape found by that Windows check is pinned by
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
- **The saved-prompt library is user configuration, tap-gated, not capability.**
  It is the `saved_prompts` table; a title is unique so re-saving updates rather
  than clones, and the assistant row keeps its own copy. The workshop may read
  it (`app_list_prompts` / `app_read_prompt`) and draft an entry
  (`app_propose_prompt` returns a `PromptProposal`), but no tool writes or
  selects it: the proposal card's **Save to prompt library** tap calls
  `ChatViewModel.saveProposedPrompt`, which uses the same `SavedPromptRepository`
  upsert as the editor, and an untapped proposal leaves no row. The model cannot
  delete a prompt or make one an assistant's system prompt; choosing an entry
  only fills that field, still editable. Neither title nor text goes to
  diagnostics. This amends the earlier model-invisible rule
  (`docs/proposals/prompt-library.md`).
- **Workspace instructions are the root `AGENTS.md`, pinned once per conversation.**
  The block is rendered on the conversation's first request, stored in
  `conversations.workspaceInstructions` (schema v21), and appended after the
  assistant prompt and the compaction summary. From then on the file is not
  re-read: editing it does not change this conversation's system message or its
  cache prefix; new conversations pin the current file, and the chat menu's
  **Reload AGENTS.md** is the only way to apply an edit to an existing
  conversation (one deliberate cold request). Null means unpinned and `""` means
  pinned with no file; do not collapse them. The read goes through
  `SessionWorkspace.resolve` (no symlinks), is capped at 16 KiB and cut at a line
  boundary, and never includes the absolute workspace path. Missing, blank,
  binary or unreadable files pin the empty block; a failed workspace self-check
  removes it with the workspace tools. The block is workspace content, not a
  capability: it cannot add a tool, change an approval or grant SSH. The agent
  may write the file -- the user chose that -- but that affects new
  conversations or a reload, not this one; Rewind still restores the file itself.
- **Follow-ups are `suggest_replies`, a tool that ends its own turn.** The tool
  is in the main turn's tool set, `paid = true`, and `endsTurn = true`: the
  engine executes it, persists the result and stops, so the turn does not spend
  a second full-history request making the model read its own suggestions back.
  The executor makes one extra completion against the assistant's configured
  suggestion model (text or tool format), sanitises the list through
  `Advice.sanitize`, and returns `{"suggestions":[...]}`. The result is
  persisted like any other tool result, the chips are rendered from it, and
  `isInterruptedTurn` treats that terminal tail as finished. The tool is
  opt-in twice -- the global switch and the assistant's explicit selection --
  and `ToolCatalog.permits` never grants it from a null "all" selection.
  `useSuggestion` may only fill the composer; it must never send, and a
  suggestion is not an approval or a grant. The old inline `<<<suggestions`
  block, its streaming filter and its transient system instruction are gone; do
  not reintroduce a second mechanism.
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
  the Kotlin comparator inside `ConversationStore.walkActive` must agree with
  it; a tool result and the turn that asked for it routinely share a
  millisecond. One walk serves full rows, search's `BranchRow`s and the rewind
  gate's `MessageTurnRow`s.
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
- **A message sent while a turn is running is a real user row and not a turn
  root.** `messages.injected` (schema v26) marks it; `EngineTurn.beforeRound`
  appends it after a complete tool group, before the next request, and the host
  persists it *before* returning from the hook — a line the model read that the
  transcript lacks is the bug that ordering prevents. Turn grouping, the jump
  list, `lastUser`/Rewind, compaction boundaries and `continueInterruptedTurn`
  must all use "turn root" (`fromUser && !injected`); one insertion otherwise
  moves the Rewind start point and orphans the start checkpoint keyed by the
  root. The wire needs no adapter special case: a user message after the tool
  messages is legal in all four formats. The queue strip is memory-only, like
  the composer draft, and a failed turn holds it rather than retrying the same
  error once per queued message. See `docs/07-gotchas.md` A29.
- **A role-play scene freezes its persona, and that is the promise, not a
  cache trick.** `conversations.rpSnapshotJson` (schema v27) holds the assistant
  prompt, display name and card copied at creation; the projection reads the
  snapshot, not the live assistant, so editing a card or prompt cannot rewrite a
  story in progress. A pre-column scene pins on its first turn (empty means "not
  pinned yet"); the scene-settings **Reload card and assistant prompt** is the
  only update, shown only when the live assistant has a decodable card
  (`ChatUiState.roleplayReloadable`). Do not fall back to the live card once a
  snapshot exists: a cleared card would silently resurrect. `rp_format`/
  `rp_validate` (`core/engine/.../roleplay/RoleplayTools.kt`) are the authoring
  helpers; they attach only as the workshop bullet below says, and `rp_validate`
  additionally requires `read_file`/`list_files`, so a scene with no tools keeps
  an empty set and the validator never becomes a read path the user did not
  grant. It reports structure only, never file prose. The format text is
  generated from the serializers and `RoleplayLimits` (`RoleplaySpecTest` pins
  the match). See `docs/07-gotchas.md` A30.
- **The built-in workshop is one workspace of kind `workshop`, not a permission
  tier.** The app keeps one built-in permanent workspace,
  `workspaces.kind = "workshop"` (schema v29), created lazily on first use
  (`ConversationRepository.ensureWorkshop`, the only writer).
  `MIGRATION_28_29` rewrote the v28 `"roleplay"`/`"studio"` values with no column
  change: role-play becomes the workshop; a configuration row becomes an
  ordinary permanent workspace when a role-play row existed (files and
  conversations kept, helpers and context lost), else it becomes the workshop.
  A non-scene conversation gets twelve helpers after the allow-list:
  `rp_format`, `rp_validate` (needs `read_file`/`list_files`),
  `rp_create_scene`, `app_list_assistants`, `app_read_assistant`,
  `app_propose_assistant`, `app_list_instructions`, `app_read_instruction`,
  `app_propose_instruction`, `app_list_prompts`, `app_read_prompt`,
  `app_propose_prompt`. None is in `ToolCatalog` and all are
  `mutates = false`, so none appears in a picker, approval table or gap report; a
  scene gets none. The kind adds **no capability** -- file/SSH/download tools
  still come from the assistant and the conversation's list -- and no tool lets
  the model create, enter or retag a workspace. `rp_create_scene` returns a
  `SceneProposal` only; the user's tap creates it through `RoleplaySceneCreator`
  with `tools = "[]"`, the frozen snapshot, the greeting, the current model and
  the workshop as its workspace; an untapped proposal leaves no row and no file.
  The configuration helpers only read and draft: reads return content fields
  (never tools, provider or approval rules), each permanent workspace's root
  `AGENTS.md`, and now the saved-prompt library; an assistant or instruction
  proposal opens the existing editor prefilled and the user's Save is the only
  write, while a prompt proposal's card **Save to prompt library** tap is its
  only write -- no tool writes a row. A proposal carries no
  tool/provider/model/approval field and names a workspace by display name, never
  its directory id; do not add an "apply" tool. A saved `AGENTS.md` also reaches
  new scenes there. The assistant editor's prompt **Import** now offers the
  device picker or a two-step browse of any workspace, with the same
  `SessionWorkspace` fence, 1 MiB guard, UTF-8 check and replace/append question
  as the device picker. The workshop is app-owned: no rename, no single or bulk
  delete. One host-written `Workshop context` block covers both jobs and the
  prompt library, from the kind alone; `docs/07-gotchas.md` A31 is the invariant.
- **A role-play reply is painted, not rewritten.** In a scene assistant text
  goes through `RoleplayText` (`app/.../ui/chat/RoleplayText.kt`), not the
  Markdown renderer: narration secondary, quoted speech in the reading colour,
  parenthetical/`*...*` actions secondary italic, `**...**` bold, a `---`
  paragraph a hairline. **Display-only**, and not dependent on the model obeying
  a format: `RoleplayTypography` is a total linear scan of the conventions models
  already write (`"..."`, `“...”`, `「...」`, `（...）`, `*...*`, `**...**`,
  `---`) that hides only the delimiters at paint time. Never persist or send the
  transformed text, never use it for export, search or copy -- stored reply,
  Source view and copy buffer stay raw. Block Markdown falls back to
  `MarkdownText`, and an unclosed marker stays literal (open quotes/parentheses
  are styled only while streaming). `RoleplayTypographyTest` pins the
  conventions, the streaming/unclosed cases, the raw round-trip and the fallback.
- **An imported character card is untrusted text, never a runtime.**
  `RoleplayCardImport` (`core/engine/.../roleplay/RoleplayCardImport.kt`) reads
  PNG (`chara` tEXt/iTXt base64), JSON (Character Card v1/v2/v3 and this app's
  own shape) and CHARX (ZIP or JPEG+ZIP `card.json`), maps the text fields, and
  reports everything else as **not imported**: alternate greetings, world book,
  `system_prompt`/`post_history_instructions`, extensions (regex/HTML UI),
  assets. HTML in mapped text becomes plain text; script/style *content* is
  dropped, no tag is kept for a renderer, nothing is fetched or executed, and
  the raw file is not persisted. Over-limit fields are truncated to
  `RoleplayLimits` and reported; the card name counts toward the same budget.
  The import fills the card editor's draft only -- the user still has to Save --
  and adds no tool, approval, SSH grant or network path. Do not add URL import,
  extension execution or HTML rendering without answering
  `docs/proposals/rp-formatting.md` §9 (P0a is text-only; P1/P2 were not taken).
  `RoleplayCardImportTest` pins the formats, the cleanup, the truncation and the
  failure reasons.
- **A scene can start from workspace files, and that source shares the tool's
  validator.** `NewSceneDialog` offers assistant cards or a permanent workspace
  folder (`rp/<story>/card.json` + optional `scene.json`), browsed and read by
  `RoleplayStoryLoader`; it calls the same `RoleplayValidator`/`RoleplayCodec`
  as `rp_validate`, so the dialog and the tool cannot disagree. The card JSON is
  copied into `rpSnapshotJson` verbatim; a card file's optional `name` is the
  scene title and `{{char}}` (then the assistant name, then the folder name).
  A folder-built conversation keeps that permanent workspace while its tools
  remain the explicit empty list; `scene.json` is a creation template and
  Scene-settings edits never write back. The assistant editor's **Export to
  workspace…** writes `rp/<folder>/card.json` through `WorkspaceTransfer`,
  refuses to overwrite, writes the assistant's name into the card when it had
  none, and is the migration path for legacy cards. Do not move the folder rules
  into the Android layer: the engine loader is the one place both callers read
  them.
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
  `ssh_read_file`, and removing any tool, does not. Changing the conversation's
  host selection does not touch it: the list is information.
- **Tool families are a presentation of the selection, not a second permission
  system.** The assistant editor, the conversation sheet and both approval-rule
  editors group the catalogue by family and give each family one master control
  (`app/.../ui/common/ToolGroups.kt`, `ToolGroupRows.kt`). A family switch writes
  the same per-tool names (or the same per-tool rules) that ticking every member
  would, so `ToolCatalog.permits`, `PolicySet` and the approval audit see the
  ordinary representation and no new schema or rule kind exists. Two
  consequences are load-bearing: the SSH family is split into `SshRead` and
  `SshWrite` so the read-only grant can never be the way `ssh_exec` arrives, and
  a family switch is an explicit gesture naming its members -- it is not `null`
  ("all"), which still refuses every remote and opt-in tool. Changing the family
  layout is a UI change; changing who may widen a selection is not.
- **URL download consent is a capability grant, and one validator serves both
  download paths.** `download_url` is gated four ways: the Settings switch
  (`SecureStore.KEY_DOWNLOAD_TOOL_ENABLED`, default off), the catalogue's
  `optIn`/`gatedByDownload` flags, the per-conversation
  `conversations.downloadAllowed` persisted by the first-call dialogue, and the
  ordinary `ToolPolicy` after that. It is not `needsApproval`. `DownloadUrl`
  (`core/ai/.../tools/DownloadUrl.kt`) is the one scheme/length/credential/
  private-address/filename rule, shared with the Files downloader -- do not add a
  second copy. The request must keep the app's SSRF-guarded client: the custom
  `Dns` check at connect time (DNS rebinding) and redirect re-validation are what
  stop the tool from reaching `127.0.0.1`. The sink must be
  `WorkspaceTransfer.import` so quota, file count, free-space reserve and the
  staging file apply; the URL must never be written to diagnostics (host and
  bytes only). `docs/07-gotchas.md` A27 and `docs/08-port-philosophy.md` §1.6.
- **The address book is information; the call carries the connection.** Saved
  entries live in `SecureStore` as one document with **no credential**, and
  `conversations.sshHostsJson` holds only the ids `ssh_hosts` may return. No
  tool resolves an alias: `SshArguments` reads `host`/`port`/`username` from the
  call and a key from the workspace (`identityFile`, through
  `SessionWorkspace`) or a password. Sharing a host therefore grants nothing and
  changing the selection does not touch `sshAllowed`; the earlier
  alias-to-stored-password design made that toggle a credential grant and is
  recorded as refused in `docs/08-port-philosophy.md` §1.2.1.
- **The gap report may not name a switched-off capability.** `unavailable_tools`
  subtracts the registry from `ToolCatalog.visible(...)`, so a tool the user
  turned off app-wide (SSH, the sandbox, follow-ups) is not in the universe and
  is never reported -- otherwise the tool is the "greyed row" §1.2.1 refuses, in
  text form, and a prompt injection learns the name it wanted. It reports only
  what the user can fix from here: the conversation selection or a missing key.
- **A media tool's absence is a configuration gate, not a capability switch.**
  `decide`, `transcribe`, `generate_image`, `generate_video`, `video_status` and
  `speak` are `gatedByMediaConfig`: while `MediaToolConfig.isConfigured` cannot
  resolve a key and an endpoint for the relevant provider (and, for the four
  file-writing tools, a workspace), `ToolCatalog.visible(mediaConfigured = ...)`
  drops them from every picker -- absent, not greyed (`docs/13-product.md`,
  `docs/07-gotchas.md` B46). Do not fold this into `gatedBySsh`: the user fixes
  it in Settings, so `unavailable_tools` passes `null` and still reports it as
  "needs configuration", and a hidden name must survive in storage so
  configuring the backend restores the selection. The predicate exists once
  (`app/.../ui/chat/MediaTools.kt`): a picker and the request builder disagreeing
  hides a working tool or offers an inert one, and neither shows up as a failure.
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
- **The quota check walks the tree once**, via `SessionWorkspace.usage()`;
  asking for bytes and count separately traversed it twice per write.
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
  measures overlap at one; do not add parallel tool calls or a subagent without
  answering `docs/08-port-philosophy.md` Q4 (subagents: one, serial,
  non-recursive).
- **Turn notifications are keyed by conversation.** `ChatViewModel` reports a
  `TurnStatus`; `MainActivity` passes it to `TurnNotifications`; the foreground
  service renders one notification per active turn, and a summary only when
  several run. Do not reintroduce a single notification id/stop handler: it
  would overwrite or stop the wrong conversation. Do not tie the service to a
  screen's composition, or navigating back would stop it while the turn keeps
  billing.
- **Diagnostics never records a key, a prompt or file content, and never throws
  into the call site.** A logger that can fail a turn is a second bug; one that
  keeps the user's text is a leak with a retention policy. The manual recording
  copies the same formatted lines into a bounded second file -- same redaction,
  same no-content rule -- so it must never become a different kind of record.
  `docs/10-diagnostics.md` is the contract.
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
  `HTTP 200` line and a spinner until restart; removing this interceptor
  restores that. Do not fold it into `readTimeout`. Its
  `HeaderTimeoutException` message contains "no response", which is what makes
  `chatErrorAction` offer Retry.
- **A provider retry is per round and discards that round's output.**
  `ChatEngine` re-sends a transiently failed request up to five times
  (`RetryPolicy`), before committing the round and before running any tool in
  it, so a retry in round 2 cannot replay round 1's write. The failed attempt's
  text is cleared in the engine, and the Android row swaps its `deltas` channel
  because the incremental Markdown renderer cannot rewind; appending the retry
  would duplicate the answer. A `Finish` followed by an `IOException` is a clean
  end. Do not move retry into the provider (it cannot know what was rendered)
  and do not retry a whole turn (`docs/07-gotchas.md` A21).
- **Bilibili reads are an app-proxied read, not a new exception.** The ten
  tools (`bili_search`, `bili_video`, `bili_comments`, `bili_article`, `bili_up`,
  `bili_trending`, `bili_danmaku`, `bili_bangumi`, `bili_live`, plus the
  login-only `bili_transcript`) are anonymous where possible: no account, no key,
  no stored credential, and the browsing id lives in memory for the process. The
  app makes the request; the assistant still has no network and no URL argument
  (a BV/cv/ss/ep id, a mid, a room id or a keyword only). They are
  `optIn` and `gatedByBilibili`: while the Settings switch is off they are
  **absent** from every picker, request, approval table and gap report, not
  greyed; while on, an assistant must still select one, and `null` ("all")
  never includes them. They are neither `remote` nor `paid`. Everything they
  return is untrusted third-party text — keep the warning in the tool
  descriptions, and never treat page content as instructions.
  Two invariants came with the second read surface: **`bili_danmaku` is a
  sample** (Bilibili returns a bounded subset per six-minute window; the result
  names the window count and which ones were read, and the hand-written
  `BilibiliDanmakuParser` must stay total on a truncated body), and **the
  ranking's `rid` and the search's `tids` are different namespaces** — do not
  share one friendly-name map between them. `BilibiliLiveTest` is the one networked anonymous test, skipped unless
`INHUMAN_BILI_LIVE=1`; it is paced with a grace pause because anonymous bursts
are what Bilibili's risk control blocks; a `-509` means the probe was throttled.
Rules: `docs/07-gotchas.md` A22, mechanism: `docs/03-ai-provider.md`.
- **The optional Bilibili login is read-only by construction.** `BilibiliAuth`
  runs Bilibili's QR flow only (no webview, no password field) and stores exactly
  `SESSDATA` and `DedeUserID` under `SecureStore.KEY_BILIBILI_SESSION`; `bili_jct`
  — the write/CSRF token — is deliberately never stored, because the app has no
  Bilibili write path. `bili_transcript` is `bilibiliLoginRequired`, so it is
  absent from every picker, request, approval table and gap report while the
  login is unlinked; comment pages after the first are login-only and say so.
  The transcript is timestamped cues, readable in `from`/`to` windows: when the
  20,000-character cap cuts a long video, the footer names the timestamp to
  continue from, so the result is resumable.
  The login must never grow tools for history, favourites, followings or
  messages: those are account data, not public content, and adding them is a
  separate product decision (`docs/08-port-philosophy.md` §1.2). Tests:
  `BilibiliParsersTest` / `BilibiliMoreParsersTest` / `BilibiliToolsTest` /
  `BilibiliMoreToolsTest` (offline fixtures), `BilibiliAuthTest` (offline),
  `BilibiliLoginLiveTest` skipped unless `INHUMAN_BILI_SESSION` names a
  gitignored scratch session file. Do not commit that file, a cookie, or a
  fixture containing one.

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
  :core:design:testDebugUnitTest `
  --console=plain --rerun-tasks
```

`--rerun-tasks` matters: with a warm cache a plain run can report
`BUILD SUCCESSFUL` without compiling your change. `tools/ci.ps1` runs exactly
this list and fails on the first compiler warning; check the warning output too,
because this tree compiles clean and that is worth keeping. `:core:common`,
`:core:ai`, `:core:files`, `:core:documents`, `:core:db` and `:core:engine` are
plain JVM modules; `:core:db` owns the schema and the platform-free repositories,
`:core:data` is the Android adapter, and `:core:engine` is the one turn loop —
there is no second loop. See `docs/02-architecture.md`.

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

Release builds sign from `keystore.properties` when present and fall back to the
debug key otherwise. The keystore and properties are gitignored; losing them
means no future release can update an installed build.

---

## House style

The comments explain **why**, and the ones worth reading are about something
that went wrong. Match that: a bug-fix comment should say what the bug *was* and
how it presented, not just what the code now does — several useful paragraphs
here exist because somebody wrote down a failure that would otherwise have been
reintroduced.

Where a doc comment and the code disagree, the comment is the bug. One was
found making claims the implementation did not honour (the Markdown renderer's
incrementality), and a sweep that had never been written was another;
`docs/07-gotchas.md` section C carries the full list.
