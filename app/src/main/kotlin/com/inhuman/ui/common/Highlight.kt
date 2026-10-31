package com.inhuman.ui.common

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * The search term, marked inside a result snippet.
 *
 * A snippet is cut around the match, so the match is usually in the middle of
 * the line; without a mark the user has to re-read the sentence to find why it
 * was returned. The style is weight only: the caller's `Text` colour still
 * applies, and a background highlight would fight the theme in both schemes.
 *
 * Matching is `ignoreCase` and literal, not a regex: the query is whatever the
 * user typed, and a `(` must not crash the result list.
 */
fun highlightSnippet(text: String, query: String): AnnotatedString {
	val needle = query.trim()
	if (needle.isEmpty()) return AnnotatedString(text)
	val builder = AnnotatedString.Builder(text)
	var start = text.indexOf(needle, startIndex = 0, ignoreCase = true)
	while (start >= 0) {
		builder.addStyle(
			SpanStyle(fontWeight = FontWeight.SemiBold),
			start,
			start + needle.length,
		)
		start = text.indexOf(needle, startIndex = start + needle.length, ignoreCase = true)
	}
	return builder.toAnnotatedString()
}
