# 05 — The sandbox, the file tools, and the design layer

Module: `:core:files` for the first two; `:core:design` for the third.

## Workspace lifetime

A workspace is now a first-class row (`WorkspaceEntity`), not a directory owned
implicitly by a conversation. `SessionWorkspace` still receives only an id and
still confines every tool path to that directory; what changed is who owns the
directory.

- **Temporary**: created with its one conversation, deleted with it. The
  conversation's delete confirmation says that its files go too.
- **Permanent**: created from the Workspaces screen or promoted from a
  temporary workspace. It survives deletion of every conversation that uses it,
  may be empty, and may be shared by several conversations. Its 256 MiB /
  5,000-file quota is shared by all of them, because it is one directory.
  "Permanent" is relative to conversations, not to the app: uninstalling or
  clearing app data still removes it, and Android backup remains off.
- A temporary workspace is deliberately single-conversation. Otherwise deleting
  its owner would delete files another conversation is still using.

Deleting a conversation in a permanent workspace removes its transcript and
rollback snapshots only. The files and the workspace row remain. The
single-workspace delete in Workspace detail is refused while conversations
remain. The Workspaces list has a separate **multi-select** action: selecting
workspaces and confirming deletes their conversations, rollback snapshots, the
workspace directories and finally the workspace rows. The confirmation names
the conversation count, so this cascade is never silent.

The absolute directory is shown in Files → Workspace location and on the
Workspace detail screen, with a copy action. **Open in Files app** opens the
system DocumentsUI on the workspace root, which is the permissionless way to
touch the files from outside; the absolute path itself is useful for adb or a
root file manager, because an ordinary file manager cannot traverse
`/data/user/0`. It is a **user-only** path string: no tool argument, system
prompt, or diagnostics line contains it. The agent still sees only
workspace-relative paths through `resolve()`.

## What the sandbox is, and what it is not

**It confines the agent's tool calls. It does not sandbox the process.**
`SessionWorkspace`'s own doc comment says so
(`core/files/src/main/kotlin/com/inhuman/core/files/SessionWorkspace.kt:35-39`):
anything running as the same UID can read the same files regardless. Do not cite
`resolve()` as an OS-grade boundary — the research notes in
`_research/android-sandbox/README.md` describe the boundary that *would* be one
(SELinux/kernel, `isolatedProcess`), and none of it is implemented. There is no
QuickJS, no `libqjssandbox`, and no shell `run_code`; the closed set of eleven
workspace tools is asserted by `ToolMutationClassificationTest`. `run_javascript`
is a separate tool with no `SessionWorkspace`: it runs in the platform's V8
isolated process, is offered only after its own device probe, and can return a
string but cannot read a file or open a socket. The SSH tools are not workspace
tools either: `ssh_probe`, `ssh_list`, `ssh_read_file` and `ssh_exec` never
receive a `SessionWorkspace`; `ssh_upload`,
`ssh_write_file` and `ssh_download` reach the workspace only through callbacks
owned by `ChatViewModel`, which resolves every path with `SessionWorkspace`.
Their effects outside the sandbox are why the capability has the separate consent
gate in [03-ai-provider.md](03-ai-provider.md) and
[09-approvals.md](09-approvals.md).

### The startup self-check: the fence is asked, not assumed

`WorkspaceSelfCheck` (`core/files/src/main/kotlin/com/inhuman/core/files/WorkspaceSelfCheck.kt`)
runs in `InhumanApp.onCreate`, before the first activity, in a throwaway
directory under the app's cache:

- a relative path stays inside the root;
- `../` is refused;
- an absolute-looking path is treated as relative and stays inside;
- a file outside the root cannot be relativised;
- a symlink planted in the workspace is refused;
- on Windows, a junction is refused by the real-path comparison (the check that
  `Files.isSymbolicLink` cannot make).

It creates one outside file and one link, deletes the link **before** the
directories, and never recursively deletes a tree that contains a link. A
platform that refuses to create a symlink reports `Skipped` for that check, not
a failure: it cannot have a planted link either, and the canonical prefix check
still runs.

**A failure — including "the check could not run" — is not a warning.**
`AppCapabilities.workspaceOk` becomes false, and every workspace-bound tool is
absent from the request and from the pickers: the eleven file/document tools, the media
tools that save or read workspace files, and SSH (its transfers and identity
reads use the same gate). The chat shows a banner with the failing assertions;
Files still opens the user's files, because the fence is a rule about the
**agent's** tool calls, not about the owner's access. The next process start
asks again.

