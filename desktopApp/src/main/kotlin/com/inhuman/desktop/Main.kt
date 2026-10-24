package com.inhuman.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.ai.ssh.SshClient
import com.inhuman.core.ai.ssh.SshLimits
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.ProviderProfileEntity
import com.inhuman.core.data.db.StoredToolCall
import com.inhuman.core.data.db.ToolsColumn
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import com.inhuman.core.files.SessionWorkspace
import com.inhuman.desktop.backup.DesktopBackup
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

private val Graphite = Color(0xFF151311)
private val GraphiteRaised = Color(0xFF1D1A17)
private val Paper = Color(0xFFF2EDE7)
private val Muted = Color(0xFFA79C91)
private val Hairline = Color(0xFF3A332D)
private val Signal = Color(0xFFE56B3F)

private val DesktopColors = darkColorScheme(
	primary = Signal,
	onPrimary = Color(0xFF22140E),
	background = Graphite,
	onBackground = Paper,
	surface = GraphiteRaised,
	onSurface = Paper,
	surfaceVariant = Color(0xFF26211D),
	onSurfaceVariant = Muted,
	outline = Hairline,
	error = Color(0xFFFF8A7A),
)

private data class Line(
	val role: Role,
	val text: String,
	val reasoning: String = "",
	val toolCalls: List<StoredToolCall> = emptyList(),
	val toolName: String? = null,
	val isError: Boolean = false,
	val attachments: List<DesktopAttachment> = emptyList(),
)

private fun StoredLine.toLine() = Line(
	role = when (role) {
		"user" -> Role.User
		"tool" -> Role.Tool
		"system" -> Role.System
		else -> Role.Assistant
	},
	text = text,
	reasoning = reasoning,
	toolCalls = toolCalls,
	toolName = toolName,
	isError = isError,
	attachments = attachments,
)

/** One entry in the conversation's workspace, as the Files window lists it. */
private data class WorkspaceEntry(
	val path: String,
	val name: String,
	val directory: Boolean,
	val size: Long,
)

private fun workspacePath(directory: String, name: String): String =
	if (directory.isBlank()) name else "$directory/$name"

private fun bytesLabel(bytes: Long): String = when {
	bytes < 1024 -> "$bytes B"
	bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
	else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

fun main() {
	val dataDir = DesktopStore.defaultDataDir()
	DesktopDiagnostics.install(dataDir)
	val single = SingleInstance.acquire(dataDir)
	if (single == null) {
		javax.swing.JOptionPane.showMessageDialog(
			null,
			"inhuman is already running for this profile.",
			"inhuman",
			javax.swing.JOptionPane.INFORMATION_MESSAGE,
		)
		return
	}
	// A staged restore must replace the files before Room opens, and only the
	// process that owns the data directory may do it.
	DesktopBackup.applyPendingRestore(dataDir)
	val store = DesktopStore.open(dataDir)
	application {
		Window(
			onCloseRequest = {
				store.close()
				single.close()
				exitApplication()
			},
			title = "inhuman · desktop",
			state = rememberWindowState(width = 900.dp, height = 620.dp),
		) {
			var language by remember { mutableStateOf(store.language()) }
			val setLanguage: (String) -> Unit = { code ->
				language = code
				store.setLanguage(code)
			}
			var dropHandler by remember { mutableStateOf<((List<File>) -> Unit)?>(null) }
			// AWT owns the native drop target; Compose has no portable file-drop
			// modifier on desktop. The handler is registered by DesktopSpike once
			// its attachment function exists, so a drop uses the same import path
			// as the file picker.
			DisposableEffect(Unit) {
				val target = object : DropTarget() {
					override fun dragEnter(dtde: DropTargetDragEvent) {
						if (dtde.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
							dtde.acceptDrag(DnDConstants.ACTION_COPY)
						} else {
							dtde.rejectDrag()
						}
					}

					override fun dragOver(dtde: DropTargetDragEvent) {
						if (dtde.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
							dtde.acceptDrag(DnDConstants.ACTION_COPY)
						} else {
							dtde.rejectDrag()
						}
					}

					@Suppress("UNCHECKED_CAST")
					override fun drop(dtde: DropTargetDropEvent) {
						runCatching {
							dtde.acceptDrop(DnDConstants.ACTION_COPY)
							val files = dtde.transferable
								.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
							dropHandler?.invoke(files)
							dtde.dropComplete(true)
						}.onFailure { dtde.dropComplete(false) }
					}
				}
				val previous = window.dropTarget
				window.dropTarget = target
				onDispose { window.dropTarget = previous }
			}
			var tray by remember { mutableStateOf<DesktopTray?>(null) }
			DisposableEffect(Unit) {
				val created = DesktopTray(
					onOpen = {
						window.isMinimized = false
						window.isVisible = true
						window.toFront()
						window.requestFocus()
					},
					onExit = {
						store.close()
						single.close()
						exitApplication()
					},
				)
				tray = if (created.install()) created else null
				onDispose {
					created.remove()
					tray = null
				}
			}
			CompositionLocalProvider(
				LocalDesktopLanguage provides language,
				LocalSetDesktopLanguage provides setLanguage,
			) {
				MaterialTheme(colorScheme = DesktopColors) {
					DesktopSpike(
						store = store,
						onReplyReady = {
							if (window.isMinimized || !window.isActive) tray?.notifyReply()
						},
						onDropReady = { dropHandler = it },
					)
				}
			}
		}
	}
}

@Composable
private fun DesktopSpike(
	store: DesktopStore,
	onReplyReady: () -> Unit = {},
	onDropReady: ((List<File>) -> Unit) -> Unit = {},
) {
	val lines = remember { mutableStateListOf<Line>() }
	val scope = rememberCoroutineScope()
	val listState = rememberLazyListState()
	val ssh = remember {
		SshClient(
			File(DesktopStore.defaultDataDir(), "ssh/known_hosts").apply {
				parentFile?.mkdirs()
			},
		)
	}
	val rootFocus = remember { FocusRequester() }
	val searchFocus = remember { FocusRequester() }
	LaunchedEffect(Unit) { rootFocus.requestFocus() }
	var conversations by remember { mutableStateOf<List<ConversationSummary>>(emptyList()) }
	var selectedId by remember { mutableStateOf<String?>(null) }
	var query by remember { mutableStateOf("") }
	var matches by remember { mutableStateOf<List<ConversationMatch>>(emptyList()) }
	var renameTarget by remember { mutableStateOf<ConversationSummary?>(null) }
	var deleteTarget by remember { mutableStateOf<ConversationSummary?>(null) }
	var assistant by remember { mutableStateOf<com.inhuman.core.data.db.AgentEntity?>(null) }
	var providerLabel by remember { mutableStateOf("Demo / demo-model") }
	var showSettings by remember { mutableStateOf(false) }
	var showAssistants by remember { mutableStateOf(false) }
	var showFiles by remember { mutableStateOf(false) }
	var showRewind by remember { mutableStateOf(false) }
	var showTools by remember { mutableStateOf(false) }
	var pendingApproval by remember { mutableStateOf<DesktopChat.ApprovalRequest?>(null) }
	var approvalAnswer by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
	var pendingSshConsent by remember { mutableStateOf<DesktopChat.SshConsentRequest?>(null) }
	var sshConsentAnswer by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
	var error by remember { mutableStateOf<String?>(null) }
	var input by remember { mutableStateOf("") }
	var streaming by remember { mutableStateOf(false) }
	var turnJob by remember { mutableStateOf<Job?>(null) }
	var contextTokens by remember { mutableStateOf(0) }
	var compactionActive by remember { mutableStateOf(false) }
	var compactBusy by remember { mutableStateOf(false) }
	var showCompact by remember { mutableStateOf(false) }
	var pendingAttachments by remember { mutableStateOf<List<DesktopAttachment>>(emptyList()) }
	var attachError by remember { mutableStateOf<String?>(null) }
	var attaching by remember { mutableStateOf(false) }
	var reasoning by remember { mutableStateOf("") }
	var answer by remember { mutableStateOf("") }
	var loaded by remember { mutableStateOf(false) }

	LaunchedEffect(Unit) {
		assistant = store.defaultAssistant()
		var all = store.list()
		if (all.isEmpty()) {
			store.create()
			all = store.list()
		}
		conversations = all
		selectedId = all.firstOrNull()?.id
	}

	// Switching is a load, not a filter: the transcript is cleared before the
	// read so a slow query cannot leave the previous conversation's text under
	// the new title.
	LaunchedEffect(selectedId) {
		val id = selectedId ?: return@LaunchedEffect
		loaded = false
		lines.clear()
		val stored = store.load(id)
		lines.addAll(stored.map { it.toLine() })
		contextTokens = store.contextTokens(id)
		compactionActive = store.contextSummary(id) != null
		loaded = true
	}

	fun select(id: String) {
		if (streaming || id == selectedId) return
		selectedId = id
		pendingAttachments = emptyList()
		attachError = null
	}

	val language = LocalDesktopLanguage.current
	fun l(english: String, chinese: String): String = if (language == "zh") chinese else english

	/**
	 * Copy images into the conversation workspace.
	 *
	 * The picker and the drop target share this path. Four images at a time is
	 * the same ceiling the mobile composer uses; every image is re-encoded to
	 * fit the workspace read limit before it becomes a pending attachment.
	 */
	fun attachImages(files: List<File>) {
		val id = selectedId ?: return
		if (streaming) return
		val room = (4 - pendingAttachments.size).coerceAtLeast(0)
		if (room == 0) {
			attachError = l("At most four images per message.", "每条消息最多四张图片。")
			return
		}
		val chosen = files
			.filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "gif", "bmp") }
			.take(room)
		if (chosen.isEmpty()) {
			attachError = l("Choose a PNG, JPEG, GIF or BMP image.", "请选择 PNG、JPEG、GIF 或 BMP 图片。")
			return
		}
		scope.launch {
			attaching = true
			attachError = null
			val vision = runCatching { store.supportsVision(assistant, id) }.getOrNull()
			if (vision == false) {
				attachError = l(
					"The selected model does not accept image input.",
					"当前模型不接受图片输入。",
				)
				attaching = false
				return@launch
			}
			for (file in chosen) {
				runCatching { store.importAttachment(id, file) }
					.onSuccess { pendingAttachments = pendingAttachments + it }
					.onFailure {
						attachError = l(
							"Could not attach ${file.name}: ${it.message}",
							"无法附加 ${file.name}：${it.message}",
						)
					}
			}
			attaching = false
		}
	}

	fun chooseImages() {
		val chooser = JFileChooser().apply {
			isMultiSelectionEnabled = true
			fileFilter = FileNameExtensionFilter(
				"Images (PNG, JPEG, GIF, BMP)",
				"png",
				"jpg",
				"jpeg",
				"gif",
				"bmp",
			)
		}
		if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
			attachImages(chooser.selectedFiles.toList())
		}
	}

	// Dropping image files onto the window is the same path as the picker; the
	// window installs the AWT target and calls back through this registration.
	LaunchedEffect(Unit) { onDropReady(::attachImages) }

	LaunchedEffect(query) {
		val text = query.trim()
		if (text.isEmpty()) {
			matches = emptyList()
			return@LaunchedEffect
		}
		delay(150)
		matches = store.search(text)
	}

	fun newConversation() {
		if (streaming) return
		scope.launch {
			val created = store.create()
			conversations = store.list()
			selectedId = created.id
		}
	}

	fun deleteConversation(target: ConversationSummary) {
		if (streaming) return
		scope.launch {
			store.delete(target.id)
			var all = store.list()
			if (all.isEmpty()) {
				store.create()
				all = store.list()
			}
			conversations = all
			if (selectedId == target.id) selectedId = all.firstOrNull()?.id
		}
	}

	fun send() {
		val conversationId = selectedId ?: return
		val text = input.trim()
		if ((text.isEmpty() && pendingAttachments.isEmpty()) || streaming || !loaded) return
		val sendingAttachments = pendingAttachments
		input = ""
		pendingAttachments = emptyList()
		attachError = null
		reasoning = ""
		answer = ""
		error = null
		streaming = true
		turnJob = scope.launch {
			try {
				val resolved = store.resolve(assistant, conversationId)
				if (resolved.error != null) {
					error = resolved.error
					return@launch
				}
				providerLabel = "${resolved.label} / ${resolved.model}"

				val failure = DesktopChat(
					store = store,
					conversationId = conversationId,
					assistant = assistant,
					ssh = ssh,
					sshFiles = ssh,
					sshConsent = { request ->
						val answer = CompletableDeferred<Boolean>()
						pendingSshConsent = request
						sshConsentAnswer = answer
						val allowed = answer.await()
						pendingSshConsent = null
						sshConsentAnswer = null
						allowed
					},
				).send(
					userText = text,
					provider = resolved.provider,
					model = resolved.model,
					attachments = sendingAttachments,
					approve = { request ->
						val answer = CompletableDeferred<Boolean>()
						pendingApproval = request
						approvalAnswer = answer
						val allowed = answer.await()
						pendingApproval = null
						approvalAnswer = null
						allowed
					},
				) { event ->
					when (event) {
						is DesktopChat.Event.UserMessage -> {
							lines += event.line.toLine()
							scope.launch { conversations = store.list() }
						}

						is DesktopChat.Event.AssistantStarted -> {
							reasoning = ""
							answer = ""
						}

						is DesktopChat.Event.AssistantDelta -> {
							if (event.reasoning) reasoning += event.text else answer += event.text
						}

						is DesktopChat.Event.AssistantFinished -> {
							lines += event.line.toLine()
							reasoning = ""
							answer = ""
							onReplyReady()
							scope.launch {
								conversations = store.list()
								contextTokens = store.contextTokens(conversationId)
								compactionActive = store.contextSummary(conversationId) != null
							}
						}

						is DesktopChat.Event.ToolResult -> {
							lines += event.line.toLine()
						}

						is DesktopChat.Event.Error -> error = event.message
					}
				}
				failure?.let { error = it }
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (failure: Exception) {
				error = failure.message ?: failure::class.simpleName
			} finally {
				// Also covers a failed insert: without this the composer would
				// stay locked for the rest of the session.
				streaming = false
				answer = ""
				reasoning = ""
				pendingApproval = null
				approvalAnswer = null
				pendingSshConsent = null
				sshConsentAnswer = null
				turnJob = null
			}
		}
	}

	fun stop() {
		turnJob?.cancel()
		turnJob = null
	}

	LaunchedEffect(lines.size, streaming) {
		if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
	}

	val selectedTitle = conversations.firstOrNull { it.id == selectedId }?.title
		?: s("New conversation", "新会话")

	Row(
		modifier = Modifier
			.fillMaxSize()
			.background(Graphite)
			.focusRequester(rootFocus)
			.focusable()
			.onPreviewKeyEvent { event ->
				if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
				when {
					event.isCtrlPressed && event.key == Key.N -> {
						newConversation()
						true
					}

					event.isCtrlPressed && event.key == Key.Enter -> {
						send()
						true
					}

					event.isCtrlPressed && event.key == Key.F -> {
						searchFocus.requestFocus()
						true
					}

					event.isCtrlPressed && event.key == Key.Comma -> {
						showSettings = true
						true
					}

					event.isCtrlPressed && event.key == Key.O -> {
						chooseImages()
						true
					}

					event.key == Key.Escape && streaming -> {
						stop()
						true
					}

					else -> false
				}
			},
	) {
		Sidebar(
			conversations = conversations,
			selectedId = selectedId,
			searchFocus = searchFocus,
			busy = streaming,
			query = query,
			matches = matches,
			onQuery = { query = it },
			onSelect = { id ->
				query = ""
				select(id)
			},
			onNew = ::newConversation,
			onRename = { renameTarget = it },
			onPin = { target ->
				if (!streaming) {
					scope.launch {
						store.setPinned(target.id, !target.pinned)
						conversations = store.list()
					}
				}
			},
			onDelete = { deleteTarget = it },
		)
		Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
			TopBar(
				title = selectedTitle,
				provider = providerLabel,
				onRewind = { if (!streaming) showRewind = true },
				onTools = { if (!streaming) showTools = true },
				onFiles = { showFiles = true },
				onAssistants = { showAssistants = true },
				onSettings = { showSettings = true },
			)
			Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
				if (lines.isEmpty()) {
					EmptyState()
				} else {
					LazyColumn(
						state = listState,
						modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 24.dp),
						verticalArrangement = Arrangement.spacedBy(18.dp),
					) {
						items(lines) { line ->
							TranscriptLine(line, store, selectedId.orEmpty())
						}
						if (streaming && (answer.isNotBlank() || reasoning.isNotBlank())) {
							item {
								TranscriptLine(
									Line(Role.Assistant, answer, reasoning),
									store,
									selectedId.orEmpty(),
									cursor = true,
								)
							}
						}
					}
				}
			}
			error?.let { message ->
				Text(
					text = localizedError(message),
					color = Color(0xFFFF8A7A),
					fontSize = 12.sp,
					modifier = Modifier
						.fillMaxWidth()
						.background(GraphiteRaised)
						.padding(horizontal = 36.dp, vertical = 8.dp),
				)
			}
			ContextBar(
				tokens = contextTokens,
				compacted = compactionActive,
				busy = compactBusy,
				onCompact = {
					if (!streaming && !compactBusy && selectedId != null) showCompact = true
				},
				onClear = {
					val id = selectedId
					if (id != null) {
						scope.launch {
							store.clearContextSummary(id)
							compactionActive = false
							contextTokens = store.contextTokens(id)
						}
					}
				},
			)
			Composer(
				input = input,
				onInput = { input = it },
				streaming = streaming,
				ready = loaded && selectedId != null,
				store = store,
				conversationId = selectedId,
				attachments = pendingAttachments,
				attaching = attaching,
				attachError = attachError,
				onAttach = ::chooseImages,
				onRemove = { pendingAttachments = pendingAttachments - it },
				onSend = ::send,
				onStop = ::stop,
			)
		}
	}

	if (showSettings) {
		SettingsWindow(
			store = store,
			conversationId = selectedId,
			onClose = { showSettings = false },
		)
	}

	if (showAssistants) {
		AssistantsWindow(
			store = store,
			onClose = { showAssistants = false },
			onStartConversation = { agent ->
				scope.launch {
					store.saveAssistant(agent)
					val created = store.create(assistant = agent)
					conversations = store.list()
					selectedId = created.id
					showAssistants = false
				}
			},
		)
	}

	if (showFiles) {
		FilesWindow(
			store = store,
			conversationId = selectedId,
			onClose = { showFiles = false },
		)
	}

	if (showRewind) {
		RewindWindow(
			store = store,
			conversationId = selectedId,
			onCancel = { showRewind = false },
			onRewind = { messageId ->
				showRewind = false
				val id = selectedId
				if (id != null) {
					scope.launch {
						val outcome = store.rewindTo(id, messageId)
						if (outcome.error != null) {
							error = outcome.error
						} else {
							lines.clear()
							lines.addAll(store.load(id).map { it.toLine() })
							input = outcome.userText.orEmpty()
							pendingAttachments = outcome.attachments
							contextTokens = store.contextTokens(id)
							compactionActive = store.contextSummary(id) != null
							conversations = store.list()
							error = null
						}
					}
				}
			},
		)
	}

	if (showTools) {
		ConversationToolsWindow(
			store = store,
			conversationId = selectedId,
			onClose = { showTools = false },
		)
	}

	if (showCompact) {
		CompactWindow(
			onCancel = { showCompact = false },
			onConfirm = {
				showCompact = false
				val id = selectedId
				if (id != null) {
					scope.launch {
						compactBusy = true
						val resolved = store.resolve(assistant, id)
						if (resolved.error != null) {
							error = resolved.error
						} else {
							val outcome = store.compact(id, resolved.provider, resolved.model)
							if (outcome.error != null) {
								error = outcome.error
							} else {
								compactionActive = true
								contextTokens = store.contextTokens(id)
								error = null
							}
						}
						compactBusy = false
					}
				}
			},
		)
	}

	pendingApproval?.let { request ->
		ApprovalWindow(
			request = request,
			onDecision = { allowed ->
				approvalAnswer?.complete(allowed)
				pendingApproval = null
				approvalAnswer = null
			},
		)
	}

	pendingSshConsent?.let { request ->
		SshConsentWindow(
			request = request,
			onDecision = { allowed ->
				sshConsentAnswer?.complete(allowed)
				pendingSshConsent = null
				sshConsentAnswer = null
			},
		)
	}

	renameTarget?.let { target ->
		RenameWindow(
			initial = target.title,
			onCancel = { renameTarget = null },
			onSave = { title ->
				renameTarget = null
				scope.launch {
					store.rename(target.id, title)
					conversations = store.list()
				}
			},
		)
	}

	deleteTarget?.let { target ->
		DeleteWindow(
			title = target.title,
			onCancel = { deleteTarget = null },
			onDelete = {
				deleteTarget = null
				deleteConversation(target)
			},
		)
	}
}

