# Conversation tools

## The problem

A conversation copies its assistant's tool selection at creation
(`ConversationRepository.create`: `toolsJson = ToolsColumn.encode(agent?.tools)`).
That copy is what makes an old conversation stable when the assistant is edited,
but it also means a tool added by a release, or a tool the user adds to the
assistant later, never reaches a conversation that is already running. Starting a
new conversation is not a small workaround: a temporary workspace belongs to the
old conversation, and the transcript, the files and the context go with it.

`ssh_upload` and `ssh_download` made this visible: the assistant gained two tools,
and an existing 68-minute SSH conversation could not use either without starting
over.

## The rule

**Capability is fixed per turn, not per conversation.**

- Only the user can change a conversation's tool set. There is no tool that
  changes tools, and nothing in a message, a tool result or a model response can
  add one.
- A change applies to the **next** turn. The sheet is disabled while a reply is
  streaming, because the request being built has already been sent. A message
  the user writes during that turn (queued or inserted, `docs/06-agent-loop-ui.md`)
  is not a tool change: it cannot alter this turn's set, and it does not touch the
  sheet.
- The assistant is the ceiling. A conversation can choose from tools the
  assistant has, or tools the conversation already had; it cannot invent a tool
  the assistant never offered.
- Remote tools never arrive through "all". Each one is selected explicitly, and
  the existing once-per-conversation SSH consent still applies.
- Widening is an explicit user action in the sheet. This is not a new approval
  layer: there is no per-call or per-change modal, and the ordinary tool policy
  is unchanged.
- Narrowing is immediate (next turn) and needs no confirmation.

The last point matters more than it looks. The alternative -- a prompt on every
tool change -- is friction the user has already said they do not want, and the
sheet plus Apply is already a deliberate action. The security property preserved
here is that the **model cannot change its own capability**, not that the user
needs two dialogs to do so.

## The ceiling, precisely

Let `agent.tools == null` mean the historical "all tools", which already excludes
remote tools, and let `conversation.tools == null` mean the same for that
conversation.

- `agent.tools == null`: the ceiling is every non-remote catalogue entry that is
  not opt-in. A remote tool, `suggest_replies` (opt-in because it spends an
  extra request), `read_pdf_vision` (opt-in because it spends one request per
  page) and `read_scanned` (opt-in because it uploads a file to a paid OCR
  service) are available only if the conversation or assistant already names
  them.
- `agent.tools` is a list: the ceiling is that list. The conversation's existing
  names are added to the available set as well, so a tool the assistant no longer
  has can be restored but not invented.
- `ToolCatalog.visible(sshEnabled, includeWorkspaceBound = workspaceToolsEnabled,
  includeSuggestions = suggestionsEnabled, includePdfVision = true)` is applied
  last: with SSH off, no remote row is shown; with the follow-up switch off, no
  `suggest_replies` row is shown; a host without the PDF renderer keeps
  `read_pdf_vision` hidden. Existing names are kept in storage but not registered
  in the turn.
  With the startup workspace self-check failed, every workspace-bound row --
  files, media and SSH -- is absent for the same reason; the stored names
  survive, so a later healthy start restores them without the user re-picking
  anything.

The result is that editing an assistant to add `ssh_upload` makes it available in
an existing conversation, while a conversation cannot grant itself a capability
its assistant never had.

## The read-only SSH profile

The same mechanism is what makes a no-exec SSH profile possible. Select
`ssh_probe`, `ssh_list`, `ssh_read_file` and `ssh_download`, leave `ssh_exec`,
`ssh_upload` and `ssh_write_file` unchecked, and the model can inspect a host,
find a file, read a small text file or its tail, or copy one file into the
workspace without a shell on the remote machine. The tools are registered
independently; `ssh_download` does not require `ssh_exec`.

`ssh_probe` runs one fixed read-only script (OS, load, disk, failed units, ports,
containers); it accepts no command. `ssh_list` is one SFTP `ls`, not `find`: no
recursion, at most 500 rows. `ssh_read_file` reads the head up to 64 KiB or the
last `tailLines` from a bounded tail window, and refuses binary instead of
truncating. `ssh_write_file` is a separate text-write tool, and upload is the
artifact path; both are remote writes and explicit choices.

The consent text follows the selection
(`SshConsent.canExec`/`canUpload`/`canWriteFile`), so a read-only grant is not
described as "run any command". Adding `ssh_exec`, `ssh_upload` or
`ssh_write_file` to a read-only grant clears `sshAllowed`; the next call asks
again with the new text. Adding `ssh_probe`/`ssh_list`/`ssh_read_file` does not
reset it.

## UI

Chat overflow menu -> **Conversation tools**. A scrollable list of the available
entries with the same badges as the assistant editor (`PAID`, `REMOTE`, and
`REWIND` / `NO REWIND`), a one-line description, and a checkbox. The footer says
that changes apply from the next turn, and warns when a selected tool is remote
or irreversible. Apply writes the selection; Cancel closes without changing
anything. The row is disabled while a turn is streaming.

Removing a tool is just unchecking it and applying. Adding a tool the conversation
did not have is the same gesture; the warning is the disclosure, not a second
dialog.

## Storage

`conversations.toolsJson` already exists and `ToolsColumn` already encodes
`null` / `[]` / a list. The first edit pins an explicit list; it no longer
inherits future catalogue additions. That is the cost of editing, and it is
visible in the sheet.

The list an edit pins is **the catalogue minus every remote tool**
(`ToolCatalog.everythingExplicit`). `null` already refuses remote tools, so the
list that replaces it must refuse them too; the picker used to write
`names - "ssh_exec"`, which put the other six remote names into the saved
selection, and `ToolCatalog.permits` reads a remote name in a selection as
consent. One unticked box would have granted six SSH tools that nobody chose.

Hidden remote tools (SSH switched off while the conversation still names one) are
preserved through an edit rather than dropped, because a switch-off is not a
request to forget the selection.

## Why this is not a new exception

The SSH exception is about a capability that leaves the device; this change is
about **who may edit a selection** and **when it takes effect**. It adds no host
configuration, no credential store, no new approval prompt and no tool the model
can call to change its own capabilities. `docs/08-port-philosophy.md` §1.2.1 and
§1.5 still apply; the only sentence that changes is the timing: the set is fixed
per turn, not for the lifetime of the conversation.

## Tests

- `ConversationToolsTest` (JVM): the ceiling with `null` and explicit assistant
  selections; preserving a removed tool; hidden remote names; catalogue order;
  merge on apply.
- `ToolCatalogTest` / `ToolCatalogVisibilityTest` already pin the remote
  explicit-only rule and the hidden-when-disabled rule; `ToolCatalogTest` also
  pins that the list an assistant edit materialises contains **no remote name**
  and still names every non-remote tool.
- The UI itself is checked on the tablet and phone profiles: the sheet scrolls,
  Apply is disabled while streaming, and the count in the menu matches the
  selection.

## Non-goals

- A model-callable `set_tools` tool.
- Automatic synchronisation from the assistant on every edit.
- Per-conversation approval policy. Approval remains global / per-assistant /
  per-path; this sheet changes *which* tools exist, not whether a call asks.
- A second "advanced" mode. The sheet is the only new surface.
