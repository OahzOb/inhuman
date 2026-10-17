package com.inhuman.core.common.diag

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The app's flight recorder.
 *
 * ## Why this exists
 *
 * The diagnostic question that matters here is not "what is wrong right now?"
 * -- that can be looked at. It is "what happened ten minutes ago, before I
 * looked?" A bug report arrives after the fact: the turn that failed, the
 * search that never returned, the crash that closed the screen. Without a
 * record, the only way to answer is to ask the user to reproduce it, and a bug
 * that depends on a slow network, a particular model response or a race is
 * often not reproducible on demand.
 *
 * So every meaningful step writes one line to a file that outlives the process.
 * The line is structured enough to grep (`level/tag` and `key=value`) and short
 * enough to read. This facade is the only API the app calls; [Sink] is
 * installed once at start-up and a missing sink is a no-op, which is what lets
 * the same code run in JVM tests with nothing installed.
 *
 * ## What is deliberately not recorded
 *
 * Never the API key, a prompt, or file contents. The transcript already holds
 * what was said and what the tools returned, and it is on the device; the log
 * exists to explain the *machinery around* that transcript -- which model, how
 * many messages, what HTTP status, how long a tool took, which rule refused a
 * call. Lengths and identifiers take the place of content. Names and paths do
 * appear, because a path is usually the answer ("it refused this file") and is
 * far less sensitive than the bytes in it.
 *
 * [redact] is the second line of defence, not the first: call sites are
 * expected not to pass secrets at all.
 */
object Diag {

	enum class Level(val letter: Char) {
		Debug('D'),
		Info('I'),
		Warn('W'),
		Error('E'),
	}

	/**
	 * Where the lines go. Installed by the application; absent in tests and
	 * previews, where logging would be noise or a missing Android dependency.
	 */
	fun interface Sink {
		fun write(level: Level, tag: String, message: String, error: Throwable?)
	}

	/** A message long enough to hold an exception, short enough not to flood. */
	private const val MAX_MESSAGE = 4_096

	/** Stack traces are valuable but not unbounded; a truncated one still names the frame. */
	private const val MAX_STACK = 8_192

	private const val MAX_TAG = 24

	private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

	/**
	 * Key-shaped strings. DeepSeek keys begin with `sk-`; the bearer form is
	 * what the same value looks like in a header. Matching a shape rather than
	 * a known key means a key pasted into the wrong log line is still caught.
	 */
	private val apiKey = Regex("""sk-[A-Za-z0-9_-]{8,}""")
	private val bearer = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}""")
	private val namedSecret = Regex(
		"""(?i)\b(api[_-]?key|authorization|access[_-]?token|secret|password)\b\s*[:=]\s*[^\s,;)]+""",
	)

	@Volatile
	private var sink: Sink? = null

	/**
	 * Replace the sink, or remove it with null.
	 *
	 * A single global rather than passing a logger through every constructor:
	 * the alternative is threading a dependency through the provider, the view
	 * model, the tools and the database for something that must never change
	 * behaviour. The cost is that a test which installs a sink must remove it.
	 */
	fun install(sink: Sink?) {
		this.sink = sink
	}

	fun d(tag: String, message: String) = emit(Level.Debug, tag, message, null)

	fun i(tag: String, message: String) = emit(Level.Info, tag, message, null)

	fun w(tag: String, message: String, error: Throwable? = null) =
		emit(Level.Warn, tag, message, error)

	fun e(tag: String, message: String, error: Throwable? = null) =
		emit(Level.Error, tag, message, error)

	/**
	 * The first eight characters of an id, or `-`.
	 *
	 * Enough to correlate lines from one turn, short enough that a UUID does not
	 * dominate every line. It is not a secret: a conversation id is a random
	 * UUID, not a name.
	 */
	fun shortId(id: String?): String = id?.take(8) ?: "-"

	/**
	 * One log line, without a trailing newline.
	 *
	 * Public and pure so the format can be tested without a file, a device or a
	 * clock. `zone` is a parameter rather than read inside so a test is not
	 * hostage to the machine's time zone.
	 */
	fun format(
		level: Level,
		tag: String,
		message: String,
		error: Throwable? = null,
		at: Instant = Instant.now(),
		zone: ZoneId = ZoneId.systemDefault(),
	): String {
		val time = timeFormat.withZone(zone).format(at)
		val cleanTag = tag
			.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
			.take(MAX_TAG)
			.ifBlank { "-" }
		val body = oneLine(redact(message)).take(MAX_MESSAGE).ifBlank { "-" }

		val line = StringBuilder(64 + body.length)
		line.append(time).append(' ').append(level.letter).append('/').append(cleanTag)
			.append(' ').append(body)

		if (error != null) {
			val trace = redact(error.stackTraceToString()).take(MAX_STACK)
			for (frame in trace.lineSequence()) {
				line.append('\n').append("    ").append(frame)
			}
		}
		return line.toString()
	}

	/**
	 * Remove the shapes a credential takes.
	 *
	 * Imperfect on purpose: it is a scrubber for text that should not contain a
	 * secret in the first place, not a guarantee. The regexes are deliberately
	 * broad -- a false positive costs a redacted word in a log, a false negative
	 * costs the key.
	 */
	fun redact(text: String): String =
		text
			.replace(apiKey, "sk-***")
			.replace(bearer, "Bearer ***")
			.replace(namedSecret) { match -> "${match.groupValues[1]}=***" }

	/**
	 * A log line is one line. Multi-line content is collapsed rather than
	 * structured, because a line-oriented file is what makes `grep`, `tail` and
	 * a shared text file all work.
	 */
	private fun oneLine(text: String): String =
		text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')

	private fun emit(level: Level, tag: String, message: String, error: Throwable?) {
		val target = sink ?: return
		// A logger that throws is worse than a missing line: it would turn a
		// diagnostic aid into a second failure, possibly inside the crash
		// handler itself.
		try {
			target.write(level, tag, message, error)
		} catch (_: Throwable) {
			// Swallowed deliberately. See above.
		}
	}
}