@Composable
private fun Sidebar(
	conversations: List<ConversationSummary>,
	selectedId: String?,
	searchFocus: FocusRequester,
	busy: Boolean,
	query: String,
	matches: List<ConversationMatch>,
	onQuery: (String) -> Unit,
	onSelect: (String) -> Unit,
	onNew: () -> Unit,
	onRename: (ConversationSummary) -> Unit,
	onPin: (ConversationSummary) -> Unit,
	onDelete: (ConversationSummary) -> Unit,
) {
	Column(
		modifier = Modifier
			.width(286.dp)
			.fillMaxHeight()
			.background(Color(0xFF191614))
			.padding(24.dp),
	) {
		Text(
			text = "inhuman",
			fontFamily = FontFamily.Monospace,
			fontWeight = FontWeight.Bold,
			fontSize = 24.sp,
			color = Paper,
		)
		Text(
			text = s("Private agent workbench", "私有智能体工作台"),
			fontSize = 12.sp,
			color = Muted,
			modifier = Modifier.padding(top = 2.dp),
		)
		Spacer(Modifier.height(18.dp))
		OutlinedTextField(
			value = query,
			onValueChange = onQuery,
			placeholder = { Text(s("Search conversations", "搜索会话"), color = Muted, fontSize = 12.sp) },
			singleLine = true,
			textStyle = TextStyle(fontSize = 12.sp, color = Paper),
			shape = RoundedCornerShape(6.dp),
			modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
		)
		Spacer(Modifier.height(16.dp))
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = if (query.isBlank()) s("CONVERSATIONS", "会话") else s("SEARCH", "搜索"),
				fontFamily = FontFamily.Monospace,
				fontSize = 11.sp,
				letterSpacing = 1.5.sp,
				color = Signal,
			)
			Spacer(Modifier.weight(1f))
			Text(
				text = s("+ NEW", "+ 新建"),
				fontFamily = FontFamily.Monospace,
				fontSize = 10.sp,
				letterSpacing = 1.sp,
				color = if (busy) Muted else Signal,
				modifier = Modifier.clickable(enabled = !busy) { onNew() },
			)
		}
		Spacer(Modifier.height(10.dp))
		LazyColumn(modifier = Modifier.weight(1f)) {
			if (query.isBlank()) {
				items(conversations, key = { it.id }) { conversation ->
					ConversationRow(
						conversation = conversation,
						selected = conversation.id == selectedId,
						busy = busy,
						onSelect = { onSelect(conversation.id) },
						onRename = { onRename(conversation) },
						onPin = { onPin(conversation) },
						onDelete = { onDelete(conversation) },
					)
				}
			} else {
				items(matches, key = { it.id }) { match ->
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.clickable(enabled = !busy) { onSelect(match.id) }
							.padding(horizontal = 10.dp, vertical = 9.dp),
					) {
						Text(
							text = match.title,
							fontSize = 13.sp,
							color = Paper,
							maxLines = 1,
						)
						Text(
							text = match.snippet.replace('\n', ' '),
							fontSize = 11.sp,
							color = Muted,
							maxLines = 2,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.padding(top = 2.dp),
						)
					}
				}
				if (matches.isEmpty()) {
					item {
						Text(
							text = s("No matches", "没有匹配"),
							fontSize = 12.sp,
							color = Muted,
							modifier = Modifier.padding(10.dp),
						)
					}
				}
			}
		}
		Spacer(Modifier.height(8.dp))
		Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
		Spacer(Modifier.height(14.dp))
		Text(
			text = s("DATABASE", "数据库"),
			fontFamily = FontFamily.Monospace,
			fontSize = 10.sp,
			color = Muted,
		)
		Text(
			text = "inhuman.db · core:db",
			fontFamily = FontFamily.Monospace,
			fontSize = 10.sp,
			color = Muted,
			modifier = Modifier.padding(top = 4.dp),
		)
	}
}

