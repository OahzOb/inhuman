package com.inhuman.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.design.Eyebrow
import com.inhuman.core.design.FormFactor
import com.inhuman.core.design.Hairline
import com.inhuman.core.design.InhumanTheme
import com.inhuman.core.design.Layout
import com.inhuman.core.design.MetaLine
import com.inhuman.core.design.StatusDot
import com.inhuman.core.design.WorkbenchGrid
import com.inhuman.core.design.Wordmark
import com.inhuman.core.design.comfortableGutter
import com.inhuman.core.design.currentFormFactor
import com.inhuman.core.design.prefersFabForPrimaryAction
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
	onOpenSettings: () -> Unit,
	onOpenAgents: () -> Unit,
	onOpenWorkspaces: () -> Unit,
	onNewWorkspace: () -> Unit,
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	var pendingDelete by remember { mutableStateOf<ConversationEntity?>(null) }
	var showCreateSheet by remember { mutableStateOf(false) }

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

	val formFactor = currentFormFactor()
	val useFab = prefersFabForPrimaryAction()
	val wide = formFactor != FormFactor.Compact

	Scaffold(
		topBar = {
			HomeHeader(
				wide = wide,
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
					Icon(Icons.Filled.Add, contentDescription = "Create")
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

				wide -> Row(modifier = Modifier.fillMaxSize()) {
					HomeRail(
						conversationCount = state.conversations.size,
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
							onOpen = onOpenConversation,
							onTogglePin = viewModel::setPinned,
							onRequestDelete = { pendingDelete = it },
							onCreate = { showCreateSheet = true },
							onOpenWorkspaces = onOpenWorkspaces,
							onOpenAssistants = onOpenAgents,
							showStartButton = true,
						)
					}
				}

				else -> ConversationPanel(
					conversations = state.conversations,
					workspaceNames = state.workspaceNames,
					onOpen = onOpenConversation,
					onTogglePin = viewModel::setPinned,
					onRequestDelete = { pendingDelete = it },
					onCreate = { showCreateSheet = true },
					onOpenWorkspaces = onOpenWorkspaces,
					onOpenAssistants = onOpenAgents,
					showStartButton = false,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}
	}

	if (showCreateSheet) {
		CreateSheet(
			workspaces = state.permanentWorkspaces,
			onDismiss = { showCreateSheet = false },
			onConversation = { workspaceId ->
				showCreateSheet = false
				viewModel.startNewConversation(workspaceId)
			},
			onNewWorkspace = {
				showCreateSheet = false
				onNewWorkspace()
			},
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
			title = { Text("Delete \"${conversation.title}\"?") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(
						text = if (permanent) {
							buildString {
								append("This deletes the conversation and its rollback snapshots. ")
								append("Files in \"$workspaceName\" are kept")
								if (fileCount != null) {
									append(
										if (fileCount == 0) {
											"; the workspace is empty."
										} else if (fileCount == 1) {
											"; 1 file remains there."
										} else {
											"; $fileCount files remain there."
										},
									)
								} else {
									append(".")
								}
							}
						} else {
							when {
								fileCount == null -> "This deletes the conversation and its folder."
								fileCount == 0 -> "This deletes the conversation. Its folder is empty."
								fileCount == 1 -> "This deletes the conversation and 1 file in its folder."
								else -> "This deletes the conversation and $fileCount files in its folder."
							}
						},
					)
					Text(
						text = if (permanent) {
							"The conversation cannot be restored; the workspace files remain."
						} else {
							"This cannot be undone."
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
				TextButton(onClick = { viewModel.delete(conversation) }) {
					Text("Delete", color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = {
				TextButton(onClick = closeDialog) { Text("Cancel") }
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
			title = { Text("Start with which assistant?") },
			text = {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(max = 400.dp)
						.verticalScroll(rememberScrollState()),
					verticalArrangement = Arrangement.spacedBy(6.dp),
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
								text = agent.name.ifBlank { "(unnamed)" },
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
					Text("No assistant")
				}
			},
			dismissButton = {
				TextButton(onClick = viewModel::cancelChoosing) { Text("Cancel") }
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
				.fillMaxWidth()
				.widthIn(max = 640.dp)
				.padding(horizontal = 24.dp)
				.padding(bottom = 24.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			if (choosingWorkspace) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
				) {
					IconButton(
						onClick = { choosingWorkspace = false },
						modifier = Modifier.size(32.dp),
					) {
						Icon(
							Icons.AutoMirrored.Filled.ArrowBack,
							contentDescription = "Back",
							modifier = Modifier.size(18.dp),
						)
					}
					Spacer(Modifier.width(6.dp))
					Text(
						text = "Choose a workspace",
						style = MaterialTheme.typography.titleLarge,
						color = MaterialTheme.colorScheme.onSurface,
					)
				}
				Spacer(Modifier.height(8.dp))
				if (workspaces.isEmpty()) {
					Text(
						text = "No permanent workspaces yet.",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(bottom = 4.dp),
					)
				} else {
					workspaces.forEach { workspace ->
						CreateChoice(
							title = workspace.name,
							detail = "Permanent workspace",
							onClick = { onConversation(workspace.id) },
						)
					}
					Spacer(Modifier.height(8.dp))
					Hairline()
				}
				CreateChoice(
					title = "New workspace",
					detail = "Create a shared folder for files.",
					onClick = onNewWorkspace,
				)
			} else {
				Text(
					text = "Create",
					style = MaterialTheme.typography.headlineMedium,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Spacer(Modifier.height(8.dp))
				CreateChoice(
					title = "New conversation",
					detail = "Start in a temporary workspace.",
					onClick = { onConversation(null) },
					leadingIcon = Icons.Filled.Add,
				)
				CreateChoice(
					title = "New workspace",
					detail = "Create a shared folder for files.",
					onClick = onNewWorkspace,
					leadingIcon = Icons.Outlined.CreateNewFolder,
				)
				CreateChoice(
					title = "Choose a workspace",
					detail = "Start a conversation in an existing workspace.",
					onClick = { choosingWorkspace = true },
					leadingIcon = Icons.Outlined.FolderOpen,
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

@Composable
private fun HomeHeader(
	wide: Boolean,
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
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Wordmark()
				Text(
					text = "PRIVATE AGENT WORKBENCH",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Spacer(Modifier.weight(1f))
			if (wide) {
				HeaderTextAction("Workspaces", Icons.Outlined.FolderOpen, onOpenWorkspaces)
				HeaderTextAction("Assistants", Icons.Outlined.Person, onOpenAgents)
				HeaderTextAction("Settings", Icons.Outlined.Settings, onOpenSettings)
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
					Text("Create", style = MaterialTheme.typography.labelLarge)
				}
			} else {
				// The three destinations used to live behind a three-dot menu.
				// On a phone the icons fit on the bar, and hiding them only made
				// the app feel smaller.
				IconButton(onClick = onOpenWorkspaces) {
					Icon(Icons.Outlined.FolderOpen, contentDescription = "Workspaces")
				}
				IconButton(onClick = onOpenAgents) {
					Icon(Icons.Outlined.Person, contentDescription = "Assistants")
				}
				IconButton(onClick = onOpenSettings) {
					Icon(Icons.Outlined.Settings, contentDescription = "Settings")
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
			Eyebrow("Workbench / 01")
			Text(
				text = "A private agent\nwith a desk.",
				style = MaterialTheme.typography.displaySmall,
				color = MaterialTheme.colorScheme.onBackground,
			)
			Text(
				text = "Chat, files and tools in one place. Each conversation keeps " +
					"its own folder; the assistant cannot reach anything outside it.",
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(18.dp))
			MetaLine("Files", "App-private workspace")
			MetaLine("Tools", "Files, web, SSH on demand")
			MetaLine("Model", "Your configured provider")
			Spacer(Modifier.weight(1f))
			Hairline()
			Spacer(Modifier.height(12.dp))
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				StatusDot(MaterialTheme.colorScheme.secondary)
				Eyebrow(
					text = if (conversationCount == 1) "01 thread stored" else
						twoDigits(conversationCount) + " threads stored",
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun ConversationPanel(
	conversations: List<ConversationEntity>,
	workspaceNames: Map<String, String>,
	onOpen: (String) -> Unit,
	onTogglePin: (ConversationEntity, Boolean) -> Unit,
	onRequestDelete: (ConversationEntity) -> Unit,
	onCreate: () -> Unit,
	onOpenWorkspaces: () -> Unit,
	onOpenAssistants: () -> Unit,
	showStartButton: Boolean,
	modifier: Modifier = Modifier,
) {
	val compact = currentFormFactor() == FormFactor.Compact
	Box(
		modifier = modifier,
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
				Eyebrow("Conversations")
				Spacer(Modifier.weight(1f))
				Text(
					text = twoDigits(conversations.size),
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Spacer(Modifier.height(10.dp))
			Hairline()
			Spacer(Modifier.height(if (conversations.isEmpty()) 34.dp else 4.dp))
			if (conversations.isEmpty()) {
				EmptyState(
					showStartButton = showStartButton,
					onCreate = onCreate,
					onOpenWorkspaces = onOpenWorkspaces,
					onOpenAssistants = onOpenAssistants,
				)
			} else {
				ConversationList(
					conversations = conversations,
					workspaceNames = workspaceNames,
					onOpen = onOpen,
					onTogglePin = onTogglePin,
					onRequestDelete = onRequestDelete,
				)
			}
		}
	}
}

@Composable
private fun ConversationList(
	conversations: List<ConversationEntity>,
	workspaceNames: Map<String, String>,
	onOpen: (String) -> Unit,
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
				now = now,
				onClick = { onOpen(conversation.id) },
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
	now: Long,
	onClick: () -> Unit,
	onTogglePin: () -> Unit,
	onDelete: () -> Unit,
) {
	var menu by remember { mutableStateOf(false) }
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(vertical = 14.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
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
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(4.dp),
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
			Text(
				text = buildString {
					append(formatTimestamp(conversation.updatedAt, now))
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
		Box {
			IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
				Icon(
					Icons.Filled.MoreVert,
					contentDescription = "Conversation actions",
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(18.dp),
				)
			}
			DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
				DropdownMenuItem(
					text = { Text(if (conversation.pinned) "Unpin" else "Pin") },
					leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
					onClick = {
						menu = false
						onTogglePin()
					},
				)
				DropdownMenuItem(
					text = { Text("Delete") },
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

private fun formatTimestamp(millis: Long, now: Long): String {
	val age = now - millis
	return when {
		age < 60_000 -> "just now"
		age < 3_600_000 -> "${age / 60_000} min ago"
		age < 86_400_000 -> "${age / 3_600_000} h ago"
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
		Eyebrow("No sessions yet")
		Text(
			text = "Start with a\nclean desk.",
			style = MaterialTheme.typography.headlineMedium,
			color = MaterialTheme.colorScheme.onBackground,
		)
		Text(
			text = "Each conversation gets its own folder. The assistant can read " +
				"and write inside it, and nowhere else — enforced in code, not asked " +
				"for in a prompt.",
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
				Text("Create", style = MaterialTheme.typography.labelLarge)
			}
		}
		Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			TextButton(onClick = onOpenWorkspaces) { Text("Workspaces") }
			TextButton(onClick = onOpenAssistants) { Text("Assistants") }
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
