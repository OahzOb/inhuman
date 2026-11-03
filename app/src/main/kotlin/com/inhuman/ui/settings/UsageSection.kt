package com.inhuman.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inhuman.R
import com.inhuman.core.design.Eyebrow
import com.inhuman.core.design.Hairline
import com.inhuman.ui.chat.TokenFormat
import com.inhuman.ui.common.FlowRowCompat
import com.inhuman.ui.common.PaidBadge
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date

/**
 * How many rows each breakdown prints before it says what it is leaving out.
 *
 * The section lives inside Settings' single scroll column, not a lazy list, so
 * an "All" window over a year of use would otherwise compose hundreds of rows
 * at once. The cap is disclosed in the section rather than silent.
 */
private const val MAX_DAYS = 14
private const val MAX_CONVERSATIONS = 10

/**
 * The Usage overview.
 *
 * ## Why this is derived, and says so
 *
 * The numbers are folded from the message rows still in the database, not from
 * a running total. That makes them honest about the data they can see: a turn
 * deleted by rewind, and a compaction summary (which has its own table and no
 * message row), were real calls but are not here. The body text says this
 * outright. A number labelled "spend" that silently omits history would be the
 * kind of promise this project treats as a bug.
 *
 * ## Why no prices
 *
 * Providers set prices and change them; a figure computed from a table in this
 * app would be wrong the first time one of them moved. The overview counts
 * tokens and paid calls, which is what the app actually observed.
 */
