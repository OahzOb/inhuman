# Running user/agent-authored programs inside an ordinary (non-rooted) Android app

**Scope.** An Android chat app (minSdk 34 / targetSdk 36) that wants an LLM agent to write and run small programs, confined to one directory.

**Evidence policy.** Every claim carries a URL and a tag:
- **[verified]** — I read it in a primary source (AOSP source/sepolicy, official Android docs, the project's own docs/LICENSE, an upstream issue tracker, a man page, a spec).
- **[uncertain]** — secondary source, or an inference from primary sources, or something I could not read end-to-end.

Anything I could not establish is stated as such rather than guessed. Section 7 lists the real options.

---

## 1. Process execution on modern Android

### 1.1 Can an ordinary app call `Runtime.exec()` / `ProcessBuilder`?

**Yes, but only for executables that SELinux permits the app's domain to `execve()`.** There is no blanket ban on `exec()` — the ban is on *which files*.

The three things an app can actually execute:

**(a) `/system/bin/sh` and `/system/bin/toybox` — yes.** `system/sepolicy/private/app.te` (branch `main`) grants:

```
# Execute the shell or other system executables.
allow { appdomain -ephemeral_app -sdk_sandbox_all } shell_exec:file rx_file_perms;
allow { appdomain -ephemeral_app -sdk_sandbox_all } toolbox_exec:file rx_file_perms;
```
[verified] — [private/app.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app.te)

`rx_file_perms` expands to include `execute_no_trans` (see §1.3), and `private/file_contexts` maps `/system/bin/sh → shell_exec` and `/system/bin/toybox → toolbox_exec`:
```
/system/bin/toybox	--	u:object_r:toolbox_exec:s0
/system/bin/sh		--	u:object_r:shell_exec:s0
```
[verified] — [private/file_contexts](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/file_contexts)

There is no `type_transition` from `untrusted_app*` to the `shell` domain, so the shell **stays in the app's own SELinux domain and the app's UID**. It is a real POSIX shell with the app's exact privileges — no privilege gain.

*Policy-level evidence only; I did not run this on a device. Corroboration that exec-from-app-process works at all: the same sepolicy file carries `allow untrusted_app_all system_linker_exec:file execute_no_trans;` with the comment "Chrome Crashpad uses the the dynamic linker to load native executables from an APK (b/112050209, crbug.com/928422)".* **[verified for the policy; device behaviour is an inference]**

**(b) Binaries in the app's `nativeLibraryDir` — yes.** See §1.3.

**(c) Anything in the app's own data directory — NO, if `targetSdkVersion >= 29`.** See §1.2.

**Practical consequence:** you can ship *no shell at all* and still give the agent a shell. `sh -c '<script text>'` runs arbitrary shell script, and `toybox <applet> [args]` gives you the standard Android userland (`ls`, `cat`, `grep`, `sed`, `awk`, `find`, `tar`, `gzip`, …) inside your own UID. What you cannot do is *write a script file and exec it* — but `sh /path/to/script.sh` works, because `sh` **reads** the file rather than exec'ing it.

### 1.2 Executing a native binary in the app's own data directory (`/data/data/<pkg>/files/...`)

**Not allowed for `targetSdkVersion >= 29`. Still in force on the current AOSP `main` branch (Android 16/17 development). `targetSdkVersion` is exactly what controls it.**

**Official platform statement** — Android 10 behaviour changes, heading *"Removed execute permission for app home directory"*:

> Execution of files from the writable app home directory is a W^X violation. Apps should load only the binary code that's embedded within an app's APK file. **Untrusted apps that target Android 10 cannot invoke `execve()` directly on files within the app's home directory.** In addition, apps that target Android 10 cannot in-memory modify executable code from files which have been opened with `dlopen()` and expect those changes to be written through to disk, because the library cannot have been mapped `PROT_EXEC` through a writable file descriptor. This includes any shared object (`.so`) files with text relocations.

[verified] — [Behavior changes: apps targeting API 29+](https://developer.android.com/about/versions/10/behavior-changes-10)

**The actual enforcement mechanism is SELinux, and it is keyed on `targetSdkVersion`.** `system/sepolicy/private/seapp_contexts` maps `targetSdkVersion` to a process domain:

```
user=_app minTargetSdkVersion=34 domain=untrusted_app     type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=32 domain=untrusted_app_32  type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=30 domain=untrusted_app_30  type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=29 domain=untrusted_app_29  type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=28 domain=untrusted_app_27  type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=26 domain=untrusted_app_27  type=app_data_file levelFrom=user
user=_app                        domain=untrusted_app_25 type=app_data_file levelFrom=user
```
[verified] — [private/seapp_contexts](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/seapp_contexts)

And `private/app_neverallows.te` **on `main` today** still contains:

```
# Block calling execve() on files in an apps home directory.
# This is a W^X violation (loading executable code from a writable
# home directory). For compatibility, allow for targetApi <= 28.
# b/112357170
neverallow {
  all_untrusted_apps
  -untrusted_app_25
  -untrusted_app_27
  -runas_app
} { app_data_file privapp_data_file }:file execute_no_trans;
```
where `all_untrusted_apps` = `{ ephemeral_app isolated_app isolated_app_all isolated_compute_app mediaprovider mediaprovider_app untrusted_app untrusted_app_25 untrusted_app_27 untrusted_app_29 untrusted_app_30 untrusted_app_all }`.

[verified] — [private/app_neverallows.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te)

**So the rule is:** `targetSdkVersion <= 28` → domain `untrusted_app_25`/`untrusted_app_27` → `execve()` of app-data files **allowed**. `targetSdkVersion >= 29` → `untrusted_app_29`/`_30`/`_32`/`untrusted_app` → **denied**. There is no API, permission, manifest flag, or opt-out. **A 2026 Play-distributed app at targetSdk 36 cannot do it.**

**The original commit message** (commit `0dd738d810532eb41ad8d90520156212ce756648`, author Nick Kralevich) — this is the announcement, and it contains the single most useful sentence in this whole report:

> **Enforce execve() restrictions for API > 28**
> `untrusted_app`: Remove the ability to run `execve()` on files within an application's home directory. Executing code from a writable `/home` directory is a `W^X` violation (https://en.wikipedia.org/wiki/W%5EX). Additionally, loading code from application home directories violates a security requirement that all executable code mapped into memory must come from signed sources, or be derived from signed sources.
> **Note: this change does *not* remove the ability to load executable code through other mechanisms, such as `mmap(PROT_EXEC)` of a file descriptor from the app's home directory. In particular, functionality like `dlopen()` on files in an app's home directory continues to work even after this change.**
> `untrusted_app_25` and `untrusted_app_27`: For backwards compatibility, continue to allow these domains to `execve()` files from the application's home directory.
> `seapp_contexts`: Bump the minimum API level required to enter the `untrusted_app` domain. …
> Bug: 112357170

[verified] — [commit 0dd738d8](https://android.googlesource.com/platform/system/sepolicy/+/0dd738d810532eb41ad8d90520156212ce756648)

**This resolves the open question from the earlier RikkaHub report.** `mmap(PROT_EXEC)` of an `app_data_file` **is allowed**; only `execute_no_trans` (i.e. `execve`) is not. Confirmed in current sepolicy — `private/untrusted_app_all.te`:

```
# Some apps ship with shared libraries and binaries that they write out
# to their sandbox directory and then execute.
allow untrusted_app_all privapp_data_file:file { r_file_perms execute };
allow untrusted_app_all app_data_file:file     { r_file_perms execute };
auditallow untrusted_app_all app_data_file:file execute;
```
[verified] — [private/untrusted_app_all.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/untrusted_app_all.te)

This is why PRoot works: its loader `mmap`s guest ELF images out of `app_data_file` instead of `execve`-ing them.

There is also a **newer, stricter rule** already landed on `main`, gated behind a release flag:
```
is_flag_enabled(RELEASE_UNLOCKED_STORAGE_API, `
  # block apps from executing files in their storage areas
  # this is a stronger and more desirable guarantee than blocking execute_no_trans, but
  # execute cannot be blocked on all of app_data_file without causing
  # backwards compatibility issues (see b/237289679)
  neverallow appdomain storage_area_content_file:file execute;
')
```
[verified] — same file. Not yet a change you must plan around, but it shows the direction of travel.

### 1.3 Binaries in `/data/app/.../lib/<abi>/` — executable?

**Yes. This is the sanctioned location for executables an app ships.** [verified]

Two pieces of primary evidence:

1. `private/app.te` grants the full execute set on `apk_data_file`:
```
# Allow apps to read/execute installed binaries
allow appdomain apk_data_file:dir { open getattr read search ioctl lock };
allow appdomain apk_data_file:file { getattr open read ioctl lock map x_file_perms };
```
[verified] — [private/app.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app.te)

2. `public/global_macros` defines the macro, and it **includes `execute_no_trans`**:
```
define(`x_file_perms',  `{ getattr execute execute_no_trans map }')
define(`r_file_perms',  `{ getattr open read ioctl lock map watch watch_reads }')
define(`rx_file_perms', `{ r_file_perms x_file_perms }')
```
[verified] — [public/global_macros](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/public/global_macros)

`apk_data_file` files are owned by `system`, mode `rwxr-xr-x`, and live on a read-only system-managed path — which is exactly why they are exempt. [uncertain — file ownership/mode is from the agnostic-apollo write-up, not read from AOSP: [App Data File Execute Restrictions](https://github.com/agnostic-apollo/Android-Docs/blob/master/site/pages/en/projects/docs/apps/processes/app-data-file-execute-restrictions.md)]

**Restrictions you must design around:**

| Restriction | Detail | Source |
|---|---|---|
| Must be requested explicitly | `android:extractNativeLibs="true"`, replaced from AGP 4.2.0 by the Gradle DSL `packaging.jniLibs.useLegacyPackaging = true`. Official doc: *"The default value of `extractNativeLibs` depends on `minSdkVersion` and the version of AGP you're using."* — i.e. **do not rely on the default; set it.** | [verified] [application-element](https://developer.android.com/guide/topics/manifest/application-element) |
| Filename must end in `.so` | Files not matching the native-library pattern are not packaged into `lib/<abi>/`. | [uncertain] — agnostic-apollo, plus documented by every project that ships a binary this way (e.g. ffmpeg-kit, busybox installers) |
| **Single flat directory** | There is no nesting under `lib/<abi>/`. A multi-binary rootfs must be symlinked out of this directory into app data. | [verified] — AOSP `lib/<abi>/` layout; the constraint is spelled out in [Termux's NLnet announcement](https://termux.dev/en/posts/general/2024/11/11/termux-selected-for-nlnet-ngi-mobifree-grant.html) |
| `ApplicationInfo.nativeLibraryDir` is the reliable way to find it | Do not hardcode the `/data/app/<pkg>-<hash>/lib/<abi>` path; the `-<hash>` suffix changes on update. | [verified] — standard Android API |
| Play Core on-demand feature modules do **not** work | They land in `/data/data/<pkg>/files/splitcompat` (`app_data_file`) first, where exec is denied. | [uncertain] — agnostic-apollo, citing [bundletool#155](https://github.com/google/bundletool/issues/155#issuecomment-614126504) |

**Google Play policy caveat.** Shipping the binary in the APK as a native library is Play-compliant; *downloading* executables at runtime is not. Play's policy: *"an app may not download executable code (such as dex, JAR, .so files) from a source other than Google Play. This restriction does not apply to code that runs in a virtual machine or an interpreter…"* — [verified] [Play Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/9888379). This matters: it is the policy reason the `system_linker_exec` trick (§1.5) is off the table for a Play app.

### 1.4 `posix_spawn` / `fork` availability

**All available.** `fork(2)`, `clone(2)`, `vfork(2)` and `posix_spawn(3)` all work in an app process. `posix_spawn` is implemented in bionic on top of `clone`/`vfork`; bionic's own comment confirms the design:

```
# vfork is used by bionic (and java.lang.ProcessBuilder) on some
# architectures. (The others use clone(2) directly instead.)
vfork()	arm,x86,x86_64
```
[verified] — [bionic/libc/SECCOMP_ALLOWLIST_COMMON.TXT](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/SECCOMP_ALLOWLIST_COMMON.TXT)

**Restrictions on the number of processes.** I could not find any *documented, Android-imposed* per-app process-count limit. The real constraints are:

- **`RLIMIT_NPROC` is per *real UID*, not per process** — and every process of your app shares one UID. It is inherited across `fork`. See §5.
- **cgroups are forbidden to apps**, so you cannot use a cgroup to bound a child:
```
# Untrusted apps are not allowed to use cgroups.
neverallow all_untrusted_apps cgroup:file *;
neverallow all_untrusted_apps cgroup_v2:file *;
```
[verified] — [private/app_neverallows.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te)
- `neverallow all_untrusted_apps domain:anon_inode *;` blocks `userfaultfd` and `io_uring` for apps. [verified] — same file.
- The practical ceiling is memory + LMKD (§5), not a process count.

**[uncertain overall — absence of documentation is not documentation of absence.]**

### 1.5 Other exec routes, and why to avoid them

- **`/system/bin/linker64 <path-to-elf>`** — the system linker can be exec'd by apps (`allow untrusted_app_all system_linker_exec:file execute_no_trans;` [verified], same file), and since Android 10 the linker accepts an absolute path to an executable in app data and runs it, bypassing the `execute_no_trans` check because the kernel only sees `linker` being executed. **This works, but it is a deliberate policy bypass and is not Google Play compliant** (it lets you download executables at runtime). Treat it as unavailable. [verified for the mechanism: [agnostic-apollo](https://github.com/agnostic-apollo/Android-Docs/blob/master/site/pages/en/projects/docs/apps/processes/app-data-file-execute-restrictions.md); the Play-policy conclusion follows from the Play quote above]
- **Root / sepolicy patching** — out of scope by definition.
- **PRoot** — see §6.

### 1.6 PTY without root

**Yes. Verified, and it is explicitly enabled for apps by name.**

`private/untrusted_app_all.te`:
```
# Allow the allocation and use of ptys
# Used by: https://play.google.com/store/apps/details?id=jackpal.androidterm
create_pty(untrusted_app_all)
```
[verified] — [private/untrusted_app_all.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/untrusted_app_all.te)

`public/te_macros` shows exactly what `create_pty` grants:
```
# create_pty(domain)
# Allow domain to create and use a pty, isolated from any other domain ptys.
define(`create_pty', `
# Each domain gets a unique devpts type.
type $1_devpts, fs_type;
# Label the pty with the unique type when created.
type_transition $1 devpts:chr_file $1_devpts;
# Allow use of the pty after creation.
allow $1 $1_devpts:chr_file { open getattr read write ioctl };
allowxperm $1 $1_devpts:chr_file ioctl unpriv_tty_ioctls;
# TIOCSTI is only ever used for exploits. Block it.
neverallowxperm * $1_devpts:chr_file ioctl TIOCSTI;
# Note: devpts:dir search and ptmx_device:chr_file rw_file_perms
# allowed to everyone via domain.te.
')
```
[verified] — [public/te_macros](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/public/te_macros)

And `private/domain.te` provides the base grants:
```
allow domain devpts:dir search;
allow domain ptmx_device:chr_file rw_file_perms;
allowxperm domain devpts:chr_file ioctl unpriv_tty_ioctls;
neverallowxperm * devpts:chr_file ioctl TIOCSTI;
```
[verified] — [private/domain.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/domain.te)

So: open `/dev/ptmx`, `grantpt`/`unlockpt`, `ptsname`, and you have a PTY. `TIOCSTI` is blocked (irrelevant to you). **Caveat:** `openpty(3)`/`forkpty(3)` are glibc `libutil` functions and are **not** in bionic — you either call the primitives yourself or vendor an implementation. Termux and jackpal's Android Terminal Emulator both do the former. [verified for the sepolicy grants; the bionic `openpty` absence is [uncertain] — I did not read bionic's headers]

---

## 2. Interpreters that can be embedded in an APK (no `exec` needed)

This is where the honest answer is: **everything in this section is fundamentally leakier than WASI, because an in-process interpreter in a same-UID process has your app's full filesystem access.** An interpreter sandbox is a *language-level* control, not a security boundary. Only §2's WASM runtimes give you a real capability boundary (§3).

### 2.1 Summary table

| Engine | Licence | Maintained | JNI needed | arm64-v8a | x86_64 | Real FS confinement? |
|---|---|---|---|---|---|---|
| **Chaquopy** (CPython) | MIT | Yes — v17.0.0, 2025-12-01 | Yes | Yes | Yes | No (OS module is the real one) |
| **CPython** (python-for-android / BeeWare) | PSF-2.0 | Yes | Yes | Yes | Yes | No |
| **QuickJS** (Bellard) | MIT | Dormant | Yes (or C via NDK) | via your NDK build | via your NDK build | Yes, at language level (no `std`/`os` unless you add it) |
| **QuickJS-ng** | MIT | Yes | as above | as above | as above | as above |
| **LuaJ** | MIT | Low activity | **No — pure Java** | n/a | n/a | Yes, if you replace `luajava`/IO libs |
| **Lua 5.4 via NDK** | MIT | Yes | Yes | Yes | Yes | Yes, if you drop `io`/`os` |
| **JavaScriptCore** | LGPL-2.0 / BSD-2 | Yes (WebKit) | Yes | unofficial | unofficial | No |
| **V8** | BSD-3 | Yes | Yes | unofficial | unofficial | No |
| **Rhino** | MPL-2.0 | Yes | **No — pure Java** | n/a | n/a | Yes, at language level |
| **GraalJS** | UPL-1.0 | Yes | n/a — needs Truffle/GraalVM | **No** | **No** | — |
| **wasm3** | MIT | Yes | Yes | Yes | Yes | **Yes — WASI preopens** |
| **WAMR / iwasm** | Apache-2.0 + LLVM exception | Yes | Yes | Yes | Yes | **Yes — WASI preopens** |
| **WasmEdge** | Apache-2.0 | Yes | Yes | Yes | Yes | **Yes — WASI preopens** |
| **Wasmtime** | Apache-2.0 + LLVM exception | Yes | Yes | **Tier 3 — build it yourself** | Tier 3 | **Yes — WASI preopens** |
| **Chicory** | Apache-2.0 | Yes | **No — pure Java** | ✅ interpreter only | ✅ interpreter only | **Yes — WASI preopens** |
| **Wasmer** | MIT | Yes | Yes | **no primary source found** | — | Yes (WASI) |

### 2.2 Chaquopy (Python for Android)

- **Licence: MIT.** The repo's `LICENSE.txt` is the MIT text, "Copyright (c) 2017-2025 Chaquo Ltd and contributors". [verified] — [chaquo/chaquopy LICENSE.txt](https://raw.githubusercontent.com/chaquo/chaquopy/master/LICENSE.txt)
  - **This is a change from the historical situation.** Chaquopy was previously offered under a paid/commercial licence with a free tier. The repo is now MIT, so **there is no cost and no commercial-use restriction.** I could not find a current licence/pricing page on the docs site (the `license.html` and `licence.html` URLs both 404), so I am going on the repository LICENSE file. [uncertain on the absence of a separate commercial tier]
- **Maintained:** yes, actively. v17.0.0 released **2025-12-01**; Python 3.10–3.14; minSdk 24. [verified] — [Version summary](https://chaquo.com/chaquopy/doc/current/versions.html), [chaquo.com/chaquopy](https://chaquo.com/chaquopy/)
- **JNI: yes.** Ships per-ABI native runtime libraries. arm64-v8a and x86_64 both supported (the FAQ's own example uses exactly `"arm64-v8a", "x86_64"`). [verified] — [Chaquopy FAQ](https://chaquo.com/chaquopy/doc/current/faq.html)
- **APK size cost: no primary source found.** Chaquopy does not publish a number. The FAQ's guidance is "you can also try reducing the number of ABIs in your APK… use a product flavor dimension" — i.e. the size is dominated by per-ABI native libs, and the official advice is to ship **arm64-v8a only** in production. Do not expect a small number: a CPython runtime plus the standard library plus whatever wheels you pull in is tens of MB per ABI. **Measure it yourself; I will not invent a figure.** [uncertain]
- **Filesystem confinement: no.** Python's `os`/`open` reach the real filesystem with your app's UID. You would have to strip/patch `posix`, `_io`, `os`, `io`, `importlib` — at which point you no longer have Python. Not a sandbox.
- **Play policy:** a bundled interpreter is explicitly OK ("This restriction does not apply to code that runs in a virtual machine or an interpreter"). But note the immediately following sentence: apps with interpreted languages *loaded at run time* must not allow policy violations. Since you'd run agent-written Python, that is your responsibility. [verified] — [Play policy](https://support.google.com/googleplay/android-developer/answer/9888379)

### 2.3 CPython via python-for-android / BeeWare Briefcase

- **Licence: PSF-2.0** for CPython itself. [uncertain — standard, not fetched]
- Both are real, maintained toolchains that produce an APK with CPython compiled for Android; Briefcase targets Android officially. [uncertain — not fetched in this pass]
- Same size and confinement story as Chaquopy, but you own the whole build. Compared with Chaquopy you trade build effort for control. [uncertain]

### 2.4 QuickJS / quickjs-ng

- **Licence: MIT.** [verified] — [quickjs LICENSE](https://raw.githubusercontent.com/bellard/quickjs/master/LICENSE)
- **Maintenance: Bellard's original is dormant; `quickjs-ng` is the active fork.** The fork's own README: *"This project is a fork of the original QuickJS project by Fabrice Bellard and Charlie Gordon, **after it went dormant**, with the intent of reigniting its development."* [verified] — [quickjs-ng README](https://raw.githubusercontent.com/quickjs-ng/quickjs/master/README.md)
- **JS feature level:** *"It aims to support the latest ECMAScript specification."* In practice this means ES2020+ (BigInt, modules, async/await, generators, Proxy, WeakRef). [verified for the claim, not for a conformance table]
- **arm64 + x86_64:** yes via your own NDK build (it is plain C, ~a few files). [uncertain]
- **APK size:** no primary source found; QuickJS is famous for being small (a ~200 KB-class `qjs` binary is the commonly cited order of magnitude), but I did not verify a number. [uncertain]
- **Confinement: yes, this is QuickJS's strength.** It is a bare language engine. There is no `require`, no filesystem, no `os` — a script can only do what *you* bind into it. You can expose exactly one directory via your own host functions and nothing else. That is a genuine (if hand-rolled) capability boundary at the language level. The risk is what you add: any `os`-like module you expose is a hole.

### 2.5 Lua

- **LuaJ (pure Java, Lua 5.2 semantics): MIT.** [verified] — [luaj LICENSE](https://raw.githubusercontent.com/luaj/luaj/master/LICENSE)
  - **No JNI, no NDK, no ABI concerns** — it is a Java interpreter, so it runs on ART as-is. That is a real advantage given how much of this report is about ABI packaging.
  - Maintenance is low-activity. [uncertain — I did not read the commit history]
  - Size: small (a pure-Java jar, single-digit MB at most). [uncertain]
  - Confinement: good, because you control the globals. LuaJ's `luajava` binding is a full reflection bridge into your app's Java object graph — **do not expose it**; that is a complete sandbox escape.
- **Lua 5.4/5.3 via NDK: MIT.** [uncertain — standard, not fetched]. Compiles cleanly to arm64/x86_64 with the NDK. Same story: remove `io`, `os`, `loadfile`, `dofile`, `require` and you have a clean expression/script engine with no ambient authority.

### 2.6 JavaScriptCore / V8 embedded in an APK

**Not realistic, and I would not pursue it.** [uncertain — this is an engineering judgement, not a cited fact]

- Neither project publishes an official, embeddable Android build for third-party apps. JSC ships in WebKit/Android System WebView as a *system* component; there is no supported "embed JSC in my APK" distribution. V8 has an Android build for Chrome/WebView, not for app embedding.
- Both are large: a V8 `libv8` monolith is in the tens-of-MB per ABI class. Building and keeping it updated across ABIs is a substantial ongoing cost.
- Neither provides filesystem confinement. JSC's `JSContext` (Apple API) has no Android equivalent; V8 isolates have no capability model.
- **If you want JS, use QuickJS (§2.4) or run JavaScript *inside* a WASM build of one of these engines under WASI (§3).** The latter is the interesting move: it gets you a real sandbox *and* a real JS engine.

### 2.7 Rhino / GraalJS on Android

- **Rhino: MPL-2.0, pure Java, works on ART.** It is a mature ES5-level engine (partial ES6). No JNI, no ABI issues, small. [uncertain — licence not fetched in this pass]
  - Confinement: good if you don't expose Java interop. Rhino's `Packages`/`JavaAdapter`/`importClass` live-connect to the JVM — **you must disable the `ClassShutter`-unprotected default** or a script gets your whole app. Rhino does provide `ClassShutter` for exactly this. [uncertain]
- **GraalJS: not viable on Android.** GraalJS is a Truffle language and requires GraalVM's Truffle runtime; there is no supported Android/ART target. Treat as **no**. [uncertain — inferred from the absence of any Android target, not from an explicit "Android unsupported" statement]

### 2.8 WebAssembly runtimes (§2 half; WASI semantics are §3)

Verified facts, with the gaps stated:

- **wasm3 — MIT.** README: *"Minimum useful system requirements: **~64Kb** for code and **~10Kb** RAM"*; runs on *"x86 x86_64 ARM RISC-V PowerPC MIPS Xtensa ARC32 … [platforms]"* including **Android**; *"is able to run many `WASI` apps"*. [verified] — [wasm3 README](https://github.com/wasm3/wasm3)
- **WAMR (iwasm) — Apache-2.0 with LLVM exception.** *"Small runtime binary size (core vmlib on cortex-m4f …) … ~21.4K for libc-wasi library"*; AOT loader works on *"Linux, Windows, MacOS, **Android**, SGX and MCU systems"*; AArch64 tested. Android build path is documented at `product-mini/platforms/android/`, producing `libiwasm.so`. [verified] — [WAMR README](https://github.com/bytecodealliance/wasm-micro-runtime), [product-mini/README.md](https://raw.githubusercontent.com/bytecodealliance/wasm-micro-runtime/main/product-mini/README.md)
- **WasmEdge — Apache-2.0.** CNCF sandbox project; provides an official guide for calling WasmEdge from an Android APK. Explicitly positions itself as *"a well-defined execution sandbox… The most important use case for WasmEdge is to safely execute user-defined or community-contributed code as plug-ins in a software product"*. [verified for the positioning and licence family — README; the Android APK guide is at [wasmedge.org](https://wasmedge.org/docs/zh-tw/contribute/source/os/android/apk/) (I did not read it in full)] — [WasmEdge README](https://github.com/WasmEdge/WasmEdge)
- **Wasmtime — Apache-2.0 with LLVM exception. Android is Tier 3.** PR #6480 ("Android release binaries", 2023) added Android aarch64/x86_64 to the build matrix *"and adds Android to the docs in 'tier3'"*. Tier 3 means **no official binaries, no CI coverage** — you must cross-compile and maintain it yourself. An open issue on `wasmtime-py` asks "Current state of Android support?" (May 2025) and cites exactly that tier-3 doc, with no answer. [verified] — [wasmtime#6480](https://github.com/bytecodealliance/wasmtime/pull/6480), [wasmtime-py#278](https://github.com/bytecodealliance/wasmtime-py/issues/278)
- **Chicory — pure-JVM WASM runtime, zero native dependencies.** Releases are on Maven Central. **This is the one that needs no NDK at all.** But read the execution-mode caveat in §3.3 carefully. [verified] — [Chicory README](https://github.com/dylibso/chicory)
- **Wasmer — MIT.** **No primary source found** for current Android support. Their docs describe the runtime in general terms; I did not find an Android platform-support statement. Treat as unverified. [uncertain]

---

## 3. WebAssembly + WASI specifically

### 3.1 Does WASI preview 1 give a genuine capability-based filesystem sandbox?

**Yes — for the filesystem, preopens + per-descriptor rights are a real capability model, and the only way to name a file is relative to a directory descriptor the host chose to hand you.** [verified]

From the WASI preview 1 API definition:

- `fd_prestat_get(fd: fd) -> Result<prestat, errno>` — *"Return a description of the given preopened file descriptor."*
- `prestat` — *"Information about a **pre-opened capability**."*
- `fd_prestat_dir_name(fd: fd, path: Pointer<u8>, path_len: size) -> Result<(), errno>` — *"A buffer into which to write the preopened directory name."*
- `path_open(fd: fd, dirflags: lookupflags, path: string, oflags: oflags, fs_rights_base: rights, fs_rights_inheriting: rights, fdflags: fdflags) -> Result<fd, errno>` — *"Open a file or directory. … **Note: This is similar to `openat` in POSIX.**"* and the `path` parameter is *"The relative path of the file or directory to open, **relative to the `path_open::fd` directory**"*.
- The `rights` record is a **per-descriptor capability set**: `fd_read`, `fd_write`, `path_open`, `path_create_directory`, `path_create_file`, `path_remove_directory`, `path_unlink_file`, `path_readlink`, `path_symlink`, `fd_readdir`, `path_filestat_get`, `path_rename_source`, `path_rename_target`, …
- The errno list includes `ENOTCAPABLE` — *"Extension: Capabilities insufficient."*

[verified] — [WASI preview1 docs.md](https://raw.githubusercontent.com/WebAssembly/WASI/01255fd333137c902f74e6b0ad9693ae4694cce3/legacy/preview1/docs.md)

**There is no absolute-path open in WASI preview 1.** A module starts with exactly the fds the host preopened (conventionally fd 3..n). If you preopen one directory, the module can name only things beneath it. That is precisely the "expose exactly one directory and nothing else" mechanism you asked about.

**The honest caveats:**

1. **The sandbox is only as good as the host's path resolution.** The capability model says "you may only reach what's under this dirfd", but enforcing that against `..`, symlinks and absolute symlink targets is the *runtime's* job. This is historically where WASI implementations have had bugs. WASI 0.2 writes the requirement down explicitly: *"step of resolving a `path`, including `..` and symbolic link steps, reaches a directory outside of the base directory, or reaches a symlink to an absolute or rooted path in the underlying filesystem, the function fails with `error-code::not-permitted`."* [verified] — [wasi-filesystem types.wit](https://raw.githubusercontent.com/WebAssembly/wasi-filesystem/main/wit/types.wit). Pick a runtime that takes this seriously and keep it updated.
2. **WASI preview 1 has no ambient authority *except* what the host adds.** Environment variables, CLI args, stdio, clock, random — all host-mediated. But if you also link WasmEdge's socket extension or a `wasi:http` import, you have just handed the guest the network. **Your import set is the sandbox.**
3. **Nothing stops the host from preopening `/`.** The security property is a property of *your* `WasiCtx` construction, not of WASI.
4. **`fd_prestat_*` is preview-1 legacy.** It still works and every preview-1 runtime implements it.

### 3.2 Does WASI preview 2 change the model?

**No. Preopens remain the mechanism**, re-expressed as a WIT interface. The whole interface is:

```wit
interface preopens {
    use types.{descriptor};
    /// Return the set of preopened directories, and their paths.
    get-directories: func() -> list<tuple<descriptor, string>>;
}
```
[verified] — [wasi-filesystem wit/preopens.wit](https://raw.githubusercontent.com/WebAssembly/wasi-filesystem/main/wit/preopens.wit)

The difference is ergonomic/structural, not security: descriptors are now typed resources and rights are per-descriptor, so the *shape* of the capability set is the same. `wasi:filesystem` is where path resolution rules now live in normative prose. [verified] — same repo.

### 3.3 Which runtimes implement preopens correctly on Android?

| Runtime | Android | Preopens | Verdict |
|---|---|---|---|
| **wasm3** | Android listed as a supported platform; "able to run many WASI apps" [verified] | WASI support present, but wasm3 is an interpreter with a deliberately minimal WASI surface; **I did not verify preopen correctness** [uncertain] | Good for tiny/simple programs; weakest WASI completeness |
| **WAMR** | Official Android build path, produces `libiwasm.so` [verified] | Full WASI with preopens and a libc-wasi (~21.4 KB) [verified that libc-wasi exists; preopen correctness not independently verified] | **Strong candidate** — small, designed for embedding, C API |
| **WasmEdge** | Official Android/APK integration guide [verified that the guide exists] | Full WASI; runtime markets itself on exactly this sandboxing use case [verified] | **Strong candidate**; larger than WAMR |
| **Wasmtime** | **Tier 3** — you build it yourself, no CI, no release binaries [verified] | Reference-quality WASI [uncertain for Android specifically] | Best WASI semantics, worst Android ergonomics |
| **Chicory** | **Pure Java, no NDK.** Interpreter mode is the portable default [verified] | WASI P1 supported; `WasiOptions.Builder.withDirectory(String guest, Path host)` is the preopen API [verified] | **Read §3.3a — this is the decision point** |
| **Wasmer** | Unverified [uncertain] | — | — |

#### 3.3a Chicory on Android — the catch that decides it

From Chicory's own execution-mode documentation:

| Mode | Performance | Dynamic module loading | Requirements | **Android** |
|---|---|---|---|---|
| Interpreter | 🐢 Slow | ✅ Supported | **None** | ✅ works |
| Runtime Compilation | 🐇 Fast | ✅ Supported | reflection + ASM dependency; in-memory Java bytecode | ❌ *"it might not be supported on some platforms (**such as Android**, or GraalVM's native-image)"* |
| Build-time Compilation | 🐇 Fast | ❌ **Not supported** | Build-time Maven/Gradle plugin; plain Java bytecode | ✅ but useless here |

[verified] — [Chicory execution modes](https://chicory.dev/docs/usage/execution_modes/)

**Why this matters:** build-time compilation is the fast mode that works on Android, but it cannot load a module at runtime — and an LLM writing code at runtime is the entire use case. So **on Android you are stuck with Chicory's interpreter**, which is the slow mode. That is still usable (Chicory is a competent interpreter and you get a pure-Java, zero-NDK, zero-ABI sandbox), but do not expect the "🐇 Fast" numbers.

### 3.4 Can you get a compiler that runs on-device to turn source into WASM?

**No practical one. This is the hardest gap in the whole picture, and it is the reason the "WASM + WASI" story is incomplete for arbitrary languages.** [uncertain — a negative finding]

What exists:

- **`c4wa` — "A simplified subset of C transpiled into Web Assembly".** [verified for the description] — [kign/c4wa](https://github.com/kign/c4wa). This is a *transpiler for a C subset*, not a C compiler. It will not compile an arbitrary C program, and it will not compile Rust/Go/Python.
- **`wasm-clang` (binji) and similar browser-hosted clang builds** — I found references but **could not fetch a primary source**, so I cannot give you a size or a usability verdict. [uncertain] These were browser demos: a full clang+LLVM compiled to WASM, with a multi-tens-of-MB download, no filesystem, and a single-file-in/single-file-out model. I would not ship one.
- **A WASI build of clang (WASI SDK / `wasm32-wasi` clang)** would in principle be the thing — clang targeting `wasm32-wasi` running *as* a WASI program at runtime — but I found no maintained, size-documented distribution of clang-built-for-WASI, and LLVM is very large (hundreds of MB as a full toolchain). [uncertain]
- **TinyGo / AssemblyScript**: these compile to WASM but are themselves compilers written in Go/TypeScript; running either inside a WASM runtime on a phone is not a shipped configuration. [uncertain]

**The practical consequence:** if you want WASI sandboxing, the agent must write **WASM**, not C/Rust/Python. Options: (a) the agent writes WAT/WASM directly (LLMs are decent at small WAT); (b) you ship one of the *pre-built* WASM language runtimes (e.g. a WASI build of CPython, Lua, or QuickJS) as a static `.wasm` asset inside the APK and the agent writes the language, not WASM.

**Option (b) is the good answer, and I want to flag it explicitly:** a WASI-compiled CPython/Lua/QuickJS `.wasm` gives you *both* — a real language for the agent *and* a real capability boundary from WASI preopens, with no NDK, no per-ABI packaging, and no exec. The cost is that the runtime is fixed at build time (you cannot `pip install`), and interpreter performance is roughly 2–10× slower than native. **[uncertain — I did not verify a specific prebuilt WASI Python distribution in this pass; this is a design recommendation, not a cited fact.]**

---

## 4. Sandboxing / isolation primitives available to a normal Android app

### 4.1 `seccomp` from an app without root

Two separate questions, two different answers.

**(a) Android already installs a seccomp filter on every app process, and it is a strict *allowlist* whose default action is `SIGSYS` (not `ENOSYS`).**

`bionic/libc/seccomp/seccomp_policy.cpp`:
```cpp
inline void Allow(filter& f)    { f.push_back(BPF_STMT(BPF_RET|BPF_K, SECCOMP_RET_ALLOW)); }
inline void Disallow(filter& f) { f.push_back(BPF_STMT(BPF_RET|BPF_K, SECCOMP_RET_TRAP)); }
...
    ExamineSyscall(f);
    for (size_t i = 0; i < p_size; ++i) { f.push_back(p[i]); }
    Disallow(f);          // <-- default action for anything unmatched: TRAP
```
[verified] — [bionic/libc/seccomp/seccomp_policy.cpp](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/seccomp/seccomp_policy.cpp)

The policy is generated from bionic's syscall list. `SECCOMP_BLOCKLIST_APP.TXT` states the rule:
> *"The final seccomp allowlist is `SYSCALLS.TXT - SECCOMP_BLOCKLIST.TXT + SECCOMP_ALLOWLIST.TXT`"*

[verified] — [SECCOMP_BLOCKLIST_APP.TXT](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/SECCOMP_BLOCKLIST_APP.TXT)

**Syscalls explicitly denied to apps** (from `SECCOMP_BLOCKLIST_APP.TXT`, verbatim):
`setgid`, `setuid`, `setregid`, `setreuid`, `setresgid`, `setfsgid`, `setfsuid`, `setgroups`, `adjtimex`, `clock_adjtime`, `clock_settime`, `settimeofday`, `acct`, `syslog`, **`chroot`**, `init_module`, `delete_module`, **`mount`**, **`umount2`**, `swapon`, `swapoff`, `setdomainname`, `sethostname`, `reboot`. (`setresuid` is deliberately *not* blocked — zygote needs it.)
[verified] — same URL

**The two headline negatives from this:**

> **An Android app can never `mount()` or `chroot()`. The syscall is killed by seccomp with `SIGSYS` before SELinux or the kernel even evaluate it.** There is no permission, no root-free workaround, and no manifest flag. Any design that assumes a real `chroot` or a bind mount is dead on arrival. [verified]

**(b) Can an app *tighten* seccomp further? Yes — and this is a genuinely useful primitive.**

The relevant kernel rule is that `prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, …)` requires **either** `CAP_SYS_ADMIN` **or** `task->no_new_privs == 1`. Android's own seccomp installer documents this:
```cpp
    // This assumes either the current process has CAP_SYS_ADMIN, or PR_SET_NO_NEW_PRIVS bit is set.
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) < 0) { PLOG(FATAL) << ... }
```
[verified] — [seccomp_policy.cpp](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/seccomp/seccomp_policy.cpp)

And zygote **deliberately does not set `no_new_privs`** for apps:
```cpp
    // Must be called when the new process still has CAP_SYS_ADMIN, in this case,
    // before changing uid from 0, which clears capabilities.  The other
    // alternative is to call prctl(PR_SET_NO_NEW_PRIVS, 1) afterward, but that
    // breaks SELinux domain transition (see b/71859146).  As the result,
    // privileged syscalls used below still need to be accessible in app process.
    SetUpSeccompFilter(uid, is_child_zygote);
    ...
    if (setresuid(uid, uid, uid) == -1) { ... }
```
[verified] — [com_android_internal_os_Zygote.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/jni/com_android_internal_os_Zygote.cpp)

**Conclusion:** after `setresuid`, an app process has neither `CAP_SYS_ADMIN` nor `no_new_privs`. But `prctl(PR_SET_NO_NEW_PRIVS, 1)` is unprivileged and always succeeds, so an app **can** do:

1. `prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)`
2. `seccomp(SECCOMP_SET_MODE_FILTER, …, &prog)` or `prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)`

Both `prctl` and `seccomp` are on the app allowlist — `seccomp(unsigned, unsigned, void*) all` is explicitly there with the comment *"Needed for a CTS test of seccomp (b/34763393)."* [verified] — [SECCOMP_ALLOWLIST_COMMON.TXT](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/SECCOMP_ALLOWLIST_COMMON.TXT)

seccomp filters **stack** (they are ANDed), so you can only ever make the policy stricter — which is exactly what you want. **This is the one real, cheap, kernel-enforced confinement primitive available to an ordinary Android app.**

*Caveats:* [uncertain]
- A filter installed with `PR_SET_SECCOMP` applies **only to the calling thread**. To cover the whole process you need `SECCOMP_FILTER_FLAG_TSYNC`, which can fail if threads hold differing filters.
- The filter is **inherited across `fork`/`exec`**, so if you install it in a native launcher binary and then `exec` the target, the target starts confined. That is the practical pattern.
- Your filter can only *forbid*; it cannot add syscalls Android already blocked. So a sandboxed child still cannot `mount`, `chroot`, or `setuid`.
- Getting a non-trivial seccomp filter right is genuinely hard. Do not hand-roll it for v1.

### 4.2 Linux namespaces — are they available to a normal app?

**Mostly no, and the parts that work are not the parts you want.**

**(a) Every app already runs in its own mount namespace — created by zygote, not by the app.**
```cpp
static void ensureInAppMountNamespace(fail_fn_t fail_fn) {
  if (gInAppMountNamespace) {
    // In app mount namespace already
    return;
  }
  if (unshare(CLONE_NEWNS) == -1) {
    fail_fn(CREATE_ERROR("Failed to unshare(): %s", strerror(errno)));
  }
  gInAppMountNamespace = true;
}
```
[verified] — [Zygote.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/jni/com_android_internal_os_Zygote.cpp)

**(b) An app cannot create its own namespaces.** `unshare(2)` and `setns(2)` *are* on the seccomp allowlist:
```
setns(int, int) all
unshare(int) all
```
[verified] — [bionic SYSCALLS.TXT](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/SYSCALLS.TXT)

…but `CLONE_NEWNS`/`NEWPID`/`NEWNET`/`NEWUTS`/`NEWIPC` all require `CAP_SYS_ADMIN`, and zygote drops the capability bounding set for apps (`DropCapabilitiesBoundingSet`) and `setresuid`s away from root. So these calls return `EPERM`. [verified for the capability drop; the `EPERM` follows from Linux namespace semantics]

**(c) User namespaces: I could not establish this definitively — treat as [uncertain], but plan for "no".**

The Android kernel tree carries a patch that tightens `create_user_ns()` beyond upstream, adding `CAP_SETUID`/`CAP_SETGID` to the `CAP_SYS_ADMIN` requirement:
```diff
 	/* hopefully this check will go away when userns support is
 	 * complete */
-	if (!capable(CAP_SYS_ADMIN))
+	if (!capable(CAP_SYS_ADMIN) || !capable(CAP_SETUID) ||
+	    !capable(CAP_SETGID))
 		return -EPERM;
```
[verified that this diff exists in the Android kernel tree] — [kernel/arm64 commit 7657d904](https://android.googlesource.com/kernel/arm64/+/7657d90497f98426af17f0ac633a9b335bb7a8fb%5E%21/kernel/fork.c)

An app has none of those capabilities, so under that patch `unshare(CLONE_NEWUSER)` fails. **However** I could not date that commit, could not confirm it is still present in `android14-6.1`/`android16-*`, and my grep of the current `kernel/user_namespace.c` was inconclusive. There are also widely-repeated secondary reports that Android 14 "does not support user namespaces for unprivileged apps". **Bottom line: assume user namespaces are unavailable; do not design around them. [uncertain]**

**(d) What SELinux denies.** The most relevant neverallows for an app domain [all [verified], [private/app_neverallows.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te)]:

```
neverallow all_untrusted_apps cgroup:file *;          # no cgroups
neverallow all_untrusted_apps cgroup_v2:file *;
neverallow all_untrusted_apps domain:anon_inode *;    # no userfaultfd, no io_uring
neverallow all_untrusted_apps selinuxfs:file no_rw_file_perms;   # "SELinux is not an API for untrusted apps to use"
neverallow all_untrusted_apps file_type:file link;    # no hardlinks at all
neverallow all_untrusted_apps all_untrusted_apps ... # no service_manager add, no vndbinder, etc.
neverallow all_untrusted_apps *:{ netlink_route_socket netlink_selinux_socket } ioctl;
neverallow all_untrusted_apps tun_device:chr_file open;
neverallow all_untrusted_apps domain:netlink_socket *;
```
A couple of these are worth calling out: **`selinuxfs` is unreadable/unwritable**, so an app cannot inspect or alter its own policy; **hardlinks are forbidden outright**, which kills a whole class of "install a compiler by hardlinking into the executable directory" tricks.

### 4.3 Can an app run code in a separate process with a *different* UID?

**Yes — `android:isolatedProcess="true"` is the only mechanism, and it is a real UID boundary.** This is the closest thing Android gives an ordinary app to a sandbox.

**Official documentation (verbatim):**
> `android:isolatedProcess` — If set to `"true"`, this service runs under a special process that is isolated from the rest of the system and **has no permissions of its own**. The only communication with it is through the Service API, with binding and starting.

[verified] — [`<service>` element](https://developer.android.com/guide/topics/manifest/service-element)

**What that means in the SELinux policy.** `seapp_contexts` maps it to its own domain and its own UID range:
```
user=_isolated domain=isolated_app levelFrom=user
neverallow user=_isolated isIsolatedComputeApp=false domain=((?!isolated_app).)*
neverallow user=((?!_isolated).)* domain=isolated_app
```
[verified] — [private/seapp_contexts](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/seapp_contexts)

`private/isolated_app.te` is short enough to quote in full in the relevant part:
```
###
### isolated_apps.
###
### This file defines the rules for isolated apps that does not wish to use
### service managers and does not require extra computational resources.
###
typeattribute isolated_app coredomain;
app_domain(isolated_app)
isolated_app_domain(isolated_app)

allow isolated_app webviewupdate_service:service_manager find;

# Allow access to network sockets received over IPC. New socket creation is not
# permitted.
allow isolated_app { ephemeral_app priv_app untrusted_app_all }:{ tcp_socket udp_socket } { rw_socket_perms_no_ioctl };

# b/32896414: Allow accessing sdcard file descriptors passed to isolated_apps
# by other processes. Open should never be allowed, and is blocked by
# neverallow rules in isolated_app_all attribute.
# media_rw_data_file is included for sdcardfs, and can be removed if sdcardfs
# is modified to change the secontext when accessing the lower filesystem.
allow isolated_app { sdcard_type fuse media_rw_data_file }:file { read write append getattr lock map };
...
```
[verified] — [private/isolated_app.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/isolated_app.te)

### 4.4 What `isolatedProcess` actually isolates — capability matrix

| Capability | Normal app process | `isolatedProcess` service |
|---|---|---|
| **UID** | your app's UID | a **different, isolated UID** (range reserved for `_isolated`) [verified — seapp_contexts] |
| **Runtime permissions** | whatever you hold (`INTERNET`, …) | **none** — *"has no permissions of its own"* [verified — docs] |
| **Network** | yes (with `INTERNET`) | **no new sockets.** *"New socket creation is not permitted."* It may only read/write `tcp`/`udp` sockets **passed to it over IPC** [verified — isolated_app.te] |
| **Your app's private files (`app_data_file`)** | full read/write | **no access.** `isolated_app` is *not* a member of `untrusted_app_all`, and `isolated_app.te` contains no allow for `app_data_file`. Path opens are denied. [verified by policy reading] |
| **Public storage / sdcard** | with storage permissions | **read/write/append on already-open descriptors only** — *"Open should never be allowed, and is blocked by neverallow rules"* [verified] |
| **Your APK's native libraries (`apk_data_file`)** | executable | **executable** — `isolated_app` is in `appdomain`, and `allow appdomain apk_data_file:file { … x_file_perms }` applies. So it *can* exec a helper binary you shipped in `jniLibs`. [verified by policy reading] |
| **Service manager** | broad | essentially nothing (only `webviewupdate_service`) [verified] |
| **IPC** | full Binder | *"The only communication with it is through the Service API, with binding and starting."* [verified — docs] |
| **Android APIs / your app's classes** | all | it runs your `Service` subclass in your APK, so your code runs — but with no permissions. Any API needing a permission will throw. [verified — this is how the mechanism works] |

**The architectural consequence, stated bluntly:** `isolatedProcess` gives you a genuine UID + permission boundary **and simultaneously takes away the ability to open the workspace directory**. To let the isolated process touch files you must **proxy every filesystem operation back through your main process over Binder**, or pass file descriptors.

Whether passing an `app_data_file` `ParcelFileDescriptor` into an `isolatedProcess` actually works is the one thing I could not resolve from policy alone: SELinux re-evaluates `file:read`/`file:write` against the *accessing* process's domain on each operation, and there is no `allow isolated_app app_data_file:file …` rule — but kernel behaviour for inherited/passed descriptors can differ from fresh opens. Note that the policy authors explicitly added a rule for *sdcard* fds passed to isolated apps and commented that "Open should never be allowed" — implying passed-fd access is the intended model generally, but they did not add the equivalent for `app_data_file`. **Treat this as needing a device test before you architect around it.** [uncertain — flagged as the single most important thing to prototype]

**Practical recommendation:** if you use `isolatedProcess`, do **not** try to hand it the workspace. Hand it *bytes*: it asks the host for file contents over Binder, computes, and returns bytes. That is unambiguously supported, and it makes the boundary real.

### 4.5 Restricting a child process's network access

**There is no public API for per-app or per-process network firewalling.** `NetworkPolicyManager` is a `@SystemApi` gated behind `MANAGE_NETWORK_POLICY`; `ConnectivityManager.restrictBackgroundStatus()` is read-only and reflects the *user's* Data Saver setting; there is no firewall API. A `VpnService` requires explicit user consent, is a device-wide VPN, and cannot be applied per-UID from your app. [uncertain — this is an argument from the absence of any such API in the public SDK; I did not find a page stating the negative]

**What actually works, in order of strength:**

1. **`isolatedProcess`** — no `INTERNET` permission, so `socket()` fails. **This is the only clean, OS-enforced answer.** [verified — docs + isolated_app.te]
2. **A seccomp filter installed in the child** that returns an error for `socket`, `socketpair`, `connect`, `sendto`, `sendmsg`, `bind`, `listen`, `accept4`, `recvfrom`, `recvmsg`. Since filters are inherited across `exec`, a native launcher in `jniLibs` can install this and then exec the target. **This is real and kernel-enforced.** Cost: you must write and maintain the filter. [verified mechanism; the specific syscall list is mine]
3. Don't grant `INTERNET`. Useless here — your chat app needs it.

---

## 5. Resource limits

### 5.1 `setrlimit` from an app

**The mechanism works and is inherited by children. The semantics have sharp edges that make only some limits usable.**

Linux `setrlimit(2)`, verified from the man page:
- *"The hard limit acts as a ceiling for the soft limit: an unprivileged process may set only its soft limit to a value in the range from 0 up to the hard limit, and (irreversibly) lower its hard limit."*
- *"A privileged process (under Linux: one with the `CAP_SYS_RESOURCE` capability in the initial user namespace) may make arbitrary changes to either limit value."*
- *"`EPERM`: An unprivileged process tried to raise the hard limit; the `CAP_SYS_RESOURCE` capability is required to do this."*
- *"A child process created via `fork(2)` inherits its parent's resource limits."*

[verified] — [setrlimit(2)](https://man7.org/linux/man-pages/man2/setrlimit.2.html)

`setrlimit` is on the bionic allowlist (`setrlimit(int, const struct rlimit*) lp32` plus the lp64 variant), so seccomp does not block it. [verified] — [SYSCALLS.TXT](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/SYSCALLS.TXT)

**Which limits are actually usable:**

| Limit | Works? | Practical notes |
|---|---|---|
| `RLIMIT_CPU` | ✅ | SIGXCPU at soft, SIGKILL at hard. **Trap:** *"Linux doesn't return an error when an attempt to set `RLIMIT_CPU` has failed, for compatibility reasons"* [verified] — so you cannot detect failure. Also since Linux 2.6.12 the kernel bumps the soft limit by one second on SIGXCPU if a handler is installed. Wall-clock timeouts are more predictable; use both. |
| `RLIMIT_AS` | ⚠️ | Limits total virtual address space. **Do not apply it to your own ART process** — ART reserves an enormous virtual range and you will kill your app. Fine for a plain native child. [uncertain — inferred from ART's heap reservation behaviour] |
| `RLIMIT_DATA` | ⚠️ | Same caution as `RLIMIT_AS` on modern kernels where it covers `mmap`ed anonymous memory. [uncertain] |
| `RLIMIT_FSIZE` | ✅ | *"the maximum size in bytes of files that the process may create"*; exceeding it raises SIGXFSZ. [verified — man page] Directly useful for "stop the agent filling the disk with one file". Does not stop *many* files. |
| `RLIMIT_NPROC` | ❌ **not usable for what you want** | *"a limit on the number of extant process (or, more precisely on Linux, threads) **for the real user ID** of the calling process. So long as the current number of processes belonging to this process's real user ID is greater than or equal to this limit, `fork(2)` fails with `EAGAIN`. The `RLIMIT_NPROC` limit is **not enforced** for processes that have either the `CAP_SYS_ADMIN` or `CAP_SYS_RESOURCE` capability, or run with real user ID 0."* [verified — man page]. **Because every process in your app shares one real UID, this is an app-wide cap, not a per-child cap — and setting it low can stop your own app from forking.** This is the sharpest edge in the whole section. |
| `RLIMIT_NOFILE` | ✅ | Per-process fd cap. Useful against fd-exhaustion. |
| `RLIMIT_CORE` | ✅ | Core dumps are already disabled for apps unless `persist.zygote.core_dump` is set (see Zygote's `EnableDebugger`). [verified] |

**How to apply a limit to a child, in practice.** You cannot run code between `fork` and `exec` from Java. Two workable patterns:
1. **Native launcher** shipped in `jniLibs` (so it is in `apk_data_file`, hence exec'able — §1.3). It calls `setrlimit()` on itself, then `execve()`s the target. **This is the pattern I would use.**
2. Native code in your app that uses `posix_spawn` with a `posix_spawnattr_t`, or does `fork()` + `setrlimit()` + `execve()` directly.

### 5.2 Android's own per-app limits

- **No hard memory cap on native allocations.** Android's `ActivityManager.getMemoryClass()` reports the ART **Java heap** limit, and `android:largeHeap` raises it; neither bounds native/heap-allocated memory. [uncertain — standard behaviour, not re-fetched]
- **LMKD (Low Memory Killer Daemon)** is the actual enforcement, and it is heuristic and *global*, not a per-app quota. It kills by `oom_score_adj`, so a background chat app is a prime target. Code: [platform/system/memory/lmkd](https://android.googlesource.com/platform/system/memory/lmkd). [verified that lmkd exists; the "no per-app quota" conclusion is [uncertain]]
- **Background execution limits** (Android 8.0+): background services are stopped, background location is throttled, implicit broadcasts are restricted. Relevant because agent code that runs while the user is elsewhere can be frozen or killed. [uncertain — did not fetch the current page]
- **App Standby Buckets / Doze**: an unused app's jobs and network access get deferred. [uncertain]
- **You cannot use cgroups.** `neverallow all_untrusted_apps cgroup:file *;` and `neverallow all_untrusted_apps cgroup_v2:file *;`. [verified] — [app_neverallows.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te). Android does place your app in a cgroup (that is how scheduling/`cpuctl` works), but **you cannot create or manage one**, so cgroup-based CPU/memory caps for a child are off the table.
- `seccomp` also blocks `ioprio_set`? No — `ioprio_set`/`ioprio_get` are in `SECCOMP_ALLOWLIST_COMMON.TXT` but marked `lp64` for booting Android; they still need privileges to actually change anything. [verified presence]

### 5.3 Is there a documented way to cap the CPU/memory of a spawned child?

**No — there is no public Android API for this.** [uncertain — a negative finding]

What you actually have:
1. **`setrlimit` in a native launcher** — `RLIMIT_CPU`, `RLIMIT_AS`, `RLIMIT_FSIZE`, `RLIMIT_NOFILE`. Kernel-enforced, per-process, inherited. This is the real answer.
2. **A wall-clock timeout in your process** — `Process.destroy()` / `destroyForcibly()`, or wait on the pipe with a deadline. Catches hangs that `RLIMIT_CPU` misses (blocked on I/O, sleeping).
3. **Output byte caps** while draining stdout/stderr — otherwise a runaway child fills your heap or disk.
4. **A total workspace quota + `StatFs` free-space precheck** before writes and before any archive extraction.
5. **`seccomp`** to forbid `fork`/`clone`/`vfork` outright if the child has no business spawning anything. Note `clone` and `vfork` are in `SECCOMP_ALLOWLIST_COMMON.TXT`, so blocking them is a *tightening* you can do. [verified that they are currently allowed]

**Do not expect to cap memory reliably.** `RLIMIT_AS` is the only lever and it is a virtual-address-space limit, which is a poor proxy for actual usage (ART, jemalloc and mmap-heavy code all reserve far more VA than they touch).

---

## 6. Precedent

### 6.1 How does Termux run compilers on a non-rooted device?

**Termux works without root. The mechanism is: pin `targetSdkVersion = 28` and keep everything in `/data/data/com.termux/files/usr`.**

**Verified from Termux's own repository** — `gradle.properties` on the `master` branch today:
```
minSdkVersion=21
targetSdkVersion=28
compileSdkVersion=36
```
[verified] — [termux-app gradle.properties](https://raw.githubusercontent.com/termux/termux-app/master/gradle.properties)

This is the crux and it is not a coincidence: `targetSdkVersion=28` puts Termux in the **`untrusted_app_27`** SELinux domain, which is one of the two domains the `neverallow` in §1.2 **explicitly exempts**:
```
neverallow {
  all_untrusted_apps
  -untrusted_app_25
  -untrusted_app_27
  -runas_app
} { app_data_file privapp_data_file }:file execute_no_trans;
```
[verified] — [app_neverallows.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te)

**So Termux's entire package ecosystem (`apt`, `clang`, `python`, `rustc`) rests on being allowed to `execve()` files inside `/data/data/com.termux/files/usr/bin` — which is exactly what a targetSdk-36 app may not do.** Termux pairs this with:
- its own bootstrap zip extracted into `$PREFIX` (`/data/data/com.termux/files/usr`),
- `termux-exec` (an `LD_PRELOAD` shim that rewrites `execve` paths),
- binaries built against `/system/bin/linker64` with `DT_RUNPATH` so shared libraries resolve under `$PREFIX/lib`.

[verified for the targetSdk pin and the sepolicy exemption; the bootstrap/termux-exec/RUNPATH details are [uncertain] — the parent report cites the [Termux NLnet announcement](https://termux.dev/en/posts/general/2024/11/11/termux-selected-for-nlnet-ngi-mobifree-grant.html), which I did not re-read in this pass]

**The Play Store consequence.** `targetSdkVersion=28` cannot satisfy current Google Play target-API requirements, so **this approach is unavailable to a Play-distributed app in 2026.** Termux's own status bears this out. [verified that targetSdk is 28; the Play-requirement conclusion is [uncertain] — I did not read the current Play target-API policy page]

**Also relevant:** Termux has repeatedly asked for a *sanctioned* path — an `untrusted_dev_app` domain that a user could grant to a specific app via `adb`, so it could keep the traditional rootfs-and-package-manager design. It does not exist. [verified] — [agnostic-apollo write-up](https://github.com/agnostic-apollo/Android-Docs/blob/master/site/pages/en/projects/docs/apps/processes/app-data-file-execute-restrictions.md)

### 6.2 Does PRoot work without root? (the workaround Termux and RikkaHub both use)

**Yes, and the policy evidence says it should.** PRoot is a `ptrace`-based syscall translator that fakes a rootfs — it is not a container, not a namespace, and not a `chroot` (which seccomp forbids outright, §4.1). It works on modern Android because:

- **`ptrace` is on the app seccomp allowlist**: `__ptrace:ptrace(int request, int pid, void* addr, void* data) all`. [verified] — [SYSCALLS.TXT](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/SYSCALLS.TXT)
- **`mmap(PROT_EXEC)` of `app_data_file` is allowed** (`execute`, but not `execute_no_trans`) — so PRoot's loader can map guest ELF images out of the rootfs without `execve`-ing them. This is exactly what the AOSP commit message anticipated (§1.2). [verified]
- PRoot itself ships as `.so` files in `jniLibs` so it lands in `apk_data_file` and *can* be exec'd — which is precisely the design RikkaHub uses. [verified in the parent's source read of RikkaHub]

**But be clear-eyed about what PRoot is not:** it is same-UID, same-SELinux-domain, no kernel isolation, and its "root" is a fake. Every file in your app's private data — your database, your API keys, every other conversation — is readable by anything running inside it. **PRoot is a compatibility layer, not a sandbox.** [verified conceptually from the mechanism; the parent's report reached the same conclusion]

### 6.3 Apps that ship a compiler in the APK and run it

| App | Needs root? | Mechanism | Evidence quality |
|---|---|---|---|
| **Termux** | No | `targetSdkVersion=28` + full userland in app data; exec is permitted because of the `untrusted_app_27` exemption | [verified] — gradle.properties + sepolicy |
| **RikkaHub** | No | PRoot (`libproot_exec.so` + `libproot_loader.so` in `jniLibs`), rootfs downloaded as `.tar.xz` at runtime, Termux-style PTY | [verified] — the parent's source read; corroborated here by the ptrace/mmap policy evidence |
| **TaskerAppFactory** | No | Binaries built by `termux-packages`, shipped as APK native libs (`lib*.so`), symlinked from `nativeLibraryDir` into a rootfs under `getFilesDir()`. This is the canonical POC of the `apk_data_file` approach | [uncertain] — [agnostic-apollo write-up](https://github.com/agnostic-apollo/Android-Docs/blob/master/site/pages/en/projects/docs/apps/processes/app-data-file-execute-restrictions.md) |
| **UserLAnd / Andronix / AnLinux** | No (PRoot) | PRoot + downloaded distro rootfs | [uncertain] — **no primary source read in this pass; verify their FAQs before relying on this** |
| **AIDE, CppDroid, C4droid, Dcoder, Pydroid, QPython** | No | Historically shipped interpreters/compilers in the APK; some pre-date the API 29 restriction entirely | [uncertain] — **not verified**; these are exactly the cases where the API-29 change would have bitten, and I did not check any of them |

### 6.4 What do LLM-agent apps on Android do today for code execution?

**I could not establish this with primary sources, and I will not guess.** [uncertain]

What I can say with evidence:
- **RikkaHub is the one production LLM chat app I have verified does local code execution on Android**, via PRoot. [verified — parent's source read]
- Everything else I searched for returned either marketing pages, app-store listings, or nothing verifiable. **No primary source found** for how any other Android LLM agent app executes code.
- The industry-standard workaround for LLM code execution is a **remote sandbox** (E2B / Modal / Daytona / Piston / Judge0-style), which sidesteps every constraint in this report — at the cost of network dependency, latency, cost, and sending user code off-device. [uncertain — widely known, no primary source read]
- Android's own "run code on behalf of an AI" story is **AppFunctions / Gemini extensions**, which are about *exposing your app's typed capabilities to an assistant* — not about executing generated code. That is a different problem and does not help here. [uncertain]

---

## 7. What is actually feasible without root today

Ordered by how much I would trust it. For each: what it buys you, and **the single biggest obstacle**.

### Tier 1 — real boundaries, ship-able

**7.1 `isolatedProcess` service + FD/bytes-over-Binder protocol.**
A genuine UID boundary, zero permissions, no network, and no ability to open your app's private files. Android's only sanctioned real sandbox.
→ **Biggest obstacle: it cannot see the workspace.** You must proxy every file operation over Binder (or pass fds, which is the one thing I could not verify from policy — §4.4). This turns "give the agent a filesystem" into "implement a filesystem protocol", and it rules out running any off-the-shelf interpreter that expects a real filesystem.

**7.2 WASI (preview 1 or 2) under a runtime you embed, with exactly one preopened directory.**
The only mechanism that gives you *capability-scoped filesystem access* — one directory open, everything else invisible, plus per-descriptor rights, plus optional network denial, with no `exec` and no ambient authority.
→ **Biggest obstacle: the agent must produce WASM, and there is no practical on-device source→WASM compiler** (§3.4). The viable form is to ship a **prebuilt WASI `.wasm` interpreter** (CPython/Lua/QuickJS) as an asset and have the agent write *that* language. Secondary obstacle: choosing a runtime — WAMR or WasmEdge (native, needs NDK + per-ABI packaging) vs Chicory (pure Java, no NDK, but **interpreter-only on Android**).

**7.3 Build a seccomp filter into a native launcher in `jniLibs`, and `exec` the target from there.**
Kernel-enforced, inherited, cheap, and it stacks on top of Android's own allowlist. Currently the *only* way to deny network to a same-UID child, and a good way to forbid `fork`/`clone`.
→ **Biggest obstacle: it constrains syscalls, not paths.** seccomp cannot say "only this directory" — there is no path-argument filtering. It is a complement to 7.1/7.2, never a substitute.

### Tier 2 — works, but is not a sandbox

**7.4 PRoot, exactly as Termux and RikkaHub do it.**
Gives the agent a real `bash`, `apt`, `python`, `git` — a genuine Linux userland — with no root. Policy-wise it is fully legal: `ptrace` is allowlisted and `mmap(PROT_EXEC)` of app data is permitted.
→ **Biggest obstacle: it is same-UID and same-domain. Anything the agent runs can read your Room database and your plaintext API keys, and can ptrace your main process.** Calling this a sandbox is the mistake to avoid. Also: rootfs must be downloaded (not bundled), hundreds of MB, and RikkaHub is AGPL-3.0 so you can read but not copy it into a closed-source app.

**7.5 An in-process interpreter (QuickJS / Lua / LuaJ / Rhino) with a hand-written host API.**
Zero `exec`, no per-ABI packaging for the pure-Java options (LuaJ, Rhino, Chicory), no Play-policy problem, and you decide exactly which functions exist.
→ **Biggest obstacle: language-level confinement is only as strong as your discipline, and the failure mode is total.** Every bridge you expose is a hole; exposing the Java reflection bridge (`luajava`, Rhino's `Packages`) is an instant full escape. A bug in your host API is a bug in your app, with your app's UID.

**7.6 Shell out to `/system/bin/sh` and `/system/bin/toybox`.**
Free POSIX shell + Android's standard userland, inside your UID, with no shipped binaries and no `exec` of app data. Verified from sepolicy.
→ **Biggest obstacle: it is your app's UID, so it reads everything you can, and it is only available if you accept raw shell as a tool surface.** (Also: `sh /path/script.sh` works, but you can never `exec` a script you wrote, so shebang-style tooling is out.)

### Tier 3 — not possible; do not design around these

| Not possible | Why |
|---|---|
| `execve()` of a binary in `/data/data/<pkg>/files/` at targetSdk ≥ 29 | SELinux `neverallow`; no opt-out. [verified] |
| `chroot()`, `mount()`, bind mounts | **seccomp-blocked for apps — `SIGSYS`.** [verified] |
| Real namespaces (mount/pid/net/uts/ipc) | Need `CAP_SYS_ADMIN`; apps have no capabilities. [verified] |
| User namespaces | Android kernel requires `CAP_SYS_ADMIN`+`CAP_SETUID`+`CAP_SETGID`. [uncertain but plan for no] |
| cgroup-based CPU/memory caps for a child | `neverallow all_untrusted_apps cgroup:file *`. [verified] |
| Per-UID / per-process network firewall without root or VPN | No public API. [uncertain] |
| Hard per-child memory cap | Only `RLIMIT_AS` (virtual address space), a poor proxy; no cgroups. |
| `RLIMIT_NPROC` as a per-child process cap | It is per **real UID** — app-wide, not per-child. [verified] |
| Hardlinks (e.g. "install" a compiler by linking) | `neverallow all_untrusted_apps file_type:file link;`. [verified] |
| Downloading a compiler/toolchain at runtime and running it | Play policy forbids downloading executable code; also blocked by the exec rule. [verified] |
| `openat2(RESOLVE_BENEATH)`-style atomic path confinement | Not verified as available; on typical shipping kernels it is absent. [uncertain] |

### The single most important thing I would verify next, on a device

**Whether an `isolatedProcess` service can read a `ParcelFileDescriptor` for a file in your app's `filesDir`.** §4.4. Everything about the "real sandbox" design (7.1) hinges on the answer, and it is not decidable from the SELinux policy alone. A 30-line prototype — main app opens a file, `ParcelFileDescriptor` over Binder to an isolated service, isolated service tries to read it — settles it.

### The design I would actually build

Layered, because no single mechanism is sufficient:

1. **Put the workspace in `filesDir` (real POSIX, no permissions) — as the parent report already concluded.** Expose it for user browsing with a `DocumentsProvider`; never use `getExternalFilesDir` or `MANAGE_EXTERNAL_STORAGE`.
2. **For "run a small program", use WASI under an embedded runtime with exactly one preopen.** This is the only option that makes "confined to one directory" a *true* statement rather than a hopeful one. Ship a curated `.wasm` language runtime inside the APK so the agent writes a real language, not raw WASM.
3. **If you need a real Linux userland, add PRoot as a clearly-labelled "compatibility mode", not as the security model.** Say so in the UI. Treat the API-key exposure as a first-class risk: Keystore-encrypt secrets, approve shell per-invocation, and audit every command.
4. **Wrap any same-UID child in a seccomp lock-down** via a native launcher in `jniLibs` — at minimum no `socket`, no `connect`, no `fork`/`clone`.
5. **Cap resources with `setrlimit` in that launcher** (`RLIMIT_CPU`, `RLIMIT_FSIZE`, `RLIMIT_NOFILE`, cautiously `RLIMIT_AS`) plus a wall-clock timeout and output byte caps. **Never `RLIMIT_NPROC`.**
6. **Move the whole thing into an `isolatedProcess` when the fd-passing prototype succeeds.**

---

## 8. What I could not verify (consolidated)

1. **`isolatedProcess` + passed `ParcelFileDescriptor` for `app_data_file`** — the pivotal unknown (§4.4). Policy reading says no; kernel fd semantics may say yes.
2. **User namespaces on current Android** — the commit I found is real but undated and I could not confirm it survives in `android16-*`. Assumed unavailable.
3. **APK size cost of Chaquopy, CPython, QuickJS, Lua, LuaJ, JSC and V8.** No primary source found for any of them. I deliberately did not invent numbers. **Measure locally.**
4. **Chaquopy's current commercial terms.** The repository LICENSE is MIT, but I could not reach a current licence/pricing page, so I cannot rule out a separate commercial tier.
5. **Wasmer's Android support** — no primary source found.
6. **Whether a prebuilt WASI build of CPython/Lua/QuickJS exists, its size, and its licence.** I recommended it as an architecture but verified no specific artifact.
7. **On-device source→WASM compilers** — `wasm-clang` and similar: primary sources unfetchable, so no size or practicality verdict. `c4wa` exists and is a C-subset transpiler, not a compiler.
8. **Preopen path-resolution correctness per runtime.** WASI 0.2 states the requirement normatively; I did not audit any runtime's implementation or CVE history.
9. **Whether `openat2`/`RESOLVE_BENEATH` is usable on Android 15/16** — affects any TOCTOU-resistant path confinement.
10. **UserLAnd / Andronix / AnLinux / AIDE / CppDroid / Pydroid and every other "compiler in an APK" app** — not verified in this pass. Their FAQs are the next thing to read if that precedent matters to you.
11. **How any Android LLM-agent app other than RikkaHub executes code.** No primary source found.
12. **Current Google Play target-API-level policy** (the exact minimum targetSdk required today) — not read; I inferred targetSdk 28 is unpublishable.
13. **`bionic` lacking `openpty`/`forkpty`** — plausible and consistent with every Android terminal shipping its own, but I did not read bionic's headers.
14. **Android background execution limits / App Standby Buckets / `getMemoryClass()` pages** — cited from memory of the standard behaviour, not re-fetched in this pass.
