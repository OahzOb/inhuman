package com.inhuman.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.R
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.ConversationSearchHit
import com.inhuman.core.design.Eyebrow
import com.inhuman.core.design.WindowWidthClass
import com.inhuman.core.design.currentWindowFrame
import com.inhuman.core.design.Hairline
import com.inhuman.core.design.InhumanTheme
import com.inhuman.core.design.Layout
import com.inhuman.core.design.MetaLine
import com.inhuman.core.design.StatusDot
import com.inhuman.core.design.WorkbenchGrid
import com.inhuman.core.design.WorkbenchSwitch
import com.inhuman.core.design.Wordmark
import com.inhuman.core.design.comfortableGutter
import com.inhuman.core.design.prefersFabForPrimaryAction
import com.inhuman.ui.backup.BackupReminderUi
import com.inhuman.ui.common.highlightSnippet
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * The conversation list.
 *
 * The layout differs by form factor rather than scaling: a phone gets a
 * floating action button within thumb reach and a tighter gutter, a tablet gets
 * a top-bar action and a capped text measure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
	viewModel: ConversationsViewModel,
	onOpenConversation: (String) -> Unit,
	onOpenSearchHit: (ConversationSearchHit) -> Unit = { onOpenConversation(it.conversationId) },
	onOpenSettings: () -> Unit,
	onOpenAgents: () -> Unit,
	onOpenWorkspaces: () -> Unit,
	onNewWorkspace: () -> Unit,
	sshToolEnabled: Boolean = false,
	bilibiliEnabled: Boolean = false,
	backupReminder: BackupReminderUi? = null,
	onOpenBackup: () -> Unit = {},
	onLaterBackup: () -> Unit = {},
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	var pendingDelete by remember { mutableStateOf<ConversationEntity?>(null) }
	var showCreateSheet by remember { mutableStateOf(false) }
	var showBulkDelete by remember { mutableStateOf(false) }
	// A bundle import creates a conversation the user did not configure; when
	// that fails, the reason is the only thing they can act on, so it is shown
	// rather than dropped. Success is a navigation, not a message.
	val importContext = LocalContext.current
	var importBundleError by remember { mutableStateOf<String?>(null) }
	val bundleImporter = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			viewModel.importConversationBundle(uri) { id, error ->
				if (id != null) {
					showCreateSheet = false
					onOpenConversation(id)
				} else {
					importBundleError = error
						?: importContext.getString(R.string.home_import_bundle_unknown)
				}
			}
		}
	}

	BackHandler(enabled = state.selecting) { viewModel.cancelSelection() }

	// The speech marker is a fact about the workspace, and the workspace can
	// gain audio while this screen is away. Rescan on resume, and once more
	// when the list has actually loaded -- the first resume races the load.
	LifecycleResumeEffect(Unit) {
		viewModel.refreshSpeechReady()
		onPauseOrDispose { }
	}
	LaunchedEffect(state.loaded, state.conversations.size) {
		if (state.loaded) viewModel.refreshSpeechReady()
	}

	// Ask for the file count when the question is about to be shown, not when it
	// is answered -- by then the folder is gone and there is nothing to count.
	LaunchedEffect(pendingDelete?.id) {
		pendingDelete?.let { viewModel.countFiles(it) }
	}

	/**
	 * Drop the confirmation when the conversation it names is gone.
	 *
	 * `pendingDelete` holds a *copy* of a conversation, taken when the icon was
	 * tapped. That copy does not stop being a valid object when the row is
	 * deleted, so the dialog stayed on screen naming a conversation that no
	 * longer existed -- and if nothing else closed it, it stayed for the rest of
	 * the session.
	 *
	 * Keyed on the whole list so that any change re-evaluates it. The check is
	 * by id against the live rows, which is the only question that matters:
	 * "is the thing this dialog is about still here".
	 */
	LaunchedEffect(state.conversations) {
		val id = pendingDelete?.id ?: return@LaunchedEffect
		if (state.conversations.none { it.id == id }) pendingDelete = null
	}

	val frame = currentWindowFrame()
	val useFab = prefersFabForPrimaryAction()
	val twoPane = frame.usesTwoPane
	val wideHeader = frame.widthClass == WindowWidthClass.Expanded
	val compactActions = frame.usesHeaderOverflow

	Scaffold(
		topBar = {
			HomeHeader(
				wide = wideHeader,
				showCreate = !useFab,
				compactActions = compactActions,
				onOpenWorkspaces = onOpenWorkspaces,
				onOpenAgents = onOpenAgents,
				onOpenSettings = onOpenSettings,
				onCreate = { showCreateSheet = true },
			)
		},
		floatingActionButton = {
			if (useFab) {
				FloatingActionButton(
					onClick = { showCreateSheet = true },
					containerColor = MaterialTheme.colorScheme.primary,
					contentColor = MaterialTheme.colorScheme.onPrimary,
					shape = MaterialTheme.shapes.medium,
				) {
					Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.common_create))
				}
			}
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { inner ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(inner)
		) {
			when {
				// Nothing is shown until the first read completes, so an empty
				// database does not flash "no conversations" over a list that
				// is about to appear.
				!state.loaded -> Unit

				twoPane -> Row(modifier = Modifier.fillMaxSize()) {
					HomeRail(
						conversationCount = state.conversations.size,
						sshToolEnabled = sshToolEnabled,
						bilibiliEnabled = bilibiliEnabled,
						modifier = Modifier
							.width(320.dp)
							.fillMaxSize(),
					)
					Box(
						modifier = Modifier
							.fillMaxSize()
							.weight(1f),
					) {
						ConversationPanel(
							conversations = state.conversations,
							workspaceNames = state.workspaceNames,
							speechReady = state.speechReadyConversations,
							searchQuery = state.searchQuery,
							searchResults = state.searchResults,
							searching = state.searching,
							selecting = state.selecting,
							selectedIds = state.selectedIds,
							deletingSelection = state.deletingSelection,
							error = state.error,
							onBeginSelection = viewModel::beginSelection,
							onToggleSelection = viewModel::toggleSelection,
							onToggleSelectAll = viewModel::toggleSelectAll,
							onCancelSelection = viewModel::cancelSelection,
							onDeleteSelected = { showBulkDelete = true },
							onClearError = viewModel::clearError,
							onSearchChange = viewModel::onSearchQueryChange,
							onClearSearch = viewModel::clearSearch,
							onOpenSearchHit = onOpenSearchHit,
							onOpen = onOpenConversation,
							onTogglePin = viewModel::setPinned,
							onRequestDelete = { pendingDelete = it },
							onCreate = { showCreateSheet = true },
							onOpenWorkspaces = onOpenWorkspaces,
							onOpenAssistants = onOpenAgents,
							compactLayout = frame.isCompact,
							showStartButton = true,
							backupReminder = backupReminder,
							onOpenBackup = onOpenBackup,
							onLaterBackup = onLaterBackup,
						)
					}
				}

				else -> ConversationPanel(
					conversations = state.conversations,
					workspaceNames = state.workspaceNames,
					speechReady = state.speechReadyConversations,
					searchQuery = state.searchQuery,
					searchResults = state.searchResults,
					searching = state.searching,
					selecting = state.selecting,
					selectedIds = state.selectedIds,
					deletingSelection = state.deletingSelection,
					error = state.error,
					onBeginSelection = viewModel::beginSelection,
					onToggleSelection = viewModel::toggleSelection,
					onToggleSelectAll = viewModel::toggleSelectAll,
					onCancelSelection = viewModel::cancelSelection,
					onDeleteSelected = { showBulkDelete = true },
					onClearError = viewModel::clearError,
					onSearchChange = viewModel::onSearchQueryChange,
					onClearSearch = viewModel::clearSearch,
					onOpenSearchHit = onOpenSearchHit,
					onOpen = onOpenConversation,
					onTogglePin = viewModel::setPinned,
					onRequestDelete = { pendingDelete = it },
					onCreate = { showCreateSheet = true },
					onOpenWorkspaces = onOpenWorkspaces,
					onOpenAssistants = onOpenAgents,
					compactLayout = frame.isCompact,
					showStartButton = false,
					backupReminder = backupReminder,
					onOpenBackup = onOpenBackup,
					onLaterBackup = onLaterBackup,
					modifier = Modifier
						.fillMaxSize()
						.padding(bottom = if (useFab) 72.dp else 0.dp),
				)
			}
		}
	}

	if (showCreateSheet) {
		CreateSheet(
			workspaces = state.permanentWorkspaces,
			continuous = state.newConversationContinuous,
			onContinuousChange = viewModel::setNewConversationContinuous,
			onImportBundle = {
				// Close the sheet before the system picker opens: the picker is a
				// separate window, and a sheet behind it would still be
				// "dismissible" while the result is in flight.
				showCreateSheet = false
				bundleImporter.launch(arrayOf("application/zip", "application/x-zip-compressed"))
			},
			onDismiss = { showCreateSheet = false },
			onConversation = { workspaceId ->
				showCreateSheet = false
				viewModel.startNewConversation(workspaceId, onOpenConversation)
			},
			onNewWorkspace = {
				showCreateSheet = false
				onNewWorkspace()
			},
		)
	}

	importBundleError?.let { message ->
		AlertDialog(
			onDismissRequest = { importBundleError = null },
			title = { Text(stringResource(R.string.home_import_bundle)) },
			text = { Text(stringResource(R.string.home_import_bundle_failed, message)) },
			confirmButton = {
				TextButton(onClick = { importBundleError = null }) {
					Text(stringResource(R.string.common_ok))
				}
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	// Deleting a multi-selection asks once, naming how many threads go and what
	// happens to their workspaces. Permanent workspace files are not touched.
	if (showBulkDelete) {
		val count = state.selectedIds.size
		AlertDialog(
			onDismissRequest = { showBulkDelete = false },
			title = {
				Text(
					pluralStringResource(
						R.plurals.home_delete_selected_title,
						count,
						count,
					),
				)
			},
			text = {
				Text(stringResource(R.string.home_delete_selected_body))
			},
			confirmButton = {
				TextButton(
					enabled = !state.deletingSelection,
					onClick = {
						showBulkDelete = false
						viewModel.deleteSelected()
					},
				) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
			},
			dismissButton = {
				TextButton(onClick = { showBulkDelete = false }) { Text(stringResource(R.string.common_cancel)) }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	// Deleting a conversation destroys its folder too, so it asks first.
	//
	// The confirmation names what goes with it rather than only the title: the
	// files in the workspace are not visible from this screen, and "delete this
	// conversation?" reads like it removes a list entry.
	pendingDelete?.let { conversation ->
		val fileCount = state.deletePreviewCounts[conversation.id]
		val permanent = conversation.workspaceId in state.permanentWorkspaceIds
		val workspaceName = state.workspaceNames[conversation.workspaceId] ?: "the workspace"

		// One meaning of "close", used by all three ways out.
		//
		// The dialog can be closed by Cancel, by tapping outside it, or by the
		// system Back button, and the first version handled them inconsistently:
		// Cancel cleared both pieces of state, while `onDismissRequest` cleared
		// only the cached file count. So tapping outside left the dialog open --
		// and visibly changed it, because the count it had just discarded was
		// the only thing making the body read "its folder is empty" instead of
		// the fallback "and its folder".
		//
		// That is the whole bug: a dismissal that dismissed part of the state.
		// Written once here so the next path added cannot get it half right.
		val closeDialog = {
			viewModel.cancelDelete()
			pendingDelete = null
		}

		AlertDialog(
			onDismissRequest = closeDialog,
			title = { Text(stringResource(R.string.home_delete_title, conversation.title)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(
						text = if (permanent) {
							val suffix = when {
								fileCount == null -> stringResource(R.string.home_delete_suffix_end)
								fileCount == 0 -> stringResource(R.string.home_delete_suffix_empty)
								else -> pluralStringResource(
									R.plurals.home_delete_suffix_files,
									fileCount,
									fileCount,
								)
							}
							stringResource(
								R.string.home_delete_permanent_body,
								workspaceName,
								suffix,
							)
						} else {
							when {
								fileCount == null -> stringResource(R.string.home_delete_body_folder_unknown)
								fileCount == 0 -> stringResource(R.string.home_delete_body_folder_empty)
								else -> pluralStringResource(
									R.plurals.home_delete_body_folder_files,
									fileCount,
									fileCount,
								)
							}
						},
					)
					Text(
						text = if (permanent) {
							stringResource(R.string.home_delete_permanent_note)
						} else {
							stringResource(R.string.home_delete_note)
						},
						color = if (permanent) {
							MaterialTheme.colorScheme.onSurfaceVariant
						} else {
							MaterialTheme.colorScheme.error
						},
					)
				}
			},
			confirmButton = {
				TextButton(
					// The dialog is still on screen while the delete runs, so
					// the button has to stop accepting taps for it.
					enabled = conversation.id !in state.deletingIds,
					onClick = { viewModel.delete(conversation) },
				) {
					Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = {
				TextButton(onClick = closeDialog) { Text(stringResource(R.string.common_cancel)) }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	// Which assistant to start with.
	//
	// Asked before the conversation exists rather than after, because the
	// assistant's instructions shape every turn that follows, and retrofitting
	// them would mean the first exchange was answered under different rules than
	// the rest.
	state.choosingAgent?.let { available ->
		AlertDialog(
			onDismissRequest = viewModel::cancelChoosing,
			title = { Text(stringResource(R.string.home_start_assistant_title)) },
			text = {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(max = 400.dp)
						.verticalScroll(rememberScrollState()),
					verticalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
				) {
					available.forEach { agent ->
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.background(
									MaterialTheme.colorScheme.surfaceVariant,
									RoundedCornerShape(10.dp),
								)
								.clickable { viewModel.chooseAgent(agent, onOpenConversation) }
								.padding(12.dp),
							verticalArrangement = Arrangement.spacedBy(3.dp),
						) {
							Text(
								text = agent.name.ifBlank { stringResource(R.string.home_unnamed) },
								style = MaterialTheme.typography.bodyLarge,
								color = MaterialTheme.colorScheme.onSurface,
							)
							// Two lines of the instructions, so the choice is
							// informed rather than a guess between two names.
							Text(
								text = agent.systemPrompt,
								style = MaterialTheme.typography.labelLarge,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 2,
								overflow = TextOverflow.Ellipsis,
							)
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { viewModel.chooseAgent(null, onOpenConversation) }) {
					Text(stringResource(R.string.home_no_assistant))
				}
			},
			dismissButton = {
				TextButton(onClick = viewModel::cancelChoosing) { Text(stringResource(R.string.common_cancel)) }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateSheet(
	workspaces: List<com.inhuman.core.data.db.WorkspaceEntity>,
	continuous: Boolean,
	onContinuousChange: (Boolean) -> Unit,
	onImportBundle: () -> Unit,
	onDismiss: () -> Unit,
	onConversation: (String?) -> Unit,
	onNewWorkspace: () -> Unit,
) {
	val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
		skipPartiallyExpanded = true,
	)
	androidx.compose.material3.ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = sheetState,
		containerColor = MaterialTheme.colorScheme.surface,
		shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
	) {
		var choosingWorkspace by remember { mutableStateOf(false) }
		Column(
			modifier = Modifier
				// Constrain first, fill second: `fillMaxWidth` consumes the
				// incoming maximum, so the 640dp cap was inert on a tablet.
				.widthIn(max = 640.dp)
				.fillMaxWidth()
				.padding(horizontal = 24.dp)
				.padding(bottom = 24.dp)
				// The sheet now has four choices plus the continuous-session
				// disclosure. On a 360x640 phone at 1.5x font that can exceed the
				// sheet's height, so the content scrolls rather than clipping the
				// last choice.
				.verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed),
		) {
			if (choosingWorkspace) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
				) {
					IconButton(
						onClick = { choosingWorkspace = false },
					) {
						Icon(
							Icons.AutoMirrored.Filled.ArrowBack,
							contentDescription = stringResource(R.string.home_create_back),
							modifier = Modifier.size(18.dp),
						)
					}
					Spacer(Modifier.width(6.dp))
					Text(
						text = stringResource(R.string.home_create_choose_workspace),
						style = MaterialTheme.typography.titleLarge,
						color = MaterialTheme.colorScheme.onSurface,
					)
				}
				Spacer(Modifier.height(8.dp))
				ContinuousSessionChoice(
					continuous = continuous,
					onContinuousChange = onContinuousChange,
				)
				Hairline()
				if (workspaces.isEmpty()) {
					Text(
						text = stringResource(R.string.home_create_no_workspaces),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(bottom = 4.dp),
					)
				} else {
					workspaces.forEach { workspace ->
						CreateChoice(
							title = workspace.name,
							detail = stringResource(R.string.home_create_permanent_workspace),
							onClick = { onConversation(workspace.id) },
						)
					}
					Spacer(Modifier.height(8.dp))
					Hairline()
				}
				CreateChoice(
					title = stringResource(R.string.workspaces_new),
					detail = stringResource(R.string.home_create_new_workspace_detail),
					onClick = onNewWorkspace,
				)
			} else {
				Text(
					text = stringResource(R.string.home_create_heading),
					style = MaterialTheme.typography.headlineMedium,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Spacer(Modifier.height(8.dp))
				CreateChoice(
					title = stringResource(R.string.home_create_new_conversation),
					detail = stringResource(R.string.home_create_new_conversation_detail),
					onClick = { onConversation(null) },
					leadingIcon = Icons.Filled.Add,
				)
				CreateChoice(
					title = stringResource(R.string.home_import_bundle),
					detail = stringResource(R.string.home_import_bundle_detail),
					onClick = onImportBundle,
				)
				CreateChoice(
					title = stringResource(R.string.workspaces_new),
					detail = stringResource(R.string.home_create_new_workspace_detail),
					onClick = onNewWorkspace,
					leadingIcon = Icons.Outlined.CreateNewFolder,
				)
				CreateChoice(
					title = stringResource(R.string.home_create_choose_workspace),
					detail = stringResource(R.string.home_create_choose_workspace_detail),
					onClick = { choosingWorkspace = true },
					leadingIcon = Icons.Outlined.FolderOpen,
				)
				Hairline()
				ContinuousSessionChoice(
					continuous = continuous,
					onContinuousChange = onContinuousChange,
				)
			}
		}
	}
}

@Composable
private fun CreateChoice(
	title: String,
	detail: String,
	onClick: () -> Unit,
	leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(vertical = 11.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (leadingIcon != null) {
			Icon(
				leadingIcon,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(18.dp),
			)
			Spacer(Modifier.width(10.dp))
		} else {
			StatusDot(MaterialTheme.colorScheme.primary, size = 5.dp)
			Spacer(Modifier.width(10.dp))
		}
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = detail,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Icon(
			Icons.AutoMirrored.Filled.KeyboardArrowRight,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.size(18.dp),
		)
	}
}

/**
 * The create-form switch for a continuous session, with its disclosure.
 *
 * Default-on is a product decision, and a default the user never saw would be
 * automatic spend the app refuses elsewhere. The sentence therefore sits beside
 * the switch every time the sheet opens -- not behind an "advanced" tap and not
 * only on first launch. Turning it off is remembered for the next new
 * conversation by the view model, so the preference is one decision, not a
 * question asked again every time.
 */
@Composable
private fun ContinuousSessionChoice(
	continuous: Boolean,
	onContinuousChange: (Boolean) -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 10.dp),
		verticalAlignment = Alignment.Top,
	) {
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(3.dp),
		) {
			Text(
				text = stringResource(R.string.home_create_continuous),
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onBackground,
			)
			Text(
				text = stringResource(R.string.home_create_continuous_detail),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		WorkbenchSwitch(
			checked = continuous,
			onCheckedChange = onContinuousChange,
			modifier = Modifier.padding(start = 12.dp),
		)
	}
}

@Composable
private fun HomeHeader(
	wide: Boolean,
	showCreate: Boolean,
	compactActions: Boolean,
	onOpenWorkspaces: () -> Unit,
	onOpenAgents: () -> Unit,
	onOpenSettings: () -> Unit,
	onCreate: () -> Unit,
) {
	Column(modifier = Modifier.statusBarsPadding()) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = comfortableGutter(), vertical = 14.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				Wordmark(size = if (compactActions) 16 else 20)
				Text(
					text = stringResource(R.string.home_tagline),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			if (wide) {
				HeaderTextAction(stringResource(R.string.workspaces_title), Icons.Outlined.FolderOpen, onOpenWorkspaces)
				HeaderTextAction(stringResource(R.string.common_assistants), Icons.Outlined.Person, onOpenAgents)
				HeaderTextAction(stringResource(R.string.common_settings), Icons.Outlined.Settings, onOpenSettings)
				Button(
					onClick = onCreate,
					shape = MaterialTheme.shapes.small,
					contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
				) {
					Icon(
						Icons.Filled.Add,
						contentDescription = null,
						modifier = Modifier.size(16.dp),
					)
					Spacer(Modifier.width(6.dp))
					Text(stringResource(R.string.common_create), style = MaterialTheme.typography.labelLarge)
				}
			} else if (compactActions) {
				// At a large font scale the wordmark alone can consume most of a
				// 360dp bar; three 48dp destinations then push Settings off-screen.
				// One menu keeps every destination reachable without shrinking the
				// logo or hiding Settings silently.
				Box {
					var menu by remember { mutableStateOf(false) }
					IconButton(onClick = { menu = true }) {
						Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_more_destinations))
					}
					DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
						// Create lives here when the header collapsed: at large
						// font the three destinations become one menu, and a
						// Comfortable preference also removes the FAB, so
						// without this entry that combination had no way to
						// start a conversation.
						if (showCreate) {
							DropdownMenuItem(
								text = { Text(stringResource(R.string.common_create)) },
								leadingIcon = {
									Icon(Icons.Filled.Add, contentDescription = null)
								},
								onClick = {
									menu = false
									onCreate()
								},
							)
						}
						DropdownMenuItem(
							text = { Text(stringResource(R.string.workspaces_title)) },
							leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
							onClick = {
								menu = false
								onOpenWorkspaces()
							},
						)
						DropdownMenuItem(
							text = { Text(stringResource(R.string.common_assistants)) },
							leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
							onClick = {
								menu = false
								onOpenAgents()
							},
						)
						DropdownMenuItem(
							text = { Text(stringResource(R.string.common_settings)) },
							leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
							onClick = {
								menu = false
								onOpenSettings()
							},
						)
					}
				}
			} else {
				// The three destinations used to live behind a three-dot menu.
				// On a phone the icons fit on the bar, and hiding them only made
				// the app feel smaller.
				IconButton(onClick = onOpenWorkspaces) {
					Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.workspaces_title))
				}
				IconButton(onClick = onOpenAgents) {
					Icon(Icons.Outlined.Person, contentDescription = stringResource(R.string.common_assistants))
				}
				IconButton(onClick = onOpenSettings) {
					Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.common_settings))
				}
				if (showCreate) {
					Button(
						onClick = onCreate,
						shape = MaterialTheme.shapes.small,
						contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
					) {
						Icon(
							Icons.Filled.Add,
							contentDescription = null,
							modifier = Modifier.size(16.dp),
						)
						Spacer(Modifier.width(4.dp))
						Text(stringResource(R.string.common_create), style = MaterialTheme.typography.labelMedium)
					}
				}
			}
		}
		Hairline()
	}
}

@Composable
private fun HeaderTextAction(
	label: String,
	icon: androidx.compose.ui.graphics.vector.ImageVector,
	onClick: () -> Unit,
) {
	TextButton(
		onClick = onClick,
		colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
			contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		),
	) {
		Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
		Spacer(Modifier.width(6.dp))
		Text(label, style = MaterialTheme.typography.labelLarge)
	}
}