This is the workspace half of `docs/08-port-philosophy.md` §3.6. The
code-execution assertions that section also names (`fetch` is `undefined`, the
sandbox cannot read `filesDir`, the heap cap holds) are made by
`SandboxRuntime.probe()` on every process that offers `run_javascript`; a
failure removes that tool and records the reason in Diagnostics. They are not
part of `WorkspaceSelfCheck`, because the JavaScript isolate has no workspace to
check.

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
5. **Per-component symlink and reparse walk** (`:87-94`). Any symlinked
   component is refused, *including one pointing back inside the root* —
   allowing some symlinks and not others is the kind of rule that eventually has
   a hole. On Windows a junction is not a symlink: `Files.isSymbolicLink` says
   false, and the component is checked with `toRealPath()` against the real root.
6. **Real-path prefix check** (`:100-107`). `canonicalFile()` does not resolve a
   Windows junction, so existing components use `toRealPath()`; the final target
   is compared against the real root. Allowed iff the target path equals the root
   path or starts with `rootPath + separator` — the `+ separator` closes the
   sibling-prefix hole. The returned `File` is the resolved target, not the
   joined path.
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
   and **only `delete_file` has it** (`FileTools.kt:363-364`).
3. **The symlink tests are vacuous where symlinks cannot be created.** Both
   return silently when `createSymbolicLink` throws
   (`SessionWorkspaceTest.kt:151-154`, `:165-167`), which is the normal case on
   Windows without privilege. They assert nothing there. The Windows junction
   test is separate: `mklink /J` does not need developer mode, and that test
   found a real escape before the `toRealPath()` check was added.
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

The quota is per **workspace**, not per conversation. Conversations sharing a
permanent workspace share these ceilings, and deleting one of them frees no
files; the Workspaces screen and the Files header show the same usage numbers.

The details that matter, all in `WorkspaceTool.kt`:

- **A read over the ceiling is refused, not truncated.** A truncated read looks
  complete to the model, which then reasons confidently about half a file —
  a failure mode that is very hard to notice from the outside (`:40-45`).
- **The refusal has to name something the app can do.** It used to end "Read part
  of it, or use a shell." There is no shell, and `read_file` had no ranged read
  either, so it told the model to do two impossible things. `read_file` now takes
  line numbers, which is why the advice is actionable (`:47-54`).
- **A ranged read is bounded by the slice, and streams it.** `offset`/`limit`
  are applied by a line reader, so ten lines of a 20 MB log no longer allocate
  the whole file first; the ceiling is checked while accumulating and refuses
  before the slice becomes a single `String`. The remaining inaccuracy is the
  unit: the ceiling is compared against UTF-16 characters while the message
  calls that number bytes.
- **The quota only runs when a write grows the file** (`delta > 0`, `:114-119`).
  A shrinking overwrite bypasses both totals ceilings — which is the right
  outcome and an accident rather than a design. The file-count test charges a
  new slot only when the file did not already exist
  (`addsFiles = if (existed) 0 else 1`), so growing an existing file at the
  limit is no longer refused as if it were adding one.
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

## The eight file tools and three document tools

`FileTools.all` (`core/files/src/main/kotlin/com/inhuman/core/files/tools/FileTools.kt:54-69`),
in registry order:

| Tool | `mutates` | Asks by default | Notes |
| --- | --- | --- | --- |
| `list_files` | no | no | Sorted directories-first, then case-insensitively; announces truncation at `maxListEntries`. |
| `read_file` | no | no | Line-numbered. The numbers are the **file's own**, not the slice's: a ranged read that restarted at 1 would invite the model to edit the wrong line, and the mistake would look like a successful edit (`:146-162`). |
| `write_file` | yes | no | Reports "Created" vs "Replaced". Overwriting is recoverable from the transcript; that is why it does not ask. |
| `append_file` | yes | no | Appends bytes in place (`FileOutputStream(..., true)`), so it is bounded by the appended text rather than by the combined size; it no longer reads and rewrites the file, which was O(existing) per call. |
| `create_directory` | yes | no | Idempotent, and says so when it already exists. |
| `move_file` | yes | no | Refuses moving a directory inside itself (canonical prefix) and refuses an existing destination rather than clobbering. |
| `delete_file` | yes | **no** | The one irreversible operation — and it no longer asks. Structure is the argument, not permission: what is inside a workspace is a *copy* of what the user handed over, so deleting it is reversible at the level that matters. See [09-approvals.md](09-approvals.md). |
| `file_info` | no | no | With no path, reports the workspace totals against their two ceilings plus the per-file read and write limits — four of the six, not all of them. |

