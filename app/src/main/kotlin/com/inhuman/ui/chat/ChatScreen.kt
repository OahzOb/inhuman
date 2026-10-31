package com.inhuman.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.AppCapabilities
import com.inhuman.R
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.data.ApprovalRecord
import com.inhuman.core.data.Branches
import com.inhuman.core.data.SnapshotStats
import com.inhuman.core.data.SnapshotTurn
import com.inhuman.core.data.WorkspaceChange
import com.inhuman.core.data.db.ApprovalDecision
import com.inhuman.core.files.WorkspaceSelfCheck
import com.inhuman.core.design.Eyebrow
import com.inhuman.core.design.currentWindowFrame
import com.inhuman.core.design.Hairline
import com.inhuman.core.design.Layout
import com.inhuman.core.design.MetaLine
import com.inhuman.core.design.StatusDot
import com.inhuman.core.design.WideDialog
import com.inhuman.core.design.WorkbenchGrid
import com.inhuman.core.design.comfortableGutter
import com.inhuman.ui.common.formatBytes
import com.inhuman.ui.common.FlowRowCompat
import com.inhuman.ui.common.PaidBadge
import com.inhuman.ui.common.RemoteBadge
import com.inhuman.ui.common.RewindBadge
import com.inhuman.ui.common.toolBlurb
import com.inhuman.ui.common.openWorkspaceFile
import com.inhuman.ui.common.exportFileName
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * One field of the chat state, as `State`, with the mapping built once.
 *
 * `collectAsStateWithLifecycle` keys its producer on the flow instance, so the
 * inline `viewModel.state.map { ... }` this file used to have created a new
 * flow -- and started a new collector -- on every recomposition of the screen:
 * once per keystroke in the composer, once per field the chrome reads. Forty
 * of them is not free, and none of it bought anything, because every selector
 * here is a pure field read that never changes.
 */
@Composable
private fun <S, T> fieldOf(
	source: Flow<S>,
	fallback: T,
	select: (S) -> T,
): State<T> = remember(source) { source.map(select) }.collectAsStateWithLifecycle(fallback)

/**
 * Whether a nested-scroll event means the user took the list away from the end.
 *
 * Split out to be testable, because the bug it exists for is invisible in a
 * screenshot: a horizontal drag inside a too-wide code block reached the parent
 * connection with no vertical component, turned following off, and left the
 * transcript at the bottom showing "jump to latest" for a list that had not
 * moved at all.
 *
 * @param availableY how far the nested scroll wants to move vertically.
 * @param ours true when the source is the app's own scroll or the app is driving
 *   one; those must never detach following.
 */
