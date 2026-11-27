package com.inhuman.ui.chat

import com.inhuman.AppCapabilities
import com.inhuman.core.ai.tools.ToolCatalog

/**
 * What the conversation-tool sheet may offer, and what is currently on.
 *
 * The rules are deliberately asymmetric: the assistant is the ceiling, but the
 * conversation keeps what it already had. An assistant edited to add
 * `ssh_upload` therefore reaches an existing conversation, while a conversation
 * cannot invent a capability its assistant never offered.
 */
internal data class ConversationToolPlan(
	val available: List<ToolCatalog.Entry>,
	val selected: Set<String>,
)

internal fun conversationToolPlan(
	sshEnabled: Boolean,
	agentTools: List<String>?,
	conversationTools: List<String>?,
	/**
	 * False when the startup fence check failed. The sheet then stops offering
	 * every tool that needs the workspace -- including SSH, whose transfers and
	 * identity reads go through it -- while the stored names stay in the
	 * conversation, so a later healthy start restores them without a re-pick.
	 */
	workspaceToolsEnabled: Boolean = true,
	/** False hides `suggest_replies` from the sheet, not merely disables it. */
	suggestionsEnabled: Boolean = false,
	/**
	 * False hides the Bilibili read rows. The stored names stay in the
	 * conversation, so switching the capability back on restores a selection
	 * without asking the user to remember it.
	 */
	bilibiliEnabled: Boolean = false,
	/** False hides `bili_transcript`; the anonymous Bilibili reads stay. */
	bilibiliLoggedIn: Boolean = false,
	/**
	 * False hides `download_url`. The stored name stays in the conversation, so
	 * switching the capability back on restores a selection without asking the
	 * user to remember it.
	 */
	downloadEnabled: Boolean = false,
	/** False hides the app's own (keyless) web tools. */
	localSearchEnabled: Boolean = false,
	localFetchEnabled: Boolean = false,
	/**
	 * Media tool names whose backend resolves for this conversation, or
	 * null to keep every media row.
	 *
	 * The sheet passes the resolved set, so a capability the user has not
	 * configured is absent instead of a checkbox that does nothing
	 * (`docs/13-product.md`). Null only happens before the first
	 * resolution, and hiding a configured tool for a frame would be worse
	 * than briefly showing all of them.
	 */
	mediaConfigured: Set<String>? = null,
): ConversationToolPlan {
	val allNonRemote = ToolCatalog.all
		.filterNot { it.remote }
		.mapTo(mutableSetOf()) { it.name }
	// `null` is the historical "all" selection. It has never included remote
	// tools, so expanding it to all non-remote names is the same answer the
	// permission check gives.
	val ceiling = if (agentTools == null) allNonRemote else agentTools.toSet()
	val current = if (conversationTools == null) allNonRemote else conversationTools.toSet()

	// `visible` already removes every remote entry while SSH is off. Applying
	// it last is what keeps a stored `ssh_exec` out of the sheet without
	// dropping it from storage.
	val available = ToolCatalog.visible(
		includeSsh = sshEnabled,
		includeWorkspaceBound = workspaceToolsEnabled,
		includeSandbox = AppCapabilities.sandboxOk == true,
		includeSuggestions = suggestionsEnabled,
		includeBilibili = bilibiliEnabled,
		includeBilibiliLogin = bilibiliLoggedIn,
		includeDownload = downloadEnabled,
		includeLocalSearch = localSearchEnabled,
		includeLocalFetch = localFetchEnabled,
		includePdfVision = true,
		mediaConfigured = mediaConfigured,
	)
		.filter { it.name in ceiling || it.name in current }

	val selected = available
		.filter { ToolCatalog.permits(conversationTools, it.name) }
		.mapTo(linkedSetOf()) { it.name }

	return ConversationToolPlan(available = available, selected = selected)
}

/**
 * Turn the sheet's checkboxes into the stored list.
 *
 * Names the sheet cannot show -- a remote tool while SSH is off -- are carried
 * through untouched. Switching SSH off is not a request to forget the
 * conversation's selection.
 */
internal fun mergeConversationTools(
	current: List<String>?,
	available: List<ToolCatalog.Entry>,
	checked: Set<String>,
): List<String> {
	val visibleNames = available.mapTo(mutableSetOf()) { it.name }
	val hidden = current.orEmpty().filter { it !in visibleNames }
	val merged = (hidden + checked.filter { it in visibleNames })
		.filter { it in ToolCatalog.names }
		.toSet()
	// Catalogue order, not checkbox order: the stored list should not depend on
	// the order someone happened to tap rows.
	return ToolCatalog.names.filter { it in merged }
}

/** Remote tools the selection actually permits. `null` permits none of them. */
internal fun permittedRemoteTools(selection: List<String>?): Set<String> =
	ToolCatalog.all
		.filter { it.remote && ToolCatalog.permits(selection, it.name) }
		.mapTo(mutableSetOf()) { it.name }

/**
 * Whether a change needs the SSH acknowledgement again.
 *
 * Adding a read-only tool to a read-only grant does not widen the dangerous
 * part of the capability. Adding `ssh_exec` or `ssh_upload` does: one can run
 * anything, the other can replace a remote file. Removing tools never needs a
 * new grant.
 */
internal fun widensDangerousSsh(before: List<String>?, after: List<String>): Boolean =
	(permittedRemoteTools(after) - permittedRemoteTools(before)).any {
		it == "ssh_exec" || it == "ssh_upload" || it == "ssh_write_file"
	}
