# 07 — Gotchas: what is load-bearing, and what is currently wrong

Read this one when you are about to change something and have not read the rest.
Every entry was verified against the source, and the file and line are given so
you can re-verify cheaply.

Three sections: **A** is rules that will silently break something if undone,
**B** is things that are wrong today, and **C** is places where a comment claims
something the code does not do. Section C is this repository's definition of a
bug, not a nitpick.

---

## A. Load-bearing — undoing these restores a bug

### A1. A failure chunk must be `trySend`-ed *before* `close()`

`DeepSeekProvider.kt:87-102`. A chunk sent after the channel closes is **dropped**,
and the collector then sees the flow end normally. It previously read
`close()` then `trySend(...)`, so every DNS failure, refused connection and TLS
error arrived at the view model as an empty successful stream: no error, no
transcript entry, and a reply bubble deleted for being empty. It looked like the
model had nothing to say. Pinned by `CallbackFlowFailureDeliveryTest`, because the
rule is a fact about the coroutines library rather than about that file.

### A2. The closing `appendMessage` is wrapped in `NonCancellable` when a turn ends

`ChatViewModel.kt:1040`. A cancelled coroutine refuses to suspend, so without it
`appendMessage` throws and the part of the answer already received never reaches
the database — after the user was billed for it.

### A3. `SessionWorkspace.resolve()` is the only place a path becomes a `File`

`SessionWorkspace.kt:41-113`, and the rule is restated in `WorkspaceTool.kt:7-13`.
No symlinks, ever, plus a canonical-prefix check with the `+ separator` that
closes the sibling-prefix hole. Nothing else may construct a path from model
input — including `WorkspaceDocumentsProvider`, which routes its ids through
`resolve` for exactly this reason (`WorkspaceDocumentsProvider.kt:249-256`).

### A4. Message order needs its tie-break, and it is expressed twice

`ORDER BY createdAt ASC, id ASC` in both message queries must equal the Kotlin
comparator inside `ConversationStore.walkActive` (`InhumanDatabase.kt`). A tool
result and the turn that asked for it routinely share a millisecond. Two rules
that disagree would render the transcript in one order and choose branches by
another.

Note the second expression: `ConversationRepository.branches` cannot see the
private comparator and reproduces "newest" as `children.last()`, relying on
`childrenOf`'s ORDER BY. If you change one, change both. One walk now serves the
full rows, search's `BranchRow`s and the rewind gate's `MessageTurnRow`s, so
those three cannot disagree with each other.

### A5. `mutates` and `needsApproval` are different questions

`Tool.kt:43-84`. `mutates` = "would replaying this with tools change state?
afterwards"; `needsApproval` = "may this call happen?" *before*. `mutates`
deliberately has **no default**: a default `false` on a writer is how a retry
with tools writes twice. Regeneration keeps the tool definitions for prompt-cache
matching but suppresses calls, so this flag is a record about replay-with-tools,
not a blanket refusal. `needsApproval` **takes the arguments**, because "writing a
new file may be fine while overwriting an existing one is not".

### A6. The quota check walks the tree once

`SessionWorkspace.usage()` (`:155-167`). Asking for bytes and count separately
traversed the workspace twice per write: 106 ms per `write_file` in a 2,000-file
workspace, ~86 ms of it walks. `usedBytes()` and `fileCount()` each call `usage()`
again, so calling them in turn restores the cost — which `FilesViewModel.refresh()`
currently does (`FilesViewModel.kt:138-141`).

### A7. Tool-call arguments are fragments, and the first frame carries the opening one

`StreamChunk.kt:69-79` and `DeepSeekStreamDecoder.kt:130-160`. Parsing per
fragment is the most common way tool calling breaks. Emitting only the `Start`
chunk on the frame that opens a call drops the opening argument fragment, and the
assembled JSON then fails with a missing brace — a failure that only appears once
a tool is actually called with arguments. `ToolCallDelta` must also
**concatenate** the name fragments (`ChatViewModel.kt:945-949`); keeping only the
first produced a tool named `read_` instead of `read_file`.

### A8. `ToolCallStart.index` is the provider's ordering key, not arrival order

`StreamChunk.kt:56-62`. Parallel calls stream interleaved; keying on arrival order
silently corrupts every call after the first.

### A9. Reasoning must be persisted and replayed whenever thinking was on

`ChatTypes.kt:91-117`, measured: thinking on with `reasoning_content` omitted is
**HTTP 400 whether or not tools are present**. The documented rule ties it to
tools; the measurement says it follows the thinking state. A transcript that
drops reasoning works right up until a thinking turn is replayed, then fails with
a 400 that points at the wrong thing.

### A10. `image_url` is an object, not a string

`DeepSeekRequest.kt:114-124`. The bare-string form returns 422. And **vision is
checked before the image is built**, because sending an image to a text-only
model returns **200** and the model answers about `[Unsupported Image]` while
sounding like it looked (`ChatTypes.kt:55-61`).

### A11. `mutated` is stored, and written *after* the row exists

`Entities.kt:298-318`. Derived flags would silently reclassify history when a
tool is renamed or removed, and then re-run a real write. `markMutated` is a
separate statement (`InhumanDatabase.kt:101-108`) because tools run after the
stream closes; without it the record disappears on restart. It no longer gates
regeneration: the final-answer retry keeps definitions but suppresses calls.

### A12. `ToolsColumn` garbage decodes to "every tool"

`Entities.kt:163-182`: `""` → `null` → everything; `"[]"` → nothing; malformed →
`null` → **everything**. A typo widens permissions rather than narrowing them.
That is the safe direction and it is deliberate.

### A13. `null` and `[]` tool selections are different answers

`ToolCatalog.kt:66-92`. `null` = everything including tools added later; `[]` =
nothing. Collapsing them makes one of the two impossible to express.
`allSelection()` returns **`null`**, not the full list. A new assistant gets the
explicit ten-name list instead (`AgentRepository.kt:85-98`).

### A14. Workspace rows and directories are ordered for the sweep

`ConversationRepository.create` writes the temporary workspace row first, then
the conversation row, then `mkdirs()`; `delete` removes the directory first and
the rows second. That ordering is what makes the orphan sweep safe: an
interrupted operation leaves a directory with no workspace row, which the sweep
can find, rather than a row pointing at a missing directory, which nothing can.
A permanent workspace rows-first is also what lets an empty workspace survive
startup.

### A15. `runBlocking` appears only around two reads in the provider

`WorkspaceDocumentsProvider.kt`. Every method runs on a Binder thread that
DocumentsUI blocks on, hence `MAX_CHILDREN = 500` and the caps. The two are
`workspaces().byId(documentId)` — resolving the document id that the provider
itself publishes — and `workspaces().all()` for the root listing. Both are
primary-key or small-table reads; a conversation lookup must not be used here
because a permanent workspace can have several conversations.

### A16. The demo script reads the transcript, not a counter

`DemoProvider.kt:131-146`. A provider is stateless; a counter would desynchronise
the moment a reply was regenerated or a branch switched, then call delete on a
file that is already gone.

### A17. Vertical rhythm lives in `Layout.Space`

`Layout.kt:50-92`. It was literals once, and the transcript spent more vertical
space on the gaps than on the things: a one-line collapsed tool result stood 53dp
tall with an 18dp gap.

### A18. Web keys are not chat keys, and there is no fallback

`WebToolProviders` reads `web.deepseek.api_key` / `web.exa.api_key`, never
`deepseek.api_key` or `provider.<id>.api_key`. A "convenience" fallback would
silently authorize a paid service from a credential the user configured for
something else, and rotating the chat key would change who bills the search. The
chat key alone must leave the search tool unregistered; `WebToolProvidersTest`
pins that.

### A19. Reasoning fields are not universal

`thinking`, `reasoning_effort` and `reasoning_content` are three different
contracts. `reasoning_mode = none` must send none of them; an arbitrary
OpenAI-compatible server that rejects unknown fields otherwise fails on the first
message. The built-in DeepSeek profile is the only seeded row with
`reasoning_mode = deepseek`. The wire options are pinned by
`ProviderWireOptionsTest`.

### A20. The DeepSeek key has three lives, deliberately

The built-in profile keeps the legacy `deepseek.api_key` slot so an upgrade does
not ask for the key again. Custom profiles use `provider.<uuid>.api_key`. Web
tools use neither. Do not "simplify" these into one slot: the first is a chat
credential, the second is a per-profile credential, and the third is a paid-tool
credential with a different blast radius.

### A21. An automatic retry is per round, and it discards that round's output

`ChatEngine` retries a failed provider request itself, up to
`RetryPolicy.DEFAULT_MAX_RETRIES` (five re-sends). Three properties are
load-bearing:

- **The unit is a round, not the turn.** Retrying the turn would replay every
  completed round's tool calls, including a write or `ssh_exec`. The engine
  retries only the request that failed, before its assistant message is
  committed and before its tools run.
- **The failed attempt's bytes are dropped.** These APIs offer no resume. The
  engine clears its accumulators and emits `EngineEvent.Retrying`; the host
  simulates a fresh row by clearing `parts` and replacing the `deltas` channel.
  Appending a retry to the partial text produces `HiHi`-style duplicates that
  are invisible until the stream ends.
- **A `Finish` followed by an `IOException` is a clean answer.** The closing
  socket is not a reason to pay for the completion again; only an `IOException`
  after `Finish` is ignored this way, because a decoder failure can mean a lost
  tool call.

`ChatEngineTest` pins the tool execution count across a later-round retry, the
five-retry exhaustion, the permanent-failure path, and the post-`Finish` case.

### A22. Bilibili reads are absent when off, and they take ids — never URLs

The ten `bili_*` tools are an app-proxied external read, not a new remote
capability, and three rules keep them from becoming a general fetch:

- **The global switch hides them completely.** While it is off,
  `ToolCatalog.visible(includeBilibili = false)` and `ChatViewModel.buildTools`
  do not build them: no picker row, no approval row, no request schema, and no
  name in `unavailable_tools`. A greyed row (or a named gap) still advertises
  the capability.
- **`optIn` means `null` ("all tools") never includes them.** The null selection
  predates the names; an external read must not appear in every existing
  assistant because a release added it. `ToolCatalog.permits` answers false for
  a null selection and only a stored name turns one on.
- **Arguments are an id or a keyword, never a URL.** `bili_search` takes a
  keyword (plus filters), `bili_video`/`bili_comments`/`bili_transcript`/
  `bili_danmaku` a BV id, `bili_comments` an optional cv id, `bili_article` a cv
  id, `bili_up` a mid, `bili_bangumi` an ss/ep id, `bili_live` a room id or mid.
  A prompt injection cannot turn a read into "fetch this address", and the client
  only ever contacts its own fixed endpoints. The one download in the whole path
  is the subtitle file, and it is host-allowlisted (`*.hdslb.com` /
  `*.bilibili.com`) and fetched without cookies.

Two smaller rules follow from the content being third-party text:
`BilibiliHtml` flattens both HTML shapes to plain text (a renderer must never
see it as markup), and a refusal (`-352`/`-412`/`-509`/`-101`) is returned as
the mapped sentence from `BilibiliErrors` rather than as an empty page — "no
comments" and "the request was blocked" must stay distinguishable
(`BilibiliToolsTest`).

**Three invariants came with the second read surface** (uploader, discovery,
shows, live, danmaku), and undoing one silently makes a result overstate what it
knows:

- **`bili_danmaku` is a sample and says so.** Bilibili returns a bounded subset
  per six-minute window, so the result names the video's window count, which
  windows were read (first/middle/last by default, at most three, or those a
  `from`/`to` window touches) and that it is not every danmaku. The protobuf is
  decoded by the hand-written `BilibiliDanmakuParser` (varint reader, no
  dependency), which must stay total: a truncated body means "nothing parsed",
  not a thrown tool call.
- **The ranking's `rid` and the search's `tids` are different namespaces.** The
  same number means different buckets on the two endpoints (`188` is "digital" to
  the ranking and adjacent to it in search; `36` is "knowledge" to search and
  "social science/law" to the ranking). `BilibiliRankingCategory` maps friendly
  names only against verified ranking buckets, and `bili_search` takes the raw
  `tids` number with documented examples instead of sharing a name table. Do not
  "fix" this by reusing one enum for both.
- **The uploader page's dynamic section is optional.** The feed is a union of
  post shapes and can be empty; a failure there must not take the profile and
  videos down with it, so the client reads it with `runCatching` and the parser
  drops posts with no extractable text.

**The login is read-only by construction.** `BilibiliAuth` uses Bilibili's QR
flow only (no webview, no password field) and stores exactly `SESSDATA` and
`DedeUserID`. `bili_jct` is the write/CSRF token and is deliberately **not**
kept: the app has no Bilibili write path, so holding it would be capability with
no purpose. `bili_transcript` is `bilibiliLoginRequired`, so it is absent from
every picker while logged out, and the result names its source — an AI summary
is Bilibili's digest, not a transcript. Comment pages after the first are
another login-only read and say so when anonymous. The login *never* adds
history/favourites/following/message tools; those are account data, not public
content. Everything else — threads, article comments, related videos, uploader
pages, rankings, shows, live rooms, danmaku — was verified anonymous before its
tool was written and must not start requiring the login.

