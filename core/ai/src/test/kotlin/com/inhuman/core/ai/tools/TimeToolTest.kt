package com.inhuman.core.ai.tools

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock tool, against a fixed moment.
 *
 * Time and timezone are injected rather than read, because a test that depends
 * on "now" passes in the afternoon and fails at midnight -- and because the
 * interesting behaviour is the arithmetic, which needs a known starting point
 * to assert anything about.
 */
class TimeToolTest {

	/** 2026-03-15T12:00:00Z, a Sunday, deliberately not near a month boundary. */
	private val fixed = Instant.parse("2026-03-15T12:00:00Z")
	private val utc = ZoneId.of("UTC")

	private fun tool(zone: ZoneId = utc) = TimeTool(now = { fixed }, defaultZone = { zone })

	private fun call(vararg pairs: Pair<String, String>): String = runBlocking {
		val args: JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
		val result = tool().execute(args)
		// A failure here is a test bug rather than a tool bug; surfacing it as
		// the text keeps the assertion readable.
		result.content
	}

	// --- the common case ------------------------------------------------------

	@Test
	fun `no arguments reports the present`() {
		val out = call()

		// Both a human date and a machine instant, because the two questions a
		// model has are "what day is it for the user" and "what is the instant".
		assertTrue(out, out.contains("Sunday"))
		assertTrue(out, out.contains("2026-03-15"))
		assertTrue(out, out.contains("epoch milliseconds: 1773576000000"))
	}

	@Test
	fun `reports UTC even when the device zone is elsewhere`() {
		val out = runBlocking {
			tool(ZoneId.of("America/New_York")).execute(buildJsonObject { }).content
		}

		// 12:00 UTC is 08:00 in New York, and both must appear: a model asked
		// "what time is it" needs the user's, and one computing an interval
		// needs a fixed reference.
		assertTrue(out, out.contains("device timezone"))
		assertTrue(out, out.contains("UTC"))
		assertTrue(out, out.contains("08:00:00"))
	}

	@Test
	fun `an explicit timezone is added without replacing the defaults`() {
		val out = call("timezone" to "Asia/Tokyo")

		// 12:00 UTC is 21:00 in Tokyo.
		assertTrue(out, out.contains("Asia/Tokyo"))
		assertTrue(out, out.contains("21:00:00"))
		assertTrue(out, out.contains("UTC"))
	}

	// --- arithmetic -----------------------------------------------------------

	@Test
	fun `a past date is measured back to now`() {
		val out = call("date" to "2026-03-01")

		assertTrue(out, out.contains("2026-03-01"))
		// Both numbers, because they answer different questions and a model
		// that wants one is confused by being handed only the other.
		assertTrue(out, out.contains("14 days"))
		assertTrue(out, out.contains("calendar 14d"))
	}

	@Test
	fun `an interval reports both the elapsed and the calendar difference`() {
		// 31 days but exactly one calendar month: the case where the two
		// answers differ and using the wrong one is visibly wrong.
		val out = call("from" to "2026-01-15", "to" to "2026-02-15")

		assertTrue(out, out.contains("31 days"))
		assertTrue(out, out.contains("calendar 1mo"))
	}

	@Test
	fun `adding time crosses a month boundary correctly`() {
		val out = call("add" to "30 days")

		// 15 March + 30 days is 14 April, not "15 April" and not a 30-day
		// shortcut. This is the arithmetic models get wrong most often.
		assertTrue(out, out.contains("2026-04-14"))
		assertTrue(out, out.contains("ahead"))
	}

	@Test
	fun `subtracting time goes backwards`() {
		val out = call("add" to "-2 weeks")

		assertTrue(out, out.contains("2026-03-01"))
		assertTrue(out, out.contains("behind"))
	}

	@Test
	fun `adding months respects month length`() {
		// 31 January + 1 month is 28 February, not 31 February and not
		// 3 March. `java.time` already knows this; the tool must not undo it.
		val out = runBlocking {
			TimeTool(now = { Instant.parse("2026-01-31T12:00:00Z") }, defaultZone = { utc })
				.execute(buildJsonObject { put("add", "1 month") })
				.content
		}

		assertTrue(out, out.contains("2026-02-28"))
	}

	// --- refusals -------------------------------------------------------------

	@Test
	fun `an unreadable date is refused with the expected format`() {
		val out = call("date" to "next tuesday")

		// Refused rather than guessed: silently reading "next tuesday" as
		// something would produce a confidently wrong interval.
		assertTrue(out, out.contains("could not read the date"))
		assertTrue(out, out.contains("YYYY-MM-DD"))
	}

	@Test
	fun `an unknown timezone is refused by name`() {
		val out = call("timezone" to "Mars/Olympus")

		assertTrue(out, out.contains("unknown timezone"))
		assertTrue(out, out.contains("Mars/Olympus"))
	}

	@Test
	fun `an unreadable shift is refused with examples`() {
		val out = call("add" to "a while")

		assertTrue(out, out.contains("could not read"))
		assertTrue(out, out.contains("30 days"))
	}

	// --- classification -------------------------------------------------------

	@Test
	fun `reading the clock does not block regeneration`() {
		// It changes nothing, so replaying a turn that used it is free.
		assertFalse(TimeTool().mutates)
	}

	@Test
	fun `the schema requires nothing`() {
		// Asking for the time is the common case and an empty call must work;
		// a required field would make the model invent one.
		val required = TimeTool().parameters["required"]
		assertEquals(emptyList<String>(), required)
	}
}
