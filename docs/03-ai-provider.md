# 03 — The AI layer: provider, streaming, tools, and web search

Module: `:core:ai`. It has **no Compose and no Activity**, which is what lets all
of it be tested on the JVM. If you are adding provider behaviour, it belongs
here; if you are adding UI, it does not.

## The two interfaces

`ChatProvider` (`core/ai/src/main/kotlin/com/inhuman/core/ai/ChatProvider.kt:19`)
is deliberately thin: `id`, `listModels()`, and
`stream(request): Flow<StreamChunk>`. The file states what is *not* in it —
retries, key management, persistence, tool execution — because those are policy
and belong to the layer with the context to decide. **That is still true of the
provider: the retry policy lives in `ChatEngine`, not here** (see "Retrying a
failed round" below).

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
retried and the other should not (`StreamChunk.kt:101-107`). The engine retries
the failure chunk; a `Finish` is trusted, including `Unknown`, because some
compatible servers simply stop without a finish frame.

## Retrying a failed round

The provider has no retry. `DeepSeekProvider` even sets
`retryOnConnectionFailure(false)`; OkHttp cannot know whether a retried POST
already produced billable tokens. The **engine** retries instead
(`core/engine/…/RetryPolicy.kt`, applied in `ChatEngine`), because it is the
first layer that knows what the failed attempt already did.

- **Five re-sends, six requests.** `RetryPolicy.DEFAULT_MAX_RETRIES = 5`. The
  delay starts at 1 s, doubles, and is capped at 15 s, with up to 250 ms of
  jitter; the fifth wait is 15 s, so exhaustion takes roughly half a minute
  before the manual Retry banner appears. The error says how many retries
  happened.
- **Only transient failures.** HTTP 408/425/429 and 5xx, an `IOException`
  (connection reset, DNS, TLS read, no response headers), or a message with a
  transient marker. Authentication, billing/quota exhaustion, context overflow,
  a malformed request and a certificate/hostname failure are not retried: they
  will not change, and five more sends only make the message arrive later.
  `StreamChunk.Failure.statusCode` is the HTTP status when one exists; the policy
  prefers it over matching the body, because a 400 whose JSON mentions a timeout
  is still a refused request.
- **Per round, never across a completed tool group.** A round is retried before
  its assistant message is committed and before any tool in it runs. A retry in
  round 2 therefore cannot re-execute round 1's `write_file` or `ssh_exec`;
  `ChatEngineTest` pins the tool execution count at one.
- **The failed attempt's output is discarded.** These APIs offer no resume, so a
  retry is a fresh completion. The engine clears its text/reasoning/tool builders
  and tells the host with `EngineEvent.Retrying`; the Android row drops its parts
  and replaces its delta channel so the incremental Markdown renderer starts from
  an empty document rather than appending the second answer to the first. Any
  tokens the provider billed for the abandoned attempt are not visible in the
  usage line, which reports the successful attempt.
- **`Finish` beats a closing socket.** An `IOException` that arrives *after* a
  `Finish` chunk is treated as closing noise, not as a reason to replay the
  answer. A decoder failure after `Finish` is not ignored this way; it can mean a
  lost tool call.

The manual **Retry** (`chatErrorAction`) remains for the case where five
re-sends did not help, and for non-transient failures where retrying would be
wrong.

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

**The app adds no image-count ceiling of its own.** The provider's documented
limits are the boundary: 600 images per request, a 48 MiB request body, 32 MiB
per inline image, 64 MiB of images per request without the Files API, and an
8192 px side that drops to 4096 px once a request carries 15 or more images. At
the app's 1 MiB prepared size the 48 MiB body binds long before the count does,
so a smaller client number would refuse requests the API accepts. The Android
composer stages a multi-pick one image at a time but does not cap the count.

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

**Workspace instructions are pinned, so an edit does not touch this prefix.**
The root `AGENTS.md` block is rendered into the system message and stored on the
conversation on its first request (`docs/06`). The app sends that stored string,
not the file, so editing `AGENTS.md` does not change an existing conversation's
first message: its cache prefix stays hit. New conversations pin the file as it
is then -- they are cold anyway. The chat menu's **Reload AGENTS.md** is the only
way an edit reaches an existing conversation; it replaces the pinned block,
changes the system message, and costs **one deliberate cold request**. On
DeepSeek that request can report `cache 0`: its cache is unit-based at message
boundaries, and the changed first message invalidates the unit that covered the
old prefix. A token-level prefix cache may still reuse the tokens before the
block, and the app does not claim either as a guarantee. The old units are not
deleted: the requests after the reload rebuild against the new prompt. The turn's
usage line is where the reload's cost becomes visible.

**Time hints are derived, so they do not move between turns.** When the setting
is on, `buildWireHistory` prefixes qualifying user messages with the time they
were sent (`docs/06`, "Time hints on the wire"). The prefix comes from
`messages.createdAt`, not from the clock, so the same row renders the same bytes
on every later request and the cache prefix still hits; only the message being
sent now gets a new prefix, and it sits at the end of the prefix where a new
turn is written anyway. Changing the mode or threshold, or the device time zone,
re-renders the marked lines and costs **one cold request**. The hint is never
stored in the message row.

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
| `opencode` | `reasoning_effort` and `reasoning_content`, but not `thinking` or `user_id` | OpenCode Zen/Go, whose DeepSeek models name the reasoning field `reasoning_content` without accepting DeepSeek's other fields |
| `deepseek` | all three, exactly as the DeepSeek adapter needs | built-in DeepSeek |

`includeStreamUsage` controls `stream_options`; a compatible server that rejects
the field can turn it off. `/models` is read generically, including OpenRouter's
`architecture.context_length` / `architecture.input_modalities`; a provider with
no usable endpoint can still be used through the profile's fallback model ids,
which have no context-window or vision metadata until the endpoint answers. A
compatible endpoint that reports neither field will look text-only in the app,
because inventing vision support would send images to a model that may reject
them.

Two things about `/models` are easy to get wrong and were:

- **Image input is not always spelled `"image"`.** OpenCode reports MIME types
  (`image/jpeg`, `image/png`, …). The parser collapses `image/*` to `image`
  (and likewise audio/video) and `RemoteModel.supportsVision` accepts either
  spelling. Without that, every OpenCode vision model looked text-only and the
  composer refused an attachment the model accepts.
- **Reasoning levels are not always `effort.supported_levels`.** OpenCode puts
  them under `capabilities.reasoning.effort` (`["low","high","max"]`), with
  `capabilities.reasoning.supported` beside them. The parser reads that shape as
  a fallback, which is what populates the effort chips for models that
  advertise levels.

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
- **`HeaderTimeoutInterceptor` (90 seconds).** It bounds only the wait for
  response headers, not the body. The 2026-10-25 log had two chat requests with
  no `HTTP 200` line at all and a spinner until the app was restarted; headers
  normally arrive in hundreds of milliseconds, so this ends a dead connection
  long before the eleven-minute read timeout. It throws
  `HeaderTimeoutException`, pinned by `HeaderTimeoutInterceptorTest`; the
  message contains "no response", so the error banner offers **Retry** without
  resending the user's message.
- `connectTimeout(30, SECONDS)`.
- **`retryOnConnectionFailure(false)`.** A retried POST that already produced
  tokens duplicates the answer; OkHttp cannot know whether this attempt already
  showed text or ran a tool. The engine retries deliberately instead, per round
  and only for transient failures (see "Retrying a failed round").

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

### The stream buffer is unlimited, because `trySend` cannot wait

Inside those callbacks there is no `send`: they run on OkHttp's dispatcher
thread, not in a coroutine, so nothing can suspend and there is no backpressure.
A `callbackFlow` channel is finite (64 slots by default), and a `trySend` that
finds it full **fails without a trace** — the token is gone, the reply has a hole
in it, and nothing in the diagnostics says a chunk was lost.

All three providers therefore fuse an unlimited buffer onto the flow
(`DeepSeekProvider.kt:248-262`, and the same tail in `AnthropicProvider` and
`GeminiProvider`). The memory ceiling is one reply's worth of text, which is
small next to the request that produced it. The two facts this rests on — the
default buffer drops, and `Channel.UNLIMITED` does not — are pinned by
`the default buffer drops what does not fit, and unlimited does not` in
`CallbackFlowFailureDeliveryTest`.

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

## Native Anthropic, Gemini and Responses adapters

`ProviderFactory` (`ProviderFactory.kt`) maps the stored profile kind to one of
four wire implementations:

| `provider_profiles.kind` | Adapter | Wire |
| --- | --- | --- |
| `deepseek`, `openai-compatible` | `DeepSeekProvider` | OpenAI Chat Completions |
| `anthropic-messages` | `AnthropicProvider` | Anthropic Messages |
| `google-generative-ai` | `GeminiProvider` | Gemini `generateContent` |
| `openai-responses` | `OpenAiResponsesProvider` | OpenAI Responses |

The database column stays a string so old profiles remain readable; no schema
migration was needed for the new kinds. The provider editor exposes the choices
as chips and sets the default base URL for each.

**Known providers** (`provider/ProviderCatalog.kt`) are the Pi-style shortcut:
Anthropic, Google Gemini, OpenAI (Chat Completions), OpenAI (Responses API),
OpenCode Zen (pay-as-you-go) and OpenCode Go (subscription), Z.AI Coding
(global/China), Qwen Token Plan
(global/China), Xiaomi MiMo and Token Plan, Kimi For Coding, MiniMax
(global/China), OpenRouter, Groq, Mistral, Cerebras and xAI. Picking one fills
the kind and base URL and asks only for the API key. "Customize endpoint" reveals
the base URL and manual-model fields for a proxy or an unlisted service. Account
/ web login is deliberately absent; every catalog entry is API-key
authenticated. Most entries fetch `/models`; if a plan endpoint does not expose
it, Customize accepts manual model IDs.

OpenCode Zen is `https://opencode.ai/zen/v1` and OpenCode Go is
`https://opencode.ai/zen/go/v1`; both use the OpenAI Chat Completions adapter and
both expose `/models`. Their `/models` shape describes context and output under
`limit`, input MIME types under `modalities.input`, and reasoning under
`capabilities.reasoning` (`supported` and `effort`). `DeepSeekModels` reads all
three, normalises `image/*` to `image`, and the catalogue gives both presets
`reasoningMode = "opencode"`; the migration to v20 moves existing OpenCode
profiles from `none` to that mode, because the old default silently dropped
`reasoning_effort`. Go requires a per-conversation `x-opencode-session`; the app
sends the existing `ChatRequest.userId` (the conversation id, also used by
DeepSeek for cache isolation), and a product `User-Agent` of `inhuman`.

**Anthropic** (`provider/anthropic/`): `POST /v1/messages`, `x-api-key`,
`anthropic-version: 2023-06-01`, system as a separate field, `tool_use` /
`tool_result` content blocks, images as base64 source blocks, and named SSE
events (`content_block_start`, `content_block_delta`, `message_delta`, …).
When the assistant's thinking toggle is on it requests `thinking` with a
`budget_tokens` that is always smaller than `max_tokens`; the decoder keeps the
`signature_delta` that follows a thinking block, the engine attaches it to the
assistant message, and the adapter re-emits `{"type":"thinking","thinking":…,
"signature":…}` before that message's text/tool blocks. Thinking is enabled only
when **every** assistant turn carrying reasoning or tool calls has both its
reasoning and its signature; a transcript written before this existed, or one
whose thinking was off, falls back to a thinking-off request instead of a 400.
Anthropic also rejects a non-default `temperature` while thinking is enabled, so
the adapter omits it in that case. Redacted thinking blocks are not replayed. Not
verified against a real Anthropic account, which needs a key.

**Gemini** (`provider/gemini/`): `POST /v1beta/models/{model}:streamGenerateContent?alt=sse`,
`x-goog-api-key`, `contents[].parts[]`, `functionCall` / `functionResponse`,
`inlineData` images, and `usageMetadata`. Tool calls arrive whole rather than as
argument fragments; the decoder still emits start/delta/end so the engine has
one path. A `thoughtSignature` on a `functionCall` part is captured per call,
stored in the call record and echoed on the same part when the call is replayed;
that is the signature Gemini requires to continue a tool turn. A signature on a
thought text part is stored on the message but not replayed. A conversation
recorded before this field existed has no signature to echo; continuing such a
Gemini tool turn may be rejected by the API, and the remedy is a new
conversation. Not verified against a real Gemini account, which needs a key.

**OpenAI Responses** (`provider/openai/`): `POST /v1/responses` with Bearer
authentication. `instructions` carries the system prompt; `input` is a list of
typed items (`message` with `input_text` / `input_image`, assistant `output_text`,
`function_call` with `call_id`, `function_call_output`). The adapter sends
`store = false` and never depends on `previous_response_id`: the app keeps the
transcript, as it does for every other provider. The stream is decoded from
`response.output_text.delta`, `response.function_call_arguments.delta` /
`response.output_item.*` and `response.completed`, with the same start/delta/end
neutral chunks. Function calls are mapped through their opaque `item_id` to a
stable call index. Reasoning is requested when the assistant's thinking toggle or
an effort level asks for it, with `include: ["reasoning.encrypted_content"]`;
completed reasoning items are persisted as opaque `providerState` and replayed
before the earlier call when reasoning is enabled, because `store = false`
otherwise leaves a tool turn with nothing to continue from. Reasoning text is
displayed as the summary; the encrypted content is never parsed or logged.
`temperature` is omitted while reasoning is enabled. Old rows have no state to
replay. Not verified against a real OpenAI account, which needs a key.

**Qwen Token Plan** is the first capability bundle rather than one chat
adapter. One `sk-sp-…` key serves several API families under
`https://token-plan.cn-beijing.maas.aliyuncs.com`:

| Capability | Endpoint |
| --- | --- |
| Chat | `/compatible-mode/v1/chat/completions` |
| Decision (Jev/System One) | `/compatible-mode/v1/systemone` |
| Image | `/api/v1/services/aigc/multimodal-generation/generation` |
| Video | `/api/v1/services/aigc/video-generation/video-synthesis` + `/api/v1/tasks/{id}` |
| TTS | `/api/v1/services/audio/tts/SpeechSynthesizer` |

`QwenTokenPlanClient` downloads the image/audio URL immediately because those
URLs expire; video is submitted as an async task. The live probe is
`QwenTokenPlanLiveTest` (skipped without `QWEN_TOKEN_PLAN_CN_API_KEY`), and the
verified models are `wan2.7-image`, `wan3.0-video`, `qwen-audio-3.0-tts-plus`
and `decision-model-preview`.

### Media backends: one choice per tool

The five media capabilities (`Image`, `Video`, `Speech`, `Transcription`,
`Decision`) no longer have to use the assistant's chat provider. Each is
configured independently under **Settings → 02 / Web & media**:

- **Assistant** (default): the conversation's provider, if its kind advertises
  the capability. This preserves the pre-setting behaviour.
- **Configured provider**: any profile under 01 / Models whose kind supports the
  capability (today the two Qwen Token Plan kinds), plus a model chosen from
  that profile's `/models`; the profile's own key and base URL are used.
- **Separate API**: a key, an HTTPS base URL and a model entered in the media
  section itself. The value is one `SecureStore` entry per capability, so the
  key is Keystore-wrapped and never enters the transcript.

`MediaToolConfig` (`app/…/ui/chat/MediaTools.kt`) is the stored shape; a blank
model means the capability's built-in default. `ChatViewModel.mediaBackend`
resolves the choice per turn and passes the model into the tool, so changing the
backend affects the next call rather than requiring a restart. A provider or key
that is missing leaves the tool absent; it is not registered and then failed.

The version notes below describe the original capability gate (the assistant's
provider chose the backend); the per-tool backend above is the current rule.

0.3.0 exposes the first user-facing slice: **Chat menu → Generate image**.
`ChatViewModel.generateImage` checks the profile's capability, downloads the
bytes, writes them through `SessionWorkspace.resolve`, applies
`quotaRefusal`, and stages the result as a pending attachment when the active
model can see images.

0.3.1 adds the decision slice: **Chat menu → Decision** runs the fixed
department/urgent/severity questions, and the agent gets a `decide` tool only
when the active provider advertises `Decision` and has a key. The tool returns
the typed JSON unchanged.

0.3.2 adds the `generate_image` tool (capability-gated, returns a workspace
path) and **Chat menu → Text to speech**, which saves an MP3 under
`generated/audio-*.mp3`.

0.3.3 adds **Chat menu → Generate video**: `wan3.0-video` is submitted as an
async task, the view model polls once every 15 seconds, progress survives
closing the dialog, and a completed MP4 is saved under `generated/video-*.mp4`.
The task id is not yet in Room, so a process kill loses the poll loop; speech-
to-text and a `generate_video` tool are later slices.

0.3.4 adds `generate_video` and `video_status` tools. `generate_video` submits
the task and returns its id; `video_status` polls it and downloads the MP4 into
the workspace on success. This keeps a two-minute render out of one blocking
tool call.

0.3.5 adds the `speak` tool, which saves TTS output as
`generated/audio-*.mp3`.

0.3.6 adds `transcribe`: Qwen ASR is **not** an OpenAI-style
`/audio/transcriptions` endpoint (that returned 404). It accepts a base64 data
URI in the same `input.messages` shape as image generation at
`/api/v1/services/aigc/multimodal-generation/generation`, model
`qwen-audio-3.0-asr-flash`, and returns the transcript at the top level. The tool
reads workspace audio, limits formats to mp3/wav/opus, and returns text.

0.3.7 adds media opening: the image, audio and video dialogs expose an
**Open/Play** action that builds the DocumentsProvider URI for the generated
file and launches the system viewer. The files were already ordinary workspace
files; this makes the result reachable without navigating to Files first.

0.3.8 persists an in-flight video task as `conversationId|taskId` in
`SecureStore` before polling. On process restart, `ChatViewModel.init` resumes
that task only for the same conversation, updates `videoStatus`, and clears the
checkpoint when the file is saved or the task fails. This is a checkpoint, not a
Room job table; a future migration can move it.

0.3.9 adds a **Transcribe** button to the TTS result. It reads the generated MP3
back through the workspace and calls the same ASR endpoint, so ASR now has a
direct path (for generated audio) as well as the `transcribe` tool.

0.4.0 adds custom decision questions: the Decision panel can switch from the
preset department/urgent/severity set to a JSON `questions` object, parsed and
validated locally before the same `systemone` call.

0.4.1 adds **Transcribe audio file** to the chat menu. It imports the chosen
mp3/wav/opus through `WorkspaceTransfer`, then runs the same ASR call and shows
the transcript; the file is left in `attachments/` and can be deleted normally.

**OAuth subscription login is a generic PKCE flow, not bundled provider clients.**
The provider row's OAuth button opens a dialog for a user-registered public
client: authorization endpoint, token endpoint, client ID, optional scopes and
client secret. The redirect is `inhuman://oauth`; the state and verifier live in
the encrypted store between the browser launch and the callback, and the
authorization code is exchanged with PKCE. Access and refresh tokens are stored
per provider in `SecureStore`, refreshed sixty seconds before expiry, and
removed by **Sign out** while the registration stays for a later sign-in.
Deleting the provider forgets both. A callback whose state does not match does
not cancel the pending flow; otherwise any app could abort a sign-in with a
forged intent. No Claude/ChatGPT/Copilot/Kimi client IDs are shipped: those are
registrations tied to a particular app and redirect URI, so a provider that only
offers its first-party client is not turnkey here. The flow is unverified
against a real account or registration.

**Not implemented yet:** custom per-provider headers and managed
Azure/Bedrock/Vertex credentials. `ProviderKinds.OPENAI_COMPATIBLE` still covers
most API-key plans (ZAI, Qwen, Xiaomi, Kimi, OpenCode) when the base URL and key
are entered manually.

Tests: `AnthropicMessagesTest` and `GeminiMessagesTest` pin request mapping,
text/tool SSE decoding, usage, the tool block index, the signed-thinking block
and the Gemini thought-signature round trip. `OpenAiResponsesMessagesTest` pins
the typed input items, the Responses event set, the reasoning parameter and the
opaque provider-state replay. `OAuthProtocolTest` pins PKCE challenges, URL
building, callback/state parsing and token/error parsing; `OAuthSessionTest`
pins the exchange, storage, forged-state behavior, refresh and sign-out with a
fake token endpoint. `ChatEngineTest` checks that a signature and provider
state streamed by a provider reach the assistant message and the next
request. `QwenTokenPlanLiveTest` verifies the real Qwen image and decision
endpoints when its key is present. They are offline by default; no key is
needed.

## Not every listed model is a chat model

A provider's `/models` is what the account can call, not what can answer a
message. Qwen's list includes `wan2.7-image`; picking it as a conversation's
model produced, in the transcript:

```
HTTP 400 Bad Request: {"code":"InvalidParameter","message":"Input should be
'user': input.messages.0.role & Input should be a valid list:
input.messages.0.content ..."}
```

Three things now exist because of that, and they are separate on purpose:

1. **A guess** (`core/ai/.../model/ModelCapability.kt`). `ModelCapabilities.infer`
   matches a short list of words in the id — image/video/speech — and calls
   everything else text. The list is deliberately short: a false positive hides
   a working model, a false negative only leaves a label off.
2. **An override.** The guess is stored as nothing; a user's tag is stored in
   `provider_profiles.modelTags` (`docs/04-data-persistence.md`) and **replaces**
   the guess rather than adding to it, so an image model can be declared usable
   as chat and an innocuous id can be declared not.
3. **A sentence.** `ModelRejection.looksLikeModelRejection` recognises the family
   of 400s that talk about messages, roles or content being invalid, and
   `ChatViewModel.rejectionNote` appends one line saying the model may not be for
   chatting. Every rule needs both a validation word and a message-shaped word,
   because telling someone "this model is wrong" on an over-long prompt would be
   worse than the raw error. A false negative leaves exactly what the app showed
   before.

The picker folds non-chat models behind a count (`Show N models not offered for
chat`) instead of dropping them: the guess may be wrong, and the fold is where
the tag editor is found. Both facts — that a model is folded, and that it can be
retagged — come from `ModelCapabilities.isChatModel`.
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

## Bilibili reads: anonymous, read-only, app-proxied

The four `bili_*` tools are the second "app reads something external for the
assistant" path, after the paid web tools, and the first one that is **free and
has no key**. `BilibiliClient`
(`core/ai/src/main/kotlin/com/inhuman/core/ai/bilibili/BilibiliClient.kt`)
implements the interface in `BilibiliTypes.kt`; the tools are in
`core/ai/src/main/kotlin/com/inhuman/core/ai/tools/Bilibili*Tool.kt`.

**What it reads.** Public web endpoints only: `x/web-interface/wbi/search/type`
for video and article search, `x/web-interface/view` for a video's metadata,
`x/v2/reply/main` for one page of comments, and `x/article/view` for a column's
text. The request is anonymous — `x/frontend/finger/spi` supplies the public
`buvid3`/`buvid4` browsing ids, `x/web-interface/nav` supplies the WBI key
images, and both live in memory for the process. **No Bilibili account, no
cookie jar and no stored credential** are involved, which is what makes "no
account required" true rather than a promise.

**Signature.** The search endpoint needs the WBI signature. `WbiSigner`
reorders the two key filenames into a 32-character mixin key, adds `wts` and
`web_location`, sorts and percent-encodes the parameters and appends `w_rid`.
The percent-encoding is hand-written because `URLEncoder` encodes `~`
differently from the server; `WbiSignerTest` pins the mixin key and the digest
against a fixed vector, because a wrong signature comes back as `-403`, which
otherwise looks like a server outage.

**Pacing and refusals.** Requests are 700 ms apart. `-509` (rate limited) gets
two pauses, 1.5 s and 3 s; `-403` (stale key) refetches the mixin key once.
`-352`/`-412` (risk control) are surfaced, not retried — retrying is what caused
them. `BilibiliErrors` turns those codes, and `-101`, into one sentence the
model can use, so "blocked" never looks like "there are no comments".

**The tools and their caps.** `bili_search` returns at most 10 hits from at most
page 5, `bili_video` caps the description at 2,000 characters and lists up to 10
parts, `bili_comments` returns at most 30 comments (400 characters each, 2
replies) and says it read one page, and `bili_article` caps the body at 20,000
characters with the same truncation marker `web_fetch` uses. Every argument is
a keyword, a BV id or a cv id — **no URL argument**, so a prompt injection
cannot turn a read into a general fetch.

**Registration is two gates.** The entries are `gatedByBilibili` and `optIn`.
While the global Settings switch is off, `ToolCatalog.visible(...)` and
`ChatViewModel.buildTools` do not build them at all: absent from the assistants
picker, the conversation sheet, the approval table and `unavailable_tools`, not
a greyed row. While on, `ToolCatalog.permits` still requires the assistant's
explicit selection — `null` ("all tools") predates the names and never includes
them. They are neither `paid` nor `remote`: the app makes the request, and the
assistant cannot reach anything that leaves a workspace copy or another machine.

**The content is untrusted.** Titles, descriptions, comments and article bodies
are third-party text that becomes prompt context. Tool descriptions say so, and
`BilibiliHtml` reduces both HTML shapes to plain text; a markdown renderer must
never see it as markup.

### The optional read-only login

Anonymous Bilibili cannot read a video's speech: `player/wbi/v2` answers with an
empty subtitle list, and the AI-summary endpoint answers `-101`. The optional
login therefore exists for **content that only a signed-in caller can see** —
never for account data.

`BilibiliAuth` (`core/ai/.../bilibili/BilibiliAuth.kt`) runs Bilibili's QR flow
against `passport.bilibili.com`: generate, poll every two seconds, and on
success take `SESSDATA` and `DedeUserID` from the poll's own `Set-Cookie`
headers. There is no webview and no password field — the account is
authenticated in Bilibili's own app, and the app only ever sees the cookies.
`bili_jct`, the write/CSRF token, is **not stored**: this app has no write path,
so keeping it would be capability with no use.

The host stores the two cookies as one JSON document under
`SecureStore.KEY_BILIBILI_SESSION` (settings → 02 / Web & media → B 站登录). The
client reads that document per request, so unlinking takes effect on the next
tool call. Backups exclude the secret store; diagnostics never see it. The
settings dialog renders the QR with ZXing, rotates it automatically when
Bilibili expires it (about three minutes), and closes on cancel without
touching an existing link.

What the login unlocks:

- `bili_transcript` — subtitles of the selected part, else Bilibili's own AI
  summary. The result names the source; an AI summary is Bilibili's digest, not
  a transcript, and the tool description says not to present it as the
  speaker's words. It is hidden while no account is linked (`optIn` plus
  `bilibiliLoginRequired`), because a row that would always fail is worse than
  an absent one.
