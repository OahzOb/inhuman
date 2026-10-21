package com.inhuman.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
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
 * The marker for a tool that reaches another machine.
 *
 * A remote command is not a workspace copy: deleting the wrong path there is
 * not undone by deleting the conversation. The badge sits beside the name for
 * the same reason as [PaidBadge] -- a category in the far-right column is read
 * as a label, not as a warning.
 */
@Composable
fun RemoteBadge(modifier: Modifier = Modifier) {
	Surface(
		modifier = modifier,
		color = MaterialTheme.colorScheme.tertiaryContainer,
		contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
		shape = RoundedCornerShape(4.dp),
	) {
		Text(
			text = stringResource(R.string.badge_remote),
			style = MaterialTheme.typography.labelSmall,
			modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
		)
	}
}

/** The warning shown above the tool list, where a chooser will see it. */
@Composable
fun RemoteToolsNotice(modifier: Modifier = Modifier) {
	Surface(
		modifier = modifier.fillMaxWidth(),
		color = MaterialTheme.colorScheme.tertiaryContainer,
		contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
		shape = RoundedCornerShape(8.dp),
	) {
		Text(
			text = stringResource(R.string.notice_remote_tools),
			style = MaterialTheme.typography.bodySmall,
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
		)
	}
}
