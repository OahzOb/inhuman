# inhuman

An Android app where **every conversation works inside a workspace**, and the
assistant can read and write files inside that workspace — and nowhere else,
except for the opt-in SSH tools described below.

> For a **technology-neutral** description of the product — design principles,
> current features and future plans — see
> [docs/13-product.md](docs/13-product.md). It deliberately names no stack,
> framework or implementation detail.

A workspace is **temporary** by default: it is created with its first
conversation and deleted with it. **Save as permanent** (or creating a workspace
from the Workspaces screen) keeps the files after conversations are deleted and
lets several conversations share them. The model still sees only sandbox-relative
paths; the absolute location is a user-facing detail.

The sandbox is the point. It is enforced in code, not requested in a prompt.

**One capability can leave the device.** The SSH tools (`ssh_probe`,
`ssh_list`, `ssh_read_file`, `ssh_exec`, `ssh_upload`, `ssh_write_file`,
`ssh_download`) are off by default, require the global Settings switch, must be
selected explicitly on the assistant, and ask for a separate acknowledgement in
each conversation before the first call. They can inspect a host, read and write
small files, move files, and run commands only if `ssh_exec` is selected; the app
has no remote-host or credential configuration, and the user supplies those to
the agent. This is a deliberate exception to the local sandbox, not a "full
access" mode.

---

## Status

Working, and installed on a phone. A conversation can be started against a
chosen assistant, replies stream with reasoning and a live token breakdown,
files can be read and written by both the model and the user, workspaces can be
temporary or permanent and shared by several conversations, and answers can be
regenerated and compared.

| Phase | Contents | State |
| --- | --- | --- |
| **0** | Skeleton, Compose, theme, `SessionWorkspace` + tests | **done** |
| **1** | DeepSeek adapter, streaming replies | **done** |
| **2** | Room persistence, conversation list | **done** |
| **3** | Markdown + code highlighting | **done** |
| **4** | File tools, approval, `DocumentsProvider`, upload/download | **done** |
| **5** | Agent loop, multi-turn tool calling, reasoning display | **done** |

Beyond the phases: assistants with visible system prompts, per-conversation
provider switching and model/reasoning effort read from the provider, **native
Anthropic Messages and Google Gemini adapters alongside DeepSeek and
OpenAI-compatible profiles**, an approval gate that is
**configurable per tool, per assistant and per path and asks nothing by
default**, branch switching, image attachments (picked or photographed), scanned
PDF pages read by the conversation's vision model (an opt-in paid tool), usage
reporting, a context
gauge above the composer, a **Usage** section that totals provider-reported
tokens and paid tool calls by day and conversation without converting prices,
**plain-text export** of one conversation to Markdown (optionally with tool
calls and results) and of a workspace to zip, per-tool media backends
(assistant, a configured Qwen Token Plan provider and model, or a separate
Qwen-compatible API and key), temporary/permanent workspaces
with the absolute
location available to the user, global title/message search with
jump-to-message, actionable error recovery, encrypted backup import preview and
one-step undo, per-conversation progress notifications with a grouped summary,
Stop/Stop all and an approval **Review** action, a readable wire inspector for
the exact system message, tool schemas, messages and raw tool calls (including
final replies and reasoning), with long text collapsed, an in-app text editor,
Open with… support so another app can edit and save a workspace file, and
multi-select deletion for conversations (with select all) and permanent
workspaces. The app
starts in Chinese; Settings → **11 / Language** switches to English, as does
the system's per-app language screen. The layout
follows the window's width **and** height, with an Auto/Compact/Comfortable
override; Settings is folded by concern; tool rounds and total tool calls have
separate global budgets; and every SSH tool is absent from every picker while its
global switch is off.

**Demo mode exercises the tool loop, not just the stream.** With file tools
switched on it creates a file and then deletes it, so the tool loop, the
results and the closing summary can be demonstrated — and regression-tested —
with no API key, no network and no bill. That matters more than it sounds: the
tool loop is the app's most consequential machinery, and it used to be the one
thing that could not
be reached without an account.

### What the agent can do

