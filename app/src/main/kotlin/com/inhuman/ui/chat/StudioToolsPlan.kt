package com.inhuman.ui.chat

/**
 * Which configuration helpers belong in a turn's tool set.
 *
 * Extracted from `ChatViewModel.buildTools` so the availability rule can be
 * tested without an Android view model. The rule mirrors the role-play helpers:
 *
 * - **Only in the configuration workshop.** A workspace's kind decides whether
 *   its non-scene conversations get these helpers. Ordinary workspaces get no
 *   configuration tooling at all, which is the management shape the product
 *   chose: one place to ask an agent to set the app up, instead of a per
 *   assistant switch.
 * - **These are not selectable capabilities.** They are appended after the
 *   allow-list, never appear in the assistant editor, the conversation tool
 *   sheet, the approval tables or the gap report, and add no network, file or
 *   execution power of their own. They can list and read the app's configuration
 *   and *prepare* a draft; the user's existing editors are the only writers.
 * - **A scene gets none of them.** A conversation that is itself a role-play
 *   scene must not offer "manage the assistants" tools inside the story.
 */
internal data class StudioHelpersPlan(
	val listAssistants: Boolean,
	val readAssistant: Boolean,
	val proposeAssistant: Boolean,
	val listInstructions: Boolean,
	val readInstruction: Boolean,
	val proposeInstruction: Boolean,
) {
	companion object {
		/** The plan when no helper may be attached. */
		val NONE = StudioHelpersPlan(
			listAssistants = false,
			readAssistant = false,
			proposeAssistant = false,
			listInstructions = false,
			readInstruction = false,
			proposeInstruction = false,
		)

		/** Every helper, for a configuration-workshop conversation. */
		val ALL = StudioHelpersPlan(
			listAssistants = true,
			readAssistant = true,
			proposeAssistant = true,
			listInstructions = true,
			readInstruction = true,
			proposeInstruction = true,
		)
	}
}

/**
 * @param studio the conversation's workspace has
 *   [com.inhuman.core.data.db.WorkspaceKinds.STUDIO].
 * @param roleplayActive the conversation is itself a scene (a scene, or an
 *   assistant with a character card); configuration tools would be a second role
 *   inside the story.
 */
internal fun planStudioHelpers(studio: Boolean, roleplayActive: Boolean): StudioHelpersPlan =
	if (studio && !roleplayActive) StudioHelpersPlan.ALL else StudioHelpersPlan.NONE
