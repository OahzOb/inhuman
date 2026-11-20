package com.inhuman

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inhuman.core.ai.oauth.OAuthProtocol
import com.inhuman.core.ai.ssh.SshClient
import com.inhuman.core.ai.ssh.SshLimits
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.BackupRepository
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.DataPaths
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.db.Database
import com.inhuman.core.data.secret.SecureStore
import com.inhuman.core.design.Hairline
import com.inhuman.core.design.InhumanTheme
import com.inhuman.core.design.LayoutPreference
import com.inhuman.core.design.LocalLayoutPreference
import com.inhuman.core.design.WideDialog
import com.inhuman.ui.ConversationsScreen
import com.inhuman.ui.ConversationsViewModel
import com.inhuman.ui.agents.AgentsScreen
import com.inhuman.ui.agents.AgentsViewModel
import com.inhuman.ui.backup.BackupReminderStore
import com.inhuman.ui.backup.BackupReminderUi
import com.inhuman.ui.chat.ChatScreen
import com.inhuman.ui.chat.ChatViewModel
import com.inhuman.ui.chat.SettingsViewModel
import com.inhuman.ui.files.FilesScreen
import com.inhuman.ui.files.FilesViewModel
import com.inhuman.ui.files.SharedContent
import com.inhuman.ui.files.saveSharedContent
import com.inhuman.ui.files.sharedContentFrom
import com.inhuman.ui.settings.SettingsScreen
import com.inhuman.ui.workspaces.WorkspaceDetailScreen
import com.inhuman.ui.workspaces.WorkspaceDetailViewModel
import com.inhuman.ui.workspaces.WorkspacesScreen
import com.inhuman.ui.workspaces.WorkspacesViewModel
import com.inhuman.web.BilibiliReadProvider
import com.inhuman.web.WebToolProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

	/** A conversation requested by a notification tap, consumed by the root. */
	private val pendingConversationId = mutableStateOf<String?>(null)

	/** An `ACTION_SEND` item from the share sheet, consumed by the root. */
	private val pendingShare = mutableStateOf<SharedContent?>(null)

	/** An `inhuman://oauth` callback, consumed after the settings screen uses it. */
	private val pendingOAuth = mutableStateOf<String?>(null)

	/**
	 * Make the first frame the default language.
	 *
	 * `InhumanApp` persists `zh-CN` when the system has no per-app locale, but a
	 * locale set during `Application.onCreate` does not reconfigure the process
	 * that is already starting: the first launch after install stayed English
	 * until it was restarted. The Activity base context is the earliest place
	 * whose resources Compose actually reads, and it is also the context the
	 * platform recreates when the user switches language.
	 */
	override fun attachBaseContext(newBase: Context) {
		val locales = newBase.getSystemService(LocaleManager::class.java)
			?.applicationLocales
			?.takeUnless { it.isEmpty }
		val tag = locales?.toLanguageTags() ?: "zh-CN"
		val configuration = Configuration(newBase.resources.configuration)
		configuration.setLocales(LocaleList.forLanguageTags(tag))
		super.attachBaseContext(newBase.createConfigurationContext(configuration))
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		if (savedInstanceState == null) {
			pendingConversationId.value =
				intent?.getStringExtra(TurnNotifications.EXTRA_CONVERSATION_ID)
		}
		// Parsed on every create, not only the first: a share must survive the
		// activity being recreated (rotation, process death) while its
		// destination picker is open. `onSharedConsumed` clears the action and
		// extras, so a handled share is not offered again after a later
		// recreate.
		pendingShare.value = sharedContentFrom(this, intent)
		oauthCallbackFrom(intent)?.let { pendingOAuth.value = it }
		setContent {
			InhumanTheme {
				// Named Root, not InhumanApp: InhumanApp is the Application
				// class in this same package, and a composable of the same name
				// is an overload ambiguity rather than a shadowing warning.
				InhumanRoot(
					deepLinkedConversation = pendingConversationId.value,
					onDeepLinkConsumed = { pendingConversationId.value = null },
					shared = pendingShare.value,
					onSharedConsumed = {
						pendingShare.value = null
						intent?.let { handled ->
							handled.removeExtra(Intent.EXTRA_STREAM)
							handled.removeExtra(Intent.EXTRA_TEXT)
							handled.action = null
						}
					},
					oauthCallback = pendingOAuth.value,
					onOAuthConsumed = { pendingOAuth.value = null },
				)
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		val conversation = intent.getStringExtra(TurnNotifications.EXTRA_CONVERSATION_ID)
		if (conversation != null) pendingConversationId.value = conversation
		sharedContentFrom(this, intent)?.let { pendingShare.value = it }
		oauthCallbackFrom(intent)?.let { pendingOAuth.value = it }
	}
}

/**
 * Where the app currently is.
 *
 * Plain state rather than a navigation library. With five screens and no deep
 * links, a navigation graph would be more machinery than the problem has -- and
 * Navigation 3's "you own the back stack" model means adopting it later is a
 * change of where this list lives, not a rewrite of the screens.
 */
@Composable
private fun InhumanRoot(
	deepLinkedConversation: String?,
	onDeepLinkConsumed: () -> Unit,
	shared: SharedContent?,
	onSharedConsumed: () -> Unit,
	oauthCallback: String?,
	onOAuthConsumed: () -> Unit,
) {
	val context = androidx.compose.ui.platform.LocalContext.current
	// One store and one repository for the process. The Keystore lookup inside
	// SecureStore is not free, and Room's concurrency is per-instance -- two
	// instances against one file would serialise badly and invite locking that
	// a single instance avoids. AgentRepository also caches the model list, so
	// a second instance would fetch it twice.
	val secure = remember { SecureStore(context) }
	var layoutPreference by remember {
		mutableStateOf(
			LayoutPreference.fromStored(secure.get(SecureStore.KEY_LAYOUT_PREFERENCE)),
		)
	}
	val db = remember { Database.open(context) }
	val repository = remember { ConversationRepository(db, DataPaths(context.filesDir)) }
	val agents = remember { AgentRepository(db, secure) }
	val backup = remember { BackupRepository(context) }
	val backupReminders = remember(secure) { BackupReminderStore(secure) }

	// Clear out workspace directories that no conversation refers to.
	//
	// Deleting a conversation removes its files first and its rows second, and
	// a crash in that window -- or a conversation abandoned part-way through
	// creation -- leaves a folder of the user's files that nothing in the UI can
	// see or delete. This is the sweep the delete path has always said would
	// find them.
	//
	// Off the main thread and after the first frame: it lists a directory and
	// runs one query, and nothing on screen depends on it. Silence is the
	// expected outcome on a healthy install, which is why nothing is shown.
	LaunchedEffect(Unit) {
		withContext(Dispatchers.IO) {
			runCatching { repository.sweepOrphanedWorkspaces() }
				.onFailure { Diag.w("workspace", "orphan sweep failed", it) }
			// Start the "never backed up" clock on this launch. Nothing is
			// written; this only makes the first reminder measurable.
			runCatching { backupReminders.ensureTracking(System.currentTimeMillis()) }
				.onFailure { Diag.w("backup", "reminder clock failed", it) }
		}
	}

	// Which conversation the chat screen is showing. Null means the list.
	//
	// ## Why these are `rememberSaveable` and not `remember`
	//
	// The Activity declares no `android:configChanges`, so a rotation destroys
	// and recreates it. Plain `remember` does not survive that, so turning a
	// tablet on its side while reading a reply dropped the user back to the
	// conversation list -- and because the view model is keyed and outlives the
	// Activity, the turn they were reading kept running and billing with
	// nothing on screen to show it.
	//
	// The same applies to the flags below: a rotation with the Files browser
	// open should leave it open.
	var openConversationId by rememberSaveable { mutableStateOf<String?>(null) }
	/** A message to reveal after opening from search or a notification. */
	var chatFocusMessageId by rememberSaveable { mutableStateOf<String?>(null) }
	var showSettings by rememberSaveable { mutableStateOf(false) }
	var showFiles by rememberSaveable { mutableStateOf(false) }
	var showAgents by rememberSaveable { mutableStateOf(false) }
	var showWorkspaces by rememberSaveable { mutableStateOf(false) }
	var startWorkspaceCreation by rememberSaveable { mutableStateOf(false) }
	var openWorkspaceId by rememberSaveable { mutableStateOf<String?>(null) }
	/** Open Settings with the backup fold expanded, from the home reminder. */
	var startBackupSection by rememberSaveable { mutableStateOf(false) }

	// A sign-in callback is useless unless the provider editor is visible to
	// complete it. Show Settings; the screen consumes the URI once.
	LaunchedEffect(oauthCallback) {
		if (oauthCallback != null) showSettings = true
	}
	/** Non-null while the home screen is showing a due-backup prompt. */
	var backupReminder by remember { mutableStateOf<BackupReminderUi?>(null) }
	/** Files opened from a workspace rather than from a chat. */
	var filesWorkspaceId by rememberSaveable { mutableStateOf<String?>(null) }
	/** Where Back returns after closing a conversation opened inside a workspace. */
	var chatOriginWorkspaceId by rememberSaveable { mutableStateOf<String?>(null) }

	val pendingConversation = deepLinkedConversation
	LaunchedEffect(pendingConversation) {
		if (pendingConversation != null) {
			showSettings = false
			showAgents = false
			showFiles = false
			showWorkspaces = false
			openWorkspaceId = null
			chatOriginWorkspaceId = null
			chatFocusMessageId = null
			openConversationId = pendingConversation
			onDeepLinkConsumed()
		}
	}

	// A share while a chat is the visible screen goes straight into that chat's
	// workspace: the user has already chosen the context by being there, and a
	// picker would be a second decision for the same act. Everywhere else the
	// target is chosen once, then the copy happens immediately -- no import
	// screen, no second copy step.
	var shareDestinations by remember { mutableStateOf<List<ConversationEntity>?>(null) }
	val shareScope = rememberCoroutineScope()
	LaunchedEffect(shared) {
		val content = shared ?: return@LaunchedEffect
		val target = openConversationId?.takeIf {
			!showSettings && !showAgents && !showWorkspaces && openWorkspaceId == null
		}
		if (target != null) {
			val saved = runCatching { saveSharedContent(context, repository, target, content) }
				.onFailure { Diag.w("share", "save threw", it) }
				.getOrDefault(false)
			Toast.makeText(
				context,
				if (saved) R.string.share_saved else R.string.share_save_failed,
				Toast.LENGTH_SHORT,
			).show()
			onSharedConsumed()
		} else {
			shareDestinations = withContext(Dispatchers.IO) {
				runCatching { repository.all() }
					.onFailure { Diag.w("share", "destination list failed", it) }
					.getOrElse { emptyList() }
			}
		}
	}

	// The backup reminder is evaluated when the home screen is the visible
	// screen: on launch, and after coming back from Settings. It is not a
	// background job and never writes a backup; a due reminder is a banner with
	// a button, and "Later" snoozes it for a few days.
	LaunchedEffect(
		openConversationId,
		showSettings,
		showAgents,
		showFiles,
		showWorkspaces,
		openWorkspaceId,
	) {
		val home = openConversationId == null && !showSettings && !showAgents &&
			!showFiles && !showWorkspaces && openWorkspaceId == null
		if (!home) return@LaunchedEffect
		backupReminder = withContext(Dispatchers.IO) {
			val hasData = runCatching { repository.all().isNotEmpty() }
				.onFailure { Diag.w("backup", "reminder check failed", it) }
				.getOrDefault(false)
			runCatching { backupReminders.evaluate(System.currentTimeMillis(), hasData) }
				.onFailure { Diag.w("backup", "reminder read failed", it) }
				.getOrNull()
		}
	}

	// Whether a screen has an internal dialog open, so Back closes that before
	// it navigates. Without this, Back on a screen showing a dialog would leave
	// the screen and discard whatever the dialog was asking about.
	//
	// Dialog flags are deliberately *not* saved: a dialog is a question being
	// asked right now, and restoring a screen into "a dialog is open" after a
	// rotation would leave Back pointing at something that is not there.
	var chatDialogOpen by remember { mutableStateOf(false) }
	var filesDialogOpen by remember { mutableStateOf(false) }

	// The file browser's current directory, so Back steps up a level before it
	// leaves the browser. Without it, Back from three folders deep would exit
	// the screen entirely, which is not what "up" means anywhere else.
	//
	// Transient for the same reason as the dialog flags: the browser's own
	// view model is keyed by conversation and re-derives its path.
	var filesAtRoot by remember { mutableStateOf(true) }

	// Assigned by the Files branch, called by the Back handler. A var rather
	// than passing the view model up, because the handler is composed before the
	// branch that creates it and cannot close over it.
	var filesUpOneLevel: () -> Unit by remember { mutableStateOf({}) }

	// Which screen is on top, in the same terms the `when` below uses. A crash
	// report without this cannot tell "the chat screen was open" from "the view
	// model was running in the background"; one line per navigation is enough.
	LaunchedEffect(openConversationId, showSettings, showFiles, showAgents, showWorkspaces, openWorkspaceId) {
		val screen = when {
			showSettings -> "settings"
			showAgents -> "agents"
			showFiles && filesWorkspaceId != null -> "workspace-files"
			showFiles && openConversationId != null -> "files"
			openConversationId != null -> "chat"
			openWorkspaceId != null -> "workspace"
			showWorkspaces -> "workspaces"
			else -> "conversations"
		}
		Diag.d(
			"ui",
			"screen=$screen conv=${Diag.shortId(openConversationId)} " +
				"workspace=${Diag.shortId(openWorkspaceId ?: filesWorkspaceId)}",
		)
	}

	/**
	 * Make the system Back button walk the app's own history.
	 *
	 * ## Why this is needed at all
	 *
	 * The screens are chosen by a `when` over state, not by a navigation
	 * library, so nothing has registered a back stack with the framework. With no
	 * `OnBackPressedCallback` installed, the default applies and Back finishes
	 * the Activity -- so from any screen, including four levels deep, one press
	 * closed the app. That is what the user hit.
	 *
	 * ## Why the order is reversed
	 *
	 * Registered once, at the root, rather than one handler per screen. Several
	 * enabled handlers all fire on a single press, so per-screen registration
	 * would run two or three levels of navigation at once -- Back from a
	 * conversation inside Files inside Settings would jump straight to the list.
	 * One handler reading the whole state makes the order explicit.
	 *
	 * The ordering itself: a dialog first because it is the most recent thing the
	 * user opened, then a directory level, then the overlays in the order they
	 * can be stacked, and finally the conversation. Only when nothing is left
	 * does the handler disable itself and let the press reach the framework,
	 * which closes the app from the list screen -- the one place that is right.
	 */
	BackHandler(
		enabled = openConversationId != null || showSettings || showFiles ||
			showAgents || showWorkspaces || openWorkspaceId != null,
	) {
		when {
			// AlertDialog handles its own dismissal through onDismissRequest and
			// registers its handler later in the composition, so it runs first.
			// Returning here only stops this handler from also navigating.
			chatDialogOpen || filesDialogOpen || shareDestinations != null -> Unit

			showFiles && !filesAtRoot -> filesUpOneLevel()

			showFiles -> {
				showFiles = false
				filesWorkspaceId = null
			}
			showAgents -> showAgents = false
			showSettings -> showSettings = false
			openConversationId != null -> {
				openConversationId = null
				val origin = chatOriginWorkspaceId
				chatOriginWorkspaceId = null
				if (origin != null) {
					openWorkspaceId = origin
					showWorkspaces = true
				}
			}
			showWorkspaces && openWorkspaceId == null -> showWorkspaces = false
			openWorkspaceId != null -> {
				openWorkspaceId = null
				showWorkspaces = true
			}
		}
	}

	CompositionLocalProvider(LocalLayoutPreference provides layoutPreference) {
	when {
		showSettings -> {
			val factory = remember(secure, agents, repository) {
				viewModelFactory {
					SettingsViewModel(
						context = context.applicationContext,
						secure,
						agents,
						repository,
						backup,
						File(context.applicationContext.filesDir, "ssh/known_hosts"),
					)
				}
			}
			val vm: SettingsViewModel = viewModel(factory = factory)
			SettingsScreen(
				viewModel = vm,
				onBack = { showSettings = false },
				onLayoutPreferenceChange = { layoutPreference = it },
				startBackup = startBackupSection,
				onBackupStarted = { startBackupSection = false },
				oauthCallback = oauthCallback,
				onOAuthConsumed = onOAuthConsumed,
			)
		}

		showAgents -> {
			val factory = remember(agents, repository) {
				viewModelFactory {
					AgentsViewModel(context.applicationContext, agents, repository, secure)
				}
			}
			val vm: AgentsViewModel = viewModel(factory = factory)
			AgentsScreen(viewModel = vm, onBack = { showAgents = false })
		}

		showFiles && filesWorkspaceId != null -> {
			val id = filesWorkspaceId!!
			val factory = remember(id) {
				viewModelFactory {
					FilesViewModel(
						context = context.applicationContext,
						repository = repository,
						requestedWorkspaceId = id,
					)
				}
			}
			val vm: FilesViewModel = viewModel(key = "workspace-files-$id", factory = factory)
			filesUpOneLevel = { vm.navigateUp() }
			val filesState by vm.state.collectAsStateWithLifecycle()
			LaunchedEffect(filesState.currentPath) {
				filesAtRoot = filesState.currentPath.isEmpty()
			}
			FilesScreen(
				viewModel = vm,
				onBack = {
					showFiles = false
					filesWorkspaceId = null
				},
				onDialogVisibilityChange = { filesDialogOpen = it },
			)
		}

		showFiles && openConversationId != null -> {
			val id = openConversationId!!
			val factory = remember(id) {
				viewModelFactory {
					FilesViewModel(
						context = context.applicationContext,
						repository = repository,
						conversationId = id,
					)
				}
			}
			val vm: FilesViewModel = viewModel(key = "files-$id", factory = factory)
			// An escape hatch for the one Back action that belongs to a screen
			// rather than to the navigation state: stepping up a directory.
			// Hoisted into a lambda the handler can call, because the handler
			// runs outside this branch.
			filesUpOneLevel = { vm.navigateUp() }
			val filesState by vm.state.collectAsStateWithLifecycle()
			LaunchedEffect(filesState.currentPath) {
				filesAtRoot = filesState.currentPath.isEmpty()
			}
			FilesScreen(
				viewModel = vm,
				onBack = { showFiles = false },
				onDialogVisibilityChange = { filesDialogOpen = it },
			)
		}

		openConversationId != null -> {
			// Keyed by conversation id so switching conversations creates a new
			// view model rather than reusing one that is still holding the
			// previous transcript.
			val id = openConversationId!!
			val factory = remember(id, agents, secure) {
				viewModelFactory {
					// The SSH client owns only the TOFU host-key file in
					// app storage. It stores no hosts and no credentials. One
					// instance serves the exec and SFTP seams, so both use the
					// same known_hosts file.
					val ssh = SshClient(
						File(context.applicationContext.filesDir, "ssh/known_hosts"),
					)
					ChatViewModel(
						context = context.applicationContext,
						repository = repository,
						agents = agents,
						secure = secure,
						conversationId = id,
						// Search and fetch are separate seams with separate
						// credentials. Both are resolved per turn, so changing a
						// web backend or key in Settings takes effect without
						// reopening the conversation.
						searchProvider = { WebToolProviders.search(secure) },
						fetchProvider = { WebToolProviders.fetch(secure) },
						sshTransport = ssh,
						sshFileTransport = ssh,
						sshToolEnabled = {
							secure.get(SecureStore.KEY_SSH_TOOL_ENABLED) == "1"
						},
						sshLimits = {
							SshLimits.fromStored(
								commandTimeoutSeconds = secure.get(SecureStore.KEY_SSH_COMMAND_TIMEOUT_SECONDS),
								outputBytes = secure.get(SecureStore.KEY_SSH_OUTPUT_BYTES),
								listEntries = secure.get(SecureStore.KEY_SSH_LIST_ENTRIES),
								readBytes = secure.get(SecureStore.KEY_SSH_READ_BYTES),
							)
						},
						workspaceToolsEnabled = { AppCapabilities.workspaceOk },
						suggestionsEnabled = {
							secure.get(SecureStore.KEY_SUGGESTIONS_ENABLED) == "1"
						},
						// The anonymous Bilibili source has no credential to be
						// missing; the switch alone decides whether the tools
						// exist. Read per turn like the SSH switch.
						bilibiliReadService = { BilibiliReadProvider.service() },
						bilibiliEnabled = {
							secure.get(SecureStore.KEY_BILIBILI_TOOL_ENABLED) == "1"
						},
						onTurnStatus = { status ->
							TurnNotifications.update(context.applicationContext, status)
						},
					)
				}
			}
			val vm: ChatViewModel = viewModel(key = "chat-$id", factory = factory)
			ChatScreen(
				viewModel = vm,
				focusMessageId = chatFocusMessageId,
				onFocusConsumed = { chatFocusMessageId = null },
				onBack = {
					openConversationId = null
					chatFocusMessageId = null
					val origin = chatOriginWorkspaceId
					chatOriginWorkspaceId = null
					if (origin != null) {
						openWorkspaceId = origin
						showWorkspaces = true
					}
				},
				onOpenSettings = { showSettings = true },
				onOpenFiles = { showFiles = true },
				onOpenConversation = { newId ->
					openConversationId = newId
					chatFocusMessageId = null
				},
				onDialogVisibilityChange = { chatDialogOpen = it },
			)
		}

		openWorkspaceId != null -> {
			val id = openWorkspaceId!!
			val factory = remember(id, repository, agents) {
				viewModelFactory {
					WorkspaceDetailViewModel(context.applicationContext, repository, agents, id)
				}
			}
			val vm: WorkspaceDetailViewModel = viewModel(key = "workspace-$id", factory = factory)
			WorkspaceDetailScreen(
				viewModel = vm,
				onBack = {
					openWorkspaceId = null
					showWorkspaces = true
				},
				onOpenConversation = { conversationId ->
					chatOriginWorkspaceId = id
					openConversationId = conversationId
				},
				onOpenFiles = {
					filesWorkspaceId = id
					showFiles = true
				},
				onDeleted = {
					openWorkspaceId = null
					showWorkspaces = true
				},
			)
		}

		showWorkspaces -> {
			val factory = remember(repository) {
				viewModelFactory { WorkspacesViewModel(repository) }
			}
			val vm: WorkspacesViewModel = viewModel(factory = factory)
			WorkspacesScreen(
				viewModel = vm,
				startCreating = startWorkspaceCreation,
				onCreateStarted = { startWorkspaceCreation = false },
				onBack = { showWorkspaces = false },
				onOpenWorkspace = { id ->
					showWorkspaces = true
					openWorkspaceId = id
				},
			)
		}

		else -> {
			val factory = remember(repository, agents) {
				viewModelFactory {
					ConversationsViewModel(context.applicationContext, repository, agents)
				}
			}
			val vm: ConversationsViewModel = viewModel(factory = factory)
			ConversationsScreen(
				viewModel = vm,
				onOpenConversation = {
					chatOriginWorkspaceId = null
					chatFocusMessageId = null
					openConversationId = it
				},
				onOpenSearchHit = { hit ->
					chatOriginWorkspaceId = null
					chatFocusMessageId = hit.messageId
					openConversationId = hit.conversationId
				},
				onOpenSettings = { showSettings = true },
				onOpenAgents = { showAgents = true },
				onOpenWorkspaces = { showWorkspaces = true },
				onNewWorkspace = {
					startWorkspaceCreation = true
					showWorkspaces = true
				},
				sshToolEnabled = secure.get(SecureStore.KEY_SSH_TOOL_ENABLED) == "1",
				bilibiliEnabled = secure.get(SecureStore.KEY_BILIBILI_TOOL_ENABLED) == "1",
				backupReminder = backupReminder,
				onOpenBackup = {
					backupReminder = null
					startBackupSection = true
					showSettings = true
				},
				onLaterBackup = {
					backupReminder = null
					shareScope.launch(Dispatchers.IO) {
						backupReminders.snooze(System.currentTimeMillis())
					}
				},
			)
		}
	}

		if (shared != null && shareDestinations != null) {
			ShareDestinationDialog(
				conversations = shareDestinations.orEmpty(),
				onPick = { conversation ->
					shareDestinations = null
					shareScope.launch {
						val saved = runCatching {
							saveSharedContent(context, repository, conversation.id, shared)
						}.onFailure { Diag.w("share", "save threw", it) }
							.getOrDefault(false)
						Toast.makeText(
							context,
							if (saved) R.string.share_saved else R.string.share_save_failed,
							Toast.LENGTH_SHORT,
						).show()
						onSharedConsumed()
					}
				},
				onDismiss = {
					shareDestinations = null
					onSharedConsumed()
				},
			)
		}
	}
}

/**
 * Pick the conversation a shared item is copied into when no chat is on screen.
 *
 * The list is the app's own conversations, not a file browser: a share is one
 * item and the destination is a workspace, so the choice a user can actually
 * make is which conversation it belongs to. An empty list is the cold-start
 * case with no conversations yet; creating one is a separate action.
 */
@Composable
private fun ShareDestinationDialog(
	conversations: List<ConversationEntity>,
	onPick: (ConversationEntity) -> Unit,
	onDismiss: () -> Unit,
) {
	WideDialog(
		onDismissRequest = onDismiss,
		title = stringResource(R.string.share_destination_title),
		dismissLabel = stringResource(R.string.common_cancel),
		maxBodyHeight = 420.dp,
	) {
		if (conversations.isEmpty()) {
			Text(
				text = stringResource(R.string.share_destination_empty),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		} else {
			conversations.forEachIndexed { index, conversation ->
				if (index > 0) Hairline()
				Text(
					text = conversation.title,
					style = MaterialTheme.typography.bodyLarge,
					modifier = Modifier
						.fillMaxWidth()
						.clickable { onPick(conversation) }
						.padding(vertical = 12.dp),
				)
			}
		}
	}
}

/**
 * The `inhuman://oauth?code=...&state=...` callback, or null.
 *
 * The manifest filter is deliberately narrow, but an exported activity can be
 * started with any intent; the state check and the pending flow live in
 * `OAuthSession`, not here.
 */
private fun oauthCallbackFrom(intent: Intent?): String? {
	val data = intent?.data ?: return null
	return data.takeIf {
		it.scheme == OAuthProtocol.CALLBACK_SCHEME &&
			it.host == OAuthProtocol.CALLBACK_HOST
	}?.toString()
}

/**
 * A [ViewModelProvider.Factory] for a view model with constructor arguments.
 *
 * Written by hand rather than pulled from a DI framework, which is why the
 * app declares no DI dependency at all: the graph is small enough to read in
 * one screen, and a container would be more configuration than the thing it
 * configures.
 */
private inline fun <reified VM : ViewModel> viewModelFactory(
	crossinline create: () -> VM,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
	@Suppress("UNCHECKED_CAST")
	override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}
