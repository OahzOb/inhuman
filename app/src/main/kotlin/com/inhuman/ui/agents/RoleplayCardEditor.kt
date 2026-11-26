package com.inhuman.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inhuman.R
import com.inhuman.core.design.Layout
import com.inhuman.core.design.LongTextField
import com.inhuman.core.design.LongTextFieldLabels
import com.inhuman.core.design.WideDialog
import com.inhuman.core.engine.roleplay.RoleplayCard
import com.inhuman.core.engine.roleplay.RoleplayField
import com.inhuman.core.engine.roleplay.RoleplayLimitError
import com.inhuman.core.engine.roleplay.RoleplayLimits

/**
 * The character card behind one assistant.
 *
 * ## Why this is a dialog of its own
 *
 * A card is seven prose fields plus three sampling numbers. Put in the assistant
 * editor it would double the length of a form that already scrolls past the
 * fold, and it would make the card's own validation (per-field and total limits,
 * sampling ranges) look like part of the general Save. A card is also edited
 * deliberately, once, not read on every visit -- unlike the prompt, which is
 * shown in full because hiding it is what makes an assistant unexplainable.
 *
 * ## Why the dialog edits a copy
 *
 * Nothing here reaches the assistant draft until Save, so Cancel discards a
 * half-typed card instead of leaving it to be written by the assistant's Save.
 * Remove is a second, confirmed exit: it writes an empty card, and encoding an
 * empty card is `""` -- the value that turns role-play off for this assistant
 * without touching its prompt or tools.
 *
 * ## Why the limits live here
 *
 * [RoleplayLimits.cardErrors] is enforced before the card can be saved, because
 * the card is prepended to every request in every scene with this character. A
 * card that broke the limit would push recent turns out of the context window
 * and present as the character forgetting, so saving one is not offered rather
 * than silently truncating it.
 */
