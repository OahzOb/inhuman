package com.inhuman.ui.chat

/**
 * Whether a tool result reads as machinery rather than prose.
 *
 * The transcript drew every tool result in monospace, including `web_search`
 * snippets and `web_fetch` article text -- the largest body-text areas on the
 * screen, in the one font the visual language reserves for metadata
 * (`AGENTS.md`: "Mono is for metadata, not body text"). The wire inspector
 * already renders long text in sans; this makes the result row agree with it
 * where the text is prose, while structured output keeps the alignment
 * monospace exists for.
 *
 * Deliberately a small, explicit set of shapes rather than a guess from
 * punctuation density: JSON and indented blocks are the cases where columns
 * line up, and everything else is treated as prose that reads better wrapped in
 * the body font.
 */
internal fun toolResultIsMachinery(text: String): Boolean {
	val trimmed = text.trim()
	if (trimmed.isEmpty()) return false
	// Parsed rather than pattern-matched: `[see the notes]` is a sentence with
	// brackets, `{"path":"a.txt"}` is a document, and only the parser can tell
	// them apart without a heuristic that will be wrong for the next tool.
	if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
		if (runCatching { kotlinx.serialization.json.Json.parseToJsonElement(trimmed) }.isSuccess) {
			return true
		}
	}
	// A stack trace, a git diff header or an aligned table: lines indented from
	// the left margin are the other place monospace matters.
	return text.lineSequence().any { line ->
		(line.startsWith("    ") || line.startsWith("\t")) && line.isNotBlank()
	}
}