@Composable
internal fun UsageSection(
	report: UsageReport?,
	period: UsagePeriod,
	failed: Boolean,
	/** True while the deferred read is in flight. */
	loading: Boolean = false,
	initiallyExpanded: Boolean = false,
	visible: Boolean = true,
	onPeriodChange: (UsagePeriod) -> Unit,
	onRetry: () -> Unit,
	/** Load the report the first time the section is opened. */
	onExpand: () -> Unit = {},
) {
	SettingsFold(
		code = stringResource(R.string.settings_section_usage),
		title = stringResource(R.string.settings_usage_title),
		summary = usageSummary(report, failed, loading),
		initiallyExpanded = initiallyExpanded,
		visible = visible,
		onExpand = onExpand,
	) {
		FlowRowCompat {
			UsagePeriod.entries.forEach { candidate ->
				FilterChip(
					selected = candidate == period,
					onClick = { onPeriodChange(candidate) },
					label = { Text(stringResource(candidate.label())) },
				)
			}
		}

		when {
			report != null -> {
				UsageTotalsBlock(report)
				if (failed) UsageFailure(onRetry)
			}
			failed -> UsageFailure(onRetry)
			loading -> Text(
				text = stringResource(R.string.settings_usage_loading),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			else -> Text(
				text = stringResource(R.string.settings_usage_open_hint),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}

		// Last, not first: the numbers are the point; this paragraph says what
		// they do not include, which is worth reading after seeing them.
		Text(
			text = stringResource(R.string.settings_usage_body),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun usageSummary(report: UsageReport?, failed: Boolean, loading: Boolean): String = when {
	failed -> stringResource(R.string.settings_usage_failed)
	report != null && report.isEmpty -> stringResource(R.string.settings_usage_empty_summary)
	report != null -> stringResource(
		R.string.settings_usage_summary,
		tokens(report.totals.promptTokens),
		tokens(report.totals.completionTokens),
		pluralStringResource(
			R.plurals.settings_usage_paid_calls,
			report.totals.paidCalls,
			report.totals.paidCalls,
		),
	)
	loading -> stringResource(R.string.settings_usage_loading)
	// Not read yet: the section is opened to load it, so say that rather than
	// claiming a read that has not started.
	else -> stringResource(R.string.settings_usage_open_hint)
}

@Composable
private fun UsageFailure(onRetry: () -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = stringResource(R.string.settings_usage_failed),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.error,
			modifier = Modifier.weight(1f),
		)
		TextButton(onClick = onRetry) {
			Text(stringResource(R.string.settings_usage_retry))
		}
	}
}

@Composable
private fun UsageTotalsBlock(report: UsageReport) {
	if (report.isEmpty) {
		Text(
			text = stringResource(R.string.settings_usage_empty),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		return
	}

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		UsageStat(
			label = stringResource(R.string.settings_usage_in),
			value = tokens(report.totals.promptTokens),
			modifier = Modifier.weight(1f),
		)
		UsageStat(
			label = stringResource(R.string.settings_usage_out),
			value = tokens(report.totals.completionTokens),
			modifier = Modifier.weight(1f),
		)
	}
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		UsageStat(
			label = stringResource(R.string.settings_usage_cached),
			value = tokens(report.totals.cachedPromptTokens),
			modifier = Modifier.weight(1f),
		)
		UsageStat(
			label = stringResource(R.string.settings_usage_reasoning),
			value = tokens(report.totals.reasoningTokens),
			modifier = Modifier.weight(1f),
		)
	}

	PaidCallsBlock(report)
	DayBreakdown(report.days)
	ConversationBreakdown(report.conversations)
}

@Composable
private fun UsageStat(label: String, value: String, modifier: Modifier = Modifier) {
	Column(
		modifier = modifier,
		verticalArrangement = Arrangement.spacedBy(2.dp),
	) {
		Eyebrow(label)
		Text(
			text = value,
			style = MaterialTheme.typography.titleMedium,
			fontFamily = FontFamily.Monospace,
		)
	}
}

@Composable
private fun PaidCallsBlock(report: UsageReport) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Eyebrow(stringResource(R.string.settings_usage_paid))
		Spacer(Modifier.width(8.dp))
		PaidBadge()
		Spacer(Modifier.weight(1f))
		Text(
			text = pluralStringResource(
				R.plurals.settings_usage_paid_calls,
				report.totals.paidCalls,
				report.totals.paidCalls,
			),
			style = MaterialTheme.typography.titleMedium,
			fontFamily = FontFamily.Monospace,
		)
	}

	if (report.paidTools.isEmpty()) {
		return
	}

	report.paidTools.forEach { tool ->
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(vertical = 3.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = tool.name,
				style = MaterialTheme.typography.bodySmall,
				fontFamily = FontFamily.Monospace,
				modifier = Modifier.weight(1f),
			)
			Text(
				text = tool.calls.toString(),
				style = MaterialTheme.typography.bodySmall,
				fontFamily = FontFamily.Monospace,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun DayBreakdown(days: List<UsageDay>) {
	if (days.isEmpty()) return
	Eyebrow(stringResource(R.string.settings_usage_by_day))
	days.take(MAX_DAYS).forEach { day ->
		val date = remember(day.date) {
			DateFormat.getDateInstance(DateFormat.MEDIUM).format(
				Date(day.date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()),
			)
		}
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(vertical = 3.dp),
			verticalArrangement = Arrangement.spacedBy(1.dp),
		) {
			Text(
				text = date,
				style = MaterialTheme.typography.labelLarge,
				fontFamily = FontFamily.Monospace,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Text(text = statsLine(day.totals), style = MaterialTheme.typography.bodySmall)
		}
		Hairline()
	}
	if (days.size > MAX_DAYS) {
		Text(
			text = stringResource(R.string.settings_usage_days_capped, MAX_DAYS),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun ConversationBreakdown(conversations: List<UsageConversation>) {
	if (conversations.isEmpty()) return
	Eyebrow(stringResource(R.string.settings_usage_by_conversation))
	conversations.take(MAX_CONVERSATIONS).forEach { conversation ->
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(vertical = 3.dp),
			verticalArrangement = Arrangement.spacedBy(1.dp),
		) {
			Text(
				text = conversation.title.ifBlank {
					stringResource(R.string.settings_usage_untitled)
				},
				style = MaterialTheme.typography.titleSmall,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = statsLine(conversation.totals),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Hairline()
	}
	if (conversations.size > MAX_CONVERSATIONS) {
		Text(
			text = stringResource(R.string.settings_usage_conversations_capped, MAX_CONVERSATIONS),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

/**
 * "In 1,234 · Out 567 · 2 paid calls", with labels from the resource file.
 *
 * Built from already-localized parts rather than a format string with three
 * substitutes, because a field the provider never reported should be absent,
 * not rendered as a zero.
 */
@Composable
private fun statsLine(totals: UsageTotals): String {
	val parts = ArrayList<String>(3)
	totals.promptTokens?.let {
		parts += stringResource(R.string.settings_usage_in_value, TokenFormat.exact(it))
	}
	totals.completionTokens?.let {
		parts += stringResource(R.string.settings_usage_out_value, TokenFormat.exact(it))
	}
	if (totals.paidCalls > 0) {
		parts += pluralStringResource(
			R.plurals.settings_usage_paid_calls,
			totals.paidCalls,
			totals.paidCalls,
		)
	}
	return parts.joinToString(" · ")
}

private fun tokens(value: Long?): String = value?.let(TokenFormat::exact) ?: "—"

private fun UsagePeriod.label(): Int = when (this) {
	UsagePeriod.Today -> R.string.settings_usage_period_today
	UsagePeriod.SevenDays -> R.string.settings_usage_period_week
	UsagePeriod.ThirtyDays -> R.string.settings_usage_period_month
	UsagePeriod.All -> R.string.settings_usage_period_all
}