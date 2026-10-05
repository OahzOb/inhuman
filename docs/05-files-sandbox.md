# 05 — The sandbox, the file tools, and the design layer

Module: `:core:files` for the first two; `:core:design` for the third.

## What the sandbox is, and what it is not

**It confines the agent's tool calls. It does not sandbox the process.**
`SessionWorkspace`'s own doc comment says so
(`core/files/src/main/kotlin/com/inhuman/core/files/SessionWorkspace.kt:35-39`):
anything running as the same UID can read the same files regardless. Do not cite
`resolve()` as an OS-grade boundary — the research notes in
`_research/android-sandbox/README.md` describe the boundary that *would* be one
(SELinux/kernel, `isolatedProcess`), and none of it is implemented. There is no
QuickJS, no `libqjssandbox`, and no `run_code` tool; the closed set of eight
tools is asserted by `ToolMutationClassificationTest`.

## `resolve()`: the one place a path becomes a `File`

`SessionWorkspace` (`SessionWorkspace.kt:41-45`) has a **private constructor** and
a private `root`, and `rootPath()` (`:175-184`) is a narrow accessor for `StatFs`
rather than a way to get the `File` back. The rule it enforces, quoted from the
class:

> A tool never receives the workspace root and never builds a path itself. It
> hands a relative string to `resolve()`, and `resolve()` either returns a file
> that is provably inside the root or refuses.

The pipeline, in order (`:62-113`):

1. **A NUL byte is refused** (`:63-65`).
2. `trim().replace('\\', '/').trimStart('/')` (`:70`). So whitespace is stripped —
   a filename with a leading space is unaddressable — backslashes are separators,
   and **only leading** separators are stripped.
3. **Empty after cleaning resolves to the root** (`:71`). `""`, `"   "` and `"/"`
   return `Ok(root)` here; `"."` reaches the same place through step 4. None of
   them is ever refused.
4. Split on `/`, dropping empty parts and `"."` (`:73`). **`".."` is not filtered
   — it is refused inside the loop** with "'..' is not allowed in a workspace
   path" (`:84-86`). So the normalisation is asymmetric: `"."` disappears and
   `"a/../b"` fails even though it stays inside.
5. **Per-component symlink walk** (`:87-94`). Any symlinked component is refused,
   *including one pointing back inside the root* — allowing some symlinks and not
   others is the kind of rule that eventually has a hole.
6. **Canonical-prefix check** (`:100-107`), using `canonicalFile()` (which
   resolves symlinks in the *parent* chain) rather than `normalize()`. Allowed
   iff the target path equals the root path or starts with `rootPath + separator`
   — the `+ separator` closes the sibling-prefix hole. The returned `File` is the
   canonical target, not the joined path.
7. Only `InvalidPathException` and `IOException` become rejections (`:108-112`).
   `resolve` **never throws**: a malformed path is a normal thing for a model to
   produce and has to come back as something it can read and correct.

### The four facts that are mis-remembered

1. **Absolute paths are not rejected — they are neutralised.** `/etc/passwd`
   resolves to `<root>/etc/passwd`, which is inside and harmless (`:56-60`).
   Tests assert it: `SessionWorkspaceTest.kt:93-101`, `FileToolsTest.kt:109-117`.
   On Windows `C:/x` throws `InvalidPathException` and is rejected; on Linux `C:`
   is a legal filename and would resolve *inside* the root — so the drive-letter
   test is green on Windows only, while the symlink cases in point 3 assert only
   where symlinks can be created. There is no CI configuration in the tree, so
   neither half is run for you.
2. **`resolve()` never refuses the root.** Root protection is each tool's job,
   and **only `delete_file` has it** (`FileTools.kt:324-326`).
3. **The symlink tests are vacuous where symlinks cannot be created.** Both
   return silently when `createSymbolicLink` throws
   (`SessionWorkspaceTest.kt:151-154`, `:165-167`), which is the normal case on
   Windows without privilege. They assert nothing there.
4. **`relativize` throws** (`error(...)`, `:125`) for anything outside the root,
   and `guard` does **not** catch `IllegalStateException`. A file that somehow
   came from elsewhere turns a tool result into an ended turn.

The TOCTOU claim in the doc comment (`:26-33`) is stronger than the code:
refusing to *traverse* links removes the window for paths that go through a link,
but nothing prevents another same-UID writer from creating a symlink after the
check, and there is no re-check or `O_NOFOLLOW` at open time. Fine against the
model, not a guarantee against a hostile co-resident process.

`open()` (`:194-197`) **ignores `mkdirs()`' return value**, so a workspace whose
directory could not be created still opens: `resolve("")` reports `Ok("")`, and
`usage()` reports 0 bytes rather than an error, so `file_info` reads it as an
empty workspace. `list_files` is the one that notices, with "no such directory".

