package com.inhuman.ui.chat

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The zip half of the conversation bundle: one JSON document plus the
 * attachment bytes it names.
 *
 * ## What the archive looks like
 *
 * ```
 * conversation.json          the bundle document, UTF-8
 * files/<workspace-relative>  one entry per attachment
 * ```
 *
 * ## Why reads are defensive
 *
 * A zip is a container chosen by whoever produced it, and this app opens
 * bundles that came from elsewhere. Entry names are therefore treated as
 * untrusted input: absolute paths, `..`, drive letters, backslashes and empty
 * segments are never turned into a key, and nothing is ever extracted to disk
 * here -- the returned map is just names and bytes. The caller decides what to
 * do with the files, through the workspace's own rules.
 *
 * Unknown top-level entries, directories and duplicates are reported in
 * [ReadResult.skipped] rather than silently dropped: a bundle that lost a file
 * must not look complete.
 *
 * ## Caps
 *
 * A zip can claim entry sizes that do not match its contents (a zip bomb), so
 * the caps are enforced on the bytes actually inflating. Exceeding one returns
 * `null`: the caller reports the failure, because a truncated bundle is worse
 * than a refused one. Caps are per entry and cumulative for the whole archive.
 *
 * ## Write-side caveat: memory
 *
 * [write] reads each source completely before creating its entry. Streaming a
 * file straight into the archive would be cheaper, but a read failure halfway
 * through would leave a truncated file in a valid-looking zip -- the failure
 * would travel with the bundle. Attachment sizes are bounded by the workspace
 * quotas, which is what makes the buffered copy acceptable here.
 */
object ConversationBundleZip {

	private const val CONVERSATION_ENTRY = "conversation.json"
	private const val FILES_PREFIX = "files/"
	private const val COPY_BUFFER_BYTES = 64 * 1024

	/**
	 * A ceiling no workspace reaches; it exists so a hostile archive cannot make
	 * the reader allocate an unbounded number of map entries.
	 */
	private const val MAX_FILE_ENTRIES = 10_000

	/** One attachment to write: its workspace-relative path and how to read it. */
	data class Source(val path: String, val open: () -> InputStream)

	/**
	 * What [write] produced.
	 *
	 * [files] and [bytes] count the `files/` entries only: `conversation.json` is
	 * the envelope, and adding its size to a number the UI shows as "attachments"
	 * would make the two disagree.
	 *
	 * [missing] lists every source that did not become an entry, preserving the
	 * caller's order: the stream could not be opened or read, the path was not a
	 * safe relative path, or the path was a duplicate.
	 */
	data class WriteResult(val files: Int, val bytes: Long, val missing: List<String>)

	/**
	 * What [read] found.
	 *
	 * [files] keys are the paths with the `files/` prefix removed, i.e. workspace
	 * relative paths. [skipped] lists entries that were not returned, in the order
	 * they were seen: unsafe paths, unknown entries, directories and duplicates.
	 */
	data class ReadResult(val json: String, val files: Map<String, ByteArray>, val skipped: List<String>)

	/**
	 * Write [json] and [files] as a zip into [output].
	 *
	 * Closing the `ZipOutputStream` closes [output]: it was opened for exactly
	 * this one write, and leaving it half-open would make a caller that appends
	 * bytes silently produce an archive with trailing garbage.
	 */
	fun write(output: OutputStream, json: String, files: List<Source>): WriteResult {
		val missing = ArrayList<String>()
		val written = HashSet<String>()
		var fileCount = 0
		var fileBytes = 0L

		ZipOutputStream(BufferedOutputStream(output)).use { zip ->
			zip.putNextEntry(ZipEntry(CONVERSATION_ENTRY))
			zip.write(json.toByteArray(Charsets.UTF_8))
			zip.closeEntry()

			for (source in files) {
				val relative = safeRelativePath(source.path)
				if (relative == null || relative in written) {
					missing += source.path
					continue
				}
				// Read before creating the entry: a failed read must not leave a
				// truncated file inside an otherwise valid archive.
				val bytes = try {
					source.open().use { it.readBytes() }
				} catch (_: Exception) {
					missing += source.path
					continue
				}
				zip.putNextEntry(ZipEntry(FILES_PREFIX + relative))
				zip.write(bytes)
				zip.closeEntry()
				written += relative
				fileCount++
				fileBytes += bytes.size.toLong()
			}
		}

		return WriteResult(files = fileCount, bytes = fileBytes, missing = missing)
	}