- Comment pages after the first: the endpoint documents page 2+ as requiring a
  signed-in caller. `bili_comments` takes a `page` argument and says so when no
  login is linked.

The login does **not** add tools for the user's history, favourites, followings
or messages: reading those would turn a public-content reader into an
account-data reader, which is a separate product decision. While linked, all
Bilibili reads carry the account identity — the settings copy says so.

**Coverage is not universal.** In a 16-video sample across tech/course, lecture,
review, music, gaming and documentary searches, 12 had subtitle tracks and 10
had an AI summary; the three pure-music videos had neither. A video with neither
returns an explicit "no subtitles and no AI summary" answer, not an empty
success — the tool description tells the model to report that rather than guess.

**Tests.** `BilibiliParsersTest` runs against fixtures captured from the live
endpoints (2026-11-20) so the parsing is offline and the shapes are real;
`BilibiliToolsTest` covers caps, argument rejection and error text against a
fake service; `BilibiliAuthTest` pins the QR state mapping and cookie parsing.
`BilibiliLiveTest` (anonymous) and `BilibiliLoginLiveTest` (a linked session read
from `INHUMAN_BILI_SESSION`, a gitignored scratch file) are skipped unless
enabled. Run:

```powershell
$env:INHUMAN_BILI_LIVE = '1'
.\gradlew.bat :core:ai:test --tests '*BilibiliLiveTest'

$env:INHUMAN_BILI_SESSION = 'C:\path\to\session.json'
.\gradlew.bat :core:ai:test --tests '*BilibiliLoginLiveTest'
```