`BilibiliParsersTest`, `BilibiliMoreParsersTest`, `BilibiliToolsTest` and
`BilibiliMoreToolsTest` run offline against captured fixtures (the danmaku
fixtures are protobuf bytes). `BilibiliLiveTest` and `BilibiliLoginLiveTest` are
the only networked tests and are skipped unless `INHUMAN_BILI_LIVE` /
`INHUMAN_BILI_SESSION` are set; they are paced and open with a grace pause,
because anonymous request bursts are exactly what Bilibili's risk control blocks.
A `-509` on a deliberate run means the probe was throttled, not that the request
shape regressed.

### A23. A time hint is wire-only and decided from `createdAt`

A user message can carry a `[YYYY-MM-DD HH:mm]` line in the request
(`buildWireHistory` → `TimeHints.kt`). Two rules keep it from becoming a
transcript mutation or a cache-hostile one:

- **Never write the prefix into `messages.content`.** It would enter search,
  Markdown export, branches, Rewind, the auto-title and the next request's
  context, and the next turn would prefix the already-prefixed text a second
  time. The stored row is the user's words; the request is the only place the
  hint exists, which is also why the request viewer shows it.
- **Decide only from stored timestamps.** The gap is measured between
  consecutive user messages' `createdAt`, so a message that qualified once
  qualifies forever: the prefix stays byte-identical across turns and the
  provider's prefix cache still hits. Only the unstored draft uses the clock, so
  a preview and the following send agree. Recomputing stored rows against "now"
  would make every turn's prefix new — a silent cache loss on every message.

Changing the setting, or the device's time zone (the wall clock is rendered in
the current zone), rewrites the marked part of the prefix once; that is the only
intended cold request. `TimeHintsTest` pins the decision; `TimeHints.kt` carries
the reasoning.

### A24. Continuous-session invariants

Five rules keep the automatic projection from becoming a second transcript:

- **The record is authoritative and is never edited.** Maintenance writes only
  `context_summaries` (the brief) and `maintenance_runs` (the cost ledger).
  Earlier turns are omitted from the request, never deleted; the brief is
  derived and can be rebuilt from the record.
- **The boundary must be on the active branch and end a complete turn.**
  `planProjection` ignores a `throughMessageId` that is not in the transcript and
  puts the new boundary before the first kept user turn, so a tool call and its
  results cannot split. A branch switch or Rewind makes a stale brief inert
  (full history is sent) rather than applying it to the wrong prefix.
- **Maintenance spends only while the chat is visible, and never touches
  `conversations.updatedAt`.** `onChatHidden` cancels the job; a need found while
  hidden is deferred to the next visible moment. A background fold that bumped
  `updatedAt` would move the chat to the top of the list and look like the user
  spoke.
- **A read is not an event.** `recall` results go on the wire for that turn and
  are persisted as a stub (`ToolResult.persistedContent`); keeping the full text
  would make every lookup grow every later request and turn the record into its
  own echo. The tool is read-only, active-branch only, local, free, and absent
  from `ToolCatalog` and every picker.
- **`sessionMode` fails toward classic.** `SessionMode.parse` maps unknown or
  blank values to `classic`, and `ConversationRepository.create` defaults to
  classic, so a caller that forgets the user's choice cannot start automatic
  spending. The Create-sheet preference (`chat.new_conversation_continuous`) is
  read only when a conversation is created; the chat-menu switch writes the
  conversation column and never the preference.

### A25. Imported bundles and zips are untrusted

- **A conversation bundle imports the active branch only, under new ids.** The
  JSON has no message ids or branch pointers by design: it is a linear snapshot
  of what the user sees. `appendMessage` builds a fresh parent chain, so a
  bundle cannot splice into an existing conversation, and the target assistant
  is the local default because an assistant id from the source install would be
  meaningless here. Attachment files are copied into the new workspace with
  `WorkspaceTransfer`; only paths that the bundle's own `attachmentsJson`
  mentions are read, so an archive cannot smuggle unrelated files into the
  workspace.
- **The zip readers are fences, not convenience.** Both
  `ConversationBundleZip.read` and `WorkspaceZip.importInto` normalise entry
  names and reject absolute paths, `..`, drive prefixes, backslashes, control
  characters and unknown top-level entries; the workspace's `resolve()` is the
  last check. The conversation zip caps the JSON, each file and the total; the
  workspace zip caps the entry count and streams each entry through the ordinary
  quota/per-file path. A cap or unsafe entry must fail or be reported, never be
  silently truncated into a "complete" import.
- **Workspace zip import never merges.** It creates a permanent workspace named
  from the document display name. On a quota/IO/corrupt-archive failure the files
  that arrived are kept and the UI reports the failure; only a zero-file failure
  removes the empty workspace again. A merge would let one archive overwrite
  files in a workspace that may be shared with other conversations.
- **Deleting a key is not deleting its provider.** `clearProviderKey` removes
  the `SecureStore` slot only; the profile row, endpoint and model tags stay, and
  an OAuth session is a different credential ended by Sign out. The media dialog
  needs its own clear action because `saveMedia` treats a blank field as "keep
  the existing key"; without it, clearing the field and pressing Save silently
  keeps the credential. Web-tool keys are cleared together because one section
  with one Save would otherwise keep half of a deleted credential.

### A26. Role-play content is content, not capability

- **A card and a scene are prompt text and nothing else.** They live in
  `agents.rpJson` and `conversations.sceneJson`, are rendered by
  `RoleplayPrompt`, and no tool registry, approval rule, SSH check or workspace
  path reads them. Editing or importing one cannot add a tool or widen the
  fence; the rules block tells the model that, but the boundary is that the code
  never consults the text.
- **The greeting is a real assistant row; the start line is wire-only.** A scene
  opens with the character's greeting stored as the only root message, so the
  transcript says who spoke. When the request's first sent turn would be that
  assistant row, `buildWireHistory` prepends `RoleplayPrompt.KICKOFF` as a user
  message; it is never persisted. Faking a user row instead would put words in
  the user's mouth and show up in search, export and Rewind.
- **A scene is created with `ToolsColumn.NONE` (`"[]"`), not `""`.** `""` means
  every tool including future ones, which is the opposite of the scene default;
  `null` would copy the assistant's set. The conversation tool sheet remains the
  only post-creation writer.
- **Scenes get the story-state brief.** `summarizeBrief` chooses
  `RoleplayPrompt.STORY_BRIEF_PROMPT` when the assistant has a card, so a story
  is not summarized under "Decisions & constraints / Files & artifacts". The
  brief is still derived, capped and rebuildable; classic-mode scenes use the
  manual summary.
