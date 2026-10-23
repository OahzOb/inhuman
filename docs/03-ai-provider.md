# 03 — The AI layer: provider, streaming, tools, and web search

Module: `:core:ai`. It has **no Compose and no Activity**, which is what lets all
of it be tested on the JVM. If you are adding provider behaviour, it belongs
here; if you are adding UI, it does not.

## The two interfaces

`ChatProvider` (`core/ai/src/main/kotlin/com/inhuman/core/ai/ChatProvider.kt:19`)
is deliberately thin: `id`, `listModels()`, and
`stream(request): Flow<StreamChunk>`. The file states what is *not* in it —
retries, key management, persistence, tool execution — because those are policy
and belong to the layer with the context to decide.

Its contract, worth quoting because callers rely on it: the flow **completes
normally on a clean finish** and emits `StreamChunk.Failure` for anything that
went wrong; cancelling the collector must cancel the underlying request
(`ChatProvider.kt:27-34`).

`Tool` (`core/ai/src/main/kotlin/com/inhuman/core/ai/Tool.kt:25`) is the other:
`name`, `description`, `parameters` (a raw `Map<String, Any?>` JSON Schema),
`mutates`, `needsApproval(arguments)`, `execute(arguments): ToolResult`. Two of
its rules are load-bearing and are argued at length in that file:

- **`needsApproval` takes the arguments.** Approval is per call, not per tool.
  "Writing a new file may be fine while overwriting an existing one is not." A
  boolean baked into the tool cannot express that, so every such tool ends up
  asking every time — which trains the user to approve without reading, and is
  worse than no gate at all (`Tool.kt:8-17`).
- **`mutates` is not `needsApproval`.** `mutates` asks "can this turn be
  replayed?" *afterwards*; `needsApproval` asks "may this happen?" *before*
  (`Tool.kt:58-66`). It has no default value on purpose: a default would be
  wrong in the dangerous direction, leaving regenerate enabled on a turn that
  writes (`Tool.kt:68-74`).

**Where approval is actually decided has since moved, and `needsApproval` is now
a hook rather than the gate.** The interface still declares it per call
(`Tool.kt:84`, defaulting to `false`), but **no tool overrides it to return
true** — `FileToolsTest` asserts the set of tools that ask is empty. The decision
belongs to the user's configured rules (`core/ai/…/policy/PolicySet.kt`), applied
by the turn loop; `ChatViewModel.kt:1187-1213` consults `Deny`/`Blind` as
refusals first, then `Ask`-or-`needsApproval` as the dialog. So read
`needsApproval` as "this call must always be asked about, whatever the rules
say" — a capability nothing currently needs, kept because a future tool might.
[09-approvals.md](09-approvals.md) is the model.

`execute` must not throw for an ordinary failure. A missing file, a quota
exceeded and a refused path are normal outcomes that belong in the returned text
where the model can read them; a thrown exception ends the turn and tells the
model nothing (`Tool.kt:86-94`). `ToolResult.error()` is still fed back to the
model — a tool result the model never sees is a turn that cannot recover
(`Tool.kt:111-118`).

## StreamChunk: why the stream is typed

`StreamChunk` (`core/ai/src/main/kotlin/com/inhuman/core/ai/StreamChunk.kt:33`)
is a sealed interface of `TextStart/TextDelta/TextEnd`,
`ReasoningStart/ReasoningDelta/ReasoningEnd`,
`ToolCallStart/ToolCallDelta/ToolCallEnd`, `Usage`, `Finish`, `Failure`, `Raw`.

Three facts drive the shape, and all three are places implementations go wrong:

- **Reasoning and text are separate channels.** A model that emits reasoning and
  then text one token at a time, interleaved, cannot be represented as a growing
  string without losing which part is which (`StreamChunk.kt:19-23`).
- **Tool-call arguments arrive as string fragments** and must be concatenated
  before they parse. `ToolCallDelta.inputDelta` is a `String`, deliberately not
  parsed (`StreamChunk.kt:69-79`).
- **`ToolCallStart.index` is the provider's ordering key, not arrival order.**
  OpenAI streams parallel tool calls interleaved and identifies each by `index`;
  keying on arrival order silently corrupts the arguments of every call after
  the first (`StreamChunk.kt:56-62`).

`Finish` is an explicit signal rather than an inference from the socket closing,
so a truncated connection can be told apart from a clean finish — one should be
retried and the other should not (`StreamChunk.kt:101-107`).

## Request and message types

`core/ai/src/main/kotlin/com/inhuman/core/ai/ChatTypes.kt`. The non-obvious
parts, all of them measured against the live API rather than assumed:

### Reasoning must be sent back, and the rule is not the documented one

`ChatMessage.reasoning` (`ChatTypes.kt:91-117`) carries a table measured against
the live API:

| request | result |
| --- | --- |
| thinking on, tools present, reasoning omitted | **HTTP 400** |
| thinking on, tools present, reasoning echoed | 200 |
| thinking off, tools present, reasoning omitted | 200 |
| **thinking on, NO tools, reasoning omitted** | **HTTP 400** |
| thinking on, no tools, reasoning echoed | 200 |

DeepSeek's docs tie this to `tools`; the measurement says the deciding factor is
**thinking mode**, not tool use. The error text is explicit: *"The
`reasoning_content` in the thinking mode must be passed back to the API."* The
consequence is that a transcript which drops reasoning works right up until a
thinking turn is replayed, and then fails with a 400 that points at the wrong
thing. Sending reasoning when thinking is off is harmless.

**Storage consequence: reasoning has to be persisted with the message.** See
[04-data-persistence.md](04-data-persistence.md).