### `AGENTS.md`: the one file that becomes a prompt

A root-level `AGENTS.md` is the workspace's standing instruction. It is a normal
workspace file: the user edits it in Files, the agent can write it, it counts
against the quota, and it travels with the zip. What is special is that the
conversation **pins** it on its first request: `WorkspaceInstructions.kt` reads
the file, renders the block and stores it in
`conversations.workspaceInstructions`; the system message (`docs/06`) then uses
the stored string, never a fresh read. Editing the file therefore does not
change an existing conversation -- new conversations pin the current version,
and the chat menu's **Reload AGENTS.md** replaces the pin deliberately.

Only the root name matches; `docs/AGENTS.md` is an ordinary file. The read goes
through `SessionWorkspace.resolve`, so a symlink is refused like any other agent
path. It is capped at 16 KiB and cut at a line boundary; a single 16 KiB line, a
blank file, a NUL byte or an unreadable file pins an empty block rather than
failing the turn. The absolute workspace path never enters the block. A failed
workspace self-check removes workspace instructions along with the workspace
tools -- the fence is what makes the file trustworthy. The pinned string lives
in the database, so an encrypted backup carries it; the workspace zip carries
the file itself, not the pinned copy.

### Document tools

`DocumentTools.all` appends `read_document`, `write_document` and
`convert_document` to the same list (`DocumentTools.kt`). They resolve paths
through `SessionWorkspace`, use the same quota/free-space check
(`WorkspaceTool.writeBytes` for binary packages), and are marked
`workspaceBound = true`, so a failed fence check removes them from every picker
with the file tools. `read_document` is read-only; the other two are `mutates`.
They parse locally and accept no network or absolute path.

The implemented formats are CSV/TXT (own RFC 4180 writer/reader plus plain text),
DOCX/XLSX (a local OOXML subset over zip + DOM) and PDF (PDFBox-Android for the
text layer; the platform PDF writer for output). A DOCX/XLSX write copies every
package part it does not understand, so a macro, comment or embedded chart
survives an edit even though it cannot be rendered. A read or conversion records
what the neutral model could not carry in a `LossReport` — unsupported features,
flattened formulas, skipped sheets, truncation — and the tool result says so.
A scanned PDF page is reported as having no text layer, not as an empty document.
PDF/DOC/XLS backends are host-supplied: the Android host passes PDFBox and POI,
and a format with no backend is refused with a reason. Android reads
and writes XLS, and writes DOC into a bundled blank Word 97 template rather than
renaming a DOCX/RTF; DOC output is plain paragraphs (tables and formatting are
flattened), and DOC reads report that structure is not represented. The
format/library decision is `14-documents-voice.md`.

**"Asks by default" is not where approval is decided.** `Tool.needsApproval` is
still evaluated per call against the arguments (`Tool.kt:84`), but it has become
a **"must always ask" hook that no tool currently uses** — every file tool
returns `false`, and `FileToolsTest` asserts the set of tools that ask is
**empty**. The decision moved to the user's configured rules, which is what
[09-approvals.md](09-approvals.md) describes and what
`ChatViewModel.kt:1187-1213` implements in this order:

| Effective policy | What happens |
| --- | --- |
| `Deny` or `Blind` | Refused immediately with `REFUSED_MARKER`, before any question. `needsApproval` can only say "ask", and asking about something the user forbade would be the wrong question with a confusing answer (`:1187-1192`). |
| `Ask`, or a tool whose own `needsApproval` returns true | The approval dialog, and the turn suspends (`:1213-1221`). |
| anything else | Runs. |

So the declined state is still reachable — it is now reached by configuring a
rule rather than by an irreversible operation existing.

Every one takes **workspace-relative path arguments** — `path`, or `from`/`to`
for `move_file` — and none is ever told where the workspace is: confinement is a
property of the interface, not of a prompt (`:34-43`). `FileToolsTest` locks the
half that matters (no description names an absolute location), but its `path`
check is weaker than it reads: it asserts that *some* tool declares `path`, not
that every one does (`FileToolsTest.kt:62-76`).

