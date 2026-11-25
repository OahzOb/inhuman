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

	/**
	 * The one tool that is implemented only by the Android host.
	 *
	 * It renders PDF pages to bitmaps and asks a vision model to transcribe
	 * them. [visible] keeps it out unless a host explicitly asks for it, so a
	 * catalogue consumer without that renderer and request path cannot offer a
	 * row it cannot execute. Keeping the name here means the catalogue, the
	 * picker filter and the registry cannot drift apart.
	 */
	const val PDF_VISION_TOOL = "read_pdf_vision"

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
		/**
		 * Whether "all tools" deliberately excludes it.
		 *
		 * A null selection has always meant every tool, and it still does. This
		 * flag is for a tool that must be **chosen**, not inherited: `suggest_replies`
		 * spends an extra request per answer that calls it, so an assistant
		 * created before this release -- or one whose owner picked "everything" --
		 * must not start paying for it because a new name appeared. Remote
		 * tools already work this way for a stronger reason; the rule is shared
		 * so "null means everything" cannot quietly grow a second, weaker
		 * reading, and so the picker shows the row unchecked until someone
		 * ticks it.
		 */
		val optIn: Boolean = false,
		/**
		 * Whether the tool belongs to the SSH capability without reaching it.
		 *
		 * `ssh_hosts` lists the machines the user shared; it opens no socket.
		 * It is still hidden while the global switch is off, because the list
		 * is part of that capability's disclosure -- but it must not trigger
		 * the first-call consent dialogue, which is about connecting. This flag
		 * is that distinction, and it is separate from [remote] for exactly the
		 * reason the consent gate is keyed on [remote].
		 */
		val gatedBySsh: Boolean = false,
		/**
		 * Whether the tool belongs to the anonymous Bilibili read capability.
		 *
		 * Read-only, free and account-free, so it is not [paid] and not
		 * [remote]: it reads public content through the app and cannot affect
		 * anything outside it. It is still a capability the user must switch on,
		 * because it is a new external data path, and while that switch is off
		 * the row is absent from every picker rather than greyed -- the same
		 * rule §1.2.1 applies to remote tools, for the same reason.
		 *
		 * It is also [optIn]: a null "all tools" selection predates this name,
		 * and a new external read must not appear in every existing assistant
		 * because a release added it.
		 */
		val gatedByBilibili: Boolean = false,
		/**
		 * Whether the tool belongs to the app-proxied URL download capability.
		 *
		 * The app makes the request and the agent still has no socket, but a
		 * URL is not inert: anything encoded into its path or query leaves the
		 * device. That is why the capability is off by default, chosen per
		 * assistant, and consented per conversation. While the global switch is
		 * off the row is absent from every picker, request and gap report -- the
		 * same rule §1.2.1 applies to the remote tools, for the same reason.
		 *
		 * It is also [optIn]: a null "all tools" selection predates this name,
		 * and a new external path must not appear in every existing assistant
		 * because a release added it.
		 */
		val gatedByDownload: Boolean = false,
		/**
		 * Whether this tool only works while a Bilibili account is linked.
		 *
		 * The host hides it while the login is absent: a row that cannot run is
		 * the greyed row §1.2.1 refuses, and unlike a missing key there is a
		 * visible place right next to the switch that explains how to fix it.
		 */
		val bilibiliLoginRequired: Boolean = false,
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
			"Read a CSV, TXT, DOCX, XLSX, PDF, DOC or XLS document",
			mutates = false,
			workspaceBound = true,
		),
		Entry(
			PDF_VISION_TOOL,
			"Read scanned PDF pages with the current vision model",
			mutates = false,
			paid = true,
			workspaceBound = true,
			optIn = true,
		),
		Entry(
			"read_scanned",
			"Read scanned PDF pages or images with DeepSeek OCR (uploads the file; paid per page)",
			mutates = false,
			paid = true,
			workspaceBound = true,
			optIn = true,
		),
		Entry("read_time", "The current date and time, and date arithmetic", mutates = false),
		Entry(
			"run_javascript",
			"Run a short JavaScript calculation in an isolated no-network sandbox",
			mutates = false,
		),
		Entry(
			"unavailable_tools",
			"See which app tools this conversation cannot use, and why",
			mutates = false,
		),
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
		// Anonymous, read-only Bilibili. Free and account-free, so no PAID or
		// REMOTE badge: the app makes the request and the model still has no
		// network. They are opt-in twice for the same reason as SSH and
		// suggestions -- the global switch makes them available, and the
		// assistant's explicit selection puts one in a request.
		Entry(
			"bili_search",
			"Search Bilibili videos or column articles, anonymously",
			mutates = false,
			optIn = true,
			gatedByBilibili = true,
		),
		Entry(
			"bili_video",
			"Read a Bilibili video's public details and description",
			mutates = false,
			optIn = true,
			gatedByBilibili = true,
		),
		Entry(
			"bili_comments",
			"Read the top comments on a Bilibili video",
			mutates = false,
			optIn = true,
			gatedByBilibili = true,
		),
		Entry(
			"bili_article",
			"Read a Bilibili column article as plain text",
			mutates = false,
			optIn = true,
			gatedByBilibili = true,
		),
		Entry(
			"bili_transcript",
			"Read a Bilibili video's subtitles, or Bilibili's AI summary when it has none (needs login)",
			mutates = false,
			optIn = true,
			gatedByBilibili = true,
			bilibiliLoginRequired = true,
		),
		Entry(
			"bili_up",
			"Read a Bilibili uploader's page: profile, recent videos, collections",
			mutates = false,
			optIn = true,
			gatedByBilibili = true,
		),
		Entry(
			"bili_trending",
			"Read Bilibili's ranking, popular feed or hot searches",
			mutates = false,
			optIn = true,
			gatedByBilibili = true,
		),
		Entry(
			"bili_danmaku",
			"Read a sample of a Bilibili video's on-screen comments, with timestamps",
			mutates = false,
			optIn = true,
			gatedByBilibili = true,
		),
		Entry(
			"bili_bangumi",
			"Read a Bilibili show's details and episode list",
			mutates = false,
			optIn = true,
			gatedByBilibili = true,
		),
		Entry(
			"bili_live",
			"Check whether a Bilibili live room is streaming",
			mutates = false,
			optIn = true,
			gatedByBilibili = true,
		),
		Entry(
			"decide",
			"Classify or score a state with a typed decision model",
			mutates = false,
			paid = true,
		),
		Entry(
			"suggest_replies",
			"Offer 2-3 follow-ups after answering, using one extra paid request",
			mutates = false,
			paid = true,
			optIn = true,
		),
		Entry(
			"transcribe",
			"Transcribe an audio file from the workspace",
			mutates = false,
			paid = true,
			workspaceBound = true,
		),
		Entry(
			"ssh_hosts",
			"List the remote machines the user shared with this conversation",
			mutates = false,
			optIn = true,
			gatedBySsh = true,
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
			"download_url",
			"Download a file from a URL into the folder",
			mutates = true,
			workspaceBound = true,
			optIn = true,
			gatedByDownload = true,
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
			"Create or replace a CSV, TXT, DOCX, XLSX, PDF or XLS document",
			mutates = true,
			workspaceBound = true,
		),
		Entry(
			"convert_document",
			"Convert between CSV, TXT, DOCX, XLSX, PDF and XLS (DOC read-only)",
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
	 *
	 * [includeSuggestions] is the same rule again for the follow-up tool: while
	 * its global switch is off it is absent from every picker, approval table
	 * and home summary, not a greyed row. The default is false so a consumer
	 * that has not implemented the capability does not offer a row that would
	 * do nothing there.
	 */
	fun visible(
		includeSsh: Boolean,
		includeWorkspaceBound: Boolean = true,
		includeSandbox: Boolean = true,
		includeSuggestions: Boolean = false,
		/**
		 * Whether the global Bilibili read switch is on.
		 *
		 * Off means the Bilibili read tools are absent from the pickers, the
		 * approval table and `unavailable_tools`, not a greyed row: a disabled
		 * row still advertises the capability, and §1.2.1 refuses that for the
		 * remote tools for the same reason. Off is the default so a caller that
		 * has not wired the switch cannot offer a row that would do nothing.
		 */
		includeBilibili: Boolean = false,
		/**
		 * Whether the optional Bilibili account login is currently linked.
		 *
		 * Only `bili_transcript` requires it. While it is absent the row is
		 * hidden like any other unavailable capability; the anonymous Bilibili
		 * reads do not depend on it.
		 */
		includeBilibiliLogin: Boolean = false,
		/**
		 * Whether the global URL-download switch is on.
		 *
		 * Off means `download_url` is absent from the pickers, the approval
		 * table and `unavailable_tools`, not a greyed row -- the same rule the
		 * remote tools follow. Off is the default so a caller that has not
		 * wired the switch cannot offer a row that would do nothing.
		 */
		includeDownload: Boolean = false,
		/**
		 * Whether the host implements [PDF_VISION_TOOL]. Unlike SSH or the
		 * sandbox this is not a runtime capability switch: it is a statement
		 * about the binary. The Android pickers pass true; the default keeps a
		 * caller without an executor from offering a row it cannot run.
		 */
		includePdfVision: Boolean = false,
	): List<Entry> =
		all.filter { entry ->
			(includeSsh || !(entry.remote || entry.gatedBySsh)) &&
				(includeWorkspaceBound || !entry.workspaceBound) &&
				(includeSandbox || entry.name != "run_javascript") &&
				(includeSuggestions || entry.name != "suggest_replies") &&
				(includeBilibili || !entry.gatedByBilibili) &&
				(includeBilibiliLogin || !entry.bilibiliLoginRequired) &&
				(includeDownload || !entry.gatedByDownload) &&
				(includePdfVision || entry.name != PDF_VISION_TOOL)
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
		if (entry.remote || entry.optIn) return selected?.contains(toolName) == true
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
	fun everythingExplicit(includeSuggestions: Boolean = false): List<String> =
		visible(includeSsh = false, includeSuggestions = includeSuggestions)
			// Opt-in tools are chosen one by one, never inherited by "select
			// all": the point of the flag is that a new paid or external tool
			// does not enter an existing assistant because someone ticked an
			// unrelated box. `visible()` still lists them (the picker shows
			// the row), so they are removed here rather than by a gate per
			// tool.
			.filterNot { it.optIn }
			.map { it.name }
}
