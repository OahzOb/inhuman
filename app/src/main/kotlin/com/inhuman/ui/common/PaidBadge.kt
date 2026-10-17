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
 * The one marker that says a tool spends money at DeepSeek.
 *
 * ## Why a filled badge next to the name
 *
 * It was a plain word in the far-right column, the same weight as "writes". That
 * column is where a reader looks for a category, not a cost, and a word that
 * small is easy to miss while scanning tool names. This is deliberately louder:
 * it sits beside the name, uses a filled error-container surface, and says
 * `PAID` in caps. The price is in [PaidToolsNotice]; the badge is the part that
 * survives a glance.
 */
@Composable
fun PaidBadge(modifier: Modifier = Modifier) {
	Surface(
		modifier = modifier,
		color = MaterialTheme.colorScheme.errorContainer,
		contentColor = MaterialTheme.colorScheme.onErrorContainer,
		shape = RoundedCornerShape(4.dp),
	) {
		Text(
			text = "PAID",
			style = MaterialTheme.typography.labelSmall,
			modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
		)
	}
}

/**
 * The price, stated once where tools are chosen or reviewed.
 *
 * A user who never opens a description still sees this above the list, and the
 * per-row badge above still marks which entries it applies to.
 */
@Composable
fun PaidToolsNotice(modifier: Modifier = Modifier) {
	Surface(
		modifier = modifier.fillMaxWidth(),
		color = MaterialTheme.colorScheme.errorContainer,
		contentColor = MaterialTheme.colorScheme.onErrorContainer,
		shape = RoundedCornerShape(8.dp),
	) {
		Text(
			text = "web_search and web_fetch are DeepSeek's paid APIs: " +
				"¥5 per 1,000 successful calls, billed to your DeepSeek account. " +
				"Everything else runs on this device.",
			style = MaterialTheme.typography.bodySmall,
			modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
		)
	}
}