`delete_file` refuses the canonical workspace root, because a model that decides
to "clean up" by deleting `"."` would remove every file in the conversation and
there is no reading of that instruction where the user wanted it
(`FileTools.kt:363-364`).

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
  and enforces a **per-file ceiling** (default `maxWriteBytes`) while it streams,
  then every quota — `maxTotalBytes`, `maxFileCount` and reserved free space —
  before the staging file is renamed into place. The per-file ceiling is a
  parameter: tool writes and attachments use the 4 MiB default, a user download
  passes the space left in the workspace so a PDF or zip is not refused for being
  larger than a tool write, without letting it walk past the workspace ceiling.
- A failure deletes the **staging file only**. The destination that was already
  there is untouched; the earlier implementation deleted the target on every
  error path, which lost an existing file when an import failed halfway.
- `export` has **no read ceiling**.
- `uniqueName` appends " (2)" rather than overwriting, because a user importing
  twice usually means two files and silently replacing the first is how work
  disappears (`:125-147`). At `n > 200` it returns a name **without
  collision-checking it** (`:144`).

### Workspace to workspace: `WorkspaceCopy`

`core/files/src/main/kotlin/com/inhuman/core/files/WorkspaceCopy.kt` is the
user's route between two workspaces. The Files screen's copy action opens a
picker of every **other** workspace (permanent first, temporary ones too --
rescuing a file before deleting its conversation is a real use), then a read-only
browser of that workspace. Picking a file copies it into the directory currently
on screen.

- The source path is re-resolved through the **source** `SessionWorkspace`, not
  trusted from the browser, and only a regular file is accepted. Directories are
  refused rather than copied recursively: a recursive copy needs its own quota
  walk and partial-failure semantics, and "copy this folder" is a separate
  feature, not a quiet expansion of this one.
- The bytes go through `WorkspaceTransfer.import` with the source's own stream,
  so the per-file ceiling, total quota, file count, reserved free space and the
  " (2)" naming rule are the **same ones** a system import gets.
- The result is a **snapshot**: the source is unchanged, later edits there do not
  follow, and there is no reference or mount. A reference would let one
  conversation's rollback write into another's materials, which is the boundary
  `docs/13-product.md` refuses.
- Workspace rules are not consulted: those constrain the assistant, and this is
  the owner acting on their own files. Neither absolute path reaches the model.
- The source browser filters symlinks out; the source-side `resolve` would refuse
  them on import anyway, and a row that always fails is worse than no row.

### The share sheet: one item, copied now

`MainActivity` registers `ACTION_SEND` (never `ACTION_SEND_MULTIPLE`) with
`text/plain`, `image/*` and `application/*` — no catch-all. The filter is the
entry's whole narrowness: a share is one item the user chose in another app, not
a channel any app can push into. `SharedContent.kt` re-checks the action and MIME
allowlist before reading anything, because an exported activity can be started
explicitly with anything.

- **Destination.** If a chat is the visible screen, the item is copied into that
  conversation's workspace and a Toast confirms it — the user already chose the
  context by being there. Otherwise a one-step conversation picker appears; the
  copy happens immediately after the pick, with no import screen. Cancelling
  drops the share.
- **Names.** A sender-provided display name wins, with separators flattened; text
  becomes `shared.txt`; an unnamed image or file falls back to its type
  (`shared.jpg`, `shared.pdf`, `shared.bin`). The target's
  `WorkspaceTransfer.uniqueName` supplies " (2)".
- **Rules.** The bytes go through the ordinary `WorkspaceTransfer.import`, so the
  4 MiB per-file ceiling, total quota, file count and free-space reserve are the
  same as a system import. The URI is read in the same process, immediately: a
  share grant is transient, and keeping it for later would produce a broken file.
- **No read path back.** Copying into the workspace gives the sender no ability
  to read the workspace; the URI grant runs the other way, and the copied file is
  just bytes. The activity is exported for the share sheet, but the MIME filter
  and the user's tap are what create the entry.

### Workspace export: `WorkspaceZip`

`core/files/src/main/kotlin/com/inhuman/core/files/WorkspaceZip.kt`. This is the
**plain** zip behind the chat menu and the workspace detail screen. It writes
the files only: no database, no manifest, no keys, and no encryption. It is an
export for reading or sharing, not a backup — the encrypted `BackupRepository`
is the migration path.

Every directory and file is checked with `toRealPath()` against the real root
before it is added. A symbolic link is skipped even when it points back inside;
a Windows junction is followed by neither `Files.isSymbolicLink` nor
`File.canonicalFile`, so the real-path check is what closes the B29 escape. A
directory whose real path repeats one already visited is skipped rather than
walked twice or allowed to loop. The result carries `skipped`; the UI prints it,
because a zip missing a file must not look complete.

