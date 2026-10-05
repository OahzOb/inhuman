package com.inhuman.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A dialog that uses the width it is given.
 *
 * ## Why not `AlertDialog`
 *
 * Material's `AlertDialog` caps its own width at a platform default and puts the
 * remainder into margins. Measured on a 411dp phone, the assistant editor came
 * out 714px of 1080 -- **66% of the screen**, with 183px of dead space either
 * side. Every control inside it was already tight; the dialog was what wasted
 * the space, and no amount of spacing work inside could recover it.
 *
 * A dialog is not automatically wrong for being narrow. It is wrong here because
 * the content is wide: a system prompt, a tool list, and rows of chip controls,
 * all of which read better with more room and none of which need a margin.
 *
 * ## Why the width is still bounded on a large screen
 *
 * Filling a phone is right; filling a tablet is not, because a full-width form
 * puts a label and its field a hand's width apart. The cap is
 * [Layout.ReadableMeasure], so the dialog grows with the device up to where a
 * line stops being readable and then stops.
 *
 * ## The actions sit outside the scroll area
 *
 * That is what lets [body] be as long as it likes. Inside, a long tool list
 * would push Save off the bottom, and the only way to keep it visible would be
 * to bound the content -- which is how the previous version ended up hiding
 * half the editor below a fold.
 */
@Composable
fun WideDialog(
	onDismissRequest: () -> Unit,
	title: String,
	confirmLabel: String? = null,
	onConfirm: (() -> Unit)? = null,
	confirmEnabled: Boolean = true,
	dismissLabel: String = "Cancel",
	contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
	/**
	 * How tall the scrolling body may get before it scrolls.
	 *
	 * Needed because the dialog sizes itself to its content, which makes the
	 * body's height unbounded -- and `verticalScroll` inside an unbounded column
	 * does not clip, it *overflows*. Measured: a multi-line text field inside
	 * one drew its own text past its border and over the label below it, which
	 * the screenshot showed as two elements tangled together.
	 *
	 * A bound on the body rather than a `weight`. The dialog should still size
	 * itself to short content, and `weight(1f, fill = false)` in an unbounded
	 * column is what produced the overflow in the first place.
	 */
	maxBodyHeight: Dp = 420.dp,
	body: @Composable ColumnScope.() -> Unit,
) {
	Dialog(
		onDismissRequest = onDismissRequest,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		Surface(
			modifier = Modifier
				// Constrain first, fill second. `fillMaxWidth()` is greedy and
				// consumes the incoming maximum, so with the two swapped the cap
				// was inert and a dialog stretched the full 1280dp of a tablet.
				.widthIn(max = Layout.ReadableMeasure)
				.fillMaxWidth()
				// A margin rather than edge-to-edge: full-bleed would make the
				// dialog indistinguishable from a new screen and lose the sense
				// of returning to whatever is behind it.
				.padding(horizontal = 12.dp, vertical = 24.dp),
			shape = RoundedCornerShape(20.dp),
			color = MaterialTheme.colorScheme.surface,
			tonalElevation = 6.dp,
		) {
			Column(modifier = Modifier.padding(contentPadding)) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleLarge,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.padding(bottom = 10.dp),
				)

				Column(
					modifier = Modifier
						.heightIn(max = maxBodyHeight)
						.verticalScroll(rememberScrollState()),
					verticalArrangement = Arrangement.spacedBy(9.dp),
					content = body,
				)

				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = 10.dp),
					horizontalArrangement = Arrangement.End,
				) {
					TextButton(onClick = onDismissRequest) { Text(dismissLabel) }
					if (confirmLabel != null && onConfirm != null) {
						TextButton(onClick = onConfirm, enabled = confirmEnabled) {
							Text(confirmLabel)
						}
					}
				}
			}
		}
	}
}