### Images: `image_url` must be an object, and the model must be checked first

Two shapes are plausible and only one works. `image_url` as an **object**
containing `url` returns 200; as a bare string it returns
`422 ... invalid type: string, expected struct OpenAIChatMessageContentPartImage`
(`ChatTypes.kt:49-53`, `DeepSeekRequest.kt:116-124`). The field is named after a
URL, which is exactly what invites the wrong guess.

`ChatImage` carries **bytes, not a path** (`ChatTypes.kt:38-45`). The request
builder is a pure function from a request to JSON; giving it a path would make
it do file IO, make it untestable without a filesystem, and put the read inside
the transport where the workspace sandbox has not been consulted. Bytes are read
at the moment history is assembled, through the sandbox.

The attach path prepares the image before staging it
(`ImagePreprocessor.kt`). It decodes with a power-of-two `inSampleSize`, applies
the EXIF orientation, fits the long edge to 1568 px, and re-encodes to JPEG
(quality 85) or WebP (quality 90 for alpha/PNG/WebP/GIF sources), lowering
quality and then dimensions until the result is at most `maxReadBytes`
(1 MiB). Only the prepared copy is stored in `attachments/`; the original is not
kept, because the transcript stores a path and the model would never see the
full-size bytes anyway.

This is **not** a provider requirement or a billing optimisation: every vision
provider resizes before inference, and they bill by the resulting tokens, not by
file size. The local limit exists because the bytes are base64-inlined into the
request and held in memory on a phone -- and because provider byte limits differ
(Claude 5 MB, DeepSeek 32 MB inline) while this app supports any
OpenAI-compatible one. The provider's own resize rule is documented in
[DeepSeek's Vision guide](https://api-docs.deepseek.com/guides/vision): images
are scaled to roughly a 1300×1300 pixel budget and capped at 1024 tokens each,
so a 2000×2000 and a 5000×5000 image cost the same after resizing.

**Capabilities are checked before the image is ever built**
(`ChatTypes.kt:56-61`). Sending an image to a text-only model returns **200** and
the model answers about `[Unsupported Image]` while sounding like it looked.
Nothing at the transport layer can detect that, so the guard lives where the
model is known — `RemoteModel.supportsVision`
(`core/ai/src/main/kotlin/com/inhuman/core/ai/RemoteModel.kt:24`).

### Tool calls are echoed byte-for-byte

`ToolCall.argumentsJson` is the **assembled string as the provider sent it**,
not re-serialised from a parsed object (`ChatTypes.kt:23-27`). Re-serialising
would change key order and whitespace, and the transcript is compared against
what the provider produced.

### `ChatRequest` has no `maxTokens` or `temperature` set by the app today

`ChatRequest.maxTokens` exists and is forwarded when non-null
(`DeepSeekRequest.kt:38`), and `ChatRequest.temperature` the same way
(`DeepSeekRequest.kt:37`), but the app never sets either — grep the tree: the
only assignments of `temperature`/`maxTokens` on a `ChatRequest` are the
defaulted constructor parameters themselves. That is half of why a
conversation that reaches the context ceiling gets stuck
(`README.md:81-88`). `userId` **is** set per conversation: DeepSeek uses it for
KV-cache isolation and content-safety scoping, so one conversation's cache is
not shared with another (`ChatTypes.kt:152-159`).

### Cache is a prefix, and the request shape is part of it

DeepSeek's context cache is automatic and prefix-based; `prompt_cache_hit_tokens`
is the part reused, and there is no explicit "write cache" call. A different
prefix — even one that is a subset of an earlier prompt — is a different
request.

The cache is persisted in **prefix units**, at the end of the user input and at
the end of the model output (plus fixed intervals for long inputs). A pure-chat
turn `A+B` therefore leaves a unit at the user-input boundary `A+B`. Regenerating
its assistant answer sends `A+B` again: that exactly matches the user-input
unit, so the reused part is `A+B`; the old output unit `A+B+C` is ignored and the
new output gets a new unit. It is not "cache zero" and not a rewrite of the old
cache.

The tool case used to be different because the retry removed the `tools`
definitions: the same turn measured `prompt=2201 cache=2048` with tools and
`prompt=191 cache=0` without them. The fix is to keep the tool definitions and
append a **transient** system instruction after the cached prefix, telling the
model to answer in prose and not call one. The app refuses any call before
execution. The same turn then measured `prompt=2148 cache=1920` (89%): the old
prefix is reused, and the instruction plus new output are the only uncached
tokens. A Rewind checkpoint point does the same without the instruction, from a
checkpoint; the conversation measured `prompt=2204 cache=93%` there.

Tool calls are ordinary serialized messages once they are on the wire; there is
no separate "tool cache". They can hit the same prefix cache when the whole tool
definition + call/result prefix is byte-for-byte the request prefix — which is
what a checkpoint replay and the instruction-based final retry preserve. A retry
that removes the definitions cannot; a new tool call diverges at its new call id
anyway.

Neither path "rewrites the whole cache"; it either reuses a matching prefix unit
or creates a new one. Zero is shown as `cache 0%` in the usage summary, and the
expanded row spells out `from cache N of M`.

## Provider profiles

`provider_profiles` (Room v12) holds the chat backends. The built-in `deepseek`
row is seeded on create and by `MIGRATION_11_12`; every other row is a named
OpenAI-compatible endpoint. A profile stores the name, kind, base URL, reasoning
field mode, whether to request stream usage, and optional fallback model ids. The
API key is not in Room: it lives in `SecureStore` under a slot derived from the
profile id. The built-in DeepSeek profile reuses `deepseek.api_key`, so an
existing install keeps chatting without re-entering it.

`agents.providerId` selects the provider; `conversations.providerId` copies it at
creation, beside the model and effort copy. Changing an assistant therefore does
not rewrite an existing transcript's provider. The model cache in
`AgentRepository` is keyed by provider id, because two services can offer the same
model id with different windows and capabilities.

Profiles are deliberately **OpenAI-compatible**, not a set of native SDKs. The
request shape, SSE decoder, tool calls and usage parsing are shared with the
DeepSeek adapter below; a native Anthropic or Gemini protocol would need its own
decoder and is not part of this version. Base URLs must be HTTPS: HTTP would send
the bearer key in clear text, and the app has no cleartext exception.

`reasoningMode` controls three otherwise incompatible fields:

| Mode | Sends | Use |
| --- | --- | --- |
| `none` | neither `thinking`, `reasoning_effort`, nor `reasoning_content` | safe default for an arbitrary compatible server |
| `effort` | `reasoning_effort` only | OpenAI-style reasoning endpoints |
| `deepseek` | all three, exactly as the DeepSeek adapter needs | built-in DeepSeek |

`includeStreamUsage` controls `stream_options`; a compatible server that rejects
the field can turn it off. `/models` is read generically, including OpenRouter's
`architecture.context_length` / `architecture.input_modalities`; a provider with
no usable endpoint can still be used through the profile's fallback model ids,
which have no context-window or vision metadata until the endpoint answers. A
compatible endpoint that reports neither field will look text-only in the app,
because inventing vision support would send images to a model that may reject
them.

## The OpenAI-compatible adapter

`core/ai/src/main/kotlin/com/inhuman/core/ai/provider/deepseek/`.

### Endpoints

| Purpose | URL | Auth |
| --- | --- | --- |
| Chat, streaming | `https://api.deepseek.com/chat/completions` (OpenAI-compatible) | `Authorization: Bearer <provider key>` |
| Model list | `https://api.deepseek.com/models` | `Authorization: Bearer <provider key>` |
| Web search, DeepSeek backend | `https://api.deepseek.com/v1/tools/search` (Search API) | `Authorization: Bearer <web DeepSeek key>` |
| Web fetch, DeepSeek backend | `https://api.deepseek.com/v1/tools/fetch` (Fetch API) | `Authorization: Bearer <web DeepSeek key>` |
| Web search, Exa backend | `https://api.exa.ai/search` | `x-api-key: <Exa key>` |
| Web fetch, Exa backend | `https://api.exa.ai/contents` | `x-api-key: <Exa key>` |

`DeepSeekProvider.DEFAULT_BASE_URL` is `https://api.deepseek.com`, and the chat
POST is `"$baseUrl/chat/completions"` with `Authorization: Bearer`. The same class
serves every OpenAI-compatible profile: `id`, base URL and reasoning mode come
from the profile. `DeepSeekSearchApi` and `DeepSeekFetchApi` still default to the
DeepSeek host, but their key is the **web** key, not the chat provider's key.

### One shared OkHttp client, and why

`DeepSeekProvider.shared` is a single lazily-created client
(`DeepSeekProvider.kt:197-211`). This used to be a factory called per
`DeepSeekProvider`, and a provider is constructed **per turn** — so every round
trip of every turn got a new connection pool, a new dispatcher and a new
executor. Nothing was warm: no keep-alive, no TLS session reuse, a fresh
handshake for a conversation that had been talking to the same host seconds
earlier, and abandoned clients' threads alive until finalisation
(`DeepSeekProvider.kt:181-188`).

Settings, all deliberate (`DeepSeekProvider.kt:204-209`):

- `readTimeout(11, MINUTES)`. DeepSeek documents that the server closes the
  connection if inference has not begun within 10 minutes, and reasoning models
  routinely take a long time before the first token. The conventional 30-second
  timeout kills legitimate slow responses while they are still thinking.
- `connectTimeout(30, SECONDS)`.
- **`retryOnConnectionFailure(false)`.** A retried POST that already produced
  tokens duplicates the answer; the caller is better placed to decide whether
  re-running is safe.

### Cancellation actually closes the socket

`stream` is a `callbackFlow` whose `awaitClose { call.cancel() }` is the
important line (`DeepSeekProvider.kt:158-162`): cancelling the collector must
close the socket, not merely stop reading from it. Without it a cancelled
generation holds the connection until the read timeout while the server keeps
generating tokens nobody will read. A cancel also arrives in `onFailure` as an
`IOException` on a closed socket, and is reported as a clean close rather than a
failure so every user-initiated stop does not look like an error in the
transcript (`DeepSeekProvider.kt:79-86`).

### `okhttp-sse` is deliberately not used

Its automatic reconnection is actively harmful for LLM streaming: a silent
reconnect re-runs the completion and duplicates the answer, because these APIs
offer no `Last-Event-ID` resume. Reading the raw body line by line keeps the
socket lifecycle explicit, which is what makes cancellation immediate
(`DeepSeekProvider.kt:30-42`). `Retrofit` would be worse still — its converter
model fights incremental bodies.

### Failure delivery order — the bug that looked like a silent model

In `onFailure`, `trySend(Failure)` **must** come before `close()`:

```kotlin
trySend(StreamChunk.Failure(e.message ?: "network error", e))
close()
```

It previously read `close()` then `trySend(...)`, and **a chunk sent after the
channel is closed is dropped**. The collector then saw the flow end normally and
could not tell "the network died" from "the model finished without saying
anything". Every DNS failure, refused connection and TLS error arrived at the
view model as an empty successful stream: no error, no transcript entry, and a
reply bubble deleted for being empty. It looked like the model had nothing to
say (`DeepSeekProvider.kt:87-102`). Pinned by
`CallbackFlowFailureDeliveryTest`, because the behaviour is a fact about the
coroutines library rather than about that file. The same rule applies to any new
`callbackFlow` in this tree.

### SSE parsing

`core/ai/src/main/kotlin/com/inhuman/core/ai/stream/SseParser.kt` is
spec-compliant and fed one line at a time. `SseFrame` is **transport**;
`StreamChunk` is **provider semantics** — that split is the point, because SSE
has a spec and what goes inside `data` is where providers differ.

Four things it handles that a naive parser does not (`SseParser.kt:32-58`):

- **Comments.** Any line beginning with `:` is ignored. DeepSeek documents that
  a streaming request receives `: keep-alive` comments while inference has not
  begun, and warns that a client parsing the response itself must handle them. A
  parser that assumes every chunk is JSON dies on the first one.
- **Multi-line `data:`** lines must be joined with `\n`; taking only the last
  truncates the payload.
- **Named events** (`event:`) are preserved — Anthropic and the OpenAI Responses
  API put meaning there, and a parser that discards it cannot tell a
  `content_block_delta` from a `message_stop`.
- **A missing trailing blank line.** `endOfStream()` must flush, or the final
  frame — often the one carrying `finish_reason` — is lost.

`lastId` intentionally survives across frames, per the spec, because that is
what makes a reconnection resumable (`SseParser.kt:141-143`).

### The decoder

`DeepSeekStreamDecoder` is **stateful and single-use: one per response stream**
(`DeepSeekStreamDecoder.kt:41-43`). It remembers which regions are open so it can
emit matching `End` chunks, and flushes them at end of body — otherwise the UI
shows a streaming cursor forever (`DeepSeekStreamDecoder.kt:185-187`).

Three wire-format traps the file names explicitly:

1. **Reasoning and text are separate fields on the same delta.** Either, both,
   or neither may be present in one frame. Reading only `content` silently drops
   reasoning; assuming they arrive in separate frames mis-orders them. Reasoning
   is emitted first when a frame carries both, because emitting text first would
   render the answer above the thinking that produced it
   (`DeepSeekStreamDecoder.kt:94-103`).
2. **Tool-call arguments are indexed string fragments.** Parsing per fragment is
   the single most common way tool calling breaks. The **first** frame for a call
   carries the id, the name **and** the opening argument fragment together —
   emitting only the `Start` chunk there drops that fragment and the assembled
   JSON then fails with a missing opening brace, a failure that only appears once
   a tool is actually called with arguments (`DeepSeekStreamDecoder.kt:130-138`).
3. **`[DONE]` is a sentinel, not JSON.** It must be tested for before any
   parsing (`DeepSeekStreamDecoder.kt:55-62`).

A non-JSON frame becomes a `Failure` chunk carrying the head of the payload,
rather than being dropped — without it the message says only that something was
wrong with something (`DeepSeekStreamDecoder.kt:66-80`).

Two more decoder details that are easy to lose in a refactor:

- **`usage` is read before the choice**, so a usage-only frame with
  `"choices": []` is not dropped (`DeepSeekStreamDecoder.kt:85-89`).
- **A `[DONE]` that arrives *before* the `finish_reason` frame yields two
  `Finish` chunks** — `Finish(Unknown)` from the sentinel and then the real one.
  `sawFinish` guards only the sentinel path, there is no coalescing, and
  `DeepSeekStreamDecoderTest` pins the ordered case only. It has not been seen in
  practice, but a consumer that assumes exactly one `Finish` is assuming
  something the decoder does not guarantee (`DeepSeekStreamDecoder.kt:57-62`,
  `164-172`).

## The model list is fetched, never hardcoded

`DeepSeekModels` reads `GET /models` (`DeepSeekModels.kt:31-34`). The provider
reports per-model capabilities the app genuinely needs: `input_modalities`
decides whether an image attachment is offered at all, `context_window` bounds
the transcript, and `effort.supported_levels` populates the reasoning-effort
control. Hardcoding would work until DeepSeek ships a model, at which point the
app either omits it or offers it wrongly.

**The live ids and display names differ, and conflating them sends a display
name as a model** (`DeepSeekModelsTest.kt:25-28`):

| id (on the wire) | name (shown to a person) | vision | context | max output |
| --- | --- | --- | --- | --- |
| `deepseek-flash` | DeepSeek-V4.1-Flash | **yes** | 1,048,576 | 393,216 |
| `deepseek-v4-pro` | DeepSeek-V4-Pro | **no** | 1,048,576 | 393,216 |

Both advertise effort levels `low`, `high`, `max` with default `high`
(`DeepSeekModelsTest.kt:46-49`). `ChatViewModel.DEFAULT_MODEL` is
`"deepseek-flash"` (`app/src/main/kotlin/com/inhuman/ui/chat/ChatViewModel.kt:1484`).

Parsing is defensive on purpose: every field is read so that a missing or
wrongly-typed value becomes `null` rather than throwing, because a provider
adding a field must not make the model list disappear
(`DeepSeekModels.kt:50-62`). Two subtleties:

- `asStringOrNull` is **not** `jsonPrimitive.contentOrNull`, which is not a type
  check — it strips quotes off a string and stringifies everything else, so
  `"name": 42` became the display name `"42"` instead of being rejected. A test
  asserting that a wrongly typed field becomes null caught this
  (`DeepSeekModels.kt:112-115`).
- An integer field arriving as a string (`"1048576"`) is treated as **absent**.
  Being lenient would mean a value that displays correctly but might not be what
  the provider meant (`DeepSeekModels.kt:117-123`).

`api_capabilities.anthropic_messages.system_prompt_update` (`in-history` for
flash, `leading-only` for pro) appears in the live response fixture but **is not
read by any code** — verified: the string occurs only in
`DeepSeekModelsTest.kt` (lines 51 and 68). If the app ever moves chat onto the
Anthropic endpoint that field becomes relevant. The app does not read it today.

## Demo mode: a provider that answers from a script

`core/ai/src/main/kotlin/com/inhuman/core/ai/provider/demo/DemoProvider.kt`.
It ships rather than living in a test because the streaming path — provider
chunks → view-model state → Compose → rendered bubble — is the part most likely
to break and the part an API-key gate keeps out of reach (`DemoProvider.kt:15-28`).

What it does, precisely:

- Model: one entry, `demo-model`, "Demo model (no network)", context 64,000,
  max output 4,096, modalities `text` + `image`, effort `low`/`high`
  (`DemoProvider.kt:56-66`).
- Always emits a reasoning region first, then a 180 ms pause, then text
  (`DemoProvider.kt:72-85`).
- **Emits tool calls only if the request already offers them**
  (`DemoProvider.kt:175-176`, `fileCall`; `scriptedCalls` at `:147`). An assistant
  with file tools off gets prose and no calls. The script is: `write_file` a
  `demo/notes.txt`, then `delete_file` it. Arguments go out in 12-character
  fragments so the accumulation path is genuinely exercised, not skipped
  (`DemoProvider.kt:192-198`).
- With tools offered, the turn ends with `FinishReason.ToolCalls` rather than
  `Stop`, because a turn that asked for tools did not finish answering
  (`DemoProvider.kt:119-128`).
- The closing answers are **Markdown on purpose**: an unclosed fence, a bold span
  arriving in pieces, a list whose markers precede their text. A plain sentence
  exercises none of the incremental parser's awkward states
  (`DemoProvider.kt:261-293`).

Two special cases that exist for real reasons:

- **Type `plain` in a message to get prose instead of a tool turn.** Without it
  the long answer can only be read *after* answering a dialog, which makes the
  one thing the long answer exists to show — text parsed while it arrives — the
  one thing that cannot be watched (`DemoProvider.kt:151-160`).
- **The turn is inferred from the transcript, not counted.** A provider is
  stateless; a counter would desynchronise the moment a reply was regenerated or
  a branch switched, then start calling delete on a file that is already gone.
  Anything after the last user message is this turn's own work in progress
  (`DemoProvider.kt:131-146`).

## `web_search` and `web_fetch`: independently configured paid backends

The tools are unchanged in shape: `web_search` returns titles, links and excerpts;
`web_fetch` returns one page as Markdown. What changed is where they get it.

- **DeepSeek** and **Exa** are both supported for search and for fetch.
- Search and fetch are selected separately, so Exa search + DeepSeek fetch is a
  valid combination.
- The DeepSeek web key is **not** the DeepSeek chat key. A user can chat through
  an OpenAI-compatible profile and still use DeepSeek web tools, or chat on
  DeepSeek and use Exa. The four slots live in `SecureStore`:
  `web.deepseek.api_key`, `web.exa.api_key`, `web.search_backend`,
  `web.fetch_backend`.
- The old single-key behaviour is deliberately not copied on upgrade: it would
  make "independent" true in name only. Settings reports which backend has no key,
  and the tool is not registered until that backend's key exists.
- `WebToolProviders` resolves the selected provider per turn in `MainActivity`;
  `ChatViewModel` receives closures, not a captured instance, so changing a
  backend or key takes effect without reopening the conversation.

| Tool | Backend | Endpoint | Returns |
| --- | --- | --- | --- |
| `web_search` | DeepSeek | `POST https://api.deepseek.com/v1/tools/search` | structured result list |
| `web_fetch` | DeepSeek | `POST https://api.deepseek.com/v1/tools/fetch` | one page as Markdown |
| `web_search` | Exa | `POST https://api.exa.ai/search` with `contents.text` | result text per hit |
| `web_fetch` | Exa | `POST https://api.exa.ai/contents` with `text` | one page as text |

DeepSeek uses `Authorization: Bearer`; Exa uses `x-api-key`.

### Cost

- **DeepSeek Search/Fetch** were ¥5 per 1,000 successful requests when these
  direct APIs were adopted (2026-10-17); failures, empty results and timeouts are
  not charged. This price is provider-specific and can change.
- **Exa** bills per search/contents request under its own plan. The app does not
  hardcode a price that would be wrong for a user's plan.
- Both tools are marked `paid` in `ToolCatalog`. The assistant editor puts a
  filled **PAID** badge beside the name and a notice above the tool list; the
  notice says the configured search/fetch service bills per call, rather than
  naming a fixed DeepSeek price. The settings approval row carries the badge too,
  the approval dialog repeats the paid-API sentence when a rule makes a call ask,
  and a tool result in the transcript carries the badge on its collapsed header.
- Mutating tools also carry **REWIND** or **NO REWIND**, driven by
  `ToolCatalog.Entry.reversible`, in the same badge row. This is separate from
  paid: `ssh_exec` and `ssh_upload` are both `REMOTE` and `NO REWIND`; a local
  write is `REWIND`, and `ssh_download` is `REMOTE` but `REWIND` because it writes
  a workspace copy.
  The name is not expected to carry the consequence by itself.
- Why the badge is beside the name rather than left in a description is a
  design rule, not a styling choice:
  [08-port-philosophy.md](08-port-philosophy.md) §1.4.

### Search

`DeepSeekSearchApi` sends `{"query": "...", "count": 5, "need_content": false}`.
`count` is 1–50 in the API; the tool clamps `max_results` to 10 and defaults to 5.
`need_content` is **false**: results carry a 600–800 character summary, and
fetching every result would spend the context before the model knows which page
matters. The parser prefers `summary` over `snippet`, maps `date` to
`publishedAt`, and de-duplicates by URL.

`cleanUrl` drops tracking parameters (`utm_*`, `gclid`, `via`, …), keeps unknown
ones such as `page` or `ref`, and decodes HTML-escaped `&amp;` separators first.
Without the decode every parameter is named `amp;utm_...`, matches no rule, and
tracking survives into what the model cites. Non-2xx becomes an `IOException`
carrying the status and the first 300 characters of the body; 402 is balance and
429 is QPS.

`ExaSearchApi` sends:

```json
{"query": "...", "numResults": 5, "type": "auto",
 "contents": {"text": {"maxCharacters": 1200}}}
```

Exa returns each hit's readable text, which satisfies the "snippet, not just a
link" contract. The parser accepts `text`, then `summary`, then `author` as the
excerpt; a missing excerpt is null rather than an empty string, and a result
without a URL is dropped.

### Fetch

`DeepSeekFetchApi` sends `{"url": "..."}` and receives `{"title": ..., "content":
...}` where `content` is Markdown. `ExaFetchApi` sends `{"urls": ["..."], "text":
{"maxCharacters": 20000}}` and reads the first result's `text` and `title`.

- Only absolute `http`/`https` URLs, no credentials, at most 8192 characters.
  Validation happens before the request, so an invalid URL is a free local error
  rather than a paid round trip.
- `WebFetchTool` caps the Markdown at **20,000 characters** and appends
  `[truncated at 20000 characters]`. A clean ending would make a truncated page
  look complete.
- Blank content is an error, not a successful fetch of an empty page.
- **These tools never open the page.** The selected backend performs the request;
  the sandbox still has no network path of its own, and a URL cannot make the
  agent fetch anything. The user-initiated download in Files is a separate,
  user-owned path; the agent cannot invoke it.

### The tool wrappers

`WebSearchTool` (`core/ai/src/main/kotlin/com/inhuman/core/ai/tools/WebSearchTool.kt`)
formats results with title, URL, optional date and excerpt, says out loud when
fewer results came back than were requested, and treats an empty list as a real
answer ("No results for …") rather than an error. `WebFetchTool`
(`tools/WebFetchTool.kt`) puts the title, the URL and the body in that order and
uses the same error contract.

**Both tools are registered only when their selected backend has a key**
(`ChatViewModel.buildTools` → `WebToolProviders`). A tool that is present and
always fails teaches the model that it is broken and it keeps retrying; an absent
tool teaches it that it cannot do that, and it says so.

### What is not here

- No HTML parsing or readability pass in the app: DeepSeek returns Markdown.
- No page cache: every fetch is a new paid call, including fetching the same URL
  twice.
- No streaming fetch: it is one response, capped and returned whole.

### The live test

`DeepSeekWebLiveTest` is skipped unless `DEEPSEEK_LIVE_KEY` is set, because it
spends money on one search and one fetch. Run deliberately:

```powershell
$env:DEEPSEEK_LIVE_KEY = 'sk-...'
.\gradlew.bat :core:ai:test --tests '*DeepSeekWebLiveTest'
```

It checks the request shape that fixtures cannot, and it is what caught the
`&amp;` URL-encoding issue above.

## The SSH tools: read-only, transfer and exec, no host inventory

`SshClient` (`core/ai/src/main/kotlin/com/inhuman/core/ai/ssh/SshClient.kt`) wraps
**sshj 0.41.2**. JSch was tried first and rejected: its EdDSA implementation
throws `UnsupportedOperationException` on an `ssh-keygen -t ed25519` key, and
Ed25519 is the default key type today. sshj accepts the same key and brings
BouncyCastle transitively; that is the dependency-size cost of modern keys.

The client is deliberately narrow:

- one **exec** channel and one **SFTP** channel, no PTY, no port forwarding;
- 15 s connect timeout, a 120 s default command timeout and a 20,000-byte default
  output cap per stream; the user ceilings and model tightening are below;
- password auth, OpenSSH v1 private-key auth, optional passphrase;
- TOFU known hosts in `filesDir/ssh/known_hosts`, written as standard
  `host:port SHA256:…`; a changed key fails closed; an optional
  `hostKeyFingerprint` argument overrides first use and is stored. Settings →
  Pinned host keys lists them and can forget them, which is the only in-app
  recovery when a server legitimately changes its key;
- no host inventory and no credential store; SSH tool-call arguments are
  redacted before the transcript is persisted.

`SshExecTool` (`tools/SshExecTool.kt`) takes `host`, `port`, `username`,
`command`, and one of `password`, `privateKey` (OpenSSH text) or `identityFile`
(a workspace-relative path read through `SessionWorkspace`). The file path keeps
the key out of the tool arguments that are sent to DeepSeek. It is **not** a
secret store — the file is workspace content, so the model can also call
`read_file` on it, and the transcript would then hold the key. The app has no
separate credential store; do not leave long-lived keys in a workspace if that
distinction matters. A password/key supplied as a **tool argument** is sent to
DeepSeek with the request but is removed before the tool-call row is persisted;
the stored transcript keeps host, user, command and `identityFile`, not the
secret. Text the user typed into a message is still stored verbatim — that is
the conversation, and the consent dialog says so. The tool is `mutates = true` (a
replay would run the remote command again) and `needsApproval = false`; the
separate per-conversation consent gate is `ChatViewModel.gateEngineCall`, behind
the shared engine's approval hook, and the ordinary `ToolPolicy` applies after it.

`SshProbeTool` (`tools/SshProbeTool.kt`) is the read-only snapshot: one fixed
script, no model-supplied command, no sudo. It reports OS, kernel, uptime, load,
CPU count, memory, disk, inodes, failed systemd units, listening ports and Docker
containers when those commands exist. It is `mutates = false` and can be selected
without `ssh_exec`; a missing command becomes `unavailable`, not a failed probe.

`SshListTool` (`tools/SshListTool.kt`) and `SshReadFileTool`
(`tools/SshReadFileTool.kt`) are the SFTP-only read side. Neither runs a command,
so an assistant can be configured with probe/list/read/download and no
`ssh_exec`:

- `ssh_list` calls `SFTPClient.ls` once, non-recursively; directories sort first,
  then names; at most 500 rows; each row is type, size, mtime and name.
- `ssh_read_file` reads the head with a 64 KiB default ceiling, or the last
  `tailLines` from an SFTP tail window capped by the user's read ceiling (at most
  256 KiB). It refuses a NUL-containing file as binary and
  points at `ssh_download`; it does not truncate a larger head, because a
  silently partial config is worse than an error that says so.