- **A scene keeps its own title.** `send` skips auto-titling when the
  conversation has a scene value; otherwise the first player line ("I walk into
  the tavern") would rename the scene.
- **No cross-scene memory.** Each scene has its own transcript, workspace and
  brief. A card is reusable, but nothing learned in one scene is injected into
  another; that would be the personal-assistant feature the project has not
  built.

### A27. URL downloads are one shared rule and one capability grant

- **One validator, two callers.** `DownloadUrl` (`core/ai/.../tools/`) owns the
  scheme, length, credential, private-address and filename rules for both the
  user-facing Files download and `download_url`. Do not add a second copy: a
  missing private-address check is invisible in a working download, and the tool
  and the Files path drifting apart is exactly how one of them loses a check.
  The tool passes `requireHttps = true`; the Files path keeps accepting `http`.
- **The DNS check must stay at connect time.** Rejecting a literal private host in
  the string is not enough: a public name can resolve to `127.0.0.1` (DNS
  rebinding) and a redirect can point anywhere. `UrlDownloader`'s custom `Dns`
  refuses private/loopback/link-local/multicast/CGNAT/reserved answers before the
  socket, and every redirect is re-validated by hand. `download_url` reuses that
  client; do not give it a plain `OkHttpClient`.
- **The sink is `WorkspaceTransfer.import`, not a direct write.** That is what
  applies the per-file ceiling, total quota, file count, free-space reserve and
  the " (2)" naming while streaming, with a staging file so a failed download
  never leaves a truncated file that looks complete. The tool's declared
  `Content-Length` check is only a fast refusal: a server can lie, so the sink
  counts as it goes.
- **Consent is a capability grant, not `needsApproval`.** Four separate facts:
  the Settings switch (off by default), the catalogue's `optIn`/`gatedByDownload`
  flags, the per-conversation `conversations.downloadAllowed` persisted by the
  first-call dialogue, and the ordinary `ToolPolicy` after that. Writing a URL
  download as `needsApproval = true` would make the default per-call prompt the
  policy, which is the one thing `docs/09-approvals.md` says approvals are not.
- **The URL is an outbound channel, so it is not logged.** Diagnostics record the
  host and byte count only; the consent dialogue shows the URL because the user
  is the one deciding, but the transcript and logs must not become a second copy
  of query strings that may contain data. The tool result names the saved path and
  size, never the URL's contents.
- **It is not `web_fetch`.** `web_fetch` returns page text through a paid backend
  and is capped in characters; `download_url` streams bytes through the app's own
  client into the workspace. Do not fold them together, and do not route the
  download through the paid search/fetch key: that would make a free operation
  depend on a configured bill.

### A28. The update URL's relative join is a string join, and mirror bases need it

`resolveUpdateUrl` joins a manifest's relative APK URL onto the configured base
with plain string concatenation. `URI.resolve` was the obvious implementation
and is wrong here: Java's resolver treats a repeated slash inside a path as
redundant, so the mirror base `https://gh-proxy.com/https://github.com/...`
turned its inner `https://` into `https:/github.com/...`. One mirror happened to
accept that collapsed form, which is exactly the kind of accidental success that
makes a later mirror look broken. The release script writes a bare file name;
a root-relative path is combined with the base's scheme and authority. Do not
"simplify" this back to `URI.resolve`, and keep the built-in preset test
(`built-in update presets are valid HTTPS endpoints on the latest release`)
asserting that a mirror preset resolves to its own release directory.

### A29. A message sent during a turn is a user row but not a turn root

`messages.injected` (schema v26) marks a user message the user wrote while a turn
was running and that the engine appended before the next round's request
(`EngineTurn.beforeRound`, `ChatEngine.run`). The row is a real user message: it
is what the model read, it is searched, exported and rendered as the user's
words. It is **not** a new turn.

One flag, five readers -- and each one moves a Rewind point or a turn count if it
disagrees:

- `turnSummariesOf` used to close a turn at every `fromUser` row, so an insertion
  would draw one turn as two and put a usage footer mid-turn.
- `userTurnsOf` (the turn jump list) would offer a turn number with no start.
- `ChatViewModel.reload` / `refreshTurnMeta` take the **last user row** as the
  turn root. The insertion has no start checkpoint (the checkpoint is keyed by
  the root's user id), so Rewind would be hidden or pointed at the wrong row.
  `MessageTurnRow.injected` exists so the structural read can exclude it without
  loading prose.
- the compaction and continuous-session boundary must fall between complete
  turns; `ProjectionMessage.injected` keeps a fold boundary off an insertion.
- `isInterruptedTurn` still reports a tail insertion as interrupted -- the turn
  really is unfinished -- but `continueInterruptedTurn` must anchor on the root,
  not on the insertion.

The wire needs no new adapter shape: a user message after the tool messages is
legal in all four formats, and Anthropic/Gemini merge it into the tool-result
user turn. The engine appends the text to its live history *after* the host has
persisted the row inside the hook, so the record and the request cannot
disagree; returning before the write would leave a row the model saw and the
transcript lost. `closeDanglingCalls` stays safe because the hook runs only
after a complete tool group.

Do not "simplify" the marker away by starting a new turn at the insertion: that
moves `lastUser`, orphans the start checkpoint, and turns Continue into a second
kind of retry. The queue itself (`ChatUiState.queued`) is memory-only, exactly
like the composer draft; the draft has never been persisted either.

### A30. A role-play scene freezes its persona, and its helpers live in the workshop

`conversations.rpSnapshotJson` (schema v27) holds the assistant prompt, display
name and card copied when a scene is created. The projection reads the snapshot
instead of the live assistant, so editing a card or an assistant prompt cannot
rewrite a story already underway. Three rules follow, and each is a bug if
undone:

- **Empty means "not pinned yet", not "no persona".** A scene created before the
  column existed pins on its first turn; a malformed value is treated the same
  way and is repaired by that pin. Do not backfill the column in the migration:
  the values at upgrade time are the ones being used, and writing them from
  somewhere else would freeze a persona the conversation never sent.
- **A blank scene is `{}`, not `""`.** `""` is "ordinary conversation"; the
  scene-creation path and `setScene` normalise a blank scene to `{}`. The first
  version stored `""`, so a scene created with all fields empty was treated as a
  non-scene: the auto-title rule renamed it after the first message and the
  persona reload refused it. `{}` decodes as an empty `RoleplayScene` and keeps
  the conversation a scene.
- **The folder source and the tool share one validator.** `RoleplayStoryLoader`
  (`core/engine/.../roleplay/RoleplayStory.kt`) is what `NewSceneDialog` uses
  when it starts from `rp/<story>/card.json`; it calls the same
  `RoleplayValidator` and `RoleplayCodec` as `rp_validate`, so a folder the
  assistant checked is accepted by the dialog and both print the same
  field-level reason. `card.json` is required and `scene.json` is optional; a
  malformed/over-limit file is an invalid story, never silently "no card", and
  the card JSON is copied into the snapshot **verbatim**. Only permanent
  workspaces are offered: a temporary workspace dies with its owner and would
  take the story files with it. A scene created this way keeps the workspace,
  while its tools are still the explicit empty list.
- **A card file may carry its own `name`; a folder scene uses it.** The
  assistant-sourced path keeps the assistant name, but a card that is not owned
  by an assistant needs to name its character: display name is card `name`,
  then the chosen assistant's name, then the folder name. `RoleplaySpec`
  publishes the optional field and its limit like every other card field.
- **A scene file is a creation template, not a live document.** `scene.json` is
  read once when the conversation is created; **Scene settings** edits the
  conversation's copy and never writes back, because two writable sources for
  one story would immediately disagree. The card export is the migration path
  for legacy assistant cards: it writes `rp/<folder>/card.json` through
  `WorkspaceTransfer`, refuses to overwrite an existing file, and is a copy —
  later edits to either side do not propagate. An assistant card has no `name`
  field, so the export writes the assistant's name into the file; otherwise a
  scene started from that file would fall back to whichever assistant happened
  to be chosen and its title could change for an unrelated reason.
- **Once a snapshot exists, never fall back to the live row.** A cleared
  assistant card must not silently resurrect through the fallback; the scene's
  persona is the snapshot until the user hits **Reload card and assistant
  prompt**. The reload is the `AGENTS.md` pattern: one deliberate cold request,
  because it rebuilds the system prefix.
- **The copy keeps the card as JSON text**, not decoded fields, so an unknown
  field written by a newer release survives and the normal codec decides what it
  means.

`rp_format` and `rp_validate` (`core/engine/.../roleplay/RoleplayTools.kt`) are
authoring helpers, not selectable capabilities:

- They are appended **after** the allow-list, like `recall`, but only in a
  conversation whose workspace is the built-in RP workshop
  (`WorkspaceKinds.ROLEPLAY`, A31) and which is **not** itself a role-play
  scene. An ordinary workspace gets no role-play tooling, even with file tools;
  a scene keeps its explicit empty tool set. `RoleplayToolsPlanTest` pins the
  truth table.
- `rp_validate` additionally requires `read_file` or `list_files`: it must not
  become a read path the user did not grant. `rp_format` is constant text and
  `rp_create_scene` only previews data, so neither needs a capability.
- The validator resolves through `SessionWorkspace` and reports field names,
  lengths, JSON types and unknown-field warnings. It must not quote the file's
  prose; that would be a second read tool with different permission rules. Its
  persisted transcript line is a count, not the findings.
- The format text is generated from the serializers and `RoleplayLimits`
  (`RoleplaySpec.kt`), and `RoleplaySpecTest` fails if a field or a limit drifts.
  Do not hand-write a second description: the model writes files against this
  text and the same codec validates them.
- They are absent from the tool catalogue and from every picker. Listing them
  would turn an infrastructural helper into a capability row the user is asked
  to grant, which is the "greyed row" `docs/08-port-philosophy.md` §1.2.1
  refuses in spirit: there is nothing there to grant or refuse.
- **The reload button has a condition.** `ChatViewModel.reloadRoleplayPersona`
  copies the live assistant's card into the snapshot. A scene created from a
  workspace folder or from an inline card has no live card; the dialog hides the
  button (`roleplayReloadable`) instead of letting it empty the frozen persona,
  and the method refuses as well. This also covers folder scenes that existed
  before the guard.

### A31. The RP workshop is a workspace kind, not a permission level

`workspaces.kind` (schema v28) is a **data attribute**, like `isPermanent`. It
exists so the role-play authoring surface has one home instead of a per-assistant
switch on every conversation. Four rules make that safe, and each is a bug if
undone:

- **The kind gates only capability-free helpers.** In a non-scene conversation
  whose workspace is the workshop, the host appends `rp_format`, `rp_validate`
  (only where a read tool already exists) and `rp_create_scene` (A30). None of
  them reads outside the workspace, reaches the network, executes anything, or
  writes app state. Capability-bearing tools still come from the assistant and
  the conversation's own `toolsJson`; the kind adds none.
- **The model cannot enter, create or mark a workshop.** There is no tool that
  creates a workspace or changes its kind. Entering the workshop is a user
  gesture (the home entry or a Create-sheet choice), so there is no
  "convince the model to give you more" path — the thing
  `docs/08-port-philosophy.md` §1.1 warns about. The same entry is the only
  writer of the roleplay row (`ConversationRepository.ensureWorkshop`).
- **A scene says so in its tool list, and the proposal creates nothing.** The
  scene-proposal tool returns data only; the conversation is created by the
  user's tap through `RoleplaySceneCreator` (the same writer the New-scene
  dialog uses), with `tools = emptyList()` (stored `"[]"`), the persona frozen
  and the greeting in the same transaction. A proposed-but-untapped card leaves
  no database row and no workspace behind. Never let the tool call
  `repository.create` itself: that would be the first tool writing app state,
  and it would need a new invariant decision.
- **A scene created from the workshop binds the workshop workspace.** The
  permanent workspace is where the materials and the transcripts live, so
  deleting the scene does not delete the cards; deleting the workspace itself is
  still refused while any conversation uses it.
- **The helper set is not a mode.** There is no global switch, no approval row
  and no picker entry; a workspace either has the kind or it does not. Ordinary
  workspaces lose `rp_format`/`rp_validate` by design — that is the management
  shape, not a removal of a capability (the helpers never were one).

---

## B. Defects that were found here — and where each one now stands

Every entry below was a real defect when it was written. Most are fixed; each one
says what the fix was and how far it was verified, which is **not** the same for
all of them. A "FIXED" with no test named means build and the full suite only;
where a defect sits inside a stream, or needs a fake that does not exist, saying
so is better than a test that asserts nothing.

**Status: B1-B10 fixed, B11 mostly fixed, B12 fixed.** The dates matter less than
the method, so each fixed entry names what convinced it — a test, a device
observation, or neither.

### B1. The regenerate control was disabled for the rest of the session after any mutating turn — **FIXED**

Three expressions of one fact: a stored field on `ChatUiState`, a private method
on `ChatViewModel` with the same arithmetic, and the assignment in `reload()`.
The field was raised in the old tool loop and never cleared, so after one mutating
turn every later reply claimed it could not be regenerated -- and reopening the
conversation did not help, because the chat view model is keyed and
activity-scoped, so the same instance came back without `reload()` running.

The fix is not to clear the flag in `send()`, which would break again the next
time a path starts a turn. It is to delete the field: `currentTurnMutated` is now
a **derived property** on `ChatUiState` (`ChatUiState.kt:117-122`) computed from
the transcript after the last user message, so there is no second copy to go
stale. `reload()` deliberately does not assign it, and the comment there says
why: rebuilding the transcript *is* recomputing it. `ChatUiStateTest` carries
five tests, including "the turn after a mutating turn can be regenerated" -- the
exact sequence that was broken.

**Verified on the emulator, because this one had a trap in the verifying.**
The demo script writes a file every turn, so *every* demo turn is legitimately
mutation-gated and shows "This reply changed files, so it cannot be regenerated."
That sentence appearing is therefore **not** evidence of the bug. The decisive
case is a turn that changes nothing, which the demo's `plain` path provides:

```
after a mutating turn   'cannot be regenerated' present
send "plain"            a turn with no tool calls
after it                'cannot be regenerated' absent, "Answer again" offered
```

A version of this entry previously described the two-named-flags bug as still
current, directly beneath the paragraph announcing the fix. Both halves were in
the same file for a week; the lesson is that fixing an entry means replacing it,
not appending to it.

**Superseded on 2026-10-18.** The derived `currentTurnMutated` property and its
UI sentence were removed entirely, together with the five `ChatUiStateTest`
cases that existed only for it. Regeneration now sends the request with
`includeTools = false`, so a pure-text reply can be retried after SSH or a file
write without any chance of replaying the tool. The old property was protecting
against a replay that is now structurally impossible.

### B2. The last ~40 ms of every reply was missing from the live transcript — **FIXED**

`publishIfDue` throttles to one update per 40 ms and was a **local function
declared inside the `try`**. The comment at the throttle claimed "the `finally`
block publishes regardless, so the last partial window is never lost", but a
local function is not in scope in the `finally`, so the finally only called
`updateMessage(… isStreaming, finishReason, usage)`. Up to 40 ms of tokens were
therefore absent from `plainText`, from `parts` and from the delta channel after
the turn ended, while the database row (written from the same `StringBuilder`s)
was complete. The bubble stayed a few words short until something reloaded the
transcript, which reads as a rendering delay rather than as lost state.

The fix hoists the publish into `publishParts(...)` and calls it from the
`finally` before the channel closes (`ChatViewModel.kt:1690-1707`), so the
throttle is no longer lossy. No test isolates the last 40 ms: the loss lives
inside a stream, which adb cannot sample at any rate, so this rests on the code
path rather than on a test that asserts nothing.

### B3. `MarkdownText` never switched to the plain renderer when the stream ended — **FIXED**

`MarkdownText.kt:48-52` documents a switch. Nothing performed it: `deltas` was
captured into the message when it was built, so removing the map entry at the end
of the turn left the field holding a closed channel — the reply kept rendering
through the incremental parser until something reloaded the transcript, and the
anti-flicker `retainState` path applied only to transcripts loaded from the
database.

Two fixes, because there were two causes. The closing update now sets
`deltas = null` with `isStreaming = false` (`ChatViewModel.kt:1009-1021`), which
is the switch the renderer's documentation describes. Separately, **a rotation
mid-stream lost the text received before it**: the delta channel is a queue with
no replay, and a rebuilt composition started empty while `text` was ignored. So
`MarkdownText` now appends `text` through a `DeltaCursor` before following the
channel (`:113-132`); the cursor only emits what the renderer does not already
have.

Adding the rewind case to `DeltaCursorTest` immediately caught a regression in
that fix — on a shrinking buffer it reset `sent` to zero and re-emitted the
prefix, which is one of the two failure modes that file's own documentation
names. Verified by build and the full suite. **Not verified on a device:** this
is inside a stream, which `AGENTS.md` records as unobservable through adb at any
sampling rate.

### B4. Declining the first of several tool calls persisted an unanswered call — **FIXED**

The engine now answers every unrun call itself: `EngineEvent.ToolCallSkipped` is
emitted for the calls after a decline, and for the calls a budget will not
allow, so the host appends a `[skipped]` result for each. The chip goes to
`Failed` rather than `Declined` -- the user refused one call, and reporting the
others as refused too would be untrue.

The older loop did the same from a `finally` under `NonCancellable`, because by
then the coroutine was already cancelled and each answer was an insert; the
engine emits the results as ordinary events before it ends the turn, so that
workaround is gone with it.

The old shape was this: `executeCalls` returned immediately on the first decline
while all calls of the round were already in `toolCallsJson`, so calls after the
declined one got no result; `Stop` during `tool.execute` left the same gap. That
transcript is a request the API rejects, so the next user message after either
was likely a 400.

### B5. The 640dp measure cap was inert at seven call sites — **FIXED**

`fillMaxWidth()`/`fillMaxSize()` before `widthIn(max = 640.dp)` sets
`minWidth = maxWidth = incoming max`, so the following `widthIn` clamps back and
the cap does nothing. The sites were: `WideDialog.kt:86-87`,
`AgentsScreen.kt:123-124`, `FilesScreen.kt:206-207`,
`ConversationsScreen.kt:315-316` and `:450-454`, `SettingsScreen.kt:95-98` and
`:189-190`; two of them (`WideDialog` and `ConversationsScreen`) carried comments
claiming the cap is what makes the tablet layout readable, which is section C's
problem in its purest form — the comment described behaviour the code did not
have.

All seven now constrain first and fill second, plus a check that no new site
reintroduces the order. The chat screen's two sites (`ChatScreen.kt:349-351` and
`:387-389`) were already correct and carry the original measurement in their
comment (2208px vs 1232px).

**Verified on the tablet AVD** (`emulator-5554`, 2560x1600 @ 320dpi = 1280dp),
measuring the conversations empty state through `uiautomator`:

```
text column    1152px = 576dp, centred at 1280px (the screen centre)
```

576dp is `ReadableMeasure` (640dp) less the 32dp gutter on each side, which is
the expected result and exactly what the cap was supposed to produce. Before the
fix this text ran the full 2560px.

