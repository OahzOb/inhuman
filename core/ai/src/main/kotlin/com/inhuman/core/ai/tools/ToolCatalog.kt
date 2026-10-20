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
	)

	/**
	 * Every tool the app can offer.
	 *
	 * Order is deliberate: reads first, then the clock and the web, then the
	 * ones that change files. A list sorted by name would put `delete_file`
	 * third, which is the wrong thing to make easy to find.
	 */
	val all: List<Entry> = listOf(
		Entry("list_files", "See what is in the conversation's folder", mutates = false),
		Entry("read_file", "Read a file from the folder", mutates = false),
		Entry("file_info", "Size and modified date of a file", mutates = false),
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
			"ssh_exec",
			"Run a command on another machine over SSH",
			mutates = true,
			reversible = false,
			remote = true,
		),
		Entry("write_file", "Create or replace a file", mutates = true),
		Entry("append_file", "Add to the end of a file", mutates = true),
		Entry("create_directory", "Make a folder inside the folder", mutates = true),
		Entry("move_file", "Move or rename something in the folder", mutates = true),
		// Was "Delete from the folder -- asks first". It no longer does by
		// default, and this string is what a user reads when choosing tools, so
		// leaving it would have been a claim the code does not honour -- the
		// exact failure `docs/07-gotchas.md` section C exists to catch. Whether
		// a call asks is now configurable per tool, per assistant and per path;
		// the default is not to.
		Entry("delete_file", "Delete from the folder", mutates = true),
	)

	val names: List<String> = all.map { it.name }

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
	 */
	fun permits(selected: List<String>?, toolName: String): Boolean {
		if (toolName !in names) return false
		// SSH is never covered by the historical "null means everything"
		// selection. That selection predates SSH, and reading it as consent
		// would silently hand every old assistant a remote shell. It must be
		// named explicitly in the selection.
		if (toolName == "ssh_exec") return selected?.contains(toolName) == true
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
}
