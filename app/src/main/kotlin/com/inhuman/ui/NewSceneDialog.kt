package com.inhuman.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inhuman.R
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.design.Hairline
import com.inhuman.core.design.LongTextField
import com.inhuman.core.design.LongTextFieldLabels
import com.inhuman.core.design.WideDialog
import com.inhuman.core.engine.roleplay.RoleplayField
import com.inhuman.core.engine.roleplay.RoleplayLimitError
import com.inhuman.core.engine.roleplay.RoleplayValidator
import com.inhuman.core.engine.roleplay.StoryFolder

/**
 * The form in front of a new role-play scene.
 *
 * ## Two sources, one conversation
 *
 * The character can come from an assistant's card (the original shape) or from
 * a workspace story folder: `rp/<story>/card.json` plus an optional
 * `scene.json`. Both create the same conversation and freeze a
 * `RoleplaySnapshot`; the difference is only where the character text came
 * from. The workspace source still asks for an assistant, because the model,
 * provider and tool ceiling are conversation settings copied from one, and
 * because the assistant's name is the fallback when the card file has none.
 *
 * ## Why the browser is a dialog rather than a screen
 *
 * A scene *is* a conversation: starting one creates the transcript and
 * navigates into it exactly as "New conversation" does. What is decided first
 * -- which character, and what setting the story starts in -- is one step of
 * the create flow, so it stays a dialog over the list. The folder browser
 * inside it is a list of sub-folders, not a second file manager: only folders
 * are offered, because a story is a folder.
 *
 * ## Limits
 *
 * Assistant source: every scene field is optional, so an empty form is a legal
 * scene; what is not legal is an over-long one, and Start is refused while a
 * limit is broken. Workspace source: the folder is validated by the same
 * `RoleplayValidator` the `rp_validate` tool uses, so a folder the dialog
 * accepts is one the assistant can produce and check.
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
	onSourceChange: (SceneSource) -> Unit,
	onSelectWorkspace: (String) -> Unit,
	onEnterFolder: (String) -> Unit,
	onFolderUp: () -> Unit,
	onUseFolder: () -> Unit,
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
		maxBodyHeight = 520.dp,
	) {
		Text(
			text = stringResource(R.string.scene_source),
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			FilterChip(
				selected = draft.source == SceneSource.Assistant,
				onClick = { onSourceChange(SceneSource.Assistant) },
				label = { Text(stringResource(R.string.scene_source_assistant)) },
			)
			FilterChip(
				selected = draft.source == SceneSource.Materials,
				onClick = { onSourceChange(SceneSource.Materials) },
				label = { Text(stringResource(R.string.scene_source_materials)) },
			)
		}
		when (draft.source) {
			SceneSource.Assistant -> {
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
			}

			SceneSource.Materials -> MaterialsSource(
				draft = draft,
				onDraftChange = onDraftChange,
				onSelectWorkspace = onSelectWorkspace,
				onEnterFolder = onEnterFolder,
				onFolderUp = onFolderUp,
				onUseFolder = onUseFolder,
			)
		}
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
 * The workspace story source: workspace → folder → validated story → assistant.
 *
 * A folder row **enters** the folder; "Use this folder" validates the folder
 * being browsed. That split means browsing a tree of notes never prints card
 * errors for each folder on the way, and the one folder the user means is the
 * one that gets checked.
 */
@Composable
private fun ColumnScope.MaterialsSource(
	draft: SceneDraft,
	onDraftChange: ((SceneDraft) -> SceneDraft) -> Unit,
	onSelectWorkspace: (String) -> Unit,
	onEnterFolder: (String) -> Unit,
	onFolderUp: () -> Unit,
	onUseFolder: () -> Unit,
) {
	Text(
		text = stringResource(R.string.scene_materials_hint),
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
	// Two consequences the folder choice decides, both easy to
	// discover the wrong way round after the scene exists: the
	// conversation keeps this permanent workspace, and it starts
	// with no tools.
	Text(
		text = stringResource(R.string.scene_materials_hint_extra),
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
	if (draft.workspaces.isEmpty()) {
		Text(
			text = stringResource(R.string.scene_materials_no_workspaces),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.error,
		)
		return
	}

	Text(
		text = stringResource(R.string.scene_materials_workspace),
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
	draft.workspaces.forEach { workspace ->
		FilterChip(
			selected = workspace.id == draft.selectedWorkspaceId,
			onClick = { onSelectWorkspace(workspace.id) },
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

	Text(
		text = stringResource(R.string.scene_materials_folder),
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
	Text(
		text = if (draft.browsePath.isEmpty()) {
			stringResource(R.string.scene_materials_root)
		} else {
			"/${draft.browsePath}"
		},
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
	Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		if (draft.browsePath.isNotEmpty()) {
			TextButton(onClick = onFolderUp, enabled = !draft.storyLoading) {
				Text(stringResource(R.string.scene_materials_up))
			}
		}
		if (draft.folderHasCard) {
			TextButton(onClick = onUseFolder, enabled = !draft.storyLoading) {
				Text(stringResource(R.string.scene_materials_use))
			}
		}
	}
	if (draft.storyLoading) {
		Text(
			text = stringResource(R.string.scene_materials_reading),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
	if (!draft.storyLoading && draft.folders.isEmpty() && draft.storyError == null) {
		Text(
			text = stringResource(R.string.scene_materials_empty),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
	draft.folders.forEach { folder ->
		SceneFolderChoice(
			folder = folder,
			onClick = { onEnterFolder(folder.path) },
		)
	}
	if (draft.foldersTruncated) {
		Text(
			text = stringResource(R.string.scene_materials_truncated, draft.folders.size),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	draft.storyError?.let { reason ->
		Text(
			text = reason,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.error,
		)
	}
	draft.story?.let { story ->
		Hairline()
		Text(
			text = stringResource(R.string.scene_materials_character, draft.storyCharacterName()),
			style = MaterialTheme.typography.bodyLarge,
		)
		if (story.card.name.isBlank()) {
			Text(
				text = stringResource(
					R.string.scene_materials_name_fallback,
					draft.storyCharacterName(),
				),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		story.card.description.trim().takeIf { it.isNotEmpty() }?.let { description ->
			Text(
				text = description,
				style = MaterialTheme.typography.bodySmall,
				maxLines = 3,
				overflow = TextOverflow.Ellipsis,
			)
		}
		val scene = story.scene
		when {
			scene == null -> Text(
				text = stringResource(R.string.scene_materials_scene_file_missing),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)

			scene.world.isNotBlank() -> Text(
				text = stringResource(R.string.scene_materials_scene_set, scene.world),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
		}
		val problems = draft.storyIssues.filter { !it.warning }
		val warnings = draft.storyIssues.filter { it.warning }
		if (problems.isNotEmpty()) {
			Text(
				text = stringResource(R.string.scene_materials_issues),
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.error,
			)
			problems.forEach { issue -> StoryIssueRow(issue) }
		}
		if (warnings.isNotEmpty()) {
			Text(
				text = stringResource(R.string.scene_materials_warnings),
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			warnings.forEach { issue -> StoryIssueRow(issue) }
		}
	}

	Hairline()
	Text(
		text = stringResource(R.string.scene_materials_assistant),
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
	draft.assistants.forEach { assistant ->
		SceneAssistantChoice(
			assistant = assistant,
			selected = assistant.id == draft.selectedAgentId,
			onClick = { onDraftChange { it.copy(selectedAgentId = assistant.id) } },
		)
	}
}

/** One folder row: the name, and whether the folder directly holds a card. */
@Composable
private fun SceneFolderChoice(folder: StoryFolder, onClick: () -> Unit) {
	FilterChip(
		selected = false,
		onClick = onClick,
		modifier = Modifier.fillMaxWidth(),
		label = {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(
					text = folder.name,
					style = MaterialTheme.typography.bodyLarge,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f, fill = false),
				)
				if (folder.hasCard) {
					Text(
						text = stringResource(R.string.scene_materials_has_card),
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.primary,
					)
				}
			}
		},
	)
}

/** One assistant row in the materials source: the model configuration. */
@Composable
private fun SceneAssistantChoice(
	assistant: AgentEntity,
	selected: Boolean,
	onClick: () -> Unit,
) {
	FilterChip(
		selected = selected,
		onClick = onClick,
		modifier = Modifier.fillMaxWidth(),
		label = {
			Text(
				text = assistant.name.ifBlank { stringResource(R.string.home_unnamed) },
				style = MaterialTheme.typography.bodyLarge,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		},
	)
}

/** One validation finding: the field's localized name, then the message. */
@Composable
private fun StoryIssueRow(issue: RoleplayValidator.Issue) {
	Text(
		text = if (issue.field.isEmpty()) {
			issue.message
		} else {
			"${cardFieldLabel(issue.field)}: ${issue.message}"
		},
		style = MaterialTheme.typography.labelSmall,
		color = if (issue.warning) {
			MaterialTheme.colorScheme.onSurfaceVariant
		} else {
			MaterialTheme.colorScheme.error
		},
		modifier = Modifier.padding(start = 8.dp),
	)
}

/** The localized name of a card/scene field, for a validation finding. */
@Composable
private fun cardFieldLabel(field: String): String = when (field) {
	"name" -> stringResource(R.string.roleplay_field_name)
	"description" -> stringResource(R.string.roleplay_field_description)
	"personality" -> stringResource(R.string.roleplay_field_personality)
	"scenario" -> stringResource(R.string.roleplay_field_scenario)
	"greeting" -> stringResource(R.string.roleplay_field_greeting)
	"exampleDialogue" -> stringResource(R.string.roleplay_field_example)
	"boundaries" -> stringResource(R.string.roleplay_field_boundaries)
	"authorNote" -> stringResource(R.string.roleplay_field_author_note)
	"world" -> stringResource(R.string.roleplay_field_world)
	"premise" -> stringResource(R.string.roleplay_field_premise)
	"userPersona" -> stringResource(R.string.roleplay_field_user_persona)
	"format" -> stringResource(R.string.roleplay_field_format)
	"notes" -> stringResource(R.string.roleplay_field_notes)
	else -> field
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
