package com.inhuman.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.luben.zstd.Zstd
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random

/**
 * Can an Android device read a DeepSeek Harness session log?
 *
 * ## Why this test exists
 *
 * DSH stores each session as a JSONL file in which **every line is its own zstd
 * frame** -- measured on real logs: a 2.46 MB session holds 2,364 frames, a
 * median 365 bytes apart, each decompressing to roughly 4.3 KB of JSON.
 * Everything the product shows is a projection folded over that log, so a
 * port's cold start *is* "read the log". Whether that is affordable on a phone
 * decides whether porting the runtime is viable at all, and a fast x86 CPU does
 * not answer it: the same walk cost 177 ms per 24 MB there, and ARM64 could
 * plausibly be five to ten times slower.
 *
 * ## Why the file is generated rather than copied
 *
 * A real session log holds the user's conversations, and a benchmark has no
 * need for them. What decompression costs depends on the frame count and the
 * compressed and uncompressed sizes, not on the words inside -- so the fixture
 * is built here to the measured *shape* of a real log. The numbers below are
 * therefore transferable; the file is not.
 *
 * Run:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.inhuman.bench.SessionLogReadBenchmark
 */
@RunWith(AndroidJUnit4::class)
class SessionLogReadBenchmark {

	@Test
	fun measureReadingASessionLog() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val file = File(context.cacheDir, "bench-session.jsonl.zst")

		if (!file.exists() || file.length() < MIN_EXPECTED_BYTES) buildFixture(file)

		val bytes = file.readBytes()
		val frames = findFrames(bytes)

		// Warm up: the native library load must not land inside a measurement.
		repeat(2) { runWalk(bytes, frames, parse = false) }
		val plainTotal = runWalk(bytes, frames, parse = false).plain

		val report = StringBuilder("\n")
		report.appendLine("abis             ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
		report.appendLine("sdk              ${android.os.Build.VERSION.SDK_INT}")
		report.appendLine("build            ${android.os.Build.MODEL}")
		report.appendLine(
			"file             ${bytes.size / 1024} KB, ${frames.size} frames, ${plainTotal / 1024} KB plain",
		)
		report.appendLine(
			"ratio            %.1f:1   (a real 2.4 MB session measured 3.5:1)".format(
				plainTotal.toDouble() / bytes.size,
			),
		)

		val t1 = System.nanoTime()
		repeat(REPEAT) { runWalk(bytes, frames, parse = false) }
		val decompressMs = (System.nanoTime() - t1) / 1e6 / REPEAT
		report.appendLine(
			"decompress all   %6.1f ms   (%.1f MB out, %.1f us/frame)".format(
				decompressMs, plainTotal / 1048576.0, decompressMs * 1000 / frames.size,
			),
		)

		val t2 = System.nanoTime()
		val events = runWalk(bytes, frames, parse = true).events
		val parseMs = (System.nanoTime() - t2) / 1e6
		report.appendLine("decompress+parse %6.1f ms   ($events events)".format(parseMs))

		// The cold-start path: 1 MB of tail, which is what a client that does
		// not replay its whole history actually reads.
		val tailLen = minOf(TAIL_BYTES, bytes.size)
		val tail = bytes.copyOfRange(bytes.size - tailLen, bytes.size)
		val tailFrames = findFrames(tail)
		repeat(2) { runWalk(tail, tailFrames, parse = true) }
		val t3 = System.nanoTime()
		var tailEvents = 0
		repeat(REPEAT) { tailEvents = runWalk(tail, tailFrames, parse = true).events }
		val tailMs = (System.nanoTime() - t3) / 1e6 / REPEAT
		report.appendLine(
			"tail 1 MB        %6.1f ms   ($tailEvents events, ${tailFrames.size} frames)".format(tailMs),
		)

		// Random single frames: proves the access pattern a UI needs when it
		// scrolls or jumps, without replaying anything.
		val t4 = System.nanoTime()
		var n = 0
		repeat(200) { i ->
			val idx = (i.toLong() * frames.size / 200).toInt().coerceIn(0, frames.size - 1)
			decompressFrameAt(bytes, frames[idx])
			n++
		}
		val randomMs = (System.nanoTime() - t4) / 1e6
		report.appendLine("200 random frames %5.1f ms".format(randomMs))

		// Heap cost of holding a whole conversation's worth of parsed events.
		val runtime = Runtime.getRuntime()
		System.gc()
		val before = runtime.totalMemory() - runtime.freeMemory()
		val held = ArrayList<JSONObject>(frames.size)
		for (range in frames) {
			for (line in decompressFrameAt(bytes, range).split('\n')) {
				if (line.isNotBlank()) held.add(JSONObject(line))
			}
		}
		System.gc()
		val after = runtime.totalMemory() - runtime.freeMemory()
		report.appendLine(
			"heap for a full read %5.1f MB  (${held.size} live events)".format((after - before) / 1048576.0),
		)
		held.clear()

		println(report)
	}

