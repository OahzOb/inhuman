package com.inhuman.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.inhuman.R
import com.inhuman.ui.common.FlowRowCompat

/**
 * How full the context window is, as a ring.
 *
 * ## Why a ring and a short percentage
 *
 * The question this answers is "how much room is left", and that is a question
 * about a proportion, which a shape answers at a glance. The percentage beside
 * it is the exact-at-a-glance form for someone who watches the number; the ring
 * still carries the same fact when the label is skimmed past. Both are compact
 * and quiet at a few percent.
 *
 * ## Why it is tappable
 *
 * A ring cannot show 412,338 and should not try. The exact figures -- used,
 * total, remaining, and the percentage those make -- are one tap away, which is
 * where someone who is actually worried about running out will look. The panel
 * also carries the two cases the ring cannot draw at all: no window reported,
 * and a window with nothing measured yet.
 *
 * ## Why the whole thing collapses to nothing when there is nothing to say
 *
 * A provider that reports neither a window nor usage leaves this returning
 * without drawing. An empty ring would say "0% used", which is a claim, and a
 * false one.
 */
@Composable
fun ContextGauge(
	usage: ContextUsage,
	expanded: Boolean,
	onToggle: () -> Unit,
	compaction: ContextCompactionUi? = null,
	isCompacting: Boolean = false,
	onCompact: (() -> Unit)? = null,
	onViewCompaction: () -> Unit = {},
	onClearCompaction: () -> Unit = {},
	modifier: Modifier = Modifier,
) {
	if (!usage.isKnown) return
	val gaugeDescription = describe(usage)

	val track = MaterialTheme.colorScheme.outlineVariant
	// Warning colour from 90%: by the time it matters, a gauge that has to be
	// read carefully has already failed at its one job.
	val arc = if ((usage.fraction ?: 0f) >= 0.9f) {
		MaterialTheme.colorScheme.error
	} else {
		MaterialTheme.colorScheme.tertiary
	}

	Column(modifier = modifier.fillMaxWidth()) {
		if (expanded) {
			ContextDetail(
				usage = usage,
				compaction = compaction,
				isCompacting = isCompacting,
				onCompact = onCompact,
				onViewCompaction = onViewCompaction,
				onClearCompaction = onClearCompaction,
			)
			Spacer(Modifier.size(4.dp))
		}

		// The clickable area is the ring and its label, not the whole strip.
		// It used to be `fillMaxWidth().clickable()`, which made an invisible
		// full-width band open the panel on any tap near the composer's top
		// edge, and it was only 28dp tall. The outer row lays it out to the
		// end; the inner row is the 48dp target.
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.End,
		) {
			Row(
				modifier = Modifier
					.clip(RoundedCornerShape(8.dp))
					.clickable(onClick = onToggle)
					.heightIn(min = 48.dp)
					.padding(horizontal = 6.dp, vertical = 4.dp)
					.semantics { contentDescription = gaugeDescription },
				verticalAlignment = Alignment.CenterVertically,
			) {
				usage.summary?.let { summary ->
					Text(
						text = summary,
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Spacer(Modifier.size(6.dp))
				}
				Ring(fraction = usage.fraction, arc = arc, track = track)
			}
		}
	}
}

/**
 * The arc itself.
 *
 * Twelve o'clock start, clockwise, and the track is always drawn in full: a
 * ring with nothing in it still has to say how much there is to fill, or an
 * empty gauge and a missing gauge look the same.
 */
@Composable
private fun Ring(fraction: Float?, arc: Color, track: Color) {
	Canvas(modifier = Modifier.size(RING_SIZE)) {
		val stroke = Stroke(width = RING_STROKE.toPx(), cap = StrokeCap.Round)
		// Inset by half the stroke, or the arc is clipped by its own bounds on
		// every side and reads as a broken circle rather than a thin one.
		val inset = RING_STROKE.toPx() / 2f
		val diameter = size.minDimension - inset * 2

		drawArc(
			color = track,
			startAngle = 0f,
			sweepAngle = 360f,
			useCenter = false,
			topLeft = Offset(inset, inset),
			size = Size(diameter, diameter),
			style = stroke,
		)

		if (fraction != null && fraction > 0f) {
			drawArc(
				color = arc,
				startAngle = -90f,
				sweepAngle = 360f * fraction,
				useCenter = false,
				topLeft = Offset(inset, inset),
				size = Size(diameter, diameter),
				style = stroke,
			)
		}
	}
}

