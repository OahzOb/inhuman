# 02 — Architecture, wiring and the build

## Module graph

Verified against each module's `build.gradle`:

```
desktopApp ──┬──> core:common
             ├──> core:ai      (plain JVM)
             ├──> core:files   (plain JVM)
             ├──> core:db      (plain JVM, schema + repositories)
             └──> core:engine  (plain JVM, first turn-loop slice)

app ──────────┬──> core:common  (plain JVM)
              ├──> core:design  (Compose Android; depends on no core module)
              ├──> core:data ──api──> core:files ──impl──> core:ai ──impl──> core:common
              │                    ├──api──> core:ai
              │                    └──api──> core:db
              ├──> core:ai
              └──> core:files
```

Read the arrows as "must be able to compile against". `core:data`, `core:ai` and
`core:files` each also depend on `core:common` directly, with `implementation`;
the diagram leaves that leaf edge out rather than drawing three lines to the same
node. `:core:common`, `:core:ai`, `:core:files`, `:core:db` and `:core:engine` are
**plain Kotlin/JVM modules** now, not Android libraries: the desktop host depends
on the same jars the Android app consumes. `:core:data` and `:core:design` remain
Android-only. `:core:db` owns the Room entities, DAOs, database, migrations and
the platform-free repositories; `:core:data` is the Android adapter around it
(`Database.open(context)`, the Keystore-backed `SecureStore`, the encrypted
backup and the `DocumentsProvider`). The split is what lets the desktop host
create a conversation and append messages against the same schema without a
`Context`.

`:core:engine` is the turn loop: it streams a provider, assembles tool calls,
asks an `EngineApproval` hook, executes tools and repeats until the model
answers without a call or a budget is hit. It does not own persistence,
snapshots, compaction or consent; the host injects those through the hook. The
desktop window and every Android provider turn use it, SSH included.
`ChatViewModel.gateEngineCall` is where the Android host applies the SSH
capability grant, the approval rules, the start checkpoint and argument
normalisation; `DesktopChat` does the equivalent for the desktop host. A missing
provider or model is refused before the engine starts. There is no second turn
loop.
Three things about this graph are deliberate and are commented where they
are declared:

- **`core:files` depends on `core:ai`, not the reverse.** The file tools
  implement the `Tool` contract defined in `core:ai`, so the dependency runs one
  way. The comment in `core/files/build.gradle` says why it is `implementation`
  rather than `api`: leaking the AI module to anything that depends on the
  sandbox would invert the direction.
- **`core:data` re-exports the shared core** with `api project(':core:files')`,
  `api project(':core:ai')` and `api project(':core:db')`, because a
  conversation's lifecycle spans the sandbox that guards it, the provider that
  answers it and the schema that stores it — creating a conversation, naming it
  and listing the models it may use are one lifecycle.
- **`core:ai` exports coroutines and serialization with `api`** because its
  public surface is `Flow<StreamChunk>`, so consumers need both on their compile
  classpath regardless (`core/ai/build.gradle`).

`:core:common` is the shared leaf module. It holds the diagnostics facade and
rolling log (`core/common/src/main/kotlin/com/inhuman/core/common/diag/`) and
the opt-in `@Placeholder` annotation, which nothing applies. The diagnostics
half belongs here rather than in `:app` because it has no Android dependency and
is tested on the JVM; only the file sink, logcat mirror and crash handler live
in `:app` (`docs/10-diagnostics.md`). `@Placeholder` is the marker of choice over
`TODO()`: a `TODO()` would crash the app the first time the path is exercised,
while a warning-level opt-in requirement does not.

The rule the split enforces: **a module with no Android UI must not depend on
one.** `:core:ai` knows nothing about Compose or Activities, which is what makes
the provider testable on the JVM.

## How the app is wired

There is **no DI framework**, deliberately. The graph is small enough to read in
one screen, and the whole construction happens at the top of `InhumanRoot()` in
`app/src/main/kotlin/com/inhuman/MainActivity.kt:60-70`:

```kotlin
val secure     = remember { SecureStore(context) }
val repository = remember { ConversationRepository(context) }
val agents     = remember { AgentRepository(context, secure) }
```

Three singletons for the process, each with a stated reason
(`MainActivity.kt:63-67`): the Keystore lookup inside `SecureStore` is not free;
Room's concurrency is per-instance, so two instances against one file would
serialise badly and invite locking a single instance avoids; and
`AgentRepository` caches the model list, so a second instance would fetch it
twice.

ViewModels receive their dependencies through a hand-written
`viewModelFactory` helper at the bottom of the same file
(`MainActivity.kt:266-271`) — an inline `ViewModelProvider.Factory`. Nothing
else is invented; if you need a new dependency in a screen, add it to the
factory call for that screen.

### Web tools are resolved per turn from the selected backend

```kotlin
searchProvider = { WebToolProviders.search(secure) },
fetchProvider = { WebToolProviders.fetch(secure) },
```

`MainActivity` passes closures rather than provider instances. Each closure reads
`web.search_backend` / `web.fetch_backend` and the matching key, so changing a
backend or key in Settings takes effect on the next turn without reopening the
conversation. A missing key returns null, and `ChatViewModel.buildTools` then
does not register that tool at all.

The chat provider has the same dynamic property through `AgentRepository`: a
turn reads `conversations.providerId`, loads the profile and its encrypted key,
and constructs the OpenAI-compatible provider then. Settings edits do not require
a new ViewModel.

### Navigation is `when` over state, not a nav library

`InhumanRoot()` holds the navigation flags and renders one branch (`MainActivity.kt:171-255`):

| State | Screen |
| --- | --- |
| `showSettings` | `SettingsScreen` |
| `showAgents` | `AgentsScreen` |
| `showFiles && filesWorkspaceId != null` | `FilesScreen` for that workspace |
| `showFiles && openConversationId != null` | `FilesScreen` for that conversation |
| `openConversationId != null` | `ChatScreen` |
| `openWorkspaceId != null` | `WorkspaceDetailScreen` |
| `showWorkspaces` | `WorkspacesScreen` |
| otherwise | `ConversationsScreen` (the list) |

Two details that are easy to break:

- **The flags are `rememberSaveable`, not `remember`.** The Activity declares no
  `android:configChanges`, so rotation destroys and recreates it. Plain
  `remember` does not survive that — a rotation while reading a reply used to
  drop the user back to the list while the turn kept running and billing with
  nothing on screen to show it (`MainActivity.kt:91-98`).
- **Dialog flags and the file browser's depth are deliberately *not* saved.** A
  dialog is a question being asked right now; restoring a screen into "a dialog
  is open" would leave Back pointing at something that is not there
  (`MainActivity.kt:110-123`).

Back is handled by a **single** `BackHandler` at the root, registered once, with
an explicit ordering: dialog → directory level → settings/agents → conversation
→ workspace detail → workspace list. A conversation opened inside a workspace
records its origin, so Back returns to the workspace rather than the home list.
Registering one handler per screen would fire several on a single press and jump
two or three levels at once.

### Process lifetime

A streaming turn reports a `TurnStatus` from `ChatViewModel` through the
`onTurnStatus` callback at wiring time. `MainActivity` passes it to
`TurnNotifications`, which owns a map keyed by conversation id; the view model
also registers a per-conversation stop callback there. `TurnForegroundService`
only renders that map. With one turn its notification is the foreground
notification; with several, a summary notification is foreground and each turn
gets a child with its own Open/Stop. This is what makes two simultaneous
conversations safe — the old singleton id and single stop handler could overwrite
or stop the wrong turn.

`TurnStatus` carries the current phase (Thinking, Using a tool, Waiting for
approval/consent, Writing reply), the conversation title and the start time.
`awaitingApproval` adds a **Review** action to the child (and to the summary when
any child is waiting); it opens the conversation, not an approval decision.
The service uses a chronometer and updates on phase changes, not on every token.
The progress channel is low-importance and silent. A finish does not post a
completion while the app is in the foreground; in the background it posts a
separate `turn_complete` notification with a plain-text preview, private lock
screen version, and tap-to-open. The service stops on the final `false` and in
`ChatViewModel.onCleared`; denying `POST_NOTIFICATIONS` hides the notices but
does not stop the service.