The anonymous live test is deliberately paced: an anonymous caller that fires
seven requests in two seconds is the shape of a scraper, which is what the
endpoint set's risk control blocks.

## The SSH tools: read-only, transfer and exec, an address book and an information tool

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
- **Settings holds an address book, not a credential store.** Any number of
  entries, each an alias, host, port, username and an optional pinned host-key
  fingerprint -- and **no password or private key**, so no call can ever use a
  credential the app stored. The list is one JSON document in `SecretStore`
  (`SshHostStore`, key `ssh.hosts`), absent from the database, a backup and the
  diagnostics log, because the addresses are private too. The single host an
  earlier release saved (`ssh.saved_connection`) is converted once -- address
  and user kept, secrets dropped -- and the old key removed.
- **`ssh_hosts` shows a conversation the entries it was given.** The tool is
  read-only, opens no socket, returns no credential, and is hidden while the
  global SSH switch is off. `conversations.sshHostsJson` (schema v22) lists the
  ids it may return; only the chat menu's host sheet writes that, a change
  applies to the next turn, and **it does not reset the conversation's SSH
  grant** -- it grants nothing, so there is nothing to revoke. An entry deleted
  in Settings simply stops appearing.
- **The connection is in the call.** `host`, `port`, `username`, plus
  `identityFile` (a workspace path, read through `SessionWorkspace`) or
  `password`; an inline `privateKey` is accepted for the same reason. A password
  or key text sent this way becomes part of the conversation and is visible to
  the model provider -- the tool description says so -- and a key file in the
  workspace is readable by `read_file`, which is what "the workspace is the
  boundary" means. Nothing resolves an alias from the address book into a
  connection.
