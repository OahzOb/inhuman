package com.inhuman.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
			text = "REMOTE",
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
			text = "ssh_exec runs commands on another machine. The app does not " +
				"configure hosts or keep a credential store; the agent uses what you " +
				"give it. Damage at the remote end can be irreversible. It also " +
				"requires the SSH tool switch in Settings, and the first call in a " +
				"conversation asks for consent.",
			style = MaterialTheme.typography.bodySmall,
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
		)
	}
}
