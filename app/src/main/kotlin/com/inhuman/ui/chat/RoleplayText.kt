package com.inhuman.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.inhuman.core.design.Layout

/**
 * The parts of a role-play reply, as the display sees them.
 *
 * The model writes prose; it does not hand us a data structure. What it *does*
 * reliably write, because every RP product and every card author uses them, are
 * the conventions: quoted speech, parenthetical actions, `*...*` actions,
 * `**...**` emphasis and `---` scene breaks. This parser turns those
 * conventions into display spans **without asking the model to change** and
 * without touching the stored message: the text is the record, this is a way of
 * reading it. See `docs/proposals/rp-formatting.md`.
 *
 * ## Why not Markdown
 *
 * [MarkdownText] renders the same markers, but its vocabulary is a document's:
 * headings, lists, code, tables. A scene is speech and stage direction, and
 * Markdown gives both the same body style -- `*she smiles*` is italic and
 * `"Hello"` is plain, which is the one hierarchy a role-play needs inverted.
 * More importantly, italics are weak in Chinese (no true italic glyph, only
 * synthetic slant), so relying on Markdown alone leaves a Chinese scene flat.
 * This layer assigns the hierarchy itself.
 *
 * ## Display-only
 *
 * Nothing here is persisted, requested or exported. The Source view still shows
 * the raw text and the copy button still copies it; the renderer only changes
 * how the same string is painted. That is the same rule as the unclosed-fence
 * repair in `MessageBubble`.
 *
 * ## Assumptions, and what happens when they are wrong
 *
 * The conventions are recognised tolerantly and never guessed from context:
 * an unclosed quote is speech while the reply is still streaming and plain text
 * once it is finished; an unclosed asterisk is always literal; a paragraph that
 * is only `---` is a scene break, but `---` inside a line is not. A reply that
 * uses Markdown block constructs (fences, tables, headings, quotes, lists) is
 * handed to [MarkdownText] instead, because those are documents rather than
 * dialogue; the caller checks that with [hasMarkdownBlocks].
 */

/** How one run of text inside a paragraph should be painted. */
internal enum class RpInline {
	/** Narration and scene description: the default. */
	Plain,

	/** The character's spoken words, inside quotes. */
	Dialogue,

	/** A physical action or expression, in parentheses or `*...*`. */
	Action,

	/** `**...**` emphasis. */
	Emphasis,
}

/**
 * The markup a span was written with, when the markup is not itself content.
 *
 * Asterisks and `**` are notation, not punctuation: every Markdown renderer
 * hides them, and leaving them visible makes a scene look like raw source. The
 * delimiters are dropped at paint time and remain in the stored text, the Source
 * view and the copy buffer. Parentheses and quotation marks stay: they are
 * punctuation a reader expects to see.
 */
internal enum class RpMarkers {
	None,
	Asterisk,
	Bold,
}

/** One run of text with one display role. Adjacent runs never share a role/marker. */
internal data class RpSpan(
	val text: String,
	val inline: RpInline,
	val markers: RpMarkers = RpMarkers.None,
) {
	/** What the reader sees: the span minus markup delimiters, never content. */
	val paintedText: String
		get() = when (markers) {
			RpMarkers.None -> text
			RpMarkers.Asterisk -> text.removePrefix("*").removeSuffix("*")
			RpMarkers.Bold -> text.removePrefix("**").removeSuffix("**")
		}
}

/** A display block: a paragraph of prose, or a scene break. */
internal sealed interface RpBlock {
	data class Paragraph(val spans: List<RpSpan>) : RpBlock
	data object SceneBreak : RpBlock
}

/**
 * The role-play paragraph/spans parser.
 *
 * Pure and total: it never throws on malformed or half-streamed input, and every
 * character of the input lands in exactly one span, so nothing is lost. The only
 * characters not painted are markup delimiters (`*`, `**`), which every Markdown
 * renderer hides too; they stay in the stored text, the Source view and the copy
 * buffer. That split is what makes "display-only" a fact rather than a promise,
 * and it is what [RoleplayTypographyTest] checks.
 */
internal object RoleplayTypography {

	/** A paragraph that is only this much text is a scene transition. */
	private val SCENE_BREAKS = setOf("---", "***", "* * *", "___")

	/**
	 * Quote-open to quote-close. ASCII quotes pair with themselves; the CJK and
	 * typographic pairs are the two forms Chinese and English role-play write.
	 */
	private val QUOTE_CLOSE: Map<Char, Char> = mapOf(
		'"' to '"',
		'“' to '”',
		'「' to '」',
		'『' to '』',
	)

	fun parse(text: String, finished: Boolean): List<RpBlock> {
		if (text.isBlank()) return emptyList()
		val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
		val blocks = mutableListOf<RpBlock>()
		val paragraph = StringBuilder()
		var sawContent = false

		fun flush() {
			if (!sawContent) return
			val line = paragraph.toString()
			val trimmed = line.trim()
			blocks += if (trimmed in SCENE_BREAKS) {
				RpBlock.SceneBreak
			} else {
				RpBlock.Paragraph(tokenize(line, finished))
			}
			paragraph.clear()
			sawContent = false
		}

		for (line in normalized.split('\n')) {
			if (line.isBlank()) {
				flush()
			} else {
				if (sawContent) paragraph.append('\n')
				paragraph.append(line)
				sawContent = true
			}
		}
		flush()
		return blocks
	}