| Tool | Notes |
| --- | --- |
| `list_files` `read_file` `file_info` | read-only, safe to replay |
| `write_file` `append_file` `create_directory` `move_file` | change the workspace |
| `delete_file` | deletes from the folder; asks first only if a rule says so |
| `read_time` | the clock, in the device zone and UTC, plus date arithmetic |
| `ssh_probe` | one fixed read-only host snapshot; no command argument and no sudo |
| `ssh_list` | list one remote directory over SFTP; read-only, no shell |
| `ssh_read_file` | read a remote text file, head or tail; 64 KiB, binary refused |
| `web_search` | paid search through DeepSeek or Exa; own key, independent of the chat provider |
| `web_fetch` | paid fetch through DeepSeek or Exa; own key, independent of the chat provider |
| `bili_search` | anonymous Bilibili search over videos, column articles, uploaders or shows, with optional length/date/partition filters; **off by default**, opt-in, no account and no key |
| `bili_video` | read a Bilibili video's public title, description, parts, stats, subtitle hint and (on request) related videos |
| `bili_comments` | read one page of comments on a Bilibili video or column, or one comment's reply thread |
| `bili_article` | read a Bilibili column (cv id) as plain text |
| `bili_up` | read an uploader's profile, recent videos, collections and recent posts |
| `bili_trending` | read Bilibili's ranking, popular feed or hot search phrases |
| `bili_danmaku` | read a sample of a video's danmaku, with the times they appeared |
| `bili_bangumi` | read a show's season details and episode list (ss/ep id) |
| `bili_live` | read a live room's state by room id or uploader id |
| `bili_transcript` | read a video's timestamped subtitles, or Bilibili's AI summary when it has none; **needs the optional Bilibili login**, `optIn` |
| `ssh_exec` | one command on a remote machine; **off by default**, explicit assistant selection, per-conversation consent, optional one saved host |
| `ssh_upload` | copy a workspace file to the remote machine over SFTP; **NO REWIND**, default refuses to overwrite, can create parents |
| `ssh_write_file` | write a small remote text file over SFTP; **NO REWIND**, no heredoc |
| `ssh_download` | copy a remote file into the workspace over SFTP; **REWIND**, default refuses to overwrite |

Every mutating tool carries a **REWIND** or **NO REWIND** badge beside its name,
in the same row as PAID and REMOTE. The name says what the tool does; the badge
says whether the turn can be undone. `ssh_exec` and `ssh_upload` are the
`NO REWIND` entries.

**The nine anonymous `bili_*` tools are an app-proxied read, not a capability
the assistant holds.** They are anonymous — no Bilibili account, no key, nothing
stored — and the app makes the request while the assistant still has no network
and gets no URL argument: a BV/cv/ss/ep id, a mid, a room id or a keyword only.
They are off by default behind a Settings switch, must be selected explicitly on
an assistant, and are absent from every picker, request and `unavailable_tools`
while off. Bilibili's public web interface is unofficial: failures are returned
as mapped errors (risk control, rate limit, changed signature) rather than as
empty pages, and everything read back is untrusted third-party text. One result
is explicitly partial: `bili_danmaku` is a sample of the windows it read, and
says so.

**An optional read-only Bilibili login adds `bili_transcript`.** Settings →
02 / Web & media links an account by QR (Bilibili's own app authenticates it; the
app has no webview or password field) and stores only the two cookies the read
tools need — **not `bili_jct`**, the write token. Unlinking is one tap; while no
account is linked the transcript row is absent. The login also enables comment
pages after the first. It never reads history, favourites, followings or
messages, and posts nothing; while linked, Bilibili does see the reads as your
account. The read tools are documented in
[03-ai-provider.md](docs/03-ai-provider.md).

**A conversation can edit its own tool selection.** Chat overflow → **Conversation
tools** lists the assistant's tools plus the ones this conversation already has;
checking and applying takes effect on the next turn. The assistant is the ceiling,
so a tool the assistant never had cannot be invented there, and SSH tools remain
opt-in and still use the per-conversation consent. This is not a new approval
layer: it changes *which* tools exist, not whether a call asks.

**Rewind** is the single restart control on the newest answer. It opens a picker
of restart points:

- **Answer again** keeps the tool **definitions** so the request prefix can still
  hit the provider's context cache, but appends a transient instruction not to
  call one. If the model calls anyway, the app refuses it before execution;
  nothing is written or sent over SSH.
