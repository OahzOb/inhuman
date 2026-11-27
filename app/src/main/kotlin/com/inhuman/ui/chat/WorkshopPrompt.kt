package com.inhuman.ui.chat

import com.inhuman.core.data.db.WorkspaceKinds

/**
 * The paragraph that tells a conversation it is running in the built-in
 * workshop.
 *
 * ## Why the model has to be told
 *
 * The host appends the workshop helpers to a non-scene conversation in the
 * workshop, and each tool description says what that tool does. What no tool
 * says is the *situation*: "this is the workshop, and there is no scene in play
 * yet" and "a draft is not a change". A workspace does not ship with an
 * `AGENTS.md` (the workshop is created empty), so without this block a fresh
 * workshop conversation looks like an ordinary chat that happens to have a few
 * authoring tools -- and a model can start narrating a scene that does not
 * exist, or announce a configuration change nobody saved.
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
 * Content, not capability (`docs/07-gotchas.md` A31). The block may explain
 * what the host appended and what the user's tap means; it must not claim the
 * model can change its tool set, approvals, SSH/download grants, providers,
 * assistants' capability fields, or enter a scene/workspace itself.
 */
internal const val WORKSHOP_PROMPT_MARKER = "Workshop context:"

/**
 * The block for this conversation, or null when it is not in the workshop.
 *
 * @param kind the workspace row's [WorkspaceKinds] value.
 * @param roleplayActive the conversation is itself a scene (a scene, or an
 *   assistant with a character card). A scene gets no workshop block: it has
 *   its own role-play prompt, and the workshop is not a mode it can switch to.
 */
internal fun workshopPrompt(kind: String?, roleplayActive: Boolean): String? {
	if (roleplayActive) return null
	return when (kind) {
		WorkspaceKinds.WORKSHOP -> WORKSHOP
		else -> null
	}
}

private val WORKSHOP = """
	$WORKSHOP_PROMPT_MARKER this conversation runs in the user's workshop, a built-in
	permanent workspace where the host lets you prepare two kinds of work for the
	user. Role-play: use rp_format to write a card, rp_validate to check a card or
	scene file when that helper is attached, and rp_create_scene to prepare a scene.
	This is the workshop, not a scene: no character is in play, and the scene is a
	separate conversation you cannot enter. App configuration: use app_list_* and
	app_read_* to read the user's assistants, each permanent workspace's root
	AGENTS.md and the saved-prompt library, and app_propose_* to prepare drafts of
	an assistant, an AGENTS.md or a saved prompt. Nothing you prepare takes effect
	by itself: a scene proposal creates nothing until the user taps Create and
	open; a draft opens the user's existing editor prefilled and is written only
	when the user saves there; a saved-prompt proposal is written only when the
	user taps Save to prompt library. Never announce a scene, a character, a saved
	instruction, a stored prompt or an applied setting as if it already existed.
	You cannot change tools, approval rules, SSH or download grants, providers or
	models, or delete an assistant or a saved prompt.
""".trimIndent()