@Composable
private fun HomeRail(
	conversationCount: Int,
	sshToolEnabled: Boolean,
	bilibiliEnabled: Boolean,
	modifier: Modifier = Modifier,
) {
	Box(modifier.background(MaterialTheme.colorScheme.surface)) {
		WorkbenchGrid(Modifier.fillMaxSize())
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(horizontal = 32.dp, vertical = 36.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp),
		) {
			Eyebrow(stringResource(R.string.home_rail_eyebrow))
			Text(
				text = stringResource(R.string.home_rail_title),
				style = MaterialTheme.typography.displaySmall,
				color = MaterialTheme.colorScheme.onBackground,
			)
			Text(
				text = stringResource(R.string.home_rail_body),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(18.dp))
			MetaLine(
				stringResource(R.string.home_rail_files_label),
				stringResource(R.string.home_rail_files_value),
			)
			MetaLine(
				stringResource(R.string.home_rail_tools_label),
				when {
					// The summary is a capability claim: it must mention an
					// external source only while that source is switched on,
					// and it must not drop one that is.
					bilibiliEnabled && sshToolEnabled ->
						stringResource(R.string.home_rail_tools_value_ssh_bilibili)

					bilibiliEnabled -> stringResource(R.string.home_rail_tools_value_bilibili)
					sshToolEnabled -> stringResource(R.string.home_rail_tools_value_ssh)
					else -> stringResource(R.string.home_rail_tools_value)
				},
			)
			MetaLine(
				stringResource(R.string.home_rail_model_label),
				stringResource(R.string.home_rail_model_value),
			)
			Spacer(Modifier.weight(1f))
			Hairline()
			Spacer(Modifier.height(12.dp))
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				StatusDot(MaterialTheme.colorScheme.secondary)
				Eyebrow(
					text = pluralStringResource(
						R.plurals.home_threads_stored,
						conversationCount,
						conversationCount,
					),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun BackupReminderBanner(
	reminder: BackupReminderUi,
	onOpen: () -> Unit,
	onLater: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 8.dp),
	) {
		// Text above the actions, not beside them: at 360dp a sentence in a
		// weight(1f) column wraps around the buttons ("手机 / 上。"), which reads
		// as a broken line rather than a narrower measure.
		Text(
			text = stringResource(R.string.backup_reminder_title),
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.primary,
		)
		Text(
			text = if (reminder.neverBackedUp) {
				stringResource(R.string.backup_reminder_never)
			} else {
				stringResource(R.string.backup_reminder_due, reminder.daysSince)
			},
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.End,
		) {
			TextButton(onClick = onOpen) {
				Text(stringResource(R.string.backup_reminder_action))
			}
			TextButton(onClick = onLater) {
				Text(stringResource(R.string.backup_reminder_later))
			}
		}
		Spacer(Modifier.height(6.dp))
		Hairline()
	}
}

@Composable
private fun ConversationPanel(
	conversations: List<ConversationEntity>,
	workspaceNames: Map<String, String>,
	speechReady: Set<String>,
	searchQuery: String,
	searchResults: List<ConversationSearchHit>,
	searching: Boolean,
	selecting: Boolean,
	selectedIds: Set<String>,
	deletingSelection: Boolean,
	error: String?,
	onBeginSelection: (String?) -> Unit,
	onToggleSelection: (String) -> Unit,
	onToggleSelectAll: () -> Unit,
	onCancelSelection: () -> Unit,
	onDeleteSelected: () -> Unit,
	onClearError: () -> Unit,
	onSearchChange: (String) -> Unit,
	onClearSearch: () -> Unit,
	onOpenSearchHit: (ConversationSearchHit) -> Unit,
	onOpen: (String) -> Unit,
	onTogglePin: (ConversationEntity, Boolean) -> Unit,
	onRequestDelete: (ConversationEntity) -> Unit,
	onCreate: () -> Unit,
	onOpenWorkspaces: () -> Unit,
	onOpenAssistants: () -> Unit,
	compactLayout: Boolean,
	showStartButton: Boolean,
	backupReminder: BackupReminderUi?,
	onOpenBackup: () -> Unit,
	onLaterBackup: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val compact = compactLayout
	// All rows the delete action would name. Not a separate "all" flag: the
	// list can change while selection mode is open, and the header must
	// describe the checkboxes that are actually on screen.
	val allSelected = conversations.isNotEmpty() && conversations.all { it.id in selectedIds }
	// `fillMaxSize` here, not only at the single-pane call site: in the
	// two-pane branch the default modifier let the Box wrap its 760dp child,
	// so the list sat against the rail instead of centring in the pane.
	Box(
		modifier = modifier.fillMaxSize(),
		contentAlignment = Alignment.TopCenter,
	) {
		if (conversations.isEmpty()) {
			WorkbenchGrid(Modifier.fillMaxSize())
		}
		Column(
			modifier = Modifier
				.widthIn(max = 760.dp)
				.fillMaxWidth()
				.padding(
					horizontal = if (compact) comfortableGutter() else 32.dp,
					vertical = if (compact) 20.dp else 36.dp,
				),
			verticalArrangement = Arrangement.spacedBy(0.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Eyebrow(stringResource(R.string.home_conversations))
				Spacer(Modifier.weight(1f))
				if (selecting) {
					Text(
						text = pluralStringResource(
							R.plurals.home_selected_count,
							selectedIds.size,
							selectedIds.size,
						),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.primary,
					)
					// The list-level action belongs beside the count, not in
					// the Cancel/Delete row: on a 360dp phone four controls
					// there left the hint with no width of its own.
					Spacer(Modifier.width(2.dp))
					TextButton(
						onClick = onToggleSelectAll,
						enabled = conversations.isNotEmpty() && !deletingSelection,
					) {
						Text(
							stringResource(
								if (allSelected) R.string.home_deselect_all else R.string.home_select_all,
							),
							style = MaterialTheme.typography.labelMedium,
						)
					}
				} else {
					Text(
						text = twoDigits(conversations.size),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					if (conversations.isNotEmpty()) {
						TextButton(onClick = { onBeginSelection(null) }) {
							Text(stringResource(R.string.common_select), style = MaterialTheme.typography.labelMedium)
						}
					}
				}
			}
			error?.let { message ->
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = message,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
						modifier = Modifier.weight(1f),
					)
					TextButton(onClick = onClearError) { Text(stringResource(R.string.common_dismiss)) }
				}
			}
			Spacer(Modifier.height(10.dp))
			Hairline()
			if (!selecting) {
				backupReminder?.let { reminder ->
					BackupReminderBanner(
						reminder = reminder,
						onOpen = onOpenBackup,
						onLater = onLaterBackup,
					)
				}
			}
			if (selecting) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = stringResource(R.string.home_tap_to_select),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.weight(1f),
					)
					TextButton(onClick = onCancelSelection, enabled = !deletingSelection) {
						Text(stringResource(R.string.common_cancel))
					}
					TextButton(
						onClick = onDeleteSelected,
						enabled = selectedIds.isNotEmpty() && !deletingSelection,
					) {
						Text(
							stringResource(R.string.common_delete),
							color = MaterialTheme.colorScheme.error,
						)
					}
				}
				Spacer(Modifier.height(4.dp))
				ConversationList(
					conversations = conversations,
					workspaceNames = workspaceNames,
					speechReady = speechReady,
					selecting = true,
					selectedIds = selectedIds,
					onOpen = onOpen,
					onToggleSelection = onToggleSelection,
					onBeginSelection = onBeginSelection,
					onTogglePin = onTogglePin,
					onRequestDelete = onRequestDelete,
				)
			} else {
				Spacer(Modifier.height(8.dp))
				OutlinedTextField(
					value = searchQuery,
					onValueChange = onSearchChange,
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
					shape = MaterialTheme.shapes.small,
					placeholder = {
						Text(
							text = if (currentWindowFrame().fontScale > 1.3f) {
								stringResource(R.string.home_search_short)
							} else {
								stringResource(R.string.home_search_placeholder)
							},
							style = MaterialTheme.typography.bodyMedium,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					},
					leadingIcon = {
						Icon(
							Icons.Outlined.Search,
							contentDescription = null,
							modifier = Modifier.size(18.dp),
						)
					},
					trailingIcon = if (searchQuery.isNotEmpty()) {
						{
							IconButton(onClick = onClearSearch) {
								Icon(
									Icons.Filled.Close,
									contentDescription = stringResource(R.string.home_search_clear),
									modifier = Modifier.size(18.dp),
								)
							}
						}
					} else {
						null
					},
				)
				Spacer(Modifier.height(if (searchQuery.isBlank() && conversations.isEmpty()) 26.dp else 4.dp))
				when {
					searchQuery.isNotBlank() -> SearchResults(
						query = searchQuery,
						results = searchResults,
						searching = searching,
						onOpen = onOpenSearchHit,
					)

					conversations.isEmpty() -> EmptyState(
						showStartButton = showStartButton,
						onCreate = onCreate,
						onOpenWorkspaces = onOpenWorkspaces,
						onOpenAssistants = onOpenAssistants,
					)

					else -> ConversationList(
						conversations = conversations,
						workspaceNames = workspaceNames,
						speechReady = speechReady,
						selecting = false,
						selectedIds = emptySet(),
						onOpen = onOpen,
						onToggleSelection = onToggleSelection,
						onBeginSelection = onBeginSelection,
						onTogglePin = onTogglePin,
						onRequestDelete = onRequestDelete,
					)
				}
			}
		}
	}
}