	/**
	 * Whether a reply is a document rather than a scene.
	 *
	 * Used by the caller to fall back to [MarkdownText]. Kept in this file with
	 * the parser so the decision and the parser can be tested together.
	 */
	fun hasMarkdownBlocks(text: String): Boolean = text.lineSequence().any { raw ->
		val line = raw.trimStart()
		line.startsWith("```") ||
			line.startsWith("# ") ||
			line.startsWith("## ") ||
			line.startsWith("> ") ||
			line.startsWith("- ") ||
			line.startsWith("|") ||
			(line.contains(" | ") && line.endsWith(" |"))
	}

	private fun tokenize(text: String, finished: Boolean): List<RpSpan> {
		val spans = mutableListOf<RpSpan>()
		val plain = StringBuilder()

		fun emit(kind: RpInline, value: String, markers: RpMarkers = RpMarkers.None) {
			if (value.isEmpty()) return
			if (plain.isNotEmpty()) {
				appendSpan(spans, RpSpan(plain.toString(), RpInline.Plain))
				plain.clear()
			}
			appendSpan(spans, RpSpan(value, kind, markers))
		}

		fun appendPlain(chars: String) {
			plain.append(chars)
		}

		var i = 0
		while (i < text.length) {
			val c = text[i]

			QUOTE_CLOSE[c]?.let { close ->
				val end = text.indexOf(close, i + 1)
				when {
					end >= 0 -> {
						emit(RpInline.Dialogue, text.substring(i, end + 1))
						i = end + 1
					}
					// Mid-stream: the opening quote has arrived but not the
					// close, so the tail is already clearly speech. Painting it
					// as plain and recolouring on the close would flicker.
					!finished -> {
						emit(RpInline.Dialogue, text.substring(i))
						i = text.length
					}
					else -> {
						appendPlain(c.toString())
						i++
					}
				}
				continue
			}

			if (c == '（' || c == '(') {
				val close = if (c == '（') '）' else ')'
				val end = text.indexOf(close, i + 1)
				when {
					end >= 0 -> {
						emit(RpInline.Action, text.substring(i, end + 1))
						i = end + 1
					}
					!finished -> {
						emit(RpInline.Action, text.substring(i))
						i = text.length
					}
					else -> {
						appendPlain(c.toString())
						i++
					}
				}
				continue
			}

			if (c == '*') {
				// `**...**` is emphasis; `*...*` is an action in every RP
				// convention that uses asterisks at all.
				if (i + 1 < text.length && text[i + 1] == '*') {
					val end = text.indexOf("**", i + 2)
					if (end >= 0) {
						emit(RpInline.Emphasis, text.substring(i, end + 2), RpMarkers.Bold)
						i = end + 2
					} else {
						appendPlain("**")
						i += 2
					}
					continue
				}
				val end = text.indexOf('*', i + 1)
				if (end >= 0) {
					emit(RpInline.Action, text.substring(i, end + 1), RpMarkers.Asterisk)
					i = end + 1
				} else {
					appendPlain("*")
					i++
				}
				continue
			}

			appendPlain(c.toString())
			i++
		}

		if (plain.isNotEmpty()) appendSpan(spans, RpSpan(plain.toString(), RpInline.Plain))
		return spans
	}

	/** Adjacent runs of the same kind and markup are one span; the renderer should not restyle mid-run. */
	private fun appendSpan(spans: MutableList<RpSpan>, span: RpSpan) {
		val last = spans.lastOrNull()
		if (last != null && last.inline == span.inline && last.markers == span.markers) {
			spans[spans.lastIndex] = last.copy(text = last.text + span.text)
		} else {
			spans += span
		}
	}
}

/**
 * Paints one role-play reply with the scene hierarchy.
 *
 * Deliberately quiet: narration is secondary text, speech is the normal reading
 * colour (a scene is mostly people talking), actions are the secondary colour in
 * italics, emphasis is bold, and a scene break is a hairline. A busier design --
 * colour per character, cards per line -- would fight the app's graphite
 * workbench language and small-screen density.
 *
 * It re-parses on every stream snapshot rather than building a second
 * incremental renderer: the parser is a single linear scan with no AST, and a
 * reply is a few kilobytes, so the cost is far below the Markdown renderer's and
 * the live view never has to jump from "plain" to "styled" at the end.
 */
@Composable
internal fun RoleplayText(
	text: String,
	isStreaming: Boolean,
	modifier: Modifier = Modifier,
) {
	val blocks = remember(text, isStreaming) {
		RoleplayTypography.parse(text, finished = !isStreaming)
	}
	val narrationColor = MaterialTheme.colorScheme.onSurfaceVariant
	val speechColor = MaterialTheme.colorScheme.onSurface

	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
	) {
		blocks.forEach { block ->
			when (block) {
				RpBlock.SceneBreak -> HorizontalDivider(
					modifier = Modifier.padding(vertical = Layout.Space.BetweenCollapsed),
					color = MaterialTheme.colorScheme.outlineVariant,
				)
				is RpBlock.Paragraph -> {
					val styled = buildAnnotatedString {
						block.spans.forEach { span ->
							val style = when (span.inline) {
								RpInline.Plain -> SpanStyle(color = narrationColor)
								RpInline.Dialogue -> SpanStyle(
									color = speechColor,
									fontWeight = FontWeight.Medium,
								)
								RpInline.Action -> SpanStyle(
									color = narrationColor,
									fontStyle = FontStyle.Italic,
								)
								RpInline.Emphasis -> SpanStyle(
									color = speechColor,
									fontWeight = FontWeight.Bold,
								)
							}
							withStyle(style) { append(span.paintedText) }
						}
					}
					Text(
						text = styled,
						style = MaterialTheme.typography.bodyLarge,
						color = narrationColor,
					)
				}
			}
		}
	}
}
