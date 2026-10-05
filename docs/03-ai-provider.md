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
defaulted constructor parameters themselves, and the `max_tokens` the search
provider sends is its own literal, not this field. That is half of why a
conversation that reaches the context ceiling gets stuck
(`README.md:81-88`). `userId` **is** set per conversation: DeepSeek uses it for
KV-cache isolation and content-safety scoping, so one conversation's cache is
not shared with another (`ChatTypes.kt:152-159`).

## The DeepSeek adapter

`core/ai/src/main/kotlin/com/inhuman/core/ai/provider/deepseek/`.

### Endpoints

| Purpose | URL | Auth |
| --- | --- | --- |
| Chat, streaming | `https://api.deepseek.com/chat/completions` (OpenAI-compatible) | `Authorization: Bearer <key>` |
| Model list | `https://api.deepseek.com/models` | `Authorization: Bearer <key>` |
| Web search | `https://api.deepseek.com/anthropic/v1/messages` (Anthropic-compatible) | `x-api-key` **and** `Authorization: Bearer` |

`DeepSeekProvider.DEFAULT_BASE_URL` is `https://api.deepseek.com`
(`DeepSeekProvider.kt:166`), and the chat POST is `"$baseUrl/chat/completions"`
with `Authorization: Bearer` (`DeepSeekProvider.kt:68-69`);
`DeepSeekSearchProvider.DEFAULT_BASE_URL` is `https://api.deepseek.com/anthropic/v1`
(`DeepSeekSearchProvider.kt:157`), it appends `/messages`
(`DeepSeekSearchProvider.kt:88`), and it sends both `x-api-key` and
`Authorization: Bearer` (`DeepSeekSearchProvider.kt:92-93`).

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

## `web_search`: DeepSeek's own server-side search

This is the part of the AI layer most often misunderstood, so it is worth being
blunt about it.

**DeepSeek exposes no search endpoint.** Its web search is a **server-side tool**
on the Anthropic-compatible Messages API: the request declares
`web_search_20250305`, the model decides to use it, the search happens on
DeepSeek's side, and the results come back as content blocks. The app makes
**one model call and reads the sources out of the reply**
(`core/ai/src/main/kotlin/com/inhuman/core/ai/provider/deepseek/DeepSeekSearchProvider.kt:84-116`).

The consequences, in the order they surprise people:

1. **One search costs a whole model turn** in latency and tokens. It is not a
   lookup; it is a conversation with one answer in it. A measured run took close
   to a minute (`README.md:53-59`). On a hard question the model searches several
   times as it narrows the query.
2. **No second vendor and no second key.** The credential already configured for
   chatting is the one it uses. `MainActivity` passes
   `apiKey = { secure.get(SecureStore.KEY_DEEPSEEK_API_KEY) }` — a lambda, so a
   key added after a conversation was opened is picked up
   (`MainActivity.kt:227-229`, `DeepSeekSearchProvider.kt:50-58`, headers at
   `:89-93`).
3. **The Anthropic endpoint is required.** Declaring the server tool on
   `/chat/completions` returns **HTTP 422**; it is only understood on
   `/anthropic/v1/messages`. Declaring it there as an ordinary *function* tool is
   accepted but does something different and useless: the model asks *this app*
   to search, which is the thing there is no way to do
   (`DeepSeekSearchProvider.kt:34-40`).
4. **`max_uses` is a ceiling on the model's own judgement**, not a setting that
   decides anything alone. Default 3 (`DeepSeekSearchProvider.kt:61-68`, sent at
   `:137`).
   DeepSeek exposes no result-count parameter, so the only way to honour
   `maxResults` is to truncate the parsed list — and the caller is told the set
   was trimmed because `WebSearchTool` compares the count against what it asked
   for (`DeepSeekSearchProvider.kt:110-115`,
   `core/ai/src/main/kotlin/com/inhuman/core/ai/tools/WebSearchTool.kt:144-145`).
5. **The prose is discarded; only the structured blocks survive.**
   `DeepSeekSearchResponse` keeps `web_search_tool_result` blocks and their
   `web_search_result` items. The model writes a summary alongside the results
   and it is *not* used: a result set is evidence, a paragraph around it is a
   claim about that evidence produced by the same model the results are being
   handed to. Passing both would let the caller cite a synthesis as a source
   (`core/ai/src/main/kotlin/com/inhuman/core/ai/provider/deepseek/DeepSeekSearchResponse.kt:63-88`).
   The alternative — regexing URLs out of the model's prose — produces citations
   the model wrote rather than links the search returned, which is exactly the
   failure where a citation looks authoritative and points nowhere.
