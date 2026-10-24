package com.inhuman.desktop

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/**
 * One desktop process per data directory.
 *
 * Two windows would open the same SQLite file and the same workspaces; the
 * second host's writes would not be visible to the first until a reload, and a
 * tool could race another tool in the same folder. The lock is advisory to this
 * app only, which is the correct scope: it is not trying to stop a backup tool
 * or an editor.
 *
 * The lock lives beside `inhuman.db`, so a second profile with a different data
 * directory is still allowed. A stale lock file after a crash is harmless:
 * `tryLock` is about the open channel, not the file's existence.
 */
class SingleInstance private constructor(
	private val channel: FileChannel,
	private val lock: FileLock,
) {

	fun close() {
		runCatching { lock.release() }
		runCatching { channel.close() }
	}

	companion object {
		fun acquire(dataDir: File): SingleInstance? {
			dataDir.mkdirs()
			val lockFile = File(dataDir, "instance.lock")
			val channel = FileChannel.open(
				lockFile.toPath(),
				StandardOpenOption.CREATE,
				StandardOpenOption.WRITE,
			)
			val lock = try {
				channel.tryLock()
			} catch (_: OverlappingFileLockException) {
				null
			}
			if (lock == null) {
				channel.close()
				return null
			}
			return SingleInstance(channel, lock)
		}
	}
}
