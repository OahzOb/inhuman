package com.inhuman.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A long piece of text, previewed in a form and edited in a dialog.
 *
 * ## Why not just a taller text field
 *
 * `OutlinedTextField` with a height constraint does not clip its content -- it
 * paints past its own border. Measured in the assistant editor: a six-line
 * system prompt inside a 150dp field drew its last line through the border and
 * over the caption below, and the field's own bottom edge cut a line of text in
 * half. Making the field taller does not fix it, only moves where the overrun
 * happens.
 *
 * The shape of the problem is that a form is the wrong place for a long text.
 * A form should show enough to recognise what is there and let the thing be
 * edited somewhere with room.
 *
 * ## Why the preview is capped rather than scrollable
 *
 * A scrollable preview inside a scrollable dialog is two scrolls competing for
 * the same gesture, and the inner one wins -- which is how a form ends up
 * impossible to scroll past its own preview. A cap plus an ellipsis says "there
 * is more, tap to see it" without taking the gesture.
 *
 * @param previewLines how much to show before the ellipsis.
 */
@Composable
fun LongTextField(
	value: String,
	onValueChange: (String) -> Unit,
	label: String,
	dialogTitle: String,
	placeholder: String = "",
	previewLines: Int = 4,
	/** Applied to the editing field, so a monospace prompt edits as monospace. */
	textStyle: TextStyle = LocalTextStyle.current,
	previewMaxHeight: Dp = 96.dp,
	modifier: Modifier = Modifier,
) {
	var editing by remember { mutableStateOf(false) }

	Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = label,
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			TextButton(onClick = { editing = true }) {
				Text(
					text = if (value.isBlank()) "Write" else "Edit",
					style = MaterialTheme.typography.labelLarge,
				)
			}
		}

		Column(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(max = previewMaxHeight)
				.background(
					MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
					RoundedCornerShape(10.dp),
				)
				.border(
					1.dp,
					MaterialTheme.colorScheme.outlineVariant,
					RoundedCornerShape(10.dp),
				)
				.clickable { editing = true }
				.padding(horizontal = 12.dp, vertical = 10.dp),
		) {
			if (value.isBlank()) {
				Text(
					text = placeholder,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.outline,
				)
			} else {
				Text(
					text = value,
					style = textStyle,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = previewLines,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}

	if (editing) {
		WideDialog(
			onDismissRequest = { editing = false },
			title = dialogTitle,
			confirmLabel = "Done",
			onConfirm = { editing = false },
			// The body is the field itself, so the dialog's own scroll would be
			// a second scroll over the same text.
			maxBodyHeight = 480.dp,
		) {
			OutlinedTextField(
				value = value,
				onValueChange = onValueChange,
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(min = 280.dp),
				textStyle = textStyle,
				placeholder = {
					Text(
						text = placeholder,
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.outline,
					)
				},
			)
			Text(
				text = "${value.length} characters",
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.outline,
			)
		}
	}
}