@Composable
private fun ConversationRow(
	conversation: ConversationSummary,
	selected: Boolean,
	busy: Boolean,
	onSelect: () -> Unit,
	onRename: () -> Unit,
	onPin: () -> Unit,
	onDelete: () -> Unit,
) {
	var menuOpen by remember { mutableStateOf(false) }
	Box {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.background(
					if (selected) Color(0xFF26211D) else Color.Transparent,
					RoundedCornerShape(6.dp),
				)
				.clickable(enabled = !busy, onClick = onSelect)
				.padding(horizontal = 10.dp, vertical = 9.dp),
		) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				if (conversation.pinned) {
					Text(
						text = "★",
						fontSize = 10.sp,
						color = Signal,
						modifier = Modifier.padding(end = 4.dp),
					)
				}
				Text(
					text = conversation.title,
					fontSize = 13.sp,
					color = if (selected) Paper else Paper.copy(alpha = 0.82f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				Text(
					text = "⋯",
					fontSize = 13.sp,
					color = Muted,
					modifier = Modifier
						.clickable(enabled = !busy) { menuOpen = true }
						.padding(horizontal = 4.dp),
				)
			}
			Text(
				text = ageLabel(conversation.updatedAt),
				fontFamily = FontFamily.Monospace,
				fontSize = 10.sp,
				color = Muted,
				modifier = Modifier.padding(top = 2.dp),
			)
		}
		DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
			DropdownMenuItem(
				text = { Text(s("Rename", "重命名")) },
				onClick = {
					menuOpen = false
					onRename()
				},
			)
			DropdownMenuItem(
				text = { Text(if (conversation.pinned) s("Unpin", "取消固定") else s("Pin", "固定")) },
				onClick = {
					menuOpen = false
					onPin()
				},
			)
			DropdownMenuItem(
				text = { Text(s("Delete", "删除")) },
				onClick = {
					menuOpen = false
					onDelete()
				},
			)
		}
	}
}

@Composable
private fun TopBar(
	title: String,
	provider: String,
	onRewind: () -> Unit,
	onTools: () -> Unit,
	onFiles: () -> Unit,
	onAssistants: () -> Unit,
	onSettings: () -> Unit,
) {
	Column {
		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 18.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(Modifier.weight(1f)) {
				Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Paper, maxLines = 1)
				Text(
					provider,
					fontSize = 12.sp,
					color = Muted,
					modifier = Modifier.padding(top = 2.dp),
				)
			}
			Text(
				text = s("TOOLS", "工具"),
				fontFamily = FontFamily.Monospace,
				fontSize = 10.sp,
				letterSpacing = 1.sp,
				color = Signal,
				modifier = Modifier.clickable { onTools() },
			)
			Spacer(Modifier.width(18.dp))
			Text(
				text = s("REWIND", "回退"),
				fontFamily = FontFamily.Monospace,
				fontSize = 10.sp,
				letterSpacing = 1.sp,
				color = Signal,
				modifier = Modifier.clickable { onRewind() },
			)
			Spacer(Modifier.width(18.dp))
			Text(
				text = s("FILES", "文件"),
				fontFamily = FontFamily.Monospace,
				fontSize = 10.sp,
				letterSpacing = 1.sp,
				color = Signal,
				modifier = Modifier.clickable { onFiles() },
			)
			Spacer(Modifier.width(18.dp))
			Text(
				text = s("ASSISTANTS", "助手"),
				fontFamily = FontFamily.Monospace,
				fontSize = 10.sp,
				letterSpacing = 1.sp,
				color = Signal,
				modifier = Modifier.clickable { onAssistants() },
			)
			Spacer(Modifier.width(18.dp))
			Text(
				text = s("SETTINGS", "设置"),
				fontFamily = FontFamily.Monospace,
				fontSize = 10.sp,
				letterSpacing = 1.sp,
				color = Signal,
				modifier = Modifier.clickable { onSettings() },
			)
		}
		Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
	}
}

@Composable
private fun ageLabel(at: Long): String {
	val minutes = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
	return when {
		minutes < 1 -> s("just now", "刚刚")
		minutes < 60 -> s("${minutes}m ago", "${minutes} 分钟前")
		minutes < 1_440 -> s("${minutes / 60}h ago", "${minutes / 60} 小时前")
		else -> s("${minutes / 1_440}d ago", "${minutes / 1_440} 天前")
	}
}

@Composable
private fun EmptyState() {
	Column(
		modifier = Modifier.fillMaxSize().padding(48.dp),
		verticalArrangement = Arrangement.Center,
	) {
		Text(
			s("The desktop scaffold is running.", "桌面端脚手架已经跑起来了。"),
			fontSize = 28.sp,
			color = Paper,
			fontWeight = FontWeight.Light,
		)
		Text(
			s("This reply is streamed by core:ai's DemoProvider, with no network and no key. ", "这段回复由 core:ai 的 DemoProvider 流式生成，没有网络、没有密钥。") +
				s("Messages are written to the desktop database and survive closing the window.", "消息写进桌面数据库，关掉窗口再打开还在。"),
			fontSize = 15.sp,
			color = Muted,
			modifier = Modifier.padding(top = 12.dp),
		)
		Text(
			s("Type a message to verify that the shared core works on the JVM.", "输入一句话，验证共享核心在 JVM 上工作。"),
			fontSize = 13.sp,
			color = Muted,
			modifier = Modifier.padding(top = 22.dp),
		)
	}
}

@Composable
private fun TranscriptLine(
	line: Line,
	store: DesktopStore,
	conversationId: String,
	cursor: Boolean = false,
) {
	Column(modifier = Modifier.fillMaxWidth()) {
		if (line.role == Role.User) {
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
				Surface(
					color = Color(0xFF2A241F),
					shape = RoundedCornerShape(10.dp),
					modifier = Modifier.width(540.dp),
				) {
					Column(modifier = Modifier.padding(14.dp)) {
						if (line.text.isNotBlank()) {
							Text(
								text = line.text,
								color = Paper,
								fontSize = 15.sp,
								lineHeight = 23.sp,
							)
						}
						if (line.attachments.isNotEmpty() && conversationId.isNotBlank()) {
							AttachmentStrip(
								store = store,
								conversationId = conversationId,
								attachments = line.attachments,
								removable = false,
								onRemove = {},
							)
						}
					}
				}
			}
		} else if (line.role == Role.Tool) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.background(
						if (line.isError) Color(0xFF2A1B18) else Color(0xFF1A1815),
						RoundedCornerShape(6.dp),
					)
					.padding(12.dp),
			) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = (line.toolName ?: s("tool", "工具")).uppercase(),
						fontFamily = FontFamily.Monospace,
						fontSize = 9.sp,
						letterSpacing = 1.sp,
						color = if (line.isError) Color(0xFFFF8A7A) else Signal,
					)
					Spacer(Modifier.width(8.dp))
					Text(
						text = if (line.isError) s("FAILED", "失败") else s("RESULT", "结果"),
						fontFamily = FontFamily.Monospace,
						fontSize = 9.sp,
						color = Muted,
					)
				}
				Text(
					text = line.text.trim().ifBlank { "(empty)" },
					fontFamily = FontFamily.Monospace,
					fontSize = 12.sp,
					lineHeight = 18.sp,
					color = Paper,
					modifier = Modifier.padding(top = 6.dp),
				)
			}
		} else {
			Text(
				text = s("ASSISTANT", "助手"),
				fontFamily = FontFamily.Monospace,
				fontSize = 10.sp,
				letterSpacing = 1.2.sp,
				color = Signal,
			)
			if (line.reasoning.isNotBlank()) {
				Text(
					text = line.reasoning.trim(),
					fontSize = 12.sp,
					color = Muted,
					modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
				)
			}
			val markdownState = rememberMarkdownState(line.text.trim().ifBlank { "…" })
			if (cursor) {
				// While the answer is still arriving the renderer would re-parse
				// the whole document on every chunk. The Android chat solves
				// that with a streaming state; the desktop window keeps the raw
				// text until the reply is complete, which is honest about what
				// it is showing and cheap.
				Text(
					text = line.text.trim().ifBlank { "…" } + " ▍",
					fontSize = 15.sp,
					lineHeight = 24.sp,
					color = Paper,
					modifier = Modifier.padding(top = 6.dp),
				)
			} else {
				Markdown(
					markdownState = markdownState,
					colors = markdownColor(
						text = Paper,
						codeBackground = Color(0xFF26211D),
						inlineCodeBackground = Color(0xFF26211D),
						dividerColor = Hairline,
						tableBackground = GraphiteRaised,
					),
					typography = markdownTypography(
						text = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, color = Paper),
						paragraph = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, color = Paper),
						h1 = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Light, color = Paper),
						h2 = TextStyle(fontSize = 20.sp, color = Paper),
						h3 = TextStyle(fontSize = 17.sp, color = Paper),
						code = TextStyle(
							fontSize = 13.sp,
							fontFamily = FontFamily.Monospace,
							color = Paper,
						),
						inlineCode = TextStyle(
							fontSize = 14.sp,
							fontFamily = FontFamily.Monospace,
							color = Paper,
						),
						quote = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, color = Muted),
					),
					modifier = Modifier.padding(top = 6.dp),
				)
			}
			if (line.toolCalls.isNotEmpty()) {
				ToolCallList(line.toolCalls)
			}
		}
	}
}

@Composable
private fun ToolCallList(calls: List<StoredToolCall>) {
	Column(
		modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		calls.forEach { call ->
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					text = "→ ${call.name ?: s("tool", "工具")}",
					fontFamily = FontFamily.Monospace,
					fontSize = 10.sp,
					color = Signal,
				)
				Spacer(Modifier.width(8.dp))
				Text(
					text = call.argumentsJson.replace('\n', ' ').take(160),
					fontFamily = FontFamily.Monospace,
					fontSize = 10.sp,
					color = Muted,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
private fun AttachmentStrip(
	store: DesktopStore,
	conversationId: String,
	attachments: List<DesktopAttachment>,
	removable: Boolean,
	onRemove: (DesktopAttachment) -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		attachments.forEach { attachment ->
			AttachmentThumb(
				store = store,
				conversationId = conversationId,
				attachment = attachment,
				removable = removable,
				onRemove = onRemove,
			)
		}
	}
}

@Composable
private fun AttachmentThumb(
	store: DesktopStore,
	conversationId: String,
	attachment: DesktopAttachment,
	removable: Boolean,
	onRemove: (DesktopAttachment) -> Unit,
) {
	val bitmap by produceState<ImageBitmap?>(null, conversationId, attachment.relativePath) {
		value = withContext(Dispatchers.IO) {
			store.readAttachment(conversationId, attachment)?.let { bytes ->
				runCatching {
					SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
				}.getOrNull()
			}
		}
	}
	Box(
		modifier = Modifier
			.size(64.dp)
			.clip(RoundedCornerShape(6.dp))
			.background(Color(0xFF2A241F)),
		contentAlignment = Alignment.Center,
	) {
		val image = bitmap
		if (image != null) {
			Image(
				bitmap = image,
				contentDescription = attachment.displayName,
				modifier = Modifier.fillMaxSize(),
				contentScale = ContentScale.Crop,
			)
		} else {
			Text(
				text = attachment.displayName.take(18),
				fontSize = 8.sp,
				color = Muted,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.padding(4.dp),
			)
		}
		if (removable) {
			Box(
				modifier = Modifier
					.align(Alignment.TopEnd)
					.padding(2.dp)
					.size(16.dp)
					.clip(CircleShape)
					.background(Color(0xCC22140E))
					.clickable { onRemove(attachment) },
				contentAlignment = Alignment.Center,
			) {
				Text("×", color = Paper, fontSize = 10.sp)
			}
		}
	}
}

@Composable
private fun Composer(
	input: String,
	onInput: (String) -> Unit,
	streaming: Boolean,
	ready: Boolean,
	store: DesktopStore,
	conversationId: String?,
	attachments: List<DesktopAttachment>,
	attaching: Boolean,
	attachError: String?,
	onAttach: () -> Unit,
	onRemove: (DesktopAttachment) -> Unit,
	onSend: () -> Unit,
	onStop: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(GraphiteRaised)
			.padding(horizontal = 36.dp, vertical = 16.dp),
	) {
		if (attachments.isNotEmpty() && conversationId != null) {
			AttachmentStrip(
				store = store,
				conversationId = conversationId,
				attachments = attachments,
				removable = true,
				onRemove = onRemove,
			)
		}
		OutlinedTextField(
			value = input,
			onValueChange = onInput,
			placeholder = { Text(s("Message this desktop conversation…", "给这个桌面会话发一条消息…"), color = Muted) },
			modifier = Modifier.fillMaxWidth(),
			minLines = 2,
			maxLines = 6,
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
			keyboardActions = KeyboardActions(onSend = { onSend() }),
			shape = RoundedCornerShape(8.dp),
		)
		attachError?.let { message ->
			Text(
				text = message,
				color = Color(0xFFFF8A7A),
				fontSize = 10.sp,
				modifier = Modifier.padding(top = 4.dp),
			)
		}
		Row(
			modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				if (streaming) s("streaming…", "生成中…") else s("desktop · shared core", "桌面端 · 共享核心"),
				fontSize = 11.sp,
				color = Muted,
				fontFamily = FontFamily.Monospace,
			)
			Spacer(Modifier.weight(1f))
			Text(
				text = if (attaching) s("ATTACHING…", "附加中…") else s("ATTACH", "附加"),
				fontFamily = FontFamily.Monospace,
				fontSize = 10.sp,
				color = if (attaching || streaming) Muted else Signal,
				modifier = Modifier.clickable(enabled = !attaching && !streaming) { onAttach() },
			)
			Spacer(Modifier.width(16.dp))
			if (streaming) {
				Button(
					onClick = onStop,
					colors = ButtonDefaults.buttonColors(
						containerColor = Color(0xFF3A332D),
						contentColor = Paper,
					),
					shape = RoundedCornerShape(6.dp),
				) {
					Text(s("Stop", "停止"))
				}
			} else {
				Button(
					onClick = onSend,
					enabled = (input.isNotBlank() || attachments.isNotEmpty()) && ready,
					colors = ButtonDefaults.buttonColors(
						containerColor = Signal,
						contentColor = Color(0xFF22140E),
					),
					shape = RoundedCornerShape(6.dp),
				) {
					Text(s("Send", "发送"))
				}
			}
		}
	}
}