- **From your message** or **After tool group N** restarts at that completed
  boundary. The prefix and its tool results stay; everything after the chosen
  point is deleted, the workspace is restored to that checkpoint, and the request
  runs **with tools**. A point is disabled if the discarded suffix contains an
  irreversible tool.

The picker's **Edit your message** choice rewinds the conversation to before the
last user message, removes the message and everything after it, and restores
the text and attachments to the composer. If the turn wrote local files, the
pre-turn start checkpoint is restored **before** any rows are deleted. A turn's
changed files are listed under its answer (`+`/`~`/`-` paths) and repeated in
the confirmation. A turn that ran `ssh_exec` or `ssh_upload` is **not** offered
for rollback.

Checkpoints are content-addressed per turn and per completed tool group; the
chat menu shows how many turns and bytes are stored and lets you keep 1, 3, 5 or
10 turns. Rewinding one turn deletes only that turn's checkpoints, leaving older
ones intact.

**Search and fetch are paid, and their credentials are independent.** Choose
DeepSeek or Exa separately for search and for fetch in Settings. DeepSeek calls
`/v1/tools/search` and `/v1/tools/fetch`; Exa calls `/search` and `/contents`.
Either way the key is a **web-tool key**, not the chat provider's key, so a
conversation can chat on one service and search on another. A search is one
request, not a model turn, so what comes back is a structured list rather than the
model's summary of one; fetch is how the model reads a page that list pointed at.
Failures and empty results are not charged by DeepSeek; Exa bills under its own
plan. Both tools are labelled **paid** wherever a tool is chosen.

**The SSH tools are the only tools that leave the device.** They are off in
Settings by default; while off they are absent from every tool picker, approval
table and the home tool summary, and are not sent in tool schemas. They must be
enabled globally and selected explicitly on an assistant (the historical "all
tools" selection does not include them), and show a dedicated warning the first
time a conversation uses one. The warning text follows the selection: a
probe/list/read/download profile is told it cannot run arbitrary commands or write
files. After that they follow the normal approval policy, which defaults to
running without asking. Remote commands and replaced remote files are not a
workspace copy: some damage cannot be undone. The app can optionally save **one**
remote host (host/user/password or key) in the platform credential store for
convenience, not as a security or privacy promise; there is no host list,
allowlist or fleet manager. It provides the transport, and the user
gives the agent the host, user and password/key file. SSH credential arguments
are redacted before
the tool-call row is stored, but command text, paths and `ssh_write_file` content
remain in the transcript; text typed into the conversation is still stored
verbatim. Standard host-key checking is TOFU with `SHA256:` fingerprints,
fail-closed on a changed key. `ssh_probe` runs one fixed read-only snapshot;
`ssh_list` lists one directory over SFTP (no recursion, 500 entries);
`ssh_read_file` reads one text file's head or last N lines (64 KiB, binary
refused); `ssh_write_file` writes up to 64 KiB of text; `ssh_upload` writes one
workspace file over SFTP (1 MiB read limit); and `ssh_download` saves one remote
file into the workspace (4 MiB write limit). Upload, write and download refuse to
overwrite unless asked. Details are in
[docs/03-ai-provider.md](docs/03-ai-provider.md).

**Model-written code runs in the platform's JavaScript sandbox, or not at all.**
`run_javascript` executes a short function body in an isolated V8 process with a
16 MiB heap and a 10-second limit; `fetch`, `XMLHttpRequest`, `WebSocket`,
`require`, `process` and the filesystem are absent. A device probe checks those
globals and the heap before the tool is offered; if it fails, the tool is absent
rather than present with a warning. It returns one string and has no workspace,
network or host callback.

**SSH limits are user ceilings.** Settings → SSH limits has four values:
command timeout, output per stream, directory rows and text read. The model can
pass a smaller value to `ssh_exec`/`ssh_list`/`ssh_read_file`, never a larger
one; omitting the argument uses the app default, not the ceiling. A request above
the ceiling is clamped and the result starts with `limits: ...`.

Search results are excerpts, not pages. DeepSeek search cleans campaign
parameters from links before the model sees them, because it cites them back;
Exa results are used as returned. Fetch returns readable text/Markdown and
truncates very long pages with a visible marker rather than silently cutting them
off.

### The context gauge, and compaction

