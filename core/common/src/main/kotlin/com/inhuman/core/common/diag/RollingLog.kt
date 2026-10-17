package com.inhuman.core.common.diag

import java.io.File

/**
 * A line-oriented log file that never grows without bound.
 *
 * ## Why a file rather than logcat
 *
 * logcat is a ring buffer shared by every process, sized by the device vendor,
 * and it is not readable by the app. The interesting event has usually been
 * pushed out by the time a bug is reported, and on a device with no adb there is
 * no way to read it at all. A file the app owns survives the process, survives
 * a crash, and can be attached to a message.
 *
 * ## Rotation
 *
 * `app.log` is the live file. When it reaches [maxBytes] it becomes `app.1.log`
 * and the older archives shift down, with the oldest deleted. That keeps the
 * newest events at all times without a separate cleanup task -- the recording
 * that matters most is the one just before the problem.
 *
 * Writes are synchronous. The volume is a few dozen lines per turn, and the
 * alternative -- a queue drained by a worker -- loses exactly the lines a crash
 * was about to need unless the crash path also drains it. A page-cache write is
 * already durable across process death; an fsync is not needed for that and
 * would cost far more than the line.
 *
 * The class is in `core:common` and knows nothing about Android so its rotation
 * behaviour can be tested on the JVM with a temporary directory.
 */
class RollingLog(
	private val directory: File,
	private val baseName: String = "app",
	private val maxBytes: Long = 1_000_000,
	private val archives: Int = 3,
) {

	private val lock = Any()

	/** The file new lines are appended to. It may not exist until the first write. */
	val current: File
		get() = File(directory, "$baseName.log")

	/**
	 * Prepare the directory and rotate an over-size file left by the previous
	 * process. Returns the live file, which may not exist yet.
	 */
	fun start(): File = synchronized(lock) {
		ensureDirectory()
		if (current.length() >= maxBytes) rotate()
		current
	}

	/** Append one already-formatted line; rotation happens before the write. */
	fun append(line: String) = synchronized(lock) {
		ensureDirectory()
		if (current.length() >= maxBytes) rotate()
		// One write rather than the line and its newline separately: a process
		// that dies between two writes would otherwise leave the next event
		// glued to the end of this one.
		current.appendText(line + "\n")
		Unit
	}

	/** Oldest first, so concatenating them reads chronologically. */
	fun allFiles(): List<File> = synchronized(lock) {
		(archives downTo 1).map { File(directory, "$baseName.$it.log") } + current
	}

	fun existingFiles(): List<File> = synchronized(lock) {
		allFiles().filter { it.isFile }
	}

	fun sizeBytes(): Long = synchronized(lock) {
		existingFiles().sumOf { it.length() }
	}

	/** The whole retained history, oldest first. Used by tests and by sharing. */
	fun readText(): String = synchronized(lock) {
		existingFiles().joinToString(separator = "") { it.readText() }
	}

	/** Delete every retained file, including archives. */
	fun clear() = synchronized(lock) {
		for (file in allFiles()) {
			file.delete()
		}
		Unit
	}

	private fun ensureDirectory() {
		if (!directory.isDirectory) directory.mkdirs()
	}

	private fun rotate() {
		if (archives <= 0) {
			current.delete()
			return
		}
		File(directory, "$baseName.$archives.log").delete()
		for (index in archives - 1 downTo 1) {
			val from = File(directory, "$baseName.$index.log")
			if (from.isFile) from.renameTo(File(directory, "$baseName.${index + 1}.log"))
		}
		if (current.isFile) current.renameTo(File(directory, "$baseName.1.log"))
	}
}
