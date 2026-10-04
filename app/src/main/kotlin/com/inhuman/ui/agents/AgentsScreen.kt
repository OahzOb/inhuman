package com.inhuman.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.design.Layout
import com.inhuman.core.design.comfortableGutter

/**
 * The assistants.
 *
 * ## Why the system prompt is shown in full
 *
 * An assistant whose instructions cannot be read is one that cannot be trusted
 * or corrected. Instructions are the whole difference between two assistants,
 * and hiding them behind a name would make the app's behaviour unexplainable
 * from the inside -- the user would change a name and see different answers
 * with no way to find out why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
	viewModel: AgentsViewModel,
	onBack: () -> Unit,
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	var pendingDelete by remember { mutableStateOf<AgentEntity?>(null) }

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("Assistants", style = MaterialTheme.typography.titleMedium) },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Text("‹", style = MaterialTheme.typography.titleLarge)
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
					titleContentColor = MaterialTheme.colorScheme.onBackground,
					navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
				),
			)
		},
		floatingActionButton = {
			FloatingActionButton(
				onClick = viewModel::create,
				containerColor = MaterialTheme.colorScheme.primary,
				contentColor = MaterialTheme.colorScheme.onPrimary,
				shape = RoundedCornerShape(16.dp),
			) {
				Icon(Icons.Filled.Add, contentDescription = "New assistant")
			}
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { inner ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(inner)
				.windowInsetsPadding(WindowInsets.safeDrawing),
		) {
			LazyColumn(
				modifier = Modifier
					.fillMaxSize()
					.widthIn(max = Layout.ReadableMeasure),
				contentPadding = PaddingValues(horizontal = comfortableGutter(), vertical = 8.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp),
			) {
				items(state.agents, key = { it.id }) { agent ->
					AgentRow(
						agent = agent,
						modelLabel = viewModel.modelDisplayName(agent.model),
						onEdit = { viewModel.edit(agent) },
						onDelete = if (agent.isDefault) null else { { pendingDelete = agent } },
					)
				}
			}
		}
	}

	state.editing?.let { draft ->
		AgentEditor(
			draft = draft,
			models = state.models,
			loadingModels = state.loadingModels,
			modelsError = state.modelsError,
			effortLevels = { viewModel.effortLevelsFor(it) },
			onChange = viewModel::updateDraft,
			onReloadModels = { viewModel.loadModels(force = true) },
			onSave = viewModel::save,
			onDismiss = viewModel::closeEditor,
		)
	}

	pendingDelete?.let { agent ->
		AlertDialog(
			onDismissRequest = { pendingDelete = null },
			title = { Text("Delete ${agent.name}?") },
			text = { Text("Conversations already held with it keep their own settings.") },
			confirmButton = {
				TextButton(onClick = { viewModel.delete(agent); pendingDelete = null }) { Text("Delete") }
			},
			dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun AgentRow(
	agent: AgentEntity,
	modelLabel: String,
	onEdit: () -> Unit,
	onDelete: (() -> Unit)?,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
			.clickable(onClick = onEdit)
			.padding(14.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = agent.name.ifBlank { "(unnamed)" },
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (agent.isDefault) {
				Text(
					text = "default",
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier
						.background(
							MaterialTheme.colorScheme.primaryContainer,
							RoundedCornerShape(6.dp),
						)
						.padding(horizontal = 8.dp, vertical = 2.dp),
				)
			}
			onDelete?.let {
				IconButton(onClick = it) {
					Icon(
						Icons.Filled.Delete,
						contentDescription = "Delete",
						tint = MaterialTheme.colorScheme.error,
						modifier = Modifier.size(18.dp),
					)
				}
			}
		}

		// The model and effort, so two assistants are distinguishable without
		// opening either.
		Text(
			text = buildString {
				append(modelLabel)
				if (agent.thinking) {
					append(" · thinking")
					agent.reasoningEffort?.let { append(" ($it)") }
				}
			},
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)

		// The instructions, clipped rather than hidden: enough to recognise the
		// assistant, with the whole text one tap away in the editor.
		Text(
			text = agent.systemPrompt,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 3,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun AgentEditor(
	draft: AgentDraft,
	models: List<com.inhuman.core.ai.RemoteModel>,
	loadingModels: Boolean,
	modelsError: String?,
	effortLevels: (String?) -> List<String>,
	onChange: ((AgentDraft) -> AgentDraft) -> Unit,
	onReloadModels: () -> Unit,
	onSave: () -> Unit,
	onDismiss: () -> Unit,
) {
	val levels = effortLevels(draft.model)

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(if (draft.isNew) "New assistant" else "Edit assistant") },
		text = {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					// Bounded so a long prompt scrolls inside the dialog rather
					// than pushing the buttons off the screen.
					.heightIn(max = 460.dp)
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(14.dp),
			) {
				OutlinedTextField(
					value = draft.name,
					onValueChange = { v -> onChange { it.copy(name = v) } },
					label = { Text("Name") },
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
				)

				Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Text(
						text = "System prompt",
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					OutlinedTextField(
						value = draft.systemPrompt,
						onValueChange = { v -> onChange { it.copy(systemPrompt = v) } },
						modifier = Modifier
							.fillMaxWidth()
							// Bounded at BOTH ends, and the upper bound is the
							// important one. With only a minimum, this field
							// grew to fill every pixel the dialog had left --
							// measured at 934px of a 1208px content area -- and
							// pushed the model picker and the thinking switch
							// out of the scroll region entirely, so the
							// assistant editor showed two fields and then its
							// buttons. A text area with no maximum in a Column
							// does not stay small; it takes what is available.
							.heightIn(min = 110.dp, max = 190.dp),
						textStyle = MaterialTheme.typography.bodyMedium.copy(
							fontFamily = FontFamily.Monospace,
						),
					)
					Text(
						text = "Sent as the system message on every turn in this assistant's conversations.",
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.outline,
					)
				}

				HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

				// Model, from the provider rather than from a list in the app.
				SectionLabel("Model")
				if (modelsError != null) {
					Text(
						text = modelsError,
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.error,
					)
					TextButton(onClick = onReloadModels) { Text("Retry") }
				} else if (loadingModels) {
					CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
				}
				FlowRowCompat {
					ChoiceChip(
						label = "provider default",
						selected = draft.model == null,
						onClick = { onChange { it.copy(model = null, reasoningEffort = null) } },
					)
					models.forEach { m ->
						ChoiceChip(
							label = m.displayName?.takeIf { it.isNotBlank() } ?: m.id,
							selected = draft.model == m.id,
							onClick = {
								// Effort is cleared when the model changes: the
								// levels are per-model, so carrying one across
								// would send a value the new model may reject.
								onChange { it.copy(model = m.id, reasoningEffort = null) }
							},
						)
					}
				}

				// Thinking and effort, which only apply when thinking is on.
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = "Thinking",
							style = MaterialTheme.typography.bodyLarge,
							color = MaterialTheme.colorScheme.onSurface,
						)
						Text(
							text = "Off is the right default for chat: \"hi\" should not cost a paragraph of reasoning.",
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					Switch(
						checked = draft.thinking,
						onCheckedChange = { v ->
							onChange { it.copy(thinking = v, reasoningEffort = if (v) it.reasoningEffort else null) }
						},
					)
				}

				if (draft.thinking) {
					SectionLabel("Reasoning effort")
					if (levels.isEmpty()) {
						Text(
							text = "This model reports no effort levels; the provider default will be used.",
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.outline,
						)
					} else {
						FlowRowCompat {
							ChoiceChip(
								label = "model default",
								selected = draft.reasoningEffort == null,
								onClick = { onChange { it.copy(reasoningEffort = null) } },
							)
							// Exactly the levels the model advertises, in the order
							// it advertises them. Nothing is invented here: a
							// hardcoded scale would let the app send a value the
							// API does not accept.
							levels.forEach { level ->
								ChoiceChip(
									label = level,
									selected = draft.reasoningEffort == level,
									onClick = { onChange { it.copy(reasoningEffort = level) } },
								)
							}
						}
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onSave, enabled = draft.canSave) { Text("Save") }
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
		containerColor = MaterialTheme.colorScheme.surface,
		titleContentColor = MaterialTheme.colorScheme.onSurface,
		textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

@Composable
private fun SectionLabel(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
	FilterChip(
		selected = selected,
		onClick = onClick,
		label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
	)
}

/**
 * A wrapping row.
 *
 * Hand-rolled rather than pulling in the FlowRow API, which is still
 * experimental and would need an opt-in on every call site for one layout.
 */
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
	androidx.compose.foundation.layout.FlowRow(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		content()
	}
}
