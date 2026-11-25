package com.inhuman.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inhuman.R
import com.inhuman.core.design.Hairline
import com.inhuman.core.design.LongTextField
import com.inhuman.core.design.LongTextFieldLabels
import com.inhuman.core.design.WideDialog
import com.inhuman.core.engine.roleplay.RoleplayField
import com.inhuman.core.engine.roleplay.RoleplayLimitError

/**
 * The form in front of a new role-play scene.
 *
 * ## Why this is a dialog over the home screen
 *
 * A scene *is* a conversation: starting one creates a temporary workspace and a
 * transcript, and navigates into it exactly as "New conversation" does. It
 * differs only in what is decided before the first turn -- which character, and
 * what setting the story starts in. That is one step of the create flow, not a
 * destination of its own, so it stays a dialog over the list and the existing
 * `onCreated` callback still does the navigating.
 *
 * ## Why the character list is a snapshot
 *
 * [SceneDraft.characters] is read once, when the dialog opens (see
 * `ConversationsViewModel.openSceneDialog`). A card edited on the assistants
 * screen while this is open must not change which character is being started,
 * and a continuously observed list would do exactly that halfway through
 * typing. Characters the decoder cannot read are not offered: the view model
 * filters with `RoleplayCodec.decodeCard`, so "no characters" here means no
 * usable card rather than no `rpJson`.
 *
 * ## Why the fields use the shared long-field control
 *
 * Every scene field may hold kilobytes (world alone is 4,096 characters), and
 * a multi-line `OutlinedTextField` in a form measures at its content's full
 * height -- which is the overflow the assistant editor already recorded. The
 * shared [LongTextField] previews a couple of lines in the form and edits the
 * full text in its own dialog; [WideDialog]'s body is the scroll here, so five
 * of those previews do not compete for the height.
 *
 * ## Limits
 *
 * Every field is optional, so an empty form is a legal scene. What is not legal
 * is an over-long one: the card and scene are prepended to every request, and
 * `RoleplayLimits.sceneErrors` is checked as the user types. Start is refused
 * while it reports anything; the view model checks again before writing, so a
 * disabled button is the visible consequence rather than the enforcement.
 *
 * @param onDraftChange applies a change to the draft through the view model.
 *   The dialog never holds field state of its own -- the draft is the state, and
 *   a second copy here would survive a cancel.
 */