## Quotas

`WorkspaceLimits` (`core/files/src/main/kotlin/com/inhuman/core/files/WorkspaceLimits.kt`)
holds six ceilings, all enforced by the tools rather than asked of the model:

| Limit | Default | Enforced in |
| --- | --- | --- |
| `maxReadBytes` | 1 MiB (`:13`) | `readText` |
| `maxWriteBytes` | 4 MiB (`:16`) | `writeText`, and incrementally in `WorkspaceTransfer.import` |
| `maxListEntries` | 500 (`:24`) | `listEntries` |
| `maxTotalBytes` | 256 MiB (`:27`) | `checkQuota` |
| `maxFileCount` | 5,000 (`:30`) | `checkQuota` |
| `reservedFreeBytes` | 64 MiB (`:33`) | `checkFreeSpace` |

The details that matter, all in `WorkspaceTool.kt`:

- **A read over the ceiling is refused, not truncated.** A truncated read looks
  complete to the model, which then reasons confidently about half a file —
  a failure mode that is very hard to notice from the outside (`:40-45`).
- **The refusal has to name something the app can do.** It used to end "Read part
  of it, or use a shell." There is no shell, and `read_file` had no ranged read
  either, so it told the model to do two impossible things. `read_file` now takes
  line numbers, which is why the advice is actionable (`:47-54`).
- **A ranged read is bounded by the slice**, not by the file, or a 20 MB log
  would be unreadable in any part (`:63-65`). **But the comment overstates it**:
  the code calls `file.readText()` (`:77`) before slicing, so a ranged read does
  load the whole file into memory. The ceiling is also compared against
  `rendered.length` — UTF-16 characters — while the message calls it bytes
  (`:91-95`). Both are worth fixing together if this path ever meets a large
  file.
- **The quota only runs when a write grows the file** (`delta > 0`, `:114-119`).
  A shrinking overwrite bypasses both totals ceilings — which is the right
  outcome and an accident rather than a design. And the count test is
  `usage.files + 1 > maxFileCount` (`:138`), so **growing an existing file at the
  limit is refused as if it were adding one**.
- **Free space is a precheck for growing writes only** (`:100-105`, `:146-156`).
  Not paranoia: without it a large write can fill the volume and the app's own
  database then fails to commit, turning one oversized file into losing a
  conversation. `freeSpaceBytes` is injected because `StatFs` is a stub on the
  JVM and reports zero, which would make every write fail in exactly the
  environment where the tools need testing (`:15-23`).
- **The single tree walk is `SessionWorkspace.usage()`**
  (`SessionWorkspace.kt:155-167`): one `walkTopDown()` counting bytes and files
  together. The quota check used to ask for them separately — two full recursive
  walks per write. Measured: one `write_file` into a 2,000-file workspace cost
  106 ms, of which roughly 86 ms was those walks (`:137-154`). `usedBytes()` and
  `fileCount()` each call `usage()` again, so calling them in turn restores the
  two-walk cost.
- **`usage()` skips symlinked directories but still counts symlinked files** at
  their target's size (`:158-165`): `onEnter` gates directories only. The comment
  claims a link is "not part of the workspace and must not be charged to its
  quota", which is half true.
- **No caching and no locking**, so two concurrent writes can both pass the same
  check.

## The eight file tools

`FileTools.all` (`core/files/src/main/kotlin/com/inhuman/core/files/tools/FileTools.kt:54-69`),
in registry order:

| Tool | `mutates` | Approval | Notes |
| --- | --- | --- | --- |
| `list_files` | no | no | Sorted directories-first, then case-insensitively; announces truncation at `maxListEntries`. |
| `read_file` | no | no | Line-numbered. The numbers are the **file's own**, not the slice's: a ranged read that restarted at 1 would invite the model to edit the wrong line, and the mistake would look like a successful edit (`:146-162`). |
| `write_file` | yes | no | Reports "Created" vs "Replaced". Overwriting is recoverable from the transcript; that is why it does not ask. |
| `append_file` | yes | no | Reads the existing file and rewrites it whole, so it is bounded by the *combined* size and is O(existing). |
| `create_directory` | yes | no | Idempotent, and says so when it already exists. |
| `move_file` | yes | no | Refuses moving a directory inside itself (canonical prefix) and refuses an existing destination rather than clobbering. |
| `delete_file` | yes | **yes** | The one irreversible operation, so the one that asks. |
| `file_info` | no | no | With no path, reports the workspace totals against their two ceilings plus the per-file read and write limits — four of the six, not all of them. |

