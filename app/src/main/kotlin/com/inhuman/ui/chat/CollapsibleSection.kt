package com.inhuman.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.design.Layout
import com.inhuman.ui.common.PaidBadge
import androidx.compose.ui.unit.dp

/**
 * A part of a reply that is available but not in the way.
 *
 * ## Why reasoning and tool calls are collapsed
 *
 * Both are working-out rather than the answer. A model that thinks for a
 * paragraph before answering pushes the answer off the screen, and a turn that
 * searched eight times produces eight blocks of results -- so an uncollapsed
 * transcript is mostly machinery with the reply buried in it.
 *
 * Collapsing is not hiding. The header says what is inside and how much of it,
 * and one tap opens it. That distinction matters for reasoning in particular:
 * a user checking whether the model understood the question needs to be able to
 * read it, and a user who does not care should not have to scroll past it.
 *
 * ## Why the open state is remembered across rotation
 *
 * `rememberSaveable` rather than `remember`: someone who opened a long reasoning
 * block and then rotated the phone has expressed what they want, and having it
 * snap shut is the kind of small betrayal that makes an app feel careless.
 */
@Composable
fun CollapsibleSection(
	/** One line describing what is inside, e.g. "Thought for a moment". */
	summary: String,
	/** Optional right-aligned detail, e.g. a duration or a count. */
	trailing: String? = null,
	/**
	 * Marks a section whose content was fetched from a paid service.
	 *
	 * A tool row is the place a user actually sees the tool being used, so the
	 * cost belongs here too, not only in the picker where the tool was chosen.
	 */
	paid: Boolean = false,
	/**
	 * Starts open, for content that is worth seeing without asking.
	 *
	 * Only read on first composition, like any default: a section that reopened
	 * itself on every recomposition would fight the user's tap.
	 */
	initiallyExpanded: Boolean = false,
	/**
	 * Whether this section is the live one, or null to leave it to the user.
	 *
	 * While a turn runs, the newest working section stays open and the previous
	 * one closes as soon as anything newer appears. That is a *transition*, not
	 * a permanent policy: the effect runs only when this value changes, so a
	 * user who opens an old section keeps it open, and a user who closes the
	 * live one does not have it reopened on the next token.
	 */
	autoExpand: Boolean? = null,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	var expanded by rememberSaveable { mutableStateOf(autoExpand ?: initiallyExpanded) }
	LaunchedEffect(autoExpand) {
		autoExpand?.let {
			if (expanded != it) {
				// Only the live sections get this line, and only when the state
				// actually changes: opening an old transcript would otherwise
				// log one "false" per section. It makes "did the accordion open
				// and close?" answerable from the diagnostic log rather than a
				// screenshot race.
				Diag.d("ui", "section '$summary' autoExpand=$it")
				expanded = it
			}
		}
	}

	Column(
		modifier = modifier
			.fillMaxWidth()
			.background(
				MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
				RoundedCornerShape(8.dp),
			)
			// Animates the open and close rather than jumping, so it reads as
			// the same object changing shape rather than as content appearing.
			.animateContentSize(),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clickable { expanded = !expanded }
				.padding(
					horizontal = 8.dp,
					vertical = Layout.Space.CollapsedPadding,
				),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Icon(
				imageVector = if (expanded) {
					Icons.Filled.KeyboardArrowDown
				} else {
					// The AutoMirrored variant, so the arrow points the right
					// way under an RTL layout: a disclosure triangle is one of
					// the few glyphs whose meaning is directional.
					Icons.AutoMirrored.Filled.KeyboardArrowRight
				},
				contentDescription = if (expanded) "Collapse" else "Expand",
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(16.dp),
			)
			Text(
				text = summary,
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				// One line, always.
				//
				// A wrapped summary is what made these rows tall: "web_search —
				// latest Python version release — 5 results" ran to two lines on
				// a phone and turned a one-line index entry into a 53dp block.
				// Truncated, the row is a fixed height and a list of them reads
				// as a list -- which is the whole point of collapsing them.
				//
				// Nothing is lost: the full text is one tap away, and the tail of
				// a truncated summary is the part that says least.
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			if (paid) PaidBadge()
			trailing?.let {
				Text(
					text = it,
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.outline,
				)
			}
		}

		if (expanded) {
			Column(
				modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
			) {
				content()
			}
		}
	}
}

/**
 * Reasoning text, styled as an aside.
 *
 * Monospace and dimmed on purpose. It is not the answer and should not look like
 * one -- a reader skimming should be able to tell at a glance which parts they
 * asked for.
 */
@Composable
fun ReasoningBody(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.bodyMedium,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		fontFamily = FontFamily.Monospace,
	)
}