A ring sits above the composer showing how full the model's context window is;
tapping it gives the percentage, the exact tokens used and the window size.
The figure is the `promptTokens` the provider reported for the last request —
not a count of visible text, because the prompt is the system prompt, the tool
schemas, the conversation and every tool result, and the schemas alone are a few
thousand tokens that no amount of scrolling would reveal. It is therefore one
turn behind, and the panel says so.

Two cases are drawn as what they are rather than filled in: a provider that
reports no window gets no percentage at all, and a window with nothing measured
yet gets an empty ring. `deepseek-flash` and `deepseek-v4-pro` both report
**1,048,576 tokens**, so this is a long way off in an ordinary conversation —
but a turn that reads two large files or searches eight times can move it, and
before the gauge existed there was no way to see that happening.

**Compaction is explicit and reversible.** Opening the gauge exposes **Compact
conversation…**. It keeps the last two turns verbatim, asks the model for a
summary of the older complete turns, and sends that summary with the recent
turns on later requests. Every original message stays in the transcript; a
**CONTEXT COMPACTED** banner and the gauge's **View summary / Use full history**
controls make the projection visible and undoable. **Compact again** keeps one
recent turn verbatim, for the case where the recent turns are themselves large.

The summary call costs one extra request on a real provider; demo mode uses a
local deterministic summary instead. Tool groups are never split: the boundary
is always a turn boundary. API keys are not part of the summary, and a summary
whose boundary is no longer on the active branch is ignored rather than applied
to the wrong prefix.

This removes the old permanent stuck state: reaching the context ceiling is
still possible if a single recent turn is larger than the window, but the older
history can now be projected down without losing it.

### Role-play scenes

A conversation can be a story. An assistant may carry a **character card** —
description, personality and voice, default scenario, greeting, example
dialogue, boundaries, an author note, and optional temperature / top-p /
reply-token limits — and the home screen's **New role-play scene** flow picks
such a character and records the scene's world, premise, player persona,
narration format and notes on the conversation. Nothing here is a new
capability: the card and the scene are text in the system message, the greeting
is stored as the character's first assistant message (with a wire-only start
line so a history never begins with the assistant), and a scene is created with
an explicit empty tool selection that the conversation tool sheet can change
later. A character card can stand alone — the assistant prompt is optional.

A scene runs as a continuous session by default, and its rolling brief uses
story-state sections (story so far, characters and relationships, world
details, unresolved threads, continuity, player character) instead of the task
brief. Scenes do not share memory with each other: a card is reusable, a story's
memory is not. `/ooc` at the start of a line tells the assistant, out of
character, rather than the character. Multi-character scenes, world books and
card import are not implemented. Current behaviour and its invariants are in
[06-agent-loop-ui.md](docs/06-agent-loop-ui.md) and
[07-gotchas.md](docs/07-gotchas.md) A26.

---

## The toolchain, and the four things that will bite you

Everything below was learned by hitting it. The versions are not arbitrary.

```
JDK      17          C:\android-toolchain\jdk17
Gradle   9.8.0       C:\android-toolchain\gradle-9.8.0
AGP      9.4.1
Kotlin   2.4.20      (built-in to AGP -- see below)
KSP      2.3.12
compileSdk 37        targetSdk 36      minSdk 34
```

### 1. AGP 9 has built-in Kotlin, and `kotlin-android` is not compatible with it

Applying `org.jetbrains.kotlin.android` alongside AGP 9 fails outright:

```
Failed to apply plugin 'org.jetbrains.kotlin.android'
> Cannot add extension with name 'kotlin', as there is an extension already registered with that name
```

AGP 9 compiles Kotlin itself. So there is deliberately **no** `kotlin-android`
entry in `gradle/libs.versions.toml`, and no `alias(libs.plugins.kotlin.android)`
anywhere. Two consequences that are easy to miss:

- **`kotlin-kapt` is incompatible too.** This project uses KSP, which is the
  migration path the AGP docs recommend. If kapt were ever needed it would have
  to be `com.android.legacy-kapt`.
- **Compiler options moved.** The old `android { kotlinOptions { } }` block does
  not exist under built-in Kotlin; options go in a top-level
  `kotlin { compilerOptions { } }`. `jvmTarget` should *not* be set there — it
  defaults to `android.compileOptions.targetCompatibility`, and setting both is
  how the two drift apart.

The AGP docs do offer `android.builtInKotlin=false` plus `android.newDsl=false`
to opt out. This project does not use them: AGP 10 removes the opt-out, so
starting there would be starting in debt.

