# Documents and voice input

Status: design decision, 2026-10-27. It is written before the implementation so
the trade-offs are reviewable; the sections marked *planned* are the remaining
work, not claims about today. CSV read/write is implemented in `:core:documents`;
DOCX/XLSX/PDF/DOC/XLS and voice are still to come.

`13-product.md` is the product statement: documents must be read, written and
converted without silently losing content, and voice input must put the user's
own words in the composer without sending them. This file answers *how*, and
what this project refuses to do to get there.

## 1. The boundary does not change

A document is a file in the conversation workspace. Reading one gives the agent
no new reach; writing one lands a file in the same workspace and goes through
the same quotas, free-space reserve, path resolution and checkpoint before a
turn as any other file. There is no document-specific permission, no
`MANAGE_EXTERNAL_STORAGE`, and no direct access to another app's storage.

Conversion is local. Uploading a contract, a payslip or a medical report to a
conversion service would be a second, unaccounted network path around the
workspace fence, and calling it "free" would be worse. If a converter needs the
network, it is not in this feature. The PDF/Office parsers are offline
libraries; none of them makes a request.

Voice is the same rule in the other direction: the microphone is a **user input**
device, not an agent tool. The agent never receives audio, never triggers a
recording, and never sends the transcript. The recording is local and is deleted
after transcription; only the text the user can edit reaches the composer.

## 2. Format matrix and libraries

The first batch is PDF, DOC, DOCX, XLS, XLSX and CSV. They are not one problem:

| Format | Read | Write | Library decision |
| --- | --- | --- | --- |
| CSV | RFC 4180 stream, quoted fields, CRLF/LF | RFC 4180, quoting only when needed | own parser/writer; no dependency |
| DOCX | OOXML paragraphs, heading styles, lists, tables | create and modify a subset; preserve unrelated zip parts | own OOXML over `java.util.zip` + `XmlPullParser`; no POI |
| XLSX | shared strings, inline strings, sheets, rows, cells, formulas as text | create/modify a subset; preserve unrelated zip parts | own OOXML; no POI |
| PDF | text layer, page ranges | create/replace text with a simple layout | PDFBox-Android (`com.tom-roush:pdfbox-android:2.0.27.0`, Apache-2.0) |
| DOC | Word 97 binary text/paragraphs | create/modify simple documents | Apache POI (`poi` + `poi-scratchpad`, Apache-2.0) |
| XLS | BIFF8 cells and sheets | create/modify simple workbooks | Apache POI (`poi` + `poi-scratchpad`, Apache-2.0) |

Why the split:

- **OOXML is a zip of XML, not a binary format.** Reading DOCX/XLSX with the
  platform's zip and XML parser keeps the app small and avoids POI's StAX and
  `java.awt` assumptions on Android. Writing is a controlled subset; every
  package part the writer does not understand is copied through unchanged, so a
  macro, a comment or an embedded chart is not silently deleted just because the
  app could not render it.
- **Legacy DOC/XLS are compound binary formats.** Reimplementing Word 97 and
  BIFF8 correctly is a project in itself; Apache POI is the mature Apache-2.0
  implementation. It is isolated behind the legacy adapter and its failures are
  reported as an unsupported file, not as empty success. This is the one place
  where an Android-incompatible library could force a fallback; the adapter is
  tested on the device before the format is claimed.
- **PDF has a text layer, not a document model.** PDFBox-Android is the
  maintained Android port of PDFBox. The reader extracts text per page; it does
  not reconstruct columns, tables or reading order, and it does not OCR. A
  scanned PDF is reported as "no text layer", not as an empty document. Writing
  PDF goes through the same library with a deliberately simple layout; the
  original layout is not preserved when converting a DOCX to PDF.
- **CSV is not Excel.** It has no types, no formulas and no sheets. Reading a
  sheet to CSV reports the sheet that was chosen and that formulas became their
  cached values; writing CSV from a multi-sheet workbook is refused unless the
  user names one sheet.

No cloud converter, no LibreOffice, no PRoot, no second runtime. A paid API
conversion service is not used: it would send the document off the device and
would need the product's paid disclosure for a feature that is otherwise local.

## 3. A neutral model, and what "loss" means

Every reader produces the same small model, in `core:documents`:

```
Document
  blocks: Heading(level,text) | Paragraph(text) | ListItem(level,text)
        | Table(rows: List<List<String>>)
  sheets: Sheet(name, rows: List<List<Cell>>)      // workbooks
  pages: List<Page(text)>                          // PDF
  meta: format, sourceName, truncated, warnings
```

The model is deliberately lossy: it does not carry fonts, colours, margins,
charts, comments, track changes, macros, images, footnotes or embedded objects.
That is why every conversion returns a `LossReport` with explicit categories:

- `unsupportedFeature` — present in the source but not represented (charts,
  comments, macros, images, footnotes, conditional formatting);
- `flattened` — formulas became cached values, or PDF text lost columns;
- `skipped` — rows/pages/sheets beyond the read limit;
- `truncated` — the input was cut at a byte/row/page ceiling.

A conversion that drops something must say so. The tool result is a short
summary plus the report; an empty report is the only "complete" conversion.
Converting to the same format is a **copy**, not a rewrite, unless the user asked
for a modification.

## 4. Read and write limits

A phone cannot hold a 500-page report in memory twice. The first release reads
with explicit ceilings:

- text files: the existing per-file import ceiling;
- DOCX/XLSX: 4 MB compressed input, 20k paragraphs / 50k cells per read;
- PDF: 200 pages per read, with an explicit `pages` range; the text of one page
  is bounded;
- DOC/XLS: 4 MB and the same row/paragraph limits;
- every result reports `truncated = true` and what was omitted.

The tools take a `range`/`pages` argument so "read only part" is a first-class
operation, not a failure mode. A partial read is never labelled complete.

Writing is bounded by the workspace quota and `WorkspaceTransfer.writeText`'s
existing rules. A document write is rebuilt in memory only for the package it
edits; if the result would exceed the ceiling, the tool refuses before creating
a half-written file.

## 5. Tools and model-facing contract

Three workspace-bound tools, in addition to the eight file tools:

- `read_document(path, pages?, range?)` — text/content plus warnings; no binary
  dump, no base64.
- `write_document(path, document)` — create or replace a document from the
  neutral model (title/paragraphs/tables/sheets), choosing format by extension.
- `convert_document(source, target, sheet?, pages?)` — read, convert, write, and
  return the `LossReport`.

All three are `mutates` only for write/convert, are workspace-bound, and are
covered by the same approval/policy machinery. They do not take an absolute
path and do not follow symlinks. PDF/DOC/XLS work is local CPU work; no `paid`
or `remote` badge. A document that cannot be parsed returns a refusal naming the
format and the reason, never an empty success.

## 6. Voice input

Voice is an **Android-only composer feature**, not a tool:

- a microphone button beside the composer; permission is requested on first use
  and a denied permission means the button explains why, not a silent no-op;
- press-and-hold to record, release to transcribe; a visible elapsed time and a
  warning before the 120-second cap; cancel discards the file;
- recording uses the platform encoder (AAC in an MP4 container) at a modest
  bitrate; the file lives in `cacheDir`, never in the workspace, and is deleted
  in a `finally` after the transcription attempt, success or failure;
- transcription uses the **existing** per-tool transcription backend and key.
  That backend is paid and already carries the `PAID` disclosure; the composer
  shows the same notice before the first recording. No new network path and no
  new credential are introduced;
- the transcript is inserted at the cursor in the composer, editable, and the
  user still presses Send. `send()` is never called from the recording path;
- an empty transcript, a failure or a cancellation leaves the composer
  unchanged and reports the failure once;
- the recording is never written to the conversation transcript and never
  attached to a message.

Desktop does not get voice in this release: the Android app is the product, and
a desktop microphone path would be a separate product decision.

## 7. Verification

- JVM tests for CSV, DOCX/XLSX package round trips, loss reporting, limits and
  format detection; a malformed zip/XML must be a refusal, not a crash.
- Device tests for PDF text extraction, PDF creation, the legacy DOC/XLS adapter
  (including a deliberately broken file) and the voice recorder's permission,
  duration cap and discard path.
- A conversion test asserts that a dropped feature appears in the report; a test
  that only asserts "conversion returned" is not enough.
- The full gate (`tools/ci.ps1`) stays at zero warnings; any new dependency is
  added to `THIRD-PARTY-NOTICES.md` with its licence.

## 8. Rejected alternatives

- **Cloud conversion** (Google Drive, Microsoft Graph, a converter API): sends
  the document off-device, adds a credential and a bill, and breaks the
  workspace boundary.
- **LibreOffice/PRoot**: a second runtime at the app's UID could read the
  conversation database and keys; explicitly refused by the product.
- **Apache POI for DOCX/XLSX**: pulls StAX and AWT assumptions into Android for
  a format that is already a zip of XML; the subset writer preserves unknown
  parts and is testable on the JVM.
- **OCR in the first batch**: a scanned PDF is reported as having no text layer.
  Adding OCR is a separate paid/local-model decision, not a hidden fallback.
- **Voice through the assistant**: recording triggered by or exposed to the
  model would turn a microphone into an agent capability. The button is user
  input only, like the keyboard.