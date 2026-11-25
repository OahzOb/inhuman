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
  record matters). **Upload log** is in **Settings → Updates and logs**, beside
  the server URL it uses; the old split put the button and its address in two
  different folds.
- **Upload log** sends the same text to `<server>/log`, where
  `tools/dev-server.py` saves it under `logs/`. The server URL is set in
  **Settings → Updates and logs** and shared with the in-app updater. It is a
  user action, not polling: nothing is uploaded until the button is pressed.
- **Upload conversation** is a different action and a different endpoint:
  `<server>/conversation`, saved under `logs/conversations/`. It posts the
  conversation's Markdown, so unlike this recorder it contains message text,
  reasoning, attachment paths, and — when the tool-result checkbox is on; it is
  off by default — tool calls and results, which can contain file or document
  text. It is invoked only from the chat menu after a preview, and it is never
  part of **Upload log**. Image and attachment bytes are not included. The
  recorder's promise below is about the diagnostic log, not about this explicit
  transcript export.
- Debug builds can also be read directly:

  ```powershell
  adb exec-out run-as com.inhuman cat files/diagnostics/app.log
  ```

- The same lines are mirrored to logcat under one tag, so during development:

  ```powershell
  adb logcat -s inhuman
  ```

Release builds are not debuggable, so `run-as` is unavailable there; Settings →
Diagnostics → **Share log**, or Updates and logs → **Upload log**, is the
supported path.

## What is recorded

| Area | What a line carries |
| --- | --- |
| Start-up | version and version code, SDK, manufacturer/model, ABI, locale, pid |
| Lifecycle | activity create (fresh or restored), start, stop, destroy |
| Navigation | which screen is on top, and the conversation id when there is one |
| UI | a collapsed section that auto-expanded or auto-collapsed, by its summary ("Thinking", "Used N tools"); only on an actual change |
| Scroll | first visible item (index, offset, key), total items and turn x/y; scroll start/end, drag start, follow-flag transitions, a programmatic follow scroll, and every jump request with its source, target short id and apply index. No message text. |
| Database | opening, opened, created (and the stack trace if it will not open) |
| Turn | send (chars, attachments, demo), run start (provider, model, thinking, effort, tool count, round limit), run end (rounds, reply id), round limit reached, stop, regenerate refused and why |
| Stream | request shape (history size, tool count, reply id), first-text latency, end line (finish reason, chunk count, text and reasoning lengths, call count, prompt/completion/cached tokens, TTFT, generation time, total), failure chunk text |
| HTTP | chat request shape (model, message/tool counts, thinking, effort, payload bytes), response status, failure body (first 300 chars), stream end (SSE lines/frames/chunks, duration), network failure class and message, automatic retry (round, attempt, delay, whether a partial answer was discarded) |
| Search | request (`api=search`, count, query length), response status and bytes, result count, HTTP failure body |
| Fetch | request (URL length), response status and bytes, title and content lengths, HTTP failure body |
| Models | `/models` request base, response status/bytes/duration, failure; picker cache hit, background refresh, 20s call timeout and 25s dialog timeout |
| Audio | reply read-aloud: the file's WAV header facts (the declared and real sizes, format tag, channels, rate, bits) and whether a canonical header had to be built for playback. On a playback failure one probe per layer follows: retriever duration/mime, extractor track count with its mime/rate/channels/bits, whether a decoder exists for that format, and a muted `AudioTrack` write result. No audio content. |
| Updates | check (current and remote version code, newer flag), download requested/verified (version, bytes), install requested; log upload (bytes, saved file name). No server URL or credential is written. |
| Tools | run (name, normalised path, argument bytes), done (ok, result bytes, duration), error text (first 240 chars), unknown tool, unparsable arguments, skipped call |
| Transfer | conversation bundle export (message and attachment counts, bytes), conversation bundle import (message and copied-attachment counts, failure category), workspace zip import (files/bytes and failure category); never message text, attachment names or file content |
| Keys | a key or OAuth session was cleared or signed out, by provider id or capability name only; never the value |
| Compaction | summary written (omitted messages and chars), a summary that came back empty (finish reason, reasoning length, model), and a save failure with its stack trace; never the summary text |
| Session maintenance | fold requested (kind, omitted messages/turns), brief written (chars, estimated tokens, truncated, provider input/output tokens), budget refusal, and a failure category with its stack trace; never the brief text |
| Recall | the `recall` call is recorded like any other tool by name; the query and the returned snippets are not written (the transcript already has the stub, and the request viewer has the rest) |
| SSH | global switch or ceilings changed; consent ask/allow/deny/revoke (host and user only); connect (host, port, user, auth type); connected (host and `SHA256:` fingerprint); exec done (exit code, stdout/stderr lengths, truncation, duration); list/tail/upload/download/write done (path, entries or bytes; never file content) |
| Rollback | snapshot captured or refused, restore success/failure, subtree delete request; ids and outcomes only, never file contents |
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

**The session brief and recalled record text are the same class as prompts.**
The maintenance and recall lines record sizes, ids, hit counts and outcomes, not
the derived summary or the snippets: the brief is derived from the transcript and
the request viewer is where the user inspects what was actually sent.

The two transcript egresses — the system share sheet and **Upload conversation**
— are not this recorder. The share sheet reads a cache copy after the user picks
a destination; the upload posts to `/conversation` after the preview. Neither
writes message text into `app.log`, and neither runs automatically.

For SSH the omission is explicit: **no command text, no password, no private
key, no passphrase, no file contents.** The command length and the exit code are
enough to tell "it ran and failed" from "it never connected"; the transcript
already has the result. Host, port, user and host-key fingerprint are recorded
because they are the facts that explain a connection failure.

There is no telemetry and no automatic upload. The only ways a log leaves the
device are the user pressing **Share log**, **Upload log**, or **Upload
recording**. All are explicit; the upload target is whatever LAN/Tailscale server
the user configured.

## Manual diagnostic recording

The recorder above is always on and **app-wide**: every subsystem writes through
the same `Diag` sink, so a turn, a settings edit, a file tool, an update check and
a scroll all land in the same stream. For a bug that can be reproduced, Settings
→ Updates and logs also has a **Diagnostic recording** switch: it copies that
same stream into `filesDir/diagnostics/recordings/recording.log` between the
start and stop lines. It is not a chat-conversation log; the window is global,
and the upload is the whole app's activity during it.

- The switch starts a recording; turning it off writes the stop line. Starting
  again replaces the previous recording.
- The file is capped at 2 MB, with one truncation line. A recording is a
  reproduction, not a second flight recorder.
- A recording survives a crash or restart as a stopped file. That is deliberate:
  the evidence would otherwise be lost exactly when it matters. **Upload
  recording** is enabled only while stopped and sends only that file to the same
  `POST /log`; **Upload log** still sends the whole recorder.
- It records the app's **diagnostic events**, not a raw input trace: the same
  `Diag` call sites, same redaction, no prompt, message text or file content. A
  literal every-tap/keystroke trace would be a different feature with a
  different privacy contract, not a second file for this one.
- The always-on file is deliberately **not** switched off by this. Its job is
  the bug that has already happened, where nobody knew a recording was needed;
  the recording is the clean upload, not a replacement. The "always on" known
  gap below is still the position for the recorder itself.

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
- **The recorder is always on; the manual recording is a copy, not a level
  control.** The volume is low and the alternative — a setting that is off when
  the interesting bug happens — is worse. The recording exists so a reproduced
  segment can be uploaded by itself.
- The entries are only as good as the call sites. A failure that is swallowed
  without a log line is invisible; the ones found while adding this are listed
  in the commit that introduced it.