Directories are kept as entries, so an empty folder survives. File contents are
streamed at 64 KiB and the result's `bytes` is the uncompressed content total
for diagnostics; it is not the archive size and is not shown as one.

### Download from a URL

A second user-facing path, next to import/export: **Files → Download from a
URL**. The user types the URL; the app opens it and streams the bytes to one of
two destinations:

- **the conversation workspace**, under `downloads/`, through
  `WorkspaceTransfer.import` with the remaining quota as its ceiling;
- **a folder the user picks** through `ACTION_OPEN_DOCUMENT_TREE`, written with
  `DocumentsContract.createDocument`. The app gains no general storage
  permission, and `MANAGE_EXTERNAL_STORAGE` remains refused.

There is **no arbitrary download size cap**: the dialog shows the server's
`Content-Length` and the bytes received, the workspace quota is the limit when
saving there, and the file system is the limit for a chosen folder. The transfer
is streamed, never held in memory.

It is deliberately **not a tool**. The agent cannot call it, and no prompt can
reach it; the URL comes from the user. `UrlDownloader` nonetheless refuses
private, loopback, link-local and multicast addresses (checked again after each
redirect through a custom `Dns`), because "the user typed it" is not a defence
against a link to `127.0.0.1` or a router's admin page. Only `http`/`https` are
accepted, credentials in the URL are refused, and a hostile
`Content-Disposition` name is sanitised before it reaches either destination.

### In-app editor and external editors

Files → open a text file → **Edit** opens a full-screen monospace editor. Save
writes through `WorkspaceTransfer.import` with the workspace's 4 MiB per-file
ceiling, so the buffer is staged and renamed atomically and the same total,
file-count and free-space checks run as for an import. A file larger than the
1 MiB read ceiling, or one containing a NUL byte, is refused with **Open with…**
instead of being loaded as mojibake.

**Open with…** builds a writable document URI:

`content://<applicationId>.documents/<workspaceId>/<relativePath>`

and launches `ACTION_EDIT` with read and write URI grants, falling back to
`ACTION_VIEW` when no editor is installed. The system chooser owns the activity;
on return `LifecycleResumeEffect` refreshes the list, so an external save is
visible without restarting the screen. This path deliberately does **not** pass
through `WorkspaceTransfer`: the provider hands the external app a writable
file descriptor, which is what allows ordinary editors to save in place. The
workspace header still reports the resulting usage when the screen resumes, but
an external write is not re-checked against the import quota at write time.

## The design layer

`Layout` (`core/design/src/main/kotlin/com/inhuman/core/design/Layout.kt`):

| Symbol | Value |
| --- | --- |
| `ReadableMeasure` | 640dp — forms, settings and short prose |
| `ChatMeasure` | 900dp — transcript and composer |
| `UserBubbleMeasure` | 560dp |
| `GutterCompact` / `GutterWide` | 20dp / 32dp |
| `comfortableGutter()` | Compact → 20, otherwise 32 |
| `prefersFabForPrimaryAction()` | Compact only |
| `Layout.Space` | `BetweenMessages` 10, `InMessage` 6, `BetweenCollapsed` 4, `CollapsedPadding` 5 |

`currentFormFactor()` reads `LocalConfiguration.current.screenWidthDp` with
`0..599 → Compact`, `600..839 → Medium`, else `Expanded`. **Medium behaves like
Expanded** for both helpers above — a fact worth knowing before adding a third
case.

### The visual system

The palette is **warm paper in light, warm graphite in dark**, with one burnt
orange signal accent (`Color.kt`). The light scheme uses `AccentDeep` for text and
buttons so the accent still passes contrast on paper; the dark scheme uses the
brighter `Accent` with a dark `onPrimary`. `Theme.kt` fills the Material
`secondaryContainer`, surface-container and error-container roles as well: leaving
them at the Material defaults reintroduced a lavender `FilterChip` and a generic
purple dialog on the first render.

`Type.kt` keeps body text at 16sp/26sp and moves `labelMedium`/`labelSmall` to
monospace with 1sp tracking. Mono is for metadata (wordmark, section codes,
timestamps, model/workspace lines), never for paragraphs.

