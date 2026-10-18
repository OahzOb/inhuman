# inhuman

An Android LLM chat client where **every conversation gets its own folder**, and
the assistant can read and write files inside that folder — and nowhere else,
except for the opt-in `ssh_exec` tool described below.

The sandbox is the point. It is enforced in code, not requested in a prompt.

**One capability can leave the device.** `ssh_exec` is off by default, requires
the global Settings switch, must be selected explicitly on the assistant, and
asks for a separate acknowledgement in each conversation before its first call.
The app has no remote-host or credential configuration; the user supplies them to
the agent. It is a deliberate exception to the local sandbox, not a "full access"
mode.

---

## Status

Working, and installed on a phone. A conversation can be started against a
chosen assistant, replies stream with reasoning and a live token breakdown,
files can be read and written by both the model and the user, and answers can be
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
model and reasoning effort read from the provider, an approval gate that is
**configurable per tool, per assistant and per path and asks nothing by
default**, branch switching, image attachments, usage reporting, and a context
gauge above the composer.

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
| `web_search` | DeepSeek's paid Search API: one request, structured excerpts, no second key |
| `web_fetch` | DeepSeek's paid Fetch API: one page as Markdown, no second key |
| `ssh_exec` | one command on a remote machine; **off by default**, explicit assistant selection, per-conversation consent, no host config |

Every mutating tool carries a **REWIND** or **NO REWIND** badge beside its name,
in the same row as PAID and REMOTE. The name says what the tool does; the badge
says whether the turn can be undone. `ssh_exec` is the only `NO REWIND` entry.

Regenerating a reply has two forms:

- **Answer again** (the refresh icon on the newest answer) keeps the tool
  **definitions** so the request prefix can still hit DeepSeek's context cache,
  but appends a transient instruction not to call one. If the model calls anyway,
  the app refuses it before execution; nothing is written or sent over SSH.
- **Regenerate from…** can restart at the user message or after any completed
  tool group in the last turn. The prefix and its tool results stay; everything
  after the chosen point is deleted, the workspace is restored to that
  checkpoint, and the request runs **with tools**. A point is disabled if the
  discarded suffix contains an irreversible tool.

The last user message has an **Edit** action. It rewinds the conversation to
before that message, removes the message and everything after it, and restores
the text and attachments to the composer. If the turn wrote local files, the
pre-turn start checkpoint is restored **before** any rows are deleted. A turn's
changed files are listed under its answer (`+`/`~`/`-` paths) and repeated in
the confirmation. A turn that ran SSH is **not** offered for rollback.

Checkpoints are content-addressed per turn and per completed tool group; the
chat menu shows how many turns and bytes are stored and lets you keep 1, 3, 5 or
10 turns. Rewinding one turn deletes only that turn's checkpoints, leaving older
ones intact.

**Search and fetch are paid, and that is why they are separate tools.** Both
call DeepSeek's own APIs directly (`/v1/tools/search`, `/v1/tools/fetch`),
billed by DeepSeek at ¥5 per 1,000 successful calls on the account the
conversation already uses. A search is one request, not a model turn, so what
comes back is a structured list rather than the model's summary of one; fetch is
how the model reads a page that list pointed at. Failed and empty requests are
not charged, and both tools are labelled **paid** wherever a tool is chosen. No
second vendor and no second key: the credential already configured for chatting
is the one they use.

**`ssh_exec` is the one tool that leaves the device.** It is off in Settings by
default, must be selected explicitly on an assistant (the historical "all tools"
selection does not include it), and shows a dedicated warning the first time a
conversation uses it. After that it follows the normal approval policy, which
defaults to running without asking. Remote commands are not a workspace copy:
some damage cannot be undone. The app has no host list and no credential store;
it provides the transport, and the user gives the agent the host, user and
password/key file. SSH tool arguments are redacted before the tool-call row is
stored, but text typed into the conversation is still stored verbatim. Standard
host-key checking is TOFU with `SHA256:` fingerprints, fail-closed on a changed
key. Details are in [docs/03-ai-provider.md](docs/03-ai-provider.md).