	/**
	 * Frame boundaries, using zstd's own frame scanner rather than a parser.
	 *
	 * The scanner **throws** `ZstdException("Unknown frame descriptor")` when
	 * handed an offset that is not a frame start, rather than returning a
	 * negative size -- so a byte-by-byte scan has to catch and keep walking.
	 * That is documented behaviour for a garbage descriptor, not an error worth
	 * failing a test over.
	 */
	private fun findFrames(buf: ByteArray): List<Int> {
		val out = ArrayList<Int>()
		var i = 0
		while (i + 4 <= buf.size) {
			val size = try {
				Zstd.findFrameCompressedSize(buf, i)
			} catch (e: Exception) {
				-1L
			}
			if (size > 0 && i + size <= buf.size) {
				out.add(i)
				i += size.toInt()
			} else {
				i++
			}
		}
		return out
	}

	private class Walk(val plain: Long, val events: Int)

	private fun runWalk(buf: ByteArray, frames: List<Int>, parse: Boolean): Walk {
		var plain = 0L
		var events = 0
		for (offset in frames) {
			val text = decompressFrameAt(buf, offset)
			plain += text.length
			if (parse) {
				for (line in text.split('\n')) {
					if (line.isNotBlank()) {
						JSONObject(line)
						events++
					}
				}
			}
		}
		return Walk(plain, events)
	}

	/**
	 * One frame, decompressed.
	 *
	 * The frame's exact length comes from zstd's own scanner, and its exact
	 * output size from the frame header, so this allocates once and never
	 * guesses -- which is also the shape a real reader would take.
	 */
	private fun decompressFrameAt(buf: ByteArray, offset: Int): String {
		val compressed = Zstd.findFrameCompressedSize(buf, offset).toInt()
		// `getFrameContentSize`, not the deprecated `decompressedSize`: same
		// answer, current API.
		val plainSize = Zstd.getFrameContentSize(buf, offset).toInt()
		val frame = ByteArray(compressed)
		System.arraycopy(buf, offset, frame, 0, compressed)
		return String(Zstd.decompress(frame, plainSize), Charsets.UTF_8)
	}

	/**
	 * A fixture with the measured shape of a real session log.
	 *
	 * ## Entropy is the point, and getting it wrong invalidates the measurement
	 *
	 * The first version of this used repeated lorem-ipsum, which zstd crushed to
	 * a 13.9:1 ratio -- a 331 KB file where a real session of the same frame
	 * count is 2.41 MB at 3.5:1. Two of the numbers below were therefore
	 * meaningless: "tail 1 MB" read the entire fixture because the fixture was
	 * smaller than the tail, and every per-byte figure was measuring the
	 * decompression of near-identical frames.
	 *
	 * So the payload is high-entropy prose-like text, tuned to land near the
	 * real ratio. What is being measured is decompression of *real data*, and
	 * compressibility is most of what makes that expensive.
	 */
	private fun buildFixture(file: File) {
		val rng = Random(20261005L)
		java.io.BufferedOutputStream(file.outputStream(), 1 shl 16).use { out ->
			for (i in 0 until FRAMES) {
				val kind = when {
					i % 137 == 0 -> "tool/result"
					i % 3 == 0 -> "tool/call"
					i % 5 == 0 -> "step/start"
					else -> "assistant/message"
				}
				val payload = if (i % 137 == 0) 40_000 + rng.nextInt(90_000) else 200 + rng.nextInt(2_000)

				val sb = StringBuilder(payload + 256)
				sb.append("{\"type\":\"").append(kind).append("\",\"seq\":").append(i)
				sb.append(",\"ts\":").append(1759600000000L + i * 37L)
				sb.append(",\"payload\":\"")
				// Words drawn from a large alphabet, so the text looks like prose
				// to a compressor without being one -- the entropy a real
				// conversation and a real web-search result both carry.
				var written = 0
				while (written < payload) {
					val wordLen = 3 + rng.nextInt(9)
					repeat(wordLen) { sb.append(ALPHABET[rng.nextInt(ALPHABET.length)]) }
					sb.append(' ')
					written += wordLen + 1
				}
				sb.append("\"}\n")

				val plain = sb.toString().toByteArray(Charsets.UTF_8)
				out.write(Zstd.compress(plain, 3))
			}
		}
	}

	private companion object {
		/** Frames in the fixture; a real 2.4 MB session held 2,364. */
		const val FRAMES = 2_364

		const val REPEAT = 3
		const val TAIL_BYTES = 1 shl 20
		const val MIN_EXPECTED_BYTES = 1 shl 20

		/**
		 * Lower-case letters and a space: the alphabet of ordinary text, which
		 * zstd compresses to roughly the 3:1 a real JSONL log shows. A wider
		 * alphabet (`[a-z0-9]` uniformly) would understate the cost by making
		 * the fixture incompressible.
		 */
		const val ALPHABET = "abcdefghijklmnopqrstuvwxyz     "
	}
}