@Composable
private fun SettingsWindow(
	store: DesktopStore,
	conversationId: String?,
	onClose: () -> Unit,
) {
	val scope = rememberCoroutineScope()
	val setLanguage = LocalSetDesktopLanguage.current
	val language = LocalDesktopLanguage.current
	var tab by remember { mutableStateOf(SettingsTab.Providers) }
	var providers by remember { mutableStateOf<List<ProviderProfileEntity>>(emptyList()) }
	var selected by remember { mutableStateOf<ProviderProfileEntity?>(null) }
	var keyStatus by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
	var keyInput by remember { mutableStateOf("") }
	var status by remember { mutableStateOf<String?>(null) }
	var saving by remember { mutableStateOf(false) }
	var models by remember { mutableStateOf<List<RemoteModel>>(emptyList()) }
	var modelsLoading by remember { mutableStateOf(false) }
	var currentModel by remember { mutableStateOf<String?>(null) }
	var policies by remember { mutableStateOf<Map<String, ToolPolicy>>(emptyMap()) }
	var passphrase by remember { mutableStateOf("") }
	var backupBusy by remember { mutableStateOf(false) }
	var sshEnabled by remember { mutableStateOf(false) }
	var sshSeconds by remember { mutableStateOf("") }
	var sshOutput by remember { mutableStateOf("") }
	var sshList by remember { mutableStateOf("") }
	var sshRead by remember { mutableStateOf("") }

	LaunchedEffect(Unit) {
		providers = store.providers()
		keyStatus = providers.associate { it.id to store.hasProviderKey(it.id) }
		selected = providers.firstOrNull()
		currentModel = conversationId?.let { store.conversation(it)?.entity?.model }
		policies = store.globalRules()
		sshEnabled = store.sshEnabled()
		store.sshLimits().let { limits ->
			sshSeconds = limits.commandTimeoutCeilingSeconds.toString()
			sshOutput = limits.outputCeilingBytes.toString()
			sshList = limits.listCeilingEntries.toString()
			sshRead = limits.readCeilingBytes.toString()
		}
	}

	LaunchedEffect(tab, selected?.id, keyStatus[selected?.id]) {
		val profile = selected ?: return@LaunchedEffect
		if (tab != SettingsTab.Model || keyStatus[profile.id] != true) return@LaunchedEffect
		modelsLoading = true
		models = store.models(profile.id)
		modelsLoading = false
	}

	fun refreshKeyStatus() {
		scope.launch {
			keyStatus = providers.associate { it.id to store.hasProviderKey(it.id) }
		}
	}

	fun setPolicy(name: String, policy: ToolPolicy) {
		scope.launch {
			store.setGlobalRule(name, policy)
			policies = store.globalRules()
			status = "$name: ${policy.name.lowercase()}."
		}
	}

	DialogWindow(
		onCloseRequest = onClose,
		title = "inhuman · settings",
		state = rememberDialogState(width = 600.dp, height = 560.dp),
	) {
		MaterialTheme(colorScheme = DesktopColors) {
			Column(
				modifier = Modifier.fillMaxSize().background(GraphiteRaised).padding(28.dp),
			) {
				Text(
					text = s("SETTINGS", "设置"),
					fontFamily = FontFamily.Monospace,
					fontSize = 11.sp,
					letterSpacing = 1.5.sp,
					color = Signal,
				)
				Row(
					modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
					horizontalArrangement = Arrangement.End,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = "EN",
						fontFamily = FontFamily.Monospace,
						fontSize = 10.sp,
						color = if (language == "en") Signal else Muted,
						modifier = Modifier.clickable { setLanguage("en") },
					)
					Text(
						text = "中文",
						fontFamily = FontFamily.Monospace,
						fontSize = 10.sp,
						color = if (language == "zh") Signal else Muted,
						modifier = Modifier.padding(start = 12.dp).clickable { setLanguage("zh") },
					)
				}
				Spacer(Modifier.height(10.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
					SettingsTab.entries.forEach { entry ->
						Text(
							text = when (entry) {
								SettingsTab.Providers -> s("PROVIDERS", "服务商")
								SettingsTab.Model -> s("MODEL", "模型")
								SettingsTab.Approvals -> s("APPROVALS", "审批")
								SettingsTab.Ssh -> s("SSH", "SSH")
								SettingsTab.Backup -> s("BACKUP", "备份")
								SettingsTab.Diagnostics -> s("DIAGNOSTICS", "诊断")
							},
							fontFamily = FontFamily.Monospace,
							fontSize = 10.sp,
							letterSpacing = 1.sp,
							color = if (tab == entry) Signal else Muted,
							modifier = Modifier.clickable {
								tab = entry
								status = null
							},
						)
					}
				}
				Spacer(Modifier.height(16.dp))
				Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
					when (tab) {
						SettingsTab.Providers -> Column(Modifier.fillMaxSize()) {
							Text(
								s("Keys are encrypted with Windows DPAPI. Without a key the assistant answers from the demo provider.", "密钥用 Windows DPAPI 加密；没有密钥时助手使用 Demo provider。"),
								fontSize = 12.sp,
								color = Muted,
							)
							Spacer(Modifier.height(12.dp))
							LazyColumn(modifier = Modifier.weight(1f)) {
								items(providers, key = { it.id }) { profile ->
									val isSelected = profile.id == selected?.id
									val hasKey = keyStatus[profile.id] == true
									Column(
										modifier = Modifier
											.fillMaxWidth()
											.background(
												if (isSelected) Color(0xFF26211D) else Color.Transparent,
												RoundedCornerShape(6.dp),
											)
											.clickable {
												selected = profile
												keyInput = ""
												status = null
											}
											.padding(12.dp),
									) {
										Text(profile.name, color = Paper, fontSize = 14.sp)
										Text(
											profile.baseUrl,
											fontFamily = FontFamily.Monospace,
											fontSize = 10.sp,
											color = Muted,
											modifier = Modifier.padding(top = 2.dp),
										)
										Text(
											if (hasKey) s("KEY SAVED", "已保存密钥") else s("NO KEY", "无密钥"),
											fontFamily = FontFamily.Monospace,
											fontSize = 9.sp,
											color = if (hasKey) Signal else Muted,
											modifier = Modifier.padding(top = 4.dp),
										)
									}
								}
							}
							selected?.let { profile ->
								Spacer(Modifier.height(12.dp))
								OutlinedTextField(
									value = keyInput,
									onValueChange = { keyInput = it },
									label = {
										Text(
											if (keyStatus[profile.id] == true) {
												"New key (blank keeps the saved one)"
											} else {
												"API key"
											},
										)
									},
									visualTransformation = PasswordVisualTransformation(),
									singleLine = true,
									modifier = Modifier.fillMaxWidth(),
								)
								Row(
									modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
									verticalAlignment = Alignment.CenterVertically,
								) {
									Button(
										onClick = {
											scope.launch {
												saving = true
												status = null
												val value = keyInput.ifBlank { null }
												val ok = store.saveProviderKey(profile.id, value)
												status = when {
													!ok -> "That provider no longer exists."
													value == null -> "Nothing changed."
													else -> "Key saved."
												}
												keyInput = ""
												saving = false
												refreshKeyStatus()
											}
										},
										enabled = !saving,
										colors = ButtonDefaults.buttonColors(
											containerColor = Signal,
											contentColor = Color(0xFF22140E),
										),
										shape = RoundedCornerShape(6.dp),
									) {
										Text(s("Save key", "保存密钥"))
									}
									Spacer(Modifier.width(14.dp))
									Text(
										text = s("Remove key", "移除密钥"),
										color = Muted,
										fontSize = 12.sp,
										modifier = Modifier.clickable {
											scope.launch {
												store.saveProviderKey(profile.id, "")
												status = "Key removed; replies use the demo provider."
												refreshKeyStatus()
											}
										},
									)
								}
							}
						}

						SettingsTab.Model -> Column(Modifier.fillMaxSize()) {
							Text(
								s("Choose the model for this conversation. A blank choice uses the provider's first model.", "选择本会话使用的模型；留空则使用服务商的第一个模型。"),
								fontSize = 12.sp,
								color = Muted,
							)
							Spacer(Modifier.height(10.dp))
							Text(
								text = s("CURRENT · ", "当前 · ") + (currentModel ?: s("provider default", "服务商默认")),
								fontFamily = FontFamily.Monospace,
								fontSize = 10.sp,
								color = Signal,
							)
							Spacer(Modifier.height(10.dp))
							val profile = selected
							if (profile == null || keyStatus[profile.id] != true) {
								Text(
									s("Save a provider key on the Providers tab first.", "请先在服务商页保存 API key。"),
									fontSize = 12.sp,
									color = Muted,
								)
							} else {
								Row(verticalAlignment = Alignment.CenterVertically) {
									Text(
										text = s("MODELS", "模型"),
										fontFamily = FontFamily.Monospace,
										fontSize = 10.sp,
										color = Muted,
									)
									Spacer(Modifier.weight(1f))
									Text(
										text = if (modelsLoading) s("loading…", "加载中…") else s("Refresh", "刷新"),
										fontFamily = FontFamily.Monospace,
										fontSize = 10.sp,
										color = Signal,
										modifier = Modifier.clickable(enabled = !modelsLoading) {
											scope.launch {
												modelsLoading = true
												models = store.models(profile.id, force = true)
												modelsLoading = false
											}
										},
									)
								}
								Spacer(Modifier.height(6.dp))
								LazyColumn(modifier = Modifier.weight(1f)) {
									items(models, key = { it.id }) { model ->
										val inUse = currentModel == model.id
										Column(
											modifier = Modifier
												.fillMaxWidth()
												.background(
													if (inUse) Color(0xFF26211D) else Color.Transparent,
													RoundedCornerShape(6.dp),
												)
												.clickable {
													scope.launch {
														conversationId?.let {
															store.setConversationModel(it, model.id)
															currentModel = model.id
															status = "Model set for this conversation."
														}
													}
												}
												.padding(10.dp),
										) {
											Text(
												text = model.displayName ?: model.id,
												fontSize = 13.sp,
												color = Paper,
											)
											Text(
												text = model.id,
												fontFamily = FontFamily.Monospace,
												fontSize = 10.sp,
												color = Muted,
												modifier = Modifier.padding(top = 2.dp),
											)
										}
									}
									if (models.isEmpty() && !modelsLoading) {
										item {
											Text(
												s("No models returned. Check the key and endpoint.", "没有返回模型；请检查密钥和 endpoint。"),
												fontSize = 12.sp,
												color = Muted,
												modifier = Modifier.padding(10.dp),
											)
										}
									}
								}
							}
						}

						SettingsTab.Approvals -> Column(Modifier.fillMaxSize()) {
							Text(
								s("Default is no prompt. Ask shows the approval window before a call; Deny refuses it. Changes apply to the next turn.", "默认不询问。Ask 会在调用前弹出审批窗；Deny 直接拒绝。修改从下一轮生效。"),
								fontSize = 12.sp,
								color = Muted,
							)
							Spacer(Modifier.height(10.dp))
							LazyColumn(modifier = Modifier.weight(1f)) {
								items(ToolCatalog.visible(sshEnabled), key = { it.name }) { entry ->
									val policy = policies[entry.name] ?: ToolPolicy.Free
									Row(
										modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
										verticalAlignment = Alignment.CenterVertically,
									) {
										Column(modifier = Modifier.weight(1f)) {
											Text(entry.name, fontSize = 13.sp, color = Paper)
											Text(
												entry.blurb,
												fontSize = 10.sp,
												color = Muted,
												maxLines = 1,
												overflow = TextOverflow.Ellipsis,
											)
										}
										Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
											PolicyChoice("FREE", policy == ToolPolicy.Free) {
												setPolicy(entry.name, ToolPolicy.Free)
											}
											PolicyChoice("ASK", policy == ToolPolicy.Ask) {
												setPolicy(entry.name, ToolPolicy.Ask)
											}
											PolicyChoice("DENY", policy == ToolPolicy.Deny) {
												setPolicy(entry.name, ToolPolicy.Deny)
											}
										}
									}
								}
							}
						}

						SettingsTab.Ssh -> Column(
							Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
						) {
							Text(
								s("SSH is the one capability that leaves this device. It is off by default; enabling it only makes the tools available to assistants that explicitly select them. Each conversation asks once before its first remote call.", "SSH 是唯一离开本机的能力。默认关闭；打开只是让显式选择它的 assistant 能用这些工具。每个会话在第一次远程调用前询问一次。"),
								fontSize = 12.sp,
								color = Muted,
							)
							Row(
								modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
								verticalAlignment = Alignment.CenterVertically,
							) {
								Checkbox(
									checked = sshEnabled,
									onCheckedChange = { enabled ->
										scope.launch {
											store.setSshEnabled(enabled)
											sshEnabled = store.sshEnabled()
											if (!enabled) status = "SSH disabled; every conversation grant was revoked."
										}
									},
								)
								Text(s("Enable SSH tools", "启用 SSH 工具"), fontSize = 13.sp, color = Paper)
							}
							Text(
								"LIMITS · the model may request less, never more",
								fontFamily = FontFamily.Monospace,
								fontSize = 9.sp,
								letterSpacing = 1.sp,
								color = Muted,
								modifier = Modifier.padding(top = 8.dp),
							)
							Row(
								modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
								horizontalArrangement = Arrangement.spacedBy(10.dp),
							) {
								OutlinedTextField(
									value = sshSeconds,
									onValueChange = { sshSeconds = it },
									label = { Text(s("Command seconds", "命令秒数")) },
									singleLine = true,
									modifier = Modifier.weight(1f),
								)
								OutlinedTextField(
									value = sshOutput,
									onValueChange = { sshOutput = it },
									label = { Text(s("Output bytes", "输出字节")) },
									singleLine = true,
									modifier = Modifier.weight(1f),
								)
							}
							Row(
								modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
								horizontalArrangement = Arrangement.spacedBy(10.dp),
							) {
								OutlinedTextField(
									value = sshList,
									onValueChange = { sshList = it },
									label = { Text(s("List entries", "列表条数")) },
									singleLine = true,
									modifier = Modifier.weight(1f),
								)
								OutlinedTextField(
									value = sshRead,
									onValueChange = { sshRead = it },
									label = { Text(s("Read bytes", "读取字节")) },
									singleLine = true,
									modifier = Modifier.weight(1f),
								)
							}
							Row(
								modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(18.dp),
							) {
								Button(
									onClick = {
										val limits = SshLimits.fromStored(
											commandTimeoutSeconds = sshSeconds,
											outputBytes = sshOutput,
											listEntries = sshList,
											readBytes = sshRead,
										)
										store.setSshLimits(limits)
										sshSeconds = limits.commandTimeoutCeilingSeconds.toString()
										sshOutput = limits.outputCeilingBytes.toString()
										sshList = limits.listCeilingEntries.toString()
										sshRead = limits.readCeilingBytes.toString()
										status = "SSH limits saved."
									},
									colors = ButtonDefaults.buttonColors(
										containerColor = Signal,
										contentColor = Color(0xFF22140E),
									),
									shape = RoundedCornerShape(6.dp),
								) {
									Text(s("Save limits", "保存上限"))
								}
								Text(
									text = s("Revoke all grants", "撤销所有授权"),
									fontFamily = FontFamily.Monospace,
									fontSize = 10.sp,
									color = Signal,
									modifier = Modifier.clickable {
										scope.launch {
											store.clearAllSshAllowed()
											status = "Every conversation will ask again."
										}
									},
								)
							}
						}

						SettingsTab.Diagnostics -> Column(Modifier.fillMaxSize()) {
							Text(
								s("The flight recorder records what happened around a turn -- model, message counts, failures -- and never keys, prompts or file content.", "飞行记录器记录回合周围发生的事：模型、消息数、失败；绝不记录密钥、提示词或文件内容。"),
								fontSize = 12.sp,
								color = Muted,
							)
							Spacer(Modifier.height(12.dp))
							Text(
								text = DesktopDiagnostics.currentLog()?.absolutePath ?: s("Log file not installed.", "日志文件未安装。"),
								fontFamily = FontFamily.Monospace,
								fontSize = 10.sp,
								color = Paper,
							)
							Spacer(Modifier.height(12.dp))
							Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
								Text(
									text = s("Open folder", "打开目录"),
									fontFamily = FontFamily.Monospace,
									fontSize = 10.sp,
									color = Signal,
									modifier = Modifier.clickable {
										val dir = DesktopDiagnostics.currentLog()?.parentFile
										if (dir != null && Desktop.isDesktopSupported()) {
											runCatching { Desktop.getDesktop().open(dir) }
										}
									},
								)
								Text(
									text = s("Clear log", "清空日志"),
									fontFamily = FontFamily.Monospace,
									fontSize = 10.sp,
									color = Signal,
									modifier = Modifier.clickable {
										DesktopDiagnostics.clear()
										status = "Log cleared."
									},
								)
							}
						}

						SettingsTab.Backup -> Column(Modifier.fillMaxSize()) {
							Text(
								s("Encrypted backup of the database and every workspace. API keys are deliberately not included. Restore is staged and applied when inhuman next starts.", "加密备份数据库和所有工作区。API key 故意不包含在内。恢复会先暂存，并在下次启动 inhuman 时应用。"),
								fontSize = 12.sp,
								color = Muted,
							)
							Spacer(Modifier.height(12.dp))
							OutlinedTextField(
								value = passphrase,
								onValueChange = { passphrase = it },
								label = { Text(s("Backup passphrase (at least 8 characters)", "备份口令（至少 8 个字符）")) },
								visualTransformation = PasswordVisualTransformation(),
								singleLine = true,
								modifier = Modifier.fillMaxWidth(),
							)
							Spacer(Modifier.height(12.dp))
							Row(
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(18.dp),
							) {
								Button(
									onClick = {
										val chooser = JFileChooser().apply {
											selectedFile = File("inhuman-backup.inhuman")
										}
										if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
											return@Button
										}
										val target = chooser.selectedFile ?: return@Button
										scope.launch {
											backupBusy = true
											val outcome = DesktopBackup.export(
												store = store,
												dataDir = DesktopStore.defaultDataDir(),
												target = target,
												passphrase = passphrase.toCharArray(),
											)
											backupBusy = false
											status = outcome.error
												?: "Backup written to ${target.absolutePath}."
										}
									},
									enabled = !backupBusy && passphrase.length >= 8,
									colors = ButtonDefaults.buttonColors(
										containerColor = Signal,
										contentColor = Color(0xFF22140E),
									),
									shape = RoundedCornerShape(6.dp),
								) {
									Text(if (backupBusy) s("Working…", "处理中…") else s("Export backup", "导出备份"))
								}
								Text(
									text = s("Import backup", "导入备份"),
									fontFamily = FontFamily.Monospace,
									fontSize = 10.sp,
									color = if (backupBusy || passphrase.length < 8) Muted else Signal,
									modifier = Modifier.clickable(enabled = !backupBusy && passphrase.length >= 8) {
										val chooser = JFileChooser()
										if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
											return@clickable
										}
										val source = chooser.selectedFile ?: return@clickable
										scope.launch {
											backupBusy = true
											val outcome = DesktopBackup.stageRestore(
												backup = source,
												passphrase = passphrase.toCharArray(),
												dataDir = DesktopStore.defaultDataDir(),
											)
											backupBusy = false
											status = outcome.error
												?: "Backup staged. Close and reopen inhuman to apply it."
										}
									},
								)
							}
						}
					}
				}
				Row(
					modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					status?.let {
						Text(localizedStatus(it), fontSize = 11.sp, color = Muted)
					}
					Spacer(Modifier.weight(1f))
					Text(
						text = s("Done", "完成"),
						color = Paper,
						fontSize = 13.sp,
						modifier = Modifier.clickable { onClose() },
					)
				}
			}
		}
	}
}