- Both are `mutates = false`, `reversible = true`, and `remote = true`; they still
  need the once-per-conversation SSH grant, but not `ssh_exec`.

`SshWriteFileTool` (`tools/SshWriteFileTool.kt`) writes one small UTF-8 text file
directly, without a local copy or a heredoc: 64 KiB, optional `createParents`,
`overwrite` default false. It uses the same SFTP upload path and is `mutates =
true`, `reversible = false`; a remote file replacement cannot be undone by
deleting the conversation. The content is a tool argument and remains in the
transcript, unlike a password; do not use this tool for secrets.

### SSH limits: the user sets ceilings, the model may only tighten

`SshLimits` (`core/ai/.../ssh/SshLimits.kt`) is the single authority for the
resource ceilings. The effective value is:

```
clamp(requested ?: default, floor, min(userCeiling, hardMax))
```

The four ceilings are stored globally in `SecureStore` and edited in Settings:

| Setting | Default | Stored key | Model argument |
| --- | --- | --- | --- |
| command timeout | 120 s | `ssh.command_timeout_seconds` | `ssh_exec.timeoutSeconds` |
| output per stream | 20,000 bytes | `ssh.output_max_bytes` | `ssh_exec.maxOutputBytes` |
| directory rows | 500 | `ssh.list_max_entries` | `ssh_list.maxEntries` |
| text read | 64 KiB | `ssh.read_max_bytes` | `ssh_read_file.maxBytes` |

