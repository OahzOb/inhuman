package com.inhuman.ui.common

import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.ai.tools.ToolCatalog

/**
 * How the three tool pickers fold their rows into families.
 *
 * The catalogue has forty-odd entries. Flat, that is a wall of checkboxes on the
 * one screen where a person decides what the agent may do, and the assistant
 * editor carried two such walls -- the tool selection and the per-tool approval
 * rules. The families come from the catalogue's own flags, so a new tool lands
 * in the right group without a second table to maintain, and each family gets a
 * master control: one gesture for "all of these", with the individual names one
 * expansion below.
 *
 * ## Why the SSH family is two families
 *
 * `ssh_exec`, `ssh_upload` and `ssh_write_file` are the irreversible half of the
 * SSH capability (`docs/08-port-philosophy.md` §1.2.1). Burying them in one
 * "SSH" switch would make the read-only profile -- inspect a host, read a file,
 * copy one file down, no shell -- impossible to express in one gesture, and it
 * would let the dangerous half ride on a choice made about a snapshot. [SshRead]
 * and [SshWrite] are separate groups with separate master controls, and turning
 * the write group on still resets the conversation's consent
 * (`widensDangerousSsh`), because the consent text follows the selection.
 *
 * [SshRead] also holds `ssh_hosts`, which is the one SSH-catalogue entry that
 * opens no socket: it lists the machines the user shared, so it belongs with
 * the reading half rather than with a master control that can run commands.
 */
enum class ToolFamily {
	Read,
	Write,
	Web,
	Bilibili,
	Download,
	Media,
	SshRead,
	SshWrite,
	Other,
}

/** The family a catalogue entry is shown under. */
fun toolFamilyOf(entry: ToolCatalog.Entry): ToolFamily = when {
	// Irreversible remote writes first: `reversible` is the catalogue's own
	// statement about whether a replay or the action itself can be undone.
	entry.remote && !entry.reversible -> ToolFamily.SshWrite
	// Everything else remote, plus `ssh_hosts`, which is gated by the SSH
	// switch but does not connect.
	entry.remote || entry.gatedBySsh -> ToolFamily.SshRead
	entry.gatedByBilibili -> ToolFamily.Bilibili
	entry.gatedByDownload -> ToolFamily.Download
	entry.gatedByMediaConfig -> ToolFamily.Media
	entry.name == "web_search" || entry.name == "web_fetch" -> ToolFamily.Web
	entry.workspaceBound -> if (entry.mutates) ToolFamily.Write else ToolFamily.Read
	else -> ToolFamily.Other
}

/** One family's rows, in catalogue order. */
data class ToolGroup(
	val family: ToolFamily,
	val entries: List<ToolCatalog.Entry>,
)

/**
 * The rows grouped by family in display order, empty families omitted.
 *
 * A partition: every entry lands in exactly one group, in its original order.
 */
fun toolGroups(entries: List<ToolCatalog.Entry>): List<ToolGroup> =
	ToolFamily.entries.mapNotNull { family ->
		entries.filter { toolFamilyOf(it) == family }
			.takeIf { it.isNotEmpty() }
			?.let { ToolGroup(family, it) }
	}

/**
 * How many of one family's rows are on.
 *
 * The master control is tri-state: all on (tap turns the family off), none on
 * (tap turns it all on) and mixed (tap turns the missing ones on rather than
 * discarding the selection). [all] is false for an empty family, which no
 * caller passes because empty groups are omitted.
 */
data class GroupSelection(val on: Int, val total: Int) {
	val all: Boolean get() = total > 0 && on == total
	val none: Boolean get() = on == 0
	val mixed: Boolean get() = on > 0 && on < total
}

/** The state of a family against an explicit name set (the conversation sheet). */
fun groupSelection(selected: Set<String>, names: Collection<String>): GroupSelection =
	GroupSelection(on = names.count { it in selected }, total = names.size)

/**
 * The state of a family against a nullable selection (the assistant editor).
 *
 * `null` is the historical "every tool" and [ToolCatalog.permits] reads it the
 * same way everywhere, so the master control cannot disagree with the request.
 */
fun groupSelection(selected: List<String>?, names: Collection<String>): GroupSelection =
	GroupSelection(on = names.count { ToolCatalog.permits(selected, it) }, total = names.size)

/**
 * The stored selection after a family master control is used, for a nullable
 * selection.
 *
 * "All" is `null`, and null cannot express "everything except this family" --
 * the first gesture on a master control materialises the selection through
 * [ToolCatalog.everythingExplicit], which is what was on screen. That list
 * deliberately omits remote and opt-in tools, so turning a family **on** adds
 * its names explicitly: choosing a family is choosing its members, including
 * the opt-in ones. Removing names never needs the materialisation to have
 * gained anything, because null permits all non-opt-in tools already.
 */
fun groupToggle(
	current: List<String>?,
	names: Collection<String>,
	on: Boolean,
	includeSuggestions: Boolean = false,
): List<String> {
	val base = current ?: ToolCatalog.everythingExplicit(includeSuggestions)
	val known = names.filter { it in ToolCatalog.names }
	val next = if (on) {
		(base + known).toSet()
	} else {
		base.toSet() - known.toSet()
	}
	// Catalogue order, not tap order: the same rule `mergeConversationTools`
	// follows, so two stored selections that mean the same thing compare equal.
	return ToolCatalog.names.filter { it in next }
}

/** The stored selection after a family master control, for an explicit set. */
fun groupToggle(checked: Set<String>, names: Collection<String>, on: Boolean): Set<String> =
	if (on) checked + names else checked - names.toSet()

/**
 * The one policy a whole family shares, or null when the family is mixed.
 *
 * A family whose tools disagree has no single answer to show on its row, so the
 * row shows "custom" rather than silently reporting one member's policy as the
 * family's. Tapping a policy still applies it to every member (that is the
 * point of the family control), which is how a mixed family is made uniform.
 */
fun groupPolicy(rules: Map<String, ToolPolicy>, names: Collection<String>): ToolPolicy? {
	if (names.isEmpty()) return null
	val first = rules[names.first()] ?: ToolPolicy.Free
	return if (names.all { (rules[it] ?: ToolPolicy.Free) == first }) first else null
}

/**
 * The rules map after one policy is applied to a whole family.
 *
 * [ToolPolicy.Free] is stored as the absence of a rule -- both repositories
 * treat it that way, and a row that says "no restriction" is not the same thing
 * as no row (`PolicyRepository.setGlobalRule`, `AgentsViewModel.setDraftRule`).
 */
fun withGroupPolicy(
	rules: Map<String, ToolPolicy>,
	names: Collection<String>,
	policy: ToolPolicy,
): Map<String, ToolPolicy> {
	val next = rules.toMutableMap()
	for (name in names) {
		if (policy == ToolPolicy.Free) next.remove(name) else next[name] = policy
	}
	return next
}
