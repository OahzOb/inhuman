# Working on inhuman

Notes for whoever picks this up next, human or agent. The README explains what
the app is; this file explains how to change it without breaking what it
already gets right.

**Before reading the source, read `docs/`.** Seven files that describe what the
code actually does, with a `file:line` for every claim, so a session can start
work without re-deriving the architecture from scratch:

| File | Answers |
| --- | --- |
| `docs/01-overview.md` | What this is, what works, what is deliberately missing, the vocabulary |
| `docs/02-architecture.md` | Module graph, wiring at start-up, navigation, build toolchain, `tools/` scripts |
| `docs/03-ai-provider.md` | Provider and stream contracts, the DeepSeek adapter, SSE, demo mode, **how `web_search` really works** |
| `docs/04-data-persistence.md` | Room schema v7, orderings, migrations, repositories, `SecureStore`, the `DocumentsProvider` |
| `docs/05-files-sandbox.md` | The sandbox, quotas, the eight file tools, import/export, the design layer |
| `docs/06-agent-loop-ui.md` | The turn lifecycle, the approval gate, cancellation, the context gauge, layout |
| `docs/07-gotchas.md` | The load-bearing invariants, the current defects, and comments that contradict the code |

`docs/07-gotchas.md` is the one to read before changing anything in a hurry.
Keep the docs current: a commit that changes a documented behaviour changes the
document in the same commit, or the doc becomes the next "comment that is the
bug".

---

## Two screens, always

**Every change is verified on both a phone-sized and a tablet-sized screen.**
Not "checked on one and reasoned about for the other" — actually run on both.

| | AVD | size |
| --- | --- | --- |
| Tablet | `dsh_tablet` | 2560x1600 @ 320dpi → **1280dp wide**, `Expanded` |
| Phone | `inhuman_phone` | phone form factor, `Compact` |

```powershell
emulator -avd dsh_tablet        # Android 15, x86_64, Pixel Tablet
emulator -avd inhuman_phone
```

The phone-sized case is not a smaller copy of the tablet case and must not be
treated as one. They differ in ways that have already caused bugs here: the
primary action is a FAB on a phone and a top-bar button on a tablet
(`prefersFabForPrimaryAction`), the gutter is 20dp against 32dp
(`comfortableGutter`), and 1280dp of width is enough for a paragraph to become
unreadable if nothing caps it.

### Space utilisation is the point, not the side effect

A tablet has roughly three times the horizontal room of a phone, and the lazy
answer — stretch everything — produces a 150-character line of prose and a
composer whose send button sits in a corner nobody looks at. That is worse than
the phone layout, not better.

The rule this project arrived at: **cap the measure, centre what is capped.**
`Layout.ReadableMeasure` (640dp) is the cap. On a phone the cap is inert —
`screenWidthDp` never reaches 640 — so the same code is correct at both sizes
without a form-factor branch. Prefer that shape: a maximum that binds on the
large screen and does nothing on the small one.

Two corrections to what this file used to claim, both verified — see
`docs/07-gotchas.md` B5:

- The chat screen caps the **composer strip and the error row** on that axis,
  not the transcript. Bubbles are capped at 640dp but aligned to the start/end
  inside a full-width item, so on a tablet an assistant bubble begins at the
  32dp gutter while the centred composer begins at 320dp.
- The cap is **inert at seven call sites** in five files (the conversations
  list, the agents list, files, settings, `WideDialog`), because they chain
  `fillMaxWidth`/`fillMaxSize` *before* `widthIn`. Three of those carry a
  comment claiming the cap is what makes the tablet layout readable.

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

With no API key, Settings → Demo mode gives a scripted provider. When the
assistant has file tools it creates a file and then asks to delete it, so the
tool loop, the approval gate and the declined state are all reachable with no
key, no network and no bill. **Put this in a message to get prose instead:**

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
  No symlinks, ever, plus a canonical-prefix check. Nothing else may construct
  a path from model input.
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
  whether a turn may be replayed; the second whether a call may happen.
- **The quota check walks the tree once**, via `SessionWorkspace.usage()`.
  Asking for bytes and count separately traversed the workspace twice per write.

---

## Verification before calling something done

```powershell
.\gradlew.bat :app:assembleDebug `
  :app:testDebugUnitTest `
  :core:data:testDebugUnitTest `
  :core:ai:testDebugUnitTest `
  :core:files:testDebugUnitTest `
  --console=plain --rerun-tasks
```

`--rerun-tasks` matters: with the build cache warm, a plain run can report
`BUILD SUCCESSFUL` without compiling the file you just changed. Check that the
warning output is empty as well as the error output — this tree currently
compiles clean, and that is worth keeping.

One test is skipped by design: `DeepSeekSearchProviderLiveTest` talks to the
real API and needs a key. Everything else runs on the JVM.

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