A pending backup restore is applied in `InhumanApp.onCreate`, before any
repository opens Room, because replacing a live database under an open connection
is not safe. Navigation and repositories then start against the restored file
normally.

### Start-up sweep

One `LaunchedEffect` at the root sweeps workspace directories that no
**workspace row** refers to, off the main thread and after the first frame
(`MainActivity.kt:83-87`). A permanent workspace with no conversations is kept;
a temporary workspace whose owner conversation is gone is removed. Deleting a
conversation removes its files first and its rows second, so a crash in that
window leaves a folder of the user's files that nothing in the UI can see or
delete. Silence is the expected outcome on a healthy install, which is why
nothing is shown.

## The build toolchain

Versions live in `gradle/libs.versions.toml`; the toolchain itself is pinned to
this machine (`README.md:100-107`):

```
JDK        17          C:\android-toolchain\jdk17
Gradle     9.8.0       C:\android-toolchain\gradle-9.8.0
AGP        9.4.1
Kotlin     2.4.20      (built in to AGP)
KSP        2.3.12
compileSdk 37   targetSdk 36   minSdk 34
```

Four traps, all of which already cost someone time and are documented in
`README.md:109-186`:

1. **AGP 9 has built-in Kotlin and `kotlin-android` is incompatible with it.**
   Applying it fails with "Cannot add extension with name 'kotlin'". There is
   therefore no `kotlin-android` entry in `libs.versions.toml` and no alias for
   it anywhere. `kapt` is incompatible for the same reason — this project uses
   KSP. Compiler options live under a top-level `kotlin { compilerOptions {} }`,
   not the old `android { kotlinOptions {} }`, and `jvmTarget` must **not** be
   set there because it defaults to `android.compileOptions.targetCompatibility`
   and setting both is how the two drift apart (`app/build.gradle:41-52`).
2. **Compose needs `compileSdk = 37`.** Current AndroidX declares `minCompileSdk`
   37 in its AAR metadata; `platforms;android-37.0` is not part of a default SDK
   install. `targetSdk` is 36 on purpose — compiling against the newest API and
   targeting a tested runtime are different decisions.
3. **The Gradle wrapper points at a local `file:` distribution on purpose.** The
   JVM cannot reach `services.gradle.org` through this machine's proxy, and
   `gradle wrapper` validates the URL by fetching it. Regenerating it requires
   `tar -a -c -f`, **not** `Compress-Archive`, which omits directory entries and
   makes the wrapper's unzip die on the first file
   (`README.md:155-179`).
4. **`xml` comments in `AndroidManifest.xml` cannot contain `--`.** The manifest
   merger parses strictly and fails with a bare "Error parsing
   AndroidManifest.xml" and no line number.

`gradle.properties` holds two settings worth knowing: `org.gradle.jvmargs` is
`-Xmx4096m` because the Compose compiler plus KSP fall over at 2g on a cold
build, and `android.defaults.buildfeatures.compose=false` makes Compose opt-in
per module so the non-UI modules do not pay for it.

**On a machine that is not this one**, the wrapper's `file:` URL points at
nothing and three prerequisites (JDK 17, Android platform 37, the Gradle
distribution) live outside git. `tools/setup-machine.ps1` checks all three,
repairs the wrapper when its target is missing, and exits non-zero with the
remediation line for whatever is absent — so the first build is explained before
it is attempted. `-NoRepair` only reports.

Gradle build scripts in this repository are **Groovy** (`build.gradle`, not
`.gradle.kts`) — `app/build.gradle`, `settings.gradle`, `build.gradle`. Only
the `_research/rikkahub` reference material is Kotlin DSL.

## Verify before calling something done

