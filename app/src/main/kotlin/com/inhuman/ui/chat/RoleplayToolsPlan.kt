package com.inhuman.ui.chat

/**
 * Which role-play authoring helpers belong in a turn's tool set.
 *
 * Extracted from `ChatViewModel.buildTools` so the availability rule can be
 * tested without an Android view model. The rule is the interesting part:
 * these are **not** selectable capabilities. They attach to a workspace-file
 * capability the user already granted, and they disappear with it, so a pure
 * role-play scene (`tools = []`) keeps an empty set and `rp_validate` can never
 * become a read path on its own.
 */
internal data class RoleplayHelpersPlan(
	val format: Boolean,
	val validate: Boolean,
)

/**
 * @param fileToolNames every workspace file tool this host could build.
 * @param permitted the conversation's selection check (see `ToolCatalog.permits`).
 */
internal fun planRoleplayHelpers(
	fileToolNames: List<String>,
	permitted: (String) -> Boolean,
): RoleplayHelpersPlan {
	val allowed = fileToolNames.filter(permitted)
	return RoleplayHelpersPlan(
		// The format text is inert, but it is only useful where the agent can
		// actually write a card, so it follows the file capability too.
		format = allowed.isNotEmpty(),
		// Validation reads one workspace file; it additionally requires a tool
		// that can already read one, or it would be a new read grant.
		validate = allowed.any { it == "read_file" || it == "list_files" },
	)
}
