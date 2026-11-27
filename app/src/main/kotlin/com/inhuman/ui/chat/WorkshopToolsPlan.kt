package com.inhuman.ui.chat

/**
 * Which host-appended workshop helpers belong in a turn's tool set.
 *
 * Extracted from `ChatViewModel.buildTools` so the availability rule can be
 * tested without an Android view model. The rule is the interesting part:
 *
 * - **These are not selectable capabilities.** They are appended after the
 *   allow-list, never appear in the assistant editor, the conversation tool
 *   sheet, the approval tables or the gap report, and add no network, file or
 *   execution power of their own.
 * - **They exist only in the one built-in workshop.** A workspace's kind decides
 *   whether its non-scene conversations get them. Ordinary workspaces get no
 *   workshop tooling at all, which is the management shape the product chose:
 *   one place to ask the agent to prepare role-play material and app
 *   configuration, instead of a per-assistant switch on every conversation
 *   (`docs/proposals/workshop-merge.md`).
 * - **Scenes keep an empty tool set.** A conversation that is itself a role-play
 *   scene (`roleplayActive`) gets no helpers: the story should not offer "build
 *   me a scene" or "manage the assistants" tools.
 * - **The validator needs a read capability.** `rp_validate` reads a workspace
 *   file, so it attaches only where the conversation already has `read_file` or
 *   `list_files`; it must never become a read path the user did not grant.
 *   `rp_format` is constant text and needs nothing; `rp_create_scene` only
 *   previews data and creates nothing until the user taps.
 * - **The two jobs can be used together.** A card written with `rp_format` can
 *   become an assistant through `app_propose_assistant`, and an assistant card
 *   can be the character of a scene through `rp_create_scene`; that is why the
 *   sets share one workshop instead of two.
 */
internal data class WorkshopHelpersPlan(
	val format: Boolean,
	val validate: Boolean,
	val createScene: Boolean,
	val listAssistants: Boolean,
	val readAssistant: Boolean,
	val proposeAssistant: Boolean,
	val listInstructions: Boolean,
	val readInstruction: Boolean,
	val proposeInstruction: Boolean,
	val listPrompts: Boolean,
	val readPrompt: Boolean,
	val proposePrompt: Boolean,
) {
	companion object {
		/** The plan when no helper may be attached. */
		val NONE = WorkshopHelpersPlan(
			format = false,
			validate = false,
			createScene = false,
			listAssistants = false,
			readAssistant = false,
			proposeAssistant = false,
			listInstructions = false,
			readInstruction = false,
			proposeInstruction = false,
			listPrompts = false,
			readPrompt = false,
			proposePrompt = false,
		)
	}
}

/**
 * @param workshop the conversation's workspace has
 *   [com.inhuman.core.data.db.WorkspaceKinds.WORKSHOP].
 * @param roleplayActive the conversation is itself a scene (a scene, or an
 *   assistant with a character card); the helpers would be a second role inside
 *   the story.
 * @param fileToolNames every workspace file tool this host could build.
 * @param permitted the conversation's selection check (see `ToolCatalog.permits`).
 */
internal fun planWorkshopHelpers(
	workshop: Boolean,
	roleplayActive: Boolean,
	fileToolNames: List<String>,
	permitted: (String) -> Boolean,
): WorkshopHelpersPlan {
	if (!workshop || roleplayActive) return WorkshopHelpersPlan.NONE
	val allowed = fileToolNames.filter(permitted)
	return WorkshopHelpersPlan(
		// Inert, and the proposal tool may be writing an inline card that no
		// file tool can produce -- so the format text is present even in a
		// workshop conversation with no file capability.
		format = true,
		// Validation reads one workspace file; it additionally requires a tool
		// that can already read one, or it would be a new read grant.
		validate = allowed.any { it == "read_file" || it == "list_files" },
		// The proposal is app data; nothing is created until the user taps.
		createScene = true,
		// The configuration helpers read the app's own configuration and prepare
		// drafts; the user's existing editors are the only writers.
		listAssistants = true,
		readAssistant = true,
		proposeAssistant = true,
		listInstructions = true,
		readInstruction = true,
		proposeInstruction = true,
		// The saved-prompt library is content too: read and draft it, and let
		// the user's tap on the proposal card be the write.
		listPrompts = true,
		readPrompt = true,
		proposePrompt = true,
	)
}