internal fun detachesFollow(availableY: Float, ours: Boolean): Boolean =
	availableY != 0f && !ours

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
	/** A message to reveal after opening from search or a notification. */
	focusMessageId: String? = null,
	onFocusConsumed: () -> Unit = {},
	onBack: () -> Unit,
	onOpenSettings: () -> Unit,
	onOpenFiles: () -> Unit = {},
	/** Open another conversation, created beside this one. */
	onOpenConversation: (String) -> Unit = {},
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
	val defaultTitle = stringResource(R.string.chat_title_fallback)
	// Read here rather than at the call site: the audio launcher's failure path
	// runs inside a `launch`, where `stringResource` is not available.
	val transcriptionFailedText = stringResource(R.string.chat_transcription_failed)
	val defaultModel = stringResource(R.string.chat_model_fallback)
	val defaultProvider = stringResource(R.string.chat_provider_fallback)
	val title by fieldOf(viewModel.state, defaultTitle) { it.title }
	val modelLabel by fieldOf(viewModel.state, defaultModel) { it.modelLabel }
	val displayTitle = title.ifBlank { defaultTitle }
	val displayModel = modelLabel.ifBlank { defaultModel }
	val providerName by fieldOf(viewModel.state, defaultProvider) { it.providerName }
	val currentProviderId by fieldOf(viewModel.state, "deepseek") { it.providerId }
	val videoStatus by fieldOf(viewModel.state, null) { it.videoStatus }
	val videoPath by fieldOf(viewModel.state, null) { it.videoPath }
	val videoError by fieldOf(viewModel.state, null) { it.videoError }
	val thinking by fieldOf(viewModel.state, false) { it.thinking }
	val reasoningEffort by fieldOf(viewModel.state, null) { it.reasoningEffort }
	val error by fieldOf(viewModel.state, null) { it.error }
	val errorAction by fieldOf(viewModel.state, null) { it.errorAction }
	val pendingApproval by fieldOf(viewModel.state, null) { it.pendingApproval }

	// The composer's own inputs. Separate reads for the same reason as the rest:
	// typing changes one of these and must not invalidate the transcript, and a
	// streamed token changes `isStreaming` at the start and end of a turn
	// without touching the text in the field.
	val input by fieldOf(viewModel.state, "") { it.input }
	val configured by fieldOf(viewModel.state, false) { it.configured }
	val pendingAttachments by fieldOf(viewModel.state, emptyList()) { it.pendingAttachments }
	val modelSupportsVision by fieldOf(viewModel.state, false) { it.modelSupportsVision }
	// The microphone is offered only when a transcription backend exists. A
	// row that would always fail is worse than no row; Settings is where the
	// backend is configured, and returning here re-runs this check.
	var voiceReady by remember { mutableStateOf(false) }
	LaunchedEffect(Unit) { voiceReady = viewModel.canTranscribe() }

	val listState = rememberLazyListState()
	val scope = rememberCoroutineScope()
	var showModelPicker by remember { mutableStateOf(false) }
	// Whether the context figures are open. Screen state rather than view model
	// state: it is about what this screen is showing, and a rotation that
	// closed it is the correct outcome.
	var showContextDetail by remember { mutableStateOf(false) }
	// A search hit stays highlighted long enough to be found by eye. The id is
	// local so a later recomposition does not need to keep the search route.
	// Deliberately not keyed on `focusMessageId`: consuming the focus sets that
	// argument back to null, and a keyed `remember` would recreate this state
	// as null and erase the highlight the moment it was applied.
	var highlightedMessageId by remember { mutableStateOf<String?>(null) }
	// One timer for every way a message can be revealed: a global search hit,
	// a turn jump, or an in-conversation match. The scroll effects set the id;
	// this effect owns how long the highlight lives. Keeping the delay out of
	// the scroll effects means a message arriving during the delay cannot
	// cancel the clear and leave a row highlighted forever.
	LaunchedEffect(highlightedMessageId) {
		val id = highlightedMessageId ?: return@LaunchedEffect
		delay(2400)
		if (highlightedMessageId == id) highlightedMessageId = null
	}

	// Named `ctx`, not `context`: the bare name collides with the Kotlin
	// stdlib's `context(...)` at every call site that has a lambda in scope, and
	// the compiler then reports "function invocation expected" rather than
	// anything about naming. The same trap caught the documents provider.
	val ctx = LocalContext.current
	var audioBusy by remember { mutableStateOf(false) }
	var audioTranscript by remember { mutableStateOf<String?>(null) }
	var audioError by remember { mutableStateOf<String?>(null) }

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
	val audioImporter = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			val name = queryImageName(ctx, uri)
			audioBusy = true
			audioTranscript = null
			audioError = null
			scope.launch {
				viewModel.transcribeAudioFile(name) {
					ctx.contentResolver.openInputStream(uri)
						?: error("could not open the selected audio")
				}.fold(
					onSuccess = { audioTranscript = it },
					onFailure = { audioError = it.message ?: transcriptionFailedText },
				)
				audioBusy = false
			}
		}
	}
	val notificationPermission = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestPermission(),
	) { /* The service still runs if denied; only the notice is hidden. */ }

	// Export is a plain-text/zip write through ACTION_CREATE_DOCUMENT. The
	// dialog choice is kept outside the launcher because the result arrives on
	// a later composition; reading the checkbox then would use whatever it
	// happened to be, not what the user chose.
	var showExportDialog by remember { mutableStateOf(false) }
	var exportIncludeTools by remember { mutableStateOf(true) }
	var pendingExportTools by remember { mutableStateOf(true) }
	// A zip of a full workspace can take seconds. Keep the two menu items
	// disabled until the write finishes and say that it started, so a second tap
	// does not start a second document and the screen is not silent.
	var exporting by remember { mutableStateOf(false) }
	val conversationExportedText = stringResource(R.string.export_conversation_done)
	val conversationExporter = rememberLauncherForActivityResult(
		ActivityResultContracts.CreateDocument("text/markdown"),
	) { uri ->
		if (uri != null) {
			exporting = true
			Toast.makeText(ctx, R.string.export_working, Toast.LENGTH_SHORT).show()
			viewModel.exportConversation(uri, pendingExportTools) { error ->
				exporting = false
				Toast.makeText(
					ctx,
					error?.let { ctx.getString(R.string.export_failed, it) }
						?: conversationExportedText,
					Toast.LENGTH_LONG,
				).show()
			}
		}
	}
	val workspaceExporter = rememberLauncherForActivityResult(
		ActivityResultContracts.CreateDocument("application/zip"),
	) { uri ->
		if (uri != null) {
			exporting = true
			Toast.makeText(ctx, R.string.export_working, Toast.LENGTH_SHORT).show()
			viewModel.exportWorkspace(uri) { result, error ->
				exporting = false
				val text = when {
					error != null -> ctx.getString(R.string.export_failed, error)
					result != null -> buildString {
						append(
							ctx.resources.getQuantityString(
								R.plurals.export_workspace_done,
								result.files,
								result.files,
							),
						)
						if (result.skipped > 0) {
							append('\n')
							append(
								ctx.resources.getQuantityString(
									R.plurals.export_workspace_skipped,
									result.skipped,
									result.skipped,
								),
							)
						}
					}
					else -> ctx.getString(
						R.string.export_failed,
						ctx.getString(R.string.export_error_unknown),
					)
				}
				Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
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
	val lastMessageId by fieldOf(viewModel.state, null) { it.messages.lastOrNull()?.id }
	val isStreaming by fieldOf(viewModel.state, false) { it.isStreaming }
	// The foreground service can keep working without this grant; asking for it
	// is about making the ongoing notice visible, not about correctness.
	LaunchedEffect(isStreaming) {
		if (isStreaming && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			ContextCompat.checkSelfPermission(
				ctx,
				Manifest.permission.POST_NOTIFICATIONS,
			) != PackageManager.PERMISSION_GRANTED
		) {
			notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
		}
	}
	val sshAllowed by fieldOf(viewModel.state, false) { it.sshAllowed }
	val sshToolEnabled by fieldOf(viewModel.state, false) { it.sshToolEnabled }
	val agentTools by fieldOf(viewModel.state, null) { it.agentTools }
	val conversationTools by fieldOf(viewModel.state, null) { it.conversationTools }
	val toolsKnown by fieldOf(viewModel.state, false) { it.toolsKnown }
	val toolPlan = remember(agentTools, conversationTools, sshToolEnabled) {
		conversationToolPlan(
			sshEnabled = sshToolEnabled,
			agentTools = agentTools,
			conversationTools = conversationTools,
			workspaceToolsEnabled = AppCapabilities.workspaceOk,
		)
	}
	val rollbackAvailable by fieldOf(viewModel.state, false) { it.rollbackAvailable }
	val rollbackBlockedReason by fieldOf(viewModel.state, null) { it.rollbackBlockedReason }
	val regenerationPoints by fieldOf(viewModel.state, emptyList()) { it.regenerationPoints }
	val compaction by fieldOf(viewModel.state, null) { it.compaction }
	val isCompacting by fieldOf(viewModel.state, false) { it.isCompacting }
	val snapshotStats by fieldOf(viewModel.state, SnapshotStats(0, 0)) { it.snapshotStats }
	val snapshotRetention by fieldOf(viewModel.state, 3) { it.snapshotRetention }
	val snapshotTurns by fieldOf(viewModel.state, emptyList<SnapshotTurn>()) { it.snapshotTurns }
	val wire by viewModel.wire.collectAsStateWithLifecycle(null)
	val toolRoundLimit by fieldOf(viewModel.state, 8) { it.toolRoundLimit }
	val pendingSshConsent by fieldOf(viewModel.state, null) { it.pendingSshConsent }
	val workspaceName by fieldOf(viewModel.state, "") { it.workspaceName }
	val workspaceId by fieldOf(viewModel.state, null) { it.workspaceId }
	val workspaceIsPermanent by fieldOf(viewModel.state, false) { it.workspaceIsPermanent }
	var showChatMenu by remember { mutableStateOf(false) }
	var showGenerateImage by remember { mutableStateOf(false) }
	var showDecision by remember { mutableStateOf(false) }
	var showSpeech by remember { mutableStateOf(false) }
	var showVideo by remember { mutableStateOf(false) }
	// In-conversation search is a full-screen dialog, not an inline bar. An
	// inline bar plus the IME left no transcript on a 640x360 landscape phone;
	// the dialog owns its own window and its own inset handling instead.
	var searchOpen by remember { mutableStateOf(false) }
	var searchQuery by remember { mutableStateOf("") }
	var jumpToMessageId by remember { mutableStateOf<String?>(null) }
	var showTurnPicker by remember { mutableStateOf(false) }
	var creatingConversation by remember { mutableStateOf(false) }
	fun closeSearch() {
		searchOpen = false
		searchQuery = ""
	}
	var rollbackTarget by remember { mutableStateOf<ChatMessageUi?>(null) }
	var rollbackFiles by remember { mutableStateOf<List<WorkspaceChange>>(emptyList()) }
	var showRewindDialog by remember { mutableStateOf(false) }
	// Captured when Rewind opens, while the transcript is in scope. The dialog
	// itself is outside the Scaffold content lambda and cannot see `messages`.
	var rewindEditTarget by remember { mutableStateOf<ChatMessageUi?>(null) }
	var rewindEditFiles by remember { mutableStateOf<List<WorkspaceChange>>(emptyList()) }
	var showCompactPreview by remember { mutableStateOf(false) }
	var pendingCompaction by remember { mutableStateOf<CompactionPlan?>(null) }
	var showCompactionSummary by remember { mutableStateOf(false) }
	var showSnapshotDialog by remember { mutableStateOf(false) }
	// Lowering the retention deletes checkpoints immediately, so the number is
	// staged here until the user confirms it.
	var pendingRetention by remember { mutableStateOf<Int?>(null) }
	var showWireDialog by remember { mutableStateOf(false) }
	LaunchedEffect(wire) {
		if (wire?.preview == true) showWireDialog = true
	}
	// Audit history is loaded when the dialogue opens, not kept in the state
	// flow: it is a read-only view of rows that only change while a turn runs,
	// and a Flow would republish it on every token.
	var showApprovalHistory by remember { mutableStateOf(false) }
	var approvalHistory by remember { mutableStateOf<List<ApprovalRecord>?>(null) }
	var approvalHistoryFailed by remember { mutableStateOf(false) }
	var showCapabilityDetails by remember { mutableStateOf(false) }
	LaunchedEffect(showApprovalHistory) {
		if (showApprovalHistory) {
			approvalHistory = null
			approvalHistoryFailed = false
			val loaded = viewModel.approvalHistory()
			approvalHistory = loaded.orEmpty()
			approvalHistoryFailed = loaded == null
		}
	}
	var turnWireMessages by remember { mutableStateOf<List<ChatMessageUi>?>(null) }
	var showToolLimitDialog by remember { mutableStateOf(false) }
	var showToolsDialog by remember { mutableStateOf(false) }
	var showRenameDialog by remember { mutableStateOf(false) }
	var renameInput by remember { mutableStateOf("") }
	var showSaveWorkspaceDialog by remember { mutableStateOf(false) }
	var workspaceNameInput by remember { mutableStateOf("") }

	// Reported upward so the app's single Back handler knows a dialog is up.
	// Both this and the approval gate are transient questions that Back should
	// answer by dismissing, not by navigating away from.
	val anyDialogOpen = searchOpen || showModelPicker || pendingApproval != null ||
		pendingSshConsent != null || rollbackTarget != null || showRewindDialog ||
		showCompactPreview || showCompactionSummary ||
		showSnapshotDialog || showToolLimitDialog || showRenameDialog || showWireDialog ||
		showApprovalHistory || showCapabilityDetails ||
		turnWireMessages != null || showToolsDialog || showTurnPicker ||
			showSaveWorkspaceDialog || showExportDialog
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

	// Height of the last visible row was once used as a follow trigger. It is
	// deliberately gone: during a programmatic scroll the last visible row
	// changes, so the key changes *because of* the scroll, cancelling and
	// restarting the effect. That is the visible "jump/skip" this code used to
	// produce. The observer below treats geometry as data, not as a key.


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
				// `UserInput` was the old name for a drag. Compose now reports
				// `Drag` / `Fling`; comparing only against `UserInput` left
				// `followLatest` true during a manual fling, so the layout
				// observer immediately pulled the list back to the bottom.
				// SideEffect is the only source that is ours.
				//
				// And only a *vertical* drag counts. A code block that is wider
				// than the screen scrolls sideways, and that drag reaches this
				// connection with `available.y == 0`: the transcript then
				// stopped following and showed "jump to latest" although the
				// list had not moved at all. Found by swiping a long fence on
				// the 360dp emulator.
				if (
					detachesFollow(
						availableY = available.y,
						ours = source == NestedScrollSource.SideEffect || programmaticScroll,
					)
				) {
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
	// One layout observer, not a LaunchedEffect keyed on geometry. The old keys
	// changed *because of* the programmatic scroll itself, so each scroll
	// cancelled and restarted the effect -- the visible jump/skip. This
	// collector is stable; `conflate` keeps only the newest layout while a
	// scroll is running, so it cannot cancel that scroll.
	LaunchedEffect(listState) {
		snapshotFlow {
			val info = listState.layoutInfo
			val last = info.visibleItemsInfo.lastOrNull()
			Triple(info.totalItemsCount, last?.index ?: -1, last?.let { it.offset + it.size } ?: 0)
		}
			.distinctUntilChanged()
			.conflate()
			.collect {
				// Let the row settle first; a token that is still streaming will
				// emit again and be handled on the next pass.
				delay(24)
				if (followLatest && !programmaticScroll && !listState.isScrollInProgress &&
					!atBottom
				) {
					val lastIndex = listState.layoutInfo.totalItemsCount - 1
					if (lastIndex >= 0) {
						programmaticScroll = true
						try {
							listState.scrollToLatest(lastIndex)
						} finally {
							programmaticScroll = false
						}
					}
				}
			}
	}
	// There is deliberately no `LaunchedEffect(atBottom) { followLatest = true }`
	// here. A fast fling passes through the bottom for a frame, which turned
	// following back on mid-gesture and let the next content tick yank the list
	// away from where the user had stopped. Reaching the bottom is recognised
	// once the scroll is no longer in progress, above.

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

	Scaffold(
		topBar = {
			Column {
				TopAppBar(
					title = {
						// Tapping the title opens the model controls. The title is
						// the only thing in the bar that is not already a button, and
						// putting the current model under it means the settings are
						// visible without being in the way -- which matters because
						// they change what an answer costs.
						Column(
							modifier = Modifier.clickable { showModelPicker = true },
							verticalArrangement = Arrangement.spacedBy(2.dp),
						) {
							Text(
								text = displayTitle,
								style = MaterialTheme.typography.titleMedium,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
							Row(
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
							) {
								StatusDot(MaterialTheme.colorScheme.primary, size = 5.dp)
								Text(
									text = buildString {
										append(providerName)
										append(" / ")
										append(displayModel)
										if (thinking) {
											append(stringResource(R.string.chat_thinking_suffix))
											reasoningEffort?.let { append(" $it") }
										}
									},
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							}
						}
					},
					navigationIcon = {
						IconButton(onClick = onBack) {
							Icon(
								Icons.AutoMirrored.Filled.ArrowBack,
								contentDescription = stringResource(R.string.common_back),
								modifier = Modifier.size(20.dp),
							)
						}
					},
					actions = {
						IconButton(
							onClick = {
								if (searchOpen) closeSearch() else searchOpen = true
							},
						) {
							Icon(
								Icons.Outlined.Search,
								contentDescription = stringResource(
									if (searchOpen) R.string.chat_search_close else R.string.chat_search,
								),
								modifier = Modifier.size(20.dp),
							)
						}
						IconButton(
							onClick = {
								if (!creatingConversation) {
									creatingConversation = true
									viewModel.createNewConversation { id ->
										creatingConversation = false
										if (id != null) onOpenConversation(id)
									}
								}
							},
							enabled = !creatingConversation,
						) {
							Icon(
								Icons.Outlined.Add,
								contentDescription = stringResource(R.string.chat_new_conversation),
								modifier = Modifier.size(20.dp),
							)
						}
						Box {
							IconButton(onClick = { showChatMenu = true }) {
								Icon(
									imageVector = Icons.Filled.MoreVert,
									contentDescription = stringResource(R.string.chat_more),
									modifier = Modifier.size(20.dp),
								)
							}
							DropdownMenu(
								expanded = showChatMenu,
								onDismissRequest = { showChatMenu = false },
							) {
								DropdownMenuItem(
									text = { PaidMenuLabel(stringResource(R.string.chat_transcribe_audio_file)) },
									leadingIcon = {
										Icon(
											Icons.Outlined.PlayArrow,
											contentDescription = null,
											modifier = Modifier.size(18.dp),
										)
									},
									onClick = {
										showChatMenu = false
										audioImporter.launch(arrayOf("audio/*"))
									},
								)
								DropdownMenuItem(
									text = { PaidMenuLabel(stringResource(R.string.chat_text_to_speech)) },
									leadingIcon = {
										Icon(
											Icons.Outlined.PlayArrow,
											contentDescription = null,
											modifier = Modifier.size(18.dp),
										)
									},
									onClick = {
										showChatMenu = false
										showSpeech = true
									},
								)
								DropdownMenuItem(
									text = { PaidMenuLabel(stringResource(R.string.chat_generate_video)) },
									leadingIcon = {
										Icon(
											Icons.Outlined.PlayArrow,
											contentDescription = null,
											modifier = Modifier.size(18.dp),
										)
									},
									onClick = {
										showChatMenu = false
										showVideo = true
									},
								)
								DropdownMenuItem(
									text = { PaidMenuLabel(stringResource(R.string.chat_decision)) },
									leadingIcon = {
										Icon(
											Icons.Outlined.DataObject,
											contentDescription = null,
											modifier = Modifier.size(18.dp),
										)
									},
									onClick = {
										showChatMenu = false
										showDecision = true
									},
								)
								DropdownMenuItem(
									text = { PaidMenuLabel(stringResource(R.string.chat_generate_image)) },
									leadingIcon = {
										Icon(
											Icons.Outlined.Image,
											contentDescription = null,
											modifier = Modifier.size(18.dp),
										)
									},
									onClick = {
										showChatMenu = false
										showGenerateImage = true
									},
								)
								DropdownMenuItem(
									text = { Text(stringResource(R.string.files_title)) },
									leadingIcon = {
										Icon(
											Icons.Outlined.FolderOpen,
											contentDescription = null,
											modifier = Modifier.size(18.dp),
										)
									},
									onClick = {
										showChatMenu = false
										onOpenFiles()
									},
								)
								DropdownMenuItem(
									text = { Text(stringResource(R.string.export_chat)) },
									enabled = !isStreaming && !exporting,
									onClick = {
										showChatMenu = false
										exportIncludeTools = true
										showExportDialog = true
									},
								)
								DropdownMenuItem(
									text = {
										Column {
											Text(stringResource(R.string.export_workspace_zip))
											Text(
												text = stringResource(R.string.export_workspace_zip_detail),
												style = MaterialTheme.typography.bodySmall,
												color = MaterialTheme.colorScheme.onSurfaceVariant,
											)
										}
									},
									enabled = !isStreaming && !exporting,
									onClick = {
										showChatMenu = false
										workspaceExporter.launch(
											exportFileName(
												workspaceName.ifBlank { displayTitle },
												"zip",
											),
										)
									},
								)
								DropdownMenuItem(
									text = { Text(stringResource(R.string.chat_provider_keys)) },
									leadingIcon = {
										Icon(
											Icons.Outlined.Key,
											contentDescription = null,
											modifier = Modifier.size(18.dp),
										)
									},
									onClick = {
										showChatMenu = false
										onOpenSettings()
									},
								)
								DropdownMenuItem(
									text = {
										Column {
											Text(stringResource(R.string.chat_preview_next))
											Text(
												text = stringResource(R.string.chat_preview_next_detail),
												style = MaterialTheme.typography.bodySmall,
												color = MaterialTheme.colorScheme.onSurfaceVariant,
											)
										}
									},
									onClick = {
										showChatMenu = false
										viewModel.previewNextRequest()
									},
								)
								DropdownMenuItem(
									text = {
										Column {
											Text(stringResource(R.string.chat_inspect_last))
											Text(
												text = if (wire == null) {
													stringResource(R.string.chat_no_request)
												} else {
													stringResource(R.string.chat_inspect_detail)
												},
												style = MaterialTheme.typography.bodySmall,
												color = MaterialTheme.colorScheme.onSurfaceVariant,
											)
										}
									},
									enabled = wire != null,
									onClick = {
										showChatMenu = false
										showWireDialog = true
									},
								)
								DropdownMenuItem(
									text = {
										Column {
											Text(stringResource(R.string.chat_snapshots))
											Text(
												text = pluralStringResource(
													R.plurals.chat_snapshot_menu,
													snapshotStats.turns,
													snapshotStats.turns,
													formatBytes(snapshotStats.bytes),
												),
												style = MaterialTheme.typography.bodySmall,
												color = MaterialTheme.colorScheme.onSurfaceVariant,
											)
										}
									},
									onClick = {
										showChatMenu = false
										showSnapshotDialog = true
									},
								)
								DropdownMenuItem(
									text = { Text(stringResource(R.string.chat_approval_history)) },
									leadingIcon = {
										Icon(
											Icons.Outlined.History,
											contentDescription = null,
											modifier = Modifier.size(18.dp),
										)
									},
									onClick = {
										showChatMenu = false
										showApprovalHistory = true
									},
								)
								DropdownMenuItem(
									text = {
										Column {
											Text(stringResource(R.string.chat_tool_rounds))
											Text(
												text = stringResource(
													R.string.chat_rounds_before_stop,
													toolRoundLimit,
												),
												style = MaterialTheme.typography.bodySmall,
												color = MaterialTheme.colorScheme.onSurfaceVariant,
											)
										}
									},
									onClick = {
										showChatMenu = false
										showToolLimitDialog = true
									},
								)
								DropdownMenuItem(
									text = {
										Column {
											Text(stringResource(R.string.chat_tools_title))
											Text(
												text = stringResource(
													R.string.chat_tools_summary,
													toolPlan.selected.size,
													toolPlan.available.size,
												),
												style = MaterialTheme.typography.bodySmall,
												color = MaterialTheme.colorScheme.onSurfaceVariant,
											)
										}
									},
									enabled = toolsKnown && !isStreaming,
									onClick = {
										showChatMenu = false
										showToolsDialog = true
									},
								)
								DropdownMenuItem(
									text = { Text(stringResource(R.string.chat_rename)) },
									onClick = {
										showChatMenu = false
										renameInput = displayTitle
										showRenameDialog = true
									},
								)
								if (!workspaceIsPermanent) {
									DropdownMenuItem(
										text = {
											Column {
												Text(stringResource(R.string.chat_save_workspace))
												Text(
													text = stringResource(R.string.chat_save_workspace_detail),
													style = MaterialTheme.typography.bodySmall,
													color = MaterialTheme.colorScheme.onSurfaceVariant,
												)
											}
										},
										onClick = {
											showChatMenu = false
											workspaceNameInput = workspaceName.ifBlank { displayTitle }
											showSaveWorkspaceDialog = true
										},
									)
								}
								if (sshToolEnabled) {
									DropdownMenuItem(
										text = { Text(stringResource(R.string.chat_revoke_ssh)) },
										enabled = sshAllowed,
										onClick = {
											showChatMenu = false
											viewModel.revokeSshAccess()
										},
									)
								}
							}
						}
					},
					colors = TopAppBarDefaults.topAppBarColors(
						containerColor = MaterialTheme.colorScheme.background,
						titleContentColor = MaterialTheme.colorScheme.onBackground,
						navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
						actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
					),
				)
				Hairline()
			}
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
				// Do not consume safeDrawing before this. `safeDrawing` includes
				// the IME inset, and consuming it here left `.imePadding()` with
				// nothing to measure -- the composer stayed under the keyboard.
				.imePadding(),
		) {
			// The failure is process-wide and set before the first frame, so it
			// is read here rather than threaded through every screen. It sits
			// above the transcript because it changes what the transcript can
			// be asked to do.
			AppCapabilities.workspaceCheck?.let { report ->
				if (!report.ok) {
					WorkspaceCapabilityBanner(
						onShowDetails = { showCapabilityDetails = true },
					)
				}
			}
			// Read inside this lambda, so a new message or a streamed token
			// invalidates the list rather than the whole screen. See the note
			// above the state reads.
			val messages by fieldOf(viewModel.state, emptyList()) { it.messages }
			// A turn whose last saved message is not a finished answer can be
			// resumed. Computed from the same list the transcript draws, so the
			// banner cannot appear over a turn that is already complete.
			val interrupted = remember(messages, isStreaming) {
				isInterruptedTurn(messages, isStreaming)
			}
			// Turn boundaries and their summed usage. Computed from the same
			// list the transcript renders, so a footer cannot describe a turn
			// that is no longer on the active path.
			val turns = remember(messages) { turnSummariesOf(messages) }
			// The user rows are the navigation targets. They are a separate
			// grouping from `turns`: a jump lands on the question, not on the
			// footer of the answer it produced.
			val userTurns = remember(messages) { userTurnsOf(messages) }
			// The two banners above the transcript occupy list items of their
			// own; every index taken from `messages` has to add them back.
			val leadingItems = (if (compaction != null) 1 else 0) + (if (isCompacting) 1 else 0)
			val currentTurnIndex by remember(userTurns, leadingItems) {
				derivedStateOf {
					if (userTurns.isEmpty()) {
						0
					} else {
						val firstMessageIndex =
							(listState.firstVisibleItemIndex - leadingItems).coerceAtLeast(0)
						userTurns
							.indexOfLast { it.messageIndex <= firstMessageIndex }
							.coerceAtLeast(0)
					}
				}
			}
			// Search is computed only while the dialog is open. Doing it on every
			// streamed token would walk the whole transcript several times a
			// second for a field the user is not using.
			val searchMatches = remember(messages, searchQuery, searchOpen) {
				if (searchOpen) searchConversation(messages, searchQuery) else emptyList()
			}
			// Reveal a message opened from search, a turn jump, or a match.
			// Wait for the list to measure before asking for an index; on a cold
			// open `totalItemsCount` is 0 for a frame, and `scrollToItem` would
			// silently do nothing.
			LaunchedEffect(jumpToMessageId, messages.size) {
				val targetId = jumpToMessageId ?: return@LaunchedEffect
				if (messages.isEmpty()) return@LaunchedEffect
				val index = messages.indexOfFirst { it.id == targetId }
				if (index < 0) {
					jumpToMessageId = null
					return@LaunchedEffect
				}
				var attempts = 0
				while (listState.layoutInfo.totalItemsCount == 0 && attempts++ < 40) {
					delay(50)
				}
				if (listState.layoutInfo.totalItemsCount > index + leadingItems) {
					// An older message is a deliberate move away from the
					// newest reply; do not let the follow observer pull it
					// back to the bottom.
					followLatest = false
					programmaticScroll = true
					try {
						listState.scrollToItem(index + leadingItems)
					} finally {
						programmaticScroll = false
					}
					highlightedMessageId = targetId
					jumpToMessageId = null
				}
			}
			// Reveal a message opened from the home screen's global search or a
			// notification. Kept separate from the local jump above because this
			// one has to report consumption to its owner, while a local jump is
			// finished when it clears itself.
			LaunchedEffect(focusMessageId, messages.size) {
				val targetId = focusMessageId ?: return@LaunchedEffect
				// The transcript starts empty while Room loads. Do not consume the
				// focus yet: `messages.size` changes when the rows arrive and this
				// effect runs again.
				if (messages.isEmpty()) return@LaunchedEffect
				val index = messages.indexOfFirst { it.id == targetId }
				if (index < 0) {
					onFocusConsumed()
					return@LaunchedEffect
				}
				var attempts = 0
				while (listState.layoutInfo.totalItemsCount == 0 && attempts++ < 40) {
					delay(50)
				}
				if (listState.layoutInfo.totalItemsCount > index + leadingItems) {
					followLatest = false
					programmaticScroll = true
					try {
						listState.scrollToItem(index + leadingItems)
					} finally {
						programmaticScroll = false
					}
					highlightedMessageId = targetId
					onFocusConsumed()
				}
			}
			// Structured so Compose can skip it: two stable fields beat a
			// `ContextUsage` read through a state object that changes for
			// unrelated reasons.
			val usage by fieldOf(viewModel.state, ContextUsage(null, null)) {
				ContextUsage(it.context.usedTokens, it.context.contextWindow)
			}

			// The turn index doubles as the jump control. It is a strip above
			// the transcript rather than an overlay: a chip pinned over the
			// top-right corner would sit on the first user bubble, which is
			// exactly where the right-aligned bubbles are.
			if (userTurns.size > 1 && !searchOpen) {
				Box(
					modifier = Modifier.fillMaxWidth(),
					contentAlignment = Alignment.Center,
				) {
					Box(
						modifier = Modifier
							.widthIn(max = Layout.ChatMeasure)
							.fillMaxWidth(),
					) {
						AssistChip(
							onClick = { showTurnPicker = true },
							label = {
								Text(
									stringResource(
										R.string.chat_turn_position,
										currentTurnIndex + 1,
										userTurns.size,
									),
								)
							},
							leadingIcon = {
								Icon(
									Icons.AutoMirrored.Outlined.List,
									contentDescription = null,
									modifier = Modifier.size(16.dp),
								)
							},
							modifier = Modifier
								.align(Alignment.CenterEnd)
								.padding(end = comfortableGutter(), bottom = 4.dp),
						)
					}
				}
			}

			// `fillMaxWidth` is what makes the centring real. The Box used to
			// wrap its 900dp child, and a Column places a wrap-content child at
			// the start, so on a 1280dp tablet the transcript sat against the
			// left edge while the composer below it was centred.
			Box(
				modifier = Modifier.weight(1f).fillMaxWidth(),
				contentAlignment = Alignment.TopCenter,
			) {
				if (messages.isEmpty()) {
					WorkbenchGrid(Modifier.fillMaxSize())
				}
				LazyColumn(
					state = listState,
					modifier = Modifier
						.widthIn(max = Layout.ChatMeasure)
						.fillMaxSize()
						.nestedScroll(followConnection),
					contentPadding = PaddingValues(
						horizontal = comfortableGutter(),
						vertical = 12.dp,
					),
					verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
				) {
					compaction?.let { activeCompaction ->
						item(key = "compaction") {
							CompactionBanner(
								compaction = activeCompaction,
								onView = { showCompactionSummary = true },
								onClear = viewModel::clearCompaction,
							)
						}
					}
					if (isCompacting) {
						item(key = "compacting") {
							Text(
								text = stringResource(R.string.chat_summarizing),
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.padding(horizontal = 2.dp),
							)
						}
					}
					if (messages.isEmpty()) {
						item(key = "welcome") {
							ChatWelcome(
								providerName = providerName,
								modelLabel = displayModel,
								workspaceName = if (workspaceIsPermanent) {
									workspaceName.ifBlank {
										stringResource(R.string.chat_permanent_workspace)
									}
								} else {
									stringResource(R.string.chat_temporary_workspace)
								},
								configured = configured,
								onEnableDemo = viewModel::enableDemoMode,
								onOpenFiles = onOpenFiles,
								onOpenSettings = onOpenSettings,
								modifier = Modifier
									.widthIn(max = 760.dp)
									.fillMaxWidth()
									.padding(top = if (currentWindowFrame().isCompact) 20.dp else 48.dp),
							)
						}
					}
					items(messages, key = { it.id }) { message ->
						val isLastAssistant = !message.fromUser && !message.isToolResult &&
							message.id == lastAssistantId
						val isLastUser = message.fromUser && message.id == lastUserId
						// The footer belongs to the turn, not to the reply that
						// happens to be last: `turns` is keyed by the final
						// assistant row only, so intermediate tool rounds get
						// null and show no usage or wire button.
						val turn = turns[message.id]
						// Rewind is one control for every restart point in the
						// turn. It sits on the newest answer because that is where
						// the deepest cached prefix ends; a user row is only the
						// fallback while no answer exists yet.
						val showRewind = !isStreaming && (
							isLastAssistant ||
								(isLastUser && lastAssistantId == null && rollbackAvailable)
							)
						MessageBubble(
							message = message,
							highlighted = message.id == highlightedMessageId,
							// The strip above the composer already shows this
							// exact failure with Retry / Settings / Dismiss. The
							// bubble keeps it only after the strip is dismissed,
							// where it is the transcript's own record.
							hideError = error != null && message.error == error,
							isLatestAssistant = isLastAssistant,
							isStreaming = isStreaming,
							showRewind = showRewind,
							turnUsage = turn?.usage,
							isTurnEnd = turn?.complete == true,
							onShowTurnWire = {
								// Sliced here, from the transcript this row came
								// from: the summary carries indices, not a copy.
								turn?.let { turnWireMessages = it.messagesIn(messages) }
							},
							onShowRewind = {
								val target = messages.lastOrNull {
									it.fromUser && it.id == lastUserId
								}
								rewindEditTarget = target
								rewindEditFiles = if (target == null) {
									emptyList()
								} else {
									messages
										.dropWhile { it.id != target.id }
										.lastOrNull { !it.fromUser && !it.isToolResult }
										?.changedFiles
										.orEmpty()
								}
								showRewindDialog = true
							},
							onRetry = viewModel::retryLastTurn,
							onUseSuggestion = viewModel::useSuggestion,
							// Both ids go up: the alternative being switched to is
							// not on the active path, so the view model cannot find
							// its parent by searching the transcript.
							onSelectBranch = { parentId, childId ->
								if (parentId != null) viewModel.selectBranch(parentId, childId)
							},
						)
					}
				}

				// Show the jump control only when following has actually been
				// released by user input, not whenever `atBottom` is momentarily
				// false. During a stream the growing last row moves below the
				// viewport between automatic scrolls, so `atBottom` alternates and
				// the button flickered on every token. `followLatest` is the
				// intent; it stays true across content growth.
				if (!followLatest && messages.isNotEmpty() && !searchOpen) {
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
							contentDescription = stringResource(R.string.chat_jump_latest),
						)
					}
				}
			}

			if (showTurnPicker) {
				WideDialog(
					onDismissRequest = { showTurnPicker = false },
					title = stringResource(R.string.chat_turns),
					dismissLabel = stringResource(R.string.chat_close),
					maxBodyHeight = 420.dp,
				) {
					userTurns.forEachIndexed { index, turn ->
						val current = index == currentTurnIndex
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.background(
									if (current) {
										MaterialTheme.colorScheme.surfaceVariant
									} else {
										MaterialTheme.colorScheme.surface
									},
								)
								.clickable {
									showTurnPicker = false
									jumpToMessageId = turn.messageId
								}
								.padding(horizontal = 4.dp, vertical = 9.dp),
							verticalAlignment = Alignment.Top,
						) {
							Text(
								text = "${index + 1}",
								style = MaterialTheme.typography.labelMedium,
								fontFamily = FontFamily.Monospace,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.width(30.dp),
							)
							Column(modifier = Modifier.weight(1f)) {
								Text(
									text = turn.preview.ifBlank {
										stringResource(R.string.chat_turn_label, index + 1)
									},
									style = MaterialTheme.typography.bodyMedium,
									maxLines = 2,
									overflow = TextOverflow.Ellipsis,
								)
								Text(
									text = stringResource(R.string.chat_turn_label, index + 1),
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
						}
						if (index != userTurns.lastIndex) Hairline()
					}
				}
			}

			if (searchOpen) {
				ChatSearchDialog(
					query = searchQuery,
					onQueryChange = { searchQuery = it },
					matches = searchMatches,
					onSelect = { match ->
						closeSearch()
						jumpToMessageId = match.messageId
					},
					onDismiss = { closeSearch() },
				)
			}

			// The bottom strip, centred on the same measure as the bubbles
			// above it. Both children fill the cap and the column centres them.
			Column(
				modifier = Modifier.fillMaxWidth(),
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				// Demo mode is otherwise buried in Settings. The welcome panel
				// already offers it on an empty transcript; this strip is for a
				// conversation that has messages and still has no key.
				if (!configured && messages.isNotEmpty()) {
					TextButton(
						onClick = viewModel::enableDemoMode,
						modifier = Modifier
							.widthIn(max = Layout.ReadableMeasure)
							.fillMaxWidth()
							.padding(horizontal = comfortableGutter()),
					) {
						Text(stringResource(R.string.chat_try_demo_strip))
					}
				}

				error?.let { message ->
					Column(
						modifier = Modifier
							.widthIn(max = Layout.ChatMeasure)
							.fillMaxWidth()
							.padding(horizontal = comfortableGutter(), vertical = 4.dp),
					) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Text(
								text = message,
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.error,
								modifier = Modifier.weight(1f),
							)
							TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.chat_dismiss)) }
						}
						Row(
							horizontalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed),
							verticalAlignment = Alignment.CenterVertically,
						) {
							when (errorAction) {
								ChatErrorAction.Retry -> TextButton(
									onClick = viewModel::retryLastTurn,
								) { Text(stringResource(R.string.chat_retry)) }

								ChatErrorAction.CompactAndRetry -> TextButton(
									onClick = viewModel::compactAndRetry,
								) { Text(stringResource(R.string.chat_compact_retry)) }

								ChatErrorAction.OpenProviderSettings -> TextButton(
									onClick = {
										viewModel.clearError()
										onOpenSettings()
									},
								) { Text(stringResource(R.string.chat_provider_settings)) }

								null -> Unit
							}
							TextButton(
								onClick = {
									val clipboard = ctx.getSystemService(
										ClipboardManager::class.java,
									)
									clipboard.setPrimaryClip(
										ClipData.newPlainText(
											ctx.getString(R.string.chat_clip_error),
											message,
										),
									)
								},
							) { Text(stringResource(R.string.chat_copy_details)) }
						}
					}
				}

				// Search replaces the composer rather than sitting above it.
				// With the IME open a two-row search bar, the turn strip, the
				// composer and the keyboard left no transcript on a 731dp-tall
				// phone; the matches were searchable and invisible.
				if (!searchOpen) {
					if (interrupted && configured) {
						ContinueTurnBanner(
							onContinue = {
								// Resuming is a request to see the new part, like
								// sending; re-arm following before it is laid out.
								followLatest = true
								viewModel.continueInterruptedTurn()
							},
						)
					}
					Composer(
					value = input,
					onValueChange = viewModel::onInputChange,
					onSend = {
						// Sending is an explicit request to show the new turn.
						// If the reader had scrolled up, this re-arms following
						// before the new message is laid out.
						followLatest = true
						viewModel.send()
					},
					onStop = viewModel::stop,
					isStreaming = isStreaming,
					enabled = configured,
					attachments = pendingAttachments,
					canAttachImages = modelSupportsVision,
					onAttach = { importer.launch(arrayOf("image/*")) },
					onRemoveAttachment = viewModel::removeAttachment,
					voiceEnabled = voiceReady,
					onTranscribeVoice = viewModel::transcribeVoice,
					onVoiceText = { text ->
						viewModel.onInputChange(
							if (input.isBlank()) text else input.trimEnd() + " " + text,
						)
					},
					context = usage,
					showContextDetail = showContextDetail,
					onToggleContextDetail = { showContextDetail = !showContextDetail },
					compaction = compaction,
					isCompacting = isCompacting,
					onCompact = {
						// The first compaction keeps two turns for continuity;
						// a second pass keeps one, which is the escape hatch when
						// the recent turns are themselves huge.
						pendingCompaction = planContextCompaction(
							messages,
							keepTurns = if (compaction == null) 2 else 1,
						)
						showCompactPreview = true
					},
					onViewCompaction = { showCompactionSummary = true },
					onClearCompaction = viewModel::clearCompaction,
					// ## The modifier order is load-bearing
					//
					// `fillMaxWidth()` is greedy: it takes the whole incoming
					// maximum. Put it *before* `widthIn` and the cap never
					// binds -- the field stretches to 2208px on a 1280dp tablet
					// while the messages above it stop at 1232. Constraining
					// first and filling second is what makes the maximum mean
					// anything.
					modifier = Modifier
						.widthIn(max = Layout.ChatMeasure)
						.fillMaxWidth(),
					)
				}
			}
		}
	}

	// The model and reasoning controls, for this conversation only.
	//
	// Opened from the title rather than given a button: it is a setting that is
	// looked at occasionally and matters constantly, and a bar with Files, Key
	// and Model in it is a bar with no room for the conversation's name.
	if (showModelPicker) {
		val chosenModel by fieldOf(viewModel.state, null) { it.model }
		val resolvedModel by fieldOf(viewModel.state, null) { it.effectiveModel }

		ModelPickerDialog(
			chat = viewModel,
			currentModel = chosenModel,
			currentProviderId = currentProviderId,
			effectiveModel = resolvedModel,
			currentEffort = reasoningEffort,
			currentThinking = thinking,
			onDismiss = { showModelPicker = false },
		)
	}

	if (audioBusy || audioTranscript != null || audioError != null) {
		AlertDialog(
			onDismissRequest = {
				if (!audioBusy) {
					audioTranscript = null
					audioError = null
				}
			},
			title = { Text(stringResource(R.string.chat_transcription_title)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					if (audioBusy) {
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
						) {
							CircularProgressIndicator(strokeWidth = 2.dp)
							Text(stringResource(R.string.chat_transcribing))
						}
					}
					audioTranscript?.let { Text(it) }
					audioError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
				}
			},
			confirmButton = {
				TextButton(
					enabled = !audioBusy,
					onClick = {
						audioTranscript = null
						audioError = null
					},
				) { Text(stringResource(R.string.common_cancel)) }
			},
		)
	}

	if (showVideo) {
		VideoDialog(
			workspaceId = workspaceId,
			status = videoStatus,
			path = videoPath,
			error = videoError,
			onStart = { prompt, resolution, ratio, duration ->
				viewModel.startVideo(prompt, resolution, ratio, duration)
			},
			onDismiss = { showVideo = false },
		)
	}

	if (showSpeech) {
		SpeechDialog(
			workspaceId = workspaceId,
			onSpeak = { text -> viewModel.synthesize(text) },
			onTranscribe = { path -> viewModel.transcribe(path) },
			onDismiss = { showSpeech = false },
		)
	}

	if (showDecision) {
		DecisionDialog(
			onDecide = { text -> viewModel.decide(text) },
			onDecideCustom = { text, questions -> viewModel.decideCustom(text, questions) },
			onDismiss = { showDecision = false },
		)
	}

	if (showGenerateImage) {
		GenerateImageDialog(
			workspaceId = workspaceId,
			onGenerate = { prompt, size -> viewModel.generateImage(prompt, size) },
			onDismiss = { showGenerateImage = false },
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
	pendingApproval?.let { pending ->
		// The details are model-written JSON and can be arbitrarily long: a
		// `write_file` call carries a whole file in `content`. Left unbounded
		// they pushed the two buttons off the screen, so the one dialog where
		// reading carefully is the whole point became the one where "Allow"
		// could not be reached. Bounded and scrolled instead, with the bound
		// tied to the window so a landscape phone does not spend its entire
		// height on the arguments.
		val detailsLimit = (currentWindowFrame().heightDp * 0.4f).dp
		AlertDialog(
			onDismissRequest = viewModel::declinePendingCall,
			title = { Text(stringResource(R.string.chat_allow_title)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.InMessage)) {
					Text(pending.summary, style = MaterialTheme.typography.bodyLarge)
					ToolCatalog.all.firstOrNull { it.name == pending.call.displayName }?.let { entry ->
						Row(horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage)) {
							if (entry.paid) PaidBadge()
							if (entry.remote) RemoteBadge()
							if (entry.mutates) RewindBadge(canRewind = entry.reversible)
						}
					}
					pending.rule?.let { rule ->
						Text(
							text = stringResource(R.string.chat_allow_rule, rule),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					Text(
						text = stringResource(
							R.string.chat_allow_timeout,
							ChatViewModel.APPROVAL_TIMEOUT_MINUTES,
						),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					if (!pending.details.isNullOrBlank() || pending.call.argumentsJson.isNotBlank()) {
						Text(
							text = pending.details ?: pending.call.argumentsJson,
							style = MaterialTheme.typography.bodyMedium,
							fontFamily = FontFamily.Monospace,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier
								.fillMaxWidth()
								.heightIn(max = detailsLimit)
								.background(
									MaterialTheme.colorScheme.surfaceVariant,
									RoundedCornerShape(8.dp),
								)
								.padding(10.dp)
								.verticalScroll(rememberScrollState()),
						)
					}
				}
			},
			confirmButton = {
				TextButton(onClick = viewModel::approvePendingCall) { Text(stringResource(R.string.chat_allow)) }
			},
			dismissButton = {
				TextButton(onClick = viewModel::declinePendingCall) { Text(stringResource(R.string.chat_decline)) }
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

	if (showApprovalHistory) {
		ApprovalHistoryDialog(
			records = approvalHistory,
			failed = approvalHistoryFailed,
			onDismiss = { showApprovalHistory = false },
		)
	}

	if (showCapabilityDetails) {
		AppCapabilities.workspaceCheck?.let { report ->
			WorkspaceSelfCheckDialog(
				report = report,
				onDismiss = { showCapabilityDetails = false },
			)
		}
	}

	rollbackTarget?.let { target ->
		AlertDialog(
			onDismissRequest = { rollbackTarget = null },
			title = { Text(stringResource(R.string.chat_rewind_confirm_title)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(stringResource(R.string.chat_rewind_confirm_body))
					Text(
						text = stringResource(R.string.chat_rewind_restore_body),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					if (rollbackFiles.isEmpty()) {
						Text(
							text = stringResource(R.string.chat_no_files_changed),
							style = MaterialTheme.typography.bodySmall,
						)
					} else {
						Text(
							stringResource(R.string.chat_files_restored),
							style = MaterialTheme.typography.labelLarge,
						)
						Column(
							modifier = Modifier
								.heightIn(max = 220.dp)
								.verticalScroll(rememberScrollState()),
							verticalArrangement = Arrangement.spacedBy(2.dp),
						) {
							rollbackFiles.forEach { change ->
								Text(
									text = "${changeMark(change.kind)} ${change.path}",
									style = MaterialTheme.typography.bodySmall,
									fontFamily = FontFamily.Monospace,
								)
							}
						}
					}
				}
			},
			confirmButton = {
				TextButton(
					onClick = {
						rollbackTarget = null
						viewModel.rollbackTo(target.id)
					},
				) { Text(stringResource(R.string.chat_rewind)) }
			},
			dismissButton = {
				TextButton(
					onClick = {
						rollbackTarget = null
						rollbackFiles = emptyList()
					},
				) { Text(stringResource(R.string.common_cancel)) }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	if (showRewindDialog) {
		RewindDialog(
			showEdit = lastUserId != null,
			canEdit = rollbackAvailable,
			editBlockedReason = rollbackBlockedReason,
			canAnswerAgain = lastAssistantId != null,
			points = regenerationPoints,
			onDismiss = { showRewindDialog = false },
			onEdit = {
				val target = rewindEditTarget
				if (target != null) {
					showRewindDialog = false
					rollbackTarget = target
					rollbackFiles = rewindEditFiles
				}
			},
			onAnswerAgain = {
				showRewindDialog = false
				viewModel.regenerate()
			},
			onPoint = { point ->
				showRewindDialog = false
				viewModel.regenerateFrom(point)
			},
		)
	}

	if (showCompactPreview) {
		val plan = pendingCompaction
		AlertDialog(
			onDismissRequest = { showCompactPreview = false },
			title = {
				Text(
					if (plan == null) {
						stringResource(R.string.chat_compact_none_title)
					} else {
						stringResource(R.string.chat_compact_title)
					},
				)
			},
			text = {
				if (plan == null) {
					Text(
						text = stringResource(R.string.chat_compact_needs),
					)
				} else {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						Text(
							text = stringResource(
								R.string.chat_compact_plan,
								plan.omittedMessages,
								plan.omittedTurns,
							),
						)
						Text(
							text = stringResource(R.string.chat_compact_kept, plan.keptTurns),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						Text(
							text = stringResource(R.string.chat_compact_cost),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			},
			confirmButton = {
				if (plan != null) {
					TextButton(
						onClick = {
							showCompactPreview = false
							viewModel.compact(plan.keptTurns)
						},
					) { Text(stringResource(R.string.chat_compact)) }
				}
			},
			dismissButton = {
				TextButton(onClick = { showCompactPreview = false }) { Text(stringResource(R.string.common_cancel)) }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	compaction?.let { active ->
		if (showCompactionSummary) {
			AlertDialog(
				onDismissRequest = { showCompactionSummary = false },
				title = { Text(stringResource(R.string.chat_summary_title)) },
				text = {
					Column(
						modifier = Modifier
							.heightIn(max = 420.dp)
							.verticalScroll(rememberScrollState()),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						Text(
						text = stringResource(R.string.chat_summary_body, active.omittedMessages),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						Text(active.summary)
					}
				},
				confirmButton = {
					TextButton(onClick = { showCompactionSummary = false }) { Text(stringResource(R.string.chat_close)) }
				},
				dismissButton = {
					TextButton(
						onClick = {
							showCompactionSummary = false
							viewModel.clearCompaction()
						},
					) { Text(stringResource(R.string.chat_use_full_history)) }
				},
				containerColor = MaterialTheme.colorScheme.surface,
				titleContentColor = MaterialTheme.colorScheme.onSurface,
				textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}

	if (showSnapshotDialog) {
		AlertDialog(
			onDismissRequest = { showSnapshotDialog = false },
			title = { Text(stringResource(R.string.chat_snapshots)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages)) {
					Text(
						if (snapshotStats.turns == 0) {
							stringResource(R.string.chat_snapshots_empty)
						} else {
							pluralStringResource(
								R.plurals.chat_snapshots_stored,
								snapshotStats.turns,
								snapshotStats.turns,
								formatBytes(snapshotStats.bytes),
							)
						},
					)
					Text(
						text = stringResource(R.string.chat_snapshots_body),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					if (snapshotTurns.isNotEmpty()) {
						Column(
							modifier = Modifier
								.heightIn(max = 240.dp)
								.verticalScroll(rememberScrollState()),
							verticalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
						) {
							snapshotTurns.forEachIndexed { index, turn ->
								Column {
									Text(
										text = stringResource(
											R.string.chat_snapshot_prefix,
											snapshotTurns.size - index,
										) + pluralStringResource(
											R.plurals.chat_snapshot_checkpoints,
											turn.checkpoints,
											turn.checkpoints,
										),
										style = MaterialTheme.typography.labelLarge,
									)
									Text(
										text = "${formatSnapshotTime(turn.createdAt)} · " +
											formatBytes(turn.bytes),
										style = MaterialTheme.typography.bodySmall,
										color = MaterialTheme.colorScheme.onSurfaceVariant,
									)
								}
							}
						}
					}
					Text(
						text = stringResource(R.string.chat_snapshot_keep_label),
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					// Chips carry `selected` semantics, so the current value is
					// no longer communicated by colour alone. Lowering the value
					// stages a confirmation because pruning is immediate.
					FlowRowCompat {
						listOf(1, 3, 5, 10).forEach { turns ->
							FilterChip(
								selected = turns == snapshotRetention,
								onClick = {
									if (turns < snapshotRetention) {
										pendingRetention = turns
									} else {
										viewModel.setSnapshotRetention(turns)
									}
								},
								label = { Text("$turns") },
							)
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { showSnapshotDialog = false }) { Text(stringResource(R.string.chat_close)) }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	pendingRetention?.let { keep ->
		AlertDialog(
			onDismissRequest = { pendingRetention = null },
			title = { Text(stringResource(R.string.chat_snapshot_keep_title)) },
			text = { Text(stringResource(R.string.chat_snapshot_keep_body)) },
			confirmButton = {
				TextButton(
					onClick = {
						pendingRetention = null
						viewModel.setSnapshotRetention(keep)
					},
				) { Text(stringResource(R.string.chat_snapshot_keep_confirm, keep)) }
			},
			dismissButton = {
				TextButton(onClick = { pendingRetention = null }) {
					Text(stringResource(R.string.common_cancel))
				}
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	if (showWireDialog) {
		wire?.let { snapshot ->
			WireRequestDialog(
				snapshot = snapshot,
				onDismiss = { showWireDialog = false },
			)
		}
	}

	turnWireMessages?.let { messages ->
		WireTurnDialog(
			messages = messages,
			onDismiss = { turnWireMessages = null },
		)
	}

	if (showToolLimitDialog) {
		AlertDialog(
			onDismissRequest = { showToolLimitDialog = false },
			title = { Text(stringResource(R.string.chat_tool_rounds)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages)) {
					Text(
						text = stringResource(R.string.chat_tool_limit_body),
					)
					Text(
						text = stringResource(R.string.chat_tool_limit_current, toolRoundLimit),
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.primary,
					)
					listOf(4, 8, 16, 24, 32, 64).chunked(3).forEach { row ->
						Row(horizontalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed)) {
							row.forEach { rounds ->
								TextButton(
									onClick = {
										viewModel.setToolRoundLimit(rounds)
										showToolLimitDialog = false
									},
									contentPadding = PaddingValues(horizontal = 10.dp),
								) {
									Text(
										text = "$rounds",
										style = MaterialTheme.typography.labelLarge,
										color = if (rounds == toolRoundLimit) {
											MaterialTheme.colorScheme.primary
										} else {
											MaterialTheme.colorScheme.onSurfaceVariant
										},
									)
								}
							}
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { showToolLimitDialog = false }) { Text(stringResource(R.string.chat_close)) }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	if (showToolsDialog) {
		ConversationToolsDialog(
			available = toolPlan.available,
			selected = toolPlan.selected,
			streaming = isStreaming,
			onApply = { checked ->
				val merged = mergeConversationTools(
					current = conversationTools,
					available = toolPlan.available,
					checked = checked,
				)
				viewModel.setConversationTools(merged)
				showToolsDialog = false
			},
			onDismiss = { showToolsDialog = false },
		)
	}

	if (showRenameDialog) {
		AlertDialog(
			onDismissRequest = { showRenameDialog = false },
			title = { Text(stringResource(R.string.chat_rename)) },
			text = {
				OutlinedTextField(
					value = renameInput,
					onValueChange = { renameInput = it },
					label = { Text(stringResource(R.string.chat_title_label)) },
					singleLine = true,
				)
			},
			confirmButton = {
				TextButton(
					onClick = {
						viewModel.renameConversation(renameInput)
						showRenameDialog = false
					},
					enabled = renameInput.isNotBlank(),
				) { Text(stringResource(R.string.common_save)) }
			},
			dismissButton = {
				TextButton(onClick = { showRenameDialog = false }) { Text(stringResource(R.string.common_cancel)) }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	if (showSaveWorkspaceDialog) {
		AlertDialog(
			onDismissRequest = { showSaveWorkspaceDialog = false },
			title = { Text(stringResource(R.string.chat_save_workspace)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(
						text = stringResource(R.string.chat_save_workspace_body),
						style = MaterialTheme.typography.bodyMedium,
					)
					OutlinedTextField(
						value = workspaceNameInput,
						onValueChange = { workspaceNameInput = it },
						label = { Text(stringResource(R.string.chat_workspace_name_label)) },
						singleLine = true,
					)
				}
			},
			confirmButton = {
				TextButton(
					enabled = workspaceNameInput.isNotBlank(),
					onClick = {
						viewModel.saveWorkspaceAsPermanent(workspaceNameInput)
						showSaveWorkspaceDialog = false
					},
				) { Text(stringResource(R.string.common_save)) }
			},
			dismissButton = {
				TextButton(onClick = { showSaveWorkspaceDialog = false }) { Text(stringResource(R.string.common_cancel)) }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	if (showExportDialog) {
		AlertDialog(
			onDismissRequest = { showExportDialog = false },
			title = { Text(stringResource(R.string.export_title)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(
						text = stringResource(R.string.export_body),
						style = MaterialTheme.typography.bodyMedium,
					)
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clickable { exportIncludeTools = !exportIncludeTools },
						verticalAlignment = Alignment.CenterVertically,
					) {
						Checkbox(
							checked = exportIncludeTools,
							onCheckedChange = { exportIncludeTools = it },
						)
						Text(
							text = stringResource(R.string.export_include_tools),
							style = MaterialTheme.typography.bodyMedium,
						)
					}
				}
			},
			confirmButton = {
				TextButton(
					onClick = {
						showExportDialog = false
						pendingExportTools = exportIncludeTools
						conversationExporter.launch(exportFileName(displayTitle, "md"))
					},
				) { Text(stringResource(R.string.export_conversation_action)) }
			},
			dismissButton = {
				TextButton(onClick = { showExportDialog = false }) {
					Text(stringResource(R.string.common_cancel))
				}
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

/**
 * Full-screen in-conversation search.
 *
 * A dialog rather than a row inside the chat column: with the IME open, an
 * inline bar plus the transcript header and composer left no messages visible
 * on a 640×360 landscape phone. Owning the window also gives the results the
 * whole screen, so a hit can be read before it is opened.
 */
@Composable
private fun ChatSearchDialog(
	query: String,
	onQueryChange: (String) -> Unit,
	matches: List<ChatSearchMatch>,
	onSelect: (ChatSearchMatch) -> Unit,
	onDismiss: () -> Unit,
) {
	val focusRequester = remember { FocusRequester() }
	val keyboard = LocalSoftwareKeyboardController.current
	val resultListState = rememberLazyListState()
	LaunchedEffect(Unit) {
		// The dialog's window is attached after the first composition; a frame
		// of delay keeps requestFocus from racing that attachment. A failed
		// request is not fatal: the field is still tappable.
		delay(50)
		runCatching { focusRequester.requestFocus() }
	}
	// A new query replaces the result set; the previous scroll position would
	// otherwise leave the list halfway down the new (usually shorter) results.
	LaunchedEffect(query) {
		if (matches.isNotEmpty()) resultListState.scrollToItem(0)
	}

	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		Surface(
			modifier = Modifier.fillMaxSize(),
			color = MaterialTheme.colorScheme.background,
		) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.imePadding(),
				contentAlignment = Alignment.TopCenter,
			) {
				Column(
					modifier = Modifier
						.widthIn(max = Layout.ChatMeasure)
						.fillMaxWidth()
						.fillMaxHeight(),
				) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp, vertical = 8.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					OutlinedTextField(
						value = query,
						onValueChange = onQueryChange,
						modifier = Modifier
							.weight(1f)
							.focusRequester(focusRequester),
						singleLine = true,
						placeholder = { Text(stringResource(R.string.chat_search_placeholder)) },
						leadingIcon = {
							Icon(
								Icons.Outlined.Search,
								contentDescription = null,
								modifier = Modifier.size(18.dp),
							)
						},
						trailingIcon = {
							if (query.isNotEmpty()) {
								IconButton(onClick = { onQueryChange("") }) {
									Icon(
										Icons.Filled.Close,
										contentDescription = stringResource(R.string.chat_search_clear),
										modifier = Modifier.size(18.dp),
									)
								}
							}
						},
						keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
						keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
					)
					TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_close)) }
				}

				if (query.isNotBlank()) {
					Text(
						text = if (matches.isEmpty()) {
							stringResource(R.string.chat_search_no_results)
						} else {
							stringResource(R.string.chat_search_matches, matches.size)
						},
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
					)
					Hairline()
				}

				LazyColumn(state = resultListState, modifier = Modifier.weight(1f)) {
					items(matches, key = { it.messageId }) { match ->
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable { onSelect(match) }
								.padding(horizontal = 16.dp, vertical = 10.dp),
							verticalAlignment = Alignment.Top,
						) {
							Text(
								text = stringResource(
									if (match.fromUser) R.string.chat_you else R.string.home_role_assistant,
								).uppercase(),
								style = MaterialTheme.typography.labelSmall,
								fontFamily = FontFamily.Monospace,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.width(84.dp),
							)
							Text(
								text = match.snippet,
								style = MaterialTheme.typography.bodyMedium,
								maxLines = 3,
								overflow = TextOverflow.Ellipsis,
								modifier = Modifier.weight(1f),
							)
						}
						Hairline()
					}
				}
			}
		}
	}
}
}

/**
 * Deliberately not the normal approval dialog: this is not "may I run this one
 * command", it is "may this conversation use SSH at all". The first command is
 * shown because it is the most concrete warning available, but the text says
 * plainly that allowing it allows every later SSH call in the conversation.
 */
@Composable
private fun RewindDialog(
	showEdit: Boolean,
	canEdit: Boolean,
	editBlockedReason: String?,
	canAnswerAgain: Boolean,
	points: List<RegenerationPoint>,
	onDismiss: () -> Unit,
	onEdit: () -> Unit,
	onAnswerAgain: () -> Unit,
	onPoint: (RegenerationPoint) -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.chat_rewind)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.InMessage)) {
				Text(
					text = stringResource(R.string.chat_rewind_picker_body),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Column(
					modifier = Modifier
						.heightIn(max = 420.dp)
						.verticalScroll(rememberScrollState()),
				) {
					if (canAnswerAgain) {
						RewindChoice(
							title = stringResource(R.string.chat_answer_again),
							detail = stringResource(R.string.chat_answer_again_detail),
							enabled = true,
							blockedReason = null,
							onClick = onAnswerAgain,
						)
					}

					if (points.isNotEmpty()) {
						Text(
							text = stringResource(R.string.chat_earlier_in_turn),
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
						)
						points.forEach { point ->
							RewindChoice(
								title = point.label,
								detail = if (point.isUserMessage) {
									stringResource(R.string.chat_point_quote)
								} else {
									stringResource(R.string.chat_point_tool)
								},
								enabled = point.blockedReason == null,
								blockedReason = point.blockedReason,
								onClick = { onPoint(point) },
							)
						}
					}

					if (showEdit) {
						Hairline(Modifier.padding(top = 6.dp))
						RewindChoice(
							title = stringResource(R.string.chat_edit_message),
							detail = stringResource(R.string.chat_edit_message_detail),
							enabled = canEdit,
							blockedReason = editBlockedReason,
							onClick = onEdit,
						)
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
		},
		containerColor = MaterialTheme.colorScheme.surface,
		titleContentColor = MaterialTheme.colorScheme.onSurface,
		textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

@Composable
private fun RewindChoice(
	title: String,
	detail: String,
	enabled: Boolean,
	blockedReason: String?,
	onClick: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = enabled, onClick = onClick)
			.padding(vertical = 9.dp),
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleMedium,
			color = if (enabled) {
				MaterialTheme.colorScheme.onSurface
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
			},
		)
		Text(
			text = detail,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		blockedReason?.let { reason ->
			Text(
				text = reason,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
				modifier = Modifier.padding(top = 2.dp),
			)
		}
	}
}

@Composable
private fun CompactionBanner(
	compaction: ContextCompactionUi,
	onView: () -> Unit,
	onClear: () -> Unit,
) {
	Column(modifier = Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.background(
					MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
					RoundedCornerShape(8.dp),
				)
				.padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			StatusDot(MaterialTheme.colorScheme.primary, size = 5.dp)
			Spacer(Modifier.width(8.dp))
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(1.dp),
			) {
				Text(
					text = stringResource(R.string.chat_compacted),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Text(
					text = stringResource(R.string.chat_compacted_body, compaction.omittedMessages),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			TextButton(
				onClick = onView,
				contentPadding = PaddingValues(horizontal = 7.dp),
			) { Text(stringResource(R.string.chat_view), style = MaterialTheme.typography.labelLarge) }
			TextButton(
				onClick = onClear,
				contentPadding = PaddingValues(horizontal = 7.dp),
			) { Text(stringResource(R.string.chat_undo), style = MaterialTheme.typography.labelLarge) }
		}
	}
}

@Composable
private fun ChatWelcome(
	providerName: String,
	modelLabel: String,
	workspaceName: String,
	configured: Boolean,
	onEnableDemo: () -> Unit,
	onOpenFiles: () -> Unit,
	onOpenSettings: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier,
		verticalArrangement = Arrangement.spacedBy(14.dp),
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			StatusDot(MaterialTheme.colorScheme.primary)
			Eyebrow(stringResource(R.string.chat_welcome_eyebrow))
		}
		Text(
			text = stringResource(R.string.chat_welcome_title),
			style = MaterialTheme.typography.displaySmall,
			color = MaterialTheme.colorScheme.onBackground,
		)
		Text(
			text = stringResource(R.string.chat_welcome_body),
			style = MaterialTheme.typography.bodyLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.widthIn(max = Layout.UserBubbleMeasure),
		)
		Spacer(Modifier.height(10.dp))
		Hairline(Modifier.widthIn(max = Layout.UserBubbleMeasure))
		Spacer(Modifier.height(2.dp))
		MetaLine(
			stringResource(R.string.chat_meta_model),
			"$providerName / $modelLabel",
			Modifier.widthIn(max = Layout.UserBubbleMeasure),
		)
		MetaLine(
			stringResource(R.string.chat_meta_workspace),
			workspaceName,
			Modifier.widthIn(max = Layout.UserBubbleMeasure),
		)
		Spacer(Modifier.height(4.dp))
		// Three actions do not fit on one 360dp line at large font, and the
		// "add a key" action was missing entirely: the composer's placeholder
		// told the user to add one with no control that goes there.
		FlowRowCompat {
			if (!configured) {
				Button(
					onClick = onEnableDemo,
					shape = MaterialTheme.shapes.small,
					contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
				) {
					Text(stringResource(R.string.chat_try_demo), style = MaterialTheme.typography.labelLarge)
				}
				OutlinedButton(
					onClick = onOpenSettings,
					shape = MaterialTheme.shapes.small,
					contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
				) {
					Text(stringResource(R.string.chat_add_key), style = MaterialTheme.typography.labelLarge)
				}
			}
			OutlinedButton(
				onClick = onOpenFiles,
				shape = MaterialTheme.shapes.small,
				contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
			) {
				Text(stringResource(R.string.chat_open_files), style = MaterialTheme.typography.labelLarge)
			}
		}
	}
}

/**
 * A persistent strip when the device failed its own fence check.
 *
 * It deliberately sits outside the scrolling transcript: the tools are absent
 * for every turn, and a warning that scrolls away with last week's messages is
 * a warning the user will not see when the agent says it has no hands.
 */
@Composable
private fun WorkspaceCapabilityBanner(onShowDetails: () -> Unit) {
	Surface(color = MaterialTheme.colorScheme.errorContainer) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = comfortableGutter(), vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
		) {
			Icon(
				Icons.Filled.Warning,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onErrorContainer,
				modifier = Modifier.size(20.dp),
			)
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.capability_banner_title),
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onErrorContainer,
				)
				Text(
					text = stringResource(R.string.capability_banner_body),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onErrorContainer,
				)
			}
			TextButton(onClick = onShowDetails) {
				Text(stringResource(R.string.capability_banner_details))
			}
		}
	}
	Hairline()
}

/**
 * What the startup check asked, and what the device answered.
 *
 * One row per assertion, with the reason a check failed. The dialog is the
 * "tell the user" half of "fail closed": removing the tools without saying why
 * would look like a broken assistant.
 */
@Composable
private fun WorkspaceSelfCheckDialog(
	report: WorkspaceSelfCheck.Report,
	onDismiss: () -> Unit,
) {
	WideDialog(
		onDismissRequest = onDismiss,
		title = stringResource(R.string.capability_dialog_title),
		dismissLabel = stringResource(R.string.chat_close),
		maxBodyHeight = 460.dp,
	) {
		Text(
			text = stringResource(R.string.capability_dialog_body),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		report.error?.let { reason ->
			Text(
				text = stringResource(R.string.capability_error, reason),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
			)
		}
		report.checks.forEach { check ->
			Hairline()
			Column(
				modifier = Modifier.fillMaxWidth().padding(vertical = Layout.Space.InMessage),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				Text(
					text = stringResource(
						R.string.capability_check_row,
						capabilityCheckName(check.id),
						capabilityOutcome(check.outcome),
					),
					style = MaterialTheme.typography.bodyLarge,
					color = when (check.outcome) {
						WorkspaceSelfCheck.Outcome.Failed -> MaterialTheme.colorScheme.error
						else -> MaterialTheme.colorScheme.onSurface
					},
				)
				if (check.detail.isNotBlank()) {
					Text(
						text = check.detail,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
	}
}

@Composable
private fun capabilityCheckName(id: WorkspaceSelfCheck.Id): String = stringResource(
	when (id) {
		WorkspaceSelfCheck.Id.RelativePath -> R.string.capability_check_relative
		WorkspaceSelfCheck.Id.ParentTraversal -> R.string.capability_check_traversal
		WorkspaceSelfCheck.Id.AbsolutePath -> R.string.capability_check_absolute
		WorkspaceSelfCheck.Id.RelativizeOutside -> R.string.capability_check_relativize
		WorkspaceSelfCheck.Id.SymlinkEscape -> R.string.capability_check_symlink
		WorkspaceSelfCheck.Id.JunctionEscape -> R.string.capability_check_junction
	},
)

@Composable
private fun capabilityOutcome(outcome: WorkspaceSelfCheck.Outcome): String = stringResource(
	when (outcome) {
		WorkspaceSelfCheck.Outcome.Passed -> R.string.capability_check_passed
		WorkspaceSelfCheck.Outcome.Failed -> R.string.capability_check_failed
		WorkspaceSelfCheck.Outcome.Skipped -> R.string.capability_check_skipped
	},
)

/**
 * The conversation's approval questions, newest first.
 *
 * Deliberately read-only and deliberately not a transcript row: the model is
 * never shown this, and rewinding a turn does not erase it. [records] is null
 * while the first query runs, which is a different state from "no approvals".
 */
@Composable
private fun ApprovalHistoryDialog(
	records: List<ApprovalRecord>?,
	failed: Boolean,
	onDismiss: () -> Unit,
) {
	WideDialog(
		onDismissRequest = onDismiss,
		title = stringResource(R.string.approval_history_title),
		dismissLabel = stringResource(R.string.chat_close),
		maxBodyHeight = 460.dp,
	) {
		when {
			failed -> Text(
				text = stringResource(R.string.approval_history_error),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.error,
			)
			records == null -> Box(
				modifier = Modifier.fillMaxWidth().padding(24.dp),
				contentAlignment = Alignment.Center,
			) {
				CircularProgressIndicator()
			}
			records.isEmpty() -> Text(
				text = stringResource(R.string.approval_history_empty),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			else -> records.forEachIndexed { index, record ->
				if (index > 0) Hairline()
				Column(
					modifier = Modifier.fillMaxWidth().padding(vertical = Layout.Space.InMessage),
					verticalArrangement = Arrangement.spacedBy(2.dp),
				) {
					Text(
						text = stringResource(
							R.string.approval_history_summary,
							approvalHistoryTime(record.askedAt),
							approvalDecisionLabel(record.decision),
						),
						style = MaterialTheme.typography.bodyLarge,
					)
					Text(
						text = buildString {
							append(record.toolName)
							record.path?.let { append(" · ").append(it) }
						},
						style = MaterialTheme.typography.bodySmall,
						fontFamily = FontFamily.Monospace,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Text(
						text = stringResource(
							R.string.approval_history_rule,
							approvalRuleLabel(record.rule),
						),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
	}
}

@Composable
private fun approvalDecisionLabel(decision: ApprovalDecision): String = stringResource(
	when (decision) {
		ApprovalDecision.Asked -> R.string.approval_decision_asked
		ApprovalDecision.Allowed -> R.string.approval_decision_allowed
		ApprovalDecision.Declined -> R.string.approval_decision_declined
		ApprovalDecision.TimedOut -> R.string.approval_decision_timed_out
	},
)

@Composable
private fun approvalRuleLabel(stored: String): String {
	val join = stringResource(R.string.approval_rule_join)
	return stored.split(',')
		.map { it.trim() }
		.filter { it.isNotEmpty() }
		.map { source ->
			when (source) {
				"global" -> stringResource(R.string.approval_rule_global)
				"assistant" -> stringResource(R.string.approval_rule_assistant)
				"path" -> stringResource(R.string.approval_rule_path)
				"tool" -> stringResource(R.string.approval_rule_tool)
				// A source this build does not know: show the stored name
				// rather than dropping the only clue to why the call asked.
				else -> source
			}
		}
		.joinToString(join)
}

private fun approvalHistoryTime(ms: Long): String =
	DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

@Composable
private fun SshConsentDialog(
	consent: SshConsent,
	onAllow: () -> Unit,
	onDeny: () -> Unit,
) {
	var understood by remember { mutableStateOf(false) }
	// The grant describes what this conversation's selected SSH tools can do.
	// A read-only profile must not be told that the agent can run commands,
	// even though the same SshClient could.
	val body = when {
		consent.canExec -> R.string.chat_ssh_body
		consent.canUpload -> R.string.chat_ssh_body_upload
		consent.canWriteFile -> R.string.chat_ssh_body_write_file
		else -> R.string.chat_ssh_body_read_only
	}
	val warning = when {
		consent.canExec -> R.string.chat_ssh_warning
		consent.canUpload -> R.string.chat_ssh_warning_upload
		consent.canWriteFile -> R.string.chat_ssh_warning_write_file
		else -> R.string.chat_ssh_warning_read_only
	}
	AlertDialog(
		onDismissRequest = onDeny,
		title = { Text(stringResource(R.string.chat_ssh_title)) },
		text = {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 460.dp)
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Text(
					text = stringResource(
						body,
						consent.username,
						consent.host,
					),
					style = MaterialTheme.typography.bodyLarge,
				)
				Text(
					text = stringResource(R.string.chat_ssh_first_operation, consent.operation),
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
						text = stringResource(R.string.chat_ssh_host_key, fingerprint),
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
						text = stringResource(R.string.chat_ssh_understand),
						style = MaterialTheme.typography.bodyMedium,
						modifier = Modifier.weight(1f),
					)
				}
				Text(
					text = stringResource(warning),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.error,
				)
				Text(
					text = stringResource(R.string.chat_ssh_password_note),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		confirmButton = {
			TextButton(onClick = onAllow, enabled = understood) { Text(stringResource(R.string.chat_allow_ssh)) }
		},
		dismissButton = {
			TextButton(onClick = onDeny) { Text(stringResource(R.string.common_cancel)) }
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
 * the viewport leaves the newest tokens below the fold. The finite `scrollBy`
 * steps finish the job and avoid `scrollOffset = Int.MAX_VALUE`. The
 * `scrollToItem` is skipped when the newest row is already visible, because
 * otherwise every streaming tick jumped to its top and immediately scrolled
 * back down.
 */
private suspend fun LazyListState.scrollToLatest(lastIndex: Int) {
	// If the newest row is already visible, do not call `scrollToItem`: that
	// aligns its *top* with the viewport top and then the scrollBy below has to
	// run back down. During streaming this happened on every content tick and
	// read as a jump even though the final position was correct.
	if (layoutInfo.visibleItemsInfo.lastOrNull()?.index != lastIndex) {
		scrollToItem(lastIndex)
	}
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
	val previousLabel = stringResource(R.string.chat_branch_previous)
	val nextLabel = stringResource(R.string.chat_branch_next)
	Row(
		modifier = modifier,
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(2.dp),
	) {
		IconButton(
			onClick = { branches.previousId?.let(onSelect) },
			enabled = enabled && branches.previousId != null,
		) {
			Text(
				text = "‹",
				style = MaterialTheme.typography.titleMedium,
				color = if (enabled && branches.previousId != null) {
					MaterialTheme.colorScheme.onSurfaceVariant
				} else {
					MaterialTheme.colorScheme.outlineVariant
				},
				// The glyph is what a sighted user reads; a screen reader would
				// announce "single left-pointing angle quotation mark".
				modifier = Modifier.clearAndSetSemantics {
					contentDescription = previousLabel
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
		) {
			Text(
				text = "›",
				style = MaterialTheme.typography.titleMedium,
				color = if (enabled && branches.nextId != null) {
					MaterialTheme.colorScheme.onSurfaceVariant
				} else {
					MaterialTheme.colorScheme.outlineVariant
				},
				modifier = Modifier.clearAndSetSemantics {
					contentDescription = nextLabel
				},
			)
		}
	}
}

/**
 * A menu label that carries the PAID badge beside the action.
 *
 * The media actions in this menu call the same paid backends as the tool list,
 * but the menu had no marker and no notice: the cost was visible only in
 * Settings. The tool list's notice cannot travel into a dropdown, so the badge
 * sits on the entry itself, which is the point of choice.
 */
@Composable
private fun PaidMenuLabel(text: String) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(text)
		Spacer(Modifier.width(8.dp))
		PaidBadge()
	}
}

/** The compact billing line above a manual media dialog's fields. */
@Composable
private fun PaidActionNotice() {
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		PaidBadge()
		Text(
			text = stringResource(R.string.chat_paid_action_notice),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.weight(1f),
		)
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
		ToolCallUi.State.Ready -> stringResource(R.string.chat_tool_state_ready)
		ToolCallUi.State.Running -> stringResource(R.string.chat_tool_state_running)
		ToolCallUi.State.Done -> stringResource(R.string.chat_tool_state_done)
		ToolCallUi.State.Declined -> stringResource(R.string.chat_tool_state_declined)
		ToolCallUi.State.Failed -> stringResource(R.string.chat_tool_state_failed)
	}
	val statusColor = when (call.state) {
		ToolCallUi.State.Failed -> MaterialTheme.colorScheme.error
		ToolCallUi.State.Declined -> MaterialTheme.colorScheme.onSurfaceVariant
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
		ToolCatalog.all.firstOrNull { it.name == call.displayName }
			?.takeIf { it.mutates && !it.reversible }
			?.let { RewindBadge(canRewind = false) }
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
@Composable
private fun toolResultSummary(
	toolName: String?,
	firstLine: String,
): Pair<String, String?> {
	if (firstLine.isBlank()) return (toolName ?: stringResource(R.string.chat_tool_result)) to null

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
	highlighted: Boolean = false,
	hideError: Boolean = false,
	isLatestAssistant: Boolean,
	isStreaming: Boolean,
	showRewind: Boolean,
	turnUsage: UsageUi?,
	isTurnEnd: Boolean,
	onShowTurnWire: () -> Unit,
	onShowRewind: () -> Unit,
	onRetry: () -> Unit,
	onUseSuggestion: (String) -> Unit,
	onSelectBranch: (String?, String) -> Unit,
) {
	val alignment = if (message.fromUser) Alignment.End else Alignment.Start
	val context = LocalContext.current
	// The wire view is a turn-level inspector: one button on the last assistant
	// row of a completed turn, not one after every tool round. Intermediate
	// rounds are still included in the dialog's JSON; they just do not each get
	// an icon.
	val hasWire = isTurnEnd && !message.fromUser && !isStreaming
	val hasTextActions = !message.fromUser &&
		!message.isStreaming &&
		message.plainText.isNotBlank()
	// Put the wire button in the existing bottom action row when that row exists.
	// Only a message with no text and no rewind needs a header fallback; this is
	// what keeps a lone {} from creating an otherwise empty line.
	val showBottomWire = hasWire && (hasTextActions || showRewind)

	// Per-message view state, not stored: "show me the Markdown" and "repair the
	// unclosed fence for display" are ways of reading this answer, not edits to
	// it. A branch switch gets a different id and therefore fresh state.
	var showSource by remember(message.id) { mutableStateOf(false) }
	var forceMarkdown by remember(message.id) { mutableStateOf(false) }
	val openFenceState = remember(message.plainText, message.isStreaming) {
		if (!message.fromUser && !message.isStreaming) openFence(message.plainText) else null
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
				.widthIn(max = Layout.ChatMeasure)
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
		modifier = Modifier
			.fillMaxWidth()
			.background(
				if (highlighted) {
					MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
				} else {
					androidx.compose.ui.graphics.Color.Transparent
				},
				RoundedCornerShape(4.dp),
			),
		horizontalAlignment = alignment,
		verticalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (!message.fromUser) {
				StatusDot(MaterialTheme.colorScheme.primary, size = 5.dp)
				Spacer(Modifier.width(7.dp))
			}
			Eyebrow(
				text = if (message.fromUser) {
					stringResource(R.string.chat_you)
				} else {
					stringResource(R.string.chat_assistant)
				},
				color = if (message.fromUser) {
					MaterialTheme.colorScheme.onSurfaceVariant
				} else {
					MaterialTheme.colorScheme.primary
				},
			)
			if (hasWire && !showBottomWire) {
				Spacer(Modifier.weight(1f))
				IconButton(
					onClick = onShowTurnWire,
				) {
					Icon(
						imageVector = Icons.Outlined.DataObject,
						contentDescription = stringResource(R.string.chat_view_wire),
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(16.dp),
					)
				}
			}
		}
		Box(
			modifier = if (message.fromUser) {
				Modifier
					.widthIn(max = Layout.UserBubbleMeasure)
					.background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
					.border(
						1.dp,
						MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
						MaterialTheme.shapes.medium,
					)
					.padding(horizontal = 14.dp, vertical = 11.dp)
			} else {
				// An assistant answer is the document, not a remark in a chat:
				// no bubble. It gets a wide measure for code and tables, but not
				// the full tablet width -- a 1,200dp paragraph is a wall.
				Modifier
					.widthIn(max = Layout.ChatMeasure)
					.fillMaxWidth()
			},
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
					isLatestAssistant = isLatestAssistant,
					hasReasoning = reasoning.isNotBlank(),
					hasText = hasText,
					toolCallCount = calls.size,
					answerAfterCalls = message.answerAfterCalls,
				)
				if (reasoning.isNotBlank() && !message.fromUser) {
					CollapsibleSection(
						summary = stringResource(R.string.chat_thinking),
						trailing = stringResource(R.string.chat_chars, reasoning.length),
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
						summary = if (calls.size == 1) {
							stringResource(R.string.chat_used_one_tool)
						} else {
							stringResource(R.string.chat_used_tools, calls.size)
						},
						trailing = calls.count { it.state == ToolCallUi.State.Failed }
							.takeIf { it > 0 }
							?.let { stringResource(R.string.chat_failed_count, it) },
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
				val textParts = message.parts.filter { it.kind == AssistantPart.Kind.Text }
				if (showSource) {
					// The raw Markdown, for the cases where rendering is the
					// thing that is wrong: an unclosed fence, a table the
					// renderer lays out badly, a construct the library does not
					// know. Selectable, because the point is to get the text out.
					SelectionContainer {
						Text(
							text = message.plainText,
							style = MaterialTheme.typography.bodySmall,
							fontFamily = FontFamily.Monospace,
							color = MaterialTheme.colorScheme.onSurface,
							modifier = Modifier
								.fillMaxWidth()
								.horizontalScroll(rememberScrollState())
								.background(
									MaterialTheme.colorScheme.surfaceVariant,
									RoundedCornerShape(8.dp),
								)
								.padding(8.dp),
						)
					}
				} else {
					textParts.forEach { part ->
						// A display-only repair. In the common failure the model
						// wrote one opening fence too many and then continued
						// with Markdown; appending a close at the end would
						// leave all of that inside the block. Dropping the
						// unmatched opening line is what lets the rest render.
						// The stored message is untouched.
						val text = if (!message.fromUser && forceMarkdown) {
							withoutOpenFence(part.text) ?: part.text
						} else {
							part.text
						}
						if (message.fromUser) {
							// The user's own text is rendered literally. They
							// typed it; interpreting their asterisks as
							// formatting would silently change what they said.
							Text(
								text = text,
								style = MaterialTheme.typography.bodyLarge,
								color = MaterialTheme.colorScheme.onSurface,
							)
						} else {
							MarkdownText(
								text = text,
								deltas = message.deltas,
								isStreaming = message.isStreaming,
							)
						}
					}
				}

				if (openFenceState != null && !showSource) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = if (forceMarkdown) {
								stringResource(R.string.chat_fence_removed)
							} else {
								stringResource(R.string.chat_fence_shown)
							},
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.weight(1f),
						)
						TextButton(
							onClick = { forceMarkdown = !forceMarkdown },
							contentPadding = PaddingValues(horizontal = 8.dp),
						) {
							Text(
								if (forceMarkdown) {
									stringResource(R.string.chat_show_original)
								} else {
									stringResource(R.string.chat_render_rest)
								},
							)
						}
					}
				}

				// Attachment names, so a message that was mostly a picture does
				// not look like it had no content.
				if (message.attachments.isNotEmpty()) {
					message.attachments.forEach { attachment ->
						Text(
							text = "📎 ${attachment.displayName}",
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurface,
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

				// Follow-ups the model offered with this answer. They are chips,
				// not a new kind of turn: a tap fills the composer through
				// `useSuggestion`, and the Send button is still the user's.
				if (!message.fromUser && !message.isStreaming && message.suggestions.isNotEmpty()) {
					FlowRowCompat {
						message.suggestions.forEach { suggestion ->
							AssistChip(
								onClick = { onUseSuggestion(suggestion) },
								label = {
									Text(
										text = suggestion,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis,
									)
								},
							)
						}
					}
				}
			}
		}

		if (!hideError) message.error?.let {
			Row(
				modifier = Modifier.padding(top = 4.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = it,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.error,
					modifier = Modifier.weight(1f),
				)
				// Stop is deliberate, not a failure; keep the retry next to the
				// stopped answer instead of making the user retype the message.
				if (message.error == ChatViewModel.STOPPED_MESSAGE && isLatestAssistant) {
					TextButton(onClick = onRetry) {
						Text(stringResource(R.string.chat_retry))
					}
				}
			}
		}

		// Token accounting for the whole turn, once, at its end. The per-message
		// line was repeated after every tool round; a five-round turn showed
		// five lines and buried the answer in its own telemetry.
		if (isTurnEnd) {
			turnUsage?.let { u ->
				UsageLine(
					usage = u,
					prefix = stringResource(R.string.usage_turn_total),
					modifier = Modifier.padding(top = 4.dp),
				)
			}
		}

		if (!message.fromUser && message.changedFiles.isNotEmpty()) {
			ChangedFilesSummary(
				changes = message.changedFiles,
				modifier = Modifier.padding(top = 6.dp),
			)
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

		// One compact action bar per message. Source and copy are local to the
		// message; rewind is the single restart control for the turn.
		if (hasTextActions || showRewind || showBottomWire) {
			Row(
				modifier = Modifier.padding(top = 2.dp),
				horizontalArrangement = Arrangement.spacedBy(2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				if (hasTextActions) {
					IconButton(
						onClick = { showSource = !showSource },
					) {
						Icon(
							imageVector = Icons.Outlined.Code,
							contentDescription = if (showSource) {
								stringResource(R.string.chat_show_rendered)
							} else {
								stringResource(R.string.chat_show_markdown)
							},
							tint = if (showSource) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							},
							modifier = Modifier.size(17.dp),
						)
					}
					IconButton(
						onClick = { copyReply(context, message.plainText) },
					) {
						Icon(
							imageVector = Icons.Outlined.ContentCopy,
							contentDescription = stringResource(R.string.chat_copy_reply),
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(16.dp),
						)
					}
				}

				if (showBottomWire) {
					IconButton(
						onClick = onShowTurnWire,
					) {
						Icon(
							imageVector = Icons.Outlined.DataObject,
							contentDescription = stringResource(R.string.chat_view_wire),
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(16.dp),
						)
					}
				}

				if (showRewind) {
					IconButton(
						onClick = onShowRewind,
					) {
						Icon(
							imageVector = Icons.AutoMirrored.Outlined.Undo,
							contentDescription = stringResource(R.string.chat_rewind_turn),
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(18.dp),
						)
					}
				}
			}
		}
	}
}

@Composable
private fun ChangedFilesSummary(
	changes: List<WorkspaceChange>,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(2.dp),
	) {
		Text(
			text = stringResource(R.string.chat_changed_files),
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		changes.forEach { change ->
			Text(
				text = "${changeMark(change.kind)} ${change.path}",
				style = MaterialTheme.typography.bodySmall,
				fontFamily = FontFamily.Monospace,
				color = when (change.kind) {
					WorkspaceChange.Kind.Added -> MaterialTheme.colorScheme.primary
					WorkspaceChange.Kind.Modified -> MaterialTheme.colorScheme.onSurface
					WorkspaceChange.Kind.Deleted -> MaterialTheme.colorScheme.error
				},
			)
		}
	}
}

/** One character, so a list of paths scans as a diff. */
private fun changeMark(kind: WorkspaceChange.Kind): String = when (kind) {
	WorkspaceChange.Kind.Added -> "+"
	WorkspaceChange.Kind.Modified -> "~"
	WorkspaceChange.Kind.Deleted -> "-"
}

/**
 * The resume control for a turn a process death left unfinished.
 *
 * Above the composer rather than in the transcript: the action is about the
 * next model call, and a message at the bottom of a long scroll is exactly
 * where the user is not looking. The wording states the boundary -- the last
 * saved step resumes, a half-streamed sentence does not -- because a button
 * that implied more would be a promise the storage cannot keep.
 */
@Composable
private fun ContinueTurnBanner(onContinue: () -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = comfortableGutter(), vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
	) {
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = stringResource(R.string.chat_continue_title),
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Text(
				text = stringResource(R.string.chat_continue_body),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		TextButton(onClick = onContinue) {
			Text(stringResource(R.string.chat_continue))
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
	/** The microphone is present only when a transcription backend is configured. */
	voiceEnabled: Boolean,
	onTranscribeVoice: suspend (ByteArray) -> Result<String>,
	onVoiceText: (String) -> Unit,
	context: ContextUsage,
	showContextDetail: Boolean,
	onToggleContextDetail: () -> Unit,
	compaction: ContextCompactionUi?,
	isCompacting: Boolean,
	onCompact: () -> Unit,
	onViewCompaction: () -> Unit,
	onClearCompaction: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val voiceContext = LocalContext.current
	val recorder = remember { VoiceRecorder(voiceContext) }
	DisposableEffect(recorder) {
		onDispose { recorder.cancel() }
	}
	val voiceScope = rememberCoroutineScope()
	var recording by remember { mutableStateOf(false) }
	var voiceBusy by remember { mutableStateOf(false) }
	var voiceError by remember { mutableStateOf<String?>(null) }
	var costAcknowledged by rememberSaveable { mutableStateOf(false) }
	var showVoiceCost by remember { mutableStateOf(false) }
	var elapsedSeconds by remember { mutableStateOf(0) }
	val emptyVoice = stringResource(R.string.chat_voice_empty)
	val permissionVoice = stringResource(R.string.chat_voice_permission)
	val tooLongVoice = stringResource(R.string.chat_voice_too_long)
	val voiceBusyLabel = stringResource(R.string.chat_voice_busy)
	val identity = stringResource(R.string.chat_voice_recording)

	fun stopAndTranscribe() {
		recording = false
		val wav = recorder.stop()
		if (wav == null) {
			voiceError = emptyVoice
			return
		}
		voiceBusy = true
		voiceScope.launch {
			onTranscribeVoice(wav).fold(
				onSuccess = { text ->
					if (text.isBlank()) {
						voiceError = emptyVoice
					} else {
						voiceError = null
						onVoiceText(text)
					}
				},
				onFailure = { failure ->
					voiceError = voiceContext.getString(
						R.string.chat_voice_failed,
						failure.message ?: emptyVoice,
					)
				},
			)
			voiceBusy = false
		}
	}

	fun beginRecording() {
		if (!recorder.hasPermission()) {
			voiceError = permissionVoice
			return
		}
		val started = recorder.start(
			onLimit = {
				voiceScope.launch {
					if (recorder.isRecording) stopAndTranscribe()
					voiceError = tooLongVoice
				}
			},
		)
		if (started) {
			recording = true
			elapsedSeconds = 0
			voiceError = null
		} else {
			voiceError = voiceContext.getString(R.string.chat_voice_not_configured)
		}
	}

	val permission = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestPermission(),
	) { granted ->
		if (granted) beginRecording() else voiceError = permissionVoice
	}

	/**
	 * The button path: start if idle, stop if already recording.
	 *
	 * This used to exist only as a raw press-and-hold gesture behind an
	 * `onClick = {}`. TalkBack, Switch Access and Voice Access activate the
	 * semantics click and never reach `detectTapGestures`, so the microphone
	 * was a button that did nothing for them. A tap is also the discoverable
	 * gesture: nothing on screen says "hold".
	 */
	fun toggleVoice() {
		if (recording) {
			stopAndTranscribe()
			return
		}
		if (!costAcknowledged) {
			showVoiceCost = true
			return
		}
		if (!recorder.hasPermission()) {
			permission.launch(Manifest.permission.RECORD_AUDIO)
			return
		}
		beginRecording()
	}

	val voiceStatus = when {
		recording -> "${elapsedSeconds}s · $identity"
		voiceBusy -> voiceBusyLabel
		voiceError != null -> voiceError
		else -> null
	}

	LaunchedEffect(recording) {
		if (!recording) {
			elapsedSeconds = 0
			return@LaunchedEffect
		}
		val startedAt = System.currentTimeMillis()
		while (recording) {
			elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
			kotlinx.coroutines.delay(250)
		}
	}

	if (showVoiceCost) {
		AlertDialog(
			onDismissRequest = { showVoiceCost = false },
			title = { Text(stringResource(R.string.chat_voice_start)) },
			text = { Text(stringResource(R.string.chat_voice_cost)) },
			confirmButton = {
				TextButton(
					onClick = {
						showVoiceCost = false
						costAcknowledged = true
						if (recorder.hasPermission()) {
							beginRecording()
						} else {
							permission.launch(Manifest.permission.RECORD_AUDIO)
						}
					},
				) {
					Text(stringResource(R.string.common_ok))
				}
			},
			dismissButton = {
				TextButton(onClick = { showVoiceCost = false }) {
					Text(stringResource(R.string.common_cancel))
				}
			},
		)
	}

	Column(modifier = modifier) {
		Hairline()
		// Above the field and right-aligned, so it reads as a status of the
		// conversation rather than as a control on the message being written.
		// It draws nothing at all when the provider reports nothing to draw.
		ContextGauge(
			usage = context,
			expanded = showContextDetail,
			onToggle = onToggleContextDetail,
			compaction = compaction,
			isCompacting = isCompacting,
			onCompact = onCompact,
			onViewCompaction = onViewCompaction,
			onClearCompaction = onClearCompaction,
			modifier = Modifier.padding(horizontal = comfortableGutter(), vertical = 4.dp),
		)

		// Staged images sit above the field, so they read as part of the message
		// being written rather than as a toolbar beside it.
		if (attachments.isNotEmpty()) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = comfortableGutter(), vertical = 4.dp),
				horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
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
							text = "${attachment.displayName}  ${formatBytes(attachment.sizeBytes)}",
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.widthIn(max = 200.dp),
						)
						// A 28dp box around a text glyph was both too small to
						// hit and announced as "multiplication sign" by a screen
						// reader. The icon names the action; the button keeps
						// Material's 48dp target.
						IconButton(onClick = { onRemoveAttachment(attachment) }) {
							Icon(
								Icons.Filled.Close,
								contentDescription = stringResource(R.string.chat_remove_attachment),
								tint = MaterialTheme.colorScheme.error,
								modifier = Modifier.size(16.dp),
							)
						}
					}
				}
			}
		}

		voiceStatus?.let { status ->
			Text(
				text = status,
				style = MaterialTheme.typography.labelSmall,
				color = if (voiceError != null) {
					MaterialTheme.colorScheme.error
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				modifier = Modifier.padding(
					start = comfortableGutter(),
					end = comfortableGutter(),
					top = 4.dp,
				),
			)
		}

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = comfortableGutter(), vertical = 10.dp),
			verticalAlignment = Alignment.Bottom,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			if (voiceEnabled) {
				IconButton(
					onClick = { toggleVoice() },
					enabled = enabled && !isStreaming && !isCompacting && !voiceBusy,
					modifier = Modifier.padding(bottom = 4.dp),
				) {
					Icon(
						Icons.Filled.Mic,
						contentDescription = stringResource(
							if (recording) R.string.chat_voice_stop else R.string.chat_voice_start,
						),
						tint = if (recording) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
					)
				}
			}

			// Present but disabled when the model cannot see, rather than hidden.
			//
			// A control that vanishes leaves someone wondering where it went; one
			// that is there and greyed out can be tapped and answered. That answer
			// matters here because sending an image to a text-only model does not
			// fail -- it returns 200 and the model describes a placeholder -- so
			// there is no other place the reason could surface.
			IconButton(
				onClick = onAttach,
				enabled = enabled && canAttachImages && !isCompacting,
				modifier = Modifier.padding(bottom = 4.dp),
			) {
				Icon(
					Icons.Outlined.Image,
					contentDescription = if (canAttachImages) {
						stringResource(R.string.chat_attach_image)
					} else {
						stringResource(R.string.chat_cannot_read_images)
					},
					tint = if (enabled && canAttachImages && !isCompacting) {
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
				Text(
					if (enabled) {
						stringResource(R.string.chat_message_hint)
					} else {
						stringResource(R.string.chat_add_key_hint)
					},
				)
			},
			enabled = enabled && !isStreaming && !isCompacting,
			maxLines = 6,
			shape = MaterialTheme.shapes.small,
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
			keyboardActions = KeyboardActions(),
		)

		// One button, two meanings: send while idle, stop while streaming.
		// Reaching for a separate stop control mid-generation is worse than
		// having the same target do the obvious thing.
		if (isStreaming) {
			FilledIconButton(
				onClick = onStop,
				shape = MaterialTheme.shapes.small,
			) {
				Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_stop))
			}
		} else {
			FilledIconButton(
				onClick = onSend,
				// An image alone is sendable: "what is this?" is implied, and
				// requiring text would make attaching one a two-step job.
				enabled = enabled && !isCompacting && (value.isNotBlank() || attachments.isNotEmpty()),
				shape = MaterialTheme.shapes.small,
				colors = IconButtonDefaults.filledIconButtonColors(
					containerColor = MaterialTheme.colorScheme.primary,
					contentColor = MaterialTheme.colorScheme.onPrimary,
				),
			) {
				Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.chat_send))
			}
		}
		}
	}
}

/** Locale-aware timestamp for one retained snapshot turn. */
private fun formatSnapshotTime(millis: Long): String =
	DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
		.format(Date(millis))

/** Put one reply on the clipboard, with a label the paste target can show. */
private fun copyReply(context: android.content.Context, text: String) {
	val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
	clipboard.setPrimaryClip(
		ClipData.newPlainText(context.getString(R.string.chat_clip_reply), text),
	)
}

/**
 * The per-conversation tool sheet.
 *
 * A sheet rather than a dialog per change: this is the one place a user edits
 * the capability set, and the Apply button is the whole confirmation. The
 * checkboxes are limited to `available` (assistant ceiling or the conversation's
 * existing tools), so there is no path here to a capability neither has.
 */
@Composable
private fun ConversationToolsDialog(
	available: List<ToolCatalog.Entry>,
	selected: Set<String>,
	streaming: Boolean,
	onApply: (Set<String>) -> Unit,
	onDismiss: () -> Unit,
) {
	var checked by remember(selected) { mutableStateOf(selected) }
	val remoteSelected = available.any { it.name in checked && it.remote }
	val irreversibleSelected = available.any { it.name in checked && it.mutates && !it.reversible }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.chat_tools_title)) },
		text = {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 460.dp)
					.verticalScroll(rememberScrollState()),
			) {
				Text(
					text = stringResource(R.string.chat_tools_body),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Spacer(Modifier.height(8.dp))
				if (available.isEmpty()) {
					Text(
						text = stringResource(R.string.chat_tools_empty),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				available.forEach { entry ->
					val isChecked = entry.name in checked
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clickable {
								checked = if (isChecked) checked - entry.name else checked + entry.name
							}
							.padding(vertical = 5.dp),
						verticalAlignment = Alignment.Top,
					) {
						Checkbox(
							checked = isChecked,
							onCheckedChange = { on ->
								checked = if (on) checked + entry.name else checked - entry.name
							},
						)
						Column(
							modifier = Modifier
								.weight(1f)
								.padding(start = 2.dp, top = 1.dp),
						) {
							Row(
								horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
								verticalAlignment = Alignment.CenterVertically,
							) {
								Text(
									text = entry.name,
									style = MaterialTheme.typography.labelLarge,
									fontFamily = FontFamily.Monospace,
									color = MaterialTheme.colorScheme.tertiary,
								)
								if (entry.paid) PaidBadge()
								if (entry.remote) RemoteBadge()
								if (entry.mutates) RewindBadge(canRewind = entry.reversible)
							}
							Text(
								text = toolBlurb(entry),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				}
				if (remoteSelected) {
					Spacer(Modifier.height(6.dp))
					Text(
						text = stringResource(R.string.chat_tools_remote_note),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
					)
				}
				if (irreversibleSelected) {
					Spacer(Modifier.height(4.dp))
					Text(
						text = stringResource(R.string.chat_tools_irreversible_note),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
					)
				}
				if (streaming) {
					Spacer(Modifier.height(4.dp))
					Text(
						text = stringResource(R.string.chat_tools_streaming),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
					)
				}
			}
		},
		confirmButton = {
			TextButton(
				onClick = { onApply(checked) },
				enabled = !streaming,
			) {
				Text(stringResource(R.string.chat_tools_apply))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
		},
		containerColor = MaterialTheme.colorScheme.surface,
		titleContentColor = MaterialTheme.colorScheme.onSurface,
		textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
	)
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

@Composable
private fun GenerateImageDialog(
	workspaceId: String?,
	onGenerate: suspend (String, String) -> Result<ChatViewModel.GeneratedImage>,
	onDismiss: () -> Unit,
) {
	val scope = rememberCoroutineScope()
	val context = LocalContext.current
	var prompt by remember { mutableStateOf("") }
	var size by remember { mutableStateOf("1024*1024") }
	var generating by remember { mutableStateOf(false) }
	var saved by remember { mutableStateOf<String?>(null) }
	var error by remember { mutableStateOf<String?>(null) }

	AlertDialog(
		onDismissRequest = { if (!generating) onDismiss() },
		title = { Text(stringResource(R.string.chat_generate_image_title)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages)) {
				PaidActionNotice()
				OutlinedTextField(
					value = prompt,
					onValueChange = { prompt = it },
					modifier = Modifier.fillMaxWidth(),
					label = { Text(stringResource(R.string.chat_image_prompt)) },
					minLines = 3,
					enabled = !generating,
				)
				OutlinedTextField(
					value = size,
					onValueChange = { size = it },
					modifier = Modifier.fillMaxWidth(),
					label = { Text(stringResource(R.string.chat_image_size)) },
					singleLine = true,
					enabled = !generating,
				)
				if (generating) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
					) {
						CircularProgressIndicator(strokeWidth = 2.dp)
						Text(stringResource(R.string.chat_generating))
					}
				}
				saved?.let { path ->
					Text(stringResource(R.string.chat_image_saved, path))
					workspaceId?.let { id ->
						TextButton(
							onClick = {
								openWorkspaceFile(context, id, path, "image/png")
							},
						) { Text(stringResource(R.string.chat_open)) }
					}
				}
				error?.let {
					Text(it, color = MaterialTheme.colorScheme.error)
				}
			}
		},
		confirmButton = {
			TextButton(
				enabled = prompt.isNotBlank() && !generating,
				onClick = {
					generating = true
					error = null
					saved = null
					scope.launch {
						onGenerate(prompt.trim(), size.trim()).fold(
							onSuccess = { result ->
								saved = result.relativePath
								generating = false
							},
							onFailure = {
								error = it.message ?: "Image generation failed"
								generating = false
							},
						)
					}
				},
			) {
				Text(stringResource(R.string.common_generate))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss, enabled = !generating) {
				Text(stringResource(R.string.common_cancel))
			}
		},
	)
}

@Composable
private fun DecisionDialog(
	onDecide: suspend (String) -> Result<kotlinx.serialization.json.JsonObject>,
	onDecideCustom: suspend (String, String) -> Result<kotlinx.serialization.json.JsonObject>,
	onDismiss: () -> Unit,
) {
	// Hoisted out of the click handler: `stringResource` is @Composable, and the
	// failure text is assembled inside a `launch` where that is not available.
	val decisionFailed = stringResource(R.string.chat_decision_failed)
	val scope = rememberCoroutineScope()
	var stateText by remember { mutableStateOf("") }
	var custom by remember { mutableStateOf(false) }
	var questionsJson by remember {
		mutableStateOf(
			"""{"urgent":{"type":"noul","instructions":"Does this need immediate attention?"}}""",
		)
	}
	var running by remember { mutableStateOf(false) }
	var resultText by remember { mutableStateOf<String?>(null) }
	var error by remember { mutableStateOf<String?>(null) }

	AlertDialog(
		onDismissRequest = { if (!running) onDismiss() },
		title = { Text(stringResource(R.string.chat_decision)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages)) {
				PaidActionNotice()
				Text(
					stringResource(R.string.chat_decision_presets),
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				FilterChip(
					selected = custom,
					onClick = { custom = !custom },
					label = { Text(stringResource(R.string.chat_decision_custom_questions)) },
					enabled = !running,
				)
				if (custom) {
					OutlinedTextField(
						value = questionsJson,
						onValueChange = { questionsJson = it },
						modifier = Modifier.fillMaxWidth(),
						label = { Text(stringResource(R.string.chat_decision_questions_object)) },
						minLines = 5,
						enabled = !running,
					)
				}
				OutlinedTextField(
					value = stateText,
					onValueChange = { stateText = it },
					modifier = Modifier.fillMaxWidth(),
					label = { Text(stringResource(R.string.chat_decision_state)) },
					minLines = 4,
					enabled = !running,
				)
				if (running) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
					) {
						CircularProgressIndicator(strokeWidth = 2.dp)
						Text(stringResource(R.string.chat_deciding))
					}
				}
				resultText?.let {
					Text(
						it,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
			}
		},
		confirmButton = {
			TextButton(
				enabled = stateText.isNotBlank() && !running,
				onClick = {
					running = true
					error = null
					resultText = null
					scope.launch {
						val result = if (custom) {
							onDecideCustom(stateText.trim(), questionsJson)
						} else {
							onDecide(stateText.trim())
						}
						result.fold(
							onSuccess = {
								resultText = it.toString()
								running = false
							},
							onFailure = {
								error = it.message ?: decisionFailed
								running = false
							},
						)
					}
				},
			) {
				Text(stringResource(R.string.chat_decide))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss, enabled = !running) {
				Text(stringResource(R.string.common_cancel))
			}
		},
	)
}

@Composable
private fun SpeechDialog(
	workspaceId: String?,
	onSpeak: suspend (String) -> Result<ChatViewModel.GeneratedAudio>,
	onTranscribe: suspend (String) -> Result<String>,
	onDismiss: () -> Unit,
) {
	// As in DecisionDialog: these fallbacks are used inside `launch` blocks.
	val speechFailed = stringResource(R.string.chat_speech_failed)
	val transcriptionFailed = stringResource(R.string.chat_transcription_failed)
	val scope = rememberCoroutineScope()
	val context = LocalContext.current
	var text by remember { mutableStateOf("") }
	var running by remember { mutableStateOf(false) }
	var saved by remember { mutableStateOf<String?>(null) }
	var transcript by remember { mutableStateOf<String?>(null) }
	var error by remember { mutableStateOf<String?>(null) }

	AlertDialog(
		onDismissRequest = { if (!running) onDismiss() },
		title = { Text(stringResource(R.string.chat_tts_title)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages)) {
				PaidActionNotice()
				OutlinedTextField(
					value = text,
					onValueChange = { text = it },
					modifier = Modifier.fillMaxWidth(),
					label = { Text(stringResource(R.string.chat_tts_text)) },
					minLines = 4,
					enabled = !running,
				)
				if (running) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
					) {
						CircularProgressIndicator(strokeWidth = 2.dp)
						Text(stringResource(R.string.chat_synthesizing))
					}
				}
				saved?.let { path ->
					Text(stringResource(R.string.chat_saved_to, path))
					workspaceId?.let { id ->
						TextButton(
							onClick = {
								openWorkspaceFile(context, id, path, "audio/mpeg")
							},
						) { Text(stringResource(R.string.chat_play)) }
					}
					TextButton(
						enabled = !running,
						onClick = {
							running = true
							error = null
							transcript = null
							scope.launch {
								onTranscribe(path).fold(
									onSuccess = {
										transcript = it
										running = false
									},
									onFailure = {
										error = it.message ?: transcriptionFailed
										running = false
									},
								)
							}
						},
					) { Text(stringResource(R.string.chat_transcribe)) }
				}
				transcript?.let { Text(it) }
				error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
			}
		},
		confirmButton = {
			TextButton(
				enabled = text.isNotBlank() && !running,
				onClick = {
					running = true
					error = null
					saved = null
					scope.launch {
						onSpeak(text.trim()).fold(
							onSuccess = {
								saved = it.relativePath
								running = false
							},
							onFailure = {
								error = it.message ?: speechFailed
								running = false
							},
						)
					}
				},
			) {
				Text(stringResource(R.string.chat_speak))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss, enabled = !running) {
				Text(stringResource(R.string.common_cancel))
			}
		},
	)
}

@Composable
private fun VideoDialog(
	workspaceId: String?,
	status: String?,
	path: String?,
	error: String?,
	onStart: (String, String, String, Int) -> Unit,
	onDismiss: () -> Unit,
) {
	val context = LocalContext.current
	var prompt by remember { mutableStateOf("") }
	var resolution by remember { mutableStateOf("720P") }
	var ratio by remember { mutableStateOf("16:9") }
	var duration by remember { mutableStateOf("5") }
	val running = status != null

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.chat_generate_video)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages)) {
				PaidActionNotice()
				OutlinedTextField(
					value = prompt,
					onValueChange = { prompt = it },
					modifier = Modifier.fillMaxWidth(),
					label = { Text(stringResource(R.string.chat_video_prompt)) },
					minLines = 3,
					enabled = !running,
				)
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					OutlinedTextField(
						value = resolution,
						onValueChange = { resolution = it },
						modifier = Modifier.weight(1f),
						label = { Text(stringResource(R.string.chat_video_resolution)) },
						singleLine = true,
						enabled = !running,
					)
					OutlinedTextField(
						value = ratio,
						onValueChange = { ratio = it },
						modifier = Modifier.weight(1f),
						label = { Text(stringResource(R.string.chat_video_ratio)) },
						singleLine = true,
						enabled = !running,
					)
				}
				OutlinedTextField(
					value = duration,
					onValueChange = { duration = it.filter(Char::isDigit) },
					modifier = Modifier.fillMaxWidth(),
					label = { Text(stringResource(R.string.chat_video_seconds)) },
					singleLine = true,
					enabled = !running,
				)
				status?.let {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
					) {
						CircularProgressIndicator(strokeWidth = 2.dp)
						Text(stringResource(R.string.chat_video_task, it))
					}
				}
				path?.let { savedPath ->
					Text(stringResource(R.string.chat_saved_to, savedPath))
					workspaceId?.let { id ->
						TextButton(
							onClick = {
								openWorkspaceFile(context, id, savedPath, "video/mp4")
							},
						) { Text(stringResource(R.string.chat_play)) }
					}
				}
				error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
			}
		},
		confirmButton = {
			TextButton(
				enabled = prompt.isNotBlank() && !running,
				onClick = {
					onStart(
						prompt.trim(),
						resolution.trim(),
						ratio.trim(),
						duration.toIntOrNull()?.coerceIn(2, 30) ?: 5,
					)
				},
			) {
				Text(stringResource(R.string.common_generate))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.common_cancel))
			}
		},
	)
}