Every one takes **workspace-relative path arguments** — `path`, or `from`/`to`
for `move_file` — and none is ever told where the workspace is: confinement is a
property of the interface, not of a prompt (`:34-43`). `FileToolsTest` locks the
half that matters (no description names an absolute location), but its `path`
check is weaker than it reads: it asserts that *some* tool declares `path`, not
that every one does (`FileToolsTest.kt:62-76`).

`delete_file` refuses the canonical workspace root, because a model that decides
to "clean up" by deleting `"."` would remove every file in the conversation and
there is no reading of that instruction where the user wanted it
(`FileTools.kt:321-326`).

### `guard`: the error boundary

`WorkspaceTool.guard` (`WorkspaceTool.kt:176-185`) converts:

| thrown | becomes |
| --- | --- |
| `Refused` | `ToolResult.error(message)` |
| `IllegalArgumentException` | `ToolResult.error(message)` — this is what a missing required field throws |
| `IOException` | `ToolResult.error("filesystem error: …")` |
| anything else | **escapes**, and ends the turn |

`Refused` is a deliberate subclass of `Exception` rather than of
`IllegalArgumentException`, so it can be caught first (`:37-38`).

`ToolResult.error` is still fed back to the model. A tool result the model never
sees is a turn that cannot recover — see [03-ai-provider.md](03-ai-provider.md).

## Import and export: `WorkspaceTransfer`

`core/files/src/main/kotlin/com/inhuman/core/files/WorkspaceTransfer.kt`. This
faces the **user**, not the model: a user importing a file is not a tool call and
should not need approval — they are the one being protected (`:18-22`). Both
directions **copy** rather than reference, because a `content://` URI is not
ours: the grant can be revoked, the source can be deleted, and a file a tool must
read has to be a real path anyway (`:10-16`).

- `import` resolves through the same gate (`:42-45`), streams 64 KiB at a time,
  and enforces **only** `maxWriteBytes`, checked as it goes rather than after,
  because discovering the limit was exceeded once the whole thing is on disk
  means the disk already paid for it (`:57-67`).
- **`import` is a quota side door**: no `maxTotalBytes`, no `maxFileCount`, no
  free-space check. Imports can push a workspace over its totals.
- Any exception deletes the target, because a partial file is worse than none —
  it looks like a successful import of a truncated document (`:74-79`). Note the
  corollary: if the destination resolved to an existing **empty directory**,
  `outputStream()` throws and the target is that directory, which is then
  deleted.
- `export` has **no read ceiling** (`:90-119`).
- `uniqueName` appends " (2)" rather than overwriting, because a user importing
  twice usually means two files and silently replacing the first is how work
  disappears (`:125-147`). At `n > 200` it returns a name **without
  collision-checking it** (`:144`).

## The design layer, and the inert 640dp cap

`Layout` (`core/design/src/main/kotlin/com/inhuman/core/design/Layout.kt`):

| Symbol | Value |
| --- | --- |
| `ReadableMeasure` | 640dp (`:42`) |
| `GutterCompact` / `GutterWide` | 20dp / 32dp (`:45`, `:48`) |
| `comfortableGutter()` | Compact → 20, otherwise 32 (`:119-120`) |
| `prefersFabForPrimaryAction()` | Compact only (`:131-132`) |
| `Layout.Space` | `BetweenMessages` 10, `InMessage` 6, `BetweenCollapsed` 4, `CollapsedPadding` 5 (`:61-92`) |

`currentFormFactor()` reads `LocalConfiguration.current.screenWidthDp` with
`0..599 → Compact`, `600..839 → Medium`, else `Expanded` (`:105-110`). **Medium
behaves like Expanded** for both helpers above — a fact worth knowing before
adding a third case.

### The cap is inert wherever `fill` comes first

`AGENTS.md` states the rule as "cap the measure, centre what is capped" and gives
the modifier order. What it does not say is that **seven call sites in the tree
have the order the wrong way round, and at those sites the cap does nothing**:

| Site | Modifiers | Cap binds? |
| --- | --- | --- |
| `ChatScreen.kt:349-351` (error strip) | `widthIn` → `fillMaxWidth` | yes |
| `ChatScreen.kt:387-389` (composer) | `widthIn` → `fillMaxWidth` | yes |
| `WideDialog.kt:86-87` | `fillMaxWidth` → `widthIn` | **no** |
| `AgentsScreen.kt:123-124` | `fillMaxSize` → `widthIn` | **no** |
| `FilesScreen.kt:206-207` | `fillMaxSize` → `widthIn` | **no** |
| `ConversationsScreen.kt:315-316` (list) | `fillMaxSize` → `widthIn` | **no** |
| `ConversationsScreen.kt:450-454` (empty state) | `fillMaxWidth` → `padding` → `widthIn` | **no** |
| `SettingsScreen.kt:95-98` | `fillMaxWidth` → … → `widthIn` | **no** |
| `SettingsScreen.kt:189-190` | `fillMaxWidth` → `widthIn` | **no** |