@Composable
private fun SearchResults(
	query: String,
	results: List<ConversationSearchHit>,
	searching: Boolean,
	onOpen: (ConversationSearchHit) -> Unit,
) {
	when {
		results.isEmpty() && searching -> Text(
			text = stringResource(R.string.home_searching),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(vertical = 10.dp),
		)

		results.isEmpty() -> Text(
			text = stringResource(R.string.home_no_matches, query),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(vertical = 10.dp),
		)

		else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
			itemsIndexed(
				results,
				key = { _, hit -> "${hit.conversationId}:${hit.messageId ?: "title"}" },
			) { _, hit ->
				SearchResultRow(hit = hit, query = query, onOpen = { onOpen(hit) })
				Hairline()
			}
		}
	}
}

@Composable
private fun SearchResultRow(
	hit: ConversationSearchHit,
	query: String,
	onOpen: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onOpen)
			.padding(vertical = 12.dp),
		verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = hit.conversationTitle.ifBlank { stringResource(R.string.home_untitled) },
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			Spacer(Modifier.width(8.dp))
			Eyebrow(
				text = when (hit.role) {
					"user" -> stringResource(R.string.home_role_you)
					"assistant" -> stringResource(R.string.home_role_assistant)
					else -> stringResource(R.string.home_role_title)
				},
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Text(
			text = if (hit.messageId == null) {
				androidx.compose.ui.text.AnnotatedString(
					stringResource(R.string.home_conversation_title),
				)
			} else {
				highlightSnippet(hit.snippet, query)
			},
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 3,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun ConversationList(
	conversations: List<ConversationEntity>,
	workspaceNames: Map<String, String>,
	speechReady: Set<String>,
	selecting: Boolean,
	selectedIds: Set<String>,
	onOpen: (String) -> Unit,
	onToggleSelection: (String) -> Unit,
	onBeginSelection: (String?) -> Unit,
	onTogglePin: (ConversationEntity, Boolean) -> Unit,
	onRequestDelete: (ConversationEntity) -> Unit,
) {
	// One clock reading for the whole list, re-read once a minute. Read per row
	// it would be the same number computed N times and still frozen.
	val now = rememberNowMillis()

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(vertical = 0.dp),
		verticalArrangement = Arrangement.spacedBy(0.dp),
	) {
		itemsIndexed(conversations, key = { _, conversation -> conversation.id }) { index, conversation ->
			ConversationRow(
				index = index,
				conversation = conversation,
				workspace = workspaceNames[conversation.workspaceId],
				hasSpeech = conversation.id in speechReady,
				now = now,
				selecting = selecting,
				selected = conversation.id in selectedIds,
				onClick = { onOpen(conversation.id) },
				onToggleSelection = { onToggleSelection(conversation.id) },
				onBeginSelection = { onBeginSelection(conversation.id) },
				onTogglePin = { onTogglePin(conversation, !conversation.pinned) },
				onDelete = { onRequestDelete(conversation) },
			)
			Hairline()
		}
	}
}

@Composable
private fun ConversationRow(
	index: Int,
	conversation: ConversationEntity,
	workspace: String?,
	hasSpeech: Boolean,
	now: Long,
	selecting: Boolean,
	selected: Boolean,
	onClick: () -> Unit,
	onToggleSelection: () -> Unit,
	onBeginSelection: () -> Unit,
	onTogglePin: () -> Unit,
	onDelete: () -> Unit,
) {
	var menu by remember { mutableStateOf(false) }
	// The pin was a 13dp undescribed icon and a tinted index number, so a
	// screen reader had no way to know the row was pinned. The state belongs on
	// the row, where the whole label is read.
	val pinnedState = stringResource(R.string.home_pinned_state)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(
				if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
				else androidx.compose.ui.graphics.Color.Transparent,
			)
			.semantics {
				if (conversation.pinned) stateDescription = pinnedState
			}
			.combinedClickable(
				onClick = { if (selecting) onToggleSelection() else onClick() },
				onLongClick = { if (selecting) onToggleSelection() else onBeginSelection() },
			)
			.padding(vertical = 14.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (selecting) {
			// No explicit size: Material's minimum interactive size is 48dp, and
			// 42dp was both below the guideline and unnecessary.
			Checkbox(
				checked = selected,
				onCheckedChange = { onToggleSelection() },
			)
		} else {
			Text(
				text = twoDigits(index + 1),
				modifier = Modifier.width(38.dp),
				style = MaterialTheme.typography.labelSmall,
				color = if (conversation.pinned) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
				},
			)
		}
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed),
		) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					text = conversation.title,
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onBackground,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f, fill = false),
				)
				if (hasSpeech) {
					Spacer(Modifier.width(8.dp))
					Icon(
						imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
						contentDescription = stringResource(R.string.home_speech_ready),
						tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
						modifier = Modifier.size(13.dp),
					)
				}
				if (conversation.pinned) {
					Spacer(Modifier.width(8.dp))
					Icon(
						imageVector = Icons.Outlined.PushPin,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(13.dp),
					)
				}
			}
			val timestamp = formatTimestamp(conversation.updatedAt, now)
			Text(
				text = buildString {
					append(timestamp)
					if (!workspace.isNullOrBlank() && workspace != conversation.title) {
						append("  ·  ")
						append(workspace)
					}
				},
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		if (!selecting) {
			Box {
				IconButton(onClick = { menu = true }) {
					Icon(
						Icons.Filled.MoreVert,
						contentDescription = stringResource(R.string.home_conversation_actions),
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(18.dp),
					)
				}
				DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
					DropdownMenuItem(
						text = {
							Text(
								stringResource(
									if (conversation.pinned) R.string.home_unpin else R.string.home_pin,
								),
							)
						},
						leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
						onClick = {
							menu = false
							onTogglePin()
						},
					)
					DropdownMenuItem(
						text = { Text(stringResource(R.string.common_delete)) },
						leadingIcon = {
							Icon(
								Icons.Outlined.Delete,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.error,
							)
						},
						onClick = {
							menu = false
							onDelete()
						},
					)
				}
			}
		}
	}
}

