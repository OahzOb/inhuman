package com.inhuman.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * What a turn cost, in as much detail as the provider supplied.
 *
 * ## Why it is collapsed by default and explicit when opened
 *
 * The one-line summary answers the question people actually ask -- how fast was
 * that, and did the cache help -- without turning every reply into a dashboard.
 * Tapping opens the full breakdown, which is where the numbers that matter only
 * when something looks wrong live: time to first token separately from
 * generation, and the cache split written out rather than as a percentage.
 *
 * ## Why timing is split in two
 *
 * Time-to-first-token and generation speed are different properties and a
 * single "took 6s" hides which one is bad. A reasoning model that thinks for
 * five seconds and then writes instantly is not slow; one that starts instantly
 * and dribbles is. Reporting them together makes both look the same, and there
 * is nothing to act on.
 */
@Composable
fun UsageLine(usage: UsageUi, modifier: Modifier = Modifier) {
	var expanded by remember { mutableStateOf(false) }

	val summary = remember(usage) { summaryOf(usage) }
	if (summary.isEmpty()) return

	Column(modifier = modifier) {
		Text(
			text = if (expanded) "▾ $summary" else "▸ $summary",
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier
				.clickable { expanded = !expanded }
				.padding(vertical = 2.dp),
		)

		if (expanded) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 4.dp)
					.background(
						MaterialTheme.colorScheme.surfaceVariant,
						RoundedCornerShape(8.dp),
					)
					.padding(horizontal = 10.dp, vertical = 8.dp),
				verticalArrangement = Arrangement.spacedBy(3.dp),
			) {
				detailRows(usage).forEach { (label, value) ->
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Text(
							text = label,
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						Text(
							text = value,
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurface,
							fontFamily = FontFamily.Monospace,
						)
					}
				}
			}
		}
	}
}

/**
 * The one line worth showing without asking.
 *
 * Order is deliberate: speed first, because it is what someone notices; then
 * the cache, because it is what surprises them on a bill; then tokens.
 */
private fun summaryOf(u: UsageUi): String {
	val parts = mutableListOf<String>()

	u.tokensPerSecond?.let { parts += "%.1f tok/s".format(it) }
	u.completionTokens?.let { parts += "$it out" }
	u.promptTokens?.let { parts += "$it in" }
	u.cacheHitRatio?.takeIf { it > 0 }?.let { parts += "cache %.0f%%".format(it * 100) }

	return parts.joinToString(" · ")
}

/** Everything known, including the parts that are only interesting sometimes. */
private fun detailRows(u: UsageUi): List<Pair<String, String>> = buildList {
	u.timeToFirstTokenMs?.let { add("first token" to formatMs(it)) }
	u.generationMs?.let { add("generating" to formatMs(it)) }
	u.tokensPerSecond?.let { add("speed" to "%.1f tok/s".format(it)) }
		?: run {
			// Say why the row is absent rather than leaving a gap. Someone who
			// saw a speed on the last reply and not on this one should be able
			// to tell that the answer was simply too short to measure, not that
			// something failed.
			if (u.speedWithheldForShortReply) {
				add("speed" to "too short to measure")
			}
		}
	u.promptTokens?.let { add("prompt tokens" to it.toString()) }
	u.cachedPromptTokens?.let { cached ->
		val prompt = u.promptTokens
		if (prompt != null && prompt > 0) {
			// Written out rather than as a percentage: the absolute numbers are
			// what a bill is computed from, and the ratio alone does not show
			// whether the cached part was large or the prompt was small.
			add("from cache" to "$cached of $prompt")
			add("billed at full rate" to "${prompt - cached}")
		} else {
			add("from cache" to cached.toString())
		}
	}
	u.completionTokens?.let { add("completion tokens" to it.toString()) }
	u.reasoningTokens?.takeIf { it > 0 }?.let { add("reasoning tokens" to it.toString()) }
}

private fun formatMs(ms: Long): String = when {
	ms < 1000 -> "${ms}ms"
	ms < 60_000 -> "%.1fs".format(ms / 1000.0)
	else -> "%dm %.0fs".format(ms / 60_000, (ms % 60_000) / 1000.0)
}