`SshExecTool` (`tools/SshExecTool.kt`) takes the connection in the call --
`host`, `port`, `username` and one of `identityFile`, `password` or `privateKey`
-- plus `command`, `timeoutSeconds` and `maxOutputBytes`. There is no alias
lookup and no `useSavedHost`. `SshArguments.parse` reads the workspace key
through the same callback every file tool uses, and refuses a call without any
authentication. A password or key supplied in the call is sent with the request
and removed from the stored tool-call row (`SshArguments.redact`), but the text
the user typed into a message is still stored verbatim -- that is the
conversation, and the consent dialog says so. The tool is `mutates = true` (a
replay would run the remote command again) and `needsApproval = false`; the
separate per-conversation consent gate is `ChatViewModel.gateEngineCall`, behind
the shared engine's approval hook, and the ordinary `ToolPolicy` applies after
it. Every remote tool uses the same shape: one argument set, one parser.

`SshHostsTool` (`tools/SshHostsTool.kt`) is the only place the address book
reaches the model: a no-argument, read-only tool that returns the aliases the
user shared with this conversation. It is in the catalogue as
`optIn = true, gatedBySsh = true` -- chosen explicitly like the other SSH tools,
hidden while the switch is off, and **not** `remote`, so listing names never
triggers the connection consent.
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
assistant/conversation selection at the moment the first SSH call is made, plus
`hosts`: the labels of every machine the conversation may reach. The dialog has
four bodies: commands + files when `ssh_exec` is selected, files with upload when
it is not, text-write when only `ssh_write_file` is present, and read/download
otherwise. The warning is chosen the same way, so a read-only profile is never
told that the agent can run commands or stop services. The host list is shown
because the grant is per conversation, not per call: allowing the first call
allows every listed machine for every later turn, and naming only the first host
would understate what was granted.

