package com.inhuman.ui.settings

import com.inhuman.core.data.db.UsageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class UsageReportTest {

	private val zone = ZoneId.of("Asia/Shanghai")
	private val now = Instant.parse("2026-10-26T12:00:00Z") // 20:00 in Shanghai.

	private val paid = setOf("web_search", "generate_image")

	@Test
	fun `cached and reasoning tokens are reported beside the totals, not added twice`() {
		val report = buildUsageReport(
			rows = listOf(
				row(
					conversation = "a",
					at = at("2026-10-26"),
					prompt = 100,
					completion = 20,
					cached = 40,
					reasoning = 5,
				),
				row(conversation = "a", at = at("2026-10-25"), prompt = 50, completion = 10),
			),
			paidToolNames = paid,
			period = UsagePeriod.SevenDays,
			now = now,
			zone = zone,
		)

		assertEquals(150L, report.totals.promptTokens)
		assertEquals(30L, report.totals.completionTokens)
		assertEquals(40L, report.totals.cachedPromptTokens)
		assertEquals(5L, report.totals.reasoningTokens)
		assertEquals(180L, report.totals.totalTokens)
	}

	@Test
	fun `seven days means today and the six before it`() {
		val report = buildUsageReport(
			rows = listOf(
				row("a", at("2026-10-26"), prompt = 1),
				row("a", at("2026-10-20"), prompt = 2),
				row("a", at("2026-10-19"), prompt = 4),
			),
			paidToolNames = paid,
			period = UsagePeriod.SevenDays,
			now = now,
			zone = zone,
		)

		assertEquals(listOf("2026-10-26", "2026-10-20"), report.days.map { it.date.toString() })
		assertEquals(3L, report.totals.promptTokens)
	}

	@Test
	fun `a row just after local midnight belongs to the new day`() {
		val report = buildUsageReport(
			rows = listOf(
				row("a", at("2026-10-25", "23:55"), prompt = 10),
				row("a", at("2026-10-26", "00:05"), prompt = 20),
			),
			paidToolNames = paid,
			period = UsagePeriod.Today,
			now = now,
			zone = zone,
		)

		assertEquals(1, report.days.size)
		assertEquals("2026-10-26", report.days.single().date.toString())
		assertEquals(20L, report.totals.promptTokens)
	}

	@Test
	fun `paid calls count only the catalogue's paid tools and skip unrun calls`() {
		val report = buildUsageReport(
			rows = listOf(
				tool("a", at("2026-10-26"), "web_search", "Search results: …"),
				tool("a", at("2026-10-26"), "generate_image", "Saved image to cat.png"),
				tool("a", at("2026-10-26"), "read_file", "the file"),
				tool("a", at("2026-10-26"), "web_search", "[declined] The user declined this call."),
				tool("a", at("2026-10-26"), "generate_image", "[interrupted] The app stopped"),
			),
			paidToolNames = paid,
			period = UsagePeriod.Today,
			now = now,
			zone = zone,
		)

		assertEquals(2, report.totals.paidCalls)
		assertEquals(listOf("generate_image", "web_search"), report.paidTools.map { it.name })
		assertEquals(listOf(1, 1), report.paidTools.map { it.calls })
	}

	@Test
	fun `conversations sort by reported tokens, then paid calls`() {
		val report = buildUsageReport(
			rows = listOf(
				row("a", at("2026-10-26"), prompt = 100),
				row("b", at("2026-10-26"), prompt = 300),
				tool("c", at("2026-10-26"), "web_search", "results"),
				row("c", at("2026-10-26"), prompt = 300),
			),
			paidToolNames = paid,
			period = UsagePeriod.Today,
			now = now,
			zone = zone,
		)

		assertEquals(listOf("c", "b", "a"), report.conversations.map { it.conversationId })
		assertEquals(1, report.conversations.first().totals.paidCalls)
	}

	@Test
	fun `a provider that reported nothing leaves the fields null, not zero`() {
		val report = buildUsageReport(
			rows = listOf(
				row("a", at("2026-10-26")),
				row("a", at("2026-10-26"), completion = 12),
			),
			paidToolNames = paid,
			period = UsagePeriod.Today,
			now = now,
			zone = zone,
		)

		assertNull(report.totals.promptTokens)
		assertEquals(12L, report.totals.completionTokens)
		assertTrue(report.totals.hasTokens)
	}

	@Test
	fun `all ignores the period cutoff`() {
		val report = buildUsageReport(
			rows = listOf(row("a", at("2020-01-01"), prompt = 7)),
			paidToolNames = paid,
			period = UsagePeriod.All,
			now = now,
			zone = zone,
		)

		assertEquals(7L, report.totals.promptTokens)
		assertEquals(1, report.days.size)
	}

	@Test
	fun `nothing recorded is an empty report rather than a zero row`() {
		val report = buildUsageReport(
			rows = emptyList(),
			paidToolNames = paid,
			period = UsagePeriod.ThirtyDays,
			now = now,
			zone = zone,
		)

		assertTrue(report.isEmpty)
		assertTrue(report.days.isEmpty())
		assertTrue(report.conversations.isEmpty())
		assertTrue(report.paidTools.isEmpty())
	}

	private fun at(date: String, time: String = "12:00"): Long =
		LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()

	private fun row(
		conversation: String,
		at: Long,
		prompt: Int? = null,
		completion: Int? = null,
		cached: Int? = null,
		reasoning: Int? = null,
	): UsageRow = UsageRow(
		conversationId = conversation,
		conversationTitle = "Conversation $conversation",
		createdAt = at,
		promptTokens = prompt,
		completionTokens = completion,
		cachedPromptTokens = cached,
		reasoningTokens = reasoning,
	)

	private fun tool(
		conversation: String,
		at: Long,
		name: String,
		prefix: String,
	): UsageRow = UsageRow(
		conversationId = conversation,
		conversationTitle = "Conversation $conversation",
		createdAt = at,
		toolName = name,
		resultPrefix = prefix,
	)
}