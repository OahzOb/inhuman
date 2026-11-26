package com.inhuman.ui.settings

/**
 * Whether a settings search matches one section.
 *
 * Extracted because the match is the whole behaviour a user experiences as
 * "search cannot find it": the code, title and summary describe a section in a
 * sentence, and a sentence cannot name every control -- "音色", "B 站",
 * "地址簿" and "Tailscale" all live inside sections whose prose never says the
 * word. [keywords] carries those names, and this predicate is the only place
 * that decides what the search covers, so `SettingsSearchTest` can pin it
 * without a composition.
 *
 * Case-insensitive on purpose (an ASCII keyword typed in lower case must match
 * the resource's capitalised form); the query is trimmed because a leading
 * space from a paste otherwise matches nothing.
 */
internal fun settingsSectionMatches(
	query: String,
	code: String,
	title: String,
	summary: String,
	keywords: String,
): Boolean {
	val needle = query.trim()
	if (needle.isEmpty()) return true
	return listOf(code, title, summary, keywords).any {
		it.contains(needle, ignoreCase = true)
	}
}
