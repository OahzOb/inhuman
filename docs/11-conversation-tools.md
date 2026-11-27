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
- Remote tools never arrive through "all". Each family is selected explicitly, and
  the SSH families are two: read-only (`ssh_hosts`, `ssh_probe`, `ssh_list`,
  `ssh_read_file`, `ssh_download`) and exec/write (`ssh_exec`, `ssh_upload`,
  `ssh_write_file`) have separate master controls, so choosing the reading half
  cannot grant a shell. The existing once-per-conversation SSH consent still
  applies, and adding the exec/write family to a read-only grant resets it.
- Widening is an explicit user action in the sheet. This is not a new approval
  layer: there is no per-call or per-change modal, and the ordinary tool policy
  is unchanged.
- Narrowing is immediate (next turn) and needs no confirmation.

One more gate sits beside these: **a media tool is absent from the sheet until
its backend resolves** (`docs/03-ai-provider.md`, "Media backends"). It is a
configuration gate, not a capability one -- the stored names are kept, so
configuring the backend later restores a selection without re-picking -- and the
same filter applies to the assistant editor's tool list and the approval tables,
so no list offers a row that would do nothing. `unavailable_tools` is the
deliberate exception: it still names an unconfigured media tool as needing
configuration, because the model may tell the user how to fix it.

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

The same mechanism is what makes a no-exec SSH profile possible. Select the SSH
**read-only** family (`ssh_hosts`, `ssh_probe`, `ssh_list`, `ssh_read_file`,
`ssh_download`) and leave the **exec/write** family (`ssh_exec`, `ssh_upload`,
`ssh_write_file`) off, and the model can inspect a host, find a file, read a
small text file or its tail, or copy one file into the workspace without a shell
on the remote machine. The two families are separate master controls on every
surface, so the read-only grant is one gesture and the dangerous half cannot
arrive on the way. The tools are registered independently; `ssh_download` does
not require `ssh_exec`.

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

Chat overflow menu -> **Conversation tools**. A scrollable list of **families**:
one row per family with a tri-state master checkbox, the count of its selected
members, aggregate `PAID`/`REMOTE` badges, and a disclosure arrow. All-on, all-off
and mixed are distinct states; tapping a mixed family turns the missing members
on rather than discarding the rest, and tapping an all-on family turns it off.
The individual tool rows -- name, badges, one-line description, checkbox -- are
one tap below their family, and only one family is open at a time. The footer
says that changes apply from the next turn, and warns when a selected tool is
remote or irreversible. Apply writes the selection; Cancel closes without
changing anything. The row is disabled while a turn is streaming.

The families come from the catalogue's own flags (`app/.../ui/common/ToolGroups.kt`),
and SSH is two of them: read-only and exec/write. Removing a tool is unchecking it
(and applying); adding one is the same gesture. The warning is the disclosure, not
a second dialog.

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

A family master control is the same rule as ticking its members: it writes their
names into the stored list, and a family containing opt-in tools (read_pdf_vision,
read_scanned, Bilibili, SSH, download_url) only gains them through that explicit
gesture. Nothing about a family control makes `null` mean remote tools.

Hidden remote tools (SSH switched off while the conversation still names one) are
preserved through an edit rather than dropped, because a switch-off is not a
request to forget the selection.

## Helpers the sheet never shows

`recall` (continuous sessions), the twelve workshop helpers and
`unavailable_tools` are appended by the host after the allow-list. The workshop
helpers are the role-play set (`rp_format` / `rp_validate` / `rp_create_scene`),
the configuration set (`app_list_assistants`, `app_read_assistant`,
`app_propose_assistant`, `app_list_instructions`, `app_read_instruction`,
`app_propose_instruction`) and the saved-prompt set (`app_list_prompts`,
`app_read_prompt`, `app_propose_prompt`). They are not catalogue entries, so the
sheet neither shows nor changes them: a conversation's selection is about
**capability**, and these have none. `recall` reads this conversation's own
record; `rp_format` is constant text; `rp_validate` reads a workspace file only
when a read tool is already selected; `rp_create_scene` prepares a proposal and
creates nothing until the user taps a card.

All three sets exist together, only in a non-scene conversation whose workspace
has `kind = "workshop"` (schema v29; one built-in workshop, the v28 role-play and
configuration kinds merged) — a workspace-scoped presentation decision, not a
grant (`docs/07-gotchas.md` A31). A conversation that is itself a role-play
scene gets none of them. The configuration helpers read app configuration rather
than workspace files: assistant content, each permanent workspace's root
`AGENTS.md` by display name, and the user's saved-prompt library. Their propose
tools write nothing; the assistant/instruction cards open the existing
assistant/Files editor and the user's Save is the only write, while the
saved-prompt card's **Save to prompt library** is its own write through
`SavedPromptRepository.save`. A proposal cannot carry a tool selection, provider,
model or approval rule, and no helper can delete an assistant or a saved prompt,
or select a prompt for an assistant.

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
- `ToolGroupsTest` (JVM): the family partition; the SSH read/write split derived
  from `reversible`; a mixed family turning on rather than discarding; a family
  toggle writing names explicitly so `null` still refuses remote and opt-in
  tools; and a family rule writing every member while `Free` removes the row.
- `ToolCatalogTest` / `ToolCatalogVisibilityTest` already pin the remote
  explicit-only rule and the hidden-when-disabled rule; `ToolCatalogTest` also
  pins that the list an assistant edit materialises contains **no remote name**
  and still names every non-remote tool.
- The UI itself is checked on the tablet and phone profiles: the sheet scrolls,
  Apply is disabled while streaming, and the count in the menu matches the
  selection.

## Non-goals

- A model-callable `set_tools` tool.
- A model-callable workspace-kind change or workshop-helper toggle: entering
  the workshop and leaving it are user gestures, and the helpers are not a
  capability to grant.
- Automatic synchronisation from the assistant on every edit.
- Per-conversation approval policy. Approval remains global / per-assistant /
  per-path; this sheet changes *which* tools exist, not whether a call asks.
- A second "advanced" mode. The sheet is the only new surface.