/**
 * Relative for anything recent, absolute once that stops being useful.
 *
 * ## The two things that were wrong with the obvious version
 *
 * **It read the clock at composition time.** Nothing re-triggers composition
 * when a minute passes, so "just now" stayed on screen for as long as the list
 * was left open -- a row could read "just now" hours later. There is nothing to
 * observe here, so the fix is to recompose on a timer: [rememberNowMillis]
 * below ticks once a minute, which is the resolution the strings actually have.
 *
 * **It built a `SimpleDateFormat` per row.** `DateFormat.getDateInstance`
 * returns a fresh instance every call, and it was called for every row on every
 * composition. One instance is enough; it is not thread-safe, so it is confined
 * to the main thread, which is where composition runs.
 */
private val absoluteDate: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

private fun twoDigits(value: Int): String = value.toString().padStart(2, '0')

@Composable
private fun formatTimestamp(millis: Long, now: Long): String {
	val age = now - millis
	return when {
		age < 60_000 -> stringResource(R.string.home_just_now)
		age < 3_600_000 -> {
			val minutes = (age / 60_000).toInt()
			pluralStringResource(R.plurals.home_minutes_ago, minutes, minutes)
		}
		age < 86_400_000 -> {
			val hours = (age / 3_600_000).toInt()
			pluralStringResource(R.plurals.home_hours_ago, hours, hours)
		}
		else -> absoluteDate.format(Date(millis))
	}
}