/**
 * The figures, for someone who tapped the ring.
 *
 * Every number here is either measured by the provider or absent. Nothing is
 * estimated, because an estimate in this panel would be indistinguishable from
 * the real thing and would be the number people quote.
 */
@Composable
private fun ContextDetail(
	usage: ContextUsage,
	compaction: ContextCompactionUi?,
	isCompacting: Boolean,
	onCompact: (() -> Unit)?,
	onViewCompaction: () -> Unit,
	onClearCompaction: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 6.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(2.dp),
		horizontalAlignment = Alignment.End,
	) {
		val percent = usage.percent
		val used = usage.usedTokens
		val window = usage.contextWindow

		when {
			window == null -> {
				// No denominator, so no percentage is offered at all rather
				// than one computed against a guess.
				Detail(stringResource(R.string.context_gauge_no_window))
				used?.let {
					Detail(stringResource(R.string.context_gauge_last_request, TokenFormat.exact(it)))
				}
			}

			used == null -> {
				Detail(stringResource(R.string.context_gauge_window_only, TokenFormat.exact(window.toLong())))
				Detail(stringResource(R.string.context_gauge_nothing_measured))
			}

			else -> {
				Detail(stringResource(R.string.context_gauge_percent, percent ?: 0))
				Detail(
					stringResource(
						R.string.context_gauge_used_of,
						TokenFormat.exact(used),
						TokenFormat.exact(window.toLong()),
					),
				)
				usage.remainingTokens?.let {
					Detail(stringResource(R.string.context_gauge_left, TokenFormat.exact(it)))
				}
			}
		}

		// Said out loud, because the number is a measurement of the previous
		// request and someone watching it during a long turn will otherwise
		// read the lag as a bug.
		Detail(stringResource(R.string.context_gauge_measured_from_last), faint = true)

		if (isCompacting) {
			Detail(stringResource(R.string.context_gauge_summarizing))
		} else if (compaction != null) {
			Detail(stringResource(R.string.context_gauge_summarized, compaction.omittedMessages))
			FlowRowCompat {
				onCompact?.let {
					TextButton(
						onClick = onCompact,
						contentPadding = androidx.compose.foundation.layout.PaddingValues(
							horizontal = 6.dp,
						),
					) {
						Text(stringResource(R.string.context_gauge_compact_again), style = MaterialTheme.typography.labelLarge)
					}
				}
				TextButton(
					onClick = onViewCompaction,
					contentPadding = androidx.compose.foundation.layout.PaddingValues(
						horizontal = 6.dp,
					),
				) {
					Text(stringResource(R.string.context_gauge_view_summary), style = MaterialTheme.typography.labelLarge)
				}
				TextButton(
					onClick = onClearCompaction,
					contentPadding = androidx.compose.foundation.layout.PaddingValues(
						horizontal = 6.dp,
					),
				) {
					Text(stringResource(R.string.context_gauge_use_full_history), style = MaterialTheme.typography.labelLarge)
				}
			}
		} else if (onCompact != null) {
			TextButton(
				onClick = onCompact,
				contentPadding = androidx.compose.foundation.layout.PaddingValues(
					horizontal = 6.dp,
				),
			) {
				Text(stringResource(R.string.context_gauge_compact_conversation), style = MaterialTheme.typography.labelLarge)
			}
		}
	}
}

@Composable
private fun Detail(text: String, faint: Boolean = false) {
	Text(
		text = text,
		style = MaterialTheme.typography.labelMedium,
		// `faint` used to pick the same colour on both branches, so the one
		// line that exists to say "this is a caveat" read like every other.
		color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
			alpha = if (faint) 0.9f else 1f,
		),
	)
}

/** The ring's announcement, since a shape has no text for a screen reader. */
@Composable
private fun describe(usage: ContextUsage): String {
	val percent = usage.percent
	return when {
		percent != null -> stringResource(
			R.string.context_gauge_desc_percent,
			percent,
			usage.summary.orEmpty(),
		)
		usage.contextWindow != null -> stringResource(
			R.string.context_gauge_desc_window,
			TokenFormat.exact(usage.contextWindow.toLong()),
		)
		else -> stringResource(R.string.context_gauge_desc_unknown)
	}
}

private val RING_SIZE = 20.dp
private val RING_STROKE = 2.5.dp