private enum class SettingsTab(val label: String) {
	Providers("PROVIDERS"),
	Model("MODEL"),
	Approvals("APPROVALS"),
	Ssh("SSH"),
	Backup("BACKUP"),
	Diagnostics("DIAGNOSTICS"),
}

@Composable
private fun PolicyChoice(label: String, selected: Boolean, onClick: () -> Unit) {
	Box(
		modifier = Modifier
			.background(
				if (selected) Signal else Color.Transparent,
				RoundedCornerShape(4.dp),
			)
			.clickable(onClick = onClick)
			.padding(horizontal = 6.dp, vertical = 3.dp),
	) {
		Text(
			text = label,
			fontFamily = FontFamily.Monospace,
			fontSize = 9.sp,
			color = if (selected) Color(0xFF22140E) else Muted,
		)
	}
}

@Composable
private fun ApprovalWindow(
	request: DesktopChat.ApprovalRequest,
	onDecision: (Boolean) -> Unit,
) {
	DialogWindow(
		onCloseRequest = { onDecision(false) },
		title = "inhuman · approval",
		state = rememberDialogState(width = 560.dp, height = 440.dp),
	) {
		MaterialTheme(colorScheme = DesktopColors) {
			Column(
				modifier = Modifier.fillMaxSize().background(GraphiteRaised).padding(24.dp),
			) {
				Text(
					text = s("APPROVAL REQUIRED", "需要审批"),
					fontFamily = FontFamily.Monospace,
					fontSize = 11.sp,
					letterSpacing = 1.5.sp,
					color = Signal,
				)
				Text(
					text = request.toolName,
					fontSize = 20.sp,
					fontWeight = FontWeight.Light,
					color = Paper,
					modifier = Modifier.padding(top = 8.dp),
				)
				Text(
					text = s("The model wants to run this tool. It runs only if you allow it; Deny ends the turn.", "模型想运行这个工具。只有你允许才会执行；拒绝会结束本回合。"),
					fontSize = 12.sp,
					color = Muted,
					modifier = Modifier.padding(top = 6.dp),
				)
				Box(
					modifier = Modifier
						.weight(1f)
						.fillMaxWidth()
						.padding(top = 14.dp)
						.background(Graphite, RoundedCornerShape(6.dp))
						.padding(12.dp)
						.verticalScroll(rememberScrollState()),
				) {
					Text(
						text = request.argumentsJson.ifBlank { "{}" },
						fontFamily = FontFamily.Monospace,
						fontSize = 11.sp,
						lineHeight = 17.sp,
						color = Paper,
					)
				}
				Row(
					modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.End,
				) {
					Text(
						text = s("Deny", "拒绝"),
						color = Muted,
						fontSize = 13.sp,
						modifier = Modifier.clickable { onDecision(false) },
					)
					Spacer(Modifier.width(20.dp))
					Button(
						onClick = { onDecision(true) },
						colors = ButtonDefaults.buttonColors(
							containerColor = Signal,
							contentColor = Color(0xFF22140E),
						),
						shape = RoundedCornerShape(6.dp),
					) {
						Text(s("Allow once", "允许一次"))
					}
				}
			}
		}
	}
}

@Composable
private fun RenameWindow(
	initial: String,
	onCancel: () -> Unit,
	onSave: (String) -> Unit,
) {
	var title by remember { mutableStateOf(initial) }
	DialogWindow(
		onCloseRequest = onCancel,
		title = "inhuman · rename",
		state = rememberDialogState(width = 440.dp, height = 210.dp),
	) {
		MaterialTheme(colorScheme = DesktopColors) {
			Column(
				modifier = Modifier.fillMaxSize().background(GraphiteRaised).padding(24.dp),
			) {
				Text(
					text = s("RENAME CONVERSATION", "重命名会话"),
					fontFamily = FontFamily.Monospace,
					fontSize = 11.sp,
					letterSpacing = 1.4.sp,
					color = Signal,
				)
				Spacer(Modifier.height(14.dp))
				OutlinedTextField(
					value = title,
					onValueChange = { title = it },
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
					keyboardActions = KeyboardActions(onDone = { onSave(title) }),
				)
				Spacer(Modifier.weight(1f))
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.End,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = s("Cancel", "取消"),
						color = Muted,
						fontSize = 13.sp,
						modifier = Modifier.clickable { onCancel() },
					)
					Spacer(Modifier.width(20.dp))
					Button(
						onClick = { onSave(title) },
						enabled = title.isNotBlank(),
						colors = ButtonDefaults.buttonColors(
							containerColor = Signal,
							contentColor = Color(0xFF22140E),
						),
						shape = RoundedCornerShape(6.dp),
					) {
						Text(s("Save", "保存"))
					}
				}
			}
		}
	}
}

@Composable
private fun DeleteWindow(
	title: String,
	onCancel: () -> Unit,
	onDelete: () -> Unit,
) {
	DialogWindow(
		onCloseRequest = onCancel,
		title = "inhuman · delete",
		state = rememberDialogState(width = 460.dp, height = 230.dp),
	) {
		MaterialTheme(colorScheme = DesktopColors) {
			Column(
				modifier = Modifier.fillMaxSize().background(GraphiteRaised).padding(24.dp),
			) {
				Text(
					text = s("DELETE CONVERSATION", "删除会话"),
					fontFamily = FontFamily.Monospace,
					fontSize = 11.sp,
					letterSpacing = 1.4.sp,
					color = Color(0xFFFF8A7A),
				)
				Text(
					text = title,
					fontSize = 17.sp,
					color = Paper,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(top = 10.dp),
				)
				Text(
					text = s("Its messages and temporary workspace are deleted. This cannot be undone.", "它的消息和临时工作区会被删除，且无法撤销。"),
					fontSize = 12.sp,
					color = Muted,
					modifier = Modifier.padding(top = 6.dp),
				)
				Spacer(Modifier.weight(1f))
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.End,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = s("Cancel", "取消"),
						color = Muted,
						fontSize = 13.sp,
						modifier = Modifier.clickable { onCancel() },
					)
					Spacer(Modifier.width(20.dp))
					Button(
						onClick = onDelete,
						colors = ButtonDefaults.buttonColors(
							containerColor = Color(0xFFB3402A),
							contentColor = Paper,
						),
						shape = RoundedCornerShape(6.dp),
					) {
						Text(s("Delete", "删除"))
					}
				}
			}
		}
	}
}

@Composable
private fun AssistantsWindow(
	store: DesktopStore,
	onClose: () -> Unit,
	onStartConversation: (AgentEntity) -> Unit,
) {
	val scope = rememberCoroutineScope()
	var assistants by remember { mutableStateOf<List<AgentEntity>>(emptyList()) }
	var providers by remember { mutableStateOf<List<ProviderProfileEntity>>(emptyList()) }
	var draft by remember { mutableStateOf<AgentEntity?>(null) }
	var status by remember { mutableStateOf<String?>(null) }
	var providerMenu by remember { mutableStateOf(false) }
	var sshEnabled by remember { mutableStateOf(false) }

	suspend fun reload(select: String? = null) {
		assistants = store.assistants()
		providers = store.providers()
		draft = assistants.firstOrNull { it.id == select } ?: assistants.firstOrNull()
	}

	LaunchedEffect(Unit) {
		reload()
		sshEnabled = store.sshEnabled()
	}

	DialogWindow(
		onCloseRequest = onClose,
		title = "inhuman · assistants",
		state = rememberDialogState(width = 880.dp, height = 640.dp),
	) {
		MaterialTheme(colorScheme = DesktopColors) {
			Row(
				modifier = Modifier.fillMaxSize().background(GraphiteRaised).padding(24.dp),
			) {
				Column(modifier = Modifier.width(230.dp).fillMaxHeight()) {
					Text(
						text = s("ASSISTANTS", "助手"),
						fontFamily = FontFamily.Monospace,
						fontSize = 11.sp,
						letterSpacing = 1.5.sp,
						color = Signal,
					)
					Spacer(Modifier.height(12.dp))
					Text(
						text = s("+ NEW", "+ 新建"),
						fontFamily = FontFamily.Monospace,
						fontSize = 10.sp,
						color = Signal,
						modifier = Modifier.clickable {
							draft = store.newAssistant()
							status = null
						},
					)
					Spacer(Modifier.height(10.dp))
					LazyColumn(modifier = Modifier.weight(1f)) {
						items(assistants, key = { it.id }) { agent ->
							val selected = agent.id == draft?.id
							Column(
								modifier = Modifier
									.fillMaxWidth()
									.background(
										if (selected) Color(0xFF26211D) else Color.Transparent,
										RoundedCornerShape(6.dp),
									)
									.clickable {
										draft = agent
										status = null
										providerMenu = false
									}
									.padding(10.dp),
							) {
								Text(
									text = agent.name,
									fontSize = 13.sp,
									color = if (selected) Paper else Paper.copy(alpha = 0.82f),
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
								Text(
									text = if (agent.isDefault) s("DEFAULT", "默认") else s("ASSISTANT", "助手"),
									fontFamily = FontFamily.Monospace,
									fontSize = 9.sp,
									color = Muted,
									modifier = Modifier.padding(top = 2.dp),
								)
							}
						}
					}
					Spacer(Modifier.height(10.dp))
					Text(
						text = s("Done", "完成"),
						color = Paper,
						fontSize = 13.sp,
						modifier = Modifier.clickable { onClose() },
					)
				}

				Spacer(Modifier.width(24.dp))
				Box(Modifier.width(1.dp).fillMaxHeight().background(Hairline))
				Spacer(Modifier.width(24.dp))

				draft?.let { agent ->
					Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
						Column(
							modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
						) {
							Row(verticalAlignment = Alignment.CenterVertically) {
								Text(
									text = agent.name.ifBlank { s("Untitled assistant", "未命名助手") },
									fontSize = 20.sp,
									fontWeight = FontWeight.Light,
									color = Paper,
									modifier = Modifier.weight(1f),
								)
								if (agent.isDefault) {
									Text(
										text = s("DEFAULT", "默认"),
										fontFamily = FontFamily.Monospace,
										fontSize = 9.sp,
										color = Signal,
									)
								}
							}
							Spacer(Modifier.height(12.dp))
							OutlinedTextField(
								value = agent.name,
								onValueChange = { draft = agent.copy(name = it) },
								label = { Text(s("Name", "名称")) },
								singleLine = true,
								modifier = Modifier.fillMaxWidth(),
							)
							Spacer(Modifier.height(10.dp))
							OutlinedTextField(
								value = agent.systemPrompt,
								onValueChange = { draft = agent.copy(systemPrompt = it) },
								label = { Text(s("System prompt", "系统提示词")) },
								minLines = 4,
								maxLines = 7,
								modifier = Modifier.fillMaxWidth(),
							)
							Spacer(Modifier.height(10.dp))
							Box {
								Text(
									text = s("PROVIDER · ", "服务商 · ") + (
										providers.firstOrNull { it.id == agent.providerId }?.name
											?: agent.providerId
										),
									fontFamily = FontFamily.Monospace,
									fontSize = 10.sp,
									color = Signal,
									modifier = Modifier
										.clickable { providerMenu = true }
										.padding(vertical = 6.dp),
								)
								DropdownMenu(
									expanded = providerMenu,
									onDismissRequest = { providerMenu = false },
								) {
									providers.forEach { profile ->
										DropdownMenuItem(
											text = { Text(profile.name) },
											onClick = {
												draft = agent.copy(
													providerId = profile.id,
													model = null,
												)
												providerMenu = false
											},
										)
									}
								}
							}
							OutlinedTextField(
								value = agent.model.orEmpty(),
								onValueChange = {
									draft = agent.copy(model = it.takeIf { value -> value.isNotBlank() })
								},
								label = { Text(s("Model (blank = provider default)", "模型（留空使用服务商默认）")) },
								singleLine = true,
								modifier = Modifier.fillMaxWidth(),
							)
							Row(
								modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
								verticalAlignment = Alignment.CenterVertically,
							) {
								Checkbox(
									checked = agent.thinking,
									onCheckedChange = { draft = agent.copy(thinking = it) },
								)
								Text(s("Thinking", "思考"), fontSize = 12.sp, color = Paper)
							}

							Spacer(Modifier.height(6.dp))
							Text(
								text = s("TOOLS", "工具"),
								fontFamily = FontFamily.Monospace,
								fontSize = 10.sp,
								letterSpacing = 1.sp,
								color = Muted,
							)
							val selection = ToolsColumn.decode(agent.toolsJson)
							val effective = selection
								?: ToolCatalog.visible(sshEnabled).map { it.name }.toSet()
							ToolCatalog.visible(sshEnabled).forEach { entry ->
								Row(
									modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
									verticalAlignment = Alignment.CenterVertically,
								) {
									Checkbox(
										checked = effective.contains(entry.name),
										onCheckedChange = { checked ->
											val next = effective.toMutableSet()
											if (checked) next += entry.name else next -= entry.name
											draft = agent.copy(
												toolsJson = ToolsColumn.encode(next.toList()),
											)
										},
									)
									Column(Modifier.weight(1f)) {
										Row(verticalAlignment = Alignment.CenterVertically) {
											Text(entry.name, fontSize = 12.sp, color = Paper)
											if (entry.remote) {
												Text(
													text = s("REMOTE", "远程"),
													fontFamily = FontFamily.Monospace,
													fontSize = 8.sp,
													color = Color(0xFFFF8A7A),
													modifier = Modifier.padding(start = 6.dp),
												)
											}
											if (entry.paid) {
												Text(
													text = s("PAID", "付费"),
													fontFamily = FontFamily.Monospace,
													fontSize = 8.sp,
													color = Signal,
													modifier = Modifier.padding(start = 6.dp),
												)
											}
										}
										Text(
											entry.blurb,
											fontSize = 10.sp,
											color = Muted,
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
										)
									}
								}
							}
						}
						Row(
							modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Button(
								onClick = {
									scope.launch {
										store.saveAssistant(agent)
										reload(agent.id)
										status = "Saved."
									}
								},
								enabled = agent.name.isNotBlank(),
								colors = ButtonDefaults.buttonColors(
									containerColor = Signal,
									contentColor = Color(0xFF22140E),
								),
								shape = RoundedCornerShape(6.dp),
							) {
								Text(s("Save", "保存"))
							}
							Spacer(Modifier.width(12.dp))
							if (!agent.isDefault) {
								Text(
									text = s("Delete", "删除"),
									color = Color(0xFFFF8A7A),
									fontSize = 12.sp,
									modifier = Modifier.clickable {
										scope.launch {
											store.deleteAssistant(agent)
											reload()
											status = "Deleted."
										}
									},
								)
							}
							Spacer(Modifier.weight(1f))
							Text(
								text = s("New conversation", "新会话"),
								color = Paper,
								fontSize = 13.sp,
								modifier = Modifier.clickable {
									if (agent.name.isNotBlank()) onStartConversation(agent)
								},
							)
						}
						status?.let {
							Text(
								text = localizedStatus(it),
								fontSize = 11.sp,
								color = Muted,
								modifier = Modifier.padding(top = 8.dp),
							)
						}
					}
				} ?: Text(
					text = s("No assistants. Create one with + NEW.", "没有助手，用 + 新建 创建一个。"),
					fontSize = 12.sp,
					color = Muted,
				)
			}
		}
	}
}

