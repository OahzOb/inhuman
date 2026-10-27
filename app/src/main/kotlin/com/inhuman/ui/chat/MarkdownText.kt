package com.inhuman.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Table cells that wrap instead of truncating.
 *
 * The renderer's 0.45.0 defaults are `maxLines = 1` and
 * `TextOverflow.Ellipsis` for every cell (`MarkdownTableHeader` /
 * `MarkdownTableRow`). With the library's fixed 160dp column, any cell longer
 * than a few words became "…", while the table itself still scrolled
 * horizontally -- scrolling only helps if there is content left to scroll to.
 *
 * `Int.MAX_VALUE` lets the cell grow downwards, and `Clip` is the belt to that
 * braces: with unlimited lines there is nothing to ellipsize. The rest of the
 * components are the library defaults, except the checkbox: `markdownComponents()`
 * defaults to the base renderer's checkbox, while the m3 `Markdown` entry point
 * supplies its own. Replacing the table must not silently downgrade that.
 */
private val chatMarkdownComponents = markdownComponents(
	checkbox = { model -> MarkdownCheckBox(model.content, model.node, model.typography.text) },
	table = { model ->
		MarkdownTable(
			content = model.content,
			node = model.node,
			style = model.typography.table,
			headerBlock = { content, header, tableWidth, style ->
				MarkdownTableHeader(
					content = content,
					header = header,
					tableWidth = tableWidth,
					style = style,
					maxLines = Int.MAX_VALUE,
					overflow = TextOverflow.Clip,
				)
			},
			rowBlock = { content, row, tableWidth, style ->
				MarkdownTableRow(
					content = content,
					header = row,
					tableWidth = tableWidth,
					style = style,
					maxLines = Int.MAX_VALUE,
					overflow = TextOverflow.Clip,
				)
			},
		)
	},
)

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
 * the last *stable* block boundary. Its `append` does not compare against what
 * it already holds. The app therefore sends accumulated answer snapshots,
 * passes them through [DeltaCursor], and appends only the missing suffix. That
 * makes a late first composition and a queued snapshot safe: replaying an older
 * snapshot yields nothing, while a new one yields exactly the new text.
 *
 * A message loaded from the database has no stream and never will -- there is
 * nothing to be incremental about, because it arrives complete and is parsed
 * once. So the two cases take the two paths, and the choice is made by whether
 * [deltas] is present.
 *
 * The switch does **not** happen at the end of the stream. Clearing [deltas]
 * there created a blank frame: the plain path parses asynchronously, so the new
 * state has nothing to draw for one composition. The finished reply keeps its
 * streaming state -- its content is already the final document and the channel
 * is closed -- until the row is rebuilt from the database (reopen, branch
 * switch, reload). A database row has no deltas and takes the plain path once,
 * before anything is on screen.
 *
 * ## Deliberately absent
 *
 * **No Mermaid** for weight rather than licence: it needs a JavaScript engine
 * in a WebView, and a WebView per diagram destroys scroll performance.
 *
 * ## LaTeX
 *
 * [LatexMarkdown] rewrites math into image links and
 * [rememberMathImageTransformer] draws them with the bundled JLaTeXMath fonts.
 * The rewrite runs over the accumulated text on both paths, so a formula is
 * rendered the same way while it streams and after it is stored. An unfinished
 * formula is withheld rather than shown raw and then replaced; see the doc on
 * `LatexMarkdown`.
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
	/** Whether more text can still arrive; flushes an unfinished formula at the end. */
	isStreaming: Boolean = false,
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

	val transformer = rememberMathImageTransformer()

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
		// question as what the reply contains. It tracks the **rewritten**
		// Markdown (math as image links), not the raw reply.
		val appended = remember(deltas) { DeltaCursor() }
		val finished = remember(deltas) { mutableStateOf(!isStreaming) }
		val appendLock = remember(deltas) { Mutex() }

		// One lock around "advance the cursor and append". The final flush and
		// the channel loop are two coroutines; without it, the flush could
		// append the tail before a snapshot's prefix, and the append-only
		// renderer would show the tail twice in the wrong order.
		suspend fun appendRaw(raw: String, atEnd: Boolean) {
			appendLock.withLock {
				// A stale snapshot must not be appended after the final text:
				// its rewrite is only a prefix, and `DeltaCursor` reads a
				// shorter buffer as a fresh document.
				if (finished.value && !atEnd) return
				val rewritten = LatexMarkdown.transform(raw, finished = atEnd)
				appended.next(rewritten)?.let { streaming.append(it) }
			}
		}

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
			// Appending the rewritten `text` before the loop closes that gap,
			// and it is safe in both directions: `DeltaCursor` only ever emits
			// what the renderer does not already have. The queued snapshots are
			// reconciled the same way.
			appendRaw(text, atEnd = finished.value)

			// Sequential and lossless: `append` suspends, so the loop cannot
			// run ahead of the parser. The channel carries accumulated
			// snapshots, not slices.
			for (snapshot in deltas) {
				if (finished.value) break
				appendRaw(snapshot, atEnd = false)
			}
		}

		// The stream ending is the only moment the rewrite may flush an
		// unfinished `$...` as literal text. Setting the flag before taking the
		// lock is what makes the loop drop any snapshot it has not appended yet.
		LaunchedEffect(isStreaming) {
			if (!isStreaming) {
				finished.value = true
				appendRaw(text, atEnd = true)
			}
		}

		// Positional, not named: the m3 artifact carries four `Markdown`
		// overloads (String, MarkdownState, State, StreamingMarkdownState) and
		// the compiler rejected the named form here once already. Only
		// `components` and `imageTransformer` are named, and the first
		// argument's type selects the streaming overload.
		Markdown(
			streaming,
			colors,
			typography,
			modifier,
			imageTransformer = transformer,
			components = chatMarkdownComponents,
		)
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
	val painted = remember(text) { LatexMarkdown.transform(text, finished = true) }
	val state = rememberMarkdownState(content = painted, retainState = true)

	Markdown(
		markdownState = state,
		modifier = modifier,
		colors = colors,
		typography = typography,
		imageTransformer = transformer,
		components = chatMarkdownComponents,
	)
}
