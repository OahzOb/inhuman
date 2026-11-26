package com.inhuman.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.AppCapabilities
import com.inhuman.R
import com.inhuman.core.common.diag.Diag
import com.mikepenz.markdown.model.parseMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.data.ApprovalRecord
import com.inhuman.core.data.Branches
import com.inhuman.core.data.SnapshotStats
import com.inhuman.core.data.SnapshotTurn
import com.inhuman.core.data.WorkspaceChange
import com.inhuman.core.data.db.ApprovalDecision
import com.inhuman.core.data.db.SessionMode
import com.inhuman.core.engine.roleplay.RoleplayField
import com.inhuman.core.engine.roleplay.RoleplayLimits
import com.inhuman.core.engine.roleplay.RoleplayScene
import com.inhuman.core.files.WorkspaceSelfCheck
import com.inhuman.core.design.Eyebrow
import com.inhuman.core.design.SectionHeading
import com.inhuman.core.design.currentWindowFrame
import com.inhuman.core.design.Hairline
import com.inhuman.core.design.Layout
import com.inhuman.core.design.MetaLine
import com.inhuman.core.design.StatusDot
import com.inhuman.core.design.WideDialog
import com.inhuman.core.design.WorkbenchGrid
import com.inhuman.core.design.comfortableGutter
import com.inhuman.ui.common.formatBytes
import com.inhuman.ui.common.highlightSnippet
import com.inhuman.ui.common.FlowRowCompat
import com.inhuman.ui.common.PaidBadge
import com.inhuman.ui.common.RemoteBadge
import com.inhuman.ui.common.RewindBadge
import com.inhuman.ui.common.toolBlurb
import com.inhuman.ui.common.openWorkspaceFile
import com.inhuman.ui.common.WorkspaceAudioPlayer
import com.inhuman.ui.common.exportFileName
import com.inhuman.ui.ssh.SshHostSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.io.File
import java.io.FileInputStream
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
	val changeModelLabel = stringResource(R.string.chat_change_model)
	// Read here rather than at the call site: the audio launcher's failure path
	// runs inside a `launch`, where `stringResource` is not available.
	val transcriptionFailedText = stringResource(R.string.chat_transcription_failed)
	val speechFailedText = stringResource(R.string.chat_speech_failed)
	val playbackFailedText = stringResource(R.string.chat_audio_play_failed)
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
	// How this conversation maintains its request projection. Read here, not in
	// the composer, because four different surfaces change with it: the menu, the
	// transcript's banner/status rows, the composer's gauge, and the error action.
	val sessionMode by fieldOf(viewModel.state, SessionMode.CLASSIC) { it.sessionMode }
	val isContinuous = sessionMode == SessionMode.CONTINUOUS
	val isMaintaining by fieldOf(viewModel.state, false) { it.isMaintaining }
	val maintenancePreparing by fieldOf(viewModel.state, false) { it.maintenancePreparing }
	// Role-play scenes only add a menu entry here; the card and the scene are
	// read by the request builder, never by this screen.
	val roleplayCharacter by fieldOf(viewModel.state, false) { it.roleplayCharacter }
	val roleplayScene by fieldOf(viewModel.state, null) { it.roleplayScene }
	// A continuous conversation has no manual compaction to retry. A context
	// overflow there is the same dead end Retry already solves, and the copy
	// names the two things the user can actually do instead of offering a
	// button that would maintain a projection they do not manage.
	val contextOverflow = errorAction == ChatErrorAction.CompactAndRetry
	val visibleError = if (isContinuous && contextOverflow) {
		stringResource(R.string.chat_error_context_too_large)
	} else {
		error
	}
	val visibleErrorAction = if (isContinuous && contextOverflow) {
		ChatErrorAction.Retry
	} else {
		errorAction
	}

	// The composer's own inputs are read inside `ChatBottomBar`, not here: a
	// keystroke must invalidate the composer, and reading `input` in this scope
	// made it invalidate the whole screen body -- every `fieldOf`, both Scaffold
	// lambdas and the list's content DSL -- once per character.
	val configured by fieldOf(viewModel.state, false) { it.configured }
	val modelSupportsVision by fieldOf(viewModel.state, false) { it.modelSupportsVision }
	// The microphone is offered only when a transcription backend exists. A
	// row that would always fail is worse than no row; Settings is where the
	// backend is configured, and returning here re-runs this check.
	var voiceReady by remember { mutableStateOf(false) }
	LaunchedEffect(Unit) { voiceReady = viewModel.canTranscribe() }

	val listState = rememberLazyListState()
	// Finished Markdown is parsed off the composition, so a row entering the
	// list does not pay the parse on the frame that measures it. The map is read
	// by MessageBubble; a miss falls back to the immediate parse.
	val parsedMarkdown = remember { mutableStateMapOf<String, com.mikepenz.markdown.model.State>() }
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

	// The image picker: ACTION_OPEN_DOCUMENT with EXTRA_ALLOW_MULTIPLE
	// (`OpenMultipleDocuments`), so one launch can return several images.
	//
	// The single-document contract was a silent one-image ceiling: its
	// `parseResult` returns only `intent.data` and ignores `clipData`, so on a
	// picker that still lets the user tick several files (HyperOS does) the rest
	// were dropped without a word. The composer has always held a list; the
	// contract was the limit.
	//
	// Names and MIME types are resolved off the main thread: a large pick is one
	// ContentResolver round trip per image.
	val importer = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenMultipleDocuments(),
	) { uris ->
		if (uris.isNotEmpty()) {
			scope.launch {
				val picked = withContext(Dispatchers.IO) {
					uris.map { uri ->
						PickedImage(
							displayName = queryImageName(ctx, uri),
							mimeType = ctx.contentResolver.getType(uri) ?: "image/*",
							open = {
								ctx.contentResolver.openInputStream(uri)
									?: error("could not open the selected image")
							},
						)
					}
				}
				viewModel.attachImages(picked)
			}
		}
	}

	// The camera writes one photo into app cache through a FileProvider. The
	// name is saveable because the camera activity routinely outlives this
	// process: on return the launcher is recreated with no `remember` left, and
	// the result is still delivered to it.
	var captureName by rememberSaveable { mutableStateOf<String?>(null) }
	val camera = rememberLauncherForActivityResult(
		ActivityResultContracts.TakePicture(),
	) { saved ->
		val name = captureName
		if (name != null) {
			captureName = null
			val file = CameraCapture.file(ctx, name)
			// A cancelled camera leaves the empty file behind. A camera that
			// reports success without writing is the same thing from here, and
			// must not become a zero-byte attachment.
			if (!saved || file.length() == 0L) {
				file.delete()
				if (saved) {
					Diag.w("attach", "camera reported success with no bytes")
					Toast.makeText(ctx, R.string.chat_camera_empty, Toast.LENGTH_LONG).show()
				}
			} else {
				Diag.i("attach", "camera captured bytes=${file.length()}")
				viewModel.attachImage(
					displayName = CameraCapture.DISPLAY_NAME,
					mimeType = CameraCapture.MIME_TYPE,
					open = { FileInputStream(file) },
					onFinished = { file.delete() },
				)
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
	// Conversation upload is a separate, explicit egress from the diagnostics
	// upload: the body is the transcript. The target is read when the dialog
	// opens so the preview can name it, and editing Settings afterwards does
	// not silently retarget an open confirmation.
	var uploadTarget by remember { mutableStateOf<String?>(null) }
	var showUploadDialog by remember { mutableStateOf(false) }
	var uploadIncludeTools by remember { mutableStateOf(true) }
	var uploadBusy by remember { mutableStateOf(false) }
	var uploadError by remember { mutableStateOf<String?>(null) }
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

	// A conversation bundle is a zip the app writes itself and can import back
	// on another device. It is a separate menu item rather than an option in the
	// Markdown dialog: the two exports have different formats and destinations,
	// and the bundle is the only one that round-trips.
	var showBundleExportResult by remember { mutableStateOf(false) }
	var bundleExportError by remember { mutableStateOf<String?>(null) }
	val bundleExporter = rememberLauncherForActivityResult(
		ActivityResultContracts.CreateDocument("application/zip"),
	) { uri ->
		if (uri != null) {
			exporting = true
			Toast.makeText(ctx, R.string.export_working, Toast.LENGTH_SHORT).show()
			viewModel.exportConversationBundle(uri) { error ->
				exporting = false
				bundleExportError = error
				showBundleExportResult = true
			}
		}
	}

	// A share is a read grant on a cache copy. The Markdown is written before
	// the chooser opens, so a failed write cannot hand the user a chooser for a
	// file that does not exist, and the file is not the diagnostics provider's
	// or the camera's.
	fun shareCurrentConversation(includeTools: Boolean) {
		scope.launch {
			viewModel.buildConversationMarkdown(includeTools)
				.onSuccess { markdown ->
					val file = withContext(Dispatchers.IO) {
						runCatching {
							ConversationShare.newFile(
								ctx,
								exportFileName(displayTitle, "md"),
							).apply { writeText(markdown) }
						}
					}.getOrNull()
					if (file == null) {
						Toast.makeText(ctx, R.string.export_share_failed, Toast.LENGTH_LONG).show()
					} else {
						shareConversationFile(ctx, file, displayTitle)
					}
				}
				.onFailure { failure ->
					Diag.w("export", "share markdown failed", failure)
					Toast.makeText(
						ctx,
						ctx.getString(
							R.string.export_share_failed_detail,
							failure.message ?: ctx.getString(R.string.export_error_unknown),
						),
						Toast.LENGTH_LONG,
					).show()
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
	// The bottom strip only needs to know whether the transcript is empty, not
	// every token in it. Reading the size keeps the demo strip correct without
	// subscribing the composer to the streaming list.
	val messageCount by fieldOf(viewModel.state, 0) { it.messages.size }
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
	val downloadAllowed by fieldOf(viewModel.state, false) { it.downloadAllowed }
	val downloadEnabled by fieldOf(viewModel.state, false) { it.downloadEnabled }
	val sshHosts by fieldOf(viewModel.state, emptyList()) { it.sshHosts }
	val sshHostIds by fieldOf(viewModel.state, emptyList()) { it.sshHostIds }
	val suggestionsEnabled by fieldOf(viewModel.state, false) { it.suggestionsEnabled }
	val bilibiliEnabled by fieldOf(viewModel.state, false) { it.bilibiliEnabled }
	val bilibiliLoggedIn by fieldOf(viewModel.state, false) { it.bilibiliLoggedIn }
	val agentTools by fieldOf(viewModel.state, null) { it.agentTools }
	val conversationTools by fieldOf(viewModel.state, null) { it.conversationTools }
	val toolsKnown by fieldOf(viewModel.state, false) { it.toolsKnown }
	val workspaceInstructionsPinned by fieldOf(viewModel.state, false) {
		it.workspaceInstructionsPinned
	}
	val workspaceInstructionsChars by fieldOf(viewModel.state, 0) {
		it.workspaceInstructionsChars
	}
	val toolPlan = remember(
		agentTools,
		conversationTools,
		sshToolEnabled,
		suggestionsEnabled,
		bilibiliEnabled,
		bilibiliLoggedIn,
		downloadEnabled,
	) {
		conversationToolPlan(
			sshEnabled = sshToolEnabled,
			agentTools = agentTools,
			conversationTools = conversationTools,
			workspaceToolsEnabled = AppCapabilities.workspaceOk,
			suggestionsEnabled = suggestionsEnabled,
			bilibiliEnabled = bilibiliEnabled,
			bilibiliLoggedIn = bilibiliLoggedIn,
			downloadEnabled = downloadEnabled,
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
	val pendingDownloadConsent by fieldOf(viewModel.state, null) { it.pendingDownloadConsent }
	val workspaceName by fieldOf(viewModel.state, "") { it.workspaceName }
	val workspaceId by fieldOf(viewModel.state, null) { it.workspaceId }
	val workspaceIsPermanent by fieldOf(viewModel.state, false) { it.workspaceIsPermanent }
	var showChatMenu by remember { mutableStateOf(false) }
	var showGenerateImage by remember { mutableStateOf(false) }
	var showDecision by remember { mutableStateOf(false) }
	// The reply that the speech dialog was opened for. Null means closed; an
	// empty string is the chat-menu entry with no text to start from.
	var speechDraft by remember { mutableStateOf<String?>(null) }
	// Reply read-aloud. A speaker tap starts playback; once the one-time paid
	// notice has been confirmed there is no dialog in the way. The player lives
	// here rather than in the message row so the message icon and the mini bar
	// above the composer share one playback and one stop control.
	val speechContext = LocalContext.current
	var speechConsentFor by remember { mutableStateOf<ChatMessageUi?>(null) }
	var speechVoices by remember { mutableStateOf<List<String>>(emptyList()) }
	var speechVoice by remember { mutableStateOf<String?>(null) }
	var speechConsentConfirmed by remember { mutableStateOf(true) }
	var speakingReply by remember { mutableStateOf<ChatMessageUi?>(null) }
	var speechPaused by remember { mutableStateOf(false) }
	var speechPositionMs by remember { mutableStateOf(0) }
	var speechDurationMs by remember { mutableStateOf(0) }
	var speechCaptionText by remember { mutableStateOf("") }
	var speechCaptions by remember { mutableStateOf<List<SpeechCaptions.Segment>>(emptyList()) }
	var speechCaption by remember { mutableStateOf<String?>(null) }
	// Non-null while the user is dragging the progress slider, in 0f..1f.
	var speechScrubFraction by remember { mutableStateOf<Float?>(null) }
	var autoReadReplies by remember { mutableStateOf(false) }
	// Synthesis is owned by the view model so that leaving this screen does not
	// cancel a request that has already been billed. This screen only renders
	// its state and starts playback when the completion event arrives while it
	// is watching.
	val speechPreparingId by viewModel.speechPreparing.collectAsStateWithLifecycle()
	val cachedSpeechIds by viewModel.cachedSpeech.collectAsStateWithLifecycle()
	val speechPlayer = remember { WorkspaceAudioPlayer(speechContext) }
	// The lock-screen/notification side of the same playback. It lives and dies
	// with this composition, so its controls cannot outlive the audio.
	val speechSession = remember { SpeechMediaSession(speechContext) }

	fun speechToast(text: String) {
		Toast.makeText(speechContext, text, Toast.LENGTH_LONG).show()
	}

	fun stopTalking() {
		speechPaused = false
		speechPositionMs = 0
		speechDurationMs = 0
		speechCaptionText = ""
		speechCaptions = emptyList()
		speechCaption = null
		speechScrubFraction = null
		speechPlayer.stop()
		speechSession.stop()
		speakingReply = null
	}

	fun captionAt(positionMs: Int): String? =
		SpeechCaptions.segmentAt(speechCaptions, positionMs)?.text

	fun pauseTalking() {
		if (speakingReply == null || speechPaused) return
		if (speechPlayer.pause()) {
			speechPaused = true
			speechPositionMs = speechPlayer.positionMs()
			speechCaption = captionAt(speechPositionMs)
			speechSession.setPaused(true, speechPositionMs, speechCaption)
		}
	}

	fun resumeTalking() {
		if (!speechPaused) return
		if (speechPlayer.resume()) {
			speechPaused = false
			speechPositionMs = speechPlayer.positionMs()
			speechCaption = captionAt(speechPositionMs)
			speechSession.setPaused(false, speechPositionMs, speechCaption)
		}
	}

	/** Seek from either the in-app progress row or the lock-screen scrubber. */
	fun seekTalking(targetMs: Int) {
		val duration = speechDurationMs
		val bounded = if (duration > 0) targetMs.coerceIn(0, duration) else targetMs.coerceAtLeast(0)
		if (!speechPlayer.seekTo(bounded)) return
		speechPositionMs = bounded
		speechScrubFraction = null
		speechCaption = captionAt(bounded)
		speechSession.progress(bounded, duration, speechCaption)
	}

	fun playTalking(message: ChatMessageUi, audio: ChatViewModel.GeneratedAudio) {
		// readAloud remembers the resolved default voice; mirror it here so the
		// next tap uses the same voice rather than resolving it again.
		if (speechVoice == null) speechVoice = viewModel.speechVoice()
		val id = workspaceId
		if (id == null) {
			speechToast(playbackFailedText)
			return
		}
		speechPlayer.stop()
		speechPaused = false
		speechPositionMs = 0
		speechDurationMs = 0
		speechCaptionText = audio.speechText
		speechCaptions = emptyList()
		speechCaption = null
		speechScrubFraction = null
		speakingReply = message
		if (speechPlayer.play(id, audio.relativePath)) {
			speechSession.start(speechVoice.orEmpty())
		} else {
			speakingReply = null
			speechToast(playbackFailedText)
		}
	}

	/**
	 * Ask the view model to synthesize a reply.
	 *
	 * Deliberately not a coroutine in this composition: a conversation switch
	 * disposes this screen, and a request launched here would be cancelled
	 * after the user had already paid for it.
	 */
	fun prepareTalking(message: ChatMessageUi) {
		if (speechPreparingId != null) return
		viewModel.prepareSpeech(message.id, message.plainText)
	}

	fun refreshSpeechSetup() {
		scope.launch {
			val available = runCatching { viewModel.speechVoiceIds() }.getOrDefault(emptyList())
			if (available.isNotEmpty() || speechVoices.isEmpty()) speechVoices = available
			val stored = viewModel.speechVoice()
			speechVoice = stored?.takeIf { it in speechVoices } ?: speechVoices.firstOrNull()
		}
	}

	fun requestTalking(message: ChatMessageUi) {
		if (speakingReply?.id == message.id) {
			// The same control is pause/resume once the reply is the thing
			// playing; the mini bar has an explicit Stop.
			if (speechPaused) resumeTalking() else pauseTalking()
			return
		}
		// One synthesis at a time. The row with the request in flight shows a
		// spinner; a second tap cannot start a second paid request.
		if (speechPreparingId != null) return
		// The first composition races the conversation load, so the voice list
		// can still be empty here. Ask again at the point of use rather than
		// showing a chooser with nothing in it.
		refreshSpeechSetup()
		// The one-time notice is the paid disclosure. After it, reading a
		// reply is one tap, the way mature chat clients treat Read aloud.
		if (!speechConsentConfirmed) {
			speechConsentFor = message
			return
		}
		// Starting a second reply must silence the first immediately, not
		// leave it talking through the next synthesis.
		if (speakingReply != null && speakingReply?.id != message.id) {
			stopTalking()
		}
		prepareTalking(message)
	}

	// A synthesis finishes in the view model, not here. The event starts
	// playback only while this screen is composed to receive it: if the user
	// left the conversation, the file is cached and the reply is marked ready,
	// but audio does not start in a conversation nobody is looking at.
	LaunchedEffect(viewModel) {
		viewModel.speechEvents.collect { event ->
			when (event) {
				is ChatViewModel.SpeechEvent.Ready -> {
					val message = viewModel.state.value.messages
						.firstOrNull { it.id == event.messageId }
					if (message != null) playTalking(message, event.audio)
				}
				is ChatViewModel.SpeechEvent.Failed ->
					speechToast(event.message ?: speechFailedText)
			}
		}
	}

	// The cache name includes the voice, so the set of playable replies changes
	// when the voice does (including when the first tap resolves a default).
	LaunchedEffect(speechVoice) {
		viewModel.refreshCachedSpeech(speechVoice)
	}

	LaunchedEffect(viewModel) {
		speechVoices = viewModel.speechVoiceIds()
		speechVoice = viewModel.speechVoice()?.takeIf { it in speechVoices }
			?: speechVoices.firstOrNull()
		speechConsentConfirmed = viewModel.speechConsentShown()
		autoReadReplies = viewModel.autoReadReplies()
	}

	// Settings is a different screen; returning to the chat must pick up a
	// changed auto-read/voice choice without relying on the process restart.
	//
	// The same effect is the visibility signal the continuous-session scheduler
	// needs: it runs when the screen composes into a RESUMED activity, and its
	// pause/dispose block runs when the screen leaves -- another app screen or
	// the background. `collectAsStateWithLifecycle` is not a substitute: it
	// stops collecting, it does not tell the view model that this conversation
	// is unwatched, and maintenance must not spend while nobody is looking.
	// The key is the view model, not Unit. Opening another conversation from the
	// in-chat "+" swaps the view model without leaving this screen's composition;
	// a Unit key would leave the previous conversation marked visible and the new
	// one never marked visible, and maintenance would run under the wrong one.
	LifecycleResumeEffect(viewModel) {
		autoReadReplies = viewModel.autoReadReplies()
		refreshSpeechSetup()
		viewModel.onChatVisible()
		onPauseOrDispose { viewModel.onChatHidden() }
	}

	// Auto-read follows one turn: it fires only on the streaming -> finished
	// edge, so opening a conversation does not replay its last answer, and a
	// stopped or failed turn does not read a partial reply.
	LaunchedEffect(autoReadReplies) {
		if (!autoReadReplies) return@LaunchedEffect
		var wasStreaming = false
		var lastReadId: String? = null
		viewModel.state
			.map { state ->
				Triple(
					state.isStreaming,
					state.error,
					state.messages.lastOrNull { message ->
						!message.fromUser && !message.isToolResult &&
							message.plainText.isNotBlank()
					},
				)
			}
			.distinctUntilChanged { a, b ->
				a.first == b.first && a.second == b.second && a.third?.id == b.third?.id
			}
			.collect { frame ->
				val message = frame.third
				if (wasStreaming && !frame.first && frame.second == null &&
					message != null && message.error == null &&
					message.id != lastReadId &&
					speakingReply == null && speechPreparingId == null
				) {
					lastReadId = message.id
					// The Settings switch is the paid opt-in and the paid
					// disclosure; this path does not interrupt with the
					// one-time notice.
					speechConsentConfirmed = true
					viewModel.markSpeechConsent()
					prepareTalking(message)
				}
				wasStreaming = frame.first
			}
	}

	// One player and one media session, one owner. Releasing both when this
	// screen leaves is what stops audio on back navigation; the mini bar, the
	// message icon and the lock-screen controls are views of the same state.
	DisposableEffect(speechPlayer, speechSession) {
		speechPlayer.onDurationKnown = { duration ->
			if (duration > 0) {
				speechDurationMs = duration
				// Build the caption timeline once the true duration is known;
				// the estimated boundaries are all relative to it.
				speechCaptions = SpeechCaptions.segments(speechCaptionText, duration)
				speechCaption = captionAt(speechPlayer.positionMs())
				speechSession.progress(
					speechPlayer.positionMs(),
					duration,
					speechCaption,
				)
			}
		}
		speechPlayer.onFinished = {
			speechPaused = false
			speechPositionMs = 0
			speechDurationMs = 0
			speechCaptionText = ""
			speechCaptions = emptyList()
			speechCaption = null
			speechScrubFraction = null
			speakingReply = null
			speechSession.stop()
		}
		speechPlayer.onFailed = {
			speechPaused = false
			speechPositionMs = 0
			speechDurationMs = 0
			speechCaptionText = ""
			speechCaptions = emptyList()
			speechCaption = null
			speechScrubFraction = null
			speakingReply = null
			speechSession.stop()
			Toast.makeText(speechContext, playbackFailedText, Toast.LENGTH_LONG).show()
		}
		speechSession.onPause = { pauseTalking() }
		speechSession.onPlay = { resumeTalking() }
		speechSession.onStop = { stopTalking() }
		// The lock-screen scrubber is a seek, not a new read.
		speechSession.onSeek = { position -> seekTalking(position.toInt()) }
		onDispose {
			speechPlayer.release()
			speechSession.release()
		}
	}

	// One ticker feeds both the in-app progress row and the media session; the
	// lock screen and notification therefore move with the audio instead of
	// showing the position from the moment playback started.
		LaunchedEffect(speakingReply, speechPaused) {
		if (speakingReply == null || speechPaused) return@LaunchedEffect
		while (true) {
			val position = speechPlayer.positionMs()
			val duration = speechPlayer.durationMs()
			if (speechScrubFraction == null) speechPositionMs = position
			if (duration > 0) speechDurationMs = duration
			val caption = captionAt(position)
			if (caption != speechCaption) speechCaption = caption
			speechSession.progress(position, duration, caption)
			delay(1000)
		}
	}

	// A new turn is a new thing to listen to; the old reply stops rather than
	// talking over the stream.
	LaunchedEffect(isStreaming) {
		if (isStreaming) stopTalking()
	}
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
	var showSessionMemory by remember { mutableStateOf(false) }
	var sessionMemory by remember { mutableStateOf<SessionMemoryUi?>(null) }
	var sessionMemoryLoading by remember { mutableStateOf(false) }
	var sessionMemoryFailed by remember { mutableStateOf(false) }
	var showRebuildBriefConfirm by remember { mutableStateOf(false) }
	var showStopMaintainingConfirm by remember { mutableStateOf(false) }
	var showEnableContinuousConfirm by remember { mutableStateOf(false) }
	// Read on open rather than kept as a flow: the rows change only while
	// maintenance runs, and a flow would republish the whole brief on every
	// maintenance tick for a sheet that is usually closed.
	LaunchedEffect(showSessionMemory) {
		if (!showSessionMemory) return@LaunchedEffect
		sessionMemory = null
		sessionMemoryFailed = false
		sessionMemoryLoading = true
		val loaded = runCatching { viewModel.sessionMemory() }
			.onFailure { Diag.w("session", "could not read the session memory", it) }
			.getOrNull()
		sessionMemory = loaded
		sessionMemoryFailed = loaded == null
		sessionMemoryLoading = false
	}
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
	var showSceneDialog by remember { mutableStateOf(false) }
	var showSshHostsDialog by remember { mutableStateOf(false) }
	var showRenameDialog by remember { mutableStateOf(false) }
	var renameInput by remember { mutableStateOf("") }
	var showSaveWorkspaceDialog by remember { mutableStateOf(false) }
	var workspaceNameInput by remember { mutableStateOf("") }

	// Reported upward so the app's single Back handler knows a dialog is up.
	// Both this and the approval gate are transient questions that Back should
	// answer by dismissing, not by navigating away from.
	val anyDialogOpen = showChatMenu || searchOpen || showModelPicker || pendingApproval != null ||
		pendingSshConsent != null || pendingDownloadConsent != null ||
		rollbackTarget != null || showRewindDialog ||
		showCompactPreview || showCompactionSummary ||
		showSnapshotDialog || showToolLimitDialog || showRenameDialog || showWireDialog ||
		showSessionMemory || showRebuildBriefConfirm || showStopMaintainingConfirm ||
		showEnableContinuousConfirm ||
		showApprovalHistory || showCapabilityDetails ||
		turnWireMessages != null || showToolsDialog || showTurnPicker ||
			showSaveWorkspaceDialog || showExportDialog || showSshHostsDialog ||
			showBundleExportResult || showSceneDialog
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
	// A user drag always ends following, even when the fling's nested-scroll
	// event does not look like one.
	//
	// `onPreScroll` above is the primary signal, but it depends on a non-zero
	// vertical delta and on a source other than `SideEffect`. A fling that is
	// reported with a zero first delta, or a drag whose nested event arrives
	// from a source the connection treats as ours, left `followLatest` true.
	// Then a reply finishing while the reader was scrolled up changed the
	// layout, the observer below saw "not at bottom", and pulled the list to
	// the newest turn -- the reported jump to the next/previous turn. The
	// list's own interaction source is the reliable signal: programmatic
	// `scrollToItem`/`scrollBy` do not emit a `DragInteraction`.
	LaunchedEffect(listState) {
		listState.interactionSource.interactions.collect { interaction ->
			if (interaction is DragInteraction.Start) {
				followLatest = false
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
		Diag.i(
			"scroll",
			"tail last=${Diag.shortId(lastMessageId)} size=${messages.size} " +
				"streaming=${viewModel.state.value.isStreaming}",
		)
		lastAssistantId = messages.lastOrNull { !it.fromUser && !it.isToolResult }?.id
		// A message inserted mid-turn is not the turn's start: the retry and
		// rewind controls belong to the row the turn began with.
		lastUserId = messages.lastOrNull { it.isTurnRoot }?.id
	}
	LaunchedEffect(isStreaming) {
		Diag.i(
			"scroll",
			"streaming=$isStreaming last=${Diag.shortId(lastMessageId)} " +
				"first=${listState.firstVisibleItemIndex} follow=$followLatest",
		)
	}

	// One remembered object for every composer callback. The content lambda
	// still recomposes on a streamed token, but this object does not change, so
	// `ChatBottomBar` can skip the rebuild instead of invalidating the text
	// field and the send button several times a second.
	val currentOnOpenSettings by rememberUpdatedState(onOpenSettings)
	val composerActions = remember(viewModel, importer, camera, compaction) {
		ChatComposerActions(
			onSend = {
				// Sending is an explicit request to show the new turn. If the
				// reader had scrolled up, re-arm following before it is laid out.
				followLatest = true
				viewModel.send()
			},
			onStop = viewModel::stop,
			onSendInsert = viewModel::sendInsert,
			onCancelQueued = viewModel::cancelQueued,
			onSendQueued = viewModel::sendQueued,
			onInsertQueued = viewModel::insertQueued,
			onInputChange = viewModel::onInputChange,
			onAttachFromFiles = { importer.launch(arrayOf("image/*")) },
			onCapturePhoto = {
				// The camera writes into a fresh cache file, never over the
				// previous result: a slow attach may still be reading that one.
				val file = try {
					CameraCapture.newFile(ctx)
				} catch (failure: Exception) {
					Diag.w("attach", "camera temp file could not be created", failure)
					Toast.makeText(ctx, R.string.chat_camera_failed, Toast.LENGTH_LONG).show()
					null
				}
				if (file != null) {
					captureName = file.name
					try {
						camera.launch(CameraCapture.uri(ctx, file))
					} catch (failure: Exception) {
						// No camera app, or a provider the platform refused.
						// Report it here rather than leaving an empty composer
						// and a cache file that will never be deleted.
						captureName = null
						file.delete()
						Diag.w("attach", "camera launch failed", failure)
						Toast.makeText(ctx, R.string.chat_camera_failed, Toast.LENGTH_LONG).show()
					}
				}
			},
			onRemoveAttachment = viewModel::removeAttachment,
			onTranscribeVoice = viewModel::transcribeVoice,
			onVoiceText = viewModel::appendVoiceText,
			onToggleContextDetail = { showContextDetail = !showContextDetail },
			onCompact = {
				// The first compaction keeps two turns for continuity; a second
				// pass keeps one, which is the escape hatch when the recent
				// turns are themselves huge.
				pendingCompaction = viewModel.compactionPlan(if (compaction == null) 2 else 1)
				showCompactPreview = true
			},
			onViewCompaction = { showCompactionSummary = true },
			onClearCompaction = viewModel::clearCompaction,
			onClearError = viewModel::clearError,
			onRetry = viewModel::retryLastTurn,
			onCompactAndRetry = viewModel::compactAndRetry,
			onOpenSettings = { currentOnOpenSettings() },
			onContinue = {
				followLatest = true
				viewModel.continueInterruptedTurn()
			},
			onEnableDemo = viewModel::enableDemoMode,
		)
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
							// The label is what TalkBack says; the chevron is the
							// visual cue that this text is a control. Without
							// either, the only way to change the model was to
							// guess that the title was tappable.
							modifier = Modifier.clickable(onClickLabel = changeModelLabel) {
								showModelPicker = true
							},
							verticalArrangement = Arrangement.spacedBy(2.dp),
						) {
							Row(verticalAlignment = Alignment.CenterVertically) {
								Text(
									text = displayTitle,
									style = MaterialTheme.typography.titleMedium,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
									modifier = Modifier.weight(1f, fill = false),
								)
								Spacer(Modifier.width(4.dp))
								Icon(
									Icons.Filled.KeyboardArrowDown,
									contentDescription = null,
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.size(16.dp),
								)
							}
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
						val collapseHeaderActions = currentWindowFrame().usesHeaderOverflow
						if (!collapseHeaderActions) {
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
						}
						IconButton(onClick = onOpenFiles) {
							Icon(
								Icons.Outlined.FolderOpen,
								contentDescription = stringResource(R.string.files_title),
								modifier = Modifier.size(20.dp),
							)
						}
						if (!collapseHeaderActions) {
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
						}
						IconButton(onClick = { showChatMenu = true }) {
							Icon(
								imageVector = Icons.Filled.MoreVert,
								contentDescription = stringResource(R.string.chat_more),
								modifier = Modifier.size(20.dp),
							)
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
			LaunchedEffect(messages) {
				val texts = messages.asSequence()
					// Only rows that can read this cache: `MarkdownText` takes the
					// incremental path whenever `deltas` is non-null and returns
					// before it looks at `parsed`. A streaming reply's text grows
					// on every publish, so it is always "missing", and parsing
					// each intermediate prefix here was pure waste -- the whole
					// answer was transformed and parsed again every 40 ms, and
					// every prefix stayed in the map for the life of the screen.
					.filter { !it.fromUser && !it.isToolResult && it.deltas == null }
					.flatMap { it.parts.asSequence() }
					.filter { it.kind == AssistantPart.Kind.Text && it.text.isNotBlank() }
					.map { it.text }
					.distinct()
					.toList()
				val missing = texts.filter { !parsedMarkdown.containsKey(it) }
				val parsed = withContext(Dispatchers.Default) {
					missing.mapNotNull { text ->
						runCatching {
							text to parseMarkdown(LatexMarkdown.transform(text, finished = true))
						}.getOrNull()
					}
				}
				parsed.forEach { (text, state) -> parsedMarkdown[text] = state }
			}
			// The turn index is derived once per publish in the view model, off
			// the main thread. Reading the same list here would walk it three
			// times for every streamed token. It comes from the same `messages`
			// list, so the banner and footers cannot describe a different
			// transcript.
			val transcriptIndex by viewModel.transcriptIndex.collectAsStateWithLifecycle()
			val interrupted = transcriptIndex.interrupted
			val turns = transcriptIndex.turns
			val userTurns = transcriptIndex.userTurns
			// The banners above the transcript occupy list items of their own;
			// every index taken from `messages` has to add them back. The count
			// mirrors the LazyColumn's render conditions exactly -- see
			// `transcriptLeadingItems`. It once counted only the two classic
			// banners, so while the maintenance row was visible the turn badge
			// read one turn ahead and every jump landed on the banner row
			// instead of the message.
			val leadingItems = transcriptLeadingItems(
				continuous = isContinuous,
				compaction = compaction != null,
				compacting = isCompacting,
				maintaining = isMaintaining || maintenancePreparing,
			)
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
				Diag.i(
					"scroll",
					"jump effect target=${Diag.shortId(targetId)} " +
						"first=${listState.firstVisibleItemIndex} " +
						"total=${listState.layoutInfo.totalItemsCount}",
				)
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
					Diag.i(
						"scroll",
						"jump apply target=${Diag.shortId(targetId)} " +
							"index=$index leading=$leadingItems",
					)
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
				Diag.i(
					"scroll",
					"focus effect target=${Diag.shortId(targetId)} " +
						"first=${listState.firstVisibleItemIndex}",
				)
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
					Diag.i(
						"scroll",
						"focus apply target=${Diag.shortId(targetId)} " +
							"index=$index leading=$leadingItems",
					)
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
							onClick = {
								Diag.i(
									"scroll",
									"turn picker open turn=${currentTurnIndex + 1}/${userTurns.size} " +
										"first=${listState.firstVisibleItemIndex}",
								)
								showTurnPicker = true
							},
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
					if (!isContinuous) {
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
					}
					if (isMaintaining || maintenancePreparing) {
						item(key = "maintaining") {
							Text(
								text = stringResource(
									if (isMaintaining) {
										R.string.chat_maintaining
									} else {
										R.string.chat_maintenance_preparing
									},
								),
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
						// The callbacks below run when the user taps, long after
						// this composition. Reading `messages` directly made them
						// new instances on every streamed tick, and a row whose
						// lambda arguments change cannot be skipped -- so one
						// reply growing recomposed every visible row. The holder
						// keeps the latest list without making the lambda depend
						// on it.
						val currentMessages by rememberUpdatedState(messages)
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
							parsedMarkdown = parsedMarkdown,
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
								turn?.let { turnWireMessages = it.messagesIn(currentMessages) }
							},
							onShowRewind = {
								val target = currentMessages.lastOrNull {
									it.fromUser && it.id == lastUserId
								}
								rewindEditTarget = target
								rewindEditFiles = if (target == null) {
									emptyList()
								} else {
									currentMessages
										.dropWhile { it.id != target.id }
										.lastOrNull { !it.fromUser && !it.isToolResult }
										?.changedFiles
										.orEmpty()
								}
								showRewindDialog = true
							},
							onRetry = viewModel::retryLastTurn,
							onUseSuggestion = viewModel::useSuggestion,
							speakState = when {
								speakingReply?.id == message.id && speechPaused ->
									ReplySpeechState.Paused
								speakingReply?.id == message.id ->
									ReplySpeechState.Playing
								speechPreparingId == message.id ->
									ReplySpeechState.Preparing
								else -> ReplySpeechState.Idle
							},
							speechCached = message.id in cachedSpeechIds,
							onSpeakReply = { requestTalking(message) },
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
									Diag.i(
										"scroll",
										"turn picker choose=${index + 1}/${userTurns.size} " +
											"target=${Diag.shortId(turn.messageId)} " +
											"first=${listState.firstVisibleItemIndex}",
									)
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
						Diag.i(
							"scroll",
							"search jump target=${Diag.shortId(match.messageId)} " +
								"first=${listState.firstVisibleItemIndex}",
						)
						closeSearch()
						jumpToMessageId = match.messageId
					},
					onDismiss = { closeSearch() },
				)
			}

			if (speakingReply != null) {
				SpeechPlayerBar(
					paused = speechPaused,
					caption = speechCaption,
					onPauseResume = { if (speechPaused) resumeTalking() else pauseTalking() },
					onStop = { stopTalking() },
					positionMs = if (speechScrubFraction != null && speechDurationMs > 0) {
						((speechScrubFraction ?: 0f) * speechDurationMs).toInt()
					} else {
						speechPositionMs
					},
					durationMs = speechDurationMs,
					onScrub = { fraction -> speechScrubFraction = fraction },
					onScrubFinished = {
						val fraction = speechScrubFraction
						if (fraction != null && speechDurationMs > 0) {
							seekTalking((fraction * speechDurationMs).toInt())
						} else {
							speechScrubFraction = null
						}
					},
				)
			}

			ChatBottomBar(
				configured = configured,
				messageCount = messageCount,
				interrupted = interrupted,
				error = visibleError,
				errorAction = visibleErrorAction,
				state = viewModel.state,
				isStreaming = isStreaming,
				isCompacting = isCompacting,
				modelSupportsVision = modelSupportsVision,
				voiceReady = voiceReady,
				usage = usage,
				showContextDetail = showContextDetail,
				compaction = compaction,
				isContinuous = isContinuous,
				maintenancePreparing = maintenancePreparing,
				showComposer = !searchOpen,
				actions = composerActions,
			)
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

	speechDraft?.let { initialText ->
		SpeechDialog(
			initialText = initialText,
			initialVoice = speechVoice,
			workspaceId = workspaceId,
			onSpeak = { text, voice ->
				val result = viewModel.synthesize(text, voice)
				if (result.isSuccess) {
					voice?.let {
						viewModel.setSpeechVoice(it)
						speechVoice = it
					}
				}
				result
			},
			onVoices = { viewModel.speechVoiceIds() },
			onTranscribe = { path -> viewModel.transcribe(path) },
			onDismiss = { speechDraft = null },
		)
	}

	speechConsentFor?.let { message ->
		SpeechReadConsentDialog(
			voices = speechVoices,
			initialVoice = speechVoice,
			onConfirm = { chosen ->
				viewModel.markSpeechConsent()
				speechConsentConfirmed = true
				if (chosen != null) {
					speechVoice = chosen
					viewModel.setSpeechVoice(chosen)
				}
				speechConsentFor = null
				prepareTalking(message)
			},
			onDismiss = { speechConsentFor = null },
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

	if (showChatMenu) {
		ChatWorkbenchDialog(
			title = displayTitle,
			workspaceName = workspaceName.ifBlank { displayTitle },
			workspaceIsPermanent = workspaceIsPermanent,
			isContinuous = isContinuous,
			isStreaming = isStreaming,
			isMaintaining = isMaintaining,
			maintenancePreparing = maintenancePreparing,
			exporting = exporting,
			uploadBusy = uploadBusy,
			toolsKnown = toolsKnown,
			selectedToolCount = toolPlan.selected.size,
			availableToolCount = toolPlan.available.size,
			toolRoundLimit = toolRoundLimit,
			roleplayActive = roleplayCharacter || roleplayScene != null,
			workspaceInstructionsPinned = workspaceInstructionsPinned,
			workspaceInstructionsChars = workspaceInstructionsChars,
			hasWire = wire != null,
			snapshotStats = snapshotStats,
			sshToolEnabled = sshToolEnabled,
			sshAllowed = sshAllowed,
			downloadEnabled = downloadEnabled,
			downloadAllowed = downloadAllowed,
			showHeaderOverflowActions = currentWindowFrame().usesHeaderOverflow,
			creatingConversation = creatingConversation,
			onDismiss = { showChatMenu = false },
			onOpenSearch = {
				showChatMenu = false
				searchOpen = true
			},
			onNewConversation = {
				showChatMenu = false
				if (!creatingConversation) {
					creatingConversation = true
					viewModel.createNewConversation { id ->
						creatingConversation = false
						if (id != null) onOpenConversation(id)
					}
				}
			},
			onOpenTools = {
				showChatMenu = false
				showToolsDialog = true
			},
			onOpenToolRounds = {
				showChatMenu = false
				showToolLimitDialog = true
			},
			onOpenSceneSettings = {
				showChatMenu = false
				showSceneDialog = true
			},
			onStopContinuous = {
				showChatMenu = false
				showStopMaintainingConfirm = true
			},
			onEnableContinuous = {
				showChatMenu = false
				showEnableContinuousConfirm = true
			},
			onReloadWorkspaceInstructions = {
				showChatMenu = false
				viewModel.reloadWorkspaceInstructions()
			},
			onRenameConversation = {
				showChatMenu = false
				renameInput = displayTitle
				showRenameDialog = true
			},
			onSaveWorkspacePermanent = {
				showChatMenu = false
				workspaceNameInput = workspaceName.ifBlank { displayTitle }
				showSaveWorkspaceDialog = true
			},
			onOpenSshHosts = {
				showChatMenu = false
				showSshHostsDialog = true
			},
			onRevokeSsh = {
				showChatMenu = false
				viewModel.revokeSshAccess()
			},
			onRevokeDownload = {
				showChatMenu = false
				viewModel.revokeDownloadAccess()
			},
			onOpenSessionMemory = {
				showChatMenu = false
				showSessionMemory = true
			},
			onOpenSnapshots = {
				showChatMenu = false
				showSnapshotDialog = true
			},
			onPreviewNextRequest = {
				showChatMenu = false
				viewModel.previewNextRequest()
			},
			onInspectLastRequest = {
				showChatMenu = false
				showWireDialog = true
			},
			onOpenApprovalHistory = {
				showChatMenu = false
				showApprovalHistory = true
			},
			onOpenProviderKeys = {
				showChatMenu = false
				onOpenSettings()
			},
			onOpenFiles = {
				showChatMenu = false
				onOpenFiles()
			},
			onExportChat = {
				showChatMenu = false
				exportIncludeTools = true
				showExportDialog = true
			},
			onExportBundle = {
				showChatMenu = false
				bundleExporter.launch(
					exportFileName(displayTitle, "inhuman.zip"),
				)
			},
			onUploadConversation = {
				showChatMenu = false
				uploadTarget = viewModel.conversationUploadTarget()
				uploadIncludeTools = false
				uploadError = null
				showUploadDialog = true
			},
			onExportWorkspaceZip = {
				showChatMenu = false
				workspaceExporter.launch(
					exportFileName(
						workspaceName.ifBlank { displayTitle },
						"zip",
					),
				)
			},
			onGenerateImage = {
				showChatMenu = false
				showGenerateImage = true
			},
			onGenerateVideo = {
				showChatMenu = false
				showVideo = true
			},
			onTextToSpeech = {
				showChatMenu = false
				speechDraft = ""
			},
			onTranscribeAudio = {
				showChatMenu = false
				audioImporter.launch(arrayOf("audio/*"))
			},
			onDecision = {
				showChatMenu = false
				showDecision = true
			},
		)
	}

	// The approval gate.
	// Rendered as a dialog over the transcript rather than inline, because it
	// blocks the turn: nothing else happens until it is answered, and an inline
	// card could be scrolled past without noticing that the run has stopped.
	//
	// The text is written for a person, not for the model: a redacted
	// `details` summary leads when the tool provides one, and the raw JSON
	// arguments are printed below it on purpose -- they are the one place a
	// careful reader can see exactly what the call will do, so hiding them
	// would leave the summary as the only thing between the user and a yes.
	// docs/06 "The approval dialog" keeps the ruling.
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

	pendingDownloadConsent?.let { consent ->
		DownloadConsentDialog(
			consent = consent,
			onAllow = viewModel::allowDownloadConsent,
			onDeny = viewModel::denyDownloadConsent,
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

	if (!isContinuous && showCompactPreview) {
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
		if (!isContinuous && showCompactionSummary) {
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

	if (showSessionMemory) {
		SessionMemoryDialog(
			memory = sessionMemory,
			loading = sessionMemoryLoading,
			failed = sessionMemoryFailed,
			isContinuous = isContinuous,
			onDismiss = { showSessionMemory = false },
			onRebuild = {
				showSessionMemory = false
				showRebuildBriefConfirm = true
			},
			onStopMaintaining = {
				showSessionMemory = false
				showStopMaintainingConfirm = true
			},
			onEnableContinuous = {
				showSessionMemory = false
				showEnableContinuousConfirm = true
			},
			onSearch = {
				showSessionMemory = false
				searchOpen = true
			},
		)
	}

	if (showRebuildBriefConfirm) {
		AlertDialog(
			onDismissRequest = { showRebuildBriefConfirm = false },
			title = { Text(stringResource(R.string.session_memory_rebuild)) },
			text = { Text(stringResource(R.string.session_memory_rebuild_confirm)) },
			confirmButton = {
				TextButton(
					onClick = {
						showRebuildBriefConfirm = false
						viewModel.rebuildBrief()
					},
				) { Text(stringResource(R.string.session_memory_rebuild)) }
			},
			dismissButton = {
				TextButton(onClick = { showRebuildBriefConfirm = false }) {
					Text(stringResource(R.string.common_cancel))
				}
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	if (showStopMaintainingConfirm) {
		AlertDialog(
			onDismissRequest = { showStopMaintainingConfirm = false },
			title = { Text(stringResource(R.string.chat_continuous_stop)) },
			text = { Text(stringResource(R.string.chat_continuous_stop_confirm)) },
			confirmButton = {
				TextButton(
					onClick = {
						showStopMaintainingConfirm = false
						viewModel.stopMaintaining()
					},
				) { Text(stringResource(R.string.chat_continuous_stop)) }
			},
			dismissButton = {
				TextButton(onClick = { showStopMaintainingConfirm = false }) {
					Text(stringResource(R.string.common_cancel))
				}
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	if (showEnableContinuousConfirm) {
		AlertDialog(
			onDismissRequest = { showEnableContinuousConfirm = false },
			title = { Text(stringResource(R.string.chat_continuous_enable)) },
			text = { Text(stringResource(R.string.chat_continuous_enable_confirm)) },
			confirmButton = {
				TextButton(
					onClick = {
						showEnableContinuousConfirm = false
						viewModel.enableContinuousSession()
					},
				) { Text(stringResource(R.string.chat_continuous_enable)) }
			},
			dismissButton = {
				TextButton(onClick = { showEnableContinuousConfirm = false }) {
					Text(stringResource(R.string.common_cancel))
				}
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
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

	if (showSceneDialog) {
		SceneSettingsDialog(
			initial = roleplayScene,
			streaming = isStreaming,
			onSave = { scene ->
				viewModel.setScene(scene)
				showSceneDialog = false
			},
			onReloadPersona = { viewModel.reloadRoleplayPersona() },
			onDismiss = { showSceneDialog = false },
		)
	}

	if (showSshHostsDialog) {
		SshHostsDialog(
			hosts = sshHosts,
			selected = sshHostIds,
			onApply = { ids ->
				viewModel.setSshHosts(ids)
				showSshHostsDialog = false
			},
			onDismiss = { showSshHostsDialog = false },
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
				Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
					TextButton(
						onClick = {
							showExportDialog = false
							pendingExportTools = exportIncludeTools
							conversationExporter.launch(exportFileName(displayTitle, "md"))
						},
					) { Text(stringResource(R.string.export_conversation_action)) }
					TextButton(
						onClick = {
							showExportDialog = false
							shareCurrentConversation(exportIncludeTools)
						},
					) { Text(stringResource(R.string.export_share_action)) }
				}
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

	if (showBundleExportResult) {
		AlertDialog(
			onDismissRequest = { showBundleExportResult = false },
			title = { Text(stringResource(R.string.chat_export_bundle)) },
			text = {
				Text(
					text = bundleExportError?.let {
						stringResource(R.string.chat_export_bundle_failed, it)
					} ?: stringResource(R.string.chat_export_bundle_done),
				)
			},
			confirmButton = {
				TextButton(onClick = { showBundleExportResult = false }) {
					Text(stringResource(R.string.common_ok))
				}
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	if (showUploadDialog) {
		// Snapshot the target: the preview must name the server the upload will
		// actually use, even though Settings can be edited behind the dialog.
		val target = uploadTarget
		AlertDialog(
			onDismissRequest = { if (!uploadBusy) showUploadDialog = false },
			title = { Text(stringResource(R.string.chat_upload_title)) },
			text = {
				Column(
					modifier = Modifier.verticalScroll(rememberScrollState()),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Text(
						text = stringResource(R.string.chat_upload_body),
						style = MaterialTheme.typography.bodyMedium,
					)
					if (target == null) {
						Text(
							text = stringResource(R.string.chat_upload_no_server),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.error,
						)
					} else {
						Text(
							text = stringResource(R.string.chat_upload_target, target),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clickable(enabled = !uploadBusy) {
								uploadIncludeTools = !uploadIncludeTools
							},
						verticalAlignment = Alignment.CenterVertically,
					) {
						Checkbox(
							checked = uploadIncludeTools,
							onCheckedChange = { uploadIncludeTools = it },
							enabled = !uploadBusy,
						)
						Text(
							text = stringResource(R.string.export_include_tools),
							style = MaterialTheme.typography.bodyMedium,
						)
					}
					uploadError?.let { message ->
						Text(
							text = message,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.error,
						)
					}
				}
			},
			confirmButton = {
				TextButton(
					enabled = !uploadBusy && target != null,
					onClick = {
						uploadBusy = true
						uploadError = null
						viewModel.uploadConversation(uploadIncludeTools) { result ->
							uploadBusy = false
							result.onSuccess { saved ->
								showUploadDialog = false
								Toast.makeText(
									ctx,
									ctx.getString(R.string.chat_upload_ok, saved),
									Toast.LENGTH_LONG,
								).show()
							}.onFailure { failure ->
								uploadError = ctx.getString(
									R.string.chat_upload_failed,
									failure.message
										?: ctx.getString(R.string.export_error_unknown),
								)
							}
						}
					},
				) {
					Text(
						stringResource(
							if (uploadBusy) {
								R.string.chat_upload_working
							} else {
								R.string.chat_upload_action
							},
						),
					)
				}
			},
			dismissButton = {
				TextButton(
					onClick = { if (!uploadBusy) showUploadDialog = false },
					enabled = !uploadBusy,
				) { Text(stringResource(R.string.common_cancel)) }
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
								text = highlightSnippet(match.snippet, query),
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
 * The once-per-conversation URL-download acknowledgement.
 *
 * The first URL is shown whole, because it is the most concrete warning
 * available. The checkbox and the warning exist for the part the URL cannot
 * show: a URL is an outbound channel, and anything the model encodes into one
 * leaves the device when the request is made. Allowing here allows every later
 * `download_url` call in this conversation to follow ordinary tool policy.
 */
@Composable
private fun DownloadConsentDialog(
	consent: DownloadConsent,
	onAllow: () -> Unit,
	onDeny: () -> Unit,
) {
	var understood by remember { mutableStateOf(false) }
	AlertDialog(
		onDismissRequest = onDeny,
		title = { Text(stringResource(R.string.chat_download_title)) },
		text = {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 460.dp)
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Text(
					text = stringResource(R.string.chat_download_body, consent.host),
					style = MaterialTheme.typography.bodyLarge,
				)
				Text(
					text = consent.url,
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
				// The checkbox sits above the long warning: on a phone the
				// warning scrolls, and a checkbox below the fold left "Allow"
				// disabled with no visible reason.
				Row(verticalAlignment = Alignment.CenterVertically) {
					Checkbox(
						checked = understood,
						onCheckedChange = { understood = it },
					)
					Text(
						text = stringResource(R.string.chat_download_understand),
						style = MaterialTheme.typography.bodyMedium,
						modifier = Modifier.weight(1f),
					)
				}
				Text(
					text = stringResource(R.string.chat_download_warning),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.error,
				)
			}
		},
		confirmButton = {
			TextButton(onClick = onAllow, enabled = understood) {
				Text(stringResource(R.string.chat_allow_download))
			}
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
internal enum class ChatMenuTab {
	All,
	Conversation,
	Inspect,
	Files,
	Media,
	;

	fun shows(section: ChatMenuTab): Boolean = this == All || this == section
}

/**
 * Structured workbench sheet for the conversation's controls, inspection tools,
 * file exports and paid media actions.
 *
 * Replaces the 21-item inline `DropdownMenu` that previously overflowed three
 * screen heights on a phone and placed the five paid media test dialogs above
 * the conversation's own tools, memory and checkpoints.
 */
@Composable
private fun ChatWorkbenchDialog(
	title: String,
	workspaceName: String,
	workspaceIsPermanent: Boolean,
	isContinuous: Boolean,
	isStreaming: Boolean,
	isMaintaining: Boolean,
	maintenancePreparing: Boolean,
	exporting: Boolean,
	uploadBusy: Boolean,
	toolsKnown: Boolean,
	selectedToolCount: Int,
	availableToolCount: Int,
	toolRoundLimit: Int,
	roleplayActive: Boolean,
	workspaceInstructionsPinned: Boolean,
	workspaceInstructionsChars: Int,
	hasWire: Boolean,
	snapshotStats: SnapshotStats,
	sshToolEnabled: Boolean,
	sshAllowed: Boolean,
	downloadEnabled: Boolean,
	downloadAllowed: Boolean,
	showHeaderOverflowActions: Boolean,
	creatingConversation: Boolean,
	onDismiss: () -> Unit,
	onOpenSearch: () -> Unit,
	onNewConversation: () -> Unit,
	onOpenTools: () -> Unit,
	onOpenToolRounds: () -> Unit,
	onOpenSceneSettings: () -> Unit,
	onStopContinuous: () -> Unit,
	onEnableContinuous: () -> Unit,
	onReloadWorkspaceInstructions: () -> Unit,
	onRenameConversation: () -> Unit,
	onSaveWorkspacePermanent: () -> Unit,
	onOpenSshHosts: () -> Unit,
	onRevokeSsh: () -> Unit,
	onRevokeDownload: () -> Unit,
	onOpenSessionMemory: () -> Unit,
	onOpenSnapshots: () -> Unit,
	onPreviewNextRequest: () -> Unit,
	onInspectLastRequest: () -> Unit,
	onOpenApprovalHistory: () -> Unit,
	onOpenProviderKeys: () -> Unit,
	onOpenFiles: () -> Unit,
	onExportChat: () -> Unit,
	onExportBundle: () -> Unit,
	onUploadConversation: () -> Unit,
	onExportWorkspaceZip: () -> Unit,
	onGenerateImage: () -> Unit,
	onGenerateVideo: () -> Unit,
	onTextToSpeech: () -> Unit,
	onTranscribeAudio: () -> Unit,
	onDecision: () -> Unit,
) {
	var selectedTab by rememberSaveable { mutableStateOf(ChatMenuTab.All) }
	WideDialog(
		onDismissRequest = onDismiss,
		title = title,
		dismissLabel = stringResource(R.string.chat_close),
		maxBodyHeight = 480.dp,
	) {
		Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				StatusDot(MaterialTheme.colorScheme.primary, size = 5.dp)
				Text(
					text = buildString {
						append(workspaceName)
						append(" · ")
						append(
							stringResource(
								if (workspaceIsPermanent) {
									R.string.chat_workbench_workspace_permanent
								} else {
									R.string.chat_workbench_workspace_temporary
								},
							),
						)
						append(" · ")
						append(
							stringResource(
								if (isContinuous) {
									R.string.chat_workbench_mode_continuous
								} else {
									R.string.chat_workbench_mode_classic
								},
							),
						)
					},
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
			}

			if (showHeaderOverflowActions) {
				FlowRowCompat {
					OutlinedButton(
						onClick = onOpenSearch,
						shape = MaterialTheme.shapes.small,
					) {
						Icon(
							Icons.Outlined.Search,
							contentDescription = null,
							modifier = Modifier.size(16.dp),
						)
						Spacer(Modifier.width(6.dp))
						Text(stringResource(R.string.chat_search))
					}
					OutlinedButton(
						onClick = onNewConversation,
						enabled = !creatingConversation,
						shape = MaterialTheme.shapes.small,
					) {
						Icon(
							Icons.Outlined.Add,
							contentDescription = null,
							modifier = Modifier.size(16.dp),
						)
						Spacer(Modifier.width(6.dp))
						Text(stringResource(R.string.chat_new_conversation))
					}
				}
			}

			FlowRowCompat {
				FilterChip(
					selected = selectedTab == ChatMenuTab.All,
					onClick = { selectedTab = ChatMenuTab.All },
					label = { Text(stringResource(R.string.chat_workbench_tab_all)) },
				)
				FilterChip(
					selected = selectedTab == ChatMenuTab.Conversation,
					onClick = { selectedTab = ChatMenuTab.Conversation },
					label = { Text(stringResource(R.string.chat_menu_conversation)) },
				)
				FilterChip(
					selected = selectedTab == ChatMenuTab.Inspect,
					onClick = { selectedTab = ChatMenuTab.Inspect },
					label = { Text(stringResource(R.string.chat_menu_inspect)) },
				)
				FilterChip(
					selected = selectedTab == ChatMenuTab.Files,
					onClick = { selectedTab = ChatMenuTab.Files },
					label = { Text(stringResource(R.string.chat_menu_files)) },
				)
				FilterChip(
					selected = selectedTab == ChatMenuTab.Media,
					onClick = { selectedTab = ChatMenuTab.Media },
					label = { Text(stringResource(R.string.chat_menu_media)) },
				)
			}

			if (selectedTab.shows(ChatMenuTab.Conversation)) {
				ChatWorkbenchSectionHeading("01 / " + stringResource(R.string.chat_menu_conversation))
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_tools_title),
					detail = stringResource(
						R.string.chat_tools_summary,
						selectedToolCount,
						availableToolCount,
					),
					enabled = toolsKnown && !isStreaming,
					onClick = onOpenTools,
				)
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_tool_rounds),
					detail = stringResource(
						R.string.chat_rounds_before_stop,
						toolRoundLimit,
					),
					onClick = onOpenToolRounds,
				)
				if (roleplayActive) {
					ChatWorkbenchRow(
						title = stringResource(R.string.scene_settings),
						detail = stringResource(R.string.scene_settings_detail),
						enabled = !isStreaming,
						onClick = onOpenSceneSettings,
					)
				}
				if (isContinuous) {
					ChatWorkbenchRow(
						title = stringResource(R.string.chat_continuous_stop),
						detail = stringResource(R.string.chat_continuous_stop_detail),
						enabled = !isMaintaining && !maintenancePreparing,
						onClick = onStopContinuous,
					)
				} else {
					ChatWorkbenchRow(
						title = stringResource(R.string.chat_continuous_enable),
						detail = stringResource(R.string.chat_continuous_enable_detail),
						enabled = !isStreaming,
						onClick = onEnableContinuous,
					)
				}
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_workspace_instructions_reload),
					detail = if (workspaceInstructionsPinned) {
						stringResource(
							R.string.chat_workspace_instructions_pinned,
							workspaceInstructionsChars,
						) + "\n" + stringResource(R.string.chat_workspace_instructions_reload_detail)
					} else {
						stringResource(R.string.chat_workspace_instructions_unpinned)
					},
					enabled = toolsKnown && !isStreaming,
					onClick = onReloadWorkspaceInstructions,
				)
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_rename),
					onClick = onRenameConversation,
				)
				if (!workspaceIsPermanent) {
					ChatWorkbenchRow(
						title = stringResource(R.string.chat_save_workspace),
						detail = stringResource(R.string.chat_save_workspace_detail),
						onClick = onSaveWorkspacePermanent,
					)
				}
				if (sshToolEnabled) {
					ChatWorkbenchRow(
						title = stringResource(R.string.chat_ssh_hosts),
						enabled = !isStreaming,
						onClick = onOpenSshHosts,
					)
					ChatWorkbenchRow(
						title = stringResource(R.string.chat_revoke_ssh),
						enabled = sshAllowed,
						onClick = onRevokeSsh,
					)
				}
				if (downloadEnabled) {
					ChatWorkbenchRow(
						title = stringResource(R.string.chat_revoke_download),
						enabled = downloadAllowed,
						onClick = onRevokeDownload,
					)
				}
			}

			if (selectedTab.shows(ChatMenuTab.Inspect)) {
				ChatWorkbenchSectionHeading("02 / " + stringResource(R.string.chat_menu_inspect))
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_session_memory),
					detail = stringResource(R.string.chat_session_memory_detail),
					onClick = onOpenSessionMemory,
				)
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_snapshots),
					detail = pluralStringResource(
						R.plurals.chat_snapshot_menu,
						snapshotStats.turns,
						snapshotStats.turns,
						formatBytes(snapshotStats.bytes),
					),
					onClick = onOpenSnapshots,
				)
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_preview_next),
					detail = stringResource(R.string.chat_preview_next_detail),
					onClick = onPreviewNextRequest,
				)
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_inspect_last),
					detail = if (!hasWire) {
						stringResource(R.string.chat_no_request)
					} else {
						stringResource(R.string.chat_inspect_detail)
					},
					enabled = hasWire,
					onClick = onInspectLastRequest,
				)
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_approval_history),
					leadingIcon = Icons.Outlined.History,
					onClick = onOpenApprovalHistory,
				)
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_provider_keys),
					leadingIcon = Icons.Outlined.Key,
					onClick = onOpenProviderKeys,
				)
			}

			if (selectedTab.shows(ChatMenuTab.Files)) {
				ChatWorkbenchSectionHeading("03 / " + stringResource(R.string.chat_menu_files))
				ChatWorkbenchRow(
					title = stringResource(R.string.files_title),
					leadingIcon = Icons.Outlined.FolderOpen,
					onClick = onOpenFiles,
				)
				ChatWorkbenchRow(
					title = stringResource(R.string.export_chat),
					enabled = !isStreaming && !exporting,
					onClick = onExportChat,
				)
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_export_bundle),
					detail = stringResource(R.string.chat_export_bundle_detail),
					enabled = !isStreaming && !exporting,
					onClick = onExportBundle,
				)
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_upload_conversation),
					detail = stringResource(R.string.chat_upload_conversation_detail),
					enabled = !isStreaming && !exporting && !uploadBusy,
					onClick = onUploadConversation,
				)
				ChatWorkbenchRow(
					title = stringResource(R.string.export_workspace_zip),
					detail = stringResource(R.string.export_workspace_zip_detail),
					enabled = !isStreaming && !exporting,
					onClick = onExportWorkspaceZip,
				)
			}

			if (selectedTab.shows(ChatMenuTab.Media)) {
				ChatWorkbenchSectionHeading("04 / " + stringResource(R.string.chat_menu_media))
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_generate_image),
					leadingIcon = Icons.Outlined.Image,
					paid = true,
					onClick = onGenerateImage,
				)
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_generate_video),
					leadingIcon = Icons.Outlined.PlayArrow,
					paid = true,
					onClick = onGenerateVideo,
				)
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_text_to_speech),
					leadingIcon = Icons.Outlined.PlayArrow,
					paid = true,
					onClick = onTextToSpeech,
				)
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_transcribe_audio_file),
					leadingIcon = Icons.Outlined.PlayArrow,
					paid = true,
					onClick = onTranscribeAudio,
				)
				ChatWorkbenchRow(
					title = stringResource(R.string.chat_decision),
					leadingIcon = Icons.Outlined.DataObject,
					paid = true,
					onClick = onDecision,
				)
			}
		}
	}
}

@Composable
private fun ChatWorkbenchSectionHeading(code: String) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 6.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Eyebrow(code)
		Hairline()
	}
}

@Composable
private fun ChatWorkbenchRow(
	title: String,
	onClick: () -> Unit,
	detail: String? = null,
	leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
	paid: Boolean = false,
	enabled: Boolean = true,
) {
	val titleColor = if (enabled) {
		MaterialTheme.colorScheme.onSurface
	} else {
		MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
	}
	val detailColor = if (enabled) {
		MaterialTheme.colorScheme.onSurfaceVariant
	} else {
		MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
	}
	Column(modifier = Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clickable(enabled = enabled, onClick = onClick)
				.padding(vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			if (leadingIcon != null) {
				Icon(
					imageVector = leadingIcon,
					contentDescription = null,
					tint = detailColor,
					modifier = Modifier.size(18.dp),
				)
			}
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Text(
						text = title,
						style = MaterialTheme.typography.titleMedium,
						color = titleColor,
					)
					if (paid) {
						PaidBadge()
					}
				}
				if (!detail.isNullOrBlank()) {
					Text(
						text = detail,
						style = MaterialTheme.typography.bodySmall,
						color = detailColor,
					)
				}
			}
			Icon(
				imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
				contentDescription = null,
				tint = detailColor,
				modifier = Modifier.size(18.dp),
			)
		}
		Hairline()
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
 * The row title for a tool that has no catalogue entry of its own.
 *
 * `recall` is a conversation capability built into continuous mode, not a
 * catalogue entry the user picked, so it has no `ToolCatalog` name to show.
 */
@Composable
private fun toolRowTitle(name: String?): String = when (name) {
	"recall", "recall_get" -> stringResource(R.string.chat_recall_title)
	else -> name ?: stringResource(R.string.chat_tool_result)
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
	val recallDetail = stringResource(R.string.chat_recall_detail)

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.Top,
	) {
		Text(
			text = toolRowTitle(call.displayName),
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
			// almost always the path or the query. A recall call may still be
			// streaming its arguments; its description says what the row is.
			text = summariseArguments(call).ifBlank {
				if (call.displayName == "recall" || call.displayName == "recall_get") {
					recallDetail
				} else {
					""
				}
			},
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

/** What the speaker control on one reply is doing. */
private enum class ReplySpeechState { Idle, Preparing, Playing, Paused }

@Composable
private fun MessageBubble(
	message: ChatMessageUi,
	parsedMarkdown: Map<String, com.mikepenz.markdown.model.State>,
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
	speakState: ReplySpeechState,
	/** The reply already has audio for the current voice; tapping is free. */
	speechCached: Boolean,
	onSpeakReply: () -> Unit,
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
		val (summaryText, summaryCount) = toolResultSummary(toolRowTitle(message.toolName), firstLine)
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
				text = when {
					// The words are the user's; the marker says when they were
					// written, because a message inside a turn does not get its
					// own turn number or its own Rewind point.
					message.injected -> stringResource(R.string.chat_you_injected)
					message.fromUser -> stringResource(R.string.chat_you)
					else -> stringResource(R.string.chat_assistant)
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
				// Derived once per change of the parts, not per recomposition:
				// a streaming tick recomposes this row, and joining the
				// reasoning text copied all of it every time.
				val reasoning = remember(message.parts) {
					message.parts
						.filter { it.kind == AssistantPart.Kind.Reasoning }
						.joinToString("\n\n") { it.text }
				}
				val textParts = remember(message.parts) {
					message.parts.filter { it.kind == AssistantPart.Kind.Text }
				}
				val calls = message.toolCalls
				val hasText = textParts.any { it.text.isNotBlank() }
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
								parsed = parsedMarkdown[text],
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
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
					) {
						CircularProgressIndicator(
							modifier = Modifier.size(16.dp),
							strokeWidth = 2.dp,
							color = MaterialTheme.colorScheme.primary,
						)
						message.retryNotice?.let { notice ->
							Text(
								text = notice,
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
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
					// Read aloud: one tap starts and the same control becomes
					// Stop while this reply is playing. The paid disclosure is
					// the one-time confirmation; after that this row is the
					// one-tap path mature chat clients use.
					if (speakState == ReplySpeechState.Preparing) {
						Box(
							modifier = Modifier.size(40.dp),
							contentAlignment = Alignment.Center,
						) {
							CircularProgressIndicator(
								strokeWidth = 2.dp,
								modifier = Modifier.size(16.dp),
							)
						}
					} else {
						IconButton(onClick = onSpeakReply) {
							Icon(
								imageVector = when (speakState) {
									ReplySpeechState.Playing -> Icons.Filled.Stop
									ReplySpeechState.Paused -> Icons.Outlined.PlayArrow
									else -> if (speechCached) {
										Icons.AutoMirrored.Filled.VolumeUp
									} else {
										Icons.AutoMirrored.Outlined.VolumeUp
									}
								},
								contentDescription = stringResource(
									when (speakState) {
										ReplySpeechState.Playing -> R.string.chat_stop
										ReplySpeechState.Paused -> R.string.chat_speech_continue
										else -> if (speechCached) {
											R.string.chat_speak_reply_cached
										} else {
											R.string.chat_speak_reply
										}
									},
								),
								tint = if (speakState != ReplySpeechState.Idle || speechCached) {
									MaterialTheme.colorScheme.primary
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
								modifier = Modifier.size(17.dp),
							)
						}
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

/**
 * The composer's callbacks, as one stable value.
 *
 * The chat content lambda is recomposed by every streamed token because the
 * transcript lives in it. Passing a freshly built set of lambdas down would
 * rebuild the text field and the send button on every tick even though neither
 * changed. This object is remembered once per conversation/model state, so the
 * bottom bar can skip that work.
 */
@Immutable
private class ChatComposerActions(
	val onSend: () -> Unit,
	val onStop: () -> Unit,
	/** Show the draft to the running turn before its next model call. */
	val onSendInsert: () -> Unit,
	val onCancelQueued: (String) -> Unit,
	val onSendQueued: (String) -> Unit,
	val onInsertQueued: (String) -> Unit,
	val onInputChange: (String) -> Unit,
	val onAttachFromFiles: () -> Unit,
	val onCapturePhoto: () -> Unit,
	val onRemoveAttachment: (PendingAttachment) -> Unit,
	val onTranscribeVoice: suspend (ByteArray) -> Result<String>,
	val onVoiceText: (String) -> Unit,
	val onToggleContextDetail: () -> Unit,
	val onCompact: () -> Unit,
	val onViewCompaction: () -> Unit,
	val onClearCompaction: () -> Unit,
	val onClearError: () -> Unit,
	val onRetry: () -> Unit,
	val onCompactAndRetry: () -> Unit,
	val onOpenSettings: () -> Unit,
	val onContinue: () -> Unit,
	val onEnableDemo: () -> Unit,
)

@Composable
private fun ChatBottomBar(
	configured: Boolean,
	messageCount: Int,
	interrupted: Boolean,
	error: String?,
	errorAction: ChatErrorAction?,
	/** The view model's state, read narrowly here for the composer's fields. */
	state: StateFlow<ChatUiState>,
	isStreaming: Boolean,
	isCompacting: Boolean,
	modelSupportsVision: Boolean,
	voiceReady: Boolean,
	usage: ContextUsage,
	showContextDetail: Boolean,
	compaction: ContextCompactionUi?,
	isContinuous: Boolean,
	maintenancePreparing: Boolean,
	showComposer: Boolean,
	actions: ChatComposerActions,
) {
	// Read here, in the composer's own scope: the draft and the staged
	// attachments change per keystroke and must not invalidate the transcript or
	// the screen chrome around it. A streaming reply does not touch either of
	// these fields.
	val input by fieldOf(state, "") { it.input }
	val pendingAttachments by fieldOf(state, emptyList()) { it.pendingAttachments }
	val queued by fieldOf(state, emptyList()) { it.queued }
	val ctx = LocalContext.current
	Column(
		modifier = Modifier.fillMaxWidth(),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		// Demo mode is otherwise buried in Settings. The welcome panel already
		// offers it on an empty transcript; this strip is for a conversation
		// that has messages and still has no key.
		if (!configured && messageCount > 0) {
			TextButton(
				onClick = actions.onEnableDemo,
				modifier = Modifier
					.widthIn(max = Layout.ReadableMeasure)
					.fillMaxWidth()
					.padding(horizontal = comfortableGutter()),
			) {
				Text(stringResource(R.string.chat_try_demo_strip))
			}
		}

		// Continuous mode has no gauge and no compact button, so the one status
		// that would otherwise be invisible is the maintenance pass a send is
		// waiting for. It reads as a sentence, not an error: the request is still
		// going to be sent.
		if (isContinuous && maintenancePreparing) {
			Text(
				text = stringResource(R.string.chat_maintenance_preparing),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier
					.widthIn(max = Layout.ChatMeasure)
					.fillMaxWidth()
					.padding(horizontal = comfortableGutter(), vertical = 2.dp),
			)
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
					TextButton(onClick = actions.onClearError) {
						Text(stringResource(R.string.chat_dismiss))
					}
				}
				Row(
					horizontalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed),
					verticalAlignment = Alignment.CenterVertically,
				) {
					when (errorAction) {
						ChatErrorAction.Retry -> TextButton(
							onClick = actions.onRetry,
						) { Text(stringResource(R.string.chat_retry)) }

						ChatErrorAction.CompactAndRetry -> TextButton(
							onClick = actions.onCompactAndRetry,
						) { Text(stringResource(R.string.chat_compact_retry)) }

						ChatErrorAction.OpenProviderSettings -> TextButton(
							onClick = {
								actions.onClearError()
								actions.onOpenSettings()
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

		// Search replaces the composer rather than sitting above it. With the
		// IME open a two-row search bar, the turn strip, the composer and the
		// keyboard left no transcript on a 731dp-tall phone; the matches were
		// searchable and invisible.
		if (showComposer) {
			if (interrupted && configured) {
				ContinueTurnBanner(onContinue = actions.onContinue)
			}
			Composer(
				value = input,
				onValueChange = actions.onInputChange,
				onSend = actions.onSend,
				onStop = actions.onStop,
				onSendInsert = actions.onSendInsert,
				queued = queued,
				onCancelQueued = actions.onCancelQueued,
				onSendQueued = actions.onSendQueued,
				onInsertQueued = actions.onInsertQueued,
				isStreaming = isStreaming,
				enabled = configured,
				attachments = pendingAttachments,
				canAttachImages = modelSupportsVision,
				onAttachFromFiles = actions.onAttachFromFiles,
				onCapturePhoto = actions.onCapturePhoto,
				onRemoveAttachment = actions.onRemoveAttachment,
				voiceEnabled = voiceReady,
				onTranscribeVoice = actions.onTranscribeVoice,
				onVoiceText = actions.onVoiceText,
				context = usage,
				showContextDetail = showContextDetail,
				onToggleContextDetail = actions.onToggleContextDetail,
				compaction = compaction,
				isCompacting = isCompacting,
				isContinuous = isContinuous,
				maintenancePreparing = maintenancePreparing,
				onCompact = actions.onCompact,
				onViewCompaction = actions.onViewCompaction,
				onClearCompaction = actions.onClearCompaction,
				modifier = Modifier
					.widthIn(max = Layout.ChatMeasure)
					.fillMaxWidth(),
			)
		}
	}
}

@Composable
private fun Composer(
	value: String,
	onValueChange: (String) -> Unit,
	onSend: () -> Unit,
	onStop: () -> Unit,
	/** Show the draft to the running turn before its next model call. */
	onSendInsert: () -> Unit,
	queued: List<QueuedMessage>,
	onCancelQueued: (String) -> Unit,
	onSendQueued: (String) -> Unit,
	onInsertQueued: (String) -> Unit,
	isStreaming: Boolean,
	enabled: Boolean,
	attachments: List<PendingAttachment>,
	canAttachImages: Boolean,
	onAttachFromFiles: () -> Unit,
	onCapturePhoto: () -> Unit,
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
	isContinuous: Boolean,
	maintenancePreparing: Boolean,
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
	// The image source menu. Local to the composer: it is about the next
	// message's attachment, not screen navigation, and it must not survive a
	// configuration change as an open popup.
	var attachSources by remember { mutableStateOf(false) }
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
		// The figures, and only when the ring was tapped. The compact state is
		// the ring in the input's trailing slot: a permanent row above the field
		// cost the height of the composer to say one proportion.
		if (!isContinuous && showContextDetail) {
			ContextDetailPanel(
				usage = context,
				compaction = compaction,
				isCompacting = isCompacting,
				onCompact = onCompact,
				onViewCompaction = onViewCompaction,
				onClearCompaction = onClearCompaction,
				modifier = Modifier.padding(horizontal = comfortableGutter(), vertical = 4.dp),
			)
		}

		// Staged images sit above the field, so they read as part of the message
		// being written rather than as a toolbar beside it.
		//
		// One scrolling row rather than a wrapping grid: the picker can return any
		// number of images, and the composer must not grow into the transcript. A
		// chip that is off-screen is still reachable by scrolling.
		if (attachments.isNotEmpty()) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = comfortableGutter(), vertical = 4.dp)
					.horizontalScroll(rememberScrollState()),
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

		// The queue strip is the visible proof that a message written during a
		// turn exists. A message that was accepted but is neither in the
		// transcript nor on screen is the failure the old disabled composer
		// avoided by refusing the send outright; the strip replaces that refusal
		// with a state the user can see, edit their mind about, and cancel.
		if (queued.isNotEmpty()) {
			QueuedMessagesStrip(
				queued = queued,
				isStreaming = isStreaming,
				onInsert = onInsertQueued,
				onCancel = onCancelQueued,
				onSend = onSendQueued,
			)
		}

		// ## One surface, text above actions
		//
		// The field used to be an OutlinedTextField with the context ring in
		// its trailing slot, and the attach, microphone and send buttons in the
		// same row. On a 360dp phone with a microphone configured that left the
		// text about 70dp wide -- five characters a line -- and the trailing
		// slot reserved a 48dp column for the full height of the field, so a
		// wrapped message sat beside a blank strip as tall as itself. The
		// controls now share one row under the text: every line gets the
		// field's whole width, and a multi-line message has no reserved column
		// beside it.
		val fieldInteraction = remember { MutableInteractionSource() }
		val fieldFocused by fieldInteraction.collectIsFocusedAsState()
		// Typing is allowed while a reply streams: the send path queues instead
		// of starting a second turn. Compaction still disables it, because that
		// request rewrites the projection this draft would be sent against.
		val inputEnabled = enabled && !isCompacting
		val fieldBorder = when {
			!inputEnabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
			fieldFocused -> MaterialTheme.colorScheme.primary
			else -> MaterialTheme.colorScheme.outline
		}
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = comfortableGutter(), vertical = 6.dp)
				.clip(MaterialTheme.shapes.small)
				.border(1.dp, fieldBorder, MaterialTheme.shapes.small),
		) {
			Column(modifier = Modifier.fillMaxWidth()) {
				BasicTextField(
					value = value,
					onValueChange = onValueChange,
					modifier = Modifier
						.fillMaxWidth()
						.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp),
					textStyle = MaterialTheme.typography.bodyLarge.copy(
						color = MaterialTheme.colorScheme.onSurface,
					),
					enabled = inputEnabled,
					maxLines = 6,
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
					keyboardActions = KeyboardActions(),
					cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
					interactionSource = fieldInteraction,
					decorationBox = { field ->
						Box(modifier = Modifier.fillMaxWidth()) {
							if (value.isEmpty()) {
								Text(
									text = if (enabled) {
										stringResource(R.string.chat_message_hint)
									} else {
										stringResource(R.string.chat_add_key_hint)
									},
									style = MaterialTheme.typography.bodyLarge,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
							field()
						}
					},
				)

				Row(
					modifier = Modifier
						.fillMaxWidth()
						.height(48.dp)
						.padding(horizontal = 2.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					if (voiceEnabled) {
						IconButton(
							onClick = { toggleVoice() },
							enabled = enabled && !isStreaming && !isCompacting && !voiceBusy,
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
					//
					// Tapping it asks for the source rather than opening the picker
					// directly. The camera is one more way to produce an image, not a
					// separate kind of attachment, so it belongs behind the same icon;
					// a second camera glyph in a 48dp row would compete with the
					// microphone and the context ring for a distinction the menu makes
					// in words.
					Box {
						IconButton(
							onClick = { attachSources = true },
							enabled = enabled && canAttachImages && !isCompacting,
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
						DropdownMenu(
							expanded = attachSources,
							onDismissRequest = { attachSources = false },
						) {
							DropdownMenuItem(
								text = { Text(stringResource(R.string.chat_attach_from_files)) },
								leadingIcon = {
									Icon(Icons.Outlined.FolderOpen, contentDescription = null)
								},
								onClick = {
									attachSources = false
									onAttachFromFiles()
								},
							)
							DropdownMenuItem(
								text = { Text(stringResource(R.string.chat_attach_take_photo)) },
								leadingIcon = {
									Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
								},
								onClick = {
									attachSources = false
									onCapturePhoto()
								},
							)
						}
					}

					Spacer(Modifier.weight(1f))

					// The figures stay one tap away without costing a column of
					// every line: in the trailing slot the ring reserved its
					// 48dp for the whole height of a wrapped message.
					// Continuous conversations hide the gauge: the app owns the
					// working set there, and the audit surface is Session memory.
					// The context gauge is a reading of the last completed request;
					// while a reply streams it is neither current nor actionable,
					// and the row needs its 48dp for the send/insert/stop trio.
					if (!isStreaming && !isContinuous && context.isKnown) {
						ContextRing(
							usage = context,
							onToggle = onToggleContextDetail,
						)
					}

					// While streaming, sending has two different promises: queue
					// for the next turn, or splice this text into the current
					// turn before its next model call. They are separate targets
					// because a single button whose meaning silently depended on
					// a mode toggle is exactly the ambiguity the old disabled
					// composer avoided by refusing the message.
					if (isStreaming) {
						TextButton(
							onClick = onSendInsert,
							enabled = enabled && !isCompacting && canInsertNow(value, attachments),
						) {
							Text(
								text = stringResource(R.string.chat_insert_short),
								style = MaterialTheme.typography.labelLarge,
							)
						}
						FilledTonalIconButton(
							onClick = onSend,
							// Images alone are sendable; an insertion is not, and
							// the button above reflects that. The queue accepts
							// both.
							enabled = enabled && !isCompacting && !maintenancePreparing &&
								(value.isNotBlank() || attachments.isNotEmpty()),
							shape = MaterialTheme.shapes.small,
						) {
							Icon(
								Icons.Filled.ArrowUpward,
								contentDescription = stringResource(R.string.chat_send_after_turn),
							)
						}
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
							enabled = enabled && !isCompacting && !maintenancePreparing &&
								(value.isNotBlank() || attachments.isNotEmpty()),
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
	}
}

/**
 * The messages waiting to be delivered, above the composer.
 *
 * Each row is one thing the user wrote while a turn was running. Its state word
 * is the promise being made: "after this turn", "inserting this turn", or "not
 * sent" when a failed turn held the queue. Actions are per row rather than
 * global, because "cancel this one" and "send this one now" are decisions about
 * individual messages; inserting is offered only while it can still happen.
 */
@Composable
private fun QueuedMessagesStrip(
	queued: List<QueuedMessage>,
	isStreaming: Boolean,
	onInsert: (String) -> Unit,
	onCancel: (String) -> Unit,
	onSend: (String) -> Unit,
) {
	Column(
		modifier = Modifier
			.widthIn(max = Layout.ChatMeasure)
			.fillMaxWidth()
			.padding(horizontal = comfortableGutter(), vertical = 2.dp),
		verticalArrangement = Arrangement.spacedBy(2.dp),
	) {
		for (entry in queued) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = when {
						entry.insert -> stringResource(R.string.chat_queue_state_insert)
						isStreaming -> stringResource(R.string.chat_queue_state_after)
						else -> stringResource(R.string.chat_queue_state_held)
					},
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Spacer(Modifier.width(8.dp))
				Text(
					text = entry.preview.ifBlank { stringResource(R.string.chat_queue_image_only) },
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				if (isStreaming && !entry.insert && entry.insertable) {
					TextButton(onClick = { onInsert(entry.id) }) {
						Text(stringResource(R.string.chat_queue_insert_action))
					}
				}
				if (!isStreaming) {
					TextButton(onClick = { onSend(entry.id) }) {
						Text(stringResource(R.string.chat_queue_send_action))
					}
				}
				IconButton(onClick = { onCancel(entry.id) }) {
					Icon(
						Icons.Filled.Close,
						contentDescription = stringResource(R.string.chat_queue_cancel),
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(16.dp),
					)
				}
			}
		}
	}
}

/**
 * The session-memory sheet: the audit surface for a continuous conversation.
 *
 * It answers the three questions the normal path deliberately does not ask --
 * what the app is keeping, what maintenance has cost, and what the model is
 * actually being told. The brief is read-only here: editing it would be a second
 * system prompt, and the next maintenance pass would overwrite it.
 *
 * The primary action is the mode's own action (Rebuild while continuous, turn
 * the mode on while classic); Stop maintaining and Search record sit in the
 * scrolling body so the sheet keeps one fixed primary button.
 */
@Composable
private fun SessionMemoryDialog(
	memory: SessionMemoryUi?,
	loading: Boolean,
	failed: Boolean,
	isContinuous: Boolean,
	onDismiss: () -> Unit,
	onRebuild: () -> Unit,
	onStopMaintaining: () -> Unit,
	onEnableContinuous: () -> Unit,
	onSearch: () -> Unit,
) {
	WideDialog(
		onDismissRequest = onDismiss,
		title = stringResource(R.string.session_memory_title),
		confirmLabel = if (isContinuous) {
			stringResource(R.string.session_memory_rebuild)
		} else {
			stringResource(R.string.chat_continuous_enable)
		},
		onConfirm = if (isContinuous) onRebuild else onEnableContinuous,
		dismissLabel = stringResource(R.string.chat_close),
	) {
		val mode = memory?.sessionMode
			?: if (isContinuous) SessionMode.CONTINUOUS else SessionMode.CLASSIC
		Text(
			text = stringResource(
				R.string.session_memory_mode,
				stringResource(
					if (mode == SessionMode.CONTINUOUS) {
						R.string.session_memory_mode_continuous
					} else {
						R.string.session_memory_mode_classic
					},
				),
			),
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onBackground,
		)
		if (mode == SessionMode.CLASSIC) {
			Text(
				text = stringResource(R.string.session_memory_classic_note),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}

		FlowRowCompat {
			TextButton(onClick = onSearch) {
				Text(
					text = stringResource(R.string.session_memory_search),
					style = MaterialTheme.typography.labelLarge,
				)
			}
			if (isContinuous) {
				TextButton(onClick = onStopMaintaining) {
					Text(
						text = stringResource(R.string.chat_continuous_stop),
						style = MaterialTheme.typography.labelLarge,
					)
				}
			}
		}

		when {
			loading -> Text(
				text = stringResource(R.string.session_memory_loading),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)

			failed -> Text(
				text = stringResource(R.string.session_memory_unavailable),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
			)

			memory == null -> Unit

			else -> {
				Hairline()
				Text(
					text = if (memory.coverageTurns != null && memory.coverageMessages != null) {
						stringResource(
							R.string.session_memory_coverage,
							memory.coverageTurns,
							memory.coverageMessages,
						)
					} else {
						stringResource(R.string.session_memory_coverage_unknown)
					},
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onBackground,
				)
				Text(
					text = memory.lastRunAt?.let {
						stringResource(R.string.session_memory_last_run, formatSnapshotTime(it))
					} ?: stringResource(R.string.session_memory_never_run),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				if (memory.runs > 0) {
					Text(
						text = stringResource(
							R.string.session_memory_totals,
							memory.runs,
							TokenFormat.compact(memory.inputTokens),
							TokenFormat.compact(memory.outputTokens),
						),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}

				val brief = memory.brief?.takeIf { it.isNotBlank() }
				if (brief == null && !isContinuous) {
					// A classic conversation has no brief by design: the note
					// above already says older messages are sent in full, and an
					// empty "Session brief" section would read as a failure.
				} else {
					Hairline()
					SectionHeading(
						code = stringResource(R.string.session_memory_brief_code),
						title = stringResource(R.string.session_memory_brief),
					)
					if (brief == null) {
						Text(
							text = stringResource(R.string.session_memory_brief_empty),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					} else {
						val facts = listOfNotNull(
							when (memory.briefKind) {
								"manual" -> stringResource(R.string.session_memory_brief_kind_manual)
								else -> stringResource(R.string.session_memory_brief_kind_auto)
							},
							memory.briefUpdatedAt?.let {
								stringResource(R.string.session_memory_brief_updated, formatSnapshotTime(it))
							},
							memory.briefTokens?.let {
								stringResource(
									R.string.session_memory_brief_tokens,
									TokenFormat.compact(it.toLong()),
								)
							},
						)
						if (facts.isNotEmpty()) {
							Text(
								text = facts.joinToString(" · "),
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
						if (memory.briefTruncated) {
							Text(
								text = stringResource(R.string.session_memory_brief_truncated),
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.error,
							)
						}
						Text(
							text = brief,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurface,
						)
					}
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
private fun SshHostsDialog(
	hosts: List<SshHostSummary>,
	selected: List<String>,
	onApply: (List<String>) -> Unit,
	onDismiss: () -> Unit,
) {
	var checked by remember(selected) { mutableStateOf(selected.toSet()) }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.chat_ssh_hosts_title)) },
		text = {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 460.dp)
					.verticalScroll(rememberScrollState()),
			) {
				Text(
					text = stringResource(R.string.chat_ssh_hosts_body),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Spacer(Modifier.height(8.dp))
				if (hosts.isEmpty()) {
					Text(
						text = stringResource(R.string.chat_ssh_hosts_empty),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				hosts.forEach { host ->
					val isChecked = host.id in checked
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clickable {
								checked = if (isChecked) checked - host.id else checked + host.id
							}
							.padding(vertical = 5.dp),
						verticalAlignment = Alignment.Top,
					) {
						Checkbox(
							checked = isChecked,
							onCheckedChange = { on ->
								checked = if (on) checked + host.id else checked - host.id
							},
						)
						Column(
							modifier = Modifier
								.weight(1f)
								.padding(start = 2.dp, top = 1.dp),
						) {
							Text(host.label, style = MaterialTheme.typography.bodyMedium)
						}
					}
				}
				Spacer(Modifier.height(8.dp))
				Text(
					text = stringResource(R.string.chat_ssh_hosts_note),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		confirmButton = {
			TextButton(onClick = { onApply(checked.toList()) }) {
				Text(stringResource(R.string.common_save))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
		},
	)
}

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
 * The scene settings of a role-play conversation.
 *
 * A scene is setting, not capability: the five fields are rendered into the
 * system message and nothing here can add a tool, change an approval or widen
 * the workspace. Saving an emptied scene removes it (the codec stores `""` for
 * a blank scene), which is the same result as going back to an ordinary chat.
 *
 * `WideDialog` rather than `AlertDialog` for the same reason as the assistant
 * editor: five multi-line fields in a platform-width dialog leave a phone with
 * a strip of text, and this body scrolls while the actions stay put.
 */
@Composable
private fun SceneSettingsDialog(
	initial: RoleplayScene?,
	streaming: Boolean,
	onSave: (RoleplayScene) -> Unit,
	/** Replace the frozen persona with the assistant's current card and prompt. */
	onReloadPersona: () -> Unit,
	onDismiss: () -> Unit,
) {
	val base = initial ?: RoleplayScene()
	var world by remember(initial) { mutableStateOf(base.world) }
	var premise by remember(initial) { mutableStateOf(base.premise) }
	var userPersona by remember(initial) { mutableStateOf(base.userPersona) }
	var format by remember(initial) { mutableStateOf(base.format) }
	var notes by remember(initial) { mutableStateOf(base.notes) }
	val scene = RoleplayScene(
		world = world,
		premise = premise,
		userPersona = userPersona,
		format = format,
		notes = notes,
	)
	val errors = RoleplayLimits.sceneErrors(scene)

	WideDialog(
		onDismissRequest = onDismiss,
		title = stringResource(R.string.scene_settings),
		confirmLabel = stringResource(R.string.scene_save),
		onConfirm = { onSave(scene) },
		confirmEnabled = errors.isEmpty() && !streaming,
		dismissLabel = stringResource(R.string.roleplay_cancel),
	) {
		Text(
			text = stringResource(R.string.scene_save_intro),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = stringResource(R.string.scene_reload_persona_note),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		TextButton(
			onClick = {
				onReloadPersona()
				onDismiss()
			},
			enabled = !streaming,
		) {
			Text(stringResource(R.string.scene_reload_persona))
		}
		SceneField(stringResource(R.string.roleplay_field_world), world) { world = it }
		SceneField(stringResource(R.string.roleplay_field_premise), premise) { premise = it }
		SceneField(stringResource(R.string.roleplay_field_user_persona), userPersona) {
			userPersona = it
		}
		SceneField(
			label = stringResource(R.string.roleplay_field_format),
			value = format,
			hint = stringResource(R.string.scene_format_hint),
		) { format = it }
		SceneField(
			label = stringResource(R.string.roleplay_field_notes),
			value = notes,
			hint = stringResource(R.string.scene_notes_hint),
		) { notes = it }
		errors.forEach { error ->
			Text(
				text = stringResource(
					R.string.roleplay_limit_over,
					roleplayFieldName(error.field),
					error.actual,
					error.max,
				),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
			)
		}
		if (streaming) {
			Text(
				text = stringResource(R.string.chat_tools_streaming),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
			)
		}
	}
}

/** One scene text field; the hint names the field's job without a second label. */
@Composable
private fun SceneField(
	label: String,
	value: String,
	hint: String? = null,
	onChange: (String) -> Unit,
) {
	OutlinedTextField(
		value = value,
		onValueChange = onChange,
		modifier = Modifier.fillMaxWidth(),
		label = { Text(label) },
		minLines = 2,
		maxLines = 6,
		supportingText = if (hint == null) null else ({ Text(hint) }),
	)
}

/**
 * The localized name of a role-play field, for a limit message.
 *
 * Exhaustive over the enum so adding a field is a compile error here rather
 * than an empty label in a dialog.
 */
@Composable
private fun roleplayFieldName(field: RoleplayField): String = stringResource(
	when (field) {
		RoleplayField.Name -> R.string.roleplay_field_name
		RoleplayField.World -> R.string.roleplay_field_world
		RoleplayField.Premise -> R.string.roleplay_field_premise
		RoleplayField.UserPersona -> R.string.roleplay_field_user_persona
		RoleplayField.Format -> R.string.roleplay_field_format
		RoleplayField.Notes -> R.string.roleplay_field_notes
		RoleplayField.SceneTotal -> R.string.roleplay_field_scene_total
		RoleplayField.CardTotal -> R.string.roleplay_field_card_total
		RoleplayField.Description -> R.string.roleplay_field_description
		RoleplayField.Personality -> R.string.roleplay_field_personality
		RoleplayField.Scenario -> R.string.roleplay_field_scenario
		RoleplayField.Greeting -> R.string.roleplay_field_greeting
		RoleplayField.ExampleDialogue -> R.string.roleplay_field_example
		RoleplayField.Boundaries -> R.string.roleplay_field_boundaries
		RoleplayField.AuthorNote -> R.string.roleplay_field_author_note
	},
)

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

/**
 * Hand one cached Markdown file to the system share sheet.
 *
 * The ClipData is part of the grant on API 34+, not decoration: some receiving
 * apps read the stream from the clip rather than EXTRA_STREAM, and a missing
 * clip is the difference between "shared" and "could not open attachment".
 */
private fun shareConversationFile(
	context: android.content.Context,
	file: File,
	title: String,
) {
	val uri = ConversationShare.uri(context, file)
	val send = Intent(Intent.ACTION_SEND).apply {
		type = "text/plain"
		putExtra(Intent.EXTRA_STREAM, uri)
		putExtra(Intent.EXTRA_SUBJECT, title)
		clipData = ClipData.newRawUri(title, uri)
		addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
	}
	runCatching {
		context.startActivity(
			Intent.createChooser(send, context.getString(R.string.export_share_chooser)),
		)
	}.onFailure { failure ->
		Diag.w("export", "share chooser failed", failure)
		Toast.makeText(context, R.string.export_share_failed, Toast.LENGTH_LONG).show()
	}
}

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
	// The backend accepts sizes the app cannot enumerate for every model, so
	// the common ones are chips and anything else stays a text field. A fixed
	// list would make a valid size unreachable; free text alone made a typo a
	// paid failure.
	var customSize by remember { mutableStateOf(false) }
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
				Text(
					text = stringResource(R.string.chat_image_size),
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				FlowRowCompat {
					listOf("1024*1024", "768*1344", "1344*768").forEach { value ->
						FilterChip(
							selected = !customSize && size == value,
							onClick = {
								size = value
								customSize = false
							},
							label = { Text(value) },
							enabled = !generating,
						)
					}
					FilterChip(
						selected = customSize,
						onClick = { customSize = true },
						label = { Text(stringResource(R.string.chat_image_size_custom)) },
						enabled = !generating,
					)
				}
				if (customSize) {
					OutlinedTextField(
						value = size,
						onValueChange = { size = it },
						modifier = Modifier.fillMaxWidth(),
						label = { Text(stringResource(R.string.chat_image_size)) },
						supportingText = {
							Text(stringResource(R.string.chat_image_size_hint))
						},
						singleLine = true,
						enabled = !generating,
					)
				}
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
				enabled = prompt.isNotBlank() && size.isNotBlank() && !generating,
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

/**
 * The reply read-aloud bar.
 *
 * It used to be three rows -- state label, caption, progress -- about 107dp
 * above the composer on a 360dp phone, roughly a sixth of a 16:9 screen spent
 * on a control that is mostly read. It is now one row: the caption is the
 * label, the transport is two icon buttons, and the progress sits under the
 * caption in the same column. The voice picker moved out: a bar that switches
 * voices costs a whole row, and a voice is chosen before or after listening,
 * not while. Settings -> Network & media -> Reply read-aloud keeps the picker.
 */
@Composable
private fun SpeechPlayerBar(
	paused: Boolean,
	caption: String?,
	onPauseResume: () -> Unit,
	onStop: () -> Unit,
	positionMs: Int,
	durationMs: Int,
	onScrub: (Float) -> Unit,
	onScrubFinished: () -> Unit,
) {
	Surface(
		color = MaterialTheme.colorScheme.surfaceVariant,
		tonalElevation = 0.dp,
	) {
		Box(
			modifier = Modifier.fillMaxWidth(),
			contentAlignment = Alignment.Center,
		) {
			Row(
				modifier = Modifier
					.widthIn(max = Layout.ChatMeasure)
					.fillMaxWidth()
					.padding(start = comfortableGutter(), end = 4.dp, top = 2.dp, bottom = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				IconButton(onClick = onPauseResume) {
					Icon(
						imageVector = if (paused) {
							Icons.Outlined.PlayArrow
						} else {
							Icons.Filled.Pause
						},
						contentDescription = stringResource(
							if (paused) R.string.chat_speech_continue else R.string.chat_pause,
						),
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(20.dp),
					)
				}
				Column(modifier = Modifier.weight(1f)) {
					if (!caption.isNullOrBlank()) {
						Text(
							text = caption,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurface,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
					// The progress row is the same position the media session
					// publishes, and dragging it seeks. When the backend returns
					// a file with no duration yet, the row stays out rather than
					// drawing a fake 0%.
					if (durationMs > 0) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Slider(
								value = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
								onValueChange = onScrub,
								onValueChangeFinished = onScrubFinished,
								modifier = Modifier
									.weight(1f)
									.height(28.dp),
							)
							Text(
								text = "${SpeechTime.format(positionMs)} / " +
									SpeechTime.format(durationMs),
								style = MaterialTheme.typography.labelSmall,
								fontFamily = FontFamily.Monospace,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.padding(start = 6.dp),
							)
						}
					}
				}
				IconButton(onClick = onStop) {
					Icon(
						imageVector = Icons.Filled.Stop,
						contentDescription = stringResource(R.string.chat_stop),
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier.size(18.dp),
					)
				}
			}
		}
	}
}

/**
 * The one-time paid disclosure for reading replies aloud.
 *
 * The price rule is disclosure where the capability is chosen and where it is
 * used. Settings is where the backend is chosen; this is where the reply is
 * used, so it appears once per install and then the speaker is one tap. It also
 * carries the voice picker so the first read already sounds the way the user
 * wants.
 */
@Composable
private fun SpeechReadConsentDialog(
	voices: List<String>,
	initialVoice: String?,
	onConfirm: (String?) -> Unit,
	onDismiss: () -> Unit,
) {
	// The choice stays local until confirmation: dismissing the paid notice
	// must not silently change the stored voice.
	var chosen by remember(initialVoice, voices) {
		mutableStateOf(initialVoice?.takeIf { it in voices } ?: voices.firstOrNull())
	}
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.chat_speak_reply_title)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages)) {
				PaidActionNotice()
				Text(
					text = stringResource(R.string.chat_speak_reply_first_use),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				if (voices.size > 1) {
					Text(
						text = stringResource(R.string.chat_voice),
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					FlowRowCompat {
						voices.forEach { id ->
							FilterChip(
								selected = chosen == id,
								onClick = { chosen = id },
								label = { Text(speechVoiceLabel(id)) },
							)
						}
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = { onConfirm(chosen) }) {
				Text(stringResource(R.string.chat_speak))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.common_cancel))
			}
		},
	)
}

@Composable
private fun SpeechDialog(
	initialText: String = "",
	initialVoice: String? = null,
	workspaceId: String?,
	onSpeak: suspend (String, String?) -> Result<ChatViewModel.GeneratedAudio>,
	onVoices: suspend () -> List<String>,
	onTranscribe: suspend (String) -> Result<String>,
	onDismiss: () -> Unit,
) {
	// As in DecisionDialog: these fallbacks are used inside `launch` blocks.
	val speechFailed = stringResource(R.string.chat_speech_failed)
	val transcriptionFailed = stringResource(R.string.chat_transcription_failed)
	val playbackFailed = stringResource(R.string.chat_audio_play_failed)
	val scope = rememberCoroutineScope()
	val context = LocalContext.current
	var text by remember(initialText) { mutableStateOf(initialText) }
	var running by remember { mutableStateOf(false) }
	var saved by remember { mutableStateOf<ChatViewModel.GeneratedAudio?>(null) }
	var transcript by remember { mutableStateOf<String?>(null) }
	var error by remember { mutableStateOf<String?>(null) }
	var voices by remember { mutableStateOf<List<String>>(emptyList()) }
	var voice by remember { mutableStateOf<String?>(null) }
	var playing by remember { mutableStateOf(false) }

	// One player per dialog. It is released when the dialog leaves, so closing
	// the dialog stops the audio rather than leaving a player behind.
	val player = remember { WorkspaceAudioPlayer(context) }
	DisposableEffect(player) {
		player.onStarted = { playing = true }
		player.onFinished = { playing = false }
		player.onFailed = {
			playing = false
			error = playbackFailed
		}
		onDispose { player.release() }
	}

	LaunchedEffect(Unit) {
		val available = runCatching { onVoices() }.getOrDefault(emptyList())
		voices = available
		voice = available.firstOrNull { it == initialVoice } ?: available.firstOrNull()
	}

	fun playAudio(audio: ChatViewModel.GeneratedAudio) {
		val id = workspaceId
		if (id == null) {
			Diag.w("speech", "playback skipped: the workspace id is not loaded")
			error = playbackFailed
			return
		}
		player.stop()
		playing = false
		if (!player.play(id, audio.relativePath)) {
			Diag.w("speech", "playback did not start for ${audio.relativePath}")
			error = playbackFailed
		}
	}

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
					// A reply can be long; the field scrolls internally instead
					// of turning the dialog into a full-height page.
					maxLines = 8,
					enabled = !running,
				)
				// A single-voice backend gets no picker; a backend with a real
				// choice (DeepSeek has stella and tide) shows the ids it offers.
				if (voices.size > 1) {
					Text(
						text = stringResource(R.string.chat_voice),
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					FlowRowCompat {
						voices.forEach { id ->
							FilterChip(
								selected = voice == id,
								onClick = { voice = id },
								label = { Text(speechVoiceLabel(id)) },
								enabled = !running,
							)
						}
					}
				}
				if (running) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
					) {
						CircularProgressIndicator(strokeWidth = 2.dp)
						Text(stringResource(R.string.chat_synthesizing))
					}
				}
				saved?.let { audio ->
					Text(stringResource(R.string.chat_saved_to, audio.relativePath))
					// Playback is in-process: the point of the action is to hear
					// the answer, not to open a chooser and leave the app.
					TextButton(
						onClick = {
							if (playing) {
								player.stop()
								playing = false
							} else {
								playAudio(audio)
							}
						},
					) {
						Text(
							stringResource(
								if (playing) R.string.chat_stop else R.string.chat_play,
							),
						)
					}
					TextButton(
						enabled = !running,
						onClick = {
							running = true
							error = null
							transcript = null
							scope.launch {
								onTranscribe(audio.relativePath).fold(
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
					player.stop()
					playing = false
					scope.launch {
						onSpeak(text.trim(), voice).fold(
							onSuccess = {
								saved = it
								running = false
								// The generated file is what the user asked to
								// hear; start it without a second tap.
								playAudio(it)
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
private fun speechVoiceLabel(id: String): String = when (id) {
	"stella" -> stringResource(R.string.chat_voice_stella)
	"tide" -> stringResource(R.string.chat_voice_tide)
	else -> id
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
				// The backend accepts 480P/720P/1080P, 16:9/9:16/1:1 and
				// 2–30 seconds. These were free-text fields, so a typo only
				// surfaced as a provider error after the request was billed.
				Text(
					text = stringResource(R.string.chat_video_resolution),
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				FlowRowCompat {
					listOf("480P", "720P", "1080P").forEach { value ->
						FilterChip(
							selected = resolution == value,
							onClick = { resolution = value },
							label = { Text(value) },
							enabled = !running,
						)
					}
				}
				Text(
					text = stringResource(R.string.chat_video_ratio),
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				FlowRowCompat {
					listOf("16:9", "9:16", "1:1").forEach { value ->
						FilterChip(
							selected = ratio == value,
							onClick = { ratio = value },
							label = { Text(value) },
							enabled = !running,
						)
					}
				}
				Text(
					text = stringResource(R.string.chat_video_seconds),
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				FlowRowCompat {
					listOf(5, 10, 15, 30).forEach { value ->
						FilterChip(
							selected = duration == value.toString(),
							onClick = { duration = value.toString() },
							label = { Text("${value}s") },
							enabled = !running,
						)
					}
				}
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