`sshAllowed` is still one boolean for the conversation. To keep that from
silently covering a later expansion, `ChatViewModel.setConversationTools` calls
`widensDangerousSsh`: adding `ssh_exec`, `ssh_upload` or `ssh_write_file` to a
grant that did not have it clears `sshAllowed`, so the next call asks again with
the new text. Adding `ssh_probe`, `ssh_list` or `ssh_read_file` does not reset;
removing tools never does. `ChatViewModel.setSshHosts` applies the same rule to
the host list: changing which machines are selected is a different grant, so it
clears `sshAllowed` too. The two rules compose — a conversation can widen its
tools and its hosts in one turn and still be asked once, for the combination.

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
that tool, the matching transport exists (`SshTransport` for exec,
`SshFileTransport` for list/read/upload/download), and **the conversation has at
least one host selected**. No SSH tool depends on another: `ssh_list`,
`ssh_read_file` and `ssh_download` work with `ssh_exec` absent.
`ToolCatalog.permits` treats every entry with `remote = true` as explicit-only:
`null` means "everything" for historical assistants, but it does **not** mean
consent to SSH, and special-casing one name would silently grant the other four.
The app saves any number of hosts; a conversation may use only the ones the user
checked, and the model learns them by name from a block in the system message.

## The tool catalogue

