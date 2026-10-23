package com.inhuman.core.ai.ssh

/**
 * The user's ceilings for SSH resource use, with the model able only to tighten.
 *
 * The model never supplies the ceiling itself. It may ask for a smaller timeout,
 * output size, directory listing or read size; [effective] clamps every request
 * into `[floor, userCeiling]`. A request above the ceiling is clamped rather
 * than accepted, and a missing request uses the app default, not the ceiling.
 *
 * This is why "the user set a ten-minute limit" does not mean every command
 * waits ten minutes: the default remaining 120 seconds, and the model has to
 * ask for more.
 */
data class SshLimits(
	/** User ceiling for one `ssh_exec` command. */
	val commandTimeoutCeilingMs: Int = DEFAULT_COMMAND_TIMEOUT_MS,
	/** User ceiling for stdout and stderr each. */
	val outputCeilingBytes: Int = DEFAULT_OUTPUT_BYTES,
	/** User ceiling for one directory listing. */
	val listCeilingEntries: Int = DEFAULT_LIST_ENTRIES,
	/** User ceiling for one remote text read. */
	val readCeilingBytes: Long = DEFAULT_READ_BYTES,
) {
	val commandTimeoutCeilingSeconds: Int
		get() = commandTimeoutCeilingMs / 1000

	/**
	 * @param requestedSeconds a model request, or null to use the default.
	 *   A value above the ceiling is shortened; below the floor is raised.
	 */
	fun commandTimeoutMs(requestedSeconds: Int?): Int {
		val ceiling = commandTimeoutCeilingMs.coerceIn(
			MIN_COMMAND_TIMEOUT_MS,
			MAX_COMMAND_TIMEOUT_MS,
		)
		val fallback = minOf(DEFAULT_COMMAND_TIMEOUT_MS, ceiling)
		val requested = requestedSeconds
			?.let { (it.toLong() * 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
			?: fallback
		return requested.coerceIn(MIN_COMMAND_TIMEOUT_MS, ceiling)
	}

	/** @param requestedBytes a model request, or null to use the default. */
	fun outputBytes(requestedBytes: Int?): Int {
		val ceiling = outputCeilingBytes.coerceIn(MIN_OUTPUT_BYTES, MAX_OUTPUT_BYTES)
		val fallback = minOf(DEFAULT_OUTPUT_BYTES, ceiling)
		return (requestedBytes ?: fallback).coerceIn(MIN_OUTPUT_BYTES, ceiling)
	}

	/** @param requestedEntries a model request, or null to use the default. */
	fun listEntries(requestedEntries: Int?): Int {
		val ceiling = listCeilingEntries.coerceIn(MIN_LIST_ENTRIES, MAX_LIST_ENTRIES)
		val fallback = minOf(DEFAULT_LIST_ENTRIES, ceiling)
		return (requestedEntries ?: fallback).coerceIn(1, ceiling)
	}

	/** @param requestedBytes a model request, or null to use the default. */
	fun readBytes(requestedBytes: Int?): Long {
		val ceiling = readCeilingBytes.coerceIn(MIN_READ_BYTES, MAX_READ_BYTES)
		val fallback = minOf(DEFAULT_READ_BYTES, ceiling)
		return (requestedBytes?.toLong() ?: fallback).coerceIn(1L, ceiling)
	}

	companion object {
		const val DEFAULT_COMMAND_TIMEOUT_MS = 120_000
		const val MIN_COMMAND_TIMEOUT_MS = 5_000
		const val MAX_COMMAND_TIMEOUT_MS = 600_000

		const val DEFAULT_OUTPUT_BYTES = 20_000
		const val MIN_OUTPUT_BYTES = 5_000
		const val MAX_OUTPUT_BYTES = 100_000

		const val DEFAULT_LIST_ENTRIES = 500
		const val MIN_LIST_ENTRIES = 100
		const val MAX_LIST_ENTRIES = 2_000

		const val DEFAULT_READ_BYTES = 65_536L
		const val MIN_READ_BYTES = 16_384L
		const val MAX_READ_BYTES = 262_144L

		const val DEFAULT_COMMAND_TIMEOUT_SECONDS = DEFAULT_COMMAND_TIMEOUT_MS / 1000
		const val MIN_COMMAND_TIMEOUT_SECONDS = 30
		const val MAX_COMMAND_TIMEOUT_SECONDS = 600

		/**
		 * Read the stored ceilings.
		 *
		 * A malformed or out-of-range stored value falls back to its default
		 * rather than making SSH unusable; the Settings screen only writes
		 * values in range, but a restore from an older or edited store should
		 * not be able to set a zero timeout.
		 */
		fun fromStored(
			commandTimeoutSeconds: String?,
			outputBytes: String?,
			listEntries: String?,
			readBytes: String?,
		): SshLimits {
			val seconds = commandTimeoutSeconds?.toIntOrNull()
				?.coerceIn(MIN_COMMAND_TIMEOUT_SECONDS, MAX_COMMAND_TIMEOUT_SECONDS)
				?: DEFAULT_COMMAND_TIMEOUT_SECONDS
			val output = outputBytes?.toIntOrNull()
				?.coerceIn(MIN_OUTPUT_BYTES, MAX_OUTPUT_BYTES)
				?: DEFAULT_OUTPUT_BYTES
			val list = listEntries?.toIntOrNull()
				?.coerceIn(MIN_LIST_ENTRIES, MAX_LIST_ENTRIES)
				?: DEFAULT_LIST_ENTRIES
			val read = readBytes?.toLongOrNull()
				?.coerceIn(MIN_READ_BYTES, MAX_READ_BYTES)
				?: DEFAULT_READ_BYTES
			return SshLimits(
				commandTimeoutCeilingMs = seconds * 1000,
				outputCeilingBytes = output,
				listCeilingEntries = list,
				readCeilingBytes = read,
			)
		}
	}
}