	/**
	 * Read a bundle branch: the JSON document and the attachment bytes.
	 *
	 * Returns null when [input] is not a readable zip, when `conversation.json`
	 * is missing, or when any cap is exceeded. A null is deliberately a single
	 * failure signal: a caller that shows "the bundle is damaged or too large"
	 * needs one sentence, not a taxonomy of partial reads.
	 */
	fun read(
		input: InputStream,
		maxJsonBytes: Long = 16L * 1024 * 1024,
		maxFileBytes: Long = 8L * 1024 * 1024,
		maxTotalBytes: Long = 32L * 1024 * 1024,
	): ReadResult? {
		if (maxJsonBytes <= 0L || maxFileBytes <= 0L || maxTotalBytes <= 0L) return null

		var json: String? = null
		val files = LinkedHashMap<String, ByteArray>()
		val skipped = ArrayList<String>()
		var totalBytes = 0L

		try {
			ZipInputStream(BufferedInputStream(input)).use { zip ->
				while (true) {
					val entry = zip.nextEntry ?: break
					if (entry.isDirectory) {
						skipped += entry.name
						zip.closeEntry()
						continue
					}
					val name = entry.name
					val accepted = when {
						name == CONVERSATION_ENTRY -> {
							if (json != null) {
								false
							} else {
								val bytes = readEntry(zip, maxJsonBytes, totalBytes, maxTotalBytes)
									?: return null
								totalBytes += bytes.size.toLong()
								json = bytes.toString(Charsets.UTF_8)
								true
							}
						}

						name.startsWith(FILES_PREFIX) -> {
							val relative = safeRelativePath(name.substring(FILES_PREFIX.length))
							if (relative == null || relative in files) {
								false
							} else {
								val bytes = readEntry(zip, maxFileBytes, totalBytes, maxTotalBytes)
									?: return null
								totalBytes += bytes.size.toLong()
								files[relative] = bytes
								if (files.size > MAX_FILE_ENTRIES) return null
								true
							}
						}

						else -> false
					}
					if (!accepted) skipped += name
					zip.closeEntry()
				}
			}
		} catch (_: IOException) {
			return null
		}

		return ReadResult(
			json = json ?: return null,
			files = files,
			skipped = skipped,
		)
	}

	/**
	 * Read the current entry, refusing before it grows past either cap.
	 *
	 * The read is capped by the bytes actually inflated, not the size declared in
	 * the entry header, because the header is attacker-controlled.
	 */
	private fun readEntry(
		zip: ZipInputStream,
		perEntryCap: Long,
		alreadyRead: Long,
		totalCap: Long,
	): ByteArray? {
		val buffer = ByteArray(COPY_BUFFER_BYTES)
		val out = ByteArrayOutputStream()
		var total = alreadyRead
		while (true) {
			val read = zip.read(buffer)
			if (read < 0) break
			if (out.size().toLong() + read > perEntryCap) return null
			total += read
			if (total > totalCap) return null
			out.write(buffer, 0, read)
		}
		return out.toByteArray()
	}
}

/**
 * The path behind a `files/` entry, or null when it is not a safe relative path.
 *
 * Empty, absolute, drive-lettered, backslashed, `.`/`..` and empty-segment paths
 * are all rejected. This is not a filesystem check: it is the rule that keeps an
 * archive from naming a location the caller's workspace resolution would have to
 * refuse later, after the map entry has already been trusted as a key.
 */
internal fun safeRelativePath(path: String): String? {
	if (path.isEmpty()) return null
	if (path.startsWith("/")) return null
	if (path.length >= 2 && path[0].isLetter() && path[1] == ':') return null
	if (path.any { character -> character == '\\' || character.code < 0x20 }) return null
	val segments = path.split('/')
	if (segments.any { segment -> segment.isEmpty() || segment == "." || segment == ".." }) return null
	return path
}
