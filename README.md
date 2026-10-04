# inhuman

An Android LLM chat client where **every conversation gets its own folder**, and
the assistant can read and write files inside that folder — and nowhere else.

The sandbox is the point. It is enforced in code, not requested in a prompt.

---

## Status

Phase 0 complete: the project builds, runs, and renders. There is no chat yet —
what exists is the skeleton, the design system, and the workspace sandbox with
its tests.

| Phase | Contents | State |
| --- | --- | --- |
| **0** | Skeleton, Compose, theme, `SessionWorkspace` + tests | **done** |
| 1 | DeepSeek adapter, streaming replies | not started |
| 2 | Room persistence, conversation list | not started |
| 3 | Markdown + code highlighting | not started |
| 4 | File tools, approval, `DocumentsProvider`, upload/download | not started |
| 5 | Agent loop, multi-turn tool calling, reasoning display | not started |

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
core/common/         small shared types
core/design/         colour, typography, theme
core/data/           Room + DataStore           (empty)
core/ai/             provider + streaming       (empty)
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
- **No LaTeX rendering.** The only viable Android library is GPLv2 and this app
  is not open source. Markdown and code highlighting are permissively licensed
  and cover the common case.
- **No `MANAGE_EXTERNAL_STORAGE`,** and no `READ/WRITE_EXTERNAL_STORAGE`. Play
  gates the former behind justifications a chat app cannot honestly make.
- **No multi-provider adapter layer.** DeepSeek only, for now. The streaming
  pipeline is transport-agnostic so a second provider is an addition rather than
  a rewrite, but no abstraction is written for a provider that does not exist.

## Licensing

RikkaHub is AGPL-3.0 and was read for architecture only. No code was copied from
it, and none from any GPL-licensed library. Dependencies are Apache-2.0 / MIT.
