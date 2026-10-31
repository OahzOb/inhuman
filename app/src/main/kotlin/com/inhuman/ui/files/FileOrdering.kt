package com.inhuman.ui.files

/**
 * How the file list is ordered.
 *
 * Directories always come first regardless of this: a folder is a place, not a
 * file, and sorting by size or date would otherwise scatter the folders through
 * the list. The direction is fixed per option rather than a second control:
 * "newest first" and "largest first" are what a person means, and a separate
 * ascending toggle on a phone top bar is a control nobody asked for.
 */
internal enum class FileSort {
	Name,
	Modified,
	Size,
}

/**
 * The rows the browser should show: filtered by [query], then ordered by [sort].
 *
 * Pure and separate from the composable so the ordering rules are testable
 * without a device. The filter is a case-insensitive substring match on the
 * visible name, not the path: the user is looking at one folder.
 */
internal fun orderedFiles(
	entries: List<FileEntryUi>,
	query: String,
	sort: FileSort,
): List<FileEntryUi> {
	val needle = query.trim()
	val filtered = if (needle.isEmpty()) {
		entries
	} else {
		entries.filter { it.name.contains(needle, ignoreCase = true) }
	}
	val byName = compareBy<FileEntryUi> { it.name.lowercase() }
	val order = when (sort) {
		FileSort.Name -> byName
		FileSort.Modified -> compareByDescending<FileEntryUi> { it.modifiedAt }.then(byName)
		FileSort.Size -> compareByDescending<FileEntryUi> { it.sizeBytes }.then(byName)
	}
	return filtered.sortedWith(compareByDescending<FileEntryUi> { it.isDirectory }.then(order))
}