@Composable
private fun FilesWindow(
	store: DesktopStore,
	conversationId: String?,
	onClose: () -> Unit,
) {
	val scope = rememberCoroutineScope()
	var workspace by remember { mutableStateOf<SessionWorkspace?>(null) }
	var directory by remember { mutableStateOf("") }
	var entries by remember { mutableStateOf<List<WorkspaceEntry>>(emptyList()) }
	var selected by remember { mutableStateOf<WorkspaceEntry?>(null) }
	var editor by remember { mutableStateOf("") }
	var original by remember { mutableStateOf("") }
	var binary by remember { mutableStateOf(false) }
	var status by remember { mutableStateOf<String?>(null) }
	var loading by remember { mutableStateOf(true) }

	fun refresh(ws: SessionWorkspace) {
		val dir = if (directory.isBlank()) {
			ws.rootPath()
		} else {
			when (val resolution = ws.resolve(directory)) {
				is SessionWorkspace.Resolution.Ok -> resolution.file
				is SessionWorkspace.Resolution.Rejected -> {
					status = resolution.reason
					return
				}
			}
		}
		entries = (dir.listFiles() ?: emptyArray()).map { file ->
			WorkspaceEntry(
				path = workspacePath(directory, file.name),
				name = file.name,
				directory = file.isDirectory,
				size = if (file.isFile) file.length() else 0L,
			)
		}.sortedWith(compareBy({ !it.directory }, { it.name.lowercase() }))
	}

	fun select(ws: SessionWorkspace, entry: WorkspaceEntry) {
		selected = entry
		status = null
		editor = ""
		original = ""
		binary = false
		if (entry.directory) return
		val file = when (val resolution = ws.resolve(entry.path)) {
			is SessionWorkspace.Resolution.Ok -> resolution.file
			is SessionWorkspace.Resolution.Rejected -> {
				status = resolution.reason
				return
			}
		}
		if (file.length() > 512 * 1024) {
			binary = true
			status = "This file is too large to edit here; use Open or Export."
			return
		}
		val bytes = runCatching { file.readBytes() }.getOrElse {
			status = "Could not read the file: ${it.message}"
			return
		}
		binary = bytes.take(2048).any { it == 0.toByte() }
		if (!binary) {
			editor = bytes.toString(Charsets.UTF_8)
			original = editor
		}
	}

	fun importFile(ws: SessionWorkspace) {
		val chooser = JFileChooser()
		if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
		val source = chooser.selectedFile ?: return
		val relative = workspacePath(directory, source.name)
		val target = when (val resolution = ws.resolve(relative)) {
			is SessionWorkspace.Resolution.Ok -> resolution.file
			is SessionWorkspace.Resolution.Rejected -> {
				status = resolution.reason
				return
			}
		}
		val exists = target.isFile
		val refusal = ws.quotaRefusal(
			stagingBytes = 0,
			stagingFiles = 0,
			replacedBytes = if (exists) target.length() else 0,
			addsFiles = if (exists) 0 else 1,
			newBytes = source.length(),
		)
		if (refusal != null) {
			status = refusal
			return
		}
		runCatching {
			target.parentFile?.mkdirs()
			source.copyTo(target, overwrite = true)
		}.onSuccess {
			status = "Imported ${source.name}."
			refresh(ws)
		}.onFailure {
			status = "Import failed: ${it.message}"
		}
	}

	fun exportFile(file: File, suggestedName: String) {
		val chooser = JFileChooser().apply { selectedFile = File(suggestedName) }
		if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return
		val target = chooser.selectedFile ?: return
		runCatching { file.copyTo(target, overwrite = true) }
			.onSuccess { status = "Exported to ${target.absolutePath}." }
			.onFailure { status = "Export failed: ${it.message}" }
	}

	LaunchedEffect(conversationId) {
		loading = true
		val ws = conversationId?.let { store.workspaceFor(it) }
		workspace = ws
		if (ws == null) {
			status = "Open a conversation before browsing its files."
		} else {
			refresh(ws)
		}
		loading = false
	}

	DialogWindow(
		onCloseRequest = onClose,
		title = "inhuman · files",
		state = rememberDialogState(width = 980.dp, height = 660.dp),
	) {
		MaterialTheme(colorScheme = DesktopColors) {
			Column(
				modifier = Modifier.fillMaxSize().background(GraphiteRaised).padding(20.dp),
			) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = s("FILES", "文件"),
						fontFamily = FontFamily.Monospace,
						fontSize = 11.sp,
						letterSpacing = 1.5.sp,
						color = Signal,
					)
					Spacer(Modifier.weight(1f))
					Text(
						text = s("Done", "完成"),
						color = Paper,
						fontSize = 13.sp,
						modifier = Modifier.clickable { onClose() },
					)
				}
				Spacer(Modifier.height(6.dp))
				Text(
					text = s("PATH · /${directory}", "路径 · /${directory}"),
					fontFamily = FontFamily.Monospace,
					fontSize = 10.sp,
					color = Muted,
				)
				Spacer(Modifier.height(10.dp))
				Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
					Column(modifier = Modifier.width(360.dp).fillMaxHeight()) {
						Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
							FileAction("UP") {
								val ws = workspace ?: return@FileAction
								val parent = directory.substringBeforeLast('/', "")
								directory = parent
								selected = null
								refresh(ws)
							}
							FileAction("REFRESH") {
								workspace?.let(::refresh)
							}
							FileAction("IMPORT") {
								workspace?.let(::importFile)
							}
							FileAction("EXPORT") {
								val entry = selected
								val ws = workspace
								if (entry != null && !entry.directory && ws != null) {
									when (val resolution = ws.resolve(entry.path)) {
										is SessionWorkspace.Resolution.Ok ->
											exportFile(resolution.file, entry.name)
										is SessionWorkspace.Resolution.Rejected ->
											status = resolution.reason
									}
								}
							}
							FileAction("OPEN") {
								val entry = selected
								val ws = workspace
								if (entry != null && !entry.directory && ws != null) {
									when (val resolution = ws.resolve(entry.path)) {
										is SessionWorkspace.Resolution.Ok ->
											openExternally(resolution.file)
										is SessionWorkspace.Resolution.Rejected ->
											status = resolution.reason
									}
								}
							}
						}
						Spacer(Modifier.height(8.dp))
						if (loading) {
							Text(s("Loading…", "加载中…"), fontSize = 12.sp, color = Muted)
						} else {
							LazyColumn(modifier = Modifier.weight(1f)) {
								items(entries, key = { it.path }) { entry ->
									val ws = workspace
									val isSelected = selected?.path == entry.path
									Row(
										modifier = Modifier
											.fillMaxWidth()
											.background(
												if (isSelected) Color(0xFF26211D) else Color.Transparent,
												RoundedCornerShape(5.dp),
											)
											.clickable {
												if (entry.directory) {
													directory = entry.path
													selected = null
													if (ws != null) refresh(ws)
												} else if (ws != null) {
													select(ws, entry)
												}
											}
											.padding(horizontal = 8.dp, vertical = 6.dp),
										verticalAlignment = Alignment.CenterVertically,
									) {
										Text(
											text = if (entry.directory) "▸" else "·",
											fontFamily = FontFamily.Monospace,
											fontSize = 11.sp,
											color = if (entry.directory) Signal else Muted,
											modifier = Modifier.width(18.dp),
										)
										Column(Modifier.weight(1f)) {
											Text(
												text = entry.name,
												fontSize = 12.sp,
												color = Paper,
												maxLines = 1,
												overflow = TextOverflow.Ellipsis,
											)
											Text(
												text = if (entry.directory) "folder" else bytesLabel(entry.size),
												fontFamily = FontFamily.Monospace,
												fontSize = 9.sp,
												color = Muted,
											)
										}
									}
								}
							}
						}
						status?.let {
							Text(
								text = localizedStatus(it),
								fontSize = 10.sp,
								color = Muted,
								modifier = Modifier.padding(top = 6.dp),
							)
						}
					}

					Spacer(Modifier.width(18.dp))
					Box(Modifier.width(1.dp).fillMaxHeight().background(Hairline))
					Spacer(Modifier.width(18.dp))

					Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
						val entry = selected
						if (entry == null) {
							Text(
								text = s("Select a file to read, edit, open or export.", "选择文件以读取、编辑、打开或导出。"),
								fontSize = 12.sp,
								color = Muted,
							)
						} else {
							Text(
								text = entry.name,
								fontSize = 18.sp,
								fontWeight = FontWeight.Light,
								color = Paper,
							)
							Text(
								text = if (entry.directory) {
									s("folder · /${entry.path}", "文件夹 · /${entry.path}")
								} else {
									"${bytesLabel(entry.size)} · /${entry.path}"
								},
								fontFamily = FontFamily.Monospace,
								fontSize = 10.sp,
								color = Muted,
								modifier = Modifier.padding(top = 2.dp),
							)
							Spacer(Modifier.height(10.dp))
							if (entry.directory) {
								Text(
									text = s("Open the folder from the list on the left.", "在左侧列表中打开文件夹。"),
									fontSize = 12.sp,
									color = Muted,
								)
							} else {
								OutlinedTextField(
									value = editor,
									onValueChange = { editor = it },
									readOnly = binary,
									textStyle = TextStyle(
										fontFamily = FontFamily.Monospace,
										fontSize = 12.sp,
										color = Paper,
									),
									modifier = Modifier.weight(1f).fillMaxWidth(),
								)
								Row(
									modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
									horizontalArrangement = Arrangement.spacedBy(18.dp),
									verticalAlignment = Alignment.CenterVertically,
								) {
									Button(
										onClick = {
											val ws = workspace ?: return@Button
											val file = (ws.resolve(entry.path) as? SessionWorkspace.Resolution.Ok)?.file
												?: return@Button
											val newBytes = editor.toByteArray(Charsets.UTF_8).size.toLong()
											val refusal = ws.quotaRefusal(
												stagingBytes = 0,
												stagingFiles = 0,
												replacedBytes = file.length(),
												addsFiles = 0,
												newBytes = newBytes,
											)
											if (refusal != null) {
												status = refusal
											} else {
												runCatching { file.writeText(editor) }
													.onSuccess {
														original = editor
														status = "Saved."
														workspace?.let(::refresh)
													}
													.onFailure { status = "Save failed: ${it.message}" }
											}
										},
										enabled = !binary && editor != original,
										colors = ButtonDefaults.buttonColors(
											containerColor = Signal,
											contentColor = Color(0xFF22140E),
										),
										shape = RoundedCornerShape(6.dp),
									) {
										Text(s("Save", "保存"))
									}
									Text(
										text = s("Open", "打开"),
										color = Signal,
										fontSize = 12.sp,
										modifier = Modifier.clickable {
											val file = workspace?.resolve(entry.path)
												?.let { it as? SessionWorkspace.Resolution.Ok }?.file
											if (file != null) openExternally(file)
										},
									)
									Text(
										text = s("Export", "导出"),
										color = Signal,
										fontSize = 12.sp,
										modifier = Modifier.clickable {
											val file = workspace?.resolve(entry.path)
												?.let { it as? SessionWorkspace.Resolution.Ok }?.file
											if (file != null) exportFile(file, entry.name)
										},
									)
								}
								if (binary) {
									Text(
										text = s("Binary or too-large file: editing is disabled. Open or Export it instead.", "二进制或过大文件：不能编辑，请打开或导出。"),
										fontSize = 11.sp,
										color = Muted,
										modifier = Modifier.padding(top = 6.dp),
									)
								}
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun FileAction(label: String, onClick: () -> Unit) {
	Text(
		text = label,
		fontFamily = FontFamily.Monospace,
		fontSize = 10.sp,
		color = Signal,
		modifier = Modifier.clickable(onClick = onClick),
	)
}

@Composable
private fun ContextBar(
	tokens: Int,
	compacted: Boolean,
	busy: Boolean,
	onCompact: () -> Unit,
	onClear: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(GraphiteRaised)
			.padding(horizontal = 36.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = if (compacted) {
				s("~$tokens tokens · earlier turns summarized", "约 $tokens tokens · 已摘要更早轮次")
			} else {
				s("~$tokens tokens", "约 $tokens tokens")
			},
			fontFamily = FontFamily.Monospace,
			fontSize = 10.sp,
			color = Muted,
		)
		Spacer(Modifier.weight(1f))
		if (compacted) {
			Text(
				text = s("CLEAR", "清除"),
				fontFamily = FontFamily.Monospace,
				fontSize = 10.sp,
				color = Signal,
				modifier = Modifier.clickable(enabled = !busy) { onClear() },
			)
			Spacer(Modifier.width(18.dp))
		}
		Text(
			text = if (busy) s("COMPACTING…", "压缩中…") else s("COMPACT", "压缩"),
			fontFamily = FontFamily.Monospace,
			fontSize = 10.sp,
			color = if (busy) Muted else Signal,
			modifier = Modifier.clickable(enabled = !busy) { onCompact() },
		)
	}
}

@Composable
private fun CompactWindow(onCancel: () -> Unit, onConfirm: () -> Unit) {
	DialogWindow(
		onCloseRequest = onCancel,
		title = "inhuman · compact context",
		state = rememberDialogState(width = 560.dp, height = 320.dp),
	) {
		MaterialTheme(colorScheme = DesktopColors) {
			Column(
				modifier = Modifier.fillMaxSize().background(GraphiteRaised).padding(24.dp),
			) {
				Text(
					text = s("COMPACT CONTEXT", "压缩上下文"),
					fontFamily = FontFamily.Monospace,
					fontSize = 11.sp,
					letterSpacing = 1.5.sp,
					color = Signal,
				)
				Text(
					text = s("Older messages are summarized and omitted from the next request; the last two user turns stay verbatim. The transcript itself is not deleted, and Clear restores the full projection.", "较早的消息会被摘要并从下一次请求中省略；最近两个 user 回合保持原文。transcript 本身不会删除，Clear 可恢复完整投影。"),
					fontSize = 12.sp,
					lineHeight = 19.sp,
					color = Muted,
					modifier = Modifier.padding(top = 10.dp),
				)
				Text(
					text = s("With a real provider this costs one summarization request.", "使用真实 provider 时会花一次摘要请求。"),
					fontSize = 11.sp,
					color = Color(0xFFFF8A7A),
					modifier = Modifier.padding(top = 8.dp),
				)
				Spacer(Modifier.weight(1f))
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.End,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = s("Cancel", "取消"),
						color = Muted,
						fontSize = 13.sp,
						modifier = Modifier.clickable { onCancel() },
					)
					Spacer(Modifier.width(20.dp))
					Button(
						onClick = onConfirm,
						colors = ButtonDefaults.buttonColors(
							containerColor = Signal,
							contentColor = Color(0xFF22140E),
						),
						shape = RoundedCornerShape(6.dp),
					) {
						Text(s("Compact", "压缩"))
					}
				}
			}
		}
	}
}

