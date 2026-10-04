package com.inhuman.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontFamily

/**
 * Renders assistant text as Markdown.
 *
 * ## Why this is not just `Text(text)`
 *
 * Models answer in Markdown whether or not anyone asked: bold for emphasis,
 * fenced blocks for code, lists for steps, tables for comparisons. Rendering it
 * literally means the user reads asterisks and backticks, which is the
 * difference between an app that looks finished and one that looks like a
 * prototype.
 *
 * ## The streaming case
 *
 * A half-written code fence or an unclosed table is the normal state of a reply
 * in progress, and re-parsing the whole document on every token is both
 * quadratic and visibly janky. The renderer's `rememberMarkdownState` parses
 * asynchronously off the composition and caches, so passing the accumulated
 * text on each recomposition does not mean reparsing it each time.
 *
 * ## Deliberately absent
 *
 * **No LaTeX.** The only viable Android renderer is JLaTeXMath, which is
 * GPLv2, and this app is not open source. Rendering `$x^2$` as literal text is
 * a worse experience than rendering it as maths, and a much better one than
 * shipping a licence problem. **No Mermaid** for the same reason plus weight:
 * it needs a JavaScript engine in a WebView, and a WebView per diagram destroys
 * scroll performance.
 */
@Composable
fun MarkdownText(
	text: String,
	modifier: Modifier = Modifier,
) {
	// Parsing happens off the composition and is retained across updates, so
	// scrolling a long reply does not reparse it.
	val state = rememberMarkdownState(text)

	Markdown(
		markdownState = state,
		modifier = modifier,
		colors = markdownColor(
			text = MaterialTheme.colorScheme.onSurface,
			// Read from the library's own metadata rather than guessed: the
			// function takes exactly these five colours plus `darkTheme` and
			// `alert`, and an invented name does not compile.
			codeBackground = MaterialTheme.colorScheme.surfaceVariant,
			inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant,
			dividerColor = MaterialTheme.colorScheme.outlineVariant,
			tableBackground = MaterialTheme.colorScheme.surface,
		),
		typography = markdownTypography(
			// Body text has to come from the app's type scale rather than the
			// renderer's default: replies are the thing people read, and the
			// Material default line height is tuned for lists and labels.
			text = MaterialTheme.typography.bodyLarge,
			paragraph = MaterialTheme.typography.bodyLarge,
			h1 = MaterialTheme.typography.headlineSmall,
			h2 = MaterialTheme.typography.titleLarge,
			h3 = MaterialTheme.typography.titleMedium,
			code = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
			inlineCode = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
			quote = MaterialTheme.typography.bodyLarge,
		),
	)
}