@Composable
fun NewSceneDialog(
	draft: SceneDraft,
	onDraftChange: ((SceneDraft) -> SceneDraft) -> Unit,
	onStart: () -> Unit,
	onDismiss: () -> Unit,
) {
	// Grouped by field so each message can sit under the field it names; the
	// scene total is last and therefore shown with the notes it follows.
	val errors = draft.sceneErrors.associateBy { it.field }
	// One set of button words for all five fields: :core:design has no string
	// resources, so the shared control takes its labels from the caller. The
	// import label is required by the control's API and never drawn here -- a
	// scene field has no importer, so `onImport` is null below.
	val fieldLabels = LongTextFieldLabels(
		importAction = stringResource(R.string.agents_prompt_import_action),
		writeAction = stringResource(R.string.agents_prompt_write_action),
		editAction = stringResource(R.string.agents_prompt_edit_action),
		doneAction = stringResource(R.string.common_done),
		cancelAction = stringResource(R.string.roleplay_cancel),
	)

	WideDialog(
		onDismissRequest = onDismiss,
		title = stringResource(R.string.scene_title),
		confirmLabel = stringResource(R.string.scene_start),
		onConfirm = onStart,
		confirmEnabled = draft.canStart,
		dismissLabel = stringResource(R.string.roleplay_cancel),
	) {
		Text(
			text = stringResource(R.string.scene_pick_character),
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		when {
			// The read is a local table scan and finishes in this frame; the
			// empty message would be a flash on a dialog that is about to have
			// rows in it, which reads as a fact about the install rather than
			// as a read in flight.
			draft.loading -> Unit

			draft.characters.isEmpty() -> Text(
				text = stringResource(R.string.scene_no_characters),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)

			else -> draft.characters.forEach { character ->
				SceneCharacterChoice(
					character = character,
					selected = character.agent.id == draft.selectedAgentId,
					onClick = {
						onDraftChange { it.copy(selectedAgentId = character.agent.id) }
					},
				)
			}
		}
		Hairline()
		SceneField(
			label = stringResource(R.string.roleplay_field_world),
			value = draft.world,
			onValueChange = { value -> onDraftChange { it.copy(world = value) } },
			labels = fieldLabels,
		)
		SceneLimitError(errors[RoleplayField.World])
		SceneField(
			label = stringResource(R.string.roleplay_field_premise),
			value = draft.premise,
			onValueChange = { value -> onDraftChange { it.copy(premise = value) } },
			labels = fieldLabels,
		)
		SceneLimitError(errors[RoleplayField.Premise])
		SceneField(
			label = stringResource(R.string.roleplay_field_user_persona),
			value = draft.userPersona,
			onValueChange = { value -> onDraftChange { it.copy(userPersona = value) } },
			labels = fieldLabels,
		)
		SceneLimitError(errors[RoleplayField.UserPersona])
		SceneField(
			label = stringResource(R.string.roleplay_field_format),
			value = draft.format,
			hint = stringResource(R.string.scene_format_hint),
			onValueChange = { value -> onDraftChange { it.copy(format = value) } },
			labels = fieldLabels,
		)
		SceneLimitError(errors[RoleplayField.Format])
		SceneField(
			label = stringResource(R.string.roleplay_field_notes),
			value = draft.notes,
			hint = stringResource(R.string.scene_notes_hint),
			onValueChange = { value -> onDraftChange { it.copy(notes = value) } },
			labels = fieldLabels,
		)
		SceneLimitError(errors[RoleplayField.Notes])
		SceneLimitError(errors[RoleplayField.SceneTotal])
		// The create failure is explained where the retry is, rather than after
		// the dialog has closed over the text that caused it.
		draft.error?.let { message ->
			Text(
				text = message,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.error,
			)
		}
	}
}

/**
 * One selectable character: the name, and as much of the card's description as
 * fits on two lines.
 *
 * A `FilterChip` rather than a radio row because the chip carries the selected
 * state, the tap target and the "this is the one" check mark in one control, and
 * the app already uses chips for a single choice among several rows.
 */
@Composable
private fun SceneCharacterChoice(
	character: SceneCharacter,
	selected: Boolean,
	onClick: () -> Unit,
) {
	val name = character.name.ifBlank { stringResource(R.string.home_unnamed) }
	val description = character.description
	FilterChip(
		selected = selected,
		onClick = onClick,
		modifier = Modifier.fillMaxWidth(),
		label = {
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(
					text = name,
					style = MaterialTheme.typography.bodyLarge,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				// A card is legal with no description at all; a blank second
				// line would still take its height, so it is omitted instead.
				if (description.isNotEmpty()) {
					Text(
						text = description,
						style = MaterialTheme.typography.labelLarge,
						color = if (selected) {
							MaterialTheme.colorScheme.onSecondaryContainer
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		},
	)
}

/**
 * One optional long-text scene field.
 *
 * Only the two fields whose meaning is not obvious from the label carry a hint
 * (`scene_format_hint`, `scene_notes_hint`); for the other three a placeholder
 * would only repeat the label.
 */
@Composable
private fun SceneField(
	label: String,
	value: String,
	onValueChange: (String) -> Unit,
	labels: LongTextFieldLabels,
	hint: String = "",
) {
	LongTextField(
		value = value,
		onValueChange = onValueChange,
		label = label,
		dialogTitle = label,
		labels = labels,
		placeholder = hint,
		// Two lines are enough to recognise what is there; the editor behind
		// the preview is where the text is read and written.
		previewLines = 2,
		previewMaxHeight = 56.dp,
	)
}

/**
 * The "too long" line under the field it names.
 *
 * `RoleplayLimitError` is a value, not a message: the core module deliberately
 * stays free of Android resources, and the field's localized name along with the
 * actual and maximum counts are supplied here.
 */
@Composable
private fun SceneLimitError(error: RoleplayLimitError?) {
	if (error == null) return
	// Card fields map to null: this dialog cannot create a card, so the only
	// limit it can report is the scene's.
	val label = sceneFieldLabel(error.field) ?: return
	Text(
		text = stringResource(R.string.roleplay_limit_over, label, error.actual, error.max),
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.error,
	)
}

/**
 * The localized name of a scene field, or null for a card field.
 *
 * Only the fields this dialog can write are named; the total is named as well
 * because it is the limit most likely to be hit while typing across fields.
 */
@Composable
private fun sceneFieldLabel(field: RoleplayField): String? = when (field) {
	RoleplayField.World -> stringResource(R.string.roleplay_field_world)
	RoleplayField.Premise -> stringResource(R.string.roleplay_field_premise)
	RoleplayField.UserPersona -> stringResource(R.string.roleplay_field_user_persona)
	RoleplayField.Format -> stringResource(R.string.roleplay_field_format)
	RoleplayField.Notes -> stringResource(R.string.roleplay_field_notes)
	RoleplayField.SceneTotal -> stringResource(R.string.roleplay_field_scene_total)
	else -> null
}
