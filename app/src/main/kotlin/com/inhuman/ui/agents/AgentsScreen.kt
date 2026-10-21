package com.inhuman.ui.agents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.ai.tools.ToolCatalog
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.saveable.rememberSaveable
import com.inhuman.core.design.LongTextField
import com.inhuman.core.design.WideDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.design.Eyebrow
import com.inhuman.core.design.Hairline
import com.inhuman.core.design.Layout
import com.inhuman.core.design.comfortableGutter
import com.inhuman.ui.common.FlowRowCompat
import com.inhuman.ui.common.WorkbenchBackButton
import com.inhuman.ui.common.PaidBadge
import com.inhuman.ui.common.PaidToolsNotice
import com.inhuman.ui.common.RemoteBadge
import com.inhuman.ui.common.RemoteToolsNotice
import com.inhuman.ui.common.RewindBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val PROMPT_LENGTH_HINT = 32_000

/** A file read from the picker, waiting for Replace/Append. */
private data class ImportedPrompt(val name: String, val text: String)

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
	LaunchedEffect(Unit) { viewModel.refreshSshTool() }

	Scaffold(
		topBar = {
			Column {
				TopAppBar(
					title = { Text("Assistants", style = MaterialTheme.typography.titleLarge) },
					navigationIcon = {
						WorkbenchBackButton(onClick = onBack)
					},
					colors = TopAppBarDefaults.topAppBarColors(
						containerColor = MaterialTheme.colorScheme.background,
						titleContentColor = MaterialTheme.colorScheme.onBackground,
						navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
					),
				)
				Hairline()
			}
		},
		floatingActionButton = {
			FloatingActionButton(
				onClick = viewModel::create,
				containerColor = MaterialTheme.colorScheme.primary,
				contentColor = MaterialTheme.colorScheme.onPrimary,
				shape = MaterialTheme.shapes.medium,
			) {
				Icon(Icons.Filled.Add, contentDescription = "New assistant")
			}
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { inner ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(inner),
			contentAlignment = Alignment.TopCenter,
		) {
			LazyColumn(
				modifier = Modifier
					.widthIn(max = 760.dp)
					.fillMaxSize(),
				contentPadding = PaddingValues(horizontal = comfortableGutter(), vertical = 20.dp),
				verticalArrangement = Arrangement.spacedBy(0.dp),
			) {
				item(key = "header") {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(bottom = 10.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Eyebrow("Assistant profiles")
						Spacer(Modifier.weight(1f))
						Text(
							text = state.agents.size.toString().padStart(2, '0'),
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					Hairline()
				}
				items(state.agents, key = { it.id }) { agent ->
					AgentRow(
						agent = agent,
						providerName = state.providers
							.firstOrNull { it.id == agent.providerId }?.name
							?: "Provider",
						modelLabel = viewModel.modelDisplayName(agent.model),
						onEdit = { viewModel.edit(agent) },
						onDelete = if (agent.isDefault) null else { { pendingDelete = agent } },
					)
					Hairline()
				}
			}
		}
	}

	state.editing?.let { draft ->
		AgentEditor(
			draft = draft,
			providers = state.providers,
			models = state.models,
			loadingModels = state.loadingModels,
			modelsError = state.modelsError,
			sshToolEnabled = state.sshToolEnabled,
			effortLevels = { viewModel.effortLevelsFor(it) },
			onChange = viewModel::updateDraft,
			onProviderChange = viewModel::selectProvider,
			onRuleChange = viewModel::setDraftRule,
			onReloadModels = { viewModel.loadModels(providerId = draft.providerId, force = true) },
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
	providerName: String,
	modelLabel: String,
	onEdit: () -> Unit,
	onDelete: (() -> Unit)?,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onEdit)
			.padding(vertical = 14.dp),
		verticalArrangement = Arrangement.spacedBy(5.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = agent.name.ifBlank { "(unnamed)" },
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onBackground,
				modifier = Modifier.weight(1f),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (agent.isDefault) {
				Text(
					text = "DEFAULT",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier
						.background(
							MaterialTheme.colorScheme.primaryContainer,
							RoundedCornerShape(4.dp),
						)
						.padding(horizontal = 6.dp, vertical = 3.dp),
				)
			}
			onDelete?.let {
				IconButton(onClick = it, modifier = Modifier.size(32.dp)) {
					Icon(
						Icons.Filled.Delete,
						contentDescription = "Delete",
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(17.dp),
					)
				}
			}
		}

		// The model and effort, so two assistants are distinguishable without
		// opening either.
		Text(
			text = buildString {
				append(providerName)
				append(" / ")
				append(modelLabel)
				if (agent.thinking) {
					append(" · thinking")
					agent.reasoningEffort?.let { append(" ($it)") }
				}
			},
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)

		// The instructions, clipped rather than hidden: enough to recognise the
		// assistant, with the whole text one tap away in the editor.
		Text(
			text = agent.systemPrompt,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun AgentEditor(
	draft: AgentDraft,
	providers: List<com.inhuman.core.data.db.ProviderProfileEntity>,
	models: List<com.inhuman.core.ai.RemoteModel>,
	loadingModels: Boolean,
	modelsError: String?,
	sshToolEnabled: Boolean,
	effortLevels: (String?) -> List<String>,
	onChange: ((AgentDraft) -> AgentDraft) -> Unit,
	onProviderChange: (String) -> Unit,
	onRuleChange: (String, ToolPolicy) -> Unit,
	onReloadModels: () -> Unit,
	onSave: () -> Unit,
	onDismiss: () -> Unit,
) {
	val levels = effortLevels(draft.model)
	val availableTools = ToolCatalog.visible(sshToolEnabled)
	val availableNames = availableTools.mapTo(mutableSetOf()) { it.name }
	val selectedCount = draft.tools?.count { it in availableNames } ?: availableTools.size

	// Local, because it is a view of the draft rather than part of it.
	var showPreview by remember { mutableStateOf(false) }
	// Whether per-tool descriptions are shown. Off by default: the list is ten
	// rows and the blurbs are the only part not needed to make a choice, so
	// showing them doubles the length of the section to answer a question most
	// people do not have.
	var showToolHelp by rememberSaveable { mutableStateOf(false) }

	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var importError by remember { mutableStateOf<String?>(null) }
	var importStatus by remember { mutableStateOf<String?>(null) }
	var pendingImport by remember { mutableStateOf<ImportedPrompt?>(null) }

	val promptImporter = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			val name = queryPromptName(context, uri) ?: "imported"
			scope.launch {
				importError = null
				importStatus = null
				try {
					val text = withContext(Dispatchers.IO) {
						readPromptFile(context, uri, name)
					}
					if (draft.systemPrompt.isBlank()) {
						onChange { it.copy(systemPrompt = text) }
						importStatus = "Imported $name (${text.length} characters)."
					} else {
						pendingImport = ImportedPrompt(name, text)
					}
				} catch (failure: Exception) {
					importError = failure.message ?: "Could not import the file."
				}
			}
		}
	}

	WideDialog(
		onDismissRequest = onDismiss,
		title = if (draft.isNew) "New assistant" else "Edit assistant",
		confirmLabel = "Save",
		onConfirm = onSave,
		confirmEnabled = draft.canSave,
	) {
		OutlinedTextField(
			value = draft.name,
			onValueChange = { v -> onChange { it.copy(name = v) } },
			label = { Text("Name") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)

		// A preview with an editor behind it, rather than a small text field.
		//
		// `OutlinedTextField` with a height constraint paints its text past its
		// own border instead of clipping it -- measured at six lines inside a
		// 150dp field, the last line drawn through the border and over the
		// caption below. That is the tangle the screenshot showed, and making
		// the field taller only moves where the overrun happens.
		LongTextField(
			value = draft.systemPrompt,
			onValueChange = { v -> onChange { it.copy(systemPrompt = v) } },
			label = "System prompt",
			dialogTitle = "System prompt",
			placeholder = "Tell the assistant what it is for.",
			previewLines = 4,
			textStyle = MaterialTheme.typography.bodyMedium.copy(
				fontFamily = FontFamily.Monospace,
			),
			onImport = { promptImporter.launch(arrayOf("*/*")) },
		)
		importError?.let {
			Text(
				text = it,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
			)
		}
		importStatus?.let {
			Text(
				text = it,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		if (!draft.canSave) {
			// The Save button is disabled by validation a few sections below;
			// without this line the only feedback was a button that did nothing.
			val missing = when {
				draft.name.isBlank() && draft.systemPrompt.isBlank() ->
					"a name and a system prompt"
				draft.name.isBlank() -> "a name"
				else -> "a system prompt"
			}
			Text(
				text = "Add $missing to save.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
			)
		}
		if (draft.systemPrompt.length >= PROMPT_LENGTH_HINT) {
			Text(
				text = "${draft.systemPrompt.length} characters. This is sent on every " +
					"turn and counts against the model's context window.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.tertiary,
			)
		}
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = "Sent on every turn in this assistant's conversations.",
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.outline,
				modifier = Modifier.weight(1f),
			)
			// The full request, not just the prompt: the model, the thinking
			// level, and which tools travel with it.
			TextButton(
				onClick = { showPreview = true },
				enabled = draft.systemPrompt.isNotBlank(),
			) {
				Text("Preview request", style = MaterialTheme.typography.labelLarge)
			}
		}

		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

		// --- provider -------------------------------------------------------
		SectionLabel("Provider")
		if (providers.isEmpty()) {
			Text(
				text = "No provider is configured. Add one in Settings.",
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		} else {
			FlowRowCompat {
				providers.forEach { provider ->
					ChoiceChip(
						label = provider.name,
						selected = draft.providerId == provider.id,
						onClick = { onProviderChange(provider.id) },
					)
				}
			}
		}

		// --- model ----------------------------------------------------------
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
						// Effort is cleared when the model changes: the levels
						// are per-model, so carrying one across would send a
						// value the new model may reject.
						onChange { it.copy(model = m.id, reasoningEffort = null) }
					},
				)
			}
		}

		// --- thinking, as one row of levels ---------------------------------
		//
		// "Off" used to be a switch beside a separate row of effort chips, which
		// made one question look like two: whether to think at all, and how
		// hard. They are the same question with one more answer, so they are one
		// control -- off, low, high, max.
		//
		// The switch also cost a whole row plus a dependent section that
		// appeared and disappeared, which is part of what pushed the tool list
		// below the fold.
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			SectionLabel("Thinking")
			Text(
				text = "off is the right default for chat",
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.outline,
			)
		}
		FlowRowCompat {
			ChoiceChip(
				label = "off",
				selected = !draft.thinking,
				onClick = {
					// Effort goes with it, because a level with thinking off is
					// not a setting the API accepts -- it is one the app would
					// silently drop, and keeping it would leave a chip row
					// showing a value that is not being sent.
					onChange { it.copy(thinking = false, reasoningEffort = null) }
				},
			)
			// Exactly the levels the model advertises, in the order it
			// advertises them. Nothing is invented: a hardcoded scale would let
			// the app send a value the API does not accept.
			levels.forEach { level ->
				ChoiceChip(
					label = level,
					selected = draft.thinking && draft.reasoningEffort == level,
					onClick = { onChange { it.copy(thinking = true, reasoningEffort = level) } },
				)
			}
			// Only when the model advertises levels at all. A model with none
			// would get a lone "off" beside nothing, which reads as a broken row
			// rather than as a model without such a setting.
			if (levels.isNotEmpty()) {
				ChoiceChip(
					label = "model default",
					selected = draft.thinking && draft.reasoningEffort == null,
					onClick = { onChange { it.copy(thinking = true, reasoningEffort = null) } },
				)
			}
		}
		if (draft.thinking && levels.isEmpty()) {
			Text(
				text = "This model reports no effort levels; the provider default will be used.",
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.outline,
			)
		}

		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

		// Placed immediately after a divider rather than after the tools list,
		// which is where it was first written and where it did not appear. A
		// section appended at the end of a long scrollable column is the one
		// position whose absence is hardest to distinguish from a render
		// failure, so it sits somewhere a screenshot can settle the question.
		AssistantRulesSection(
			rules = draft.rules,
			sshToolEnabled = sshToolEnabled,
			onSelect = onRuleChange,
		)

		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

		// --- tools ----------------------------------------------------------
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			SectionLabel(
				if (draft.tools == null) {
					"Tools — all"
				} else {
					"Tools — $selectedCount of ${availableTools.size}"
				},
			)
			Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
				TextButton(onClick = { showToolHelp = !showToolHelp }) {
					Text(
						text = if (showToolHelp) "Hide help" else "Help",
						style = MaterialTheme.typography.labelLarge,
					)
				}
				TextButton(onClick = { onChange { it.copy(tools = ToolCatalog.allSelection()) } }) {
					Text("All", style = MaterialTheme.typography.labelLarge)
				}
				TextButton(onClick = { onChange { it.copy(tools = emptyList()) } }) {
					Text("None", style = MaterialTheme.typography.labelLarge)
				}
			}
		}

		if (draft.tools == null) {
			// Worth saying, because it is not what "All" does and the difference
			// only shows up later.
			Text(
				text = "Includes tools added in future versions.",
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.outline,
			)
			if (sshToolEnabled) {
				Text(
					text = "ssh_exec is never included by “all”; select it explicitly.",
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.outline,
				)
			}
		}

		// Before the list, not after it. A price printed under eleven rows is
		// read by the people who were already going to read it; a filled notice
		// above them is seen by the people scanning names.
		PaidToolsNotice(modifier = Modifier.padding(vertical = 4.dp))
		if (sshToolEnabled) {
			RemoteToolsNotice(modifier = Modifier.padding(bottom = 4.dp))
		}

		availableTools.forEach { tool ->
			val on = ToolCatalog.permits(draft.tools, tool.name)
			Row(
				modifier = Modifier
					.fillMaxWidth()
					// `toggleable` rather than `clickable`, and that is not
					// cosmetic.
					//
					// With a bare click plus `Checkbox(onCheckedChange = null)`
					// the box is drawn but is not a node: no state for a screen
					// reader to announce and nothing for `uiautomator` to read,
					// so the row said nothing about being a checkbox or about
					// whether it was on. Tapping worked and nothing else did.
					.toggleable(
						value = on,
						role = Role.Checkbox,
						onValueChange = {
							// Toggling from "everything" has to mean something.
							// It becomes the full list, so the next tap turns one
							// thing off rather than silently turning everything
							// else off.
							val current = draft.tools ?: ToolCatalog.names
								.filterNot { it == "ssh_exec" }
							val next = if (on) current - tool.name else current + tool.name
							onChange { it.copy(tools = next) }
						},
					)
					.padding(vertical = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				// Merely drawn: the row owns the interaction, because two
				// toggles for one value is one too many.
				Checkbox(checked = on, onCheckedChange = null)

				// The write marker, as ink rather than as a word.
				//
				// It used to say "changes files" on every mutating row -- five
				// repetitions of the same three words down one side of the list,
				// which is a lot of text to say one thing. A dot carries it in a
				// fraction of the space, and the colour does the work the
				// repetition was doing.
				//
				// Colour alone is not the whole signal, so the two groups also
				// differ in word: the writes say "writes", which is one word
				// rather than three.
				Box(
					modifier = Modifier
						.size(5.dp)
						.background(
							if (tool.mutates) {
								MaterialTheme.colorScheme.tertiary
							} else {
								MaterialTheme.colorScheme.outlineVariant
							},
							CircleShape,
						),
				)

				Column(modifier = Modifier.weight(1f)) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(6.dp),
					) {
						Text(
							text = tool.name,
							style = MaterialTheme.typography.bodyMedium,
							fontFamily = FontFamily.Monospace,
							color = MaterialTheme.colorScheme.onSurface,
						)
						// Beside the name, not in the right-hand column: this is
						// the one warning that has to survive a glance down the
						// list. The trailing column is for "writes".
						if (tool.paid) PaidBadge()
						if (tool.remote) RemoteBadge()
						if (tool.mutates) RewindBadge(canRewind = tool.reversible)
					}
					// The description is opt-in.
					//
					// Eleven blurbs double the length of this section, and the
					// names are what a choice is made from -- someone who wants
					// to know what `append_file` does can ask, and someone who
					// does not should not scroll past ten answers to a question
					// they did not have.
					if (showToolHelp) {
						Text(
							text = tool.blurb,
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}

				if (tool.mutates) {
					Text(
						text = "writes",
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.tertiary,
					)
				}
			}
		}

		if (showToolHelp) {
			Text(
				text = "Tools marked “writes” change files in the conversation's folder. " +
					"A reply that used one cannot be regenerated, because replaying the " +
					"turn would write a second time.",
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.outline,
			)
		}
	}

	if (showPreview) {
		PromptPreview(
			draft = draft,
			sshToolEnabled = sshToolEnabled,
			modelLabel = draft.model?.let { id ->
				models.firstOrNull { it.id == id }?.displayName?.takeIf { it.isNotBlank() } ?: id
			} ?: "provider default",
			onDismiss = { showPreview = false },
		)
	}

	pendingImport?.let { imported ->
		AlertDialog(
			onDismissRequest = { pendingImport = null },
			title = { Text("Import system prompt") },
			text = {
				Text(
					"${imported.name} is ${imported.text.length} characters. " +
						"Replace the current prompt, or append the file to it?",
				)
			},
			confirmButton = {
				TextButton(
					onClick = {
						onChange { it.copy(systemPrompt = imported.text) }
						importStatus = "Replaced with ${imported.name} " +
							"(${imported.text.length} characters)."
						pendingImport = null
					},
				) { Text("Replace") }
			},
			dismissButton = {
				Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
					TextButton(
						onClick = {
							val current = draft.systemPrompt.trimEnd()
							val joined = if (current.isBlank()) {
								imported.text
							} else {
								"$current\n\n${imported.text}"
							}
							onChange { it.copy(systemPrompt = joined) }
							importStatus = "Appended ${imported.name} " +
								"(${imported.text.length} characters)."
							pendingImport = null
						},
					) { Text("Append") }
					TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
				}
			},
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
	sshToolEnabled: Boolean,
	modelLabel: String,
	onDismiss: () -> Unit,
) {
	val selected = draft.tools
	val visibleTools = ToolCatalog.visible(sshToolEnabled)
	val visibleNames = visibleTools.mapTo(mutableSetOf()) { it.name }
	val on = visibleNames.filter { ToolCatalog.permits(selected, it) }

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

				SectionLabel(if (selected == null) "Tools — everything" else "Tools — ${on.size} of ${visibleTools.size}")
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
						visibleTools.forEach { tool ->
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

/**
 * What this assistant may do without asking.
 *
 * ## Why this lives here and not in a separate rules screen
 *
 * The rules are keyed by assistant, so a screen of their own would need a picker
 * at the top saying *which* assistant -- and that picker would be a second way to
 * choose the same thing the assistants list already chooses. Editing them beside
 * the prompt and the tool list keeps one place where an assistant is defined.
 *
 * ## Why nothing here can loosen anything
 *
 * These rules compose with the global ones by taking the strictest, so an
 * assistant can only ever be *more* cautious than settings, never less. That is
 * deliberate and worth stating where a user might otherwise expect to be granting
 * something: "this assistant may delete without asking" is not expressible, and
 * if settings already says `Ask`, choosing `run it` here changes nothing.
 */
@Composable
private fun AssistantRulesSection(
	rules: Map<String, ToolPolicy>,
	sshToolEnabled: Boolean,
	onSelect: (String, ToolPolicy) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		SectionLabel("Before a tool runs — this assistant")
		Text(
			text = "Tighter than the app-wide setting, never looser. Anything left " +
				"alone follows settings.",
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.outline,
		)

		for (entry in ToolCatalog.visible(sshToolEnabled)) {
			val current = rules[entry.name] ?: ToolPolicy.Free
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Row(
					horizontalArrangement = Arrangement.spacedBy(6.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = entry.name,
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurface,
					)
					if (entry.paid) PaidBadge()
					if (entry.remote) RemoteBadge()
					if (entry.mutates) RewindBadge(canRewind = entry.reversible)
				}
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					for (option in ToolPolicy.entries) {
						ChoiceChip(
							label = option.shortLabel(),
							selected = option == current,
							onClick = { onSelect(entry.name, option) },
						)
					}
				}
			}
		}
	}
}

