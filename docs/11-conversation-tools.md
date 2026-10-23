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
  streaming, because the request being built has already been sent.
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

- `agent.tools == null`: the ceiling is every non-remote catalogue entry. A
  remote tool is available only if the conversation already names it.
- `agent.tools` is a list: the ceiling is that list. The conversation's existing
  names are added to the available set as well, so a tool the assistant no longer
  has can be restored but not invented.
- `ToolCatalog.visible(sshEnabled)` is applied last: with SSH off, no remote row
  is shown, and existing remote names are kept in storage but not registered in
  the turn.

The result is that editing an assistant to add `ssh_upload` makes it available in
an existing conversation, while a conversation cannot grant itself a capability
its assistant never had.

## The read-only SSH profile

The same mechanism is what makes a no-exec SSH profile possible. Select
`ssh_list`, `ssh_read_file` and `ssh_download`, leave `ssh_exec` and `ssh_upload`
unchecked, and the model can find a file, read a small text file, or copy one
file into the workspace without a shell on the remote machine. The three tools
are registered independently; `ssh_download` does not require `ssh_exec`.

`ssh_list` is one SFTP `ls`, not `find`: no recursion, at most 500 rows.
`ssh_read_file` is capped at 64 KiB and refuses binary, pointing at
`ssh_download` instead of truncating. Upload is the only remote write in this
profile and is a separate explicit choice.

The consent text follows the selection (`SshConsent.canExec`/`canUpload`), so a
read-only grant is not described as "run any command". Adding `ssh_exec` or
`ssh_upload` to a read-only grant clears `sshAllowed`; the next call asks again
with the new text. Adding `ssh_list`/`ssh_read_file` does not reset it.

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
  explicit-only rule and the hidden-when-disabled rule.
- The UI itself is checked on the tablet and phone profiles: the sheet scrolls,
  Apply is disabled while streaming, and the count in the menu matches the
  selection.

## Non-goals

- A model-callable `set_tools` tool.
- Automatic synchronisation from the assistant on every edit.
- Per-conversation approval policy. Approval remains global / per-assistant /
  per-path; this sheet changes *which* tools exist, not whether a call asks.
- A second "advanced" mode. The sheet is the only new surface.
