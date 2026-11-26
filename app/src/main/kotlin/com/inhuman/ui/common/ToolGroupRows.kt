package com.inhuman.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.inhuman.R
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.design.Layout

/**
 * The localized name of a family.
 *
 * One function for all three pickers, so a name cannot drift between the screen
 * that offers a tool and the screen that decides whether it asks first.
 */
@Composable
fun toolFamilyTitle(family: ToolFamily): String = stringResource(
	when (family) {
		ToolFamily.Read -> R.string.chat_tools_group_read
		ToolFamily.Write -> R.string.chat_tools_group_write
		ToolFamily.Web -> R.string.chat_tools_group_web
		ToolFamily.Bilibili -> R.string.chat_tools_group_bilibili
		ToolFamily.Download -> R.string.chat_tools_group_download
		ToolFamily.Media -> R.string.chat_tools_group_media
		ToolFamily.SshRead -> R.string.chat_tools_group_ssh_read
		ToolFamily.SshWrite -> R.string.chat_tools_group_ssh_write
		ToolFamily.Other -> R.string.chat_tools_group_other
	},
)

/**
 * One family's master control, for a surface that selects tools.
 *
 * The checkbox is the family: all on, none on, or mixed (tap turns the missing
 * ones on rather than throwing away the rest). The rest of the row expands the
 * individual names, so the forty-odd checkboxes that used to be the only view
 * are still there -- one tap below a row that answers "is anything in here on"
 * at a glance.
 *
 * Aggregate badges sit on the row because collapsing a family must not hide a
 * cost: a family containing a paid or remote tool keeps its badge visible even
 * while its members are folded away.
 */
@Composable
fun ToolGroupMasterRow(
	family: ToolFamily,
	entries: List<ToolCatalog.Entry>,
	selection: GroupSelection,
	expanded: Boolean,
	onToggleAll: (Boolean) -> Unit,
	onToggleExpanded: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val title = toolFamilyTitle(family)
	val groupDescription = stringResource(R.string.tools_group_master, title)
	Row(
		modifier = modifier
			.fillMaxWidth()
			.minimumInteractiveComponentSize(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
	) {
		TriStateCheckbox(
			state = when {
				selection.all -> ToggleableState.On
				selection.none -> ToggleableState.Off
				else -> ToggleableState.Indeterminate
			},
			// The target state is the opposite of "all on", not the checkbox's
			// own next value: a mixed family becomes fully on, so the gesture
			// completes the selection instead of discarding it.
			onClick = { onToggleAll(!selection.all) },
			modifier = Modifier.semantics { contentDescription = groupDescription },
		)
		Row(
			modifier = Modifier
				.weight(1f)
				.clickable { onToggleExpanded() }
				.padding(vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
		) {
			Icon(
				imageVector = if (expanded) {
					Icons.Filled.KeyboardArrowDown
				} else {
					// Auto-mirrored: a disclosure triangle is directional.
					Icons.AutoMirrored.Filled.KeyboardArrowRight
				},
				contentDescription = stringResource(
					if (expanded) R.string.chat_collapse_section else R.string.chat_expand_section,
				),
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(16.dp),
			)
			Text(
				text = title,
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.onBackground,
				modifier = Modifier.weight(1f),
			)
			// Aggregate disclosure: folded away is not the same as not there.
			if (entries.any { it.paid }) PaidBadge()
			if (entries.any { it.remote }) RemoteBadge()
			Text(
				text = stringResource(R.string.tools_group_count, selection.on, selection.total),
				style = MaterialTheme.typography.labelLarge,
				color = if (selection.all) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
			)
		}
	}
}

/**
 * One family's rule control, for the two screens that decide what runs without
 * asking.
 *
 * [policy] is null when the family's tools disagree; the row then selects no
 * chip and says so, rather than presenting one member's rule as the family's.
 * Selecting a chip applies it to every member -- the family control cannot
 * express "these two differ", only "make them uniform", and per-tool exceptions
 * are one expansion below.
 *
 * [assistant] switches the word for [ToolPolicy.Free]: at assistant level the
 * same setting means "follow the global rule", while the global editor's word
 * is "run it".
 */
@Composable
fun ToolGroupPolicyRow(
	family: ToolFamily,
	entries: List<ToolCatalog.Entry>,
	policy: ToolPolicy?,
	customised: Int,
	assistant: Boolean,
	expanded: Boolean,
	onSelect: (ToolPolicy) -> Unit,
	onToggleExpanded: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val title = toolFamilyTitle(family)
	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(vertical = 2.dp),
		verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.minimumInteractiveComponentSize()
				.clickable { onToggleExpanded() },
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
		) {
			Icon(
				imageVector = if (expanded) {
					Icons.Filled.KeyboardArrowDown
				} else {
					Icons.AutoMirrored.Filled.KeyboardArrowRight
				},
				contentDescription = stringResource(
					if (expanded) R.string.chat_collapse_section else R.string.chat_expand_section,
				),
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(16.dp),
			)
			Row(
				modifier = Modifier.weight(1f),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
			) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onBackground,
				)
				if (entries.any { it.paid }) PaidBadge()
				if (entries.any { it.remote }) RemoteBadge()
				if (customised > 0) {
					Text(
						text = stringResource(R.string.tools_group_customised, customised),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
		ToolPolicyChoiceRow(policy = policy, assistant = assistant, onSelect = onSelect)
		if (policy == null) {
			Text(
				text = stringResource(R.string.tools_group_mixed),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/** The four policy chips, with no chip selected when the state is mixed. */
@Composable
fun ToolPolicyChoiceRow(
	policy: ToolPolicy?,
	assistant: Boolean,
	onSelect: (ToolPolicy) -> Unit,
) {
	FlowRowCompat {
		for (option in ToolPolicy.entries) {
			FilterChip(
				selected = option == policy,
				onClick = { onSelect(option) },
				label = { Text(toolPolicyLabel(option, assistant)) },
			)
		}
	}
}

/**
 * The word a user reads for a policy.
 *
 * Two vocabularies exist because the same [ToolPolicy.Free] means different
 * things at the two levels: the global editor's Free is "run it", the assistant
 * editor's is "follow settings". Everything else says the same word.
 */
@Composable
fun toolPolicyLabel(policy: ToolPolicy, assistant: Boolean): String = when (policy) {
	ToolPolicy.Free -> stringResource(
		if (assistant) R.string.agents_policy_follow else R.string.settings_policy_free,
	)
	ToolPolicy.Ask -> stringResource(R.string.settings_policy_ask)
	ToolPolicy.Deny -> stringResource(R.string.settings_policy_deny)
	ToolPolicy.Blind -> stringResource(R.string.settings_policy_blind)
}
