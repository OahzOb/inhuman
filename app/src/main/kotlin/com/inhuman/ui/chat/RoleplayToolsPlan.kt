package com.inhuman.ui.chat

/**
 * Which role-play authoring helpers belong in a turn's tool set.
 *
 * Extracted from `ChatViewModel.buildTools` so the availability rule can be
 * tested without an Android view model. The rule is the interesting part:
 *
 * - **These are not selectable capabilities.** They are appended after the
 *   allow-list, never appear in the assistant editor, the conversation tool
 *   sheet, the approval tables or the gap report, and add no network, file or
 *   execution power of their own.
 * - **They exist only in the RP workshop.** A workspace's kind decides whether
 *   its non-scene conversations get the authoring helpers and the scene
 *   proposal tool. Ordinary workspaces get no role-play tooling at all, which
 *   is the management shape the product chose: one place to build RP content,
 *   instead of a per-assistant switch on every conversation.
 * - **Scenes keep an empty tool set.** A conversation that is itself a role-play
 *   scene (`roleplayActive`) gets no authoring helpers: the story should not
 *   offer "build me a scene" tools.
 * - **The validator needs a read capability.** `rp_validate` reads a workspace
 *   file, so it attaches only where the conversation already has `read_file` or
 *   `list_files`; it must never become a read path the user did not grant.
 *   `rp_format` is constant text and needs nothing; `rp_create_scene` only
 *   previews data and creates nothing until the user taps.
 */
internal data class RoleplayHelpersPlan(
	val format: Boolean,
	val validate: Boolean,
	val createScene: Boolean,
)

/**
 * @param workshop the conversation's workspace has [com.inhuman.core.data.db.WorkspaceKinds.ROLEPLAY].
 * @param roleplayActive the conversation is itself a scene (a scene, or an
 *   assistant with a character card); the authoring helpers would be a second
 *   role inside the story.
 * @param fileToolNames every workspace file tool this host could build.
 * @param permitted the conversation's selection check (see `ToolCatalog.permits`).
 */
internal fun planRoleplayHelpers(
	workshop: Boolean,
	roleplayActive: Boolean,
	fileToolNames: List<String>,
	permitted: (String) -> Boolean,
): RoleplayHelpersPlan {
	if (!workshop || roleplayActive) return RoleplayHelpersPlan(false, false, false)
	val allowed = fileToolNames.filter(permitted)
	return RoleplayHelpersPlan(
		// Inert, and the proposal tool may be writing an inline card that no
		// file tool can produce -- so the format text is present even in a
		// workshop conversation with no file capability.
		format = true,
		// Validation reads one workspace file; it additionally requires a tool
		// that can already read one, or it would be a new read grant.
		validate = allowed.any { it == "read_file" || it == "list_files" },
		// The proposal is app data; nothing is created until the user taps.
		createScene = true,
	)
}
