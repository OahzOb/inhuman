package com.inhuman.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inhuman.R

/**
 * Whether a turn that uses this tool can be rewound, stated next to the name.
 *
 * The name alone is not enough: `ssh_exec` says what it runs, not that its
 * effects are permanent. This badge sits in the same row as PAID and REMOTE so
 * the consequence is visible before the description is opened.
 *
 * Only mutating tools carry it. A read-only call leaves nothing to rewind.
 */
@Composable
fun RewindBadge(canRewind: Boolean, modifier: Modifier = Modifier) {
	Surface(
		modifier = modifier,
		color = if (canRewind) {
			MaterialTheme.colorScheme.secondaryContainer
		} else {
			MaterialTheme.colorScheme.errorContainer
		},
		contentColor = if (canRewind) {
			MaterialTheme.colorScheme.onSecondaryContainer
		} else {
			MaterialTheme.colorScheme.onErrorContainer
		},
		shape = RoundedCornerShape(4.dp),
	) {
		Text(
			text = if (canRewind) {
				stringResource(R.string.badge_rewind)
			} else {
				stringResource(R.string.badge_no_rewind)
			},
			style = MaterialTheme.typography.labelSmall,
			modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
		)
	}
}
