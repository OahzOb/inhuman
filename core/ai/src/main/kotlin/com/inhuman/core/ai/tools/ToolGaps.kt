package com.inhuman.core.ai.tools

/**
 * What the app offers right now, minus what this request actually carries.
 *
 * ## What is reported, and what is deliberately not
 *
 * The universe is [ToolCatalog.visible] for the host's current capability
 * state -- the tools the app would put in a picker at this moment. Anything
 * missing from that set (SSH while the global switch is off, the sandbox when
 * its probe failed, the follow-up tool when it is off) is **not** reported.
 * That is the same rule as the picker: a capability the app has switched off is
 * absent, not greyed, and a tool whose whole purpose is to describe gaps must
 * not become the greyed row in text form. A prompt injection cannot learn
 * "ssh_exec exists" from this tool while SSH is off.
 *
 * What it does report is the gap the user can act on: a tool this app offers
 * *now* that this conversation cannot use, and whether the reason is the
 * conversation's selection or a missing configuration.
 *
 * ## Why this is not a way to widen capability
 *
 * The tool only returns names and reasons. `ToolCatalog.permits` still decides
 * what runs, the user still edits the selection, and nothing here changes a
 * grant. The model can say "you have not enabled read_file" instead of "I
 * cannot read files" -- which is the whole point -- and nothing more.
 */
object ToolGaps {

	/** Why an offered tool is not in this request. */
	enum class Reason(val wire: String) {
		/** The conversation's or assistant's selection does not include it. */
		NotSelected("not selected for this conversation"),

		/**
		 * The selection includes it, but the host could not build it -- a
		 * provider key, a media backend or another setting is missing.
		 */
		NeedsConfiguration("offered by the app, but not usable here: it needs configuration"),
	}

	data class Gap(
		val name: String,
		val blurb: String,
		val reason: Reason,
	)

	/**
	 * @param available names actually registered for this request.
	 * @param selected the conversation's or assistant's tool selection, or
	 *   null for "everything" (which still excludes opt-in tools).
	 * @param offered [ToolCatalog.visible] for the current capability state.
	 */
	fun compute(
		available: Set<String>,
		selected: List<String>?,
		offered: List<ToolCatalog.Entry>,
	): List<Gap> =
		offered
			.filter { it.name !in available }
			.map { entry ->
				Gap(
					name = entry.name,
					blurb = entry.blurb,
					reason = if (ToolCatalog.permits(selected, entry.name)) {
						Reason.NeedsConfiguration
					} else {
						Reason.NotSelected
					},
				)
			}
}