**Why**: `fillMaxWidth()`/`fillMaxSize()` set `minWidth = maxWidth = incoming
max`. A following `widthIn(max = 640.dp)` can only coerce its argument into that
already-fixed range, so it clamps back to the incoming maximum and the cap never
binds. It is not that the dialog or the list is subtly too wide — the constraint
is doing nothing at all.

**Three of those sites have comments claiming the opposite**, which is the
doc-comment-contradicts-code pattern this repository treats as a bug:

- `WideDialog.kt:41-46`: "The cap is `Layout.ReadableMeasure`, so the dialog grows
  with the device up to where a line stops being readable and then stops."
- `ConversationsScreen.kt:452-454`: "The measure cap is what makes the tablet
  layout readable rather than merely wide. On a phone this is inert."
- `SettingsScreen.kt:96-97`: "Cap the measure on a tablet; a full-width text
  field across 1280dp is unusable to type into."

`ChatScreen.kt:379-389` is the one that gets it right, and it carries the
measurement: with the wrong order the composer's field stretched to **2208px** on
a 1280dp tablet while the messages above it stopped at **1232**.

The fix is the order, and the parent has to centre the result — which is what
makes `ChatScreen`'s `Column(horizontalAlignment = CenterHorizontally)` and
`ConversationsScreen`'s `Box(contentAlignment = Center)` load-bearing rather than
cosmetic. Change one of those alignments and every capped child moves to the
start edge.

### The rest of the design layer

- `WideDialog` (`WideDialog.kt:55-128`): `usePlatformDefaultWidth = false`, a
  Surface with 12/24dp margin, 20dp corner radius, tonal elevation 6dp. The body
  is bounded by `heightIn(max = maxBodyHeight)` (default 420dp) plus
  `verticalScroll`, and the comment explains why a bound rather than a `weight`:
  **`verticalScroll` inside an unbounded column does not clip, it overflows.**
  Measured symptom: a multi-line text field drew its own text past its border and
  over the label below it. The actions sit **outside** the scroll area, which is
  what lets the body be arbitrarily long without pushing Save off the bottom.
- `LongTextField.kt`: a collapsed preview capped at 96dp / 4 lines with ellipsis,
  deliberately **not** scrollable; editing happens in a `WideDialog` with
  `maxBodyHeight` 480dp and a field at `heightIn(min = 280dp)`.
- `Theme.kt:49-52`: `InhumanTheme(darkTheme = isSystemInDarkTheme())`. **Dark is
  the designed scheme**; the light scheme is reduced, and `LightScheme` sets no
  `tertiary` or `onPrimaryContainer` (`Color.kt:31-35`, `Theme.kt:29-40`).
- `Type.kt`: `bodyLarge` 16sp/26sp line height, `bodyMedium` 14sp/21sp.

## Tests in `:core:files`

All JVM against a temp directory — `:core:files` sets
`testOptions { unitTests.returnDefaultValues = true }` because these tests need
real filesystem semantics rather than a device (`core/files/build.gradle`).

| Test | Locks in |
| --- | --- |
| `SessionWorkspaceTest` | Paths inside the root; empty → root; `"."` and `"//"` normalised; backslash separators; `..` refused with a message containing `..`; absolute-looking paths confined; Windows drive paths rejected-or-confined; NUL refused; symlinks refused (conditionally — see above); `relativize` inverts `resolve`; `fileCount`/`usedBytes` exclude directories. |
| `FileToolsTest` | The interface lock (no absolute location in any description, and `path` present among the declared properties); **approval is exactly `["delete_file"]`**; write→read round trip with line numbering; `../` refused with nothing written outside; `/etc/passwd` reinterpreted as relative; deleting `"."` refused and the root survives; move-into-itself refused; missing file names the path; missing required argument named; oversized read refused, not truncated; oversized write leaves nothing on disk; the file-count ceiling; truncation announced; free-space refusal at an injected 1024 bytes and success at 1 GiB; Created vs Replaced; append; move preserves content; `file_info` totals; empty listing says "empty". |
| `ToolMutationClassificationTest` | The **exact tool-name set**, so a ninth tool fails loudly until it is classified; the three read tools are `mutates = false`; the five writes are `true`; and the flags are verified against the filesystem — `list_files` leaves `fileCount` unchanged, `write_file` increases it. |
