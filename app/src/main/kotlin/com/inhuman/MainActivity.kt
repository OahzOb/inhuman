package com.inhuman

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inhuman.core.ai.ssh.SshClient
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.BackupRepository
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.secret.SecureStore
import com.inhuman.core.design.InhumanTheme
import com.inhuman.core.design.LayoutPreference
import com.inhuman.core.design.LocalLayoutPreference
import com.inhuman.ui.ConversationsScreen
import com.inhuman.ui.ConversationsViewModel
import com.inhuman.ui.agents.AgentsScreen
import com.inhuman.ui.agents.AgentsViewModel
import com.inhuman.ui.chat.ChatScreen
import com.inhuman.ui.chat.ChatViewModel
import com.inhuman.ui.chat.SettingsViewModel
import com.inhuman.ui.files.FilesScreen
import com.inhuman.ui.files.FilesViewModel
import com.inhuman.ui.settings.SettingsScreen
import com.inhuman.ui.workspaces.WorkspaceDetailScreen
import com.inhuman.ui.workspaces.WorkspaceDetailViewModel
import com.inhuman.ui.workspaces.WorkspacesScreen
import com.inhuman.ui.workspaces.WorkspacesViewModel
import com.inhuman.web.WebToolProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

	/** A conversation requested by a notification tap, consumed by the root. */
	private val pendingConversationId = mutableStateOf<String?>(null)

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		if (savedInstanceState == null) {
			pendingConversationId.value =
				intent?.getStringExtra(TurnNotifications.EXTRA_CONVERSATION_ID)
		}
		setContent {
			InhumanTheme {
				// Named Root, not InhumanApp: InhumanApp is the Application
				// class in this same package, and a composable of the same name
				// is an overload ambiguity rather than a shadowing warning.
				InhumanRoot(
					deepLinkedConversation = pendingConversationId.value,
					onDeepLinkConsumed = { pendingConversationId.value = null },
				)
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		val conversation = intent.getStringExtra(TurnNotifications.EXTRA_CONVERSATION_ID)
		if (conversation != null) pendingConversationId.value = conversation
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
	val repository = remember { ConversationRepository(context) }
	val agents = remember { AgentRepository(context, secure) }
	val backup = remember { BackupRepository(context) }

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
			chatDialogOpen || filesDialogOpen -> Unit

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
			)
		}

		showAgents -> {
			val factory = remember(agents, repository) {
				viewModelFactory { AgentsViewModel(agents, repository, secure) }
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
						// The SSH client owns only the TOFU host-key file in
						// app storage. It stores no hosts and no credentials.
						sshTransport = SshClient(
							File(context.applicationContext.filesDir, "ssh/known_hosts"),
						),
						sshToolEnabled = {
							secure.get(SecureStore.KEY_SSH_TOOL_ENABLED) == "1"
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
				viewModelFactory { ConversationsViewModel(repository, agents) }
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
			)
		}
	}
	}
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
