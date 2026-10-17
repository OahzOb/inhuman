package com.inhuman.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import kotlinx.coroutines.channels.Channel

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
 * ## The two paths, and why there are two
 *
 * A reply being streamed arrives a few characters at a time, and parsing the
 * whole document on every arrival is quadratic: the parser re-reads the whole
 * prefix once per token, and the AST it produces is replaced wholesale so the
 * message re-renders from the top several times a second.
 *
 * The renderer ships a genuinely incremental path for exactly this --
 * `StreamingMarkdownStateImpl`, which appends into an
 * `org.intellij.markdown.parser.StreamingMarkdownFile` and only reparses from
 * the last *stable* block boundary. It consumes a `Flow<String>` of **deltas**;
 * `collectAsStreamingMarkdownState` appends every emission with no inspection,
 * so feeding it the accumulated text would duplicate it. That is the whole
 * reason the view model now publishes a delta flow alongside the text it keeps
 * for persistence: the text is what gets stored, the deltas are what gets
 * parsed.
 *
 * A message loaded from the database has no deltas and never will -- there is
 * nothing to be incremental about, because it arrives complete and is parsed
 * once. So the two cases take the two paths, and the choice is made by whether
 * [deltas] is present.
 *
 * The switch happens once per reply, when the stream ends: the incremental
 * state holds exactly the text that was appended, so the plain renderer
 * produces the same document and the swap is not visible. That is a claim about
 * two renderers agreeing, so it is one of the things to look at on a device
 * rather than to assume.
 *
 * ## Deliberately absent
 *
 * **No LaTeX.** The only viable Android renderer is JLaTeXMath; the licence is
 * not the obstacle (linking exception), but its fonts are GPLv2/OFL/Knuth and
 * shipping them means maintaining a `THIRD-PARTY-NOTICES` file. Rendering
 * `$x^2$` as literal text is worse than maths, and better than that upkeep.
 * **No Mermaid** for weight rather than licence: it needs a JavaScript engine
 * in a WebView, and a WebView per diagram destroys scroll performance.
 */
@Composable
fun MarkdownText(
	text: String,
	modifier: Modifier = Modifier,
	/**
	 * The deltas this message is still arriving in, or null once it is
	 * complete. Non-null selects the incremental parser.
	 */
	deltas: Channel<String>? = null,
) {
	val colors = markdownColor(
		text = MaterialTheme.colorScheme.onSurface,
		// Read from the library's own metadata rather than guessed: the
		// function takes exactly these five colours plus `darkTheme` and
		// `alert`, and an invented name does not compile.
		codeBackground = MaterialTheme.colorScheme.surfaceVariant,
		inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant,
		dividerColor = MaterialTheme.colorScheme.outlineVariant,
		tableBackground = MaterialTheme.colorScheme.surface,
	)
	val typography = markdownTypography(
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
	)

	if (deltas != null) {
		// ## Why the app appends the deltas itself
		//
		// The library also ships `collectAsStreamingMarkdownState(flow)`, which
		// does exactly this in one line -- and it is *not callable from Kotlin
		// here*. Its JVM signature is public (verified with `javap` against the
		// 0.45.0 artifact), but the Kotlin metadata the Android variant
		// publishes does not expose it, so the compiler reports an unresolved
		// reference to a function that demonstrably exists. The two functions it
		// is built from are both visible, so this is the same work done
		// explicitly rather than a reimplementation.
		val streaming = rememberStreamingMarkdownState()
		// Tracks what *this* composition has appended, which is not the same
		// question as what the reply contains.
		val appended = remember(deltas) { DeltaCursor() }
		LaunchedEffect(deltas) {
			// Catch up first, then follow.
			//
			// The incremental state lives in the composition and its only write
			// is `append` -- `StreamingMarkdownState` exposes no way to be told
			// what it should contain, and `rememberStreamingMarkdownState` takes
			// no initial content (both read off the 0.45.0 artifact with
			// `javap`). So a composition rebuilt mid-reply -- a rotation, a
			// configuration change -- starts empty while `text` already holds
			// everything received so far.
			//
			// Appending `text` before the loop closes that gap, and it is safe
			// in both directions: `DeltaCursor` only ever emits what the
			// renderer does not already have, so text that has already been
			// appended produces nothing, and text that has not produces exactly
			// the missing part. Without it, everything received before the
			// rebuild was permanently lost from the bubble -- the deltas still
			// to arrive carry only the *new* text, and `text` was ignored
			// entirely while `deltas != null`.
			appended.next(text)?.let { streaming.append(it) }

			// Sequential and lossless: `append` suspends, so the loop cannot
			// run ahead of the parser, and `Channel` iteration delivers every
			// value in order without dropping any.
			for (delta in deltas) {
				streaming.append(delta)
			}
		}
		// Positional, not named: the m3 artifact carries four `Markdown`
		// overloads (String, MarkdownState, State, StreamingMarkdownState) and
		// naming parameters does not disambiguate them here -- the compiler
		// picked the `MarkdownState` one and rejected the argument. The order
		// is state, colors, typography, modifier.
		Markdown(streaming, colors, typography, modifier)
		return
	}

	// retainState is the fix for the flicker, and leaving it at its default was
	// the bug.
	//
	// Parsing happens off the composition and its result arrives asynchronously.
	// With retainState = false the state clears while the next parse is in
	// flight, so a token arriving between a recomposition and its parse leaves
	// nothing to draw and the bubble blinks -- at token rate, which reads as
	// continuous flickering rather than as one dropped frame. Retaining the
	// previous result means the last complete render stays on screen until the
	// next one is ready.
	//
	// The signature is
	//   rememberMarkdownState(content, lookupLinks, retainState, flavour, parser, …)
	// and everything after `content` defaults to exactly the value that causes
	// the flicker.
	val state = rememberMarkdownState(content = text, retainState = true)

	Markdown(
		markdownState = state,
		modifier = modifier,
		colors = colors,
		typography = typography,
	)
}