The model never sees or sets the ceiling. A missing request uses the app default,
not the ceiling; a request above the ceiling is clamped and the tool result starts
with `limits: ...`; a request below the floor is raised. The Settings UI offers
discrete values, so the stored ceiling is always in range. `ssh_probe` uses the
default command/output values with no model argument, and `ssh_read_file`'s tail
window is itself capped by the user's read ceiling.

### File transfer

`SshFileTransport`
(`core/ai/src/main/kotlin/com/inhuman/core/ai/ssh/SshFileTransport.kt`) is the
SFTP seam; `SshClient` implements it alongside `SshTransport`, so one instance
and one
`known_hosts` file serve both. `SshUploadTool` reads one workspace file through
the same `readWorkspaceFile` callback the exec tool uses for keys and writes it
with chunked `RemoteFile.write`; `SshDownloadTool` reads chunked
`RemoteFile.read` into memory and hands the bytes to `ChatViewModel`, which
resolves the destination through `SessionWorkspace` and imports it with
`WorkspaceTransfer`.

- Upload is capped by the workspace read limit (1 MiB), download by the write
  limit (4 MiB); the remote size is checked before a download starts.
- Both refuse to overwrite unless `overwrite=true`. Upload is `reversible =
  false` (`NO REWIND`) because replacing a remote file cannot be undone by
  deleting the conversation; download is reversible, because the bytes land in a
  workspace copy the rollback checkpoints can restore.