Search results are excerpts, not pages. The links are cleaned of campaign
parameters before the model sees them, because it cites them back to the user;
fetch returns Markdown and truncates very long pages with a visible marker
rather than silently cutting them off.

### The context gauge, and what is not there

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

**There is still no compaction, and no trimming.** The whole active branch is
sent every turn (`ChatViewModel.streamOneTurn`), `ChatRequest.maxTokens` is
never set, and nothing is ever dropped. So when a conversation does reach the
ceiling, the API rejects it with a 400, the app shows that error once, and the
turn is not recorded — which means the next message rebuilds the same oversized
history and fails the same way. The conversation is stuck, and the only exit is
a new one.

The gauge is the honest minimum: it makes the wall visible before it is hit. It
does not remove the wall. Trimming and summarising are both real design
decisions — one silently changes what the model was told, the other costs a
model call and loses the original text — and neither is made by default here.

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

### 4. `xml` comments in an `AndroidManifest.xml` cannot contain `--`

The manifest merger parses manifests strictly. A prose comment containing a
double hyphen (which is easy to write when explaining a flag like
`MANAGE_EXTERNAL_STORAGE`) fails the build with a bare
`Error parsing AndroidManifest.xml` and no line number.

---

## Layout

```
app/                 UI, theme application, entry point
core/common/         platform-free shared code (the diagnostics facade)
core/design/         colour, typography, theme
core/data/           Room, Keystore-backed secrets, DocumentsProvider
core/ai/             provider + streaming       (no Compose, no Activity)
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
.\gradlew.bat :core:files:testDebugUnitTest
```

16 tests cover traversal (including backslash traversal), null bytes,
absolute-looking paths, Windows drive paths, symlinks pointing both outside
*and* inside the root, and the accounting used for quotas.

---

## Building

```powershell
$env:JAVA_HOME = 'C:\android-toolchain\jdk17'
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :core:files:testDebugUnitTest
```

Or the whole loop — build, install to the emulator, launch, screenshot:

```powershell
.\tools\build-and-shot.ps1
```

Screenshots land in `.shots/` at 2560x1600, with a 900px copy beside them and a
mean-brightness figure, so an all-black frame (sleeping or locked device) can
never pass silently.

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

## Deliberate non-goals

- **No Linux/PRoot environment.** The requirement is files in and out of a
  per-conversation directory, which needs no shell. Skipping it also removes a
  real security problem: PRoot runs at the app's own UID, so "sandboxed" Linux
  code could read the conversation database and the stored API keys.
- **No LaTeX rendering.** Still true, but **not for the reason this file used to
  give.** It claimed the only viable Android library is GPLv2 and this app is
  not open source — that was wrong twice over: the app is going open source
  (`LICENSE`), and JLaTeXMath carries a linking exception whose own text grants
  permission to *"link this library with independent modules … regardless of the
  license terms of these independent modules"*. So the licence was never the
  obstacle. The real costs are the ones nobody had priced: its Greek fonts are
  GPLv2, several others are OFL or the Knuth Licence, and shipping any of them
  means maintaining a `THIRD-PARTY-NOTICES` file. Markdown and code highlighting
  remain permissively licensed and cover the common case.
- **No `MANAGE_EXTERNAL_STORAGE`,** and no `READ/WRITE_EXTERNAL_STORAGE`. Play
  gates the former behind justifications a chat app cannot honestly make. The
  user reaches a conversation's directory through the app's own `DocumentsProvider`
  and its import/export, which needs no such permission.
- **No multi-provider adapter layer.** DeepSeek only, for now. The streaming
  pipeline is transport-agnostic so a second provider is an addition rather than
  a rewrite, but no abstraction is written for a provider that does not exist.

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
