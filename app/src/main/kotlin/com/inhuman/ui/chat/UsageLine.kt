package com.inhuman.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.inhuman.R

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
fun UsageLine(usage: UsageUi, modifier: Modifier = Modifier, prefix: String? = null) {
	var expanded by remember { mutableStateOf(false) }

	val outLabel = stringResource(R.string.usage_out)
	val inLabel = stringResource(R.string.usage_in)
	val cacheLabel = stringResource(R.string.usage_cache)
	val summary = remember(usage, outLabel, inLabel, cacheLabel, prefix) {
		summaryOf(usage, outLabel, inLabel, cacheLabel)
			.let { line -> if (prefix.isNullOrBlank() || line.isEmpty()) line else "$prefix · $line" }
	}
	if (summary.isEmpty()) return

	Column(modifier = modifier) {
		Text(
			text = if (expanded) "▾ $summary" else "▸ $summary",
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier
				// Same treatment as CollapsibleSection's header: this was ~26dp
				// of touch for one of the most-tapped rows on the screen.
				.minimumInteractiveComponentSize()
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
internal fun summaryOf(
	u: UsageUi,
	outLabel: String = "out",
	inLabel: String = "in",
	cacheLabel: String = "cache",
): String {
	val parts = mutableListOf<String>()

	u.tokensPerSecond?.let { parts += tenths(it) + " tok/s" }
	u.completionTokens?.let { parts += "$it $outLabel" }
	u.promptTokens?.let { parts += "$it $inLabel" }
	// Zero is shown, not omitted: a missing "cache" span read as "the cache
	// feature broke" after a regenerate, when the honest answer was that this
	// request hit nothing. The detail row still spells out the split.
	u.cacheHitRatio?.let { parts += "$cacheLabel %.0f%%".format(it * 100) }

	return parts.joinToString(" · ")
}

/** Everything known, including the parts that are only interesting sometimes. */
@Composable
private fun detailRows(u: UsageUi): List<Pair<String, String>> = buildList {
	u.timeToFirstTokenMs?.let { add(stringResource(R.string.usage_first_token) to formatMs(it)) }
	u.generationMs?.let { add(stringResource(R.string.usage_generating) to formatMs(it)) }
	u.tokensPerSecond?.let { add(stringResource(R.string.usage_speed) to tenths(it) + " tok/s") }
		?: run {
			// Say why the row is absent rather than leaving a gap. Someone who
			// saw a speed on the last reply and not on this one should be able
			// to tell that the answer was simply too short to measure, not that
			// something failed.
			if (u.speedWithheldForShortReply) {
				add(stringResource(R.string.usage_speed) to stringResource(R.string.usage_too_short))
			}
		}
	u.promptTokens?.let { add(stringResource(R.string.usage_prompt_tokens) to it.toString()) }
	u.cachedPromptTokens?.let { cached ->
		val prompt = u.promptTokens
		if (prompt != null && prompt > 0) {
			// Written out rather than as a percentage: the absolute numbers are
			// what a bill is computed from, and the ratio alone does not show
			// whether the cached part was large or the prompt was small.
			add(
				stringResource(R.string.usage_from_cache) to
					stringResource(R.string.usage_cached_of, cached, prompt),
			)
			add(stringResource(R.string.usage_billed_full_rate) to "${prompt - cached}")
		} else {
			add(stringResource(R.string.usage_from_cache) to cached.toString())
		}
	}
	u.completionTokens?.let { add(stringResource(R.string.usage_completion_tokens) to it.toString()) }
	u.reasoningTokens?.takeIf { it > 0 }?.let {
		add(stringResource(R.string.usage_reasoning_tokens) to it.toString())
	}
}

private fun formatMs(ms: Long): String = when {
	ms < 1000 -> "${ms}ms"
	ms < 60_000 -> tenths(ms / 1000.0) + "s"
	else -> "%dm %.0fs".format(ms / 60_000, (ms % 60_000) / 1000.0)
}

/**
 * "12.3" by integer arithmetic rather than `String.format`: on a locale with
 * a comma decimal separator `"%.1f"` renders "12,3", and a technical readout
 * must not change shape with the device locale. Same rule as
 * `TokenFormat.compact` (see its note in `ContextUsage.kt`); the `%.0f`/`%d`
 * sites in this file produce no decimal separator and stay as they are.
 */
private fun tenths(value: Double): String {
	val rounded = kotlin.math.round(value * 10).toLong()
	return "${rounded / 10}.${rounded % 10}"
}
