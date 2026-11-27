package com.inhuman.ui.chat

import com.inhuman.core.data.db.WorkspaceKinds

/**
 * The paragraph that tells a conversation it is running in one of the built-in
 * workshops.
 *
 * ## Why the model has to be told
 *
 * The host appends the workshop helpers to a non-scene conversation in an RP or
 * configuration workshop, and each tool description says what that tool does.
 * What no tool says is the *situation*: "this is the RP workshop, and there is
 * no scene in play yet" or "this is the configuration workshop, and a draft is
 * not a change". A workspace does not ship with an `AGENTS.md` (the workshop is
 * created empty), so without this block a fresh workshop conversation looks
 * like an ordinary chat that happens to have a few authoring tools -- and a
 * model can start narrating a scene that does not exist, or announce a
 * configuration change nobody saved.
 *
 * ## Why it is not a pinned file block
 *
 * The text is derived from the workspace kind alone, so it is identical on
 * every turn: the cache prefix does not move. It is not read from a file the
 * user could delete, because the kind -- and therefore the attached helpers --
 * would still be there; the sentence has to describe the helpers that are
 * actually in the request. The pinned `AGENTS.md` block stays separate and still
 * comes last.
 *
 * ## What it may not become
 *
 * Content, not capability (`docs/07-gotchas.md` A31/A32). The block may explain
 * what the host appended and what the user's tap means; it must not claim the
 * model can change its tool set, approvals, SSH/download grants, providers,
 * assistants' capability fields, or enter a scene/workspace itself.
 */
internal const val WORKSHOP_PROMPT_MARKER = "Workshop context:"

/**
 * The block for this conversation, or null when it is not in a workshop.
 *
 * @param kind the workspace row's [WorkspaceKinds] value.
 * @param roleplayActive the conversation is itself a scene (a scene, or an
 *   assistant with a character card). A scene gets no workshop block: it has
 *   its own role-play prompt, and the workshop is not a mode it can switch to.
 */
internal fun workshopPrompt(kind: String?, roleplayActive: Boolean): String? {
	if (roleplayActive) return null
	return when (kind) {
		WorkspaceKinds.ROLEPLAY -> ROLEPLAY_WORKSHOP
		WorkspaceKinds.STUDIO -> STUDIO_WORKSHOP
		else -> null
	}
}

private val ROLEPLAY_WORKSHOP = """
	$WORKSHOP_PROMPT_MARKER this conversation runs in the user's RP workshop, a built-in
	permanent workspace for preparing role-play material (character cards, scene
	settings, and scenes to start). It is the workshop, not a scene: no character
	is in play and no scene has been started here. Use rp_format to write a card,
	rp_validate to check a card or scene file when that helper is attached, and
	rp_create_scene to prepare a scene the user can start. rp_create_scene only
	prepares a proposal card -- nothing is created until the user taps Create and
	open, the scene is a separate conversation, and you cannot enter it yourself.
	Never announce a scene, a character or a story as if it already existed.
""".trimIndent()

private val STUDIO_WORKSHOP = """
	$WORKSHOP_PROMPT_MARKER this conversation runs in the user's configuration workshop, a
	built-in permanent workspace for setting the app up. Here the host lets you
	read the user's assistants (display name, system prompt, character card, model
	id) and every permanent workspace's root AGENTS.md, and prepare drafts: a new
	or edited assistant, or an AGENTS.md for a workspace. A draft is not a change:
	its card opens the user's existing assistant editor or Files editor prefilled,
	and nothing takes effect until the user saves there, so never say that a change
	was saved or applied. You cannot change tools, approval rules, SSH or download
	grants, providers or models, delete an assistant, or read the saved-prompt
	library.
""".trimIndent()