- The parent folder of an upload must already exist on the remote unless
  `createParents=true`; a download creates its workspace parent folders after
  `resolve` confines them. `ssh_upload` and `ssh_write_file` share this option.
- Binary-safe: no base64, no heredoc, no shell quoting. This is the reason the
  tools exist next to `ssh_exec`; a real session had 54 heredocs and commands up
  to 8.9 KB writing scripts to `/tmp`.

### Consent text follows the selection

`SshConsent` carries `canExec`, `canUpload` and `canWriteFile`, read from the
assistant/conversation selection at the moment the first SSH call is made. The
dialog has four bodies: commands + files when `ssh_exec` is selected, files with
upload when it is not, text-write when only `ssh_write_file` is present, and
read/download otherwise. The warning is chosen the same way, so a read-only
profile is never told that the agent can run commands or stop services.

`sshAllowed` is still one boolean for the conversation. To keep that from
silently covering a later expansion, `ChatViewModel.setConversationTools` calls
`widensDangerousSsh`: adding `ssh_exec`, `ssh_upload` or `ssh_write_file` to a
grant that did not have it clears `sshAllowed`, so the next call asks again with
the new text. Adding `ssh_probe`, `ssh_list` or `ssh_read_file` does not reset;
removing tools never does.

### The Android BouncyCastle trap

