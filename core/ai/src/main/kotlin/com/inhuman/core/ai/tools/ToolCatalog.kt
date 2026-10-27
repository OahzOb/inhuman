package com.inhuman.core.ai.tools

/**
 * What tools exist, so a person can choose between them.
 *
 * ## Why this is a catalogue rather than a scan of the registry
 *
 * The registry is built per conversation and depends on things that are not
 * known when someone is editing an assistant -- whether a search provider is
 * configured, whether the workspace opened. A picker cannot be built from that:
 * it would show different options depending on unrelated state, and an assistant
 * saved against a missing tool would look broken later.
 *
 * So the catalogue is the *possible* set, declared statically, and the registry
 * is the *available* set. An assistant may name a tool that a particular
 * conversation cannot offer; that is filtered at run time rather than being an
 * error, because the reasons are temporary.
 *
 * ## Why the descriptions are duplicated here
 *
 * The tool's own `description` is prompt text, written for a model. A few of
 * them are long. What a person choosing between tools needs is shorter and
 * different -- not "prefer searching over guessing" but "can look things up on
 * the web". [blurb] is that second sentence, and having it separate is why this
 * is a hand-written list rather than something derived.
 */
object ToolCatalog {

	/** One entry: the name the model calls, and what to tell a person. */
	data class Entry(
		val name: String,
		val blurb: String,
		/**
		 * Whether replaying the turn with tools enabled could change state.
		 *
		 * Surfaced in the picker because it is the consequence a person is
		 * actually choosing about: a mutating tool means a retry that re-runs
		 * tools is a second real write. Regenerating the final text is still
		 * allowed, but it is sent without tools so this cannot happen.
		 */
		val mutates: Boolean,
		/**
		 * Whether the effects can be undone inside the app's boundaries.
		 *
		 * A file write is a change to a workspace copy: the original is still
		 * in the user's hands. A remote SSH command is not reversible at all.
		 * This is separate from [mutates]: `delete_file` mutates but its blast
		 * radius is the conversation copy; `ssh_exec` mutates and cannot be
		 * undone. Rollback warns on the irreversible ones.
		 */
		val reversible: Boolean = true,
		/**
		 * Whether using it spends money at a third party.
		 *
		 * Surfaced in the picker and in the approval rules because the cost is
		 * real and invisible otherwise: these tools call DeepSeek's own paid
		 * APIs, and the bill arrives on the same account as the conversation.
		 * A tool that is merely read-only is not automatically free.
		 */
		val paid: Boolean = false,
		/**
		 * Whether the tool can affect something outside this device.
		 *
		 * A remote command is not a workspace copy: the damage at the other end
		 * can be irreversible. The picker marks it so the name is not the only
		 * warning.
		 */
		val remote: Boolean = false,
		/**
		 * Whether the tool needs the conversation's workspace to work.
		 *
		 * A picker must not offer one while the startup fence check says the
		 * workspace gate is unreliable: the request would omit it anyway, and a
		 * row that cannot do anything is a claim the app does not honour. The
		 * remote tools are marked because their file transfers and identity
		 * reads go through the same workspace.
		 */
		val workspaceBound: Boolean = false,
	)

	/**
	 * Every tool the app can offer.
	 *
	 * Order is deliberate: reads first, then the clock and the web, then the
	 * ones that change files. A list sorted by name would put `delete_file`
	 * third, which is the wrong thing to make easy to find.
	 */
	val all: List<Entry> = listOf(
		Entry(
			"list_files",
			"See what is in the conversation's folder",
			mutates = false,
			workspaceBound = true,
		),
		Entry(
			"read_file",
			"Read a file from the folder",
			mutates = false,
			workspaceBound = true,
		),
		Entry(
			"file_info",
			"Size and modified date of a file",
			mutates = false,
			workspaceBound = true,
		),
		Entry(
			"read_document",
			"Read a CSV, TXT, DOCX or XLSX document",
			mutates = false,
			workspaceBound = true,
		),
		Entry("read_time", "The current date and time, and date arithmetic", mutates = false),
		Entry(
			"web_search",
			"Search the web via the configured paid search API",
			mutates = false,
			paid = true,
		),
		Entry(
			"web_fetch",
			"Fetch a page via the configured paid fetch API",
			mutates = false,
			paid = true,
		),
		Entry(
			"decide",
			"Classify or score a state with a typed decision model",
			mutates = false,
			paid = true,
		),
		Entry(
			"transcribe",
			"Transcribe an audio file from the workspace",
			mutates = false,
			paid = true,
			workspaceBound = true,
		),
		Entry(
			"ssh_probe",
			"Snapshot a remote Linux machine",
			mutates = false,
			reversible = true,
			remote = true,
			workspaceBound = true,
		),
		Entry(
			"ssh_list",
			"List one remote directory over SFTP",
			mutates = false,
			reversible = true,
			remote = true,
			workspaceBound = true,
		),
		Entry(
			"ssh_read_file",
			"Read a remote text file",
			mutates = false,
			reversible = true,
			remote = true,
			workspaceBound = true,
		),
		Entry(
			"ssh_exec",
			"Run a command on another machine over SSH",
			mutates = true,
			reversible = false,
			remote = true,
			workspaceBound = true,
		),
		Entry(
			"ssh_upload",
			"Send a workspace file to the remote machine",
			mutates = true,
			reversible = false,
			remote = true,
			workspaceBound = true,
		),
		Entry(
			"ssh_write_file",
			"Write a remote text file",
			mutates = true,
			reversible = false,
			remote = true,
			workspaceBound = true,
		),
		Entry(
			"ssh_download",
			"Copy a remote file into the workspace",
			mutates = true,
			reversible = true,
			remote = true,
			workspaceBound = true,
		),
		Entry(
			"generate_image",
			"Generate an image and save it in the workspace",
			mutates = true,
			paid = true,
			workspaceBound = true,
		),
		Entry(
			"generate_video",
			"Start an async video generation task",
			mutates = true,
			paid = true,
			workspaceBound = true,
		),
		Entry(
			"video_status",
			"Check a video task and save it when finished",
			mutates = true,
			workspaceBound = true,
		),
		Entry(
			"speak",
			"Convert text to an audio file in the workspace",
			mutates = true,
			paid = true,
			workspaceBound = true,
		),
		Entry("write_file", "Create or replace a file", mutates = true, workspaceBound = true),
		Entry("append_file", "Add to the end of a file", mutates = true, workspaceBound = true),
		Entry(
			"create_directory",
			"Make a folder inside the folder",
			mutates = true,
			workspaceBound = true,
		),
		Entry(
			"move_file",
			"Move or rename something in the folder",
			mutates = true,
			workspaceBound = true,
		),
		// Was "Delete from the folder -- asks first". It no longer does by
		// default, and this string is what a user reads when choosing tools, so
		// leaving it would have been a claim the code does not honour -- the
		// exact failure `docs/07-gotchas.md` section C exists to catch. Whether
		// a call asks is now configurable per tool, per assistant and per path;
		// the default is not to.
		Entry("delete_file", "Delete from the folder", mutates = true, workspaceBound = true),
		Entry(
			"write_document",
			"Create or replace a CSV, TXT, DOCX or XLSX document",
			mutates = true,
			workspaceBound = true,
		),
		Entry(
			"convert_document",
			"Convert between CSV, TXT, DOCX and XLSX",
			mutates = true,
			workspaceBound = true,
		),
	)