/** One word per policy, for a row that already has ten of these. */
private fun ToolPolicy.shortLabel(): String = when (this) {
	ToolPolicy.Free -> "follow settings"
	ToolPolicy.Ask -> "ask"
	ToolPolicy.Deny -> "refuse"
	ToolPolicy.Blind -> "hide"
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
 * The picker's label for a document, or null when the provider does not offer
 * one. The caller falls back to "imported" so the confirmation still names
 * something.
 */
private fun queryPromptName(context: Context, uri: Uri): String? = runCatching {
	context.contentResolver.query(
		uri,
		arrayOf(OpenableColumns.DISPLAY_NAME),
		null,
		null,
		null,
	)?.use { cursor ->
		val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
		if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
	}
}.getOrNull()?.takeIf { it.isNotBlank() }

/**
 * Read a picked prompt file as UTF-8.
 *
 * The 1 MiB ceiling is a read guard, not a prompt limit: it stops a mis-tapped
 * video or database from being pulled into memory before the user sees what
 * happened. A system prompt that big would not fit any context window anyway.
 */
private fun readPromptFile(context: Context, uri: Uri, name: String): String {
	val mimeType = context.contentResolver.getType(uri)
	if (!isPromptText(name, mimeType)) {
		throw IOException("Choose a .txt or .md file.")
	}

	val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
		readPromptBytes(input)
	} ?: throw IOException("Could not open the file.")

	return decodePromptText(bytes)
}