Android ships a **stripped** BouncyCastle under the provider name `BC`. sshj sees
that name, assumes a full installation, negotiates X25519 and fails with
`no such algorithm: X25519 for provider BC`. JVM tests do not catch it because
the full provider is present there. `SshClient.ensureBouncyCastle()` replaces the
stripped provider with the full `org.bouncycastle.jce.provider.BouncyCastleProvider`
before the first connection; the app still builds and the device test completed
a real password-auth handshake and exec against an embedded server.

The same device run then used a workspace file (`id_ed25519`, the repo's test
key) with `identityFile` and no password. The server needed BouncyCastle on its
own classpath to verify Ed25519 signatures; once it had one, the app logged
`auth=key`, the same fingerprint, and exit 0. Password auth and key auth are
therefore both device-tested, not just JVM-tested.

### Registration, not configuration

Each SSH tool is registered only when **all** of these are true: the global
Settings switch is on, the assistant/conversation tool selection explicitly names
that tool, and the matching transport exists (`SshTransport` for exec,
`SshFileTransport` for list/read/upload/download). No SSH tool depends on
another: `ssh_list`, `ssh_read_file` and `ssh_download` work with `ssh_exec`
absent. `ToolCatalog.permits` treats every entry with `remote = true` as
explicit-only: `null` means "everything" for historical assistants, but it does
**not** mean consent to SSH, and special-casing one name would silently grant the
other four. The app has no host editor, no allowlist and no credential store — the
user tells the model how to connect, or puts a key file in the workspace and tells
it the path.