6. **Result URLs are stripped of tracking parameters** before the model sees
   them (`DeepSeekSearchResponse.cleanUrl`). Not cosmetic: a live search for a
   Kotlin release returned
   `?via=Knowgenerativeai.com&utm_source=Knowgenerativeai.com&…`, and following
   one tells a third party which query found them. Only parameters whose sole
   purpose is tracking are dropped — `ref` and `source` are deliberately kept
   because plenty of sites use them to select content, and breaking a working
   link is the worse failure (`DeepSeekSearchResponse.kt:136-143`).
7. **Snippets are cited text keyed by URL**, not nested in the result, and only
   some results have any. Absent stays absent rather than becoming an empty
   string, so "no excerpt" and "an empty excerpt" stay distinguishable
   (`DeepSeekSearchResponse.kt:101-111`).

The OkHttp client here is separate from the chat provider's
(`DeepSeekSearchProvider.defaultClient()`): connect 30s, **read 3 minutes**,
write 30s. The default 10-second read timeout is regularly exceeded because a
search is a whole model turn (`DeepSeekSearchProvider.kt:176-188`).

One asymmetry between the two clients, stated because it is invisible otherwise:
the chat client sets **`retryOnConnectionFailure(false)`**, the search client
does **not** — it leaves OkHttp's default of `true` and sets no call timeout. A
search is a read-only model turn whose worst case is paying for it twice, so a
retried connection failure is tolerable there in a way it is not for a stream
that may already have produced tokens.

### The tool wrapper

`WebSearchTool` (`core/ai/src/main/kotlin/com/inhuman/core/ai/tools/WebSearchTool.kt`)
is what the model calls, and `SearchProvider` is the seam behind it — an
interface with one implementation, written before the provider was chosen so
that the first implementation could not be wired directly to one vendor's JSON.
`max_results` defaults to 5 and is clamped to the tool's own limit
(`WebSearchTool.kt:119-122`, limit at `:72`). An empty result set says "No results for …"
plainly, because a model that receives silence tends to search again with the
same query (`WebSearchTool.kt:126-129`).

**`web_search` is registered only when a provider exists**
(`ChatViewModel.kt:1255-1260`). A tool that is present and always fails teaches
the model that the tool is broken and it keeps retrying; one that is absent
teaches it that it cannot search, and it says so.

### A live inconsistency worth knowing before it bites

`DeepSeekSearchProvider.DEFAULT_MODEL` is **`"deepseek-v4-flash"`**
(`DeepSeekSearchProvider.kt:156`). The id from a live `GET /models` is
**`deepseek-flash`** — `deepseek-v4-flash` does not appear in the provider's
model list, and the display name is `DeepSeek-V4.1-Flash`
(`DeepSeekModelsTest.kt:38-41`). The search provider shares no model constant
with the chat provider: it has its own default (`DeepSeekSearchProvider.kt:59`)
and is never passed the conversation's chosen model — the only construction in
the app, `MainActivity.kt:227-229`, passes the key and nothing else.
`GitHub`-style naming aside, `search` will send
`deepseek-v4-flash` on `/anthropic/v1/messages` and the outcome depends on
whether that endpoint accepts an alias the OpenAI-compatible `/models` list does
not advertise. This is a one-line change if search starts failing with a 400
naming the model — check here first.

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

The 10 tools and their `mutates` flags are tabulated in
[05-files-sandbox.md](05-files-sandbox.md) and
[01-overview.md](01-overview.md).

## Tests in this module

All JVM, no device. The one that needs a live key and is skipped by design is
`DeepSeekSearchProviderLiveTest`.

| Test | Locks in |
| --- | --- |
| `SseParserTest` | Comments, multi-line data, named events, CRLF, flush-at-EOF |
| `DeepSeekStreamDecoderTest` | Reasoning/text separation, fragment accumulation, `[DONE]`, finish-reason mapping |
| `CallbackFlowFailureDeliveryTest` | **The failure-before-close ordering**, because the rule is a fact about the coroutines library |
| `DeepSeekModelsTest` | A fixture copied from a live `/models`: ids vs names, vision flags, wrongly-typed fields becoming null |
| `DeepSeekImageRequestTest` | The `image_url`-as-object shape |
| `DeepSeekSearchResponseTest` | Block/item parsing, URL de-duplication, tracking-parameter removal |
| `WebSearchToolTest` | The tool against a fake provider: count, empty result, failure text |
| `TimeToolTest` | Zone handling and date arithmetic |
| `ToolCatalogTest` | null vs empty, renamed tools, ordering (reads before writes) |
| `DemoProviderTest` | The scripted turn, including that it only calls tools it was offered |
