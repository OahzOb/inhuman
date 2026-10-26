package com.inhuman.ui.common

/**
 * A document name the user can pick in the system file chooser.
 *
 * Android's `CreateDocument` accepts what it is given, but a title can contain
 * `/`, `:`, a newline or an emoji sequence; the first two make a name that the
 * filesystem or the receiving app cannot use. The fallback is supplied by the
 * caller so it can be localized; the extension is appended here so every export
 * route produces the same shape.
 */
fun exportFileName(title: String, extension: String): String {
	val cleaned = title
		.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), " ")
		.replace(Regex("\\s+"), " ")
		.trim(' ', '.')
		.take(60)
		.trim(' ', '.')
		.ifBlank { "conversation" }
	return if (extension.startsWith(".")) "$cleaned$extension" else "$cleaned.$extension"
}