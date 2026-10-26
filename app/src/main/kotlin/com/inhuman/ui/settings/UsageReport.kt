package com.inhuman.ui.settings

import com.inhuman.core.data.db.UsageRow
import com.inhuman.ui.chat.ChatViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The window the Usage section is showing. */
enum class UsagePeriod {
	Today,
	SevenDays,
	ThirtyDays,
	All,
}

/**
 * Counts for one slice of the overview.
 *
 * Token fields stay nullable instead of starting at zero. `null` means "the
 * provider never reported this field", which is not the same answer as "it
 * reported zero": the UI shows a dash for the former, and a provider that
 * omits cache accounting must not look like one whose cache never hit.
 *
 * [cachedPromptTokens] is a subset of [promptTokens] and [reasoningTokens] a
 * subset of [completionTokens]. They are displayed beside the totals rather
 * than added to them, because adding them would count the same tokens twice.
 */
data class UsageTotals(
	val promptTokens: Long? = null,
	val completionTokens: Long? = null,
	val cachedPromptTokens: Long? = null,
	val reasoningTokens: Long? = null,
	val paidCalls: Int = 0,
) {
	val hasTokens: Boolean
		get() = promptTokens != null || completionTokens != null ||
			cachedPromptTokens != null || reasoningTokens != null

	val hasAny: Boolean get() = hasTokens || paidCalls > 0

	/**
	 * What the conversation list sorts on. Cached and reasoning tokens are
	 * deliberately excluded; they are already inside the two figures added here.
	 */
	val totalTokens: Long get() = (promptTokens ?: 0L) + (completionTokens ?: 0L)

	operator fun plus(other: UsageTotals): UsageTotals = UsageTotals(
		promptTokens = add(promptTokens, other.promptTokens),
		completionTokens = add(completionTokens, other.completionTokens),
		cachedPromptTokens = add(cachedPromptTokens, other.cachedPromptTokens),
		reasoningTokens = add(reasoningTokens, other.reasoningTokens),
		paidCalls = paidCalls + other.paidCalls,
	)

	private fun add(left: Long?, right: Long?): Long? = when {
		left == null -> right
		right == null -> left
		else -> left + right
	}
}

data class UsageDay(val date: LocalDate, val totals: UsageTotals)

data class UsageConversation(
	val conversationId: String,
	val title: String,
	val totals: UsageTotals,
)

data class PaidToolUsage(val name: String, val calls: Int)

/**
 * What the Usage section renders.
 *
 * [days] and [conversations] contain only entries with something to count, so
 * an empty list is the same statement as [isEmpty], and the screen does not
 * have to print a row of zeroes for every quiet day in the window.
 */
data class UsageReport(
	val period: UsagePeriod,
	val totals: UsageTotals,
	val days: List<UsageDay>,
	val conversations: List<UsageConversation>,
	val paidTools: List<PaidToolUsage>,
) {
	val isEmpty: Boolean get() = !totals.hasAny
}

/**
 * The markers the app writes into a tool result for a call it never ran.
 *
 * Kept as constants in [ChatViewModel] because that is where the transcript
 * writes them; this file only needs to recognise them. Counting a declined or
 * interrupted paid call as a paid call would overstate what the third party was
 * asked to do, and the whole point of the overview is that its numbers are
 * explainable.
 */
private val UNRUN_RESULT_PREFIXES = listOf(
	ChatViewModel.DECLINED_MARKER,
	ChatViewModel.SKIPPED_MARKER,
	ChatViewModel.REFUSED_MARKER,
	ChatViewModel.INTERRUPTED_MARKER,
)

/**
 * Fold [rows] into the overview.
 *
 * [paidToolNames] comes from `ToolCatalog` rather than from this module, so
 * "paid" has one definition. A tool that is not in the set is still a tool
 * row; it simply contributes no paid count.
 *
 * Days are local calendar days in [zone], not rolling 24-hour windows. "7 days"
 * includes today and the six before it, which is what a person checking "this
 * week" means; a timestamp at 00:05 belongs to the new day even though it is
 * only minutes older than one at 23:55.
 */
fun buildUsageReport(
	rows: List<UsageRow>,
	paidToolNames: Set<String>,
	period: UsagePeriod,
	now: Instant = Instant.now(),
	zone: ZoneId = ZoneId.systemDefault(),
	unrunPrefixes: List<String> = UNRUN_RESULT_PREFIXES,
): UsageReport {
	val today = now.atZone(zone).toLocalDate()
	val firstDay = when (period) {
		UsagePeriod.Today -> today
		UsagePeriod.SevenDays -> today.minusDays(6)
		UsagePeriod.ThirtyDays -> today.minusDays(29)
		UsagePeriod.All -> null
	}

	var total = UsageTotals()
	val dayTotals = HashMap<LocalDate, UsageTotals>()
	val conversationTotals = HashMap<String, UsageTotals>()
	val conversationTitles = HashMap<String, String>()
	val toolCalls = HashMap<String, Int>()

	for (row in rows) {
		val date = Instant.ofEpochMilli(row.createdAt).atZone(zone).toLocalDate()
		if (firstDay != null && date.isBefore(firstDay)) continue

		var delta = UsageTotals(
			promptTokens = row.promptTokens?.toLong(),
			completionTokens = row.completionTokens?.toLong(),
			cachedPromptTokens = row.cachedPromptTokens?.toLong(),
			reasoningTokens = row.reasoningTokens?.toLong(),
		)

		val toolName = row.toolName
		if (toolName != null && toolName in paidToolNames &&
			unrunPrefixes.none { row.resultPrefix.startsWith(it) }
		) {
			delta = delta.copy(paidCalls = delta.paidCalls + 1)
			toolCalls[toolName] = (toolCalls[toolName] ?: 0) + 1
		}

		if (!delta.hasAny) continue

		total += delta
		dayTotals[date] = (dayTotals[date] ?: UsageTotals()) + delta
		conversationTotals[row.conversationId] =
			(conversationTotals[row.conversationId] ?: UsageTotals()) + delta
		conversationTitles[row.conversationId] = row.conversationTitle
	}

	val days = dayTotals
		.map { (date, totals) -> UsageDay(date, totals) }
		.sortedByDescending { it.date }

	val conversations = conversationTotals
		.map { (id, totals) ->
			UsageConversation(
				conversationId = id,
				title = conversationTitles[id].orEmpty(),
				totals = totals,
			)
		}
		.sortedWith(
			compareByDescending<UsageConversation> { it.totals.totalTokens }
				.thenByDescending { it.totals.paidCalls }
				.thenBy { it.title.lowercase() },
		)

	val paidTools = toolCalls
		.map { (name, calls) -> PaidToolUsage(name, calls) }
		.sortedWith(compareByDescending<PaidToolUsage> { it.calls }.thenBy { it.name })

	return UsageReport(
		period = period,
		totals = total,
		days = days,
		conversations = conversations,
		paidTools = paidTools,
	)
}