### B6. The provider could rename or delete a workspace folder — **FIXED**

Folders carried `directoryFlags()`, so the Files app offered both. A workspace
directory's name *is* its `workspaceId`, so renaming it orphans the row -- the
next tool call recreates an empty workspace under the old id, and the renamed
directory matches no row, so the sweep eventually deletes the files -- and
deleting it leaves the inverse orphan the sweep cannot see.

Workspace roots now carry `workspaceFlags()`: create only, since creating files
inside the directory is the case the provider exists for. `deleteDocument` and
`renameDocument` also refuse a workspace root outright, because a flag is a
request and the provider should not rely on a caller honouring it. Deleting a
workspace is something the app does, through the repository, which removes
files and row together and refuses while conversations remain.

**The first version of that refusal checked the wrong id, so it never fired.**
A conversation row carries two UUIDs: `id`, the transcript's key, and
`workspaceId`, the workspace's directory name and the document id this provider
publishes. The refusal called `conversations().byId(id)`, which compares against
the first; a folder URI carries the second, so every check returned null and
execution fell through to `resolveFile(...)`. `includeDocument`'s "a bare id is a
conversation" branch had the same mismatch, so a re-queried folder fell through
to `includeFile` and reported its display name as a raw UUID rather than the
title.

The provider now resolves a bare document id through `WorkspaceDao.byId`, and
the root lists workspaces rather than conversations. That is the only correct
lookup once a permanent workspace can be named by several conversations; the old
unique `workspaceId` index is gone in schema v11. `ConversationLookupTest` still
pins the id distinction; `WorkspaceDocumentsProviderTest` covers a re-queried
folder keeping its name and both `deleteDocument` and `renameDocument` throwing
`FileNotFoundException` for the id the provider publishes.

The flags are what stop the system Files app offering rename or delete at all;
the guard is the second line for a caller that does not honour them. The
provider is additionally protected by `android.permission.MANAGE_DOCUMENTS`,
which limits callers to the system, so this was defence-in-depth rather than an
open door.

### B7. `import` was a quota side door — **FIXED**

It enforced only `maxWriteBytes` -- no `maxTotalBytes`, no `maxFileCount`, no
free-space check -- and its failure path deleted the target, which for a
destination resolving to an existing empty directory deleted that directory.

Import now streams into a staging file in the same directory and renames it into
place, so the destination is only replaced by a complete import and the cleanup
cannot reach anything the user already had. The quota arithmetic moved to
`SessionWorkspace.quotaRefusal`, shared with `WorkspaceTool`, because the rule
existed in one place and not the other.