/**
 * The current time, re-read once a minute so relative labels stay true.
 *
 * Deliberately the cheapest thing that works: one `Long` of state and a delay
 * loop. A `derivedStateOf` over the clock would not recompose on its own,
 * because nothing changes to invalidate it.
 */
@Composable
private fun rememberNowMillis(): Long {
	var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
	LaunchedEffect(Unit) {
		while (true) {
			delay(60_000)
			now = System.currentTimeMillis()
		}
	}
	return now
}

@Composable
private fun EmptyState(
	showStartButton: Boolean,
	onCreate: () -> Unit,
	onOpenWorkspaces: () -> Unit,
	onOpenAssistants: () -> Unit,
) {
	Column(
		modifier = Modifier.fillMaxWidth(),
		horizontalAlignment = Alignment.Start,
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Eyebrow(stringResource(R.string.home_empty_eyebrow))
		Text(
			text = stringResource(R.string.home_empty_title),
			style = MaterialTheme.typography.headlineMedium,
			color = MaterialTheme.colorScheme.onBackground,
		)
		Text(
			text = stringResource(R.string.home_empty_body),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		if (showStartButton) {
			Button(
				onClick = onCreate,
				modifier = Modifier.padding(top = 6.dp),
				shape = MaterialTheme.shapes.small,
				contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp),
			) {
				Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
				Spacer(Modifier.width(7.dp))
				Text(stringResource(R.string.common_create), style = MaterialTheme.typography.labelLarge)
			}
		}
		Row(horizontalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed)) {
			TextButton(onClick = onOpenWorkspaces) { Text(stringResource(R.string.workspaces_title)) }
			TextButton(onClick = onOpenAssistants) { Text(stringResource(R.string.common_assistants)) }
		}
	}
}

@Preview(name = "Empty", showBackground = true, backgroundColor = 0xFFF3EFE5, widthDp = 411, heightDp = 900)
@Composable
private fun ConversationsEmptyPreview() {
	InhumanTheme(darkTheme = false) {
		// A preview cannot construct a real view model; the empty state is
		// previewed directly instead.
		Box(
			Modifier
				.fillMaxSize()
				.background(MaterialTheme.colorScheme.background)
				.padding(20.dp),
			contentAlignment = Alignment.TopStart,
		) {
			EmptyState(true, {}, {}, {})
		}
	}
}