	val names: List<String> = all.map { it.name }

	/**
	 * The tools a picker should show for the current global capability state.
	 *
	 * Every remote tool is not merely unavailable while the global switch is
	 * off: it is absent. Showing a disabled row still tells the user (and the
	 * model-facing picker preview) that remote execution exists; the switch in
	 * Runtime is the only place that should advertise it.
	 *
	 * [includeWorkspaceBound] is the same rule for the startup fence check:
	 * when the workspace gate could not be proven, the tools that depend on it
	 * are absent rather than greyed. A stored selection keeps its names, so
	 * recovery restores the capability without the user re-picking it.
	 */
	fun visible(includeSsh: Boolean, includeWorkspaceBound: Boolean = true): List<Entry> =
		all.filter { entry ->
			(includeSsh || !entry.remote) && (includeWorkspaceBound || !entry.workspaceBound)
		}

	/**
	 * Whether a selection permits a tool.
	 *
	 * @param selected null means every tool. Empty means none.
	 *
	 * Null and empty are different answers and both are reachable: an assistant
	 * created before the choice existed gets null and keeps everything, while
	 * one deliberately set up for chat alone gets empty and keeps nothing.
	 * Collapsing them would make one of those two impossible to express.
	 *
	 * ## Why membership in the catalogue is checked as well
	 *
	 * A selection is a stored record, and storage outlives releases. An
	 * assistant saved before a tool was renamed holds the old name, and a tool
	 * removed in a later version holds a name nothing answers to. Returning
	 * `true` for those would let a *name* pass a permission check when no such
	 * tool exists -- harmless today, because the registry lookup that follows
	 * finds nothing, but it means the check answers a question about a string
	 * rather than about a capability.
	 *
	 * Asking the catalogue makes the rule say what it means: a tool is permitted
	 * when the selection allows it **and it is a tool this app has**.
	 *
	 * ## Why every remote tool needs its own name
	 *
	 * `ssh_exec` was the first remote capability, and null predates it; reading
	 * null as consent would hand every old assistant a remote shell. The file
	 * transfers are the same capability and get the same rule -- a check that
	 * special-cased one name would silently grant the other two.
	 */
	fun permits(selected: List<String>?, toolName: String): Boolean {
		val entry = all.firstOrNull { it.name == toolName } ?: return false
		if (entry.remote) return selected?.contains(toolName) == true
		return selected == null || toolName in selected
	}

	/**
	 * The catalogue as a selection, for "select all".
	 *
	 * Returns null rather than the full list, so that a later release adding a
	 * tool gives it to assistants that had chosen everything -- which is what
	 * "everything" meant when it was chosen.
	 */
	fun allSelection(): List<String>? = null

	/**
	 * "Everything" written out as a list, for the moment someone turns one
	 * tool off in the picker.
	 *
	 * The stored value has to change on the first toggle -- `null` means
	 * everything, so it cannot express "everything except one" -- and the list
	 * it becomes has to mean what was on screen. That is every tool that is
	 * not remote.
	 *
	 * It used to be `names - "ssh_exec"`, and that was a silent grant of the
	 * other six remote tools: `permits` treats a remote name in the selection
	 * as consent, so unticking an unrelated box (or ticking any one SSH tool)
	 * put `ssh_probe`, `ssh_upload` and the rest into the saved list. The
	 * picker showed them checked, the user had never chosen them, and
	 * `docs/08-port-philosophy.md` §1.2.1 says remote capability may not be
	 * switched on as a side effect of "select all".
	 */
	fun everythingExplicit(): List<String> = visible(includeSsh = false).map { it.name }
}
