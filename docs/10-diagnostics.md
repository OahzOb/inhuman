# 10 — Diagnostics

**Status: implemented.** This file describes what the app records, where it
lives, what it deliberately leaves out, and how to get it off the device.

---

## Why a flight recorder

The question a bug report actually poses is not "what is wrong now?" — that can
be looked at. It is "what happened before I looked?" A turn that failed, a search
that never returned, a crash that closed the screen: by the time anyone reads the
description, the state in memory is gone.

Without a record, the only way to answer is to ask the user to reproduce it, and
the bugs worth reporting are usually the ones that will not reproduce on demand.
Worse, `logcat` is not a substitute: it is a ring buffer shared with every other
process, sized by the vendor, unreadable by the app, and on a device with no adb
attached there is no way to read it at all.

So the app writes its own record: one line per meaningful step, to a file it
owns, which survives the process and a crash.

## Where it lives, and how to get it

```
/data/data/com.inhuman/files/diagnostics/app.log
```

- `app.log` is live; at 1 MB it becomes `app.1.log` and older archives shift
  down. Three archives plus the live file: at most about 4 MB.
- **Settings → Diagnostics** shows the current size and has two actions:
  **Share log** (a `FileProvider` URI into the system share sheet) and
  **Clear** (confirmed, because the moment before clearing is exactly when the
  record matters).
- Debug builds can also be read directly:

  ```powershell
  adb exec-out run-as com.inhuman cat files/diagnostics/app.log
  ```

- The same lines are mirrored to logcat under one tag, so during development:

  ```powershell
  adb logcat -s inhuman
  ```

Release builds are not debuggable, so `run-as` is unavailable there; Settings →
Share log is the supported path.

## What is recorded

| Area | What a line carries |
| --- | --- |
| Start-up | version and version code, SDK, manufacturer/model, ABI, locale, pid |
| Lifecycle | activity create (fresh or restored), start, stop, destroy |
| Navigation | which screen is on top, and the conversation id when there is one |
| Database | opening, opened, created (and the stack trace if it will not open) |
| Turn | send (chars, attachments, demo), run start (provider, model, thinking, effort, tool count, round limit), run end (rounds, reply id), round limit reached, stop, regenerate refused and why |
| Stream | request shape (history size, tool count, reply id), first-text latency, end line (finish reason, chunk count, text and reasoning lengths, call count, prompt/completion/cached tokens, TTFT, generation time, total), failure chunk text |
| HTTP | chat request shape (model, message/tool counts, thinking, effort, payload bytes), response status, failure body (first 300 chars), stream end (SSE lines/frames/chunks, duration), network failure class and message |
| Search | request (model, query length, max results), response status and bytes, result count, HTTP failure body |
| Tools | run (name, normalised path, argument bytes), done (ok, result bytes, duration), error text (first 240 chars), unknown tool, unparsable arguments, skipped call |
| Approval | asked (tool, path, whether a configured rule or the tool's own hook asked, argument bytes), allow, decline |
| Policy | refusal (tool, path, policy), and failures to read or write rules |
| Secrets | a stored value that will not decrypt, by entry name only |
| Crash | thread, pid, full stack trace, written before the process is allowed to die |

Every line is one event. The format is:

```
2026-10-17 20:23:57.221 I/stream end finish=stop failure=false stopped=false chunks=130 textChars=547 ...
```

`level/tag` is greppable and `key=value` pairs are parseable without a schema.
Stack traces follow on indented lines.

## What is deliberately not recorded

**The API key, prompts, message text, file contents and image data.** The
transcript already holds what was said and what the tools returned, and it is on
the device; the log exists to explain the machinery around it — which model, how
many messages, which HTTP status, how long a tool took, which rule refused a
call. Lengths and identifiers take the place of content. Paths and tool names do
appear, because they are usually the answer and are far less sensitive than the
bytes in a file.

`Diag.redact` is a second line of defence, not the first: call sites are expected
not to pass secrets at all. It masks `sk-…` shapes, bearer tokens and
`key=value`-shaped secrets before a line is written.

There is no upload and no telemetry. The only way a log leaves the device is the
user pressing **Share log**.

## Why the file comes before logcat

The sink writes the file first and mirrors to logcat second. If the mirror were
first and threw, the durable copy would be lost; if the process dies between the
two, the file is the copy that matters. File writes are synchronous: the volume
is a few dozen lines per turn, and a queue drained by a worker would lose exactly
the lines a crash was about to need unless the crash path drained it too.

## Known gaps

- **No ANR watchdog.** A main-thread hang produces no line until the system
  kills the process; the last event before the hang is still useful, but the
  hang itself is not recorded.
- **Stream internals are counted, not traced.** Per-chunk events would be
  thousands of lines per reply and have never answered a question that the
  request/end pair and the transcript did not.
- **Approval decisions are not durable events.** They are in the log, but the
  event-sourced session log that would make them part of the transcript is
  still P0 work ([09-approvals.md](09-approvals.md) §5.3).
- **Sharing sends the live file only**, not the archives. The current file holds
  the recent history; archives are on disk for `run-as` when needed.
- **Always on, no level control.** The volume is low and the alternative — a
  setting that is off when the interesting bug happens — is worse.
- The entries are only as good as the call sites. A failure that is swallowed
  without a log line is invisible; the ones found while adding this are listed
  in the commit that introduced it.