@Composable
private fun ConversationToolsWindow(
	store: DesktopStore,
	conversationId: String?,
	onClose: () -> Unit,
) {
	val scope = rememberCoroutineScope()
	var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
	var sshEnabled by remember { mutableStateOf(false) }
	var loaded by remember { mutableStateOf(false) }

	LaunchedEffect(conversationId) {
		sshEnabled = store.sshEnabled()
		val tools = conversationId?.let { store.conversation(it)?.entity?.tools }
		selected = tools?.toSet()
			?: ToolCatalog.visible(false).map { it.name }.toSet()
		loaded = true
	}

	DialogWindow(
		onCloseRequest = onClose,
		title = "inhuman · conversation tools",
		state = rememberDialogState(width = 560.dp, height = 640.dp),
	) {
		MaterialTheme(colorScheme = DesktopColors) {
			Column(
				modifier = Modifier.fillMaxSize().background(GraphiteRaised).padding(24.dp),
			) {
				Text(
					text = s("CONVERSATION TOOLS", "会话工具"),
					fontFamily = FontFamily.Monospace,
					fontSize = 11.sp,
					letterSpacing = 1.5.sp,
					color = Signal,
				)
				Text(
					text = s(
						"This selection applies to the next turn. SSH tools are shown only when enabled in Settings; adding exec, upload or write resets this conversation's SSH consent.",
						"该选择从下一轮生效。SSH 工具只在设置中启用后显示；增加 exec、upload 或 write 会重置本会话的 SSH 授权。",
					),
					fontSize = 12.sp,
					color = Muted,
					modifier = Modifier.padding(top = 8.dp),
				)
				Spacer(Modifier.height(10.dp))
				if (!loaded) {
					Text(s("Loading…", "加载中…"), fontSize = 12.sp, color = Muted)
				} else {
					LazyColumn(modifier = Modifier.weight(1f)) {
						items(ToolCatalog.visible(sshEnabled), key = { it.name }) { entry ->
							Row(
								modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
								verticalAlignment = Alignment.CenterVertically,
							) {
								Checkbox(
									checked = entry.name in selected,
									onCheckedChange = { checked ->
										selected = if (checked) selected + entry.name else selected - entry.name
									},
								)
								Column(Modifier.weight(1f)) {
									Row(verticalAlignment = Alignment.CenterVertically) {
										Text(entry.name, fontSize = 12.sp, color = Paper)
										if (entry.remote) {
											Text(
												text = s("REMOTE", "远程"),
												fontFamily = FontFamily.Monospace,
												fontSize = 8.sp,
												color = Color(0xFFFF8A7A),
												modifier = Modifier.padding(start = 6.dp),
											)
										}
										if (entry.paid) {
											Text(
												text = s("PAID", "付费"),
												fontFamily = FontFamily.Monospace,
												fontSize = 8.sp,
												color = Signal,
												modifier = Modifier.padding(start = 6.dp),
											)
										}
									}
									Text(
										entry.blurb,
										fontSize = 10.sp,
										color = Muted,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis,
									)
								}
							}
						}
					}
				}
				Row(
					modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Button(
						onClick = {
							val id = conversationId
							if (id != null) {
								scope.launch {
									store.setConversationTools(id, selected.toList())
									onClose()
								}
							}
						},
						enabled = loaded && conversationId != null,
						colors = ButtonDefaults.buttonColors(
							containerColor = Signal,
							contentColor = Color(0xFF22140E),
						),
						shape = RoundedCornerShape(6.dp),
					) {
						Text(s("Save", "保存"))
					}
					Spacer(Modifier.weight(1f))
					Text(
						text = s("Done", "完成"),
						color = Paper,
						fontSize = 13.sp,
						modifier = Modifier.clickable { onClose() },
					)
				}
			}
		}
	}
}

@Composable
private fun SshConsentWindow(
	request: DesktopChat.SshConsentRequest,
	onDecision: (Boolean) -> Unit,
) {
	DialogWindow(
		onCloseRequest = { onDecision(false) },
		title = "inhuman · ssh consent",
		state = rememberDialogState(width = 600.dp, height = 430.dp),
	) {
		MaterialTheme(colorScheme = DesktopColors) {
			Column(
				modifier = Modifier.fillMaxSize().background(GraphiteRaised).padding(24.dp),
			) {
				Text(
					text = s("SSH CONSENT", "SSH 授权"),
					fontFamily = FontFamily.Monospace,
					fontSize = 11.sp,
					letterSpacing = 1.5.sp,
					color = Color(0xFFFF8A7A),
				)
				Text(
					text = s("The model wants to reach a remote machine. This is the one capability that leaves the device; the app does not configure the host or keep the credentials.", "模型想访问一台远程机器。这是唯一离开本机的能力；应用不配置主机，也不保存凭据。"),
					fontSize = 12.sp,
					lineHeight = 19.sp,
					color = Muted,
					modifier = Modifier.padding(top = 8.dp),
				)
				Spacer(Modifier.height(14.dp))
				SshDetail(s("TOOL", "工具"), request.toolName)
				SshDetail(s("HOST", "主机"), request.host)
				SshDetail(s("USER", "用户"), request.username)
				SshDetail(s("OPERATION", "操作"), request.operation)
				Spacer(Modifier.weight(1f))
				Text(
					text = s("Allowing applies to this conversation. Later SSH calls will not ask again; revoke it in Settings → SSH.", "允许只作用于本会话。后续 SSH 调用不再询问；可在 设置 → SSH 中撤销。"),
					fontSize = 11.sp,
					color = Muted,
				)
				Spacer(Modifier.height(12.dp))
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.End,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = s("Deny", "拒绝"),
						color = Muted,
						fontSize = 13.sp,
						modifier = Modifier.clickable { onDecision(false) },
					)
					Spacer(Modifier.width(20.dp))
					Button(
						onClick = { onDecision(true) },
						colors = ButtonDefaults.buttonColors(
							containerColor = Color(0xFFB3402A),
							contentColor = Paper,
						),
						shape = RoundedCornerShape(6.dp),
					) {
						Text(s("Allow for this conversation", "允许此会话"))
					}
				}
			}
		}
	}
}

@Composable
private fun SshDetail(label: String, value: String) {
	Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
		Text(
			text = label,
			fontFamily = FontFamily.Monospace,
			fontSize = 9.sp,
			color = Muted,
			modifier = Modifier.width(90.dp),
		)
		Text(
			text = value.ifBlank { "?" },
			fontFamily = FontFamily.Monospace,
			fontSize = 11.sp,
			color = Paper,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun RewindWindow(
	store: DesktopStore,
	conversationId: String?,
	onCancel: () -> Unit,
	onRewind: (String) -> Unit,
) {
	var points by remember { mutableStateOf<List<DesktopStore.RewindPoint>>(emptyList()) }
	var selected by remember { mutableStateOf<String?>(null) }
	var loaded by remember { mutableStateOf(false) }

	LaunchedEffect(conversationId) {
		points = conversationId?.let { store.rewindPoints(it) }.orEmpty()
		selected = points.lastOrNull { it.restorable }?.messageId
		loaded = true
	}

	DialogWindow(
		onCloseRequest = onCancel,
		title = "inhuman · rewind",
		state = rememberDialogState(width = 620.dp, height = 560.dp),
	) {
		MaterialTheme(colorScheme = DesktopColors) {
			Column(
				modifier = Modifier.fillMaxSize().background(GraphiteRaised).padding(24.dp),
			) {
				Text(
					text = s("REWIND", "回退"),
					fontFamily = FontFamily.Monospace,
					fontSize = 11.sp,
					letterSpacing = 1.5.sp,
					color = Signal,
				)
				Text(
					text = s(
						"Choose a user turn. That turn and every later turn are removed; the workspace is restored to the checkpoint taken before it, and its text and images return to the composer.",
						"选择一个 user 回合。该回合及其后的所有回合会被移除；工作区恢复到该回合开始前的 checkpoint，文本和图片回到输入框。",
					),
					fontSize = 12.sp,
					lineHeight = 19.sp,
					color = Muted,
					modifier = Modifier.padding(top = 10.dp),
				)
				Spacer(Modifier.height(10.dp))
				if (!loaded) {
					Text(s("Loading…", "加载中…"), fontSize = 12.sp, color = Muted)
				} else if (points.isEmpty()) {
					Text(
						s("There are no user turns yet.", "还没有 user 回合。"),
						fontSize = 12.sp,
						color = Muted,
					)
				} else {
					LazyColumn(modifier = Modifier.weight(1f)) {
						items(points.asReversed(), key = { it.messageId }) { point ->
							val isSelected = point.messageId == selected
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.padding(vertical = 2.dp)
									.background(
										if (isSelected) Color(0xFF2A241F) else Color.Transparent,
										RoundedCornerShape(6.dp),
									)
									.clickable(enabled = point.restorable) {
										selected = point.messageId
									}
									.padding(10.dp),
								verticalAlignment = Alignment.CenterVertically,
							) {
								Column(modifier = Modifier.weight(1f)) {
									Text(
										text = point.text.ifBlank {
											s("[image message]", "[图片消息]")
										},
										fontSize = 12.sp,
										color = if (point.restorable) Paper else Muted,
										maxLines = 2,
										overflow = TextOverflow.Ellipsis,
									)
									Text(
										text = when {
											!point.restorable ->
												s("No start checkpoint; cannot restore", "没有开始 checkpoint，无法恢复")
											point.attachments.isNotEmpty() ->
												s("${point.attachments.size} image(s)", "${point.attachments.size} 张图片")
											else -> ""
										},
										fontFamily = FontFamily.Monospace,
										fontSize = 9.sp,
										color = Muted,
									)
								}
								if (isSelected) {
									Text("●", color = Signal, fontSize = 10.sp)
								}
							}
						}
					}
				}
				Spacer(Modifier.height(10.dp))
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.End,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = s("Cancel", "取消"),
						color = Muted,
						fontSize = 13.sp,
						modifier = Modifier.clickable { onCancel() },
					)
					Spacer(Modifier.width(20.dp))
					Button(
						onClick = { selected?.let(onRewind) },
						enabled = selected != null,
						colors = ButtonDefaults.buttonColors(
							containerColor = Color(0xFFB3402A),
							contentColor = Paper,
						),
						shape = RoundedCornerShape(6.dp),
					) {
						Text(s("Rewind to here", "回退到这里"))
					}
				}
			}
		}
	}
}

private fun openExternally(file: File) {
	runCatching {
		if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
			Desktop.getDesktop().open(file)
		}
	}
}