### 2. Compose now needs `compileSdk = 37`

Current AndroidX (Compose 1.12.1, `navigation-compose` 2.10.2, `core` 1.19.1,
even `okhttp-android` 5.5.0) declares `minCompileSdk` 37 in its AAR metadata.
With `compileSdk = 36` the build fails with 17 of these:

```
Dependency 'androidx.navigation:navigation-compose-android:2.10.2' requires
libraries and applications that depend on it to compile against version 37 or
later of the Android APIs.
```

`platforms;android-37.0` must be installed — it is not part of a default SDK
install, and the local SDK had only 34 and 36. AGP 9.4 supports up to API 37, so
this is fine; on AGP 9.0 (max 36.1) it would not be.

`targetSdk` is 36, deliberately lower than `compileSdk`. Compiling against the
newest API and *targeting* a version whose runtime behaviour has been tested are
different decisions, and the tablet this targets runs API 35.

### 3. The Gradle wrapper points at a local distribution, on purpose

`distributionUrl` is a `file:` URL into `C:\android-toolchain`, not
`services.gradle.org`. Two separate reasons:

- **`gradle wrapper` refuses the official URL.** The task validates the
  distribution URL by fetching it, and the JVM cannot reach
  `services.gradle.org` through this machine's proxy — PowerShell can, the JVM
  cannot. It fails with `Test of distribution url … failed` and writes nothing.
- **A wrapper that needs the network is a wrapper that breaks.** This machine
  reaches Maven Central and `dl.google.com` fine, but the Gradle distribution
  host has already failed once here.

Regenerating it, if the version ever moves:

```powershell
# Pack with bsdtar, NOT Compress-Archive.
tar -a -c -f C:\android-toolchain\gradle-9.8.0-bin-repacked.zip gradle-9.8.0
.\gradlew.bat wrapper --gradle-distribution-url 'file:/C:/android-toolchain/gradle-9.8.0-bin-repacked.zip'
```

**`Compress-Archive` will not work.** It omits directory entries, and the
wrapper's unzip then dies on the first file with
`Could not unzip … (The system cannot find the path specified)`. bsdtar writes
them. This cost an hour the first time.