`Workbench.kt` holds the shared visual vocabulary: `Wordmark`, `Eyebrow`,
`Hairline`, `StatusDot`, `WorkbenchGrid`, `SectionHeading`, `MetaLine`. A screen
should reach for these before adding a literal colour, radius, or spacing value.
`WorkbenchGrid` is used only on empty canvases, at 12% alpha; it is not wallpaper.

### The cap binds only when `widthIn` comes first

`AGENTS.md` states the rule as "cap the measure, centre what is capped" and gives
the modifier order. The measurement that made it stick: with the order reversed,
the composer's field stretched to **2208px** on a 1280dp tablet while the messages
above it stopped at **1232**.

Seven call sites once had the order wrong and ran the full 1280dp. All now
constrain first and fill second. The chat transcript, assistant replies and
composer share `ChatMeasure` (900dp); user messages use `UserBubbleMeasure`
(560dp); settings and forms use `ReadableMeasure` (640dp). The conversation list
and home rail are expressed as widths in `ConversationsScreen` because they are a
two-column layout rather than a reading measure.

**Why the wrong order is silent**: `fillMaxWidth()`/`fillMaxSize()` set
`minWidth = maxWidth = incoming max`. A following `widthIn(max = 640.dp)` can only
coerce its argument into that already-fixed range, so it clamps back to the
incoming maximum and the cap never binds — the constraint is doing nothing at all,
not merely producing something slightly too wide.

The parent has to centre the result. `ChatScreen` centres its 900dp transcript and
composer; `ConversationsScreen` centres the 760dp list panel on the tablet and
uses a 320dp rail beside it. Change the alignment and every capped child moves to
the edge.

### The rest of the design layer

- `WideDialog` (`WideDialog.kt`): `usePlatformDefaultWidth = false`, a Surface
  with 12/24dp margin, a 22dp corner (theme `extraLarge`), tonal elevation 6dp.
  The body is bounded by `heightIn(max = maxBodyHeight)` plus `verticalScroll`,
  because a `verticalScroll` inside an unbounded column overflows rather than
  clips. Actions sit outside the scroll area.
- `LongTextField.kt`: a collapsed preview capped at 96dp / 4 lines with ellipsis;
  editing happens in a `WideDialog` with a field at `heightIn(min = 280dp)`.
- `InhumanTheme(darkTheme = isSystemInDarkTheme())`: both schemes are first-class.
  The dark scheme is no longer "the design" with a reduced light fallback.
- `Type.kt`: body 16sp/26sp, mono labels; `displaySmall` 36sp for the workbench
  rail and welcome headline.

## Tests in `:core:files`

All JVM against a temp directory — `:core:files` sets
`testOptions { unitTests.returnDefaultValues = true }` because these tests need
real filesystem semantics rather than a device (`core/files/build.gradle`).

| Test | Locks in |
| --- | --- |
| `SessionWorkspaceTest` | Paths inside the root; empty → root; `"."` and `"//"` normalised; backslash separators; `..` refused with a message containing `..`; absolute-looking paths confined; Windows drive paths rejected-or-confined; NUL refused; symlinks refused (conditionally — see above); `relativize` inverts `resolve`; `fileCount`/`usedBytes` exclude directories. |
| `FileToolsTest` | The interface lock (no absolute location in any description, and `path` present among the declared properties); **no tool asks for approval by default** — the gated set is asserted *empty*, and the `needsApproval` hook is pinned by reflection rather than by a tool using it; write→read round trip with line numbering; `../` refused with nothing written outside; `/etc/passwd` reinterpreted as relative; deleting `"."` refused and the root survives; move-into-itself refused; missing file names the path; missing required argument named; oversized read refused, not truncated; oversized write leaves nothing on disk; the file-count ceiling; truncation announced; free-space refusal at an injected 1024 bytes and success at 1 GiB; Created vs Replaced; append; move preserves content; `file_info` totals; empty listing says "empty". |
| `ToolMutationClassificationTest` | The **exact tool-name set**, so a ninth tool fails loudly until it is classified; the three read tools are `mutates = false`; the five writes are `true`; and the flags are verified against the filesystem — `list_files` leaves `fileCount` unchanged, `write_file` increases it. |
| `WorkspaceSelfCheckTest` | A healthy temp fence passes all six checks and leaves no probe files; a parent that is a file fails closed with an error; one failed check fails the report; a `Skipped` platform check does not. |
| `WorkspaceCopyTest` | A second copy is numbered rather than overwriting; the source is unchanged; a copy lands in the browsed subdirectory; a crafted `../` source is refused; a directory is not copied as a file. |