`WorkspaceTransferTest` carries seven tests, two of them stated as behaviour
rather than as implementation ("the original file still exists and still has its
contents", "the directory is still there"). It also caught two bugs in the fix
itself: `usage()` counts the staging file, so a 2 KB import into a 3 KB
workspace was told 4 KB was over the limit, and the file count was wrong the
same way. The caller now declares what it staged and what it replaces.

### B8. `1.json` was not a v1, and the fix was smaller than I claimed — **FIXED**

`core/db/schemas/…/1.json` and `2.json` differed only in the `version` field,
and `1.json` already declared `toolCallsJson`, `toolCallId` and `toolName` — the
three columns `MIGRATION_1_2` adds. The exported v1 was v1's schema re-taken after
the entities gained those fields.

**It was functional, not cosmetic.** `MigrationTestHelper.createDatabase` builds
the starting database *from that file*, so a test beginning at v1 failed with
`duplicate column name: toolCallsJson` — the migration could not run against the
schema the export claimed. A test that cannot start is worse than no test,
because it looks like coverage.

**Two things I got wrong along the way, both worth keeping.**

First, I claimed `5.json` also drifted because it "already contained
`thinking`/`agentId`". It should: `MIGRATION_3_4` adds both, so v5 has them. That
was a correct file read as a defect, and only an audit against what each migration
adds — run for all eight versions at once — showed all of them clean on that
axis.

Second, I said fixing this needed "a derived entity class per historical version,
because the `identityHash` cannot be hand-written". The experiment says otherwise:
`MigrationTestHelper` does **not** verify the stored hash when building a starting
database. It reads the file's `version` and its DDL. Removing the three columns
from `1.json`'s `createSql` and `fields` was enough — the v1 path now builds, and
`the exported v1 schema can build a database` is the test that keeps it that way.

So the entry cost two lines of JSON and a wrong estimate of a day's work. The
`identityHash` is genuinely stale in that file (`26d8a4e9…`, identical to
`2.json`'s, which cannot be right for two different schemas); it simply is not
consulted on this path, and recomputing it would need Room to export the file
itself.

**Verified:** eleven device tests in `:core:data/src/androidTest/…/MigrationTest.kt`,
including the new schema check, plus an audit of all eight exports against the
columns each migration adds. The other eight still build their own DDL rather than
trusting the exports, which is the safer default even now that `1.json` is
correct.

**The one remaining caveat:** a schema file that is right about its columns and
wrong about its hash is right by accident. Anything that later starts verifying
hashes will reject `1.json` and `2.json`, and the fix then is to let Room re-export
them from genuine per-version entities. Recorded rather than pre-empted.

### B9. Two sources of truth for "can this model see" — **FIXED**

Two defects under one heading, both the same shape.

**`SecureStore.hasUsable` was not name-accurate.** It was `get(key) != null`, so
a stored-but-empty value read as configured while `send()` rejected it -- the
composer enabled, the turn refused, nothing on screen explaining the
disagreement. It is now `!get(key).isNullOrBlank()`. The settings screen cannot
produce that state (`SettingsViewModel.save()` removes the key when the field is
blank), so nothing in the app reached it; it is fixed because `get` is public and
the invariant is the one the callers already assume.

**The vision check consulted a different source than the button did.** In demo
mode the composer's attach button was **enabled** -- the demo model advertises
image input and `publishModelState` falls back to the demo model list -- while
`attachImage` called `agents.models()` itself and could not find that model, so
every attempt was refused with "The model in use cannot read images."

`publishModelState` already computed the answer and put it in state as
`modelSupportsVision`; `attachImage` simply did not read it. It does now, so the
button's enablement and the refusal come from one expression. Note which way the
fix went: the demo model genuinely accepts images
(`DemoProvider.listModels` declares `inputModalities = listOf("text", "image")`),
so the button was right and the refusal was wrong -- the published answer was the
authoritative one, which is why the fix is to read it rather than to make the
button lie.

### B10. `FilesViewModel` blocked the main thread and walked twice — **FIXED**

`workspace()` wrapped a database read in `runBlocking`, and every caller --
refresh, open, delete, import -- reached it from `viewModelScope`, whose
dispatcher is `Dispatchers.Main.immediate`, so each parked the thread that draws
the UI. It is now `suspend` and reads on `Dispatchers.IO`.

`refresh()` also called `usedBytes()` and `fileCount()` separately, and both call
`usage()`, so every refresh traversed the whole workspace twice -- the cost A6
exists to remove, reintroduced here. It now asks for `usage()` once and takes
both numbers off the result.

### B11. Smaller ones — **MOSTLY FIXED** (this entry corrected twice)

Fixed in this pass, each verified by reading the code rather than by the commit
message:

- `ConversationDao.observeAll` and both `AgentDao` orderings gained `id ASC` as a
  tie-break (`InhumanDatabase.kt:25`, `:167`, `:170`). Two rows saved in the same
  millisecond compare equal on `updatedAt`, and SQLite may return them in either
  order, so an emission could reorder equal keys and make the list appear to
  shuffle with nothing having changed.
- The model picker's error path is reachable: `reloadModels()` now returns a
  `Result` (`ChatViewModel.kt:326`) and the dialog reads
  `result.exceptionOrNull()?.message` into a visible error line
  (`ModelPickerDialog.kt:82`, `:104-110`). Selecting a model also dismisses, in
  both the default option and a listed one (`:118-121`, `:150-153`).
- The two-sources-of-truth bug behind "cannot read images" in demo mode is gone:
  `attachImage` reads `_state.value.modelSupportsVision`, the same published
  answer the attach button was enabled from (`ChatViewModel.kt:376`, `:388`).
- The failed turn that left no trace is fixed: a turn that fails before
  producing text, reasoning or tool calls now gets a row with the reason and
  empty content (`ChatViewModel.kt:1067-1078`). **No test covers it** — nothing in
  any test source mentions the case — so this one rests on the code comment and
  the rendered path, not on a green suite. Recorded that way on purpose.

Still open, each re-verified in the code as of this pass:

- **`pendingApproval` is a single field** (`ChatUiState.kt`), written at one
  place (`ChatViewModel.gateEngineCall`) against a single `approvalContinuation`,
  so several approval-needing calls produce **sequential** dialogs rather than a
  collected batch. The old note that `continueAfterApproval` accepted a `pending`
  parameter it never read no longer applies: it now takes only the answer, and
  the audit row is the record of which call was asked about.
- **Dead code, verified repo-wide** (`grep` over every `.kt`, excluding `build/`
  and `_research/`): five fields of `TurnOutcome` are constructed and never read
  — `text`, `reasoning`, `usage`, `finish`, `failure` (`ChatViewModel.kt:764-772`;
  the only construction site passes `usage = null`). Also unreferenced:
  `DeepSeekProvider.json`, `DeepSeekRequest.EmptyArray` (a declaration and nothing
  else, `:167`), `ReasoningType.Raw`, `ConversationRepository.observeConversation`
  (definition only — `:82`), `MessageDao.update`, `MessageDao.delete`,
  `ConversationDao.observe`, `SecureStore.has`, `Database.DEFAULT_SYSTEM_PROMPT`
  (a duplicate of the companion's, which is the one bound at
  `InhumanDatabase.kt:468`; the copy in `Database.kt` is unreferenced),
  `FilePreview.truncated`. Two are **test-only reads** rather than dead:
  `RemoteModel.maxOutputTokens` (read by `DeepSeekModelsTest`) and
  `SseFrame.id`/`retryMillis` (read by `SseParserTest`). `StreamChunk.Raw` is
  never emitted — its only reference is the `when` branch that ignores it.

Five things that *were* on this list and are now clean, recorded because a
stale entry is how this list loses its authority (each was re-read, not assumed):

- the `UsageLine` locale stragglers are gone: its `"%.1f tok/s"` and `"%.1fs"`
  figures are built by a `tenths` helper with the same integer arithmetic as
  `TokenFormat.compact`, so a comma-decimal locale can no longer bend a
  technical readout. The dead `ChatScreen.kt:1039` reference went with them;
- the duplicated doc comments on `replyDeltas`, `regenerate` and `selectBranch`
  are gone — each KDoc is now single and describes its own function;
- the `attachImage` / composer disagreement about vision is gone with the
  single-source fix;
- `DeltaCursor`'s `seen`/`sent` pair: `sent` is the only state the slice needs
  (the shrink case re-sends the new buffer whole), so `seen` was a write-only
  copy of the reply, and every publish paid an O(n) copy for it. **Removed.** An
  earlier entry here claimed the field was load-bearing; re-reading the code
  settled it the other way.- the transcript's per-publish scan is **incremental** now. The old note was
  right that a cheap key (size, last id, a flag count) cannot see an older row
  rewritten in place; the fix avoids keys altogether — `TranscriptIndexer`
  compares rows by reference and only recognises the three shapes a publish can
  take (same list, one appended row, the open turn's tail), falling back to a
  full pass for everything else, so a future mutation path can be slower but
  cannot be mis-derived. See B50.

### B12. Files showed a stale list after the assistant wrote a file — **FIXED**

The report: the transcript said a file was saved, and Files was empty. Both were
true at their moment. `FilesViewModel` refreshed only in `init`, and the view
model is activity-scoped and keyed by conversation (`files-$id`), so leaving the
screen and returning reused the same instance with the list from before the
write. The workspace on disk held `notes/g-dropping-用法笔记.md`; the screen was
still showing the state from its first composition.

The fix has two halves. `FilesScreen` refreshes on every resume
(`LifecycleResumeEffect`), and `FilesViewModel` no longer refreshes in `init`;
otherwise the first open would walk the workspace twice. `refresh()` also logs
the path, entry count, file count and bytes, so "the screen is stale" can be
told from "the file is not there" in the log.

**Verified on the pad**: reopening Files on that conversation shows `notes` and
`1/5000 files · 5 KB`; inside, `g-dropping-用法笔记.md`, 5 KB. The log line is
`D/files refresh path=/ entries=1 files=1 bytes=5440`. No unit test covers this;
the invariant is a screen-resume lifecycle, which a JVM test cannot drive.

### B13. SSH was green on the JVM and failed on Android at key exchange — **FIXED**

The 8-test `SshClientTest` passed against embedded MINA SSHD, including Ed25519
and TOFU, and the APK built. The first real device connection — an otherwise
valid password-auth server on the other side of `adb reverse` — failed with:

```
no such algorithm: X25519 for provider BC
```

Android ships a **stripped** BouncyCastle under the provider name `BC`; sshj
checks `SecurityUtils.isBouncyCastleRegistered()`, sees that name and assumes the
full provider. On the JVM the full provider is on the test classpath, so no test
could see the difference. The fix is in `SshClient.ensureBouncyCastle()`: replace
the stripped `BC` with the full `org.bouncycastle.jce.provider.BouncyCastleProvider`
before the first connection. Verified on the pad: `installed full BouncyCastle
provider`, handshake, TOFU fingerprint written, command returned exit 0.

The lesson is not "test on a device" in general; it is that a JVM SSH test
answers questions about sshj and the server, not about Android's security
providers.

### B14. A model wrapped the whole SSH argument object, and consent showed an empty call — **FIXED**

After a failed command, the model began emitting:

```json
{"arguments":"{\"hostname\":\"127.0.0.1\", ...}"}
```

— the function-call envelope as an argument, plus `hostname` instead of `host`.
`SshExecTool` was made tolerant (unwrap one string level, accept `hostname`), but
the consent dialogue read the raw parsed object and rendered "connect to @",
with a blank first command. It could therefore ask the user to authorize a call
that the tool would then reject.

`SshArguments.normalize` is now the single normalisation. It is exposed through
`Tool.normalize`, so the engine, the consent gate, the policy check,
`describeCall` and the tool itself all read the same object. `SshExecTool`'s
duplicate `normalizeArguments` was removed when SSH turns moved onto the engine.
A call still missing host/user/command gets an error result and **no** consent
dialog. Verified on the pad by re-sending after the fix: the dialog showed
`tester@127.0.0.1` and `uname -a`, then the command ran.

### B15. Global SSH off→on inherited a revoked consent — **FIXED**
`sshAllowed` was mirrored in `ChatViewModel` and only reloaded with the
transcript. Turning the global switch off cleared the database column but not
that field; turning it back on made the next call trust the stale `true` and skip
the consent dialog the user had just revoked.

The call path now re-reads `conversations.sshAllowed` before the consent gate
when the in-memory flag is true. A revoked row clears the flag and asks again.
`SettingsViewModel.setSshToolEnabled(false)` clears every row. Verified on the
pad: off→on, next SSH call showed the consent dialog again.

### B16. Killing the app during consent made every later turn HTTP 400 — **FIXED**

The assistant row with `tool_calls` is persisted as it streams; the tool rows are
not. Force-stopping the app while the SSH consent dialog was open left the
conversation with two calls and no results. The next request was rejected:

```
An assistant message with 'tool_calls' must be followed by tool messages
responding to each 'tool_call_id'. (insufficient tool messages ...)
```

`ChatViewModel.closeDanglingCalls` now validates the **wire history**: after each
assistant `tool_calls` block it keeps the real tool results and inserts an
`[interrupted]` result for any call id still missing. This works even when a
later user message exists, where appending to the database tail would put the
result in the wrong place. `repairDanglingToolCalls` separately closes a dangling
tail in storage so the transcript can be reopened cleanly.

The residual is display-only: a mid-history dangling call can still render as an
unanswered call in the transcript; the request is valid, but the UI does not
rewrite history. That is preferable to branching the conversation by inserting a
synthetic child and changing which message is selected.

### B17. A live Thinking/tool accordion expanded below the fold without following — **FIXED**

Reported as: "拖到底部时默认跟随最新，手动移动后停住，点回到底部也跟随；但 thinking
之类的临时展开部分似乎不会跟随最新." The follow effect was keyed only on
`messageCount` and the last answer's text length. A `Thinking` or tool accordion
expanding or collapsing changes the row's measured height without changing
either key, so the transcript stayed put while the newest content grew below the
fold.

Two changes in `ChatScreen`:

1. `followLatest` is now an explicit intent. `NestedScrollConnection` turns it
   off on `NestedScrollSource.UserInput`, and a scroll-end observer turns it
   back on if the gesture landed at the bottom. Content growth never changes it;
   deriving follow from `atBottom` would stop following when a growing row
   pushed its own bottom below the viewport. A `programmaticScroll` flag keeps
   the jump/follow callbacks from counting as user input; without it the jump
   requested follow and its own scroll immediately cancelled it.
2. The follow effect also keys on the last visible row's measured height and
   offset, and `atBottom` now requires the actual last item's bottom at the
   viewport bottom, not "within two items".

Verified on the pad during a live thinking answer: no jump button appeared while
the accordion expanded, a manual swipe up held its position for five seconds of
continued streaming, and the jump button restored follow.

### B18. Rollback deleted the turn but left tool effects behind — **FIXED**

The first rollback implementation was transcript-only: Edit deleted the user
message and descendants and restored the composer, but files written by the turn
stayed written and SSH commands stayed run. That is not a rollback; it is
hiding the evidence. The correction is structural:

- before the first **reversible mutating** tool of a turn, capture a
  content-addressed `start` checkpoint under
  `filesDir/rollback/<conversation>/<user-id>/`; if it fails, refuse the
  mutation; after each completed tool group capture another checkpoint;
- rollback restores `start` **before** deleting any rows; a failed restore
  leaves the conversation intact;
- `ssh_exec`, `ssh_upload` and `ssh_write_file` are tagged `reversible = false`;
  a turn containing any of them gets no Edit/rollback point, only the reason.
  `ssh_download` is remote but writes a workspace copy, so it stays reversible.
  **Answer again** is still available.

Snapshots are per turn and per tool group. The chat menu lists each stored turn
with its checkpoint count, bytes and timestamp, totals them, and sets retention
(1/3/5/10); pruning keeps the newest turns and compacts unreferenced blobs. Rewinding a turn deletes only that turn's
directory, leaving older snapshots intact. A checkpoint's existence is also
treated as evidence of a mutation, because a process can die after the file
changed and before `markMutated` was written; relying on the row alone would
recreate the same half-rollback.

Verified on the pad with a throwaway conversation: a real `write_file` created
`notes.txt`; Edit restored the empty `start` checkpoint, removed `notes.txt`,
deleted the turn, and restored the prompt.

### B19. Rewind was described only in prose, and the changed files were invisible

Three related UI failures, reported together:

- Whether a turn could be rewound lived in the tool description, which is
  collapsed by default. The name of an SSH tool says what it runs, not that its
  effects are permanent. Every mutating tool now carries **REWIND** or **NO
  REWIND** in the same badge row as PAID and REMOTE, in the assistant editor,
  the approval-rule row, and the approval dialog. `ssh_exec`, `ssh_upload` and
  `ssh_write_file` show both REMOTE and NO REWIND; `ssh_download`, `ssh_probe`,
  `ssh_list` and `ssh_read_file` show REMOTE (the reads are reversible, and the
  badge row does not add a REWIND badge to a non-mutating tool).
- Edit restored a snapshot without naming what it would revert. The turn's
  changed paths are now compared against the snapshot by content hash, stored
  on the final assistant row (`messages.changedFilesJson`, schema v10), shown
  under the answer as `+`/`~`/`- path`, and listed in the confirmation dialog.
  The comparison is content-based because checkpoints store hashes, not
  modification times.
- Agent answers were still 640dp chat bubbles. User messages remain bubbles
  because they are short; an assistant answer is the document, so it has no
  background or width cap and fills the transcript. Tool-result rows and the
  composer remain capped.

Verified on the pad: the file turn showed `Changed files / + notes.txt`, the
dialog repeated the same path under "Files that will be restored", and the
assistant text ran to the transcript edge. On the phone AVD the assistant
answer did the same while the user bubble stayed capped; the tool editor showed
`REWIND` on file tools, `REMOTE` + `NO REWIND` on `ssh_exec`/`ssh_upload`/
`ssh_write_file`, and `REMOTE` + `REWIND` on `ssh_download`. The persisted list
survived a force-stop and relaunch.

### B20. Regeneration could only retry the last text, and one snapshot overwrote all history

The next report was about control and history:

- The refresh button could only produce another **final text** answer. It could
  not resume from after the first tool call, or before the second, even when
  everything discarded was reversible. The answer was a checkpoint picker, now
  surfaced inside Rewind: it lists `From your message` and each completed tool
  group. Selecting one restores its checkpoint, deletes the suffix, and reruns
  **with tools**. A suffix containing SSH disables the point.
- One snapshot per conversation meant the previous turn disappeared on the next
  send, and there was no way to see or choose how much history was kept.
  Checkpoints are now content-addressed per turn and per tool group; the chat
  menu lists each turn's checkpoints/bytes and offers 1/3/5/10.
- Rewinding called a full-conversation discard, deleting every snapshot. It now
  deletes only the rewound turn's directory. A later regeneration of that turn
  trims manifests the rewritten transcript no longer references, so the turn's
  checkpoints are replaced rather than accumulating or truncating older turns.

Verified on the pad with two tool rounds: the picker showed `After tool group 1:
write_file` and `…2…`; selecting group 1 restored `a.txt` without `b.txt`,
deleted the old suffix, and reran with tools. The second turn was then rewound:
snapshot count went `2 turns` → `1 turn`, proving only the rewound turn was
discarded. The old second-group manifest was replaced, leaving `start` plus the
two current group manifests.

### B21. The transcript flashed when the reply completed

Two independent causes, found in sequence.

The completion path called `reload()` after the last token. `reload` re-reads
every row and replaces the whole `messages` list, so the streamed row was
swapped for an equal reconstructed row and the LazyColumn re-laid out -- a
visible flash at the exact moment the user starts reading.

The transcript is already current and persisted when the stream ends. The send
and mid-turn-regeneration paths now call `refreshTurnMeta()` instead: it
recomputes only checkpoints, rollback availability, the changed-file section's
source state and the context gauge, and does not touch `messages`. Final-answer
regeneration also avoids `reload`: it fetches the new reply's branch metadata
with `refreshReplyBranches()` and patches that one row. A full `reload` remains
for paths that genuinely replace the tree (initial load, branch switch,
rewind).

After that fix the regenerate path still flashed. The remaining switch was
`deltas = null`: it handed the finished message to `rememberMarkdownState`,
which parses asynchronously. For one composition the new state was empty and
the reply disappeared. The completed message now keeps its closed streaming
channel and its `StreamingMarkdownState`; the plain parser is used only when a
row is rebuilt from the database, before it is drawn. `MarkdownText`'s claim
that the swap is invisible was the comment that was the bug.

Keeping the streaming state exposed a latent duplicate in the delta protocol.
When the first composition landed after the first token, the catch-up appended
the accumulated `text` and the still-queued first delta was appended again:
the reply read `HiHi there!`. The channel now carries accumulated snapshots
(conflated) rather than slices; `DeltaCursor` decides the missing suffix, so a
late catch-up and a queued snapshot reconcile instead of overlapping. Verified
after a regenerate: the reply read `Hi there!`, not `HiHi`. The branch switcher
still showed `3 of 3` without a reload.

### B22. Rewind deleted the user row's descendants but kept the user row

Adding `deleteDescendants()` for mid-turn regeneration accidentally changed
`deleteSubtree()` to `doomed - rootId`, so Edit restored the workspace, deleted
the assistant/tool subtree, and left the user message on screen. The two
operations are now distinct: `deleteSubtree` deletes the root plus descendants;
`deleteDescendants` keeps the selected boundary and removes the rest.

### B23. The final-answer retry removed its tool definitions and lost the cache

The retry was "tool-less": it dropped the `tools` definitions to guarantee no
call. DeepSeek's cache is a prefix cache, so that changed the request from
`prompt=2201 cache=2048` to `prompt=191 cache=0` after a tool turn. The cache
was not reset; the cached tool-bearing prefix simply was not the request.

Keeping the definitions but appending a **transient** system instruction after
the cached prefix preserves it. The same retry then measured
`prompt=2148 cache=1920` (89%) and returned no tool calls. The app still refuses
any call before execution, so the cache is not paid for with a replayed write.
If the model violates the instruction, the turn stops with an error; nothing is
executed.

### B24. The composer sat under the keyboard, and Material's purple leaked through the theme — **FIXED**

Two failures found by opening the app rather than by reading the modifier chain.

The chat column applied `windowInsetsPadding(WindowInsets.safeDrawing)` before
`imePadding()`. `safeDrawing` includes the IME inset, so the first modifier
consumed exactly the inset the second needed; with the keyboard open, the
composer stayed behind it. Removing the redundant `safeDrawing` padding fixed the
phone layout. The same over-padding was making every other screen's content start
one status bar too low; it has been removed from those too.

The same class of bug appeared again in **Settings → Local**: the screen is
edge-to-edge, so `adjustResize` does not shrink it, and the settings scroll
column had no `imePadding()`. Focusing the server URL put the field behind the
keyboard. The scroll column now applies `imePadding()` before `verticalScroll`;
verified on the phone-sized emulator with the keyboard open. Any new scrollable
form with a bottom field needs the same check.

Separately, the new warm scheme still showed a lavender `FilterChip` and a
generic purple dialog. `InhumanTheme` set `primary`, `background`, `surface` and
a few roles, but Material reads `secondaryContainer` for a selected chip and the
`surfaceContainer*` family for menus/dialogs. An unset role is not "neutral": it
falls back to Material's stock purple. All container roles are now filled in both
schemes. The check is visual: a selected chip must be beige/orange, never
lavender.

### B25. The launcher icon was still the Android robot — **FIXED**

The manifest had no `android:icon`, so the system supplied the default robot on a
blue grid. The app now ships an adaptive icon: the wordmark's cream lowercase `i`
on the deep signal-orange background, with a monochrome layer for themed icons.
The foreground stays inside the adaptive safe zone, because the first version's
dot was clipped by a circular mask.

### B26. Fast scrolling in the transcript snapped/skipped — **FIXED**

The follow logic had three compounding problems. `onPreScroll` compared the
source to `NestedScrollSource.UserInput`, but current Compose reports a manual
drag/fling as `Drag`/`Fling`; following therefore stayed on. The auto-scroll
effect was also keyed on the last visible row's height and offset, which change
*during* the programmatic scroll, so each scroll cancelled and restarted the
effect. Finally, an `atBottom` effect re-armed following when a fast fling passed
through the bottom for a frame.

The result was a list that moved under the gesture and then jumped back. Now any
source except `SideEffect` turns following off, one stable layout observer with
`conflate()` replaces the geometry-keyed effect, and reaching the bottom is only
recognised after the scroll ends. `scrollToLatest` also skips `scrollToItem`
when the newest row is already visible, so a streaming tick no longer jumps to
that row's top before scrolling back down.

### B27. Backup import wrote the database into `filesDir` — **FIXED**

The restore copied the staged database to `filesDir/inhuman.db`, while Room
opens `context.getDatabasePath(...)` under `databases/`. The first round-trip
looked successful because the original database was still in place and Room kept
opening it; a clean install followed by the same backup restored zero
conversations. `applyPendingRestore` now swaps the database under `databases/`
(and creates that directory when absent), and a clean-install restore was
verified to recover 1 conversation / 18 messages / 1 summary.

### B28. Width was treated as the whole window — **FIXED**

`currentFormFactor()` reduced the window to `screenWidthDp`, so a 16:9 phone in
landscape (640×360) and a 700×700 square foldable both got the tablet shell. The
first had almost no vertical room for a rail; both lost the primary Create action
because the FAB was keyed to Compact width while the header only showed Create in
the two-pane layout.

`WindowFrame` now carries width and height classes plus font scale. The shell uses
`usesTwoPane` (Expanded width **and** at least Medium height), `usesFab` (Compact
width only), and `usesHeaderOverflow` (Compact width with font scale >1.2).
Medium single-pane windows get Create in the header. The `WideDialog` body
ceiling is also derived from window height.

**The regression that made this visible was a screenshot matrix, not a type
check:** at 360×640, 640×360, 700×700, 1024×768, and 1280×800 with one AVD, the
landscape and foldable home screens had no Create action at all. `WindowFrameTest`
now covers those classifications; the screenshots are still required because the
missing control was a composition decision no pure classifier could catch.

---

### B29. A Windows junction escaped the workspace — **FIXED**

`SessionWorkspace.resolve()` rejected symlinks and compared `canonicalFile()`
prefixes. A Windows directory junction is neither: `Files.isSymbolicLink()` says
false, and `File.canonicalFile` does not resolve a reparse point, so a junction
inside the workspace pointing at `C:\...\outside` resolved as an ordinary
directory. A `mklink /J` test on Windows failed with `Ok` for
`junction/secret.txt`.

The walk now calls `toRealPath()` on every existing component and compares it
with the real root; the final target is also realpath'd when it exists. The test
pins the junction case and skips on non-Windows filesystems. This is the reason
the `SessionWorkspace` invariant is no longer phrased as "canonical prefix": on
Windows, canonical is not enough.

### B30. The engine path answered the previous user message — **FIXED**

The first Android real-provider transcript on the phone showed this:

```text
user:      你好
assistant: I'll start by looking at what's in the workspace.
tool:      . is empty
assistant: Your workspace is empty, and your message didn't include a request...
user:      帮我搜搜今天的新闻
assistant: 你好！工作区目前是空的，你的消息里没有具体要求...
```

The new question got the previous turn's answer. The cause was history
construction, not the model: the engine path calls `buildWireHistory()` before
it creates the assistant placeholder, while the legacy path creates the
placeholder first. `buildWireHistory()` defaults to `dropLast(1)` because it
assumes that placeholder is present; on the engine path it dropped the **user's
just-committed message** instead.

The engine path now calls `buildWireHistory(includeCurrent = true)`. The phone
transcript is the reason the parameter is visible at that call site rather than
hidden behind a default.

### B31. Moving the checkpoint store to `:core:db` silently disabled every checkpoint — **FIXED**

`SnapshotStore` serialises its manifests with kotlinx.serialization. The file
moved to `:core:db`, which applied the JVM and KSP plugins but not
`kotlin.plugin.serialization`; `:core:data` had applied it, so the move compiled
clean. At runtime `Json.encodeToString(manifest)` threw `SerializationException`
because no serializer had been generated, and `capture()` wraps its work in
`runCatching` and returned `false` -- which the caller reads as "the snapshot
could not be taken". The visible result on Android would have been every local
mutation refused, a workspace that could not be written, while the build and
every compile check passed.

Four `SnapshotStoreTest` cases in `:core:data` caught it on the first full run,
at `assertTrue(SnapshotStore.capture(...))`. The serialization plugin is now in
`core/db/build.gradle`, with a comment at the plugin declaration. The general
trap: moving a file can lose a **compiler** plugin the old module supplied, and a
`runCatching` on the moved code turns that exception into a silent `false`.

### B32. The composer's trailing icon reserved a blank column beside every line — **FIXED**

The context ring lived in `OutlinedTextField`'s `trailingIcon`. That slot is not
a corner badge: Material lays it out as a 48dp column for the field's *whole
height*. With attach, send and a configured microphone beside the field, a
360dp phone gave the text about 70dp -- five characters a line -- and a
three-line message sat beside an empty 48dp-wide strip as tall as the message.
The ring now shares the composer's 48dp action row with Send; attach and
microphone are on the left of that row, and the text field spans the full width
of a single hairline-bounded surface. The field is a `BasicTextField` rather
than `OutlinedTextField`, because the Material box's own 56dp minimum and
decoration padding are what made the old row tall and its placeholder sit low.

Multiline is the case the trailing slot broke, so verify it with three lines:
one line hides the column because the field is only one line tall.

### B33. A finishing reply could still pull the reader to a newer turn — **FIXED**

`followLatest` was cleared only from `onPreScroll`, which needs a non-zero
vertical delta from a source other than `SideEffect`. A fling whose first
nested event arrived with a zero delta (or a source the connection treated as
ours) left the flag true. When the reply finished and the layout changed, the
stable observer saw "not at bottom" and scrolled to the newest turn; on the
phone this read as the transcript jumping away from the message being read
while the turn marker changed.

The list now also collects `LazyListState.interactionSource` and clears
following on `DragInteraction.Start`. Programmatic `scrollToItem`/`scrollBy`
do not emit a drag interaction, so the app's own scroll is unaffected. Verified
by scrolling up while a demo reply streamed: the view stayed on the older turn
after the reply completed. The nested-scroll path remains, but it is no longer
the only signal.

### B34. The transcript could jump to an adjacent turn — **FIXED**

The 2026-11-03 diagnostics recording (110 lines, 73 items / 17 turns) showed
`first index=67` at 13:30:06.402, `62` at .416 and `59` at .477, while a
drag/fling was in progress. There was no `jump effect`, no turn-picker/search
entry and no `follow scroll`; `follow=false` throughout. The turn marker changed
at 59 -- the normal boundary -- not at 67 -> 62.

A 4,000 dp/s fling cap (0.8.2) made the feel slower but did not stop the jump, so
it was reverted. The remaining possibilities were a real fling crossing short
rows and a layout shift during lazy composition; the old log's index/offset alone
could not distinguish them.

The 0.8.3 index logs could not separate a real scroll from a layout shift: their
`delta` covered everything since the previous index change. The 0.8.4 speed cap
and 0.8.5 distance cap did not stop the user's visual jump and changed the feel;
both were reverted in 0.8.6.

0.8.6 samples once per frame: the pixels actually scrolled in that frame, the
first/last offsets and sizes, and every visible item's size. On the emulator it
caught assistant item `9c841108` growing from 380 to 3,864 px with `delta=0`
right after a drag; the first/last offsets and the turn marker changed while no
scroll was consumed. That is a layout jump, not inertia.

The frame log's `resized=...` names the item whose height changed, and
`offsetJump=true` marks a large offset move with a near-zero delta. The next
phone recording will identify the item that resizes at the jump. The likely
source is a message row changing height when it enters composition (collapsible
sections and `animateContentSize`), not the fling.

0.8.7 caches each measured row height (`itemHeights`) and uses it as
`heightIn(min = cached)`; that removed the repeated jump but not the first one.

0.8.8 fixes the first measure. The m3 `Markdown` wrapper and
`rememberMarkdownState` parse from an effect, so a row entering `LazyColumn`
composes short. The finished path now calls the core `Markdown` overload with
`immediate = true`, which parses in the composition. The emulator's first frame
for `9c841108` is already 3,778 px and no `resized` line follows; the row no
longer grows after it is placed. The streaming path is untouched.

### B35. Scrolling past a user message stuttered — **FIXED**

The B34 instrumentation was itself part of the cost: a per-frame
`withFrameNanos` loop read `layoutInfo`, built a map of visible item sizes and
ran `Diag.i` for every first-row change, and the turn-index effect wrote a
`Diag` line at every user-message boundary. `Diag` writes synchronously, so the
work happened on the main thread exactly when a turn crossed the viewport. On
top of that, `immediate = true` parsed finished Markdown in the composition of
each row that scrolled into view.

0.8.9 removes the per-frame sampler and the scroll/turn diagnostic writes, and
moves finished Markdown parsing off the composition: `ChatScreen` parses each
distinct finished answer on `Dispatchers.Default` into a `State`, caches it by
text, and `MarkdownText` renders the ready state. A miss still falls back to the
immediate parse so a cold row cannot compose short. The follow logic, fling
feel and streaming path are unchanged.

### B36. Two FileProvider declarations with the same class silently lose the second — **FIXED**

The camera provider was first declared as a second
`androidx.core.content.FileProvider` with authority
`${applicationId}.capture`. The manifest merged and `dumpsys package` showed
both authorities, but PackageManager logged "Provider
ComponentInfo{com.inhuman/androidx.core.content.FileProvider} already defined;
ignoring": providers are keyed by **component name**, not by authority. The
surviving diagnostics instance served `com.inhuman.capture`, and the camera app
crashed while saving with `SecurityException: The authority
com.inhuman.capture does not match the one of the contentProvider:
com.inhuman.diagnostics`. The camera app died and nothing was staged, so the
only visible symptom was a missing attachment.

The capture provider is now `CaptureFileProvider`, a distinct subclass with its
own `capture_paths` meta-data. `CameraCaptureTest` performs the write/read round
trip through `content://com.inhuman.capture/camera/...`, which fails if the
registration is lost again.

### B37. A typed server URL was invisible to conversation upload — **FIXED**

`SettingsViewModel.onLocalServerUrlChange` updated the field but only
`requireServerUrl` wrote SecureStore, and that ran when Check for updates or
Upload log was pressed. Conversation upload reads SecureStore from
`ChatViewModel`, so a freshly typed address made the upload dialog say "set the
server URL first" even though the field was filled. Every valid edit is now
normalized and persisted; an invalid or cleared field removes the old value so
a hidden target cannot outlive the visible one.

### B38. Fixed-height dialog bodies put their own buttons off a short window — **FIXED**

The `AlertDialog` family capped its bodies at fixed 380–520dp — the SSH and
URL-download consents, the conversation tool sheet, the SSH host picker, the
model picker, the rewind picker, the compaction summary, the snapshot list,
the provider editor, the OAuth form, the provider picker, the host-key list,
the agents preview, the workspace assistant picker — and the five paid-media
dialogs (image, video, speech, decision, the speak-reply first-use notice) had
no cap and no scroll at all. M3 lays the action row below the text slot and
never moves it, so on a 640×360 landscape window — a required matrix ratio —
the body plus title plus buttons exceeded the whole dialog area: the SSH
consent's **Allow** sat below the screen edge, and the only reachable gesture,
a tap outside, *declines* a capability question. The same defect class had
been fixed twice before (`WideDialog`'s window-derived ceiling, the approval
dialog's `heightDp * 0.4f` details limit) without the rest of the family
following.

The fix is one helper, `dialogBodyMaxHeight(cap)` in `core/design/Layout.kt`,
which applies `WideDialog`'s measured 220dp reserve and 120dp floor to every
`AlertDialog` body; each fixed literal became `dialogBodyMaxHeight(<same
number>)`, so tall windows keep exactly the heights they had and only a short
window gets a short, scrollable body. The rewind picker and the snapshot list
moved their cap from an inner list to the whole text slot (fixed intro plus
list is one scroll region), the host-key list did the same, and the media
dialogs gained the scroll as well as the cap. The consent dialogs deliberately
stay `AlertDialog`s: their warning-above-checkbox order was tuned on a phone,
and a `WideDialog` move would have reordered the very text the consent exists
to make someone read.

**Verified on the phone AVD at 640×360dp** with the new
`tools/verify-dialog-heights.ps1` (uiautomator bounds, screenshots in
`.shots/`): the model picker's 完成, the conversation tool sheet's 应用/取消
and the snapshot list's 取消 all sit inside the window, and each dialog's dump
reports no node past the screen edge; the workbench itself already used the
window-derived ceiling. The SSH and download consents cannot be driven without
a live host or key, but their Allow row is slot-identical to the dialogs driven
here, and the unreachable button was the fixed cap, which is gone. Driving note
for the next script: a swipe that starts below a centered dialog lands on the
scrim and scrolls nothing — start it from a visible row's centre.

### B39. Three write paths had no failure handling — a failed save could crash the app — **FIXED**

`ConversationsViewModel.create`, the assistant read in its
`startNewConversation`, and `AgentsViewModel.save`/`delete` launched
repository writes with no `runCatching` inside a `viewModelScope`, so a Room
or disk failure was an uncaught coroutine exception, which ends the process.
The non-crash half was worse for the editor: the Create sheet had already
closed, so a failed create did nothing at all with no message, and a failed
assistant save lost the whole editing draft — prompt, tools and rules — at
the moment the user had the most to lose. `startScene` on the same screen
already had the right shape, and `ConversationsViewModel`'s own delete carried
the comment "a delete that fails is a thing to report, not to crash on";
these four paths now match their neighbours: create failures surface in the
home screen's existing error row, the assistant editor stays open with the
message above the name field, and the agents list gained the same dismissible
row. `AgentsUiState` gained the `error` field for this — its old namesake in
`CardExportDraft` was a different dialog's message, and no screen had ever
rendered a screen-level one. **Verified by build and the unit suite; not
device-driven** — the failure needs a fault injection the test set does not
have, and each fix is a `runCatching` around a single call.

### B40. Files blanked the whole screen on every revisit, and a failed delete said nothing — **FIXED**

`refresh()` set `loading = true` unconditionally and the screen rendered
`loading` as nothing at all, so every folder open, every back navigation and
every resume to Files drew an empty surface for the length of the workspace
walk before the list appeared — on the exact path where someone goes to see
what the assistant just wrote, the first frame said "no files". There was no
per-path memory, so even a folder seen seconds ago reloaded from white.

`refresh()` now keeps a per-path listing cache: a path seen before shows its
last rows while the walk runs, a first visit loads into a spinner instead of
a blank, and a result that finished after the user navigated is dropped
rather than shown under the wrong header. A delete that `deleteRecursively()`
refused used to leave the row in place with no explanation — the next
refresh then read as "the delete was refused" rather than "it did not work" —
and now reports itself through the existing error line (`files_delete_failed`).
The file's private non-atomic `MutableStateFlow.update` extension went in the
same change: it was the only `update` in scope (the library import was absent
entirely, not shadowed), and moving any state write off the main thread later
would have silently lost updates to it. **Verified by build and the unit
suite; the flash itself is a rendering timing uiautomator cannot sample
(§D11), so the pinned invariant is the cache logic: a revisit never sets
`loading` again.**

### B41. A three-line draft pushed the composer's action row off a short window — **FIXED**

`docs/06` carried this as the last open main-path defect: on a 640×360dp
landscape window with the soft keyboard up, a multi-line draft (the field was
`maxLines = 6` with no window-derived limit) plus the 48dp action row exceeded
what the IME left, the transcript's `weight(1f)` collapsed to zero, and send /
stop sat below the screen edge. The composer is a one-surface layout
(hairline-bounded field above a 48dp row, B32), so the fix belongs in the
field's line cap, not in moving controls back beside the text.

`composerMaxLines(heightClass)` in `app/.../ui/chat/ComposerLayout.kt` gives a
Compact-height window two lines and every other window six. The rule is the
height class rather than a fraction of the window because a proportional cap
also binds on a 360×800dp portrait phone, where the composer has the room;
`ComposerMaxLinesTest` asserts the whole matrix, including that 640×360 is the
only cell that is capped.

**Not device-driven, and recorded as such.** The intent was to type a
three-line draft at 1280×720@320dpi and assert the send row's bounds, and
`tools/verify-composer-touch.ps1` does exactly that. The emulator session used
for it could not inject text at all: the field reports `focused="true"`,
`dumpsys input_method` reports `mInputShown=true`, and neither
`adb shell input text` nor hardware keyevents arrived (`input text` was
confirmed working earlier in this same session for the file-tools message, so
the regression is in the emulator's input layer, not in the app). The pure
half is tested; the rendering half is the honest gap.

### B42. The most-tapped rows in the transcript were under half the recommended touch target — **FIXED**

Measured against Material's 48dp minimum: a collapsed section header
(`CollapsibleSection`) was ~30dp of touch and the turn usage line (`UsageLine`)
~26dp, both of them the rows a reader taps most. `Layout.Space.CollapsedPadding`
is 5dp of *visual* padding and is correct as such — but the comment above it
justified that number with arithmetic that did not hold: "a 14sp label plus
this is past 40dp" is 30dp, and the wrong claim is what kept the row small
(this repository's C-section standard: the comment was the bug).

Both rows now call `minimumInteractiveComponentSize()`, which supplies the
48dp target without re-inflating the visual padding A17 compressed, and the
`Layout.kt` comment states what the padding is for. The same pass removed two
explicit `Modifier.height(28.dp)` presses in `WireSheet` and gave the
read-aloud progress slider a `contentDescription` (an unnamed scrub control
reads as decoration to TalkBack).

**Verified by build and the unit suite only.** Touch-target height is
measurable in principle, but it needs a transcript with a collapsed section,
which needs a driven message — blocked by the same emulator input failure as
B41, so this is claimed as a framework guarantee, not as a measurement.

### B43. Fixed-width text columns clipped at large font scale

The turn picker's number column was a fixed `width(30.dp)` and the in-chat
search's role column a fixed `width(84.dp)`. At 1.5× font scale a
three-digit turn number and the word `ASSISTANT` no longer fit, so the number
clipped and the role label wrapped onto a second line, pushing the snippet
down. Both are now `widthIn(min = …)`: the column still starts at its designed
width and grows with the text instead of cutting it. Part of the same
large-font matrix row `WindowFrameTest` classifies.

### B44. The list screens had drifted from the shell's own rules — **FIXED**

Five smaller inconsistencies, each verified against the files that already
had the correct shape:

- **`ConversationsUiState.agentNames` was maintained and never read.**
  `ConversationsViewModel` builds the map on every list emission and its own
  doc comment says "so a row can say which assistant it belongs to", but no
  row read it — two conversations with different models were
  indistinguishable on the home screen. The row's meta line now shows the
  assistant name when it adds information (it is hidden when it duplicates
  the title).
- **The home list could not rename.** Pin and delete were in the row menu;
  rename existed only inside the chat screen, so changing a title meant
  loading the whole transcript. The row menu now has Rename (a dialog with the
  current title, `ConversationsViewModel.rename`, failures reported through
  the same error row as delete), and `docs/13-product.md`'s "pin, rename,
  delete" line is finally true of the list it describes.
- **The assistant row's trailing control was a one-tap Delete.** Files and the
  home list both keep a destructive action in a menu — Files' comment says
  "a destructive action one tap away from the row that opens a file is a
  mis-tap waiting to happen" — and the assistant row was the exception. It is
  an overflow menu now, with Edit beside Delete.
- **The assistants and workspaces screens ignored
  `prefersFabForPrimaryAction()`.** They floated a Create FAB on every window,
  including 640×360 landscape and 1024dp tablets where the home screen puts
  Create in the header and `Layout`'s own comment explains that a floating
  button over a short list covers a row. Both screens now follow the same
  rule: FAB on a compact-width window, header action elsewhere.
- **A folder over `maxListEntries` said nothing, and the workspace detail's
  usage line had no ceilings.** The user's browser truncated at 500 entries
  silently while the agent's `list_files` discloses its own truncation — the
  same "a partial result must not look complete" rule A25 states for
  archives. Files now counts before capping and renders
  `files_truncated_notice`; the workspace detail shows `N / max files ·
  X / max bytes`, the pair Files' header already shows.

Also in this pass: the assistant editor's "no provider configured" sentence
pointed at Settings with no way to get there (the dialog has no navigation),
so it gained an **Open Settings** button wired through `MainActivity`'s
existing `showSettings` state.

**Verified by build and the unit suite.** Every item here is a composition or
string choice; the list screens were not device-driven in this pass (the
emulator's input injection was broken — see B41), and none of the five changes
alters a capability, a policy or the persisted schema.

### B46. Every picker listed media tools whose backend could not run — **FIXED**

`docs/13-product.md` promised that a media tool whose backend is unsupported or
unconfigured "does not appear in the list", and `ChatViewModel.buildTools`
already omitted it from the *request*; the assistant editor, the conversation
tool sheet and the Settings approval table still offered it. Ticking such a row
changed nothing, and the empty result looked like a broken tool rather than an
unconfigured one.

The gate has three pieces, deliberately separated so the two questions cannot
collapse:

- **`MediaToolConfig.isConfigured`** (`app/.../ui/chat/MediaTools.kt`) asks the
  same question `mediaBackend`/`speechBackend` answer without building a client:
  a key with no endpoint, a provider kind that cannot serve the capability, an
  unresolved profile and plain HTTP all count as not configured, and the four
  file-writing tools additionally require a workspace (the same condition
  `buildTools` uses before registering them). `mediaConfiguredToolNames` maps
  that per-capability answer to the six catalogue names.
- **`ToolCatalog.Entry.gatedByMediaConfig`** marks `decide`, `transcribe`,
  `generate_image`, `generate_video`, `video_status` and `speak`, and
  `visible(mediaConfigured = ...)` drops the gated rows not in the set. A null
  set keeps them all.
- **The three pickers pass a real set** — resolved per context: the sheet uses
  the conversation's provider, model and workspace (`ChatUiState.configuredMediaTools`),
  the editor uses the assistant being edited, the global approval table asks
  "could any assistant run this?".

Two decisions worth keeping:

- **The gap report passes `null` on purpose.** `unavailable_tools` keeps its
  universe (`ToolCatalog.visible` without the media set), so a media tool the
  user selected but has not configured is still reported as
  `NeedsConfiguration` — it is the one place the model may name an unusable tool,
  because the user can fix it in Settings. Folding the media gate into
  `unavailable_tools`' universe would make the app unable to say why the tool
  vanished, and reusing `gatedBySsh`'s "switched off, never named" rule here
  would be wrong: this is a configuration gate, not a capability switch.
- **A hidden media name survives in storage.** Hiding the row does not rewrite
  the conversation's tool list (`mergeConversationTools` keeps names the sheet
  cannot show, exactly as SSH and download names survive their switches), so
  configuring the backend later restores the selection without re-picking.
  `ConversationToolsTest` pins both the absence and the survival.

**Verified by build and the unit suites** (`ToolCatalogMediaConfigTest`,
`MediaAvailabilityTest`, three new `ConversationToolsTest` cases) **and on the
phone AVD for the absence half**: `tools/verify-media-gate.ps1` installs a
clean build, opens the default assistant's editor, scrolls its tool list and
asserts none of the six names appears while `read_file` renders — the exact
state the product promise describes for a fresh install. The positive half is
**not** device-driven: this emulator's Gboard is in Chinese mode and converts
`:` `/` `.` to full-width characters, so the media dialog's https URL cannot be
typed, and the provider-key route did not complete either (the same
input-injection class as D13). `MediaAvailabilityTest` pins each resolution
branch instead, including DeepSeek serving speech but not image.

### B47. The conversation tool sheet hid a grant reset and listed thirty flat rows — **FIXED**

Two gaps in the one screen where the user decides what the agent may do:

- **Ticking `ssh_exec`, `ssh_upload` or `ssh_write_file` resets the
  conversation's SSH grant**, and the sheet said nothing about it. The reset is
  correct and load-bearing (`widensDangerousSsh`, `setConversationTools`), and
  the Settings paragraph mentioned it — but the moment it happens is the tick,
  so the sheet now shows the sentence there, driven by the same
  `widensDangerousSsh(selected, checked)` predicate the view model uses.
- **Thirty undifferentiated rows** turned the sheet into a settings panel. It
  now groups by family — Read, Write, External, Media, Remote, Other — with a
  heading per group. `toolFamilyOf` derives the family from the catalogue's own
  flags (`remote`/`gatedBySsh`, `gatedByMediaConfig`, `gatedByBilibili`/
  `gatedByDownload`, `workspaceBound` + `mutates`), so a new tool lands in the
  right group without a second table to maintain; `conversationToolGroups`
  keeps display order and drops empty groups, and a test asserts the grouping is
  a partition of the offered rows (nothing dropped, nothing repeated).

The workbench dialog got the matching one-line explanation: several of its rows
are greyed while a turn, maintenance or an export runs, and the dialog now says
so above them instead of leaving the grey unexplained (`chat_workbench_busy`).

**Verified by build and the unit suite.** The grouping test pins the partition
and the family order; the sheet's layout is the existing dialog body with
headings added, no new surface.

### B48. Settings search could not find a setting by the name people use for it — **FIXED**

The search matched a section's number, title and one-sentence summary. Those
sentences cannot name every control, so "音色", "B 站", "弹幕", "地址簿",
"javascript" and "Tailscale" all returned "no matching section" while the
setting sat inside one — on the page whose only navigation aid is that field.

Each of the twelve sections now carries a `keywords` resource (both languages)
holding the words for what is inside it, `SettingsSectionText` gained the field,
and the match itself moved into `settingsSectionMatches`
(`app/.../ui/settings/SettingsSearch.kt`) so `SettingsSearchTest` can pin it
without a composition: an empty query keeps everything, code/title/summary still
match, a keyword matches where the prose does not (and the same word does not
match without the keyword), unrelated words match nothing, and a query with
surrounding whitespace still matches.

**Verified by build and the unit suite.** The end-to-end drive was blocked by
this emulator's input injection failing again (taps and dumps work, typed text
does not land — D13): the keyword lists are resources and the predicate is
tested, so what remains unverified is only that the field wires the query
through, which is one line above the predicate.

### B49. A message's picture was drawn as its file name — **FIXED**

A user message with an image attachment rendered as `📎 photo.jpg`, on the
screen where the transcript is the record of what was said. The bytes were
deliberately never in `ChatUiState` (paths only), and the previous pass left the
thumbnail as "a small feature": that feature is now here, with the two decisions
that can go silently wrong extracted and tested.

- **Which attachments are images** — `isImageAttachment(mimeType, name)`: the
  reported MIME first, the extension as fallback, so a camera file with
  `application/octet-stream` still previews and `notes.txt` never tries to
  decode as a picture. An unreadable or non-image attachment keeps the chip it
  had before.
- **How large the decode may be** — `previewInSampleSize` returns a power of two
  (never zero, the value `BitmapFactory` rejects as a crash) that brings a photo
  under 384px on its long side, and `decodeAttachmentPreview` reads bounds first
  so a 4000px photo never allocates full size inside a `LazyColumn` item. The
  loader (`ChatViewModel.attachmentPreviewBytes`) goes through
  `SessionWorkspace.resolve` — the only path-to-File step — and refuses above
  8 MiB rather than reading a camera raw into the transcript.

The decode is a `LaunchedEffect` keyed by the attachment path, so a row that
scrolls out and back re-runs it at most once per composition instance, and the
bitmap lives in the row, not in state.

**Verified by build and the unit suite** (`AttachmentPreviewTest` pins the
classification and the sample arithmetic). The device half is not driven: an
image attachment can only be produced through the attach picker or the camera,
and this emulator's synthetic input is broken (D13) — so the rendering is
unverified on a device, which is recorded rather than implied.

### B50. The transcript was re-derived from scratch on every publish — **FIXED**

`ChatViewModel` publishes up to every 40 ms while a reply streams, and each
publish re-ran `turnSummariesOf`, `userTurnsOf` and `isInterruptedTurn` over the
whole transcript (off the main thread, but O(transcript) four times a tick on a
long conversation). B11 recorded why the obvious memo is wrong and why this
needed its own change: a key cheap enough to compute cannot see an **older** row
rewritten in place, which is what a reload or a rewind does, and a stale footer
is worse than a scan.

`TranscriptIndexer` (`app/.../ui/chat/TranscriptIndex.kt`) takes the opposite
approach — no key at all. It compares the new message list against the previous
one **by reference**, which is sound because of how the view model builds it
(`messages + row` reuses every earlier row object; a streaming row is the last
one). Three shapes are recognised, everything else falls back to a full pass:

1. the same list instance — only the streaming flag changed, so reuse the turn
   map and jump list and recompute the interrupted answer (O(tail));
2. one appended row with a shared prefix — patch the open turn; when the row is
   a turn root, rebuild the jump list (once per user message, not per tick);
3. the same rows with a changed tail inside the open turn — recompute that one
   turn (the per-tick case while streaming).

The fallback is *every* shape the class does not positively recognise — a
shorter list, a changed older row, a structurally equal but rebuilt list — so a
future mutation path can only be slower, never silently mis-derived.
`turnSummaryFor` was extracted from `turnSummariesOf` so both paths compute the
`complete` rule with one piece of code.

This closes the last item of the former B45 list, which is why B45 is absent
from the numbering above.

**Verified by build and the unit suites.** `TranscriptIndexerTest` has a
deterministic case per shape plus a 500-step randomised edit sequence (append
user/injected/assistant/tool rows, tail rewrites, older-row rewrites, whole-list
rebuilds, shrinks, streaming toggles) that compares the incremental index against
`turnSummariesOf`/`userTurnsOf`/`isInterruptedTurn` **after every step**,
including the turn-map key order. The existing `TurnSummaryTest`,
`InterruptedTurnTest` and `ChatUiStateTest` still pass unchanged, so the
extraction did not move any behaviour.

### B51. Three small drifts from the project's own rules — **FIXED**

- **Tool results were drawn in monospace even when they were prose.** A
  `web_search` snippet or a `web_fetch` article is the largest body-text area on
  a tool-heavy turn, and `AGENTS.md` says mono is for metadata. The wire
  inspector already rendered long text in sans, so the result row was the
  exception. `toolResultIsMachinery` now decides: JSON (parsed, not
  pattern-matched — `[see the notes]` is a sentence) and indented output keep
  monospace because alignment is the point; everything else uses the body font.
  `ToolResultTextTest` pins the boundary.
- **`anyDialogOpen` did not cover every modal.** That flag is what
  `MainActivity`'s BackHandler reads to decide whether Back belongs to a dialog,
  so a missing dialog is a correctness hole in the one place Back behaviour is
  decided — the media, upload, speech and retention dialogs were absent. They
  are covered now, with a comment saying why every new dialog must be added.
- **The context gauge's "faint" caveat was 0.9 alpha against 1.0**, which no
  screen can distinguish; 0.6 is the first step that reads as an aside. The
  comment claimed the branch existed for a reason the value did not deliver.

**Verified by build and the unit suite** (403 tests); the two visual changes are
opacity and font selection on existing rows, and the third is a boolean
expression, so no device drive was needed.

### B52. More palettes would have multiplied the two contrast bugs already found here — **FIXED**

- The original palette had two hand-found defects: `PaperMuted` at 4.35:1 on
  the dialog surface (it passed on the page background, but every dialog uses
  the surface), and `Ink700` at about 1.3:1 reused as placeholder text in
  twelve places. Adding three accent palettes and three neutral surface families
  would have repeated both mistakes thirty-six times over, so the text/background
  pairs for every palette × family × scheme are now checked in
  `PaletteContrastTest` on a plain JVM; the same test asserts the outline slots
  *stay* hairline-grade, because "fixing" their low contrast would make every
  divider loud.
- A surface family must move **every** neutral together. `SurfaceColors` carries
  background, surface, raised, outline and all text neutrals as one value, and
  `Theme.kt` reads nothing else for those roles: mixing a slate text colour
  onto a sepia surface is exactly how a "variant" becomes unreadable. The
  `defaultsAreTheOriginalLook` test pins the warm slots to the legacy constants.
- Forcing light on a dark system (or the reverse) used to flash the wrong
  window colour before the first Compose frame. `MainActivity.onCreate` now
  paints the window background from the stored `ThemeMode` and `SurfaceFamily`
  before `setContent`; the stored values are only colours — Compose still owns
  the theme.
- Choosing a palette or a ground is presentation, not capability: `ThemePalette`,
  `ThemeMode` and `SurfaceFamily` are read only by the theme and Settings, never
  by a tool, policy or request. The defaults are the original burnt orange and
  warm graphite/paper, so an install that has never touched the setting looks
  exactly as it did before.

**Verified by `:core:design:testDebugUnitTest` and the full build.** The theme
change is visual, so the device check is a screenshot, not a unit test; the
contrast numbers are the part worth pinning in code.

## C. Comments that contradict the code

By this repository's standard, the comment is the bug. These were all verified.
An entry leaves this table when the fix makes its comment true, rather than
staying as history.

| Comment | Reality |
| --- | --- |
| `WorkspaceTool.kt:68-70`: "the requested lines are over the ${ceiling}-byte read limit" | `out.length` counts UTF-16 characters, not bytes; a non-ASCII file can exceed a byte ceiling while passing this check. The streaming half of the old complaint — `readText()` before the slice — is fixed. |
| `SessionWorkspace.kt:151-153`: "anything behind a symlink is not charged to its quota" | Only symlinked *directories* are skipped; symlinked files are still counted (`:158-165`). |
| `SessionWorkspace.kt:20-22`: "[root] is never exposed" | `rootPath()` exposes it (`:175-184`) — deliberately, for `StatFs`, with the argument written out. |
| `SessionWorkspace.kt:26-33`: a symlink "can never be swapped in between the check and the open()" | The walk refuses to *traverse* links; nothing stops another same-UID writer creating one, and there is no re-check or `O_NOFOLLOW` at open. |
| `Tool.kt:81-82`: "Anything that writes, deletes or reaches outside the workspace should override this" | **No tool overrides `needsApproval` to return true**, `delete_file` included — it returns `false` (`FileTools.kt:355`). The advice is now historical: approval is decided by the user's configured rules, and `needsApproval` survives as an unused "must always ask" hook. |

---

## D. Traps in the build and the verification loop

1. **AGP 9 has built-in Kotlin.** No `kotlin-android` plugin, no kapt, and
   compiler options go in a top-level `kotlin { compilerOptions {} }` with
   `jvmTarget` deliberately unset. A comment is at `app/build.gradle:41-52` and
   the reason is in `README.md:109-133`.
2. **`compileSdk = 37` is required** by current AndroidX AAR metadata;
   `platforms;android-37.0` is not in a default SDK install. `targetSdk` is 36 on
   purpose.
3. **The Gradle wrapper points at a local `file:` distribution.** Regenerating it
   needs `tar -a -c -f` — `Compress-Archive` omits directory entries and the
   wrapper's unzip dies on the first file.
4. **`--rerun-tasks` matters.** With the build cache warm, a plain
   `test` / `testDebugUnitTest` can report `BUILD SUCCESSFUL` without compiling the file
   you changed. Check that the warning output is empty too.
5. **`xml` comments in `AndroidManifest.xml` cannot contain `--`.**
6. **Build scripts are Groovy, not Kotlin DSL.**
7. **Symlink tests are vacuous where symlinks cannot be created**
   (`SessionWorkspaceTest.kt:151-154`, `:165-167`), and the Windows drive-letter
   test is Windows-only green. There is no CI in this repository: run the
   Linux-only symlink tests and the Windows junction test locally, because each
   environment skips the other's case.
8. **Parse UI attributes, not quote characters.** uiautomator switches to single
   quotes when a value contains a double quote, which the delete dialog's title
   does. "The title did not change" was reported three times on the strength of a
   value that was never read (`tools/UiDump.psm1:1-17`).
9. **Remember `UiDump.psm1` with `Import-Module`**, not by dot-sourcing: dot
   sourcing runs it without putting its functions in the caller's scope.
10. **`adb shell input` is blocked on some OEM builds** (HyperOS), so a physical
    phone may be deployable and inspectable but not drivable.
11. **Screenshots cannot see inside a stream** — ~700ms per dump against a ~3.6s
    demo reply. Extract the invariant and test it instead.
12. **The `dsh_tablet` AVD does not start on this machine's current GPU setup**
    (2026-11-26): the emulator logs `Critical: Failed to load opengl32sw`,
    falls back to system OpenGL, and then opens a crash dialog, leaving a
    qemu process at ~176 MB with no adb device. `inhuman_phone` starts with
    `-gpu swiftshader_indirect -no-window`, which is what the tablet-ratio
    checks in `tools/verify-tablet-shell.ps1` now use on a resized phone AVD
    — `wm size 2560x1600` + `wm density 320` is 1280×800dp, the ratio
    AGENTS.md allows as a supplement, not the two-device-profile substitute.
13. **`adb shell input text` can stop landing while the UI still reports a
    focused field and a shown IME.** Seen on 2026-11-26 in the phone AVD after
    a long driven session: `dumpsys input_method` said `mInputShown=true`, the
    composer's `EditText` was `focused="true"`, and neither `input text` nor
    hardware keyevents changed its value — while the same command had worked
    earlier in the same session for the same app. Nothing in the app changed;
    a fresh emulator boot cleared it. When a drive stops typing mid-session,
    check the injection layer before reading the app's code (B41's composer
    check was blocked by exactly this).
