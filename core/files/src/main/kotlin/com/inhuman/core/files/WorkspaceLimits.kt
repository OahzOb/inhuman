package com.inhuman.core.files

/**
 * Hard ceilings on what one conversation's workspace may contain or hand back.
 *
 * These are enforced by the tools, not asked of the model. A model that ignores
 * its instructions can still only produce a bounded amount of damage, and the
 * app cannot be talked into reading a 4 GB file into memory or listing a
 * directory with a million entries in it.
 */
data class WorkspaceLimits(
	/** Largest single read. Beyond this the tool refuses rather than truncating. */
	val maxReadBytes: Long = 1L shl 20,            // 1 MiB

	/** Largest single write. */
	val maxWriteBytes: Long = 4L shl 20,           // 4 MiB

	/**
	 * Most entries one `list_files` call may return.
	 *
	 * This is a safety limit as much as a performance one: without it, an agent
	 * can be talked into walking a directory that brings the UI to its knees.
	 */
	val maxListEntries: Int = 500,

	/** Total bytes allowed in one workspace. */
	val maxTotalBytes: Long = 256L shl 20,         // 256 MiB

	/** Total files allowed in one workspace. */
	val maxFileCount: Int = 5_000,

	/** Free space that must remain on the volume after a write. */
	val reservedFreeBytes: Long = 64L shl 20,      // 64 MiB
)
