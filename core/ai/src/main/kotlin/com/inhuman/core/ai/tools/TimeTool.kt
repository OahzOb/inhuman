package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import com.inhuman.core.ai.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * What time it is, in the places that matter.
 *
 * ## Why a tool rather than something in the prompt
 *
 * A timestamp in the system prompt is stale the moment the conversation is
 * reopened, and a model asked "how long until Friday" against a stale date
 * gives a confidently wrong answer. Worse, it cannot tell that it is wrong.
 *
 * ## Why the device's own zone and UTC, always
 *
 * Both, every call, unasked. The two questions a model actually has are "what
 * day is it for the user" and "what is the machine-readable instant", and
 * making it spend a turn asking for the second one is a turn not spent on the
 * task. A caller can ask for others; it does not have to ask for these.
 *
 * ## Why date arithmetic is here rather than left to the model
 *
 * Date arithmetic is where language models are reliably bad -- month lengths,
 * leap years, and the difference between "30 days" and "one month". It is
 * exactly the kind of thing a tool should remove from the model's plate, and
 * `java.time` already does it correctly.
 *
 * Reads only, so a turn that used it can be regenerated.
 */
class TimeTool(
	private val now: () -> Instant = Instant::now,
	private val defaultZone: () -> ZoneId = { ZoneId.systemDefault() },
) : Tool {

	override val name = "read_time"

	override val mutates = false

	override val description = """
		Read the current date and time, and do date arithmetic.

		Use this rather than answering from memory whenever the answer depends on
		what day it is, how long until or since something, or what a past date
		falls on. Do not guess the current date.

		With no arguments it returns the present moment in the device's timezone
		and in UTC. Give `date` to compute a duration from that date to now, or
		`from` and `to` for the duration between two dates.
	""".trimIndent()

	override val parameters: Map<String, Any?> = mapOf(
		"type" to "object",
		"properties" to mapOf(
			"timezone" to mapOf(
				"type" to "string",
				"description" to "IANA zone to report in as well, e.g. 'America/New_York'.",
			),
			"date" to mapOf(
				"type" to "string",
				"description" to "A date to measure from, until now. 'YYYY-MM-DD' or an ISO instant.",
			),
			"from" to mapOf(
				"type" to "string",
				"description" to "Start of an interval, used with 'to'.",
			),
			"to" to mapOf(
				"type" to "string",
				"description" to "End of an interval, used with 'from'. Defaults to now.",
			),
			"add" to mapOf(
				"type" to "string",
				"description" to "Shift the reference date by an amount, e.g. '30 days', '-2 weeks', '3 months'.",
			),
		),
		// Nothing is required: asking for the time is the common case, and an
		// empty call should just work.
		"required" to emptyList<String>(),
	)

	override suspend fun execute(arguments: JsonObject): ToolResult = try {
		ToolResult.ok(compute(arguments))
	} catch (e: IllegalArgumentException) {
		// A malformed argument is the model's to fix, so it comes back as a
		// message rather than as a thrown failure.
		ToolResult.error(e.message ?: "bad arguments")
	}

	private fun compute(args: JsonObject): String {
		val instant = now()
		val zone = args.stringOrNull("timezone")?.let { name ->
			runCatching { ZoneId.of(name) }.getOrElse {
				throw IllegalArgumentException(
					"unknown timezone '$name'; use an IANA name like 'Europe/London'",
				)
			}
		} ?: defaultZone()

		// Everything below works in one zone.
		//
		// Comparing instants and local dates is where this got wrong first: the
		// difference between two moments and the difference between two calendar
		// dates are not the same number, and mixing `Instant` with
		// `ZonedDateTime` made the compiler complain rather than producing a
		// plausible wrong answer -- which is the outcome to want.
		val zoned = instant.atZone(zone)

		val lines = mutableListOf<String>()
		lines += format("device timezone", instant, defaultZone())
		lines += format("UTC", instant, ZoneId.of("UTC"))
		if (zone != defaultZone() && zone != ZoneId.of("UTC")) {
			lines += format(zone.id, instant, zone)
		}
		lines += "epoch milliseconds: ${instant.toEpochMilli()}"

		// An explicit interval, when one was asked for.
		val from = args.stringOrNull("from")
		val to = args.stringOrNull("to")
		if (from != null) {
			val start = parse(from, zone)
			val end = to?.let { parse(it, zone) } ?: zoned
			lines += ""
			lines += "${start.toLocalDate()} to ${end.toLocalDate()}: ${describe(start, end)}"
		} else if (to != null) {
			// `to` alone is meaningful too: how long until that date.
			val end = parse(to, zone)
			lines += ""
			lines += "now to ${end.toLocalDate()}: ${describe(zoned, end)}"
		}

		// A single date, measured against now.
		args.stringOrNull("date")?.let { raw ->
			val then = parse(raw, zone)
			lines += ""
			lines += "now to ${then.toLocalDate()}: ${describe(zoned, then)}"
		}

		// A shift, which is the arithmetic a model gets wrong most often.
		args.stringOrNull("add")?.let { spec ->
			val shifted = shift(zoned, spec)
			val direction = if (shifted.isAfter(zoned)) "ahead" else "behind"
			lines += ""
			lines += "$spec from now: ${shifted.toLocalDate()} " +
				"${shifted.format(DateTimeFormatter.ofPattern("HH:mm:ss"))} ${zone.id} " +
				"(${describe(zoned, shifted)}, $direction)"
		}

		return lines.joinToString("\n")
	}

	private fun format(label: String, instant: Instant, zone: ZoneId): String {
		val zoned = instant.atZone(zone)
		return "$label: ${zoned.format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm:ss z"))} " +
			"| ISO ${zoned.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}"
	}

	/** Both the plain difference and the calendar difference, because they differ. */
	private fun describe(a: ZonedDateTime, b: ZonedDateTime): String {
		val duration = Duration.between(a, b)
		val days = ChronoUnit.DAYS.between(a.toLocalDate(), b.toLocalDate())
		val period = java.time.Period.between(a.toLocalDate(), b.toLocalDate())
		val abs = duration.abs()
		val clock = "${abs.toDays()}d ${abs.toHours() % 24}h ${abs.toMinutes() % 60}m"
		val calendar = buildString {
			if (period.years != 0) append("${kotlin.math.abs(period.years)}y ")
			if (period.months != 0) append("${kotlin.math.abs(period.months)}mo ")
			append("${kotlin.math.abs(period.days)}d")
		}.trim()
		return "$clock ($days days; calendar $calendar)"
	}

	private fun shift(from: ZonedDateTime, spec: String): ZonedDateTime {
		val match = Regex("^\\s*(-?\\d+)\\s*(minute|hour|day|week|month|year)s?\\s*$", RegexOption.IGNORE_CASE)
			.matchEntire(spec)
			?: throw IllegalArgumentException(
				"could not read '$spec'; expected something like '30 days', '-2 weeks' or '3 months'",
			)
		val amount = match.groupValues[1].toLong()
		return when (match.groupValues[2].lowercase()) {
			"minute" -> from.plusMinutes(amount)
			"hour" -> from.plusHours(amount)
			"day" -> from.plusDays(amount)
			"week" -> from.plusWeeks(amount)
			"month" -> from.plusMonths(amount)
			else -> from.plusYears(amount)
		}
	}

	/**
	 * A date, an instant, or a local date-time.
	 *
	 * Tried in order rather than guessed: accepting three shapes is what makes
	 * the tool usable without a format lecture, and trying them in order is what
	 * keeps `2026-01-02` from being read as something other than a date.
	 */
	private fun parse(raw: String, zone: ZoneId): ZonedDateTime {
		val text = raw.trim()
		val attempts: List<() -> ZonedDateTime> = listOf(
			{ ZonedDateTime.parse(text) },
			{ Instant.parse(text).atZone(zone) },
			{ LocalDateTime.parse(text).atZone(zone) },
			{ LocalDate.parse(text).atStartOfDay(zone) },
		)
		for (attempt in attempts) {
			try {
				return attempt()
			} catch (_: DateTimeParseException) {
				// Try the next shape.
			}
		}
		throw IllegalArgumentException(
			"could not read the date '$raw'; try 'YYYY-MM-DD' or 'YYYY-MM-DDTHH:MM:SS'",
		)
	}
}

private fun JsonObject.stringOrNull(name: String): String? =
	this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
