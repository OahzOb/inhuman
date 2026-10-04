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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import com.inhuman.core.ai.tools.ToolCatalog
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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

	// Local, because it is a view of the draft rather than part of it.
	var showPreview by remember { mutableStateOf(false) }

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
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = "System prompt",
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						// Preview, next to the thing it previews.
						//
						// The field is scrollable and the prompt wraps, so the
						// editor already shows all of it -- what it cannot show
						// is the whole of it at once, wrapped to a readable
						// width, together with what else goes in the request. On
						// a long prompt those are different things.
						TextButton(
							onClick = { showPreview = true },
							enabled = draft.systemPrompt.isNotBlank(),
						) {
							Text("Preview", style = MaterialTheme.typography.labelLarge)
						}
					}
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

				HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

				// --- tools ---------------------------------------------------
				//
				// The consequences are stated on the rows rather than in a note
				// underneath, because a note under a list is read after the
				// choice instead of before it.
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					SectionLabel("Tools")
					Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
						TextButton(onClick = { onChange { it.copy(tools = ToolCatalog.allSelection()) } }) {
							Text("All", style = MaterialTheme.typography.labelLarge)
						}
						TextButton(onClick = { onChange { it.copy(tools = emptyList()) } }) {
							Text("None", style = MaterialTheme.typography.labelLarge)
						}
					}
				}

				Text(
					text = if (draft.tools == null) {
						// Worth saying out loud, because it is not what "All"
						// does and the difference only shows up later.
						"Everything, including tools added in future versions."
					} else {
						"${draft.tools.size} of ${ToolCatalog.all.size} selected."
					},
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.outline,
				)

				ToolCatalog.all.forEach { tool ->
					val on = ToolCatalog.permits(draft.tools, tool.name)
					Row(
						modifier = Modifier
							.fillMaxWidth()
							// `toggleable` rather than `clickable`, and that is
							// not cosmetic.
							//
							// With a bare click plus `Checkbox(onCheckedChange =
							// null)` the box is drawn but is not a node: it has
							// no state for a screen reader to announce and
							// nothing for `uiautomator` to read, so the row said
							// nothing about being a checkbox or about whether it
							// was on. Tapping worked and nothing else did.
							//
							// `toggleable` gives the row the checkbox role, the
							// on/off state, and the standard toggle action --
							// which is what makes it usable without sight.
							.toggleable(
								value = on,
								role = Role.Checkbox,
								onValueChange = {
									// Toggling from "everything" has to mean
									// something. It becomes the full list, so
									// the next tap turns one thing off rather
									// than silently turning everything else off.
									val current = draft.tools ?: ToolCatalog.names
									val next = if (on) current - tool.name else current + tool.name
									onChange { it.copy(tools = next) }
								},
							)
							.padding(vertical = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(10.dp),
					) {
						// Merely drawn: the row owns the interaction, because
						// two toggles for one value is one too many.
						Checkbox(checked = on, onCheckedChange = null)
						Column(modifier = Modifier.weight(1f)) {
							Text(
								text = tool.name,
								style = MaterialTheme.typography.bodyMedium,
								fontFamily = FontFamily.Monospace,
								color = MaterialTheme.colorScheme.onSurface,
							)
							Text(
								text = tool.blurb,
								style = MaterialTheme.typography.labelLarge,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
						// The cost, next to the choice that incurs it.
						//
						// Mutating tools are what stops a reply being
						// regenerated, so choosing one is choosing to give that
						// up for the turns that use it. Said here rather than
						// only in the chat, where it arrives too late to inform
						// anything.
						if (tool.mutates) {
							Text(
								text = "changes files",
								style = MaterialTheme.typography.labelLarge,
								color = MaterialTheme.colorScheme.outline,
							)
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

	if (showPreview) {
		PromptPreview(
			draft = draft,
			modelLabel = draft.model?.let { id ->
				models.firstOrNull { it.id == id }?.displayName?.takeIf { it.isNotBlank() } ?: id
			} ?: "provider default",
			onDismiss = { showPreview = false },
		)
	}
}

/**
 * The whole prompt, as the model receives it.
 *
 * ## Why this shows more than the system message
 *
 * The system prompt does not travel alone. It is one part of a request that also
 * names a model, states whether thinking is on, and carries a tool list -- and
 * the last of those is what this screen's other new control changes. Previewing
 * only the text would leave the two features unconnected: a person would pick
 * four tools and have no way to see that those four are now part of what gets
 * sent.
 *
 * So the footer is not decoration. It is the answer to "what did my choices
 * actually do".
 *
 * ## What it deliberately does not show
 *
 * The tool descriptions and parameter schemas. Those are prompt text and they
 * are long, and repeating them here would bury the one part a person came to
 * read. The names are enough to confirm a selection; the wording of a tool's
 * description is a property of the tool, not of this assistant.
 */
@Composable
private fun PromptPreview(
	draft: AgentDraft,
	modelLabel: String,
	onDismiss: () -> Unit,
) {
	val selected = draft.tools
	val on = ToolCatalog.all.map { it.name }.filter { ToolCatalog.permits(selected, it) }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("What the model receives") },
		text = {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 460.dp)
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				SectionLabel("System message")
				// Selectable, so a prompt can be copied out and tried
				// elsewhere -- which is the first thing anyone does with one.
				//
				// Bounded, and separately scrollable, because the prompt is the
				// longest thing here and it was pushing everything after it out
				// of view. Measured: a ten-line prompt took 990px of a 1208px
				// content area, so the tools section rendered below the fold and
				// the preview appeared to show a count with no list. The prompt
				// is the first thing read, not the only one.
				SelectionContainer {
					Text(
						text = draft.systemPrompt.ifBlank { "(empty)" },
						style = MaterialTheme.typography.bodySmall,
						fontFamily = FontFamily.Monospace,
						color = MaterialTheme.colorScheme.onSurface,
						modifier = Modifier
							.fillMaxWidth()
							.heightIn(max = 200.dp)
							.verticalScroll(rememberScrollState()),
					)
				}

				HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

				SectionLabel("Model")
				Text(
					text = buildString {
						append(modelLabel)
						append(if (draft.thinking) " · thinking on" else " · thinking off")
						draft.reasoningEffort?.let { append(" · effort $it") }
					},
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurface,
				)

				SectionLabel(if (selected == null) "Tools — everything" else "Tools — ${on.size} of ${ToolCatalog.all.size}")
				if (on.isEmpty()) {
					Text(
						text = "None. This assistant can only talk.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurface,
					)
				} else {
					// One per line, all of them, with the unavailable ones
					// dimmed rather than listed separately.
					//
					// The first version printed the enabled names as a
					// comma-separated paragraph and the disabled ones as a
					// second. On a selection of nine that wrapped to five lines
					// and pushed itself below the dialog's scroll area -- the
					// count showed and the names did not, which is a preview
					// that previews nothing.
					//
					// A column of one-per-line is shorter because it does not
					// wrap, and it answers the question directly: every tool is
					// visible, and which ones are in the request is a matter of
					// shade rather than of cross-referencing two lists.
					Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
						ToolCatalog.all.forEach { tool ->
							val available = ToolCatalog.permits(selected, tool.name)
							Text(
								text = if (available) tool.name else "· ${tool.name}",
								style = MaterialTheme.typography.bodySmall,
								fontFamily = FontFamily.Monospace,
								color = if (available) {
									MaterialTheme.colorScheme.onSurface
								} else {
									MaterialTheme.colorScheme.outline
								},
							)
						}
					}
				}

				if (selected == null) {
					Text(
						text = "Everything includes tools this app adds later.",
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.outline,
					)
				}
			}
		},
		confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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
