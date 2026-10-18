package com.inhuman.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.data.Branches
import com.inhuman.core.design.Layout
import com.inhuman.core.design.comfortableGutter
import com.inhuman.ui.common.RemoteBadge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One conversation.
 *
 * The reply is rendered as its parts rather than as one string: reasoning is
 * dimmed and separated from the answer, because a model that thinks out loud
 * should not push its answer off the top of the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
	viewModel: ChatViewModel,
	onBack: () -> Unit,
	onOpenSettings: () -> Unit,
	onOpenFiles: () -> Unit = {},
	/** Raised while a dialog is up, so system Back closes it rather than leaving. */
	onDialogVisibilityChange: (Boolean) -> Unit = {},
) {
	// ## Why nothing here reads the whole state
	//
	// This used to be `val state by viewModel.state.collectAsStateWithLifecycle()`
	// and every field was read through it. A streaming reply publishes a new
	// `ChatUiState` on each throttled tick, so that one line invalidated the
	// entire screen: the `Scaffold` and both of its lambdas were rebuilt, the
	// title `buildString` re-ran, and the `LazyColumn`'s content DSL was
	// re-executed several times a second -- for a change that affects one row.
	//
	// Compose tracks reads, not fields, so the fix is to read narrowly. Each
	// `by` below subscribes this scope to one field; a field that only the
	// Composer needs is read where the Composer is composed, and the transcript
	// is read inside the list's own lambda. What is left reading at the top is
	// the handful of values the chrome genuinely depends on.
	val title by viewModel.state.map { it.title }.collectAsStateWithLifecycle("New conversation")
	val modelLabel by viewModel.state.map { it.modelLabel }.collectAsStateWithLifecycle("default model")
	val thinking by viewModel.state.map { it.thinking }.collectAsStateWithLifecycle(false)
	val reasoningEffort by viewModel.state
		.map { it.reasoningEffort }
		.collectAsStateWithLifecycle<String?>(null)
	val error by viewModel.state.map { it.error }.collectAsStateWithLifecycle(null)
	val pendingApproval by viewModel.state.map { it.pendingApproval }.collectAsStateWithLifecycle(null)

	// The composer's own inputs. Separate reads for the same reason as the rest:
	// typing changes one of these and must not invalidate the transcript, and a
	// streamed token changes `isStreaming` at the start and end of a turn
	// without touching the text in the field.
	val input by viewModel.state.map { it.input }.collectAsStateWithLifecycle("")
	val configured by viewModel.state.map { it.configured }.collectAsStateWithLifecycle(false)
	val pendingAttachments by viewModel.state
		.map { it.pendingAttachments }
		.collectAsStateWithLifecycle(emptyList())
	val modelSupportsVision by viewModel.state
		.map { it.modelSupportsVision }
		.collectAsStateWithLifecycle(false)

	val listState = rememberLazyListState()
	val scope = rememberCoroutineScope()
	var showModelPicker by remember { mutableStateOf(false) }
	// Whether the context figures are open. Screen state rather than view model
	// state: it is about what this screen is showing, and a rotation that
	// closed it is the correct outcome.
	var showContextDetail by remember { mutableStateOf(false) }

	// Named `ctx`, not `context`: the bare name collides with the Kotlin
	// stdlib's `context(...)` at every call site that has a lambda in scope, and
	// the compiler then reports "function invocation expected" rather than
	// anything about naming. The same trap caught the documents provider.
	val ctx = LocalContext.current

	// The image picker. ACTION_OPEN_DOCUMENT like the file browser's import,
	// and for the same reasons: a real filename and a grant that survives long
	// enough to copy the bytes through it.
	val importer = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			val name = queryImageName(ctx, uri)
			val mime = ctx.contentResolver.getType(uri) ?: "image/*"
			viewModel.attachImage(name, mime) {
				ctx.contentResolver.openInputStream(uri)
					?: error("could not open the selected image")
			}
		}
	}

	// ## Why the transcript is read down there and not here
	//
	// The scaffold below wants six values that change on a human timescale: a
	// title, a model label, a thinking flag, an effort level, an error, a
	// pending approval. The transcript is the one thing that changes several
	// times a second, and it is also the only heavy value in the state -- a new
	// list, with a new object for the streaming row, on every throttled tick.
	//
	// Reading `messages` in this scope would mean every tick invalidates the
	// whole screen: the top bar rebuilt, its title `buildString` re-run, and the
	// `LazyColumn`'s content DSL re-executed. Asking for it inside the list's own
	// lambda keeps the invalidation inside the list, which is where the change
	// is, and leaves the chrome alone. Compose tracks reads rather than fields,
	// so this is the whole of the fix.
	val messageCount by viewModel.state.map { it.messages.size }.collectAsStateWithLifecycle(0)
	val lastMessageId by viewModel.state.map { it.messages.lastOrNull()?.id }.collectAsStateWithLifecycle(null)
	val lastMessageLength by viewModel.state
		.map { it.messages.lastOrNull()?.plainText?.length ?: 0 }
		.collectAsStateWithLifecycle(0)
	val isStreaming by viewModel.state.map { it.isStreaming }.collectAsStateWithLifecycle(false)
	val sshAllowed by viewModel.state.map { it.sshAllowed }.collectAsStateWithLifecycle(false)
	val rollbackAvailable by viewModel.state
		.map { it.rollbackAvailable }
		.collectAsStateWithLifecycle(false)
	val rollbackBlockedReason by viewModel.state
		.map { it.rollbackBlockedReason }
		.collectAsStateWithLifecycle(null)
	val pendingSshConsent by viewModel.state
		.map { it.pendingSshConsent }
		.collectAsStateWithLifecycle(null)
	var showChatMenu by remember { mutableStateOf(false) }
	var rollbackTarget by remember { mutableStateOf<ChatMessageUi?>(null) }

	// Reported upward so the app's single Back handler knows a dialog is up.
	// Both this and the approval gate are transient questions that Back should
	// answer by dismissing, not by navigating away from.
	val anyDialogOpen = showModelPicker || pendingApproval != null ||
		pendingSshConsent != null || rollbackTarget != null
	LaunchedEffect(anyDialogOpen) { onDialogVisibilityChange(anyDialogOpen) }

	// Re-read the key and demo setting whenever this screen appears. The view
	// model outlives the navigation trip to settings, so without this a key
	// added there is not noticed until the app restarts.
	LaunchedEffect(Unit) { viewModel.refreshConfiguration() }

	// Follow the newest content while a reply arrives, but never fight the user.
	//
	// ## Why the key is not just the message count
	//
	// It was, and that made this effect nearly dead: a streaming reply does not
	// change the number of messages, so nothing scrolled while one grew. Once
	// the bubble passed the height of the screen the newest tokens landed below
	// the fold and the reader had to chase them -- the opposite of what this is
	// for.
	//
	// The key is the count plus the length of the last message's text, which
	// changes as tokens arrive and changes *once* when a reply is appended. It
	// deliberately is not the text itself: comparing a growing string on every
	// chunk to decide whether to scroll would cost more than the scroll.
	//
	// And it only scrolls when the user is already near the bottom -- yanking
	// the view down while someone is reading earlier in the conversation is the
	// single most annoying thing a chat app can do.
	val atBottom by remember {
		derivedStateOf {
			val info = listState.layoutInfo
			val last = info.visibleItemsInfo.lastOrNull()
			// The last item has to be the *last item*, and its bottom has to be
			// at the viewport's bottom. The old test allowed the second-to-last
			// item to count as "at bottom"; that made a small manual scroll look
			// like following and let a growing reply drag the user down.
			last == null ||
				(last.index == info.totalItemsCount - 1 &&
					last.offset + last.size <= info.viewportEndOffset + 2)
		}
	}

	// Height of the last visible row. A reasoning/tool accordion expanding or
	// collapsing changes layout without changing the message count or the
	// answer's text length, which is why the follow effect used to miss it.
	val lastItemHeight by remember {
		derivedStateOf {
			listState.layoutInfo.visibleItemsInfo.lastOrNull()?.size ?: 0
		}
	}
	val lastItemOffset by remember {
		derivedStateOf {
			listState.layoutInfo.visibleItemsInfo.lastOrNull()?.offset ?: 0
		}
	}

	// Whether the view should keep following the newest content.
	//
	// This cannot be derived from `atBottom` alone: when the last item grows,
	// the list's existing scroll position stays put, so the item's bottom moves
	// below the viewport and `atBottom` becomes false *because of the growth*.
	// A derived flag would therefore stop following at the exact moment it is
	// needed. Instead, user input turns it off and reaching the bottom turns it
	// back on; content growth never changes it.
	var followLatest by remember { mutableStateOf(true) }
	// Programmatic jumps also travel through nested scroll on some Compose
	// versions. Without this guard their UserInput source would turn following
	// off at the exact moment the user asked to follow again.
	var programmaticScroll by remember { mutableStateOf(false) }
	val followConnection = remember {
		object : NestedScrollConnection {
			override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
				if (source == NestedScrollSource.UserInput && !programmaticScroll) {
					followLatest = false
				}
				return Offset.Zero
			}
		}
	}
	LaunchedEffect(listState) {
		snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
			if (!scrolling && !followLatest) {
				// The gesture/fling has ended; if it landed at the bottom, the
				// next content change should follow again.
				followLatest = atBottom
			}
		}
	}
	LaunchedEffect(atBottom) {
		// Reaching the bottom is itself the signal, not only the end of a
		// gesture. A fling can finish with `atBottom` still catching up, and a
		// new user message can shift the layout after the scroll-end event.
		if (atBottom) followLatest = true
	}

	// Which message the regenerate control belongs to. Computed from the tail
	// key rather than the list, so the scan does not re-run several times a
	// second while a reply streams.
	var lastAssistantId by remember { mutableStateOf<String?>(null) }
	var lastUserId by remember { mutableStateOf<String?>(null) }
	LaunchedEffect(lastMessageId) {
		val messages = viewModel.state.value.messages
		lastAssistantId = messages.lastOrNull { !it.fromUser && !it.isToolResult }?.id
		lastUserId = messages.lastOrNull { it.fromUser }?.id
	}
	LaunchedEffect(messageCount, lastMessageLength, lastItemHeight, lastItemOffset) {
		if (messageCount > 0 && followLatest && !programmaticScroll) {
			// scrollToItem, not animateScrollToItem: the list is already growing
			// under the user's eyes, and an animation racing the arrival of the
			// next chunk is what makes streaming look unsteady.
			//
			// `lastItemHeight` / `lastItemOffset` are part of the key because a
			// live reasoning or tool section can expand without adding a message
			// or a character to the answer. The offset also catches a preceding
			// row growing, which shifts the last row without resizing it.
			programmaticScroll = true
			try {
				listState.scrollToLatest(messageCount - 1)
			} finally {
				programmaticScroll = false
			}
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = {
					// Tapping the title opens the model controls. The title is
					// the only thing in the bar that is not already a button, and
					// putting the current model under it means the settings are
					// visible without being in the way -- which matters because
					// they change what an answer costs.
					Column(
						modifier = Modifier.clickable { showModelPicker = true },
					) {
						Text(
							text = title,
							style = MaterialTheme.typography.titleMedium,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						Text(
							text = buildString {
								append(modelLabel)
								if (thinking) {
									append(" · thinking")
									reasoningEffort?.let { append(" ($it)") }
								}
							},
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
				},
				navigationIcon = {
					IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.titleLarge) }
				},
				actions = {
					TextButton(onClick = onOpenFiles) { Text("Files") }
					TextButton(onClick = onOpenSettings) { Text("Key") }
					Box {
						IconButton(onClick = { showChatMenu = true }) {
							Icon(
								imageVector = Icons.Filled.MoreVert,
								contentDescription = "More",
							)
						}
						DropdownMenu(
							expanded = showChatMenu,
							onDismissRequest = { showChatMenu = false },
						) {
							DropdownMenuItem(
								text = { Text("Revoke SSH access") },
								enabled = sshAllowed,
								onClick = {
									showChatMenu = false
									viewModel.revokeSshAccess()
								},
							)
						}
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
					titleContentColor = MaterialTheme.colorScheme.onBackground,
					navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
				),
			)
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { inner ->
		// ## The measure, and why the bottom of the screen needed it too
		//
		// A message bubble is capped at [Layout.ReadableMeasure] so a paragraph
		// does not run the width of a tablet. The composer underneath it was
		// not, so on a tablet the input field, the attach button, the send
		// button and the context ring stretched the full 1280dp while the
		// conversation above them stopped at 640 -- one column of text with a
		// control strip twice its width, and the send button parked in a corner
		// nobody looks at.
		//
		// Capping and centring the strip puts it under the conversation it
		// belongs to. On a phone the cap is inert: `screenWidthDp` is well under
		// 640, so `widthIn` never binds and nothing moves. That is the point of
		// expressing it as a maximum rather than as a form-factor branch -- one
		// layout, correct at both sizes.
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(inner)
				.windowInsetsPadding(WindowInsets.safeDrawing)
				// imePadding, so the composer rides above the keyboard instead
				// of being covered by it.
				.imePadding(),
		) {
			// Read inside this lambda, so a new message or a streamed token
			// invalidates the list rather than the whole screen. See the note
			// above the state reads.
			val messages by viewModel.state.map { it.messages }.collectAsStateWithLifecycle(emptyList())
			// Structured so Compose can skip it: two stable fields beat a
			// `ContextUsage` read through a state object that changes for
			// unrelated reasons.
			val usage by viewModel.state
				.map { ContextUsage(it.context.usedTokens, it.context.contextWindow) }
				.collectAsStateWithLifecycle(ContextUsage(null, null))

			Box(modifier = Modifier.weight(1f)) {
				LazyColumn(
					state = listState,
					modifier = Modifier
						.fillMaxSize()
						.nestedScroll(followConnection),
					contentPadding = PaddingValues(
						horizontal = comfortableGutter(),
						vertical = 12.dp,
					),
					verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
				) {
					items(messages, key = { it.id }) { message ->
						MessageBubble(
							message = message,
							// The newest assistant reply, which is the only one that
							// can be regenerated without discarding what follows it.
							isLastAssistantMessage = !message.fromUser && !message.isToolResult &&
								message.id == lastAssistantId,
							isLastUserMessage = message.fromUser && message.id == lastUserId,
							canRollback = rollbackAvailable,
							rollbackBlockedReason = rollbackBlockedReason,
							isStreaming = isStreaming,
							onRegenerate = viewModel::regenerate,
							onRollback = { rollbackTarget = message },
							// Both ids go up: the alternative being switched to is
							// not on the active path, so the view model cannot find
							// its parent by searching the transcript.
							onSelectBranch = { parentId, childId ->
								if (parentId != null) viewModel.selectBranch(parentId, childId)
							},
						)
					}
				}

				// Only while the transcript is scrolled away from the newest
				// message. It is an overlay rather than a row so it does not
				// change the layout when it appears, and it is inside the
				// transcript's Box so it sits above the composer rather than
				// over the input.
				if (!atBottom && messages.isNotEmpty()) {
					FilledIconButton(
						onClick = {
							followLatest = true
							programmaticScroll = true
							scope.launch {
								try {
									// A long reply's height is not known until it has
									// been composed and its Markdown laid out, and
									// that happens *after* the first jump. A single
									// pass therefore lands short when the last item
									// grows; repeat after a frame or two so the
									// second pass starts from the measured height.
									// Finite `scrollBy` steps also avoid the overflow
									// of `scrollOffset = Int.MAX_VALUE`.
									var passes = 0
									while (passes++ < 4) {
										listState.scrollToLatest(messages.lastIndex)
										delay(120)
									}
								} finally {
									programmaticScroll = false
								}
							}
						},
						modifier = Modifier
							.align(Alignment.BottomEnd)
							.padding(end = comfortableGutter(), bottom = 12.dp),
					) {
						Icon(
							imageVector = Icons.Filled.KeyboardArrowDown,
							contentDescription = "Jump to latest message",
						)
					}
				}
			}

			// The bottom strip, centred on the same measure as the bubbles
			// above it. Both children fill the cap and the column centres them.
			Column(
				modifier = Modifier.fillMaxWidth(),
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				error?.let { message ->
					Row(
						// Constrain first, fill second: see the note on the
						// composer's modifier.
						modifier = Modifier
							.widthIn(max = Layout.ReadableMeasure)
							.fillMaxWidth()
							.padding(horizontal = comfortableGutter(), vertical = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = message,
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.error,
							modifier = Modifier.weight(1f),
						)
						TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
					}
				}

				Composer(
					value = input,
					onValueChange = viewModel::onInputChange,
					onSend = viewModel::send,
					onStop = viewModel::stop,
					isStreaming = isStreaming,
					enabled = configured,
					attachments = pendingAttachments,
					canAttachImages = modelSupportsVision,
					onAttach = { importer.launch(arrayOf("image/*")) },
					onRemoveAttachment = viewModel::removeAttachment,
					context = usage,
					showContextDetail = showContextDetail,
					onToggleContextDetail = { showContextDetail = !showContextDetail },
					// ## The modifier order is load-bearing
					//
					// `fillMaxWidth()` is greedy: it takes the whole incoming
					// maximum. Put it *before* `widthIn` and the cap never
					// binds -- the field stretches to 2208px on a 1280dp tablet
					// while the messages above it stop at 1232. Constraining
					// first and filling second is what makes the maximum mean
					// anything.
					modifier = Modifier
						.widthIn(max = Layout.ReadableMeasure)
						.fillMaxWidth(),
				)
			}
		}
	}

	// The model and reasoning controls, for this conversation only.
	//
	// Opened from the title rather than given a button: it is a setting that is
	// looked at occasionally and matters constantly, and a bar with Files, Key
	// and Model in it is a bar with no room for the conversation's name.
	if (showModelPicker) {
		val chosenModel by viewModel.state.map { it.model }.collectAsStateWithLifecycle(null)
		val resolvedModel by viewModel.state.map { it.effectiveModel }.collectAsStateWithLifecycle(null)

		ModelPickerDialog(
			chat = viewModel,
			currentModel = chosenModel,
			effectiveModel = resolvedModel,
			currentEffort = reasoningEffort,
			currentThinking = thinking,
			onDismiss = { showModelPicker = false },
		)
	}

	// The approval gate.
	// Rendered as a dialog over the transcript rather than inline, because it
	// blocks the turn: nothing else happens until it is answered, and an inline
	// card could be scrolled past without noticing that the run has stopped.
	//
	// The text is written for a person, not for the model. Showing the raw tool
	// name and JSON arguments would be technically complete and practically
	// useless -- the point of the gate is that someone reads it before saying
	// yes, and nobody reads `{"path":"a.txt"}` carefully.
	pendingApproval?.let { pending ->		AlertDialog(
			onDismissRequest = viewModel::declinePendingCall,
			title = { Text("Allow this action?") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.InMessage)) {
					Text(pending.summary, style = MaterialTheme.typography.bodyLarge)
					if (!pending.details.isNullOrBlank() || pending.call.argumentsJson.isNotBlank()) {
						Text(
							text = pending.details ?: pending.call.argumentsJson,
							style = MaterialTheme.typography.bodyMedium,
							fontFamily = FontFamily.Monospace,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier
								.fillMaxWidth()
								.background(
									MaterialTheme.colorScheme.surfaceVariant,
									RoundedCornerShape(8.dp),
								)
								.padding(10.dp),
						)
					}
				}
			},
			confirmButton = {
				TextButton(onClick = viewModel::approvePendingCall) { Text("Allow") }
			},
			dismissButton = {
				TextButton(onClick = viewModel::declinePendingCall) { Text("Decline") }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	pendingSshConsent?.let { consent ->
		SshConsentDialog(
			consent = consent,
			onAllow = viewModel::allowSshConsent,
			onDeny = viewModel::denySshConsent,
		)
	}

	rollbackTarget?.let { target ->
		AlertDialog(
			onDismissRequest = { rollbackTarget = null },
			title = { Text("Rewind to before this message?") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(
						"This removes this message and everything after it, and " +
							"puts the text back in the composer so you can edit it.",
					)
					Text(
						"The conversation's folder is restored to how it was before " +
							"this message. Files changed after that point are lost too.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			},
			confirmButton = {
				TextButton(
					onClick = {
						rollbackTarget = null
						viewModel.rollbackTo(target.id)
					},
				) { Text("Rewind") }
			},
			dismissButton = {
				TextButton(onClick = { rollbackTarget = null }) { Text("Cancel") }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

/**
 * The once-per-conversation SSH capability grant.
 *
 * Deliberately not the normal approval dialog: this is not "may I run this one
 * command", it is "may this conversation use SSH at all". The first command is
 * shown because it is the most concrete warning available, but the text says
 * plainly that allowing it allows every later SSH call in the conversation.
 */
@Composable
private fun SshConsentDialog(
	consent: SshConsent,
	onAllow: () -> Unit,
	onDeny: () -> Unit,
) {
	var understood by remember { mutableStateOf(false) }
	AlertDialog(
		onDismissRequest = onDeny,
		title = { Text("Allow SSH in this conversation?") },
		text = {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 460.dp)
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Text(
					text = "The agent will be able to connect to ${consent.username}@${consent.host} " +
						"and run any command there. It can also connect to any other host it is " +
						"given credentials for, without asking again in this conversation.",
					style = MaterialTheme.typography.bodyLarge,
				)
				Text(
					text = "First command:\n${consent.command}",
					style = MaterialTheme.typography.bodyMedium,
					fontFamily = FontFamily.Monospace,
					modifier = Modifier
						.fillMaxWidth()
						.background(
							MaterialTheme.colorScheme.surfaceVariant,
							RoundedCornerShape(8.dp),
						)
						.padding(10.dp),
				)
				consent.hostKeyFingerprint?.let { fingerprint ->
					Text(
						text = "Pinned host key:\n$fingerprint",
						style = MaterialTheme.typography.bodySmall,
						fontFamily = FontFamily.Monospace,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				// The checkbox sits above the long warning, not after it. On a
				// phone the warning scrolls; with the checkbox last it was below
				// the fold, so "Allow SSH" was disabled with no visible reason.
				Row(verticalAlignment = Alignment.CenterVertically) {
					Checkbox(
						checked = understood,
						onCheckedChange = { understood = it },
					)
					Text(
						text = "I understand the agent can cause irreversible damage",
						style = MaterialTheme.typography.bodyMedium,
						modifier = Modifier.weight(1f),
					)
				}
				Text(
					text = "It can delete data, stop services, change access, install software, " +
						"or expose files. Some damage cannot be undone. Prompt injection from a " +
						"web page or a file can direct it.",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.error,
				)
				Text(
					text = "If you paste a password or key, it becomes part of this " +
						"conversation and is sent to DeepSeek. A key file in the workspace " +
						"stays on the device and is read locally. Files in the workspace can " +
						"be sent to the remote machine.",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		confirmButton = {
			TextButton(onClick = onAllow, enabled = understood) { Text("Allow SSH") }
		},
		dismissButton = {
			TextButton(onClick = onDeny) { Text("Cancel") }
		},
		containerColor = MaterialTheme.colorScheme.surface,
		titleContentColor = MaterialTheme.colorScheme.onSurface,
		textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

/**
 * Put the end of [lastIndex] at the viewport bottom.
 *
 * `scrollToItem` alone aligns the item's **top**, which for a reply taller than
 * the viewport leaves the newest tokens below the fold. The follow effect used
 * to call only that, so a long streaming answer could look like it was not
 * following even when the scroll ran. The finite `scrollBy` steps finish the
 * job and avoid `scrollOffset = Int.MAX_VALUE`.
 */
private suspend fun LazyListState.scrollToLatest(lastIndex: Int) {
	scrollToItem(lastIndex)
	var steps = 0
	while (canScrollForward && steps++ < 20) {
		scrollBy(10_000f)
	}
}


/**
 * Step between the alternatives at one point in the conversation.
 *
 * ## Why arrows and a count rather than a list
 *
 * The alternatives are answers to the same question, so they are ordered only by
 * when they were produced -- there is nothing to choose between them by name.
 * A count with arrows says "this is one of three" and lets someone flip through,
 * which is what comparing two answers actually looks like.
 *
 * The arrows stop at the ends instead of wrapping. A control that cycles leaves
 * no way to tell whether the list has been around once, and with three answers
 * that matters: the reader is trying to be sure they have seen them all.
 */
@Composable
private fun BranchSwitcher(
	branches: Branches,
	onSelect: (String) -> Unit,
	enabled: Boolean,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier,
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(2.dp),
	) {
		IconButton(
			onClick = { branches.previousId?.let(onSelect) },
			enabled = enabled && branches.previousId != null,
			modifier = Modifier.size(28.dp),
		) {
			Text(
				text = "‹",
				style = MaterialTheme.typography.titleMedium,
				color = if (enabled && branches.previousId != null) {
					MaterialTheme.colorScheme.onSurfaceVariant
				} else {
					MaterialTheme.colorScheme.outlineVariant
				},
			)
		}

		Text(
			text = branches.label,
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)

		IconButton(
			onClick = { branches.nextId?.let(onSelect) },
			enabled = enabled && branches.nextId != null,
			modifier = Modifier.size(28.dp),
		) {
			Text(
				text = "›",
				style = MaterialTheme.typography.titleMedium,
				color = if (enabled && branches.nextId != null) {
					MaterialTheme.colorScheme.onSurfaceVariant
				} else {
					MaterialTheme.colorScheme.outlineVariant
				},
			)
		}
	}
}

/**
 * One tool call, inside the collapsed section.
 *
 * Shows the name, the argument that identifies it, and what happened -- which is
 * what someone opening this section is trying to find out. The raw JSON is not
 * shown: `{"path":"notes.txt","content":"..."}` spends a screenful saying what
 * `write_file notes.txt` says in a line, and the second is what a person reads.
 */
@Composable
private fun ToolCallLine(call: ToolCallUi) {
	val status = when (call.state) {
		ToolCallUi.State.Streaming -> "…"
		ToolCallUi.State.Ready -> "ready"
		ToolCallUi.State.Running -> "running"
		ToolCallUi.State.Done -> "done"
		ToolCallUi.State.Declined -> "declined"
		ToolCallUi.State.Failed -> "failed"
	}
	val statusColor = when (call.state) {
		ToolCallUi.State.Failed -> MaterialTheme.colorScheme.error
		ToolCallUi.State.Declined -> MaterialTheme.colorScheme.outline
		else -> MaterialTheme.colorScheme.onSurfaceVariant
	}

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.Top,
	) {
		Text(
			text = call.displayName,
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.tertiary,
			fontFamily = FontFamily.Monospace,
		)
		if (call.displayName == "ssh_exec") RemoteBadge()
		Text(
			// The single most identifying argument, which for these tools is
			// almost always the path or the query.
			text = summariseArguments(call),
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Text(
			text = status,
			style = MaterialTheme.typography.labelLarge,
			color = statusColor,
		)
	}
}

/**
 * The argument worth showing, or a fallback.
 *
 * Deliberately shallow: it reads the first of a few known keys rather than
 * parsing properly, because the value here is a label in a collapsed list and
 * a wrong-but-present argument name is better than a blank line. The real
 * arguments are in the transcript and in the tool result.
 */
private fun summariseArguments(call: ToolCallUi): String {
	val args = call.argumentsJson
	if (args.isBlank()) return ""
	for (key in ARGUMENT_KEYS) {
		val match = argumentPattern(key).find(args) ?: continue
		return match.groupValues[1].replace("\\\"", "\"").take(60)
	}
	return args.take(60)
}

/** The keys worth showing, in the order worth trying. */
private val ARGUMENT_KEYS = listOf("path", "host", "hostname", "query", "pattern", "name")

/**
 * One compiled pattern per key, built on first use.
 *
 * These were constructed inside the loop on every call, and a tool row
 * summarises its arguments on every recomposition -- so scrolling a transcript
 * of tool calls compiled the same four patterns again and again. `Regex`
 * compiles eagerly, which makes this the expensive kind of allocation rather
 * than the cheap kind.
 */
private val argumentPatterns = mutableMapOf<String, Regex>()

private fun argumentPattern(key: String): Regex = synchronized(argumentPatterns) {
	argumentPatterns.getOrPut(key) { Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"") }
}

/** Compiles once, for the same reason as [argumentPatterns]. */
private val COUNTED_SUMMARY =
	Regex("^(.*?)\\s+—\\s+(\\d+)\\s+(results?|files?|items?|matches?)$")

/**
 * The one line that stands for a tool result.
 *
 * ## The duplicate this removes
 *
 * A search result opens with its own header — `latest Python version release — 5
 * results` — and the collapsed row was prefixing that with the tool name, so the
 * row read `web_search — latest Python version release — 5 results` while the
 * expanded body underneath began with the same words again. On a phone that
 * wrapped to two lines, which is most of what made a one-line index entry stand
 * 53dp tall.
 *
 * The count moves out of the text and into the row's trailing slot, where the
 * collapsed header already has a place for it -- so the same fact is stated once,
 * in the position a reader looks for it.
 *
 * ## Why the parsing is this shallow
 *
 * It handles the shapes the tools here actually produce. Anything else falls
 * through to the raw first line, which is never wrong, only less tidy -- and a
 * general parser for prose would be a larger thing to get wrong than the two
 * lines it would save.
 */
private fun toolResultSummary(
	toolName: String?,
	firstLine: String,
): Pair<String, String?> {
	if (firstLine.isBlank()) return (toolName ?: "Tool result") to null

	// "… — 5 results", "… — 3 files", "… — 1 item".
	//
	// A file-level pattern rather than one built per call: this runs on every
	// recomposition of a tool-result row.
	val counted = COUNTED_SUMMARY.find(firstLine)
	val body = counted?.groupValues?.get(1)?.trim() ?: firstLine
	val count = counted?.groupValues?.get(2)?.let { "$it ${counted.groupValues[3]}" }

	// The count is stated once, in the slot made for it, and the tool name is
	// dropped when there is one: "5 results" already says a search happened.
	val text = if (count != null) body else toolName?.let { "$it — $body" } ?: body
	return text to count
}

@Composable
private fun MessageBubble(
	message: ChatMessageUi,
	isLastAssistantMessage: Boolean,
	isLastUserMessage: Boolean,
	canRollback: Boolean,
	rollbackBlockedReason: String?,
	isStreaming: Boolean,
	onRegenerate: () -> Unit,
	onRollback: () -> Unit,
	onSelectBranch: (String?, String) -> Unit,
) {
	val alignment = if (message.fromUser) Alignment.End else Alignment.Start
	val bubbleColor = if (message.fromUser) {
		MaterialTheme.colorScheme.primaryContainer
	} else {
		MaterialTheme.colorScheme.surface
	}
	val onColor = if (message.fromUser) {
		MaterialTheme.colorScheme.onPrimaryContainer
	} else {
		MaterialTheme.colorScheme.onSurface
	}

	// A tool result is machinery, not speech.
	//
	// It is rendered as one collapsed line rather than as a bubble, because it
	// is not something anyone said -- it is what came back. An eight-search turn
	// produces pages of it, and left expanded the transcript is mostly search
	// results with the answer somewhere underneath.
	//
	// The row names the tool and summarises what came back, because those are
	// the two things that decide whether it is worth opening.
	if (message.isToolResult) {
		val firstLine = message.plainText.lineSequence()
			.firstOrNull { it.isNotBlank() }
			?.trim()
			.orEmpty()
		val (summaryText, summaryCount) = toolResultSummary(message.toolName, firstLine)
		// Mark the row, not just the picker: this is where the cost is incurred.
		val paid = ToolCatalog.all.firstOrNull { it.name == message.toolName }?.paid == true
		CollapsibleSection(
			summary = summaryText,
			// The count goes in the header's right-hand slot rather than into the
			// sentence. It is the same fact either way, but a fixed-width slot
			// does not push the description along with it, so a column of these
			// lines up.
			trailing = summaryCount,
			paid = paid,
			modifier = Modifier
				.widthIn(max = Layout.ReadableMeasure)
				// No vertical padding of its own. The collapsed header already
				// carries its own inset, and the 2dp that used to be here was
				// part of a measured 47px gap between two one-line rows.
				.padding(vertical = 0.dp),
		) {
			Text(
				text = message.plainText.ifBlank { "(empty)" },
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				fontFamily = FontFamily.Monospace,
			)
		}
		return
	}

	Column(
		modifier = Modifier.fillMaxWidth(),
		horizontalAlignment = alignment,
	) {
		Box(
			modifier = Modifier
				.widthIn(max = Layout.ReadableMeasure)
				.background(bubbleColor, RoundedCornerShape(14.dp))
				.padding(horizontal = 12.dp, vertical = 8.dp),
		) {
			Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.InMessage)) {
				// Reasoning first, open only while it is the newest working
				// section, and never in the way of the answer.
				//
				// It is shown when a model has thought and hidden otherwise, so
				// a reply that used no thinking has no empty header above it. The
				// character count is in the header because "Thought for a moment"
				// and "Thought for four thousand characters" are different
				// propositions and the second is worth opening.
				val reasoning = message.parts
					.filter { it.kind == AssistantPart.Kind.Reasoning }
					.joinToString("\n\n") { it.text }
				val calls = message.toolCalls
				val hasText = message.parts.any {
					it.kind == AssistantPart.Kind.Text && it.text.isNotBlank()
				}
				// One section is open at a time, and only while the turn is
				// still producing. A tool call supersedes reasoning; the answer
				// supersedes both.
				val live = liveAssistantSection(
					isStreaming = isStreaming,
					isLatestAssistant = isLastAssistantMessage,
					hasReasoning = reasoning.isNotBlank(),
					hasText = hasText,
					toolCallCount = calls.size,
					answerAfterCalls = message.answerAfterCalls,
				)
				if (reasoning.isNotBlank() && !message.fromUser) {
					CollapsibleSection(
						summary = "Thinking",
						trailing = "${reasoning.length} chars",
						autoExpand = live == LiveAssistantSection.Reasoning,
					) {
						ReasoningBody(reasoning)
					}
				}

				// Tool calls, grouped together, and open while they are the
				// newest working section.
				//
				// One section rather than one per call: a turn that searched
				// eight times would otherwise produce eight headers, and the
				// question a reader has is "what did it do", not "what did the
				// fourth call do".
				if (calls.isNotEmpty()) {
					CollapsibleSection(
						summary = if (calls.size == 1) "Used one tool" else "Used ${calls.size} tools",
						trailing = calls.count { it.state == ToolCallUi.State.Failed }
							.takeIf { it > 0 }?.let { "$it failed" },
						autoExpand = live == LiveAssistantSection.Tools,
					) {
						Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed)) {
							calls.forEach { ToolCallLine(it) }
						}
					}
				}

				// The answer. Everything above is working-out; this is the part
				// that was asked for, and it is the only part that is always
				// visible.
				message.parts
					.filter { it.kind == AssistantPart.Kind.Text }
					.forEach { part ->
						if (message.fromUser) {
							// The user's own text is rendered literally. They
							// typed it; interpreting their asterisks as
							// formatting would silently change what they said.
							Text(
								text = part.text,
								style = MaterialTheme.typography.bodyLarge,
								color = onColor,
							)
						} else {
							MarkdownText(text = part.text, deltas = message.deltas)
						}
					}

				// Attachment names, so a message that was mostly a picture does
				// not look like it had no content.
				if (message.attachments.isNotEmpty()) {
					message.attachments.forEach { attachment ->
						Text(
							text = "📎 ${attachment.displayName}",
							style = MaterialTheme.typography.labelLarge,
							color = onColor,
						)
					}
				}

				if (message.isStreaming && message.parts.isEmpty()) {
					CircularProgressIndicator(
						modifier = Modifier.size(16.dp),
						strokeWidth = 2.dp,
						color = MaterialTheme.colorScheme.primary,
					)
				}
			}
		}

		message.error?.let {
			Text(
				text = it,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.error,
				modifier = Modifier.padding(top = 4.dp),
			)
		}

		// Token accounting, shown only when the provider reported it. Cache hits
		// are called out because DeepSeek bills them about 50x cheaper, so
		// seeing them is how a user knows prefix caching is working.
		// What the turn cost. Collapsed to a single line until tapped: speed and
		// the cache share answer the questions people actually ask, and the
		// full breakdown is there for when something looks wrong.
		message.usage?.let { u ->
			UsageLine(usage = u, modifier = Modifier.padding(top = 4.dp))
		}

		// Alternatives, when this reply has any.
		//
		// Placed under the answer rather than beside it, because it belongs to
		// the whole message: it says "this is one of three answers to the
		// question above", which is a fact about the message and not about any
		// part of its text.
		message.branches?.let { branches ->
			BranchSwitcher(
				branches = branches,
				onSelect = { childId -> onSelectBranch(message.branchParentId, childId) },
				enabled = !message.isStreaming,
				modifier = Modifier.padding(top = 6.dp),
			)
		}

		// Regenerate, on the newest reply only.
		//
		// Offered here rather than on every reply: regenerating mid-conversation
		// would discard everything after it, and a control that silently deletes
		// the rest of a transcript is not one to put within reach of a mis-tap.
		//
		// The view model sends this request **without tools**, so the refresh is
		// safe even when earlier calls in the turn used SSH or wrote files: it
		// rewrites the final text from the existing tool results and cannot run
		// anything again.
		if (isLastAssistantMessage && !message.isStreaming && !isStreaming) {
			IconButton(
				onClick = onRegenerate,
				modifier = Modifier
					.padding(top = 2.dp)
					.size(32.dp),
			) {
				Icon(
					// Refresh rather than a rerun arrow: the action produces
					// another answer, not a replay of this one, and the
					// existing answers stay reachable through the branch
					// switcher beside it.
					imageVector = Icons.Outlined.Refresh,
					contentDescription = "Answer again",
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(18.dp),
				)
			}
		}

		// Rewind is offered on the last user message, because that is the only
		// place where "before this message" is synonymous with "before this
		// turn". It restores the text and attachments to the composer; the
		// view model deletes the message and everything below it.
		if (isLastUserMessage && !isStreaming) {
			if (canRollback) {
				TextButton(
					onClick = onRollback,
					contentPadding = PaddingValues(horizontal = 8.dp),
					modifier = Modifier.padding(top = 2.dp),
				) {
					Text("Edit", style = MaterialTheme.typography.labelLarge)
				}
			} else if (rollbackBlockedReason != null) {
				Text(
					text = rollbackBlockedReason,
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.outline,
					modifier = Modifier.padding(top = 4.dp),
				)
			}
		}
	}
}

@Composable
private fun Composer(
	value: String,
	onValueChange: (String) -> Unit,
	onSend: () -> Unit,
	onStop: () -> Unit,
	isStreaming: Boolean,
	enabled: Boolean,
	attachments: List<PendingAttachment>,
	canAttachImages: Boolean,
	onAttach: () -> Unit,
	onRemoveAttachment: (PendingAttachment) -> Unit,
	context: ContextUsage,
	showContextDetail: Boolean,
	onToggleContextDetail: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(modifier = modifier) {
		// Above the field and right-aligned, so it reads as a status of the
		// conversation rather than as a control on the message being written.
		// It draws nothing at all when the provider reports nothing to draw.
		ContextGauge(
			usage = context,
			expanded = showContextDetail,
			onToggle = onToggleContextDetail,
			modifier = Modifier.padding(horizontal = comfortableGutter(), vertical = 4.dp),
		)

		// Staged images sit above the field, so they read as part of the message
		// being written rather than as a toolbar beside it.
		if (attachments.isNotEmpty()) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = comfortableGutter(), vertical = 4.dp),
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				attachments.forEach { attachment ->
					Row(
						modifier = Modifier
							.background(
								MaterialTheme.colorScheme.surfaceVariant,
								RoundedCornerShape(8.dp),
							)
							.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = "${attachment.displayName}  ${humanSize(attachment.sizeBytes)}",
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.widthIn(max = 200.dp),
						)
						IconButton(
							onClick = { onRemoveAttachment(attachment) },
							modifier = Modifier.size(28.dp),
						) {
							Text(
								text = "✕",
								style = MaterialTheme.typography.labelLarge,
								color = MaterialTheme.colorScheme.error,
							)
						}
					}
				}
			}
		}

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = comfortableGutter(), vertical = 8.dp),
			verticalAlignment = Alignment.Bottom,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			// Present but disabled when the model cannot see, rather than hidden.
			//
			// A control that vanishes leaves someone wondering where it went; one
			// that is there and greyed out can be tapped and answered. That answer
			// matters here because sending an image to a text-only model does not
			// fail -- it returns 200 and the model describes a placeholder -- so
			// there is no other place the reason could surface.
			IconButton(
				onClick = onAttach,
				enabled = enabled && canAttachImages,
				modifier = Modifier.padding(bottom = 4.dp),
			) {
				Icon(
					Icons.Outlined.Image,
					contentDescription = if (canAttachImages) {
						"Attach an image"
					} else {
						"This model cannot read images"
					},
					tint = if (enabled && canAttachImages) {
						MaterialTheme.colorScheme.onSurfaceVariant
					} else {
						MaterialTheme.colorScheme.outlineVariant
					},
				)
			}

			OutlinedTextField(
			value = value,
			onValueChange = onValueChange,
			modifier = Modifier.weight(1f),
			placeholder = {
				Text(if (enabled) "Message" else "Add an API key to start")
			},
			enabled = enabled && !isStreaming,
			maxLines = 6,
			shape = RoundedCornerShape(20.dp),
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
			keyboardActions = KeyboardActions(),
		)

		// One button, two meanings: send while idle, stop while streaming.
		// Reaching for a separate stop control mid-generation is worse than
		// having the same target do the obvious thing.
		if (isStreaming) {
			FilledIconButton(onClick = onStop) {
				Icon(Icons.Filled.Stop, contentDescription = "Stop")
			}
		} else {
			FilledIconButton(
				onClick = onSend,
				// An image alone is sendable: "what is this?" is implied, and
				// requiring text would make attaching one a two-step job.
				enabled = enabled && (value.isNotBlank() || attachments.isNotEmpty()),
				colors = IconButtonDefaults.filledIconButtonColors(
					containerColor = MaterialTheme.colorScheme.primary,
					contentColor = MaterialTheme.colorScheme.onPrimary,
				),
			) {
				Icon(Icons.Filled.ArrowUpward, contentDescription = "Send")
			}
		}
		}
	}
}

/** Sizes as a person reads them, not as a byte count. */
private fun humanSize(bytes: Long): String = when {
	bytes < 1024 -> "$bytes B"
	bytes < 1024 * 1024 -> "${bytes / 1024} KB"
	else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}

/**
 * The picked file's display name.
 *
 * Read while the grant is definitely alive rather than inside the async attach,
 * where a revoked permission would race it -- and with a fallback, because a
 * provider that does not expose DISPLAY_NAME is common enough that failing on it
 * would make attachments look broken for one file source.
 */
private fun queryImageName(context: android.content.Context, uri: android.net.Uri): String =
	runCatching {
		context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
			val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
			if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
		}
	}.getOrNull()?.takeIf { it.isNotBlank() } ?: "image"