## The tool catalogue

`core/ai/src/main/kotlin/com/inhuman/core/ai/tools/ToolCatalog.kt` is the
**possible** set, declared statically; the registry in `ChatViewModel.buildTools`
is the **available** set. They are different on purpose: the registry depends on
state that is not known when someone edits an assistant (is a search provider
configured? did the workspace open?), and a picker built from it would show
different options depending on unrelated state.

`permits(selected, toolName)` returns true when the selection allows the tool
**and the catalogue contains it** (`ToolCatalog.kt:89-92`). The second half
matters: a selection is a stored record and storage outlives releases, so an
assistant saved before a rename holds a name nothing answers to. Without the
membership check the function would answer a question about a string rather than
about a capability.

`selected == null` means every tool; `[]` means none. Both are reachable and
collapsing them would make one impossible to express (`ToolCatalog.kt:66-74`).
`allSelection()` returns `null` for "select all" so that a later release adding
a tool gives it to assistants that had chosen everything.

The 18 tools and their `mutates` flags are tabulated in
[05-files-sandbox.md](05-files-sandbox.md) and
[01-overview.md](01-overview.md).

## Tests in this module

All JVM, no device. Two live tests are skipped by default: `DeepSeekWebLiveTest`
(one search and one fetch, both paid) and `SshLiveProbeTest` (read-only SFTP
against a host whose credentials are in the environment).