`core/ai/src/main/kotlin/com/inhuman/core/ai/tools/ToolCatalog.kt` is the
**possible** set, declared statically; the registry in `ChatViewModel.buildTools`
is the **available** set. They are different on purpose: the registry depends on
state that is not known when someone edits an assistant (is a search provider
configured? did the workspace open?), and a picker built from it would show
different options depending on unrelated state.

### `unavailable_tools`: the gaps the user can close

`ToolGaps.compute` (`tools/ToolGaps.kt`) subtracts the registry from
`ToolCatalog.visible(...)` and labels each remaining name as either **not
selected** for this conversation or **needs configuration** (the selection
includes it but the host could not build it -- a missing key or backend). The
`unavailable_tools` tool returns that list, so a model that cannot read a file
can say "read_file is not enabled in this conversation" instead of "I cannot
read files".

The universe is `visible`, not `all`, and that is the load-bearing part: a
capability the app has switched off (SSH while the global switch is off, the
sandbox after a failed probe, the follow-up tool when off) is absent from the
universe and therefore **never named**. Otherwise this tool would be the
"greyed row" that `docs/08-port-philosophy.md` §1.2.1 refuses, in text form, and
a prompt injection could learn the name of a capability the user turned off.
`ToolGapsTest` pins both halves: a selection gap is reported, and no `ssh_*`
name can appear when SSH is off.

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
