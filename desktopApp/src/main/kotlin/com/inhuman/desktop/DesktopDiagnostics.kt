package com.inhuman.desktop

import com.inhuman.core.common.diag.Diag
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The desktop flight recorder.
 *
 * Android's `AppDiagnostics` writes through `Diag` to a file under the app's
 * private directory. The desktop has the same need -- the turn that failed ten
 * minutes ago is gone otherwise -- but no `Context`, so this is the same idea
 * with a plain file and a single rotation.
 *
 * The contract is [Diag]'s: no API keys, prompts or file contents. This sink
 * only adds the timestamp and the level/tag prefix.
 */
object DesktopDiagnostics {

	private const val MAX_BYTES = 5L * 1024 * 1024
	private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
		.withZone(ZoneId.systemDefault())

	@Volatile
	private var logFile: File? = null

	fun install(dataDir: File): File {
		val dir = File(dataDir, "logs").apply { mkdirs() }
		val file = File(dir, "inhuman.log")
		logFile = file
		Diag.install { level, tag, message, error ->
			runCatching {
				synchronized(this) {
					if (file.length() > MAX_BYTES) {
						val previous = File(dir, "inhuman.log.1")
						previous.delete()
						file.renameTo(previous)
					}
					val line = buildString {
						append(timeFormat.format(Instant.now()))
						append(' ')
						append(level.letter)
						append('/')
						append(tag)
						append(' ')
						append(Diag.redact(message))
						if (error != null) {
							append(" | ")
							append(error.javaClass.simpleName)
							append(": ")
							append(Diag.redact(error.message.orEmpty()))
						}
						append('\n')
					}
					file.appendText(line)
				}
			}
		}
		return file
	}

	fun currentLog(): File? = logFile

	fun clear() {
		runCatching { logFile?.writeText("") }
	}
}
