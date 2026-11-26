package com.inhuman.ui.backup

import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val BACKUP_FILE_PREFIX = "inhuman-backup-"
internal const val BACKUP_FILE_EXTENSION = ".ibak"

private val BACKUP_TIMESTAMP_FORMAT: DateTimeFormatter =
	DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)

/**
 * Deterministic archive filename for a folder-targeted backup.
 *
 * Every export into the remembered folder creates a new timestamped file rather
 * than overwriting the previous one, so a broken or interrupted sync on the
 * user's folder cannot destroy the last good backup.
 */
fun backupArchiveFileName(
	epochMs: Long,
	zoneId: ZoneId = ZoneId.systemDefault(),
): String {
	val stamp = Instant.ofEpochMilli(epochMs)
		.atZone(zoneId)
		.format(BACKUP_TIMESTAMP_FORMAT)
	return "$BACKUP_FILE_PREFIX$stamp$BACKUP_FILE_EXTENSION"
}

/**
 * Collision-safe candidate name when a directory already contains [baseName].
 *
 * `attempt == 0` is the base name itself; positive attempts insert `" (n)"`
 * before the extension, matching the workspace transfer and download naming
 * convention.
 */
fun backupCandidateFileName(baseName: String, attempt: Int): String {
	val cleaned = baseName.trim().ifEmpty { "inhuman-backup$BACKUP_FILE_EXTENSION" }
	if (attempt <= 0) return cleaned
	val dot = cleaned.lastIndexOf('.')
	return if (dot > 0) {
		cleaned.substring(0, dot) + " ($attempt)" + cleaned.substring(dot)
	} else {
		"$cleaned ($attempt)"
	}
}

/**
 * Human-readable label for a remembered SAF tree URI.
 *
 * SAF tree document IDs are provider-defined (`primary:Sync/inhuman`,
 * `raw:/storage/emulated/0/Backups`, `home:Documents`). Extracting the trailing
 * relative path gives the user a recognizable folder name in Settings without
 * exposing or assuming a filesystem path that only exists on external storage.
 */
fun backupFolderDisplayName(
	uriString: String?,
	treeDocumentId: String? = null,
): String? {
	if (uriString.isNullOrBlank()) return null
	val rawCandidate = treeDocumentId?.takeIf { it.isNotBlank() }
		?: extractTreeIdFromUri(uriString)
		?: uriString
	val decoded = runCatching { URLDecoder.decode(rawCandidate, "UTF-8") }
		.getOrDefault(rawCandidate)
		.trim()
	if (decoded.isEmpty()) return null

	val afterColon = if (':' in decoded) {
		val volume = decoded.substringBefore(':')
		val path = decoded.substringAfter(':').trim('/')
		when {
			path.startsWith("storage/emulated/0/") ->
				path.removePrefix("storage/emulated/0/").trim('/')
			path.isNotEmpty() -> path
			volume.isNotBlank() -> volume
			else -> decoded
		}
	} else {
		decoded.trim('/')
	}
	return afterColon.ifBlank { decoded }
}

private fun extractTreeIdFromUri(uriString: String): String? {
	val marker = "/tree/"
	val index = uriString.indexOf(marker)
	if (index < 0) return null
	val tail = uriString.substring(index + marker.length)
	return tail.substringBefore('/').takeIf { it.isNotBlank() }
}
