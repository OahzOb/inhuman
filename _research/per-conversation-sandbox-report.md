# Per-Conversation Sandboxed File Workspace on Android (minSdk 34 / targetSdk 36)

Technical evaluation for an LLM chatbot app where **both an AI agent (via tool calls) and the human user** read/write files.

**Method / evidence note.** I was able to read RikkaHub's actual source tree (it is AGPL-3.0 and public), which let me *verify* rather than infer answers to questions 2 and 4. `developer.android.com` was unreachable from this environment (DNS/network failure for that host); I used the `developer.android.google.cn` mirror, `source.android.com`, Google Play policy pages, the Android issue tracker, and Termux upstream docs instead. Claims I could not read end-to-end on a primary page are marked **[unverified]** or **[inferred]**.

---

## 1. Where should per-conversation directories live?

| Option | Permission on API 34/35/36 | Scoped-storage compliant | User browsable in stock Files app | Survives uninstall | In Auto Backup | Real POSIX path (`java.io.File`) |
|---|---|---|---|---|---|---|
| `context.filesDir` | **None** | **Yes** | No (by default) — **but yes via a custom DocumentsProvider** | No | Yes by default (25 MB quota; excludable) | **Yes** |
| `context.noBackupFilesDir` | None | Yes | No (same as above) | No | Never | Yes |
| `context.cacheDir` | None | Yes | No | No | Never | Yes |
| `getExternalFilesDir()` | None | Yes | **No** on API 30+ | No | Excluded **[unverified]** | Yes, but on FUSE — **no symlinks/hardlinks/unix perms** |
| MediaStore (`Downloads`) | **None** for your own inserts | **Yes** (this *is* the scoped-storage API) | **Yes** (Downloads) | **Yes** | Typically no | **No** — `content://` only |
| SAF tree (`ACTION_OPEN_DOCUMENT_TREE`) | None (user grant) | Yes | Yes (it *is* the user's folder) | Yes | Depends on folder | **No** — `content://` only, one Binder IPC per op |
| `MANAGE_EXTERNAL_STORAGE` | `MANAGE_EXTERNAL_STORAGE` + user toggle in Settings | **No — it is an escape hatch** | Yes (everything) | Yes | No | Yes |

### The two findings that actually decide this

**(a) SAF and MediaStore cannot host an agent workspace, because they are not filesystems.**
Both give you `content://` URIs, not paths. Every agent file tool (`read_file`, `edit_file`, `glob`, `grep`), every native binary, and anything like `git`/`python`/`apt` needs a real POSIX path. You cannot `bind`-mount a `content://` URI. `DocumentFile` is also one Binder IPC per operation — `DocumentFile.listFiles()` on a 5000-entry directory is seconds, not milliseconds. RikkaHub's own `WorkspaceManager` is built entirely on `java.io.File` plus `java.nio.file.Files` (`Files.walk`) — it never touches `DocumentFile` for the workspace itself. That is the correct architecture.

**(b) `/sdcard` is FUSE; `/data/data` is a real filesystem.**
Since Android 11, `/sdcard` (including `getExternalFilesDir()`) is served by a FUSE daemon. It does **not** support symlinks, hardlinks, or Unix permission bits. A Linux rootfs, `dpkg`/`apt`, and PRoot all require those. So `getExternalFilesDir()` is disqualified for a sandboxed workspace for reasons *beyond* visibility. **[inferred from FUSE semantics + the AOSP scoped-storage design; I did not read a single page stating it in these words]**

### Per-option notes

- **`filesDir`** — zero permissions, always readable/writable, survives nothing but is private. It is the only location that is simultaneously (i) a real POSIX filesystem, (ii) requirable by native code, (iii) Play-safe. The one problem — "the user can't see it" — is solvable, and RikkaHub solves it (§2).
- **`getExternalFilesDir()`** — historically the "let the user see it" trick. On Android 11+ `Android/data/` is blocked to third-party file managers and to DocumentsUI; the user cannot browse it on a stock device. It is also deleted on uninstall, and it is FUSE. Rejected.
- **MediaStore** — right tool for *export*, wrong tool for a *workspace*. It is a flat media database, not a hierarchy; ownership is by package; you cannot reliably `File`-write into it. Use `MediaStore.Downloads` + `IS_PENDING` for a one-way "save to Downloads" affordance only.
- **SAF** — right tool for *user-chosen import/export locations* and optionally an extra "mounted" folder. Also note the user's grant can be silently invalidated (folder moved/deleted, user revokes in Settings, app data cleared), so it can never be the source of truth. Persisted grants are capped at **128 per app** on Android 11+ ([issuetracker 149315521](https://issuetracker.google.com/issues/149315521)) — another reason not to allocate one grant per conversation. Also, `ACTION_OPEN_DOCUMENT_TREE` on API 30+ **cannot** select `Android/data`, `Android/obb`, the internal-storage root, or the `Download` directory root **[unverified — widely reported, I did not read the primary page]**.
- **`MANAGE_EXTERNAL_STORAGE`** — do not use. It is a declared permission that Google Play gates behind a written justification, and the permitted use cases are file managers, backup/restore, anti-virus, document management, on-device file search, disk encryption, and device-to-device migration ([Play policy](https://support.google.com/googleplay/android-developer/answer/10467955)). An LLM chatbot does not qualify; shipping it is a Play rejection waiting to happen. Note also that `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` are effectively no-ops from API 29/33 onward. RikkaHub declares `WRITE_EXTERNAL_STORAGE` with `android:maxSdkVersion="28"` and **no** `MANAGE_EXTERNAL_STORAGE` (verified in its manifest) — that is the correct pattern.

### Recommendation for storing agent/user working files

**`context.filesDir` as the single source of truth.** Layout:

```
filesDir/                                  <- real POSIX, ext4/f2fs
  conversations/<conversationId>/
    workspace/                             <- agent + user working files
    tmp/                                   <- scratch; wiped on app start
noBackupFilesDir/                          <- or filesDir + backup exclusion
  workspaces/<workspaceId>/
    linux/                                 <- rootfs (if you add a Linux env)
    tmp/                                   <- PRoot temp (PROOT_TMP_DIR)
```

- One directory per conversation (or per *workspace*, with a workspace↔assistant binding if you copy RikkaHub's model — its docs say "one assistant can bind one workspace").
- Use a **stable opaque id** (UUID) as the on-disk directory name and keep the human-readable name in the DB. RikkaHub does exactly this: on-disk `root` matches `[A-Za-z0-9._-]+` and is the workspace UUID; the display name comes from a Room entity.
- Keep the rootfs and tmp **out of Auto Backup**, and mind the 25 MB Auto Backup quota — a rootfs will blow through it instantly. RikkaHub sets `allowBackup="true"` with both `@xml/backup_rules` and `@xml/data_extraction_rules` (verified).
- Expose it read/write through a custom `DocumentsProvider`, and offer SAF/FileProvider/MediaStore only as *egress* paths.

---

## 2. How the USER accesses and manages those files

### 2a. Is a custom `DocumentsProvider` required? — **Yes. Verified, not inferred.**

To make app-private files browsable in the stock Files app without root and without `MANAGE_EXTERNAL_STORAGE`, a custom `DocumentsProvider` is the **only** mechanism. There is no manifest flag, no `getExternalFilesDir` trick, and `requestLegacyExternalStorage` is ignored on API 30+. I checked every alternative listed in the brief and none works.

**And this is exactly how RikkaHub's "RikkaHub disk" works.** The mechanism is *not* SAF-only and *not* guesswork — it is a real `DocumentsProvider`. From `app/src/main/AndroidManifest.xml`:

```xml
<provider
  android:name=".data.provider.WorkspaceDocumentsProvider"
  android:authorities="${applicationId}.documents"
  android:exported="true"
  android:grantUriPermissions="true"
  android:permission="android.permission.MANAGE_DOCUMENTS">
  <intent-filter>
    <action android:name="android.content.action.DOCUMENTS_PROVIDER" />
  </intent-filter>
</provider>
```

The disk **name** the user sees comes from `Root.COLUMN_TITLE`, which RikkaHub sets to `ctx.getString(R.string.app_name)` — i.e. literally "RikkaHub". The disk **icon** is `Root.COLUMN_ICON = R.mipmap.ic_launcher`. The root flags are `Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_CREATE`. That fully explains the docs' claim, and the docs' "folders named after each workspace" corresponds to `queryRoots` returning one root whose children are the workspace directories.

Corroborating open bug showing how fiddly this is: [rikkahub#1728 — `WorkspaceDocumentsProvider` treats non-existent files as existing](https://github.com/rikkahub/rikkahub/issues/1728).

### 2b. What a DocumentsProvider must implement

Required: `queryRoots`, `queryDocument`, `queryChildDocuments`, `openDocument`. RikkaHub's full set (346 lines of Kotlin, verified):

`onCreate`, `queryRoots`, `queryDocument`, `queryChildDocuments`, `openDocument`, `createDocument`, `deleteDocument`, `renameDocument`, `copyDocument`, `moveDocument`, `getDocumentType`, `isChildDocument`, plus `notifyChange` calls, a `documentId` codec, and projection defaults.

**It runs in the same APK** — a separate app is not required. `android:permission="android.permission.MANAGE_DOCUMENTS"` restricts callers to the system/DocumentsUI, which is the documented pattern.

**Important design choice**: RikkaHub uses its own `documentId` scheme rather than encoding real paths directly:
- root → `"root"`
- workspace root → `ws/<workspaceUuid>`
- file → `ws/<workspaceUuid>/<path relative to files/>`

This is what lets it rename/move workspaces without the ids becoming stale, and it avoids leaking host paths into `content://` URIs.

### 2c. Cost and pitfalls (real-world evidence)

**Cost: ~300–400 lines.** But the pitfalls are the real cost:

- **Threading.** Provider methods are called on Binder thread-pool threads, *not* the main thread, and they must not block for long. RikkaHub's `queryChildDocuments` calls `dir.listFiles()` **synchronously** and builds a full `MatrixCursor` — no paging via `DocumentsContract.EXTRA_LOADING`. A directory with tens of thousands of files (very likely once an agent runs `git clone` or `npm install`) will make DocumentsUI hang or ANR. **If you build this, add paging.** This is the single biggest scalability trap.
- **Cursor column completeness.** You must return exactly the columns the caller asked for (or your `DEFAULT_*_PROJECTION`), and every row must have every column, or DocumentsUI crashes or shows blanks. RikkaHub adds `Document.COLUMN_DOCUMENT_ID`, `DISPLAY_NAME`, `MIME_TYPE`, `FLAGS`, `SIZE`, `LAST_MODIFIED`.
- **Root not appearing** is the classic first bug. Usually a missing/incorrect `FLAG_LOCAL_ONLY`, an empty `COLUMN_ROOT_ID`, a missing `COLUMN_DOCUMENT_ID`, an `android:exported="false"`, or a missing/wrong permission.
- **`Root.FLAG_SIDEBAAR`** — the AOSP constant name is misspelled; if you want sidebar placement, that is the (correct) spelling to use. RikkaHub omits it.
- **Cache invalidation.** You must call `ContentResolver.notifyChange` on the *child-documents* URI after every mutation, or the Files app shows stale listings. RikkaHub builds it as `DocumentsContract.buildChildDocumentsUri("<pkg>.documents", parentDocumentId)` — note it hardcodes the authority string rather than reading it from the provider, which is slightly brittle if you ever rename the authority.
- **No thumbnails.** `openDocumentThumbnail` is not implemented, so you get generic icons. Acceptable.
- **`openDocument`** is the easy part: `ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))` bridges `content://` → your app-private `File` in one line.
- **Two-way risk:** `FLAG_SUPPORTS_CREATE` + per-document `FLAG_SUPPORTS_WRITE/DELETE/RENAME/COPY/MOVE` means the Files app (and anything the user grants) can **write into the agent's workspace**. That is the feature, but see §5 — it is also an injection vector.

### 2d. SAF `ACTION_OPEN_DOCUMENT_TREE` and persisting the grant

Yes, the grant is persistable:
1. Launch `ActivityResultContracts.OpenDocumentTree()` (→ `ACTION_OPEN_DOCUMENT_TREE`).
2. On the returned tree URI, call `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)`. The launch intent must carry `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`.
3. The grant then survives process death and reboot, and you enumerate with `DocumentFile.fromTreeUri` / `DocumentsContract.buildChildDocumentsUriUsingTree`.

Caveats: **128 persisted grants per app** on Android 11+ ([issuetracker 149315521](https://issuetracker.google.com/issues/149315521)); the user can revoke at Settings → Apps → Special access → Allowed URI permissions; the folder can be moved/deleted, so every access must be wrapped in `try/catch` for `SecurityException` and `FileNotFoundException`. Files you create via `createFile` do appear in the user's file manager — that is the point of SAF. But it is a `content://` world: this is a good *export target* and a mediocre *workspace*.

### 2e. MediaStore Downloads for user-visible export

Insert into `MediaStore.Downloads.EXTERNAL_CONTENT_URI` with `RELATIVE_PATH` (e.g. `Download/<AppName>/`), `DISPLAY_NAME`, `MIME_TYPE`, `IS_PENDING=1`; write the stream; then set `IS_PENDING=0`. **No permission required on API 29+ for your own inserts.** The file appears in the Downloads collection and in Files/Downloads apps. Good for a one-tap "save to Downloads". Not a workspace.

### 2f. Share intents

`FileProvider` (`${applicationId}.fileprovider`, `android:exported="false"`, `android:grantUriPermissions="true"`, `res/xml/file_paths.xml`) + `ACTION_SEND` with `EXTRA_STREAM` and `FLAG_GRANT_READ_URI_PERMISSION` — plus `clipData` for OEM robustness. RikkaHub declares exactly this provider alongside its DocumentsProvider (verified). Use it for "share file to another app". Never use `file://` (→ `FileUriExposedException` on API 24+).

---

## 3. Upload / download

### Receiving files into the workspace (upload)

| Mechanism | Contract | Durability | Use for |
|---|---|---|---|
| `ACTION_OPEN_DOCUMENT` | `ActivityResultContracts.OpenDocument()` / `OpenMultipleDocuments()` | **Durable, persistable** | "Attach a file the agent should keep working on" |
| `ACTION_GET_CONTENT` | `ActivityResultContracts.GetContent()` | One-shot, **not persistable** | Transient "paste this in" |
| `ACTION_SEND` / `ACTION_SEND_MULTIPLE` | manifest intent-filter on your activity | One-shot | "Share to <App>" from another app |
| `PickVisualMedia` | `ActivityResultContracts.PickVisualMedia` | One-shot | Images only, no permission needed |

**Best practice — copy immediately.** Do not treat the incoming `content://` URI as the source of truth: the grant can be revoked, the source can be deleted, and native tools need real paths. RikkaHub's API is precisely this shape:

```kotlin
fun importFile(root: String, destinationPath: String, area: WorkspaceStorageArea,
               fileName: String, inputStream: InputStream): WorkspaceFileEntry
```

— it streams straight into the workspace and returns the real entry. Copy that design.

For `ACTION_SEND`, read `intent.clipData` first (it carries multiple items and the grant flags correctly on modern Android) and fall back to `getParcelableExtra(EXTRA_STREAM)`. Call `takePersistableUriPermission` only if the sender set `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`; otherwise copy now.

Note RikkaHub only registers `ACTION_SEND` for `text/plain` (verified) — a deliberate, narrow surface. Don't register `*/*` unless you need it.

### Exporting files out of the workspace (download)

| Mechanism | Permission | Result | Best for |
|---|---|---|---|
| `ACTION_CREATE_DOCUMENT` | none | user picks name+location; you get a writable `content://` | **"Save a copy where I want"** — the default |
| `FileProvider` + `ACTION_SEND` | none (provider grant) | recipient app gets a temporary read grant; **no copy** | **"Share to another app"** |
| `MediaStore.Downloads` insert | none | durable public copy in Downloads | **"Quick save to Downloads"**, no picker |

**FileProvider vs MediaStore tradeoff:**
- **FileProvider** = zero-copy, single source of truth, revocable, temporary grant scoped to the receiving task. Downside: the recipient must read it promptly, and it only works for `content://`-aware apps. **Prefer this for sharing.**
- **MediaStore** = a durable *duplicate* in public storage. Downside: divergence between workspace and export, name-collision handling, and you must clean up. **Never auto-insert** — make it an explicit user action.

Call `intent.addFlags(FLAG_GRANT_READ_URI_PERMISSION)` on the *intent*, and also set `clipData`; do **not** add `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` for a share.

---

## 4. RikkaHub's "Workspace" — how it actually works (VERIFIED from source)

The docs promise "a lightweight Linux environment" with a rootfs, `apt`, and a terminal ([docs (zh)](https://docs.rikka-ai.com/zh/extensions/workspace), [docs (en)](https://docs.rikka-ai.com/extensions/workspace)). It is **PRoot** — and I confirmed it from the repository, not from inference.

### Evidence

RikkaHub has a dedicated Gradle module `workspace/` (namespace `me.rerere.workspace`) containing:

```
workspace/src/main/jniLibs/arm64-v8a/libproot_exec.so    267,752 bytes
workspace/src/main/jniLibs/arm64-v8a/libproot_loader.so   18,136 bytes
workspace/src/main/jniLibs/x86_64/libproot_exec.so       282,968 bytes
workspace/src/main/jniLibs/x86_64/libproot_loader.so      18,352 bytes
workspace/src/main/cpp/{CMakeLists.txt, termux_pty.cpp, workspace.cpp}
workspace/src/main/java/me/rerere/workspace/{ProotShellRunner,RootfsInstaller,RootfsPatcher,
    WorkspaceFileSystem,WorkspaceManager,WorkspaceShellRunner,Workspace}.kt
```

And the invocation, from `ProotShellRunner.kt`:

```
libproot_exec.so --root-id --link2symlink --kill-on-exit
  -r <filesDir>/<ws>/linux                # rootfs
  -w /workspace/<cwd>
  -b <filesDir>/<ws>/files:/workspace     # the visible workspace
  [-b <extra bind mounts>]
  -b /dev -b /proc -b /sys
  /usr/bin/env -i HOME=/root PATH=... TERM=xterm-256color LANG=C.UTF-8 CI=true NO_COLOR=1 PAGER=cat
  /bin/bash -l -c 'cd -- "$1" && eval "$2"' rikkahub <cwd> <command>
```

with `PROOT_LOADER=<nativeLibraryDir>/libproot_loader.so`, `PROOT_TMP_DIR`, `TMPDIR` set, and `PROOT_NO_SECCOMP=1` only when a "shell compatibility mode" flag is on (a known PRoot knob for devices/kernels where the seccomp fast path misbehaves).

**So: it is PRoot** ([termux/proot](https://github.com/termux/proot), [proot-me.github.io](https://proot-me.github.io/)) — a `ptrace`-based syscall translator. It is **not** a container, **not** a VM, **not** a real `chroot`, and **not** a custom userspace. `termux_pty.cpp` shows they reused Termux's PTY/terminal implementation for the in-app terminal.

### The exec restriction — and why proot ships as a `.so`

This is the crux, and it is well documented upstream. From Termux's own [NLnet NGI Mobifree grant announcement](https://termux.dev/en/posts/general/2024/11/11/termux-selected-for-nlnet-ngi-mobifree-grant.html):

> Android restricts execution based on SeLinux policies and contexts assigned to app processes (`untrusted_app*`) and their files under app data directory (`app_data_file`). If a file exists in the private app data directory `/data/data/<package_name>` … Android does not allow execution if app uses `targetSdkVersion >= 29` … **Google wants only files that exist inside app APKs to be executable.**
>
> The solution that is approved by Android … is for app developers to **add any executable files to the APK native library directory**, which are then extracted to system controlled `/data/app/*<package_name>/lib/<arch>` path when app is installed. Files under this directory have a different SeLinux contexts **`apk_data_file` for which execution is allowed.** However, **this is a single level directory and nested directories required by a rootfs are not allowed**, so files inside it need to be symlinked into the rootfs …

That is exactly RikkaHub's design: PRoot and its loader are shipped as `libproot_exec.so` / `libproot_loader.so` so they land in `ApplicationInfo.nativeLibraryDir` (`apk_data_file`, executable) — while the *guest* Linux binaries stay in `app_data_file` and are **never `execve`'d by the kernel**. Instead PRoot's loader (`PROOT_LOADER`) maps them. **[The "maps them, never execve's them" part is my inference from the code plus PRoot's documented loader design — I did not find a public write-up stating it, and I could not verify whether `mmap(PROT_EXEC)` of an `app_data_file` is itself permitted.]**

Additional confirmed consequences of that upstream doc:
- Termux historically worked around this by pinning `targetSdkVersion = 28`; this is **not available to a Play-distributed app** and Android already bumped `PLATFORM_MIN_SUPPORTED_TARGET_SDK_VERSION` to 28 in Android 14 with plans for 29. So a 2026 app at targetSdk 36 **must** use the native-library approach.
- The `system_linker_exec` hack exists but is "not compliant with Google PlayStore policies" and expected to be patched. Avoid.
- Upstream Termux is working on an MIT-licensed **`termux-core`** library explicitly so that "external projects and commercial entities" can embed a Termux-ish environment — currently integrating Termux "requires a full fork of Termux app and manual integration." **This is the library to watch if you want this feature without reimplementing it.**

See also: [termux-app#1072](https://github.com/termux/termux-app/issues/1072) (no exec from data folder at targetAPI ≥ Q), [termux-app#2155](https://github.com/termux/termux-app/issues/2155) (W^X), [termux-play-store/termux-exec](https://github.com/termux-play-store/termux-exec) (the `execve` wrapper), and [agnostic-apollo's App Data File Execute Restrictions write-up](https://github.com/agnostic-apollo/Android-Docs/blob/master/site/pages/en/projects/docs/apps/processes/app-data-file-execute-restrictions.md).

### Where the rootfs comes from — not the APK

Verified: `RootfsInstaller.installRootfs(url, onProgress)` **downloads over `HttpURLConnection` at runtime** and streams a **`.tar.xz`** through `org.tukaani.xz.XZInputStream` (the `libs.xz` dependency in `workspace/build.gradle.kts` confirms). The URL is a **parameter passed from the UI** (`WorkspaceDetailVM.installRootfs(url: String)`), and there is **no rootfs tarball anywhere in the repository**. So:

- **The rootfs is not bundled in the APK.** The Play AAB/APK size limits are therefore not the binding constraint for the rootfs itself. (I could not determine where the app's *preset* distro URLs point, if it offers presets — GitHub code search requires auth, so the preset list in the Compose UI was out of reach.)
- Extraction is written by hand and does the right things: it handles PAX/LONG_NAME/LONG_LINK tar extensions, calls `targetDir.safeResolve(header.name)` (zip-slip guard), creates symlinks and hardlinks explicitly, applies mode bits, and **skips `TarEntryType.OTHER`** (device nodes/FIFOs cannot be created unprivileged).
- App binary-size cost of PRoot itself is tiny: **~286 KB for arm64 (exec + loader)**, ~301 KB for x86_64. Negligible next to the app's other native libs (RikkaHub also ships `libsimple.so` at 9.1 MB and `libmupdf_java.so` at 9.4 MB per ABI).

### Other implementation details worth stealing

- **`RootfsPatcher`** runs before each shell invocation and synthesizes the files Android doesn't provide: `/etc/resolv.conf` (from the guest's existing non-local nameservers, else `DEFAULT_DNS_SERVERS`, capped at 3, plus `options edns0 trust-ad`), `/etc/hosts`, `/etc/hostname`, locale files, and `/etc/group` — the group file is built from the app's **real supplementary group ids read from `/proc/self/status`**. It also ensures `/tmp` and `/var/tmp` exist. **If you build this, budget for this patch step; DNS in particular will not work out of the box, because Android resolves DNS through `netd`, not `/etc/resolv.conf`.**
- **`--link2symlink`** makes PRoot emulate hardlinks with symlinks plus `.l2s.*` sidecar files. RikkaHub consequently **filters `.l2s.` out of every file listing** (`WorkspaceFileSystem.list/glob/grep` and `WorkspaceDocumentsProvider.queryChildDocuments`) so users and the LLM never see PRoot's internals. Nice detail; easy to miss.
- `/workspace` inside the guest = `<filesDir>/<ws>/files` on the host. `/dev`, `/proc`, `/sys` are bind-mounted through, but `resolveRootfsPath` **explicitly refuses** to read them as files, throwing *"is a kernel filesystem and cannot be read as a file, use workspace_shell instead"* instead of silently falling back to an unreadable physical path. **Good hardening pattern.**
- Shell commands are passed as **positional arguments** and evaluated once (`/bin/bash -l -c 'cd -- "$1" && eval "$2"' rikkahub <cwd> <cmd>`) specifically to avoid quoting/escaping bugs. Deliberate and correct.
- Command timeout default is **30 s** (`DEFAULT_COMMAND_TIMEOUT_MS = 30_000`), with stdin support and `redirectErrorStream(false)`.
- `cleanupAllTempDirs()` wipes the PRoot temp dir plus the rootfs's `/tmp` and `/var/tmp`.

### Real constraints summary (Q4)

1. **No root, no kernel isolation.** PRoot is `ptrace` path translation. `--root-id` fakes uid 0 *inside* the guest; the host process is still the app's `untrusted_app` uid.
2. **`execve` from app data is denied at targetSdk ≥ 29**; you must ship executables as `.so` in `jniLibs` → `nativeLibraryDir` (`apk_data_file`). Single flat directory only, hence the separate loader.
3. **W^X / execmem** — Android 10+ forbids writable-and-executable app code. **[unverified]** whether `mmap(PROT_EXEC)` of guest binaries under `app_data_file` is permitted, or whether PRoot's loader relies on a path that remains allowed. This is the highest-risk unknown if you reimplement rather than reuse.
4. **Rootfs must be downloaded**, not bundled (Android's own docs-driven reasoning + RikkaHub's implementation agree). Budget for a download UX, checksum verification (RikkaHub's installer does **not** appear to verify a signature/hash — **[inferred from reading `RootfsInstaller`; I saw `HTTP` status checks and progress, no hash check]**), and hundreds of MB of disk.
5. **No nested directories in `nativeLibraryDir`**, so a full `apt`-installable userland cannot live there; only the entry binaries can.
6. **Existing OSS**: [termux/proot](https://github.com/termux/proot), [termux/proot-distro](https://github.com/termux/proot-distro), [termux-packages](https://github.com/termux/termux-packages), [termux-play-store/termux-exec](https://github.com/termux-play-store/termux-exec), and the forthcoming MIT-licensed **`termux-core`**. Termux itself is no longer on Play Store (targetSdk 28 incompatibility). RikkaHub is, as far as I found, the only production LLM chat app that ships this, and it is AGPL-3.0 — **so you can read it, but copying it into a closed-source app is a licensing problem.**

---

## 5. Security

### 5.1 The headline risk: the "sandbox" is not a sandbox

PRoot runs the guest as **the same UID as your app**. Android's isolation boundary is the UID, not the PRoot rootfs. Consequences:

- **Any code the agent runs in the guest can read your entire app data directory**: the Room/SQLDelight database holding conversation history, DataStore/SharedPreferences holding **LLM API keys**, and *every other conversation's* files. The docs' claim that "each workspace runs independently" means *separate directory + separate rootfs* — it is organizational, not a security boundary. PRoot's own documentation does not present it as a security tool. **[verified that all workspaces live under one app-private base dir and share the app UID; the "so the agent can read the app's DB and API keys" conclusion follows directly.]**
- **Same-UID `ptrace`.** A guest process is same-UID, so it can attach to your main process and read its memory — including decrypted API keys and the in-memory conversation. Android permits same-UID `ptrace` unless you defend against it. **[inferred from Linux same-UID ptrace semantics; I did not verify Android 15/16-specific ptrace hardening for `untrusted_app`. I do not know whether Android 14+ restricts this further — flagging as a gap.]**
- **The guest has network access** (your `INTERNET` permission), so prompt injection + shell = exfiltration.

**Hardening that actually helps** (in rough order of leverage):
1. **Do not store LLM API keys as plaintext files.** Use Android Keystore-backed encryption so the secret is never at rest in a readable file. This does not stop in-memory/ptrace attacks but removes the trivial `cat` path.
2. **Run the Linux environment in a separate, isolated context.** Options: a separate app package with its own UID, or `android:isolatedProcess="true"` (a special UID with no permissions), communicating over Binder. This is Android's actual sanctioned sandbox. It is a real architectural cost but it is the only thing that makes the word "sandbox" honest.
3. **Consider not shipping shell at all.** File tools confined to the conversation directory give 90% of the value at a fraction of the risk. RikkaHub gates tool execution behind per-tool **user approval** toggles — adopt that.
4. **Egress control / audit.** Log every tool call and every file mutation, show it to the user, and consider gating network use from the guest.

### 5.2 Path traversal — the standard pattern, and its hole

The correct check (used in **both** of RikkaHub's file-access paths — `WorkspaceFileSystem.resolvePath` and `WorkspaceDocumentsProvider.resolveFile`, verified):

```kotlin
require(!normalized.contains('\u0000')) { "Path contains invalid character" }
val rootFile = root.canonicalFile
val target = File(rootFile, normalized).canonicalFile
require(target.path == rootFile.path || target.path.startsWith(rootFile.path + File.separator)) {
    "Path escapes workspace root: $path"
}
```

Key points:
- **`canonicalFile` (not `absoluteFile`, not `normalize()`)** — canonicalization resolves **symlinks**, so this single check defeats both `../` traversal *and* symlink escape. `Path.normalize()` alone does **not** resolve symlinks and is insufficient. `File.getCanonicalPath()` on a non-existent path still lexically normalizes, which is what you want for a subsequent create.
- Reject `\u0000`.
- Compare against the **canonical root**, and append `File.separator` to the prefix so `/foo/bar` cannot be matched by a root of `/foo/ba`.
- Also guard directory-into-itself for copy/move — RikkaHub does (`require(!dest.canonicalPath.startsWith(srcFile.canonicalPath + File.separator))`).
- Sanitize user/agent-supplied display names: RikkaHub replaces `/` with `_` in `createDocument`/`renameDocument`.

**The residual hole is TOCTOU.** The check and the subsequent `open()`/`delete()` are separate syscalls. The agent has concurrent shell access **and can create symlinks** (the workspace is on ext4/f2fs, which supports them). So between the canonicalization check and the file operation, a path component can be swapped for a symlink pointing at `../` or at `/data/data/<pkg>/databases/`. Mitigations:
- `android.system.Os.open(path, O_RDONLY or O_NOFOLLOW)` for the final component, and `O_DIRECTORY or O_NOFOLLOW` when walking; better, walk component-by-component with `openat` from a directory fd you opened once.
- Re-verify with `Files.readAttributes(path, LinkOption.NOFOLLOW_LINKS)` and `Files.isSymbolicLink` immediately before use.
- Android exposes **no `openat2(RESOLVE_BENEATH)`** on typical shipping kernels, so you cannot solve this in one syscall. **[inferred — I did not verify kernel/API availability of `openat2` on Android 15/16.]**
- Practically: accept the residual risk for read tools, but treat **writes and deletes** as the ones worth the atomic handling, and never let a tool path be interpreted by a shell.

### 5.3 Size limits, quotas, disk exhaustion

RikkaHub's `WorkspaceConfig` (verified to exist) already carries `maxReadBytes`, `maxWriteBytes`, `maxListEntries`, and `maxSearchResults`, enforced in `readText` (`require(file.length() <= config.maxReadBytes)`), `writeText` (`require(bytes.size <= config.maxWriteBytes)`), `list` (`take(config.maxListEntries)`), and `grep` (skips files over `maxReadBytes`). Copy that shape, and add what it lacks:

- A **total workspace quota** and a **max file count**.
- A **free-space precheck** with `StatFs(filesDir.path)` before large writes and before archive extraction.
- **Decompression-bomb defense**: the agent will run `unzip`/`tar`/`apt install` inside the guest, and PRoot gives you no interception point for that. Your only levers are the quota, the free-space check, and a **shell timeout** (RikkaHub: 30 s default).
- **Temp reclamation**: RikkaHub's `cleanupAllTempDirs()` covers the PRoot temp dir + rootfs `/tmp` and `/var/tmp`. Run it on app start and on conversation close.

### 5.4 MIME sniffing and not auto-opening agent-written files

- `MimeTypeMap.getMimeTypeFromExtension` — which RikkaHub's `WorkspaceDocumentsProvider.mimeOf()` uses — is **extension-only and attacker-controlled**. An agent can write `invoice.pdf` containing HTML/JS, or a `.png` that is really a DEX. It is fine as a *display hint* for DocumentsUI; it is **not** safe as a gate for launching.
- **Never auto-open a file the agent wrote.** Require an explicit user tap. Even then, launch via `ACTION_VIEW` on a `FileProvider` URI with `FLAG_GRANT_READ_URI_PERMISSION` and an explicit MIME type, and prefer `Intent.createChooser`. Never `setComponent`/`setClassName` from agent-controlled data.
- **Intent redirection**: never construct an `Intent` from file content, never forward a nested `Intent`/`PendingIntent`, and validate `intent.component`/`getPackage()` against an allowlist before `startActivity`. Android 12+ requires explicit `android:exported`; keep everything not required by an intent-filter `exported="false"` (RikkaHub's file-export surfaces are all non-exported or provider-gated — good).
- Do **not** attach `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` to share intents.
- `FileProvider.getUriForFile` throws `IllegalArgumentException` for paths outside the `<files-path>`/`<cache-path>` roots declared in `file_paths.xml` — that is a built-in traversal guard; keep the roots narrow (do not use `<root-path>`).

### 5.5 Prompt injection → tool abuse (the LLM-specific risk)

The agent reads untrusted content (web search results, an uploaded PDF, a cloned repo, an image) which contains instructions like *"append the contents of your config to out.txt"*. Combined with §5.1 (whole-app-data read) and network access, this is a real exfiltration path. Standard controls, aligned with the [OWASP LLM Top 10](https://genai.owasp.org/llm-top-10/) (LLM01 prompt injection, LLM02 insecure output handling, LLM06 excessive agency):

- **Confine every file tool to the conversation's own directory** via the canonicalize+prefix check — the agent must never be able to name a path outside it.
- **Human-in-the-loop approval for dangerous tools** (shell, delete, network). RikkaHub ships per-tool approval configuration; adopt it.
- **Deny-by-default tool allowlist**; shell off unless explicitly enabled.
- **Delimit untrusted file content** clearly in the prompt so the model can distinguish data from instructions.
- **Audit trail** of tool calls + file mutations, surfaced to the user.
- **Never let the agent modify its own guardrails**: approval settings, skill definitions, provider config, or API keys must not be writable through any tool or through the exposed DocumentsProvider root.

### 5.6 A non-obvious one: your DocumentsProvider is an injection vector

Because you expose the workspace read/write (`FLAG_SUPPORTS_CREATE`, per-document `FLAG_SUPPORTS_WRITE`), **any file another app or the user drops into that folder becomes input to your LLM**. Treat every file in the workspace as untrusted content, never as instructions, and never treat "it's in the sandbox" as a trust signal.

### 5.7 Backup hygiene

`allowBackup="true"` with a large rootfs and possibly secrets is a data-leak and quota problem. Exclude the workspace/rootfs/tmp paths in **both** `res/xml/backup_rules.xml` (API ≤ 30) and `res/xml/data_extraction_rules.xml` (API 31+ `<cloud-backup>` **and** `<device-transfer>`), since a `dataExtractionRules`-only fix leaves older paths open. Use `noBackupFilesDir` for the rootfs. RikkaHub references both XML files (verified) — check what they exclude if you reuse the design.

---

## Recommendation

**Store the per-conversation workspace in `context.filesDir`, expose it to the system Files app with a custom `DocumentsProvider`, and treat SAF / FileProvider / MediaStore strictly as ingress and egress paths.**

Concretely:

1. **Source of truth:** `filesDir/conversations/<uuid>/` — real POSIX paths, no permissions, Play-safe, and the only option compatible with native/PROot tooling. Never use `getExternalFilesDir()` (invisible on API 30+ and FUSE-backed) and never `MANAGE_EXTERNAL_STORAGE` (Play rejection).
2. **User visibility:** implement a `DocumentsProvider` (manifest: `MANAGE_DOCUMENTS` permission, `DOCUMENTS_PROVIDER` intent filter, `exported="true"`, `grantUriPermissions="true"`) with a root named after your app and one child directory per conversation/workspace. That is verified as the mechanism behind RikkaHub's "RikkaHub disk". Budget **~300–400 LOC plus real work on Binder-thread paging, cursor column completeness, and `notifyChange`** — the naive version hangs the Files app on large directories.
3. **Ingress:** `OpenDocument`/`OpenMultipleDocuments` + `ACTION_SEND` filter; **always copy the stream into the workspace immediately** and never keep the `content://` URI as the source of truth.
4. **Egress:** `ACTION_CREATE_DOCUMENT` for "save as"; `FileProvider` + `ACTION_SEND` for "share"; `MediaStore.Downloads` (`IS_PENDING`) for an explicit "save to Downloads".
5. **Linux environment (if you build it):** PRoot, shipped as `libproot_exec.so` + `libproot_loader.so` in `jniLibs` (≈300 KB), rootfs as a runtime-downloaded `.tar.xz`, PTY borrowed from the Termux pattern, and a rootfs patch step for `/etc/resolv.conf`, `/etc/hosts`, `/etc/group`. **Wait for / adopt `termux-core` if you can**; otherwise this is weeks of work, and note RikkaHub is **AGPL-3.0**, so you may read it but not copy it into a closed-source app.
6. **Security posture:** assume the guest is fully trusted with your app's UID. Encrypt API keys with Keystore, keep them out of files, put the Linux environment in a separate/isolated process if you want the word "sandbox" to mean anything, enforce canonicalize+prefix on every tool path, add per-workspace quotas and a `StatFs` precheck, never auto-open agent-written files, and log every tool call.

---

## What I could not verify

1. **`mmap(PROT_EXEC)` / W^X semantics for guest binaries under `app_data_file`.** I verified that `execve` of app-data files is denied at targetSdk ≥ 29 and that `nativeLibraryDir` (`apk_data_file`) is the sanctioned executable location. I could **not** verify how PRoot's loader executes *guest* binaries that live in the rootfs under `app_data_file` — i.e. whether `mmap` with `PROT_EXEC` on an `app_data_file` is permitted, or whether PRoot uses a different route. **This is the highest-risk unknown if you reimplement rather than reuse.** ([termux-app#2155](https://github.com/termux/termux-app/issues/2155) is the right thread to read next.)
2. **Whether Android 14/15/16 further restrict same-UID `ptrace`.** I inferred that a guest process can ptrace the app's main process from general Linux same-UID semantics; I did not confirm Android-specific hardening.
3. **`openat2(RESOLVE_BENEATH)` availability on Android 15/16.** The TOCTOU discussion assumes it is not usable.
4. **Where RikkaHub's preset rootfs URLs point, and which distro the rootfs is.** GitHub's code-search API requires authentication, so I could not read the Compose UI that supplies the URL to `installRootfs(url)`. Verified only that the URL is a UI-supplied parameter and that no rootfs ships in the repo.
5. **Whether `RootfsInstaller` verifies a hash or signature of the downloaded rootfs.** My reading found HTTP status validation and progress reporting but no digest check; I did not read all 16 KB line by line.
6. **Auto Backup treatment of `getExternalFilesDir()`.** Commonly stated to be excluded; I could not read the Auto Backup page to confirm. Also the exact 25 MB default quota is from memory.
7. **`ACTION_OPEN_DOCUMENT_TREE` restrictions on API 30+** (that it cannot select `Android/data`, `Android/obb`, the internal-storage root, or the `Download` root) — widely reported, not read on a primary page.
8. **Android 15/16 behavior changes specific to storage**, and the current exact wording of the `MANAGE_EXTERNAL_STORAGE` Play policy permitted-use list. I read search-result snippets of the Play policy page rather than the page itself; `support.google.com` and `developer.android.com` both failed to fetch from this environment.
9. **FUSE's unsupported-operations list for `/sdcard`.** I asserted no symlinks/hardlinks/permission bits from FUSE/scoped-storage design knowledge; I did not read a page enumerating it.
10. **RikkaHub's `RootfsPatcher.createSymlink` containment check.** I read the function's first line (`File(target.parentFile ?: root, linkName).canonicalFile`) but not the whole function, so I cannot confirm it rejects symlink targets that escape the rootfs.