None of that travels with the repository: `tools/setup-machine.ps1` puts the
official URL back on a machine that does not have this file. See
[Another machine](#another-machine).

### 4. `xml` comments in an `AndroidManifest.xml` cannot contain `--`

The manifest merger parses manifests strictly. A prose comment containing a
double hyphen (which is easy to write when explaining a flag like
`MANAGE_EXTERNAL_STORAGE`) fails the build with a bare
`Error parsing AndroidManifest.xml` and no line number.

---

## Layout

```
app/                 Android UI, theme application, entry point
core/common/         platform-free shared code (the diagnostics facade)
core/design/         colour, typography, theme (Android)
core/data/           Android adapters: Keystore, Database.open, backup, DocumentsProvider
core/db/             Room schema + platform-free repositories
core/engine/         shared turn loop
core/ai/             provider + streaming + tools       (no Compose, no Activity)
core/files/          the workspace sandbox
```

The rule the split exists to enforce: **a module with no Android UI must not
depend on one.** `core/ai` in particular knows nothing about Compose, which is
what keeps provider code testable without a device.

---

## The sandbox

`core/files/.../SessionWorkspace.kt` is the whole security story, and it is
deliberately the first thing written rather than the last.

The contract:

> A tool never receives the workspace root and never builds a path itself. It
> hands a relative string to `resolve()`, which either returns a file provably
> inside the root or refuses.

Because the constructor is private and the root is never exposed, a tool holding
a `SessionWorkspace` has no way to construct a path that skipped the check.

Two checks, and both are load-bearing:

1. **No symlinks, ever — rejected rather than resolved.** Resolving a symlink
   would leave a time-of-check/time-of-use window open: the link could be
   swapped between the check and the `open()`. If a symlink can never exist in
   the workspace, that window does not exist.
2. **Canonical prefix check.** A second line of defence, using `canonicalFile()`
   (which resolves symlinks in the *parent* chain) rather than `normalize()`
   (which does not).

`resolve()` never throws. A malformed path is a normal thing for a model to
produce, and it must come back as a rejection the model can read and correct —
not an exception that tears down the loop. This was a real bug: a Windows
`C:/…` path threw `InvalidPathException`, and the test caught it.

`WorkspaceLimits` carries the ceilings — bytes per read and write, entries per
listing, total workspace size and file count. They exist so a model that ignores
its instructions still cannot produce unbounded damage.

Run the tests:

```powershell
.\gradlew.bat :core:files:test
```

17 tests cover traversal (including backslash traversal), null bytes,
absolute-looking paths, Windows drive paths, symlinks pointing both outside
*and* inside the root, a Windows junction escape, and the accounting used for
quotas.

---

## Building

```powershell
$env:JAVA_HOME = 'C:\android-toolchain\jdk17'
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :core:files:test
```

### Another machine

Three things a clone does not carry, because each is a property of the machine
rather than of the repository:

| What | Why it is not in git | How the build finds it |
| --- | --- | --- |
| JDK 17 | installed per machine | `JAVA_HOME` |
| Android SDK, **platform 37** | licensed download; AGP 9 needs this exact platform, not the default one | `ANDROID_HOME` or `sdk.dir` in `local.properties` (gitignored) |
| the Gradle distribution | see below | `gradle/wrapper/gradle-wrapper.properties` |

**The wrapper is the one that will bite you.** `distributionUrl` is checked in as
a `file:` URL into this machine's `C:\android-toolchain`, because the JVM here
cannot reach `services.gradle.org` through its proxy (the reasoning is in
[§3 above](#3-the-gradle-wrapper-points-at-a-local-distribution-on-purpose)). On
any other machine that file does not exist and `gradlew` dies at the first
unzip. Run this after cloning:

```powershell
.\tools\setup-machine.ps1
```

It checks all three and repairs the wrapper when its `file:` target is missing —
pointing it back at the official distribution for the same Gradle version, which
the first `gradlew` then downloads once. `-NoRepair` reports without touching
anything. It installs nothing and downloads nothing itself: two of the three
need a licence click, and a script that guesses SDK paths is a second source of
truth.

For the Android loop — build, install to the emulator, launch, screenshot:

```powershell
.\tools\build-and-shot.ps1
```

Screenshots land in `.shots/` at 2560x1600, with a 900px copy beside them and a
mean-brightness figure, so an all-black frame (sleeping or locked device) can
never pass silently.

### Release build

Release signing is local and deliberately not in the repository. Create
`keystore.properties` at the project root (and a keystore beside it, both
gitignored) with:

```properties
storeFile=keystore/inhuman-release.jks
storePassword=…
keyAlias=inhuman
keyPassword=…
```

If the keystore does not exist yet, generate it once. Back it up somewhere other
than this checkout: losing it does not mean "generate another one", it means no
future build can update an installed release.

**0.27.0 is the first release signed with this keystore.** Every earlier build —
including the published 0.26.0 APK — was signed with the Android debug key, so
it cannot update in place to 0.27.0 (and 0.27.0 cannot update it either). The
first move to the production key therefore needs, once: export a backup in
Settings → 06 / 备份, uninstall the old app, install 0.27.0, import the backup.
From 0.27.0 onward the keystore above is the signing identity; keep it, or future
releases will force the same migration again.

```powershell
$env:JAVA_HOME = 'C:\android-toolchain\jdk17'
New-Item -ItemType Directory -Force keystore | Out-Null
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v `
  -keystore keystore\inhuman-release.jks -storetype PKCS12 `
  -alias inhuman -keyalg RSA -keysize 4096 -validity 10950 `
  -dname "CN=inhuman, O=inhuman, C=CN"
```

With a PKCS12 keystore the key password must equal the store password, so
`keyPassword` repeats `storePassword`. Then:

```powershell
.\gradlew.bat :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

If the properties file is absent, the release build falls back to the debug
signing key so a fresh clone can still build; that build cannot update a
production signing identity. The current version is `versionCode`/`versionName`
in `app/build.gradle`.
`lintVital` is skipped in this offline environment (`checkReleaseBuilds =
false`); run lint separately when the network is available.

The release is minified and resource-shrunk (`minifyEnabled`/`shrinkResources`
in `app/build.gradle`). The engines that resolve classes or resources by name —
POI/XMLBeans, PDFBox, BouncyCastle/sshj, JLaTeXMath and the JavaScript sandbox —
are kept whole in `app/proguard-rules.pro`: stripping one of them would fail at
the first document or SSH call, not at build time. That cut the APK from about
72 MB to about 21 MB, most of it the unused Material icons. A new dependency
that reflects over its own classes needs a keep rule there before release.

### Local updates, log and conversation upload

The app can install an update, upload a diagnostic log, and upload one
conversation over a LAN or Tailscale link. It keeps **two independent
addresses** in **Settings → 09 / Updates and uploads**: an **update server** and
an **upload server**. The useful setup is updates from this project's GitHub
Releases (`releases/latest/download` — the canonical host plus two mirrors;
three built-in chips fill the update field) and logs/conversations to a dev
server on the LAN or Tailscale. A release feed serves updates over HTTPS only —
it has no `/log` or `/conversation` endpoint — which is exactly why the upload
address is its own field. Nothing is checked or uploaded until you tap a button,
and the conversation upload additionally has a preview/confirmation dialog.

```powershell
# Bump versionCode/versionName in app/build.gradle first: Android refuses a
# same-or-older version, and a different signing key cannot update an install.
.\tools\release-apk.ps1 -Notes "What changed"

# Start the server. This is equivalent to: python tools\dev-server.py
.\tools\start-server.cmd
```

For day-to-day use, double-click `tools\start-server.cmd` and leave its window
open; closing the window or Ctrl+C stops the server. It prints the LAN and
Tailscale URLs. To start it at logon, put a shortcut to it in
`shell:startup`.

`release-apk.ps1` builds `:app:assembleRelease`, verifies the APK with
`apksigner`, copies it to `dist/`, and writes `dist/update.json` with the
version, relative APK URL and SHA-256. `dev-server.py` serves that directory,
accepts `POST /log` into `logs/`, and accepts the explicit
`POST /conversation` into `logs/conversations/`; it prints the LAN addresses it
sees. Put the PC's LAN address (for example `http://192.168.1.50:8787`) or a
Tailscale `100.x`/MagicDNS address in the **upload server** field; the **update
server** field can stay on a built-in release feed, or point at the same server
if you want it to serve `update.json` too. Plain HTTP is accepted only for
private, LAN or Tailscale hosts; a public host must use HTTPS.

The **Upload conversation** action is separate from **Upload log**: it sends
the conversation as plain Markdown (title, messages, reasoning, attachment
paths, and tool calls/results only when the checkbox is on), with no image or
attachment bytes; note that tool results can carry file or document text, which
is why that checkbox is off by default. The upload happens only after the
preview names the server. The server has no authentication, so keep it on a
trusted network. **Export or share chat** remains local: Export writes a
document you choose, Share opens the system share sheet with a cache copy.

The install itself is still the system's: the app verifies the SHA-256, package
name, version code and signing certificate, then hands the APK to
`PackageInstaller`, which asks for confirmation. There is no silent update.
A verified download is kept in the app cache; checking again reuses it, including
after a restart, until the manifest names a different build or the installed
version catches up. All future builds must use the same key, which is why the
release keystore above matters before the first distributed APK.

The updater, log upload and conversation upload are app-level, user-initiated
features, not tools; the agent still has no network path.

### Backup and long turns

Settings → **06 / Backup** writes one AES-256-GCM encrypted file of the
database and every workspace file, using PBKDF2-HMAC-SHA256 (210,000 iterations).
The passphrase is never stored and cannot be recovered. **API keys are not in the
backup** — they are Keystore-backed on Android and not portable. Rollback
snapshots are also omitted; import clears them rather than offering a rewind to
files that are not present.

Import decrypts and validates into a staging directory while the app runs, then
asks for a restart. The swap happens in `InhumanApp.onCreate`, before Room opens,
with the current database and workspace directory renamed aside until the new
ones are in place. A failure restores the old ones.

A streaming reply starts a `dataSync` foreground service (`TurnForegroundService`)
from the `TurnStatus` reported by the chat view model, so screen-off does not let
Android kill a long model/tool turn. One notification per active conversation
shows the current phase, a chronometer and Stop; two or more are grouped under a
summary with Open and Stop all. When a tool call or SSH consent is waiting, the
turn's notification also offers **Review**, which opens the conversation rather
than approving from the lock screen. When the app is in the background, completion
uses a separate `Finished replies` channel with a private lock-screen version and
a plain-text preview; it is not posted while the app is in the foreground. On
Android 13+ the notification permission is requested on the first turn; if it
is denied the service still runs, only the notices are hidden.

### Emulator

```
emulator -avd dsh_tablet        # Android 15, x86_64, Pixel Tablet
```

Acceleration is WHPX, which required enabling `HypervisorPlatform` and
`VirtualMachinePlatform` and a reboot. Verify with
`(Get-CimInstance Win32_ComputerSystem).HypervisorPresent` — it must be `True`.
Note `emulator -accel-check` is misleading here: it names the legacy AEHD driver,
which this setup does not use.

---

## Deliberate non-goals and planned work

- **No Linux/PRoot environment on Android.** The requirement is files in and out
  of a per-conversation directory, which needs no shell. Skipping it also removes
  a real security problem: PRoot runs at the app's own UID, so "sandboxed" Linux
  code could read the conversation database and the stored API keys.
- **LaTeX rendering is implemented in the Android app.** `$...$` and `$$...$$`
  are rewritten to intercepted image links and drawn offline with the bundled
  JLaTeXMath-Android fonts; a formula is also handled while the answer streams.
  The licence was never the obstacle — the app is Apache-2.0 and JLaTeXMath
  carries a linking exception — but the fonts are: the Greek set is GPLv2,
  several others are OFL or the Knuth Licence, and all of it is recorded in
  [app/src/main/assets/THIRD-PARTY-NOTICES.md](app/src/main/assets/THIRD-PARTY-NOTICES.md)
  and readable from Settings → Diagnostics.
- **No `MANAGE_EXTERNAL_STORAGE`,** and no `READ/WRITE_EXTERNAL_STORAGE`. Play
  gates the former behind justifications a chat app cannot honestly make. The
  user reaches a conversation's directory through the app's own `DocumentsProvider`
  and its import/export, which needs no such permission.
- **The first batch of document formats is implemented.** CSV/TXT, DOCX, XLSX,
  PDF, DOC and XLS can be read, created, modified and converted by
  workspace-bound tools. DOC is written into a bundled empty Word 97 template
  (tables and formatting are flattened and reported); DOCX/XLSX preserve package
  parts the app does not understand, and every conversion reports what it could
  not carry. Android voice input is implemented: hold the microphone, release to
  transcribe into the composer, never auto-send. The decision is
  [docs/14-documents-voice.md](docs/14-documents-voice.md).
- **Native Anthropic, Gemini and OpenAI Responses adapters exist for text,
  images, tool calls and usage.** Anthropic's signed thinking block and Gemini's
  per-call `thoughtSignature` are persisted and replayed; an unsigned old history
  falls back to thinking-off instead of a 400. Responses uses typed input items
  with `store=false` and persists opaque encrypted reasoning items for replay
  when reasoning is enabled. None of the three has been verified against a real
  account here. **OAuth subscription sign-in is a generic PKCE flow** with a
  user-registered public client and the `inhuman://oauth` callback; no
  Claude/ChatGPT/Copilot/Kimi client ID is bundled, and the flow has not been
  verified against a real registration or account.
- **Remote details can be saved, but only one host, and only for convenience.**
  It is not a security or privacy promise. The host/user/password/key sit in the
  platform credential store, are absent from backups, and can be forgotten in
  Settings; the model must opt in with `useSavedHost` and explicit connection
  details still win. There is no host list, allowlist or multi-host switching.

## Licensing

This app is **open source**, under **Apache-2.0** — see `LICENSE`. The reason is
not convenience: the central claim here is that the fence around a conversation
is real, and a security claim that cannot be audited is worth very little.

Two separate facts that are easy to conflate:

- **DeepSeek Harness is MIT**, so porting its agent design was never blocked by
  licence. Its own bundled dependencies are 287 packages of MIT, MPL-2.0 and
  BSD-3-Clause, with no GPL among them.
- **Opening the source did not unlock anything that was previously forbidden.**
  JLaTeXMath's linking exception already permitted closed-source linking, as
  noted above. What opening changes is the *audit*, not the dependency menu.

RikkaHub is AGPL-3.0 and was read for architecture only. No code was copied from
it, and none from any GPL-licensed library. Dependencies are Apache-2.0 / MIT /
BSD / MPL-2.0.