| Test | Locks in |
| --- | --- |
| `SseParserTest` | Comments, multi-line data, named events, CRLF, flush-at-EOF |
| `DeepSeekStreamDecoderTest` | Reasoning/text separation, fragment accumulation, `[DONE]`, finish-reason mapping |
| `CallbackFlowFailureDeliveryTest` | **The failure-before-close ordering**, because the rule is a fact about the coroutines library |
| `DeepSeekModelsTest` | A fixture copied from a live `/models`: ids vs names, vision flags, wrongly-typed fields becoming null |
| `DeepSeekImageRequestTest` | The `image_url`-as-object shape |
| `DeepSeekSearchApiTest` | Result parsing, summary-over-snippet, date handling, URL de-duplication, tracking-parameter removal including `&amp;` |
| `DeepSeekFetchApiTest` | URL validation (scheme, host, credentials, length) and page parsing |
| `WebSearchToolTest` | The tool against a fake provider: count, empty result, failure text |
| `WebFetchToolTest` | Title/URL/body format, truncation marker, missing URL, provider failure |
| `TimeToolTest` | Zone handling and date arithmetic |
| `ToolCatalogTest` | null vs empty, renamed tools, ordering (reads before writes), SSH is never covered by `null` |
| `SshClientTest` | Password and Ed25519 auth against embedded MINA SSHD; TOFU reuse, changed-key refusal, explicit fingerprint, timeout, output cap, exit code |
| `SshFileClientTest` | SFTP against embedded MINA: binary round trip, directory listing with a cap, tail reads, upload parent creation, existing-file refusal and overwrite, download size limit, missing file |
| `SshReadToolsTest` | List formatting and directory-first ordering, head/binary refusal, tail last-lines and partial-first-line handling, missing auth, read-only flags |
| `SshProbeToolTest` | The fixed command, its read-only sections, missing auth, transport failure |
| `SshWriteFileToolTest` | Exact bytes including the final newline, size/null-byte refusals, overwrite/createParents travel |
| `SshLimitsTest` | Defaults below the ceiling, tighter requests, clamped widening, zero handling, stored-value clamping |
| `SshExecToolTest` | Password/identityFile/raw-key arguments, missing auth, host validation, wrapped `arguments` strings, transport errors |
| `SshFileToolsTest` | Upload/download argument travel, local and remote limits, overwrite, workspace-save refusal, irreversibility flags |
| `SshLiveProbeTest` | Skipped unless env credentials are set; lists `/tmp`, downloads `/etc/hostname` and runs the fixed probe, read-only |
| `DemoProviderTest` | The scripted turn, including that it only calls tools it was offered |