@Composable
internal fun RoleplayCardEditor(
	card: RoleplayCard,
	onSave: (RoleplayCard) -> Unit,
	onRemove: () -> Unit,
	onDismiss: () -> Unit,
	/**
	 * Export the card currently shown (including unsaved edits) to a workspace
	 * file; null hides the action.
	 */
	onExport: ((RoleplayCard) -> Unit)? = null,
) {
	var edited by remember { mutableStateOf(card) }
	var temperatureText by remember { mutableStateOf(card.temperature?.toString().orEmpty()) }
	var topPText by remember { mutableStateOf(card.topP?.toString().orEmpty()) }
	var maxTokensText by remember { mutableStateOf(card.maxTokens?.toString().orEmpty()) }
	var confirmingRemove by remember { mutableStateOf(false) }

	val temperature = parseDoubleInput(temperatureText, 0.0..RoleplayLimits.TEMPERATURE_MAX)
	val topP = parseDoubleInput(topPText, 0.0..RoleplayLimits.TOP_P_MAX)
	val maxTokens = parseIntInput(
		maxTokensText,
		RoleplayLimits.MAX_TOKENS_MIN..RoleplayLimits.MAX_TOKENS_MAX,
	)
	// A field that does not parse contributes null rather than its last good
	// value: Save is blocked while it is invalid, and saving a number the field
	// no longer shows would be worse than not saving.
	val proposed = edited.copy(
		temperature = temperature.value,
		topP = topP.value,
		maxTokens = maxTokens.value,
	)
	val errors = RoleplayLimits.cardErrors(proposed)
	val cardChars = RoleplayLimits.cardChars(proposed)
	val totalOver = errors.any { it.field == RoleplayField.CardTotal }
	val canSave = !temperature.invalid && !topP.invalid && !maxTokens.invalid && errors.isEmpty()

	// The card's words, taken from the same labels the errors use, so a field
	// that is too long names itself the way the user sees it.
	val labels = LongTextFieldLabels(
		// The card fields have no Import action, so this one is never drawn;
		// `LongTextFieldLabels` has no defaults and the shared component has no
		// resources of its own, so the assistant prompt's words are reused.
		importAction = stringResource(R.string.agents_prompt_import_action),
		writeAction = stringResource(R.string.agents_prompt_write_action),
		editAction = stringResource(R.string.agents_prompt_edit_action),
		doneAction = stringResource(R.string.common_done),
		cancelAction = stringResource(R.string.common_cancel),
	)

	WideDialog(
		onDismissRequest = onDismiss,
		title = stringResource(R.string.roleplay_card_title),
		confirmLabel = stringResource(R.string.roleplay_save),
		dismissLabel = stringResource(R.string.roleplay_cancel),
		onConfirm = { onSave(proposed) },
		confirmEnabled = canSave,
		maxBodyHeight = 480.dp,
	) {
		Text(
			text = stringResource(R.string.roleplay_card_intro),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = stringResource(R.string.roleplay_hint_placeholders),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)

		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

		CardTextField(
			label = stringResource(R.string.roleplay_field_description),
			value = edited.description,
			error = errors.firstOrNull { it.field == RoleplayField.Description },
			labels = labels,
			onValueChange = { v -> edited = edited.copy(description = v) },
		)
		CardTextField(
			label = stringResource(R.string.roleplay_field_personality),
			value = edited.personality,
			error = errors.firstOrNull { it.field == RoleplayField.Personality },
			labels = labels,
			onValueChange = { v -> edited = edited.copy(personality = v) },
		)
		CardTextField(
			label = stringResource(R.string.roleplay_field_scenario),
			value = edited.scenario,
			error = errors.firstOrNull { it.field == RoleplayField.Scenario },
			labels = labels,
			onValueChange = { v -> edited = edited.copy(scenario = v) },
		)
		CardTextField(
			label = stringResource(R.string.roleplay_field_greeting),
			value = edited.greeting,
			placeholder = stringResource(R.string.roleplay_hint_greeting),
			error = errors.firstOrNull { it.field == RoleplayField.Greeting },
			labels = labels,
			onValueChange = { v -> edited = edited.copy(greeting = v) },
		)
		CardTextField(
			label = stringResource(R.string.roleplay_field_example),
			value = edited.exampleDialogue,
			placeholder = stringResource(R.string.roleplay_hint_example),
			error = errors.firstOrNull { it.field == RoleplayField.ExampleDialogue },
			labels = labels,
			onValueChange = { v -> edited = edited.copy(exampleDialogue = v) },
		)
		CardTextField(
			label = stringResource(R.string.roleplay_field_boundaries),
			value = edited.boundaries,
			placeholder = stringResource(R.string.roleplay_hint_boundaries),
			error = errors.firstOrNull { it.field == RoleplayField.Boundaries },
			labels = labels,
			onValueChange = { v -> edited = edited.copy(boundaries = v) },
		)
		CardTextField(
			label = stringResource(R.string.roleplay_field_author_note),
			value = edited.authorNote,
			error = errors.firstOrNull { it.field == RoleplayField.AuthorNote },
			labels = labels,
			onValueChange = { v -> edited = edited.copy(authorNote = v) },
		)

		// The live total is the same string the over-limit error uses, so the
		// number the user watches is the number that blocks Save. It turns red
		// instead of printing a second message with the same numbers in it, and
		// the per-field errors are already beside their fields.
		Text(
			text = stringResource(
				R.string.roleplay_limit_over,
				stringResource(R.string.roleplay_field_card_total),
				cardChars,
				RoleplayLimits.MAX_CARD_CHARS,
			),
			style = MaterialTheme.typography.labelLarge,
			color = if (totalOver) {
				MaterialTheme.colorScheme.error
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant
			},
		)

		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

		Text(
			text = stringResource(R.string.roleplay_hint_sampling),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
		) {
			SamplingField(
				label = stringResource(R.string.roleplay_temperature),
				value = temperatureText,
				invalid = temperature.invalid,
				// The outline says the text is wrong; the range says what to type.
				rangeHint = "0.0 – ${RoleplayLimits.TEMPERATURE_MAX}",
				keyboardType = KeyboardType.Decimal,
				onValueChange = { temperatureText = it },
				modifier = Modifier.weight(1f),
			)
			SamplingField(
				label = stringResource(R.string.roleplay_top_p),
				value = topPText,
				invalid = topP.invalid,
				rangeHint = "0.0 – ${RoleplayLimits.TOP_P_MAX}",
				keyboardType = KeyboardType.Decimal,
				onValueChange = { topPText = it },
				modifier = Modifier.weight(1f),
			)
		}
		SamplingField(
			label = stringResource(R.string.roleplay_max_tokens),
			value = maxTokensText,
			invalid = maxTokens.invalid,
			rangeHint = "${RoleplayLimits.MAX_TOKENS_MIN} – ${RoleplayLimits.MAX_TOKENS_MAX}",
			keyboardType = KeyboardType.Number,
			onValueChange = { maxTokensText = it },
			modifier = Modifier.fillMaxWidth(),
		)

		if (!proposed.isBlank) {
			HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				if (onExport != null) {
					TextButton(onClick = { onExport(proposed) }) {
						Text(stringResource(R.string.roleplay_export_action))
					}
				} else {
					Spacer(Modifier)
				}
				if (!card.isBlank) {
					TextButton(onClick = { confirmingRemove = true }) {
						Text(
							text = stringResource(R.string.roleplay_remove),
							color = MaterialTheme.colorScheme.error,
						)
					}
				}
			}
		}
	}

	if (confirmingRemove) {
		AlertDialog(
			onDismissRequest = { confirmingRemove = false },
			// The sentence is the body, not the title: as a title it would be a
			// three-line paragraph set at headline size on a phone.
			title = { Text(stringResource(R.string.roleplay_remove)) },
			text = { Text(stringResource(R.string.roleplay_remove_confirm)) },
			confirmButton = {
				TextButton(
					onClick = {
						confirmingRemove = false
						onRemove()
					},
				) {
					Text(
						text = stringResource(R.string.roleplay_remove),
						color = MaterialTheme.colorScheme.error,
					)
				}
			},
			dismissButton = {
				TextButton(onClick = { confirmingRemove = false }) {
					Text(stringResource(R.string.roleplay_cancel))
				}
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

/**
 * One prose field, edited in [LongTextField]'s dialog.
 *
 * The same shape as the system prompt's editor: a capped preview in the form and
 * the whole text in a field with room. An `OutlinedTextField` bounded by height
 * paints past its own border instead of clipping, which is why the card's long
 * fields do not use one.
 */
@Composable
private fun CardTextField(
	label: String,
	value: String,
	labels: LongTextFieldLabels,
	onValueChange: (String) -> Unit,
	error: RoleplayLimitError? = null,
	placeholder: String = "",
) {
	Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed)) {
		LongTextField(
			value = value,
			onValueChange = onValueChange,
			label = label,
			// The field's own name is the only title this dialog has; a separate
			// title string per field would be seven resources that repeat it.
			dialogTitle = label,
			labels = labels,
			placeholder = placeholder,
			previewLines = 3,
			textStyle = MaterialTheme.typography.bodyMedium,
		)
		// Beside the field rather than in one list at the bottom: the seventh
		// field's limit error would otherwise be eight rows from the field that
		// caused it.
		error?.let {
			Text(
				text = stringResource(R.string.roleplay_limit_over, label, it.actual, it.max),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
			)
		}
	}
}

/** A single-line numeric field whose empty text means "use the default". */
@Composable
private fun SamplingField(
	label: String,
	value: String,
	invalid: Boolean,
	rangeHint: String,
	keyboardType: KeyboardType,
	onValueChange: (String) -> Unit,
	modifier: Modifier = Modifier,
) {
	OutlinedTextField(
		value = value,
		onValueChange = onValueChange,
		label = {
			Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
		},
		singleLine = true,
		isError = invalid,
		supportingText = if (invalid) {
			{ Text(stringResource(R.string.roleplay_sampling_range, rangeHint)) }
		} else {
			null
		},
		keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
		modifier = modifier,
	)
}

/** A parsed sampling field: blank is null and valid; anything else must be in range. */
private data class NumberInput<T : Number>(val value: T?, val invalid: Boolean)

private fun parseDoubleInput(text: String, range: ClosedFloatingPointRange<Double>): NumberInput<Double> {
	val trimmed = text.trim()
	if (trimmed.isEmpty()) return NumberInput(value = null, invalid = false)
	val parsed = trimmed.toDoubleOrNull()
	return NumberInput(parsed, invalid = parsed == null || parsed !in range)
}

private fun parseIntInput(text: String, range: IntRange): NumberInput<Int> {
	val trimmed = text.trim()
	if (trimmed.isEmpty()) return NumberInput(value = null, invalid = false)
	val parsed = trimmed.toIntOrNull()
	return NumberInput(parsed, invalid = parsed == null || parsed !in range)
}

/**
 * The export dialog opened from the card editor.
 *
 * The card is already captured in [CardExportDraft]; this dialog only chooses a
 * destination and shows the result. The failure text is shown in place, so a
 * retry does not need the user to reopen the editor.
 */
@Composable
internal fun CardExportDialog(
	draft: CardExportDraft,
	onChange: ((CardExportDraft) -> CardExportDraft) -> Unit,
	onExport: () -> Unit,
	onDismiss: () -> Unit,
) {
	WideDialog(
		onDismissRequest = onDismiss,
		title = stringResource(R.string.roleplay_export_title),
		confirmLabel = stringResource(R.string.roleplay_export_confirm),
		onConfirm = onExport,
		confirmEnabled = draft.workspaces.isNotEmpty() &&
			!draft.busy &&
			draft.savedPath == null,
		dismissLabel = stringResource(R.string.common_done),
		maxBodyHeight = 360.dp,
	) {
		Text(
			text = stringResource(R.string.roleplay_export_intro),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		if (draft.workspaces.isEmpty()) {
			Text(
				text = stringResource(R.string.roleplay_export_no_workspaces),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.error,
			)
		} else {
			Text(
				text = stringResource(R.string.roleplay_export_workspace),
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			draft.workspaces.forEach { workspace ->
				FilterChip(
					selected = workspace.id == draft.selectedWorkspaceId,
					onClick = {
						onChange { it.copy(selectedWorkspaceId = workspace.id, error = null) }
					},
					modifier = Modifier.fillMaxWidth(),
					label = {
						Text(
							text = workspace.name,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					},
				)
			}
			OutlinedTextField(
				value = draft.folderName,
				onValueChange = { value -> onChange { it.copy(folderName = value, error = null) } },
				label = { Text(stringResource(R.string.roleplay_export_folder)) },
				singleLine = true,
				supportingText = { Text(stringResource(R.string.roleplay_export_hint)) },
				modifier = Modifier.fillMaxWidth(),
			)
		}
		draft.savedPath?.let { path ->
			Text(
				text = stringResource(R.string.roleplay_export_done, path),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.primary,
			)
		}
		draft.error?.let { message ->
			Text(
				text = message,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.error,
			)
		}
	}
}