```powershell
$env:JAVA_HOME = 'C:\android-toolchain\jdk17'
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

`--rerun-tasks` matters. With the build cache warm, a plain run can report
`BUILD SUCCESSFUL` without compiling the file you just changed. Check that the
warning output is empty as well as the error output: this tree compiles clean
and that is worth keeping (`AGENTS.md`).

Two tests are skipped by design: `DeepSeekWebLiveTest` talks to the real Search
and Fetch APIs and needs a key, and `SshLiveProbeTest` needs real SSH credentials.
Everything else runs on the JVM — including the file tests, which use a temp
directory because `:core:files` needs real filesystem semantics, and the desktop
smoke tests, which run `core:ai`/`core:files` on a plain JVM.

## Localization

UI text lives in `app/src/main/res/values/strings.xml`, with Simplified Chinese in
`values-zh-rCN/strings.xml`. Both are declared in `res/xml/locales_config.xml`
and named by `android:localeConfig` in the manifest. The product's default is
**Chinese**: `InhumanApp.ensureDefaultLanguage()` sets the system per-app locale
to `zh-CN` when none has been chosen, before the first activity composes. The
resource default stays English, so an English choice (Settings → **11 /
Language**, or the system per-app language screen) uses the unqualified
resources; the platform stores that choice, and the application never overrides
a non-empty one. There is no second language key in `SecureStore`.

Two rules:

- **User-facing text belongs in resources.** A literal in a composable cannot be
  translated at all, and the parity test below cannot see it. Every screen is
  converted, and so is text a view model produces: `ChatViewModel`,
  `SettingsViewModel`, `FilesViewModel` and `WorkspaceDetailViewModel` take an
  application `Context` and resolve their status and error strings with
  `getString`. Notification channels and actions in `TurnNotifications` /
  `TurnForegroundService` do the same.
- **Model-facing text stays English.** Tool descriptions, tool results, system
  prompts and the demo provider's script are prompt text, not UI. Translating
  them would change what the model is told and make two transcripts harder to
  compare. The rule covers `core:ai` and `core:files`.
- **Provider and library detail stays as it arrives.** Where an error is the
  provider's own message (`failure.message`) or a file-transfer reason from
  `:core:files`, the localized sentence carries the original text as a `%1$s`
  argument rather than pretending to translate it. The same applies to product
  names and wire field names (`DeepSeek`, `Exa`, `reasoning_effort`).
- **Chinese is written, not translated word for word.** A literal sentence from
  the English reads like a manual: `该`, `其`, `进行`, `被……` and `线路` are the
  usual symptoms. Prefer short active sentences, Chinese punctuation, and the
  vocabulary already used on screen: 对话 (conversation), 回合 (turn), 工作区
  (workspace), 快照 (snapshot), 原始 JSON (wire JSON), 压缩上下文 (compaction).
  `token`, `SSH`, `JSON`, `Markdown` and model/provider names stay as they are.
  The placeholder test below exists because rewording a sentence is exactly
  where a `%1$s` gets lost.

`StringResourceParityTest` fails when a key exists in one file and not the other,
and when a string's placeholders (`%1$s`, `%2$d`) differ between the two. Android
falls back to the default resource silently, so a missing key is invisible in
English and mixed-language in Chinese; a missing placeholder renders as literal
text or the wrong argument. Lint's `MissingTranslation` would catch the key, but
lint is not part of the offline verification loop, and this check is a two-file
comparison that runs on the JVM.

## Running it on a device

```powershell
emulator -avd dsh_tablet        # 2560x1600 @ 320dpi -> 1280dp, Expanded
emulator -avd inhuman_phone     # phone form factor, Compact
.\tools\build-and-shot.ps1      # build, install, launch, screenshot
```

`build-and-shot.ps1` writes PNGs to `.shots/` at full size plus a scaled copy,
and prints the **mean brightness** of the frame: `screencap` reports success
even on an all-black framebuffer, so a sleeping or locked device would otherwise
pass silently (`tools/build-and-shot.ps1:113-141`).

Both screen sizes must actually be run — see `AGENTS.md` and
[06-agent-loop-ui.md](06-agent-loop-ui.md). The phone case is not a smaller copy
of the tablet case.

### The `tools/` scripts

Most are PowerShell; `dev-server.py` is standard-library Python. The device
scripts drive `adb` and read the UI rather than guessing coordinates; the two
update/log scripts build an APK or serve it.

| Script | What it does |
| --- | --- |
| `build-and-shot.ps1` | The whole loop: build, install, launch, screenshot with brightness check. `-Serial`, `-Label`, `-SkipBuild`. |
| `ci.ps1` | The verification gate a CI host calls: runs `setup-machine.ps1`, then `:app:assembleDebug` plus every JVM test with `--rerun-tasks`, and fails on any compiler warning. `-SkipDesktop`, `-LogPath`. |
| `start-server.cmd` | Double-click launcher for `dev-server.py` on port 8787; prints the LAN/Tailscale URLs and keeps the console open. Closing it stops the server. |
| `release-apk.ps1` | Builds `:app:assembleRelease`, verifies the APK with `apksigner`, computes SHA-256 and writes `dist/update.json` plus the APK for the in-app updater. |
| `dev-server.py` | Standard-library Python server for a LAN/Tailscale link: serves `dist/` at `/update.json` and the APK, and accepts `POST /log` into `logs/`. No authentication; bind only on a trusted network. |
| `device-flow.ps1` | End-to-end on a real device: clear app data, enter the API key from `C:\android-toolchain\.deepseek-key`, create a file through the agent, then ask it to delete one. It reports whether the approval dialog appeared — by default it will not, since deletion asks nothing — and dumps the transcript either way. |
| `verify-features.ps1`, `verify-agents.ps1`, `verify-model-picker.ps1`, `verify-regenerate-guard.ps1`, `verify-back-and-delete.ps1`, `verify-migration.ps1` | Feature verification flows, each asserting on dumped UI state. |
| `inspect-agent-state.ps1`, `measure-screen.ps1`, `measure-usage.ps1` | Read-only probes: what the UI currently says, what fits on a screen, what a turn cost. |
| `seed-and-verify-migration.ps1` | Seeds an old database and checks the migration against it. |
| `check-delete-dialog.ps1`, `check-delete-dismissal.ps1`, `repro-delete-tapthrough.ps1` | Regression checks for the conversation-delete dialog. |
| `probe-image-format.ps1` | Investigates what image formats the provider accepts. |
| `UiDump.psm1` | **Import with `Import-Module`, not dot-sourcing.** Provides `Get-UiNodes`/`Get-Attr`/`Show-State`. |

**`UiDump.psm1` exists because of a specific bug, and the bug is the lesson.**
Earlier scripts parsed `text="…"` with a double quote in the pattern. uiautomator
switches to single quotes when a value contains a double quote — exactly what the
delete confirmation's title does, because it embeds the conversation name:

```
text='Delete "Reply with exactly the word banana…"?'
```

So every earlier reading of that title returned nothing, and "the title did not
change" was reported three times on the strength of a value that was never read.
Parse the attribute, not a quote character, and when a value looks empty, check
whether it was found at all (`tools/UiDump.psm1:1-17`).

Two more `adb` facts from `AGENTS.md`: one `uiautomator dump` + `adb pull` costs
roughly 700ms round trip, and `adb shell input` is blocked outright on some OEM
builds (HyperOS refuses synthetic events without an extra developer setting), so
a physical phone may be deployable and inspectable but not drivable.

## What adb cannot verify

Screenshots and UI dumps are the right tool for layout and end states, and the
**wrong** tool for anything inside a stream. A demo reply streams for about
3.6s and a dump costs ~700ms, so a sampling loop sees two or three frames — the
intermediate states of an incremental parse are not observable at any sampling
rate the tooling allows.

When something lives inside the stream, extract the invariant into a pure
function and test that, then use the device only to confirm the surrounding path
runs and renders. `DeltaCursor` and `ContextUsage` exist for exactly this
reason, and their tests carry the argument that the device cannot
(`AGENTS.md`, [06-agent-loop-ui.md](06-agent-loop-ui.md)).
