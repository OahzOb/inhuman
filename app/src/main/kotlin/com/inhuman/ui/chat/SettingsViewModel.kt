package com.inhuman.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.R
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.ai.bilibili.BilibiliApiException
import com.inhuman.core.ai.bilibili.BilibiliAuth
import com.inhuman.core.ai.bilibili.BilibiliCredentials
import com.inhuman.core.ai.bilibili.BilibiliQr
import com.inhuman.core.ai.bilibili.BilibiliQrState
import com.inhuman.ui.bilibili.BiliQrRenderer
import com.inhuman.core.ai.capability.ProviderCapabilities
import com.inhuman.core.ai.model.ModelCapabilities
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.ai.oauth.OAuthConfig
import com.inhuman.core.ai.oauth.OAuthProtocol
import com.inhuman.core.ai.provider.KnownProvider
import com.inhuman.core.ai.provider.ProviderCatalog
import com.inhuman.core.ai.provider.ProviderFactory
import com.inhuman.core.ai.ssh.SshLimits
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.BackupPreview
import com.inhuman.core.data.BackupRepository
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.db.ProviderIds
import com.inhuman.core.data.db.ProviderKinds
import com.inhuman.core.data.db.ProviderProfileEntity
import com.inhuman.core.data.db.ProviderReasoning
import com.inhuman.core.data.db.UsageRow
import com.inhuman.core.data.oauth.OAuthSession
import com.inhuman.core.data.secret.SecureStore
import com.inhuman.core.ai.ssh.SshHost
import com.inhuman.ui.ssh.SshHostStore
import com.inhuman.ui.ssh.SshHostSummary
import com.inhuman.ui.ssh.toSummary
import com.inhuman.ui.backup.BackupReminderPolicy
import com.inhuman.ui.backup.backupArchiveFileName
import com.inhuman.ui.backup.backupFolderDisplayName
import com.inhuman.diagnostics.AppDiagnostics
import com.inhuman.ui.settings.UsagePeriod
import com.inhuman.ui.settings.UsageReport
import com.inhuman.ui.settings.buildUsageReport
import com.inhuman.updates.UpdateInfo
import com.inhuman.updates.UpdateInstaller
import com.inhuman.updates.isNewerVersion
import com.inhuman.updates.logUploadUrl
import com.inhuman.updates.normalizeServerUrl
import com.inhuman.updates.parseUpdateInfo
import com.inhuman.updates.updateManifestUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One host key the SSH client pinned on first use. */
data class KnownHost(val host: String, val fingerprint: String)

/** One provider being edited. The key is prefilled from SecureStore. */
data class ProviderDraft(
	val id: String,
	val isNew: Boolean,
	val isBuiltIn: Boolean,
	val name: String,
	val kind: String,
	val baseUrl: String,
	val reasoningMode: String,
	val includeStreamUsage: Boolean,
	val manualModels: String,
	val keyInput: String,
	val hasKey: Boolean,
	/**
	 * Whether the key currently in `SecureStore` belongs to this provider.
	 *
	 * `hasKey` also turns true while the user types a replacement, so it cannot
	 * answer "is there a saved credential to delete?". OAuth is deliberately not
	 * included: signing out is its own action.
	 */
	val hasStoredKey: Boolean = false,
	/** Known provider: endpoint/kind come from the catalog, so only the key is asked. */
	val isPreset: Boolean = false,
	val testing: Boolean = false,
	val status: String? = null,
	val statusIsError: Boolean = false,
)

/**
 * The sign-in dialog's state.
 *
 * A custom scheme callback is a public entry point, so this is only a view of
 * [OAuthSession]'s pending state; the verifier and tokens never enter this
 * object, and the state check happens in the repository.
 */
data class OAuthLoginUi(
	val profileId: String,
	val providerName: String,
	val clientId: String = "",
	val authorizationEndpoint: String = "",
	val tokenEndpoint: String = "",
	val scopes: String = "",
	val clientSecret: String = "",
	val configured: Boolean = false,
	val signedIn: Boolean = false,
	val expiresAt: Long? = null,
	val busy: Boolean = false,
	val status: String? = null,
	val statusIsError: Boolean = false,
	/** Set once, for the UI to hand to the browser; cleared by the UI. */
	val authorizeUrl: String? = null,
) {
	val canBegin: Boolean get() = !busy && clientId.isNotBlank() &&
		authorizationEndpoint.startsWith("https://") &&
		tokenEndpoint.startsWith("https://")

	fun toConfig(): OAuthConfig = OAuthConfig(
		authorizationEndpoint = authorizationEndpoint.trim(),
		tokenEndpoint = tokenEndpoint.trim(),
		clientId = clientId.trim(),
		scopes = scopes.split(' ', ',', '\n').map { it.trim() }.filter { it.isNotEmpty() },
		redirectUri = OAuthProtocol.DEFAULT_REDIRECT_URI,
		clientSecret = clientSecret.takeIf { it.isNotBlank() },
	)
}

data class SettingsUiState(
	/** Result of the last demo/SSH action. */
	val status: String? = null,
	val statusIsError: Boolean = false,
	val providers: List<ProviderProfileEntity> = emptyList(),
	val editingProvider: ProviderDraft? = null,
	val oauthLogin: OAuthLoginUi? = null,
	/** Every saved SSH host, without credentials. */
	val sshHosts: List<SshHostSummary> = emptyList(),
	val providerStatus: String? = null,
	val providerStatusIsError: Boolean = false,
	val webSearchBackend: String = "deepseek",
	val webFetchBackend: String = "deepseek",
	val webDeepSeekKeyInput: String = "",
	val webExaKeyInput: String = "",
	val hasWebDeepSeekKey: Boolean = false,
	val hasWebExaKey: Boolean = false,
	val webStatus: String? = null,
	val webStatusIsError: Boolean = false,
	/**
	 * Where each paid media tool gets its key, endpoint and model.
	 *
	 * The value is derived from `SecureStore` on every screen entry; it is not a
	 * database row, for the same reason the web backends are not: a secret store
	 * is the only place a key can live, and the choice is useless without it.
	 */
	val mediaConfigs: Map<MediaCapability, MediaToolConfig> = emptyMap(),
	val mediaDraft: MediaToolDraft? = null,
	val mediaStatus: String? = null,
	val mediaStatusIsError: Boolean = false,
	/** Reply read-aloud settings; the same SecureStore keys the chat reads. */
	val autoReadReplies: Boolean = false,
	val speechVoice: String = "",
	/** Backup export/import progress and result. */
	val backupBusy: Boolean = false,
	val backupStatus: String? = null,
	val backupStatusIsError: Boolean = false,
	/** Set after a successful import; the app must restart to apply it. */
	val backupNeedsRestart: Boolean = false,
	/** What the selected backup contains, after decryption and before import. */
	val backupPreview: BackupPreview? = null,
	/** Whether the data replaced by the last import can still be restored. */
	val backupCanUndo: Boolean = false,
	/** Epoch millis of the last successful export; 0 means never. */
	val backupLastExportAt: Long = 0L,
	/** Reminder period in days; 0 is off. */
	val backupReminderDays: Int = 30,
	/** Remembered SAF directory tree URI for one-tap encrypted backup exports. */
	val backupFolderUri: String? = null,
	/** Human-readable label extracted from [backupFolderUri]. */
	val backupFolderName: String? = null,
	/** True when [backupFolderUri] is set but its persistable write grant is gone. */
	val backupFolderPermissionLost: Boolean = false,
	/** Reply from the built-in scripted source instead of DeepSeek. */
	val demoMode: Boolean = false,
	/** Whether the follow-up tool is available to assistants at all. */
	val suggestionsEnabled: Boolean = false,
	/**
	 * Whether the anonymous Bilibili read tools are available at all.
	 *
	 * Off by default. Turning it on makes the four `bili_*` tools selectable on
	 * an assistant; it grants nothing by itself.
	 */
	val bilibiliEnabled: Boolean = false,
	/**
	 * Whether an optional read-only Bilibili account is linked.
	 *
	 * The login is what unlocks subtitles, Bilibili's AI summaries and comment
	 * pages after the first; the anonymous reads never depend on it.
	 */
	val bilibiliLoggedIn: Boolean = false,
	/** The linked account's display name, for the settings row. */
	val bilibiliLoginName: String = "",
	/** Non-null while the QR dialog has an image to show. */
	val bilibiliLoginQr: android.graphics.Bitmap? = null,
	/** The dialog is generating or polling even before an image exists. */
	val bilibiliLoginRunning: Boolean = false,
	/** One line under the login row / inside the dialog. */
	val bilibiliLoginStatus: String? = null,
	val bilibiliLoginStatusIsError: Boolean = false,
	/**
	 * Global ceiling on assistant/tool rounds in one turn.
	 *
	 * A round may contain several tool calls, so this is not a call count.
	 * Stored under the same key the chat menu writes, so both entries stay in
	 * sync; the value is a budget, not an "unlimited" mode.
	 */
	val toolRoundLimit: Int = ChatViewModel.DEFAULT_TOOL_ROUNDS,
	/** Total individual tool calls across all rounds in one turn. */
	val toolCallLimit: Int = ChatViewModel.DEFAULT_TOOL_CALLS,
	/** Auto follows the window; Compact/Comfortable override the shell. */
	val layoutPreference: com.inhuman.core.design.LayoutPreference =
		com.inhuman.core.design.LayoutPreference.Auto,
	/**
	 * Whether a request marks when each user message was sent.
	 *
	 * Request formatting, not a capability: no tool, approval or workspace
	 * boundary reads it, and no stored message is changed. See
	 * `TimeHints.kt` for the rule and `docs/06-agent-loop-ui.md` for why it
	 * lives on the wire only.
	 */
	val timeHints: TimeHintPolicy = TimeHintPolicy.DEFAULT,
	/**
	 * The global SSH-tool switch.
	 *
	 * Off by default. Turning it on makes `ssh_exec` available to assistants
	 * that explicitly select it; the first call in each conversation still
	 * asks for the user's acknowledgement.
	 */
	val sshToolEnabled: Boolean = false,
	/** App-wide SSH ceilings; the model may only ask for less. */
	val sshLimits: SshLimits = SshLimits(),
	/**
	 * The global URL-download switch.
	 *
	 * Off by default. Turning it on makes `download_url` selectable on an
	 * assistant; the first call in each conversation still asks for a
	 * capability acknowledgement, because the app then makes an outbound
	 * request the model chose and a URL can carry data out.
	 */
	val downloadEnabled: Boolean = false,
	/**
	 * The global approval rules, by tool name.
	 *
	 * Absent means [ToolPolicy.Free], which is why this is a map rather than a
	 * value per tool: a tool with no rule has no row in the table either, and
	 * `ToolPolicy.Free` is stored by *deleting* rather than by writing "Free".
	 * "Configured to run it" and "not configured" are different states and the
	 * difference is worth keeping, so that a future default change reaches
	 * everyone who never expressed an opinion.
	 */
	val approvalRules: Map<String, ToolPolicy> = emptyMap(),
	/** Host keys pinned by the SSH client, in file order. */
	val knownHosts: List<KnownHost> = emptyList(),
	/** Result of the last forget action, shown in the host-key dialog. */
	val knownHostsStatus: String? = null,
	/** LAN/Tailscale base URL used by both the updater and the log upload. */
	val localServerUrl: String = "",
	val currentVersionName: String = "",
	val currentVersionCode: Long = 0,
	val updateChecking: Boolean = false,
	val updateDownloading: Boolean = false,
	/** 0..100 while downloading, null otherwise. */
	val updateProgress: Int? = null,
	val updateInfo: UpdateInfo? = null,
	val updateStatus: String? = null,
	val updateStatusIsError: Boolean = false,
	val updateReadyToInstall: Boolean = false,
	val logUploading: Boolean = false,
	val logUploadStatus: String? = null,
	val logUploadStatusIsError: Boolean = false,
	/** A manual, bounded copy of the diagnostic lines, for one reproduction. */
	val recordingActive: Boolean = false,
	val recordingBytes: Long = 0,
	/**
	 * The last usage read, or null until the first one completes.
	 *
	 * Refreshed when Settings is shown rather than on every turn: a turn can end
	 * while this screen is closed, and the view model outlives the composition.
	 */
	val usage: UsageReport? = null,
	val usagePeriod: UsagePeriod = UsagePeriod.SevenDays,
	val usageLoading: Boolean = false,
	val usageFailed: Boolean = false,
)

/**
 * Provider settings.
 *
 * The "Test connection" action exists because the failure it catches is the one
 * a user cannot diagnose: an invalid or unfunded API key produces an HTTP error
 * on the first message, which looks like the app being broken. Calling
 * `GET /models` turns that into an explicit answer, and it doubles as a way to
 * confirm the model list the app will offer.
 */
class SettingsViewModel(
	private val context: Context,
	private val secure: SecureStore,
	private val agents: AgentRepository,
	private val repository: ConversationRepository,
	private val backup: BackupRepository,
	/** `filesDir/ssh/known_hosts`; the same file `SshClient` pins into. */
	private val knownHostsFile: File,
) : ViewModel() {

	private val savedSshHostStore = SshHostStore(secure)

	private val _state = MutableStateFlow(SettingsUiState())
	val state: StateFlow<SettingsUiState> = _state.asStateFlow()

	/** Held only between a successful preview and the confirm button. */
	private var pendingImportUri: Uri? = null
	private var pendingImportPassphrase: CharArray? = null

	/**
	 * Used only by the updater and the log upload. Both are explicit user
	 * actions; neither is available to the agent and neither is retried in the
	 * background.
	 */
	private val http = OkHttpClient.Builder()
		.connectTimeout(8, TimeUnit.SECONDS)
		.readTimeout(30, TimeUnit.SECONDS)
		.build()

	/** PKCE sign-in state; tokens are stored through SecureStore. */
	private val oauth = OAuthSession(secure)

	init {
		_state.update { it.copy(sshHosts = savedSshHosts()) }
		refreshRecordingState()
	}

	/** Every saved host as the UI may see it: no password, key or passphrase. */
	fun savedSshHosts(): List<SshHostSummary> =
		savedSshHostStore.load().map { it.toSummary() }

	/**
	 * Save one address-book entry. A null [id] creates a new one.
	 *
	 * There is no credential here: the app never stores one for the agent to
	 * use, so there is nothing to keep blank and nothing to preserve. A blank
	 * fingerprint keeps the previous pin, because clearing it is a deliberate
	 * act (forget the key) rather than the default result of editing a name.
	 */
	fun saveSshHost(
		id: String?,
		name: String,
		host: String,
		port: Int,
		username: String,
		hostKeyFingerprint: String?,
	) {
		val previous = id?.let { savedSshHostStore.find(it) }
		val saved = SshHost(
			id = id ?: savedSshHostStore.newId(),
			name = name.trim(),
			host = host.trim(),
			port = port.coerceIn(1, 65535),
			username = username.trim(),
			hostKeyFingerprint = hostKeyFingerprint?.trim()?.takeIf { it.isNotBlank() }
				?: previous?.hostKeyFingerprint,
		)
		savedSshHostStore.upsert(saved)
		_state.update { it.copy(sshHosts = savedSshHosts()) }
	}

	/**
	 * Delete one host.
	 *
	 * Conversations that selected it keep the id; it resolves to nothing and
	 * becomes unavailable, which is the intended failure -- deleting a host in
	 * Settings must not silently hand a conversation a different machine.
	 */
	fun deleteSshHost(id: String) {
		savedSshHostStore.remove(id)
		_state.update { it.copy(sshHosts = savedSshHosts()) }
	}

	/** The verified download waiting for the Install button. */
	private var downloadedApk: File? = null

	/**
	 * The rows behind the last Usage read.
	 *
	 * Kept so changing the period filters what is already on hand instead of
	 * running the query again. Null until the first read succeeds, which is why
	 * a period change during that read is harmless.
	 */
	private var usageRows: List<UsageRow>? = null

	/**
	 * The maintenance cost rows behind the same read.
	 *
	 * A summary request is not a message, so `usageRows` cannot see it; without
	 * this the automatic maintenance a continuous session runs would be spend
	 * the overview silently omits.
	 */
	private var usageMaintenanceRuns: List<com.inhuman.core.data.db.MaintenanceRunEntity> =
		emptyList()

	/** One definition of "paid": the catalogue's flag, not a second list here. */
	private val paidToolNames: Set<String> =
		ToolCatalog.all.asSequence().filter { it.paid }.map { it.name }.toSet()

	/**
	 * Orders the two asynchronous paths that publish a usage report.
	 *
	 * A period change while a refresh is in flight can otherwise finish second
	 * with the old window, so the chips said "today" and the numbers were last
	 * week's. Only the newest build is allowed to publish.
	 */
	private var usageRevision = 0

	init {
		val packageInfo = runCatching {
			context.packageManager.getPackageInfo(context.packageName, 0)
		}.getOrNull()
		_state.value = SettingsUiState(
			demoMode = secure.get(SecureStore.KEY_DEMO_MODE) == "1",
			suggestionsEnabled = secure.get(SecureStore.KEY_SUGGESTIONS_ENABLED) == "1",
			bilibiliEnabled = secure.get(SecureStore.KEY_BILIBILI_TOOL_ENABLED) == "1",
			bilibiliLoggedIn = BilibiliCredentials.fromStoredJson(
				secure.get(SecureStore.KEY_BILIBILI_SESSION),
			) != null,
			bilibiliLoginName = BilibiliCredentials.fromStoredJson(
				secure.get(SecureStore.KEY_BILIBILI_SESSION),
			)?.userName.orEmpty(),
			toolRoundLimit = readToolRoundLimit(),
			toolCallLimit = readToolCallLimit(),
			layoutPreference = com.inhuman.core.design.LayoutPreference.fromStored(
				secure.get(SecureStore.KEY_LAYOUT_PREFERENCE),
			),
			timeHints = TimeHintPolicy.decode(
				secure.get(SecureStore.KEY_TIME_HINTS_MODE),
				secure.get(SecureStore.KEY_TIME_HINTS_GAP_MINUTES),
			),
			sshToolEnabled = secure.get(SecureStore.KEY_SSH_TOOL_ENABLED) == "1",
			downloadEnabled = secure.get(SecureStore.KEY_DOWNLOAD_TOOL_ENABLED) == "1",
			sshLimits = SshLimits.fromStored(
				commandTimeoutSeconds = secure.get(SecureStore.KEY_SSH_COMMAND_TIMEOUT_SECONDS),
				outputBytes = secure.get(SecureStore.KEY_SSH_OUTPUT_BYTES),
				listEntries = secure.get(SecureStore.KEY_SSH_LIST_ENTRIES),
				readBytes = secure.get(SecureStore.KEY_SSH_READ_BYTES),
			),
			webSearchBackend = secure.get(SecureStore.KEY_WEB_SEARCH_BACKEND) ?: "deepseek",
			webFetchBackend = secure.get(SecureStore.KEY_WEB_FETCH_BACKEND) ?: "deepseek",
			webDeepSeekKeyInput = secure.get(SecureStore.KEY_WEB_DEEPSEEK_API_KEY).orEmpty(),
			webExaKeyInput = secure.get(SecureStore.KEY_WEB_EXA_API_KEY).orEmpty(),
			hasWebDeepSeekKey = secure.hasUsable(SecureStore.KEY_WEB_DEEPSEEK_API_KEY),
			hasWebExaKey = secure.hasUsable(SecureStore.KEY_WEB_EXA_API_KEY),
			mediaConfigs = loadMediaConfigs(),
			autoReadReplies = secure.get(SecureStore.KEY_SPEECH_AUTO_READ) == "1",
			speechVoice = secure.get(SecureStore.KEY_SPEECH_VOICE).orEmpty(),
			backupCanUndo = backup.hasUndo(),
			backupLastExportAt = secure.get(SecureStore.KEY_BACKUP_LAST_EXPORT_AT)
				?.toLongOrNull() ?: 0L,
			backupReminderDays = BackupReminderPolicy.normalizePeriod(
				secure.get(SecureStore.KEY_BACKUP_REMINDER_DAYS)?.toIntOrNull(),
			),
			localServerUrl = secure.get(SecureStore.KEY_LOCAL_SERVER_URL).orEmpty(),
			currentVersionName = packageInfo?.versionName ?: "?",
			currentVersionCode = packageInfo?.longVersionCode ?: 0L,
			recordingActive = AppDiagnostics.recordingActive(),
			recordingBytes = AppDiagnostics.recordingBytes(),
		)
		viewModelScope.launch {
			agents.observeProviders().collect { list ->
				_state.value = _state.value.copy(providers = list)
			}
		}
		loadRules()
		loadKnownHosts()
		refreshBackupFolder()
	}

	/**
	 * The TOFU host keys, read from the same line format `SshClient` writes:
	 * `<host> <SHA256:fingerprint>`.
	 *
	 * A malformed line is skipped rather than failing the screen; the file is
	 * only ever written by the app, and a bad line is not a reason to hide the
	 * good ones.
	 */
	private fun loadKnownHosts() {
		val hosts = runCatching {
			if (!knownHostsFile.isFile) {
				emptyList()
			} else {
				knownHostsFile.readLines().mapNotNull { line ->
					val trimmed = line.trim()
					if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
					val parts = trimmed.split(' ', limit = 2)
					if (parts.size == 2) KnownHost(parts[0], parts[1]) else null
				}
			}
		}.getOrElse {
			Diag.w("ssh", "known_hosts read failed", it)
			emptyList()
		}
		_state.value = _state.value.copy(knownHosts = hosts)
	}

	/**
	 * Forget every pinned host key.
	 *
	 * The next connection to each host pins whatever key it presents. That is
	 * the only in-app recovery when a server legitimately changes its key: the
	 * verifier is fail-closed, and without this the user's only options were
	 * clearing app data or `adb`.
	 */
	fun forgetKnownHosts() {
		val removed = runCatching { !knownHostsFile.exists() || knownHostsFile.delete() }
			.getOrDefault(false)
		Diag.i("ssh", "forget pinned host keys removed=$removed")
		_state.value = _state.value.copy(
			knownHosts = emptyList(),
			knownHostsStatus = if (removed) {
				context.getString(R.string.settings_status_host_keys_forgotten)
			} else {
				context.getString(R.string.settings_error_host_keys_failed)
			},
		)
	}

	// ## Local update / log server
	//
	// Both actions are explicit user gestures. There is no background polling
	// and no automatic upload, and the agent has no path to either URL. The
	// URL is kept in SecureStore for the same reason the other app-wide
	// settings are: there is no second settings store, and the value is small.

	fun onLocalServerUrlChange(value: String) {
		_state.value = _state.value.copy(localServerUrl = value)
		// Keep the store in step with the edit. Uploading a conversation from
		// the chat screen reads SecureStore directly: waiting for a Settings
		// action to persist the URL made a fully typed address look unset there
		// until the user happened to press Check for updates or Upload log.
		// An invalid/cleared field removes the old value rather than leaving a
		// hidden target behind the visible one.
		val normalized = normalizeServerUrl(value)
		if (normalized != null) {
			secure.put(SecureStore.KEY_LOCAL_SERVER_URL, normalized)
		} else {
			secure.remove(SecureStore.KEY_LOCAL_SERVER_URL)
		}
	}

	/**
	 * The normalized base URL, persisted the first time an action uses it.
	 *
	 * Returning null means the field is not a usable URL. The caller decides
	 * which status line to show.
	 */
	private fun requireServerUrl(): String? {
		val normalized = normalizeServerUrl(_state.value.localServerUrl) ?: return null
		// Write on every action, not only when the trailing slash changed. The
		// previous version persisted only when normalization altered the text,
		// so a perfectly typed URL was used for that check and then forgotten on
		// the next process start.
		secure.put(SecureStore.KEY_LOCAL_SERVER_URL, normalized)
		if (normalized != _state.value.localServerUrl) {
			_state.value = _state.value.copy(localServerUrl = normalized)
		}
		return normalized
	}

	/**
	 * The answer to one manifest fetch, plus whatever verified APK was already
	 * waiting in the cache for that version.
	 */
	private data class UpdateCheck(
		val info: UpdateInfo,
		val newer: Boolean,
		val cached: File?,
	)

	fun checkForUpdates() {
		if (_state.value.updateChecking) return
		val base = requireServerUrl()
		if (base == null) {
			_state.value = _state.value.copy(
				updateStatus = context.getString(R.string.settings_local_invalid),
				updateStatusIsError = true,
			)
			return
		}
		Diag.i("update", "check requested current=${_state.value.currentVersionCode}")
		// Do not clear updateInfo/downloadedApk here. A verified APK already in
		// cache is the right answer until the manifest says otherwise, and a
		// failed check must not throw away a download the user already waited
		// for. The state is replaced only after the manifest answers.
		_state.value = _state.value.copy(
			updateChecking = true,
			updateStatus = null,
			updateStatusIsError = false,
		)
		viewModelScope.launch {
			val result = withContext(Dispatchers.IO) {
				runCatching {
					val info = fetchUpdate(base)
					val newer = isNewerVersion(
						info.versionCode,
						_state.value.currentVersionCode,
					)
					UpdateCheck(
						info = info,
						newer = newer,
						// Hashing a 60 MB APK is not a main-thread job. Doing it
						// here also means a stale file is removed before the UI
						// is told the update is available again.
						cached = if (newer) cachedApk(info) else null,
					)
				}
			}
			result.onSuccess { check ->
				Diag.i(
					"update",
					"check ok remote=${check.info.versionCode} " +
						"current=${_state.value.currentVersionCode} " +
						"newer=${check.newer} cached=${check.cached != null}",
				)
				downloadedApk = check.cached
				_state.value = _state.value.copy(
					updateChecking = false,
					updateInfo = check.info.takeIf { check.newer },
					updateReadyToInstall = check.cached != null,
					updateProgress = if (check.cached != null) 100 else null,
					updateStatus = when {
						!check.newer -> context.getString(R.string.settings_update_uptodate)
						check.cached != null -> context.getString(R.string.settings_update_ready)
						else -> context.getString(
							R.string.settings_update_available,
							check.info.versionName,
						)
					},
					updateStatusIsError = false,
				)
				if (!check.newer) {
					// The installed version caught up; the cached APK has no
					// remaining use and would only hold cache space.
					withContext(Dispatchers.IO) { clearUpdateCache() }
				}
			}.onFailure { failure ->
				Diag.w("update", "check failed ${failure::class.simpleName}")
				// Leave updateInfo, downloadedApk, updateReadyToInstall and
				// updateProgress untouched: the Install button must survive a
				// failed re-check.
				_state.value = _state.value.copy(
					updateChecking = false,
					updateStatus = context.getString(
						R.string.settings_update_failed,
						networkFailureMessage(failure),
					),
					updateStatusIsError = true,
				)
			}
		}
	}

	/**
	 * A network failure as something a person can act on.
	 *
	 * `SocketTimeoutException` is what a stopped local server looked like from
	 * the pad over Tailscale: the app waited the whole eight-second connect
	 * timeout and then showed the exception class. The class stays in the
	 * diagnostic line; the status line now says which thing to check.
	 */
	private fun networkFailureMessage(failure: Throwable): String = when (failure) {
		is SocketTimeoutException -> context.getString(R.string.settings_update_timeout)
		is ConnectException -> context.getString(R.string.settings_update_unreachable)
		is UnknownHostException -> context.getString(R.string.settings_update_unreachable)
		else -> failure.message ?: failure::class.simpleName ?: "unknown error"
	}

	private fun fetchUpdate(base: String): UpdateInfo {
		val request = Request.Builder()
			.url(updateManifestUrl(base))
			.header("Accept", "application/json")
			.build()
		http.newCall(request).execute().use { response ->
			if (!response.isSuccessful) error("HTTP ${response.code}")
			val text = response.body.string()
			return parseUpdateInfo(text, base) ?: error("invalid update.json")
		}
	}

	fun downloadUpdate() {
		val info = _state.value.updateInfo ?: return
		if (_state.value.updateDownloading) return
		Diag.i("update", "download requested version=${info.versionCode}")
		_state.value = _state.value.copy(
			updateDownloading = true,
			updateProgress = 0,
			updateStatus = context.getString(R.string.settings_update_downloading, 0),
			updateStatusIsError = false,
			updateReadyToInstall = false,
		)
		downloadedApk = null
		viewModelScope.launch {
			val result = withContext(Dispatchers.IO) { runCatching { download(info) } }
			result.onSuccess { apk ->
				downloadedApk = apk
				_state.value = _state.value.copy(
					updateDownloading = false,
					updateProgress = 100,
					updateReadyToInstall = true,
					updateStatus = context.getString(R.string.settings_update_ready),
					updateStatusIsError = false,
				)
			}.onFailure { failure ->
				Diag.w("update", "download failed ${failure::class.simpleName}")
				// A failed retry must not erase a verified APK that is still in
				// cache; otherwise the next check would download it again.
				val restored = withContext(Dispatchers.IO) {
					runCatching { cachedApk(info) }.getOrNull()
				}
				downloadedApk = restored
				_state.value = _state.value.copy(
					updateDownloading = false,
					updateProgress = if (restored != null) 100 else null,
					updateReadyToInstall = restored != null,
					updateStatus = if (restored != null) {
						context.getString(R.string.settings_update_ready)
					} else {
						context.getString(
							R.string.settings_update_failed,
							networkFailureMessage(failure),
						)
					},
					updateStatusIsError = restored == null,
				)
			}
		}
	}

	private fun download(info: UpdateInfo): File {
		// Another check or a retry may already have produced a verified copy.
		// Reuse it instead of downloading the same release again.
		cachedApk(info)?.let { return it }

		val dir = File(context.cacheDir, "updates").apply { mkdirs() }
		val target = updateCacheFile(info.versionCode)
		val partial = File(dir, "inhuman-${info.versionCode}.apk.part")
		runCatching { partial.delete() }
		// Keep a verified target from another check; only stale versions and a
		// previous partial file are removed before this download.
		dir.listFiles()
			?.filter { it != target && it != partial }
			?.forEach { runCatching { it.delete() } }

		val request = Request.Builder().url(info.apkUrl).build()
		try {
			http.newCall(request).execute().use { response ->
				if (!response.isSuccessful) error("HTTP ${response.code}")
				val body = response.body
				val total = body.contentLength()
				body.byteStream().use { input ->
					partial.outputStream().use { output ->
						val buffer = ByteArray(64 * 1024)
						var written = 0L
						var lastPercent = -1
						while (true) {
							val read = input.read(buffer)
							if (read <= 0) break
							output.write(buffer, 0, read)
							written += read
							if (total > 0) {
								val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
								if (percent != lastPercent) {
									lastPercent = percent
									_state.value = _state.value.copy(
										updateProgress = percent,
										updateStatus = context.getString(
											R.string.settings_update_downloading,
											percent,
										),
									)
								}
							}
						}
					}
				}
			}
			val actual = UpdateInstaller.sha256(partial)
			if (!actual.equals(info.sha256, ignoreCase = true)) {
				partial.delete()
				error("SHA-256 mismatch")
			}
			val mismatch = UpdateInstaller.verify(context, partial, info.versionCode)
			if (mismatch != null) {
				partial.delete()
				error(mismatch)
			}
			// Move only after verification: a killed process can leave a
			// `.part` file, but never a half-written file that looks installable.
			target.delete()
			if (!partial.renameTo(target)) {
				error("could not move the downloaded APK into place")
			}
			dir.listFiles()
				?.filter { it != target }
				?.forEach { runCatching { it.delete() } }
			Diag.i("update", "download verified version=${info.versionCode} bytes=${target.length()}")
			return target
		} catch (failure: Throwable) {
			partial.delete()
			throw failure
		}
	}

	private fun updateCacheFile(versionCode: Long): File =
		File(File(context.cacheDir, "updates"), "inhuman-$versionCode.apk")

	/**
	 * The cached APK for [info], only if its bytes and signature still match.
	 *
	 * Runs off the main thread: hashing a release APK is tens of megabytes.
	 * A mismatch deletes the file, because leaving a stale build next to a valid
	 * manifest is how the app would later offer an install that the system
	 * rejects.
	 */
	private fun cachedApk(info: UpdateInfo): File? {
		val target = updateCacheFile(info.versionCode)
		if (!target.isFile || target.length() == 0L) return null
		return runCatching {
			if (!UpdateInstaller.sha256(target).equals(info.sha256, ignoreCase = true)) {
				target.delete()
				return null
			}
			if (UpdateInstaller.verify(context, target, info.versionCode) != null) {
				target.delete()
				return null
			}
			target
		}.getOrNull()
	}

	private fun clearUpdateCache() {
		File(context.cacheDir, "updates").listFiles()?.forEach { runCatching { it.delete() } }
	}

	fun installUpdate() {
		val apk = downloadedApk ?: return
		if (!UpdateInstaller.canInstall(context)) {
			Diag.w("update", "install blocked: unknown sources disabled")
			_state.value = _state.value.copy(
				updateStatus = context.getString(R.string.settings_update_allow_install),
				updateStatusIsError = true,
			)
			UpdateInstaller.openInstallPermission(context)
			return
		}
		_state.value = _state.value.copy(
			updateStatus = context.getString(R.string.settings_update_installing),
			updateStatusIsError = false,
		)
		Diag.i("update", "install requested target=${_state.value.updateInfo?.versionCode}")
		viewModelScope.launch {
			// The PackageInstaller session copies the whole APK. Doing that on
			// the main thread would freeze the settings screen for a release
			// build of tens of megabytes.
			val installed = withContext(Dispatchers.IO) {
				UpdateInstaller.install(context, apk)
			}
			if (!installed) {
				_state.value = _state.value.copy(
					updateStatus = context.getString(
						R.string.settings_update_failed,
						"no package installer",
					),
					updateStatusIsError = true,
				)
			}
		}
	}

	fun startDiagnosticRecording() {
		AppDiagnostics.startRecording()
		refreshRecordingState()
	}

	fun stopDiagnosticRecording() {
		AppDiagnostics.stopRecording()
		refreshRecordingState()
	}

	fun deleteDiagnosticRecording() {
		AppDiagnostics.deleteRecording()
		refreshRecordingState()
	}

	private fun refreshRecordingState() {
		_state.update {
			it.copy(
				recordingActive = AppDiagnostics.recordingActive(),
				recordingBytes = AppDiagnostics.recordingBytes(),
			)
		}
	}

	fun uploadLog() = uploadLogText(recording = false)

	fun uploadRecording() = uploadLogText(recording = true)

	/**
	 * Upload either the whole flight recorder or one manual recording.
	 *
	 * The recording is the case where the user reproduced a bug and wants the
	 * upload to be that segment: same server, same endpoint, different body.
	 * Uploading while the recording is still running is refused rather than
	 * cutting it at an arbitrary point; the switch in the UI is the boundary.
	 * The file is read inside [Dispatchers.IO]: the full recorder is up to
	 * 4 MB, and reading it before launching would put that on the main thread.
	 */
	private fun uploadLogText(recording: Boolean) {
		if (_state.value.logUploading) return
		if (recording && _state.value.recordingActive) {
			_state.value = _state.value.copy(
				logUploadStatus = context.getString(R.string.settings_recording_stop_first),
				logUploadStatusIsError = true,
			)
			return
		}
		val base = requireServerUrl()
		if (base == null) {
			_state.value = _state.value.copy(
				logUploadStatus = context.getString(R.string.settings_log_no_server),
				logUploadStatusIsError = true,
			)
			return
		}
		val bytes = if (recording) AppDiagnostics.recordingBytes() else AppDiagnostics.sizeBytes()
		if (bytes <= 0) {
			_state.value = _state.value.copy(
				logUploadStatus = context.getString(
					if (recording) R.string.settings_recording_empty else R.string.settings_log_empty,
				),
				logUploadStatusIsError = true,
			)
			return
		}
		Diag.i(
			"diag",
			"upload requested bytes=$bytes kind=${if (recording) "recording" else "log"}",
		)
		_state.value = _state.value.copy(
			logUploading = true,
			logUploadStatus = null,
			logUploadStatusIsError = false,
		)
		viewModelScope.launch {
			val result = withContext(Dispatchers.IO) {
				runCatching {
					val text = if (recording) {
						AppDiagnostics.readRecordingText()
					} else {
						AppDiagnostics.readText()
					}
					if (text.isBlank()) error("log is empty")
					postLog(base, text)
				}
			}
			result.onSuccess { response ->
				Diag.i("diag", "upload ok saved=$response")
				_state.value = _state.value.copy(
					logUploading = false,
					logUploadStatus = context.getString(R.string.settings_log_uploaded, response),
					logUploadStatusIsError = false,
				)
			}.onFailure { failure ->
				Diag.w("diag", "upload failed ${failure::class.simpleName}")
				_state.value = _state.value.copy(
					logUploading = false,
					logUploadStatus = context.getString(
						R.string.settings_log_failed,
						networkFailureMessage(failure),
					),
					logUploadStatusIsError = true,
				)
			}
		}
	}

	private fun postLog(base: String, text: String): String {
		val body = text.toRequestBody("text/plain; charset=utf-8".toMediaType())
		val request = Request.Builder()
			.url(logUploadUrl(base))
			.post(body)
			.header(
				"X-Inhuman-Version",
				"${_state.value.currentVersionName} (${_state.value.currentVersionCode})",
			)
			.build()
		http.newCall(request).execute().use { response ->
			val responseText = response.body.string()
			if (!response.isSuccessful) {
				error("HTTP ${response.code} ${responseText.take(200)}")
			}
			return responseText.ifBlank { "ok" }
		}
	}

	private fun loadRules() {
		viewModelScope.launch {
			val rules = runCatching { repository.policies().globalRules() }.getOrElse {
				Diag.w("policy", "global rules read failed; showing none", it)
				emptyMap()
			}
			_state.value = _state.value.copy(approvalRules = rules)
		}
	}

	/**
	 * Set one tool's global rule.
	 *
	 * Written through to the database and then re-read rather than patched in
	 * memory, so what the chips show is what a tool call would actually resolve.
	 * A locally-updated map would be a second source of truth for the one thing
	 * this screen exists to make trustworthy.
	 */
	fun setApprovalRule(toolName: String, policy: ToolPolicy) {
		viewModelScope.launch {
			runCatching { repository.policies().setGlobalRule(toolName, policy) }
				.onFailure {
					Diag.w("policy", "global rule write failed tool=$toolName policy=$policy", it)
				}
			loadRules()
		}
	}

	fun setToolRoundLimit(rounds: Int) {
		val limit = rounds.coerceIn(
			ChatViewModel.MIN_TOOL_ROUNDS,
			ChatViewModel.MAX_TOOL_ROUNDS,
		)
		secure.put(SecureStore.KEY_TOOL_ROUNDS, limit.toString())
		_state.value = _state.value.copy(toolRoundLimit = limit)
		Diag.i("turn", "global tool round limit set to $limit")
	}

	fun setLayoutPreference(preference: com.inhuman.core.design.LayoutPreference) {
		secure.put(SecureStore.KEY_LAYOUT_PREFERENCE, preference.name)
		_state.value = _state.value.copy(layoutPreference = preference)
	}

	private fun readToolRoundLimit(): Int =
		secure.get(SecureStore.KEY_TOOL_ROUNDS)?.toIntOrNull()
			?.coerceIn(ChatViewModel.MIN_TOOL_ROUNDS, ChatViewModel.MAX_TOOL_ROUNDS)
			?: ChatViewModel.DEFAULT_TOOL_ROUNDS

	fun setToolCallLimit(calls: Int) {
		val limit = calls.coerceIn(
			ChatViewModel.MIN_TOOL_CALLS,
			ChatViewModel.MAX_TOOL_CALLS,
		)
		secure.put(SecureStore.KEY_TOOL_CALLS, limit.toString())
		_state.value = _state.value.copy(toolCallLimit = limit)
		Diag.i("turn", "global tool call limit set to $limit")
	}

	private fun readToolCallLimit(): Int =
		secure.get(SecureStore.KEY_TOOL_CALLS)?.toIntOrNull()
			?.coerceIn(ChatViewModel.MIN_TOOL_CALLS, ChatViewModel.MAX_TOOL_CALLS)
			?: ChatViewModel.DEFAULT_TOOL_CALLS

	/**
	 * Makes the follow-up tool visible to the pickers. It is still opt-in per
	 * assistant, and the tool is the only thing that can spend the extra
	 * request: turning this off removes the row, not just the checkbox.
	 */
	fun setSuggestionsEnabled(enabled: Boolean) {
		secure.put(SecureStore.KEY_SUGGESTIONS_ENABLED, if (enabled) "1" else "0")
		_state.value = _state.value.copy(suggestionsEnabled = enabled)
	}

	/** Turn the wire timestamp on, off, or on only after a gap. */
	fun setTimeHintMode(mode: TimeHintMode) {
		saveTimeHints(_state.value.timeHints.copy(mode = mode))
	}

	/** The gap threshold in minutes; ignored while the mode is not [TimeHintMode.AfterGap]. */
	fun setTimeHintGapMinutes(minutes: Int) {
		saveTimeHints(_state.value.timeHints.copy(gapMinutes = minutes))
	}

	/**
	 * Store both halves together and publish the normalized value.
	 *
	 * The threshold is written even in the other modes, so switching back to
	 * "after a gap" keeps the user's number instead of silently returning to
	 * ten minutes. The value is clamped here as well as on read, so nothing
	 * outside the accepted range can be stored by this app.
	 */
	private fun saveTimeHints(policy: TimeHintPolicy) {
		val normalized = policy.copy(gapMinutes = policy.normalizedGapMinutes())
		secure.put(SecureStore.KEY_TIME_HINTS_MODE, normalized.encodeMode())
		secure.put(
			SecureStore.KEY_TIME_HINTS_GAP_MINUTES,
			normalized.encodeGapMinutes(),
		)
		_state.value = _state.value.copy(timeHints = normalized)
		Diag.i("turn", "time hints mode=${normalized.encodeMode()} gap=${normalized.encodeGapMinutes()}m")
	}

	/**
	 * Turn the anonymous Bilibili read tools on or off globally.
	 *
	 * Off removes the four rows from every picker and from every request. The
	 * assistants' stored selections are kept, so switching back on restores
	 * what the user had chosen rather than asking them to remember it. There is
	 * nothing to revoke on the way out: unlike SSH there is no consent attached
	 * to a read, and unlike the paid web tools there is no key or bill.
	 */
	fun setBilibiliEnabled(enabled: Boolean) {
		secure.put(SecureStore.KEY_BILIBILI_TOOL_ENABLED, if (enabled) "1" else "0")
		_state.value = _state.value.copy(
			bilibiliEnabled = enabled,
			status = if (enabled) {
				context.getString(R.string.settings_status_bilibili_enabled)
			} else {
				context.getString(R.string.settings_status_bilibili_disabled)
			},
			statusIsError = false,
		)
		Diag.i("bilibili", "global read tools enabled=$enabled")
	}

	/** The QR dialog's polling task; cancelled when the dialog closes. */
	private var bilibiliLoginJob: Job? = null
	private var bilibiliAuth: BilibiliAuth? = null

	/**
	 * Generate a QR and poll it until confirmed, cancelled or expired too often.
	 *
	 * The QR rotates on its own when Bilibili expires it (about three minutes),
	 * because a dialog that silently dies is indistinguishable from one waiting
	 * for a slow user. Nothing is stored until the confirmation comes back; a
	 * cancelled dialog leaves the previous state untouched.
	 */
	fun beginBilibiliLogin() {
		if (bilibiliLoginJob?.isActive == true) return
		bilibiliLoginJob = viewModelScope.launch {
			_state.value = _state.value.copy(
				bilibiliLoginQr = null,
				bilibiliLoginRunning = true,
				bilibiliLoginStatus = context.getString(R.string.settings_bilibili_login_starting),
				bilibiliLoginStatusIsError = false,
			)
			val auth = bilibiliAuth ?: BilibiliAuth().also { bilibiliAuth = it }
			try {
				var qr = auth.generateQr()
				renderQr(qr)
				var rotations = 0
				while (true) {
					delay(2_000)
					when (val answer = auth.poll(qr.key)) {
						BilibiliQrState.Waiting -> Unit
						BilibiliQrState.Scanned -> _state.value = _state.value.copy(
							bilibiliLoginStatus = context.getString(R.string.settings_bilibili_login_scanned),
							bilibiliLoginStatusIsError = false,
						)
						BilibiliQrState.Expired -> {
							rotations++
							if (rotations > MAX_QR_ROTATIONS) {
								throw BilibiliApiException(
									BilibiliApiException.TRANSPORT,
									"the QR kept expiring",
								)
							}
							qr = auth.generateQr()
							renderQr(qr)
						}
						is BilibiliQrState.Confirmed -> {
							// The name is display-only; the cookies are what the
							// client sends. A failed profile read must not discard
							// a confirmed login.
							val profile = runCatching { auth.fetchProfile(answer.credentials) }.getOrNull()
							val credentials = answer.credentials.copy(
								userName = profile?.name.orEmpty(),
								savedAtMillis = System.currentTimeMillis(),
							)
							secure.put(SecureStore.KEY_BILIBILI_SESSION, credentials.toStoredJson())
							_state.value = _state.value.copy(
								bilibiliLoggedIn = true,
								bilibiliLoginName = credentials.userName,
								bilibiliLoginQr = null,
								bilibiliLoginRunning = false,
								bilibiliLoginStatus = context.getString(
									R.string.settings_bilibili_login_linked_status,
								),
								bilibiliLoginStatusIsError = false,
							)
							Diag.i("bilibili", "account linked")
							return@launch
						}
						is BilibiliQrState.Failed -> throw BilibiliApiException(
							BilibiliApiException.TRANSPORT,
							answer.message,
						)
					}
				}
			} catch (failure: Exception) {
				if (failure is kotlinx.coroutines.CancellationException) throw failure
				_state.value = _state.value.copy(
					bilibiliLoginQr = null,
					bilibiliLoginRunning = false,
					bilibiliLoginStatus = context.getString(
						R.string.settings_bilibili_login_failed,
						failure.message ?: failure.javaClass.simpleName,
					),
					bilibiliLoginStatusIsError = true,
				)
				Diag.w("bilibili", "login failed", failure)
			}
		}
	}

	/** Close the dialog and stop polling; a link that already exists is kept. */
	fun cancelBilibiliLogin() {
		bilibiliLoginJob?.cancel()
		bilibiliLoginJob = null
		_state.value = _state.value.copy(
			bilibiliLoginQr = null,
			bilibiliLoginRunning = false,
			bilibiliLoginStatus = null,
			bilibiliLoginStatusIsError = false,
		)
	}

	/**
	 * Forget the linked account.
	 *
	 * The tool that needs it disappears from every picker on the next turn; the
	 * three anonymous reads and the four public tools are unaffected.
	 */
	fun unlinkBilibili() {
		bilibiliLoginJob?.cancel()
		bilibiliLoginJob = null
		secure.remove(SecureStore.KEY_BILIBILI_SESSION)
		_state.value = _state.value.copy(
			bilibiliLoggedIn = false,
			bilibiliLoginName = "",
			bilibiliLoginQr = null,
			bilibiliLoginRunning = false,
			bilibiliLoginStatus = context.getString(R.string.settings_bilibili_login_unlinked_status),
			bilibiliLoginStatusIsError = false,
		)
		Diag.i("bilibili", "account unlinked")
	}

	private fun renderQr(qr: BilibiliQr) {
		val image = runCatching { BiliQrRenderer.encode(qr.url) }.getOrNull() ?: return
		_state.value = _state.value.copy(
			bilibiliLoginQr = image,
			bilibiliLoginRunning = true,
			bilibiliLoginStatus = context.getString(R.string.settings_bilibili_login_waiting),
			bilibiliLoginStatusIsError = false,
		)
	}

	private companion object {
		/** A QR rotates after about three minutes; this is ~15 minutes of tries. */
		const val MAX_QR_ROTATIONS = 5
	}

	fun setDemoMode(enabled: Boolean) {
		secure.put(SecureStore.KEY_DEMO_MODE, if (enabled) "1" else "0")
		_state.value = _state.value.copy(
			demoMode = enabled,
			status = if (enabled) {
				context.getString(R.string.settings_status_demo_on)
			} else {
				null
			},
			statusIsError = false,
		)
	}

	/**
	 * Turn the SSH tool on or off globally.
	 *
	 * Disabling revokes every conversation's acknowledgement. A consent that
	 * survives the capability being switched off and on again is a consent the
	 * user did not give for the second enabling.
	 */
	fun setSshToolEnabled(enabled: Boolean) {
		secure.put(SecureStore.KEY_SSH_TOOL_ENABLED, if (enabled) "1" else "0")
		_state.value = _state.value.copy(
			sshToolEnabled = enabled,
			status = if (enabled) {
				context.getString(R.string.settings_status_ssh_enabled)
			} else {
				context.getString(R.string.settings_status_ssh_disabled)
			},
			statusIsError = false,
		)
		Diag.i("ssh", "global tool enabled=$enabled")
		if (!enabled) {
			viewModelScope.launch { repository.clearAllSshAllowed() }
		}
	}

	/**
	 * Turn the URL-download tool on or off globally.
	 *
	 * Disabling revokes every conversation's acknowledgement, for the same
	 * reason SSH does: a consent that survives the capability being switched off
	 * and on again is a consent the user did not give for the second enabling.
	 */
	fun setDownloadEnabled(enabled: Boolean) {
		secure.put(SecureStore.KEY_DOWNLOAD_TOOL_ENABLED, if (enabled) "1" else "0")
		_state.value = _state.value.copy(
			downloadEnabled = enabled,
			status = if (enabled) {
				context.getString(R.string.settings_status_download_enabled)
			} else {
				context.getString(R.string.settings_status_download_disabled)
			},
			statusIsError = false,
		)
		Diag.i("download", "global tool enabled=$enabled")
		if (!enabled) {
			viewModelScope.launch { repository.clearAllDownloadAllowed() }
		}
	}

	fun setSshCommandTimeoutSeconds(value: Int) {
		updateSshLimits {
			it.copy(
				commandTimeoutCeilingMs = value
					.coerceIn(
						SshLimits.MIN_COMMAND_TIMEOUT_SECONDS,
						SshLimits.MAX_COMMAND_TIMEOUT_SECONDS,
					) * 1000,
			)
		}
	}

	fun setSshOutputBytes(value: Int) {
		updateSshLimits {
			it.copy(
				outputCeilingBytes = value.coerceIn(
					SshLimits.MIN_OUTPUT_BYTES,
					SshLimits.MAX_OUTPUT_BYTES,
				),
			)
		}
	}

	fun setSshListEntries(value: Int) {
		updateSshLimits {
			it.copy(
				listCeilingEntries = value.coerceIn(
					SshLimits.MIN_LIST_ENTRIES,
					SshLimits.MAX_LIST_ENTRIES,
				),
			)
		}
	}

	fun setSshReadBytes(value: Int) {
		updateSshLimits {
			it.copy(
				readCeilingBytes = value.toLong().coerceIn(
					SshLimits.MIN_READ_BYTES,
					SshLimits.MAX_READ_BYTES,
				),
			)
		}
	}

	private fun updateSshLimits(transform: (SshLimits) -> SshLimits) {
		val limits = transform(_state.value.sshLimits)
		secure.put(
			SecureStore.KEY_SSH_COMMAND_TIMEOUT_SECONDS,
			limits.commandTimeoutCeilingSeconds.toString(),
		)
		secure.put(SecureStore.KEY_SSH_OUTPUT_BYTES, limits.outputCeilingBytes.toString())
		secure.put(SecureStore.KEY_SSH_LIST_ENTRIES, limits.listCeilingEntries.toString())
		secure.put(SecureStore.KEY_SSH_READ_BYTES, limits.readCeilingBytes.toString())
		_state.value = _state.value.copy(sshLimits = limits)
		Diag.i(
			"ssh",
			"limits command=${limits.commandTimeoutCeilingSeconds}s " +
				"output=${limits.outputCeilingBytes}B list=${limits.listCeilingEntries} " +
				"read=${limits.readCeilingBytes}B",
		)
	}

	fun hasProviderKey(providerId: String): Boolean = agents.hasKey(providerId)

	fun addProvider(preset: KnownProvider? = null) {
		val existing = _state.value.providers.map { it.name }.toSet()
		var name = preset?.name ?: "OpenAI-compatible"
		var suffix = 2
		while (name in existing) {
			name = "${preset?.name ?: "OpenAI-compatible"} $suffix"
			suffix += 1
		}
		_state.value = _state.value.copy(
			editingProvider = ProviderDraft(
				id = UUID.randomUUID().toString(),
				isNew = true,
				isBuiltIn = false,
				name = name,
				kind = preset?.kind ?: ProviderKinds.OPENAI_COMPATIBLE,
				baseUrl = preset?.baseUrl ?: "https://api.openai.com/v1",
				// A known endpoint's reasoning fields are part of what makes it
				// known; making the user rediscover them is the friction the
				// catalogue exists to remove. Custom profiles still default to
				// none and expose the chips.
				reasoningMode = preset?.reasoningMode ?: ProviderReasoning.NONE,
				includeStreamUsage = true,
				manualModels = preset?.models?.joinToString("\n").orEmpty(),
				keyInput = "",
				hasKey = false,
				isPreset = preset != null,
			),
		)
	}

	fun editProvider(profile: ProviderProfileEntity) {
		val key = secure.get(agents.apiKeySlot(profile.id)).orEmpty()
		val isPreset = ProviderCatalog.known.any {
			it.kind == profile.kind &&
				it.baseUrl.trimEnd('/') == profile.baseUrl.trimEnd('/')
		}
		_state.value = _state.value.copy(
			editingProvider = ProviderDraft(
				id = profile.id,
				isNew = false,
				isBuiltIn = profile.isBuiltIn,
				name = profile.name,
				kind = profile.kind,
				baseUrl = profile.baseUrl,
				reasoningMode = profile.reasoningMode,
				includeStreamUsage = profile.includeStreamUsage,
				manualModels = profile.manualModels,
				keyInput = key,
				hasKey = key.isNotBlank() || oauth.status(profile.id).signedIn,
				hasStoredKey = key.isNotBlank(),
				isPreset = isPreset,
			),
		)
	}

	/**
	 * Open the PKCE sign-in dialog for a saved provider.
	 *
	 * The editor is closed because the browser leaves the app and a second
	 * dialog would hide the instructions. The client id and endpoints are
	 * user-supplied: no first-party registration ships with the app.
	 */
	fun openOAuth(profile: ProviderProfileEntity) {
		val config = oauth.config(profile.id)
		val status = oauth.status(profile.id)
		_state.value = _state.value.copy(
			editingProvider = null,
			oauthLogin = OAuthLoginUi(
				profileId = profile.id,
				providerName = profile.name,
				clientId = config?.clientId.orEmpty(),
				authorizationEndpoint = config?.authorizationEndpoint.orEmpty(),
				tokenEndpoint = config?.tokenEndpoint.orEmpty(),
				scopes = config?.scopes?.joinToString(" ").orEmpty(),
				clientSecret = config?.clientSecret.orEmpty(),
				configured = status.configured,
				signedIn = status.signedIn,
				expiresAt = status.expiresAt,
			),
		)
	}

	fun updateOAuthLogin(transform: (OAuthLoginUi) -> OAuthLoginUi) {
		val ui = _state.value.oauthLogin ?: return
		_state.value = _state.value.copy(oauthLogin = transform(ui))
	}

	fun closeOAuth() {
		_state.value = _state.value.copy(oauthLogin = null)
	}

	fun beginOAuth() {
		val ui = _state.value.oauthLogin ?: return
		if (!ui.canBegin) {
			updateOAuthLogin {
				it.copy(
					status = context.getString(R.string.settings_oauth_incomplete),
					statusIsError = true,
				)
			}
			return
		}
		updateOAuthLogin { it.copy(busy = true, status = null, statusIsError = false) }
		viewModelScope.launch {
			val result = runCatching { oauth.begin(ui.profileId, ui.toConfig()) }
			updateOAuthLogin {
				result.fold(
					onSuccess = { url ->
						it.copy(
							busy = false,
							configured = true,
							authorizeUrl = url,
							status = context.getString(R.string.settings_oauth_waiting),
						)
					},
					onFailure = { failure ->
						it.copy(
							busy = false,
							status = failure.message ?: context.getString(R.string.settings_oauth_failed),
							statusIsError = true,
						)
					},
				)
			}
		}
	}

	/** Finish a sign-in delivered by the `inhuman://oauth` callback. */
	fun completeOAuth(callbackUri: String) {
		viewModelScope.launch {
			val result = runCatching { oauth.complete(callbackUri).getOrThrow() }
			result.fold(
				onSuccess = { profileId ->
					val status = oauth.status(profileId)
					_state.value = _state.value.copy(
						oauthLogin = _state.value.oauthLogin?.takeIf { it.profileId == profileId }
							?.copy(
								signedIn = status.signedIn,
								configured = status.configured,
								expiresAt = status.expiresAt,
								busy = false,
								status = context.getString(R.string.settings_oauth_signed_in),
								statusIsError = false,
							),
						editingProvider = _state.value.editingProvider?.takeIf { it.id == profileId }
							?.copy(hasKey = true),
					)
					Diag.i("oauth", "sign-in completed")
				},
				onFailure = { failure ->
					_state.value = _state.value.copy(
						oauthLogin = _state.value.oauthLogin?.copy(
							busy = false,
							status = failure.message ?: context.getString(R.string.settings_oauth_failed),
							statusIsError = true,
						),
					)
				},
			)
		}
	}

	/** Clear the one-shot URL after the UI has handed it to a browser. */
	fun consumeOAuthUrl() {
		updateOAuthLogin { it.copy(authorizeUrl = null) }
	}

	fun signOutOAuth() {
		val ui = _state.value.oauthLogin ?: return
		oauth.signOut(ui.profileId)
		updateOAuthLogin {
			it.copy(
				signedIn = false,
				expiresAt = null,
				status = context.getString(R.string.settings_oauth_signed_out),
				statusIsError = false,
			)
		}
	}

	fun updateProviderDraft(transform: (ProviderDraft) -> ProviderDraft) {
		_state.value = _state.value.let { current ->
			current.copy(editingProvider = current.editingProvider?.let(transform))
		}
	}

	fun closeProviderEditor() {
		_state.value = _state.value.copy(editingProvider = null)
	}

	fun saveProvider() {
		val draft = _state.value.editingProvider ?: return
		val cleanName = draft.name.trim()
		val baseUrl = draft.baseUrl.trim().trimEnd('/')
		val problem = when {
			cleanName.isBlank() -> context.getString(R.string.settings_error_provider_name)
			baseUrl.isBlank() -> context.getString(R.string.settings_error_base_url_missing)
			!validBaseUrl(baseUrl) -> context.getString(R.string.settings_error_base_url_https)
			else -> null
		}
		if (problem != null) {
			updateProviderDraft { it.copy(status = problem, statusIsError = true) }
			return
		}

		viewModelScope.launch {
			// Read the stored row first: the editor has no field for these, and
			// building the entity from the draft alone reset `createdAt` on
			// every save and would have dropped every model tag the same way.
			val existing = agents.provider(draft.id)
			val now = System.currentTimeMillis()
			val profile = ProviderProfileEntity(
				id = draft.id,
				name = cleanName,
				kind = draft.kind,
				baseUrl = baseUrl,
				reasoningMode = draft.reasoningMode,
				includeStreamUsage = draft.includeStreamUsage,
				manualModels = draft.manualModels.trim(),
				modelTags = existing?.modelTags.orEmpty(),
				isBuiltIn = draft.isBuiltIn,
				createdAt = existing?.createdAt ?: now,
				updatedAt = now,
			)
			// An empty key field while OAuth is signed in means "keep the
			// token"; passing "" would clear the very session the user just
			// created. With no OAuth session, blank still clears an old key.
			val hasOAuth = oauth.status(profile.id).signedIn
			val keyToStore = when {
				draft.keyInput.isNotBlank() -> draft.keyInput
				hasOAuth -> null
				else -> ""
			}
			agents.saveProvider(profile, keyToStore)
			Diag.i("settings", "provider saved id=${Diag.shortId(profile.id)}")
			_state.value = _state.value.copy(
				editingProvider = null,
				providerStatus = context.getString(R.string.settings_status_provider_saved, profile.name),
				providerStatusIsError = false,
			)
		}
	}

	fun deleteProvider() {
		val draft = _state.value.editingProvider ?: return
		if (draft.isBuiltIn) return
		viewModelScope.launch {
			agents.deleteProvider(draft.id)
				.onSuccess {
					_state.value = _state.value.copy(
						editingProvider = null,
						providerStatus = context.getString(R.string.settings_status_provider_deleted),
						providerStatusIsError = false,
					)
				}
				.onFailure { failure ->
					updateProviderDraft {
						it.copy(
							status = failure.message
								?: context.getString(R.string.settings_error_provider_delete_failed),
							statusIsError = true,
						)
					}
				}
		}
	}

	/**
	 * Remove the locally stored API key from a provider without deleting the row.
	 *
	 * The provider row is configuration (name, endpoint, model tags) and the key
	 * is a credential; deleting one should not silently delete the other. This
	 * is also the only way to clear the built-in DeepSeek key, whose row is
	 * deliberately undeletable. An OAuth session is a separate credential and
	 * has its own Sign out; this method does not touch it.
	 */
	fun clearProviderKey() {
		val draft = _state.value.editingProvider ?: return
		if (draft.isNew) return
		secure.remove(agents.apiKeySlot(draft.id))
		Diag.i("settings", "provider key cleared id=${Diag.shortId(draft.id)}")
		updateProviderDraft {
			it.copy(
				keyInput = "",
				hasKey = false,
				hasStoredKey = false,
				status = context.getString(R.string.settings_key_cleared),
				statusIsError = false,
			)
		}
	}

	fun testProvider() {
		val draft = _state.value.editingProvider ?: return
		val typedKey = draft.keyInput.trim().ifBlank {
			secure.get(agents.apiKeySlot(draft.id)).orEmpty()
		}
		val hasOAuth = oauth.status(draft.id).signedIn
		if (typedKey.isBlank() && !hasOAuth) {
			updateProviderDraft {
				it.copy(
					status = context.getString(R.string.settings_error_save_key_first),
					statusIsError = true,
				)
			}
			return
		}
		updateProviderDraft {
			it.copy(
				testing = true,
				status = context.getString(R.string.settings_checking),
				statusIsError = false,
			)
		}
		viewModelScope.launch {
			val key = if (typedKey.isNotBlank()) typedKey else oauth.accessToken(draft.id)
			if (key == null) {
				updateProviderDraft {
					it.copy(
						testing = false,
						status = context.getString(R.string.settings_oauth_needs_sign_in),
						statusIsError = true,
					)
				}
				return@launch
			}
			val result = runCatching {
				ProviderFactory.create(
					kind = draft.kind,
					apiKey = key,
					baseUrl = draft.baseUrl.trim().trimEnd('/'),
					id = draft.id,
					reasoningMode = draft.reasoningMode,
					includeStreamUsage = draft.includeStreamUsage,
					auth = if (typedKey.isNotBlank()) {
						ProviderFactory.Auth.ApiKey
					} else {
						ProviderFactory.Auth.OAuth
					},
				).listModels()
			}
			result.fold(
				onSuccess = { models ->
					updateProviderDraft {
						it.copy(
							testing = false,
							hasKey = true,
							status = if (models.isEmpty()) {
								context.getString(R.string.settings_status_connected_no_models)
							} else {
								context.getString(
									R.string.settings_status_connected,
									models.size,
									models.take(8).joinToString(", ") { m -> m.id },
								)
							},
							statusIsError = false,
						)
					}
				},
				onFailure = { failure ->
					updateProviderDraft {
						it.copy(
							testing = false,
							status = failure.message
								?: context.getString(R.string.settings_error_connection_failed),
							statusIsError = true,
						)
					}
				},
			)
		}
	}

	private fun validBaseUrl(value: String): Boolean = runCatching {
		val uri = URI(value)
		uri.scheme == "https" &&
			!uri.host.isNullOrBlank() &&
			uri.userInfo == null &&
			uri.rawQuery == null &&
			uri.rawFragment == null
	}.getOrDefault(false)

	fun setWebSearchBackend(backend: String) {
		secure.put(SecureStore.KEY_WEB_SEARCH_BACKEND, backend)
		_state.value = _state.value.copy(webSearchBackend = backend, webStatus = null)
	}

	fun setWebFetchBackend(backend: String) {
		secure.put(SecureStore.KEY_WEB_FETCH_BACKEND, backend)
		_state.value = _state.value.copy(webFetchBackend = backend, webStatus = null)
	}

	fun onWebDeepSeekKeyChange(value: String) {
		_state.value = _state.value.copy(webDeepSeekKeyInput = value, webStatus = null)
	}

	fun onWebExaKeyChange(value: String) {
		_state.value = _state.value.copy(webExaKeyInput = value, webStatus = null)
	}

	fun saveWebTools() {
		val deepseek = _state.value.webDeepSeekKeyInput.trim()
		val exa = _state.value.webExaKeyInput.trim()
		if (deepseek.isBlank()) secure.remove(SecureStore.KEY_WEB_DEEPSEEK_API_KEY)
		else secure.put(SecureStore.KEY_WEB_DEEPSEEK_API_KEY, deepseek)
		if (exa.isBlank()) secure.remove(SecureStore.KEY_WEB_EXA_API_KEY)
		else secure.put(SecureStore.KEY_WEB_EXA_API_KEY, exa)
		_state.value = _state.value.copy(
			hasWebDeepSeekKey = deepseek.isNotBlank(),
			hasWebExaKey = exa.isNotBlank(),
			webStatus = context.getString(R.string.settings_status_web_saved),
			webStatusIsError = false,
		)
	}

	/**
	 * Delete both saved web-tool keys.
	 *
	 * They are one section with one Save, and leaving one behind would make the
	 * section still "configured" after the user asked to remove the credentials.
	 * The selected backends are configuration and stay; without a key their
	 * tools remain absent, which the section already explains.
	 */
	fun clearWebKeys() {
		secure.remove(SecureStore.KEY_WEB_DEEPSEEK_API_KEY)
		secure.remove(SecureStore.KEY_WEB_EXA_API_KEY)
		Diag.i("settings", "web keys cleared")
		_state.value = _state.value.copy(
			webDeepSeekKeyInput = "",
			webExaKeyInput = "",
			hasWebDeepSeekKey = false,
			hasWebExaKey = false,
			webStatus = context.getString(R.string.settings_key_cleared),
			webStatusIsError = false,
		)
	}

	// --- media tools ---------------------------------------------------------

	/**
	 * Open one media tool's backend dialog.
	 *
	 * A provider-mode config whose provider has since been deleted falls back to
	 * the first eligible profile rather than showing a selection that no longer
	 * exists; the dialog's Save is still explicit, so nothing is written by merely
	 * opening it.
	 */
	fun openMedia(capability: MediaCapability) {
		val config = MediaToolStore.load(secure, capability)
		val eligible = _state.value.providers.filter {
			ProviderCapabilities.supports(it.kind, capability.providerCapability)
		}
		val providerId = config.providerId.takeIf { id -> eligible.any { it.id == id } }
			?: eligible.firstOrNull()?.id.orEmpty()
		val providerKind = _state.value.providers.firstOrNull { it.id == providerId }
			?.kind.orEmpty()
		val draft = MediaToolDraft(
			capability = capability,
			mode = config.mode,
			providerId = providerId,
			providerKind = providerKind,
			model = config.model,
			baseUrl = config.baseUrl,
			hasKey = config.apiKey.isNotBlank(),
		)
		_state.value = _state.value.copy(mediaDraft = draft, mediaStatus = null)
		if (draft.mode == MediaMode.Provider &&
			!providerTakesNoSpeechModel(capability, providerId)
		) {
			loadMediaModels()
		}
	}

	/**
	 * Whether this capability/provider pair needs no model catalogue.
	 *
	 * DeepSeek's ASR/TTS endpoints take no model id, so fetching `/models` and
	 * filtering it by the audio tag would only produce a spinner and a "no
	 * models" line that does not apply.
	 */
	private fun providerTakesNoSpeechModel(capability: MediaCapability, providerId: String): Boolean {
		if (capability != MediaCapability.Speech &&
			capability != MediaCapability.Transcription
		) {
			return false
		}
		val provider = _state.value.providers.firstOrNull { it.id == providerId } ?: return false
		return provider.kind == ProviderKinds.DEEPSEEK
	}

	fun closeMedia() {
		_state.value = _state.value.copy(mediaDraft = null)
	}

	fun setMediaMode(mode: MediaMode) {
		val draft = _state.value.mediaDraft ?: return
		_state.value = _state.value.copy(
			mediaDraft = draft.copy(mode = mode, error = null, showModelPicker = false),
		)
		if (mode == MediaMode.Provider) {
			if (draft.providerId.isBlank()) {
				val first = _state.value.providers.firstOrNull {
					ProviderCapabilities.supports(it.kind, draft.capability.providerCapability)
				}
				if (first != null) setMediaProvider(first.id)
			} else {
				if (!providerTakesNoSpeechModel(draft.capability, draft.providerId)) {
					loadMediaModels()
				}
			}
		}
	}

	fun setMediaProvider(providerId: String) {
		val draft = _state.value.mediaDraft ?: return
		_state.value = _state.value.copy(
			mediaDraft = draft.copy(
				providerId = providerId,
				providerKind = _state.value.providers.firstOrNull { it.id == providerId }
					?.kind.orEmpty(),
				model = "",
				models = emptyList(),
				modelError = null,
				error = null,
			),
		)
		if (!providerTakesNoSpeechModel(draft.capability, providerId)) {
			loadMediaModels()
		}
	}

	fun setMediaModel(model: String) {
		val draft = _state.value.mediaDraft ?: return
		_state.value = _state.value.copy(
			mediaDraft = draft.copy(model = model, showModelPicker = false, error = null),
		)
	}

	fun setMediaModelPicker(open: Boolean) {
		val draft = _state.value.mediaDraft ?: return
		_state.value = _state.value.copy(mediaDraft = draft.copy(showModelPicker = open))
	}

	fun setMediaBaseUrl(value: String) {
		val draft = _state.value.mediaDraft ?: return
		_state.value = _state.value.copy(mediaDraft = draft.copy(baseUrl = value, error = null))
	}

	fun setMediaKeyInput(value: String) {
		val draft = _state.value.mediaDraft ?: return
		_state.value = _state.value.copy(mediaDraft = draft.copy(apiKeyInput = value, error = null))
	}

	fun saveMedia() {
		val draft = _state.value.mediaDraft ?: return
		val existing = MediaToolStore.load(secure, draft.capability)
		val config = when (draft.mode) {
			MediaMode.Assistant -> MediaToolConfig(
				mode = MediaMode.Assistant,
				model = draft.model.trim(),
			)
			MediaMode.Provider -> {
				val profile = _state.value.providers.firstOrNull { it.id == draft.providerId }
				val failure = when {
					profile == null -> context.getString(R.string.settings_media_error_provider)
					!ProviderCapabilities.supports(
						profile.kind,
						draft.capability.providerCapability,
					) -> context.getString(R.string.settings_media_error_provider_support)
					!agents.hasKey(profile.id) -> context.getString(
						R.string.settings_media_error_provider_key,
						profile.name,
					)
					else -> null
				}
				if (failure != null) {
					_state.value = _state.value.copy(mediaDraft = draft.copy(error = failure))
					return
				}
				MediaToolConfig(
					mode = MediaMode.Provider,
					providerId = draft.providerId,
					model = draft.model.trim(),
				)
			}
			MediaMode.Direct -> {
				val apiKey = draft.apiKeyInput.trim().ifBlank { existing.apiKey }
				val baseUrl = draft.baseUrl.trim()
				val failure = when {
					!validBaseUrl(baseUrl) -> context.getString(R.string.settings_media_error_base_url)
					apiKey.isBlank() -> context.getString(R.string.settings_media_error_key)
					else -> null
				}
				if (failure != null) {
					_state.value = _state.value.copy(mediaDraft = draft.copy(error = failure))
					return
				}
				MediaToolConfig(
					mode = MediaMode.Direct,
					model = draft.model.trim(),
					baseUrl = baseUrl,
					apiKey = apiKey,
				)
			}
		}
		MediaToolStore.save(secure, draft.capability, config)
		_state.value = _state.value.copy(
			mediaConfigs = loadMediaConfigs(),
			mediaDraft = null,
			mediaStatus = context.getString(R.string.settings_media_saved),
			mediaStatusIsError = false,
		)
	}

	/**
	 * Remove the saved key from the media backend being edited.
	 *
	 * `saveMedia` deliberately treats a blank key field as "keep the existing
	 * key" so reopening a dialog cannot silently erase a credential. Clearing it
	 * therefore needs its own action. The rest of the backend config (mode,
	 * provider, model, endpoint) stays; with no key a Direct backend is
	 * incomplete and the tools stay absent until it is filled in again.
	 */
	fun clearMediaKey() {
		val draft = _state.value.mediaDraft ?: return
		val existing = MediaToolStore.load(secure, draft.capability)
		if (existing.apiKey.isBlank()) {
			_state.value = _state.value.copy(
				mediaDraft = draft.copy(apiKeyInput = "", hasKey = false),
				mediaStatus = context.getString(R.string.settings_key_cleared),
				mediaStatusIsError = false,
			)
			return
		}
		MediaToolStore.save(secure, draft.capability, existing.copy(apiKey = ""))
		Diag.i("settings", "media key cleared capability=${draft.capability.key}")
		_state.value = _state.value.copy(
			mediaConfigs = loadMediaConfigs(),
			mediaDraft = draft.copy(apiKeyInput = "", hasKey = false),
			mediaStatus = context.getString(R.string.settings_key_cleared),
			mediaStatusIsError = false,
		)
	}

	/**
	 * Load the selected provider's models and narrow them by capability.
	 *
	 * The tag lookup is separate from the fetch because a model's role is a
	 * local judgement, not a provider field. An empty filtered list falls back to
	 * the whole list: a provider whose models have not been tagged must still be
	 * selectable, and the model text field remains editable.
	 */
	private fun loadMediaModels() {
		val draft = _state.value.mediaDraft ?: return
		val providerId = draft.providerId
		if (providerId.isBlank()) return
		_state.value = _state.value.copy(
			mediaDraft = draft.copy(
				models = emptyList(),
				loadingModels = true,
				modelError = null,
			),
		)
		viewModelScope.launch {
			val result = agents.models(providerId)
			val tags = runCatching { agents.modelTags(providerId) }.getOrDefault(emptyMap())
			val current = _state.value.mediaDraft
			if (current == null ||
				current.capability != draft.capability ||
				current.providerId != providerId
			) {
				return@launch
			}
			result.onSuccess { models ->
				val filtered = models.filter { model ->
					draft.capability.modelCapability in
						ModelCapabilities.effective(model.id, tags[model.id])
				}.ifEmpty { models }
				_state.value = _state.value.copy(
					mediaDraft = current.copy(
						models = filtered,
						loadingModels = false,
						modelError = null,
					),
				)
			}.onFailure { failure ->
				_state.value = _state.value.copy(
					mediaDraft = current.copy(
						models = emptyList(),
						loadingModels = false,
						modelError = failure.message
							?: context.getString(R.string.settings_media_models_failed),
					),
				)
			}
		}
	}

	private fun loadMediaConfigs(): Map<MediaCapability, MediaToolConfig> =
		MediaCapability.entries.associateWith { MediaToolStore.load(secure, it) }

	/**
	 * Auto-read is an opt-in made in Settings; that is also where the paid
	 * backend is disclosed. Enabling it records the same consent the one-time
	 * notice would, so a later manual tap does not ask again.
	 */
	fun setAutoReadReplies(enabled: Boolean) {
		if (enabled) {
			secure.put(SecureStore.KEY_SPEECH_AUTO_READ, "1")
			secure.put(SecureStore.KEY_SPEECH_REPLY_CONSENT, "1")
		} else {
			secure.remove(SecureStore.KEY_SPEECH_AUTO_READ)
		}
		_state.value = _state.value.copy(autoReadReplies = enabled)
	}

	/** Empty means the backend's default voice. */
	fun saveSpeechVoice(value: String) {
		val trimmed = value.trim()
		if (trimmed.isEmpty()) {
			secure.remove(SecureStore.KEY_SPEECH_VOICE)
		} else {
			secure.put(SecureStore.KEY_SPEECH_VOICE, trimmed)
		}
		_state.value = _state.value.copy(speechVoice = trimmed)
	}

	fun setBackupReminderDays(days: Int) {
		val normalized = BackupReminderPolicy.normalizePeriod(days)
		secure.put(SecureStore.KEY_BACKUP_REMINDER_DAYS, normalized.toString())
		_state.value = _state.value.copy(backupReminderDays = normalized)
	}

	private data class BackupFolderState(
		val uri: String?,
		val name: String?,
		val permissionLost: Boolean,
	)

	private fun readBackupFolderState(): BackupFolderState {
		val stored = secure.get(SecureStore.KEY_BACKUP_FOLDER_URI)
			?.trim()
			?.takeIf { it.isNotEmpty() }
			?: return BackupFolderState(null, null, false)
		val parsed = runCatching { Uri.parse(stored) }.getOrNull()
			?: return BackupFolderState(null, null, false)
		val treeId = runCatching { DocumentsContract.getTreeDocumentId(parsed) }.getOrNull()
		val name = backupFolderDisplayName(stored, treeId) ?: stored
		val hasWriteGrant = runCatching {
			context.contentResolver.persistedUriPermissions.any { permission ->
				permission.uri == parsed && permission.isWritePermission
			}
		}.getOrDefault(false)
		return BackupFolderState(
			uri = stored,
			name = name,
			permissionLost = !hasWriteGrant,
		)
	}

	/** Re-check the remembered backup folder and its SAF permission grant. */
	fun refreshBackupFolder() {
		val folder = readBackupFolderState()
		_state.value = _state.value.copy(
			backupFolderUri = folder.uri,
			backupFolderName = folder.name,
			backupFolderPermissionLost = folder.permissionLost,
		)
	}

	/** Remember a user-picked SAF directory tree for one-tap backups. */
	fun setBackupFolder(uri: Uri) {
		val previous = secure.get(SecureStore.KEY_BACKUP_FOLDER_URI)
			?.trim()
			?.takeIf { it.isNotEmpty() && it != uri.toString() }
		if (previous != null) {
			runCatching {
				context.contentResolver.releasePersistableUriPermission(
					Uri.parse(previous),
					Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
				)
			}
		}
		secure.put(SecureStore.KEY_BACKUP_FOLDER_URI, uri.toString())
		val folder = readBackupFolderState()
		_state.value = _state.value.copy(
			backupFolderUri = folder.uri,
			backupFolderName = folder.name,
			backupFolderPermissionLost = folder.permissionLost,
			backupStatus = null,
			backupStatusIsError = false,
		)
	}

	/** Forget the remembered SAF directory tree and release its grant. */
	fun clearBackupFolder() {
		val previous = secure.get(SecureStore.KEY_BACKUP_FOLDER_URI)
			?.trim()
			?.takeIf { it.isNotEmpty() }
		if (previous != null) {
			runCatching {
				context.contentResolver.releasePersistableUriPermission(
					Uri.parse(previous),
					Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
				)
			}
		}
		secure.remove(SecureStore.KEY_BACKUP_FOLDER_URI)
		_state.value = _state.value.copy(
			backupFolderUri = null,
			backupFolderName = null,
			backupFolderPermissionLost = false,
			backupStatus = null,
			backupStatusIsError = false,
		)
	}

	/** Export the database and workspaces to a user-chosen document. */
	fun exportBackup(uri: Uri, passphrase: CharArray) {
		if (_state.value.backupBusy) {
			passphrase.fill('\u0000')
			return
		}
		_state.value = _state.value.copy(
			backupBusy = true,
			backupStatus = null,
			backupNeedsRestart = false,
		)
		viewModelScope.launch {
			val result = runCatching { backup.export(uri, passphrase) }
			passphrase.fill('\u0000')
			val exportedAt = if (result.isSuccess) System.currentTimeMillis() else null
			if (exportedAt != null) {
				secure.put(SecureStore.KEY_BACKUP_LAST_EXPORT_AT, exportedAt.toString())
				// A completed export answers any pending reminder; clearing the
				// snooze keeps a later period from being shortened by it.
				secure.put(SecureStore.KEY_BACKUP_SNOOZED_UNTIL, "0")
			}
			_state.value = _state.value.copy(
				backupBusy = false,
				backupLastExportAt = exportedAt ?: _state.value.backupLastExportAt,
				backupStatus = result.fold(
					onSuccess = {
						context.getString(R.string.settings_status_backup_written)
					},
					onFailure = { failure ->
						Diag.w("backup", "export failed", failure)
						context.getString(
							R.string.settings_error_backup_failed,
							failure.message ?: context.getString(R.string.settings_error_unknown),
						)
					},
				),
				backupStatusIsError = result.isFailure,
			)
		}
	}

	/**
	 * Export a timestamped encrypted archive directly into the remembered SAF
	 * folder without opening the system file chooser again.
	 */
	fun exportBackupToFolder(passphrase: CharArray) {
		if (_state.value.backupBusy) {
			passphrase.fill('\u0000')
			return
		}
		val folder = readBackupFolderState()
		val uriString = folder.uri
		if (uriString == null || folder.permissionLost) {
			passphrase.fill('\u0000')
			_state.value = _state.value.copy(
				backupFolderUri = folder.uri,
				backupFolderName = folder.name,
				backupFolderPermissionLost = folder.permissionLost,
				backupStatus = context.getString(R.string.settings_error_backup_folder_permission),
				backupStatusIsError = true,
			)
			return
		}
		val treeUri = Uri.parse(uriString)
		val folderLabel = folder.name ?: uriString
		_state.value = _state.value.copy(
			backupBusy = true,
			backupStatus = null,
			backupNeedsRestart = false,
		)
		viewModelScope.launch {
			val now = System.currentTimeMillis()
			val targetName = backupArchiveFileName(now)
			val result = runCatching { backup.exportToTree(treeUri, targetName, passphrase) }
			passphrase.fill('\u0000')
			val exportedAt = if (result.isSuccess) System.currentTimeMillis() else null
			if (exportedAt != null) {
				secure.put(SecureStore.KEY_BACKUP_LAST_EXPORT_AT, exportedAt.toString())
				secure.put(SecureStore.KEY_BACKUP_SNOOZED_UNTIL, "0")
			}
			val updatedFolder = readBackupFolderState()
			_state.value = _state.value.copy(
				backupBusy = false,
				backupLastExportAt = exportedAt ?: _state.value.backupLastExportAt,
				backupFolderUri = updatedFolder.uri,
				backupFolderName = updatedFolder.name,
				backupFolderPermissionLost = updatedFolder.permissionLost,
				backupStatus = result.fold(
					onSuccess = { createdName ->
						context.getString(
							R.string.settings_status_backup_written_folder,
							createdName,
							folderLabel,
						)
					},
					onFailure = { failure ->
						Diag.w("backup", "folder export failed", failure)
						if (failure is SecurityException || updatedFolder.permissionLost) {
							context.getString(R.string.settings_error_backup_folder_permission)
						} else {
							context.getString(
								R.string.settings_error_backup_failed,
								failure.message ?: context.getString(R.string.settings_error_unknown),
							)
						}
					},
				),
				backupStatusIsError = result.isFailure,
			)
		}
	}

	/**
	 * Decrypt a backup and show what it contains, without staging anything.
	 *
	 * The passphrase is held only until the user confirms or cancels; it is not
	 * part of the observable UI state.
	 */
	fun previewImport(uri: Uri, passphrase: CharArray) {
		if (_state.value.backupBusy) return
		pendingImportUri = uri
		pendingImportPassphrase?.fill('\u0000')
		pendingImportPassphrase = passphrase.copyOf()
		passphrase.fill('\u0000')
		_state.value = _state.value.copy(
			backupBusy = true,
			backupStatus = null,
			backupNeedsRestart = false,
			backupPreview = null,
		)
		viewModelScope.launch {
			val result = runCatching { backup.inspect(uri, pendingImportPassphrase!!) }
			if (result.isFailure) {
				pendingImportPassphrase?.fill('\u0000')
				pendingImportPassphrase = null
				pendingImportUri = null
			}
			_state.value = _state.value.copy(
				backupBusy = false,
				backupPreview = result.getOrNull(),
				backupStatus = result.exceptionOrNull()?.let { failure ->
					Diag.w("backup", "preview failed", failure)
					context.getString(
						R.string.settings_error_backup_read,
						failure.message ?: context.getString(R.string.settings_error_bad_passphrase),
					)
				},
				backupStatusIsError = result.isFailure,
			)
		}
	}

	/** Stage the previewed backup. Nothing is replaced until the next launch. */
	fun confirmImport() {
		val uri = pendingImportUri ?: return
		val passphrase = pendingImportPassphrase ?: return
		if (_state.value.backupBusy) return
		_state.value = _state.value.copy(
			backupBusy = true,
			backupStatus = null,
			backupNeedsRestart = false,
			backupPreview = null,
		)
		viewModelScope.launch {
			val result = runCatching { backup.stageImport(uri, passphrase) }
			passphrase.fill('\u0000')
			pendingImportPassphrase = null
			pendingImportUri = null
			_state.value = _state.value.copy(
				backupBusy = false,
				backupStatus = result.fold(
					onSuccess = {
						context.getString(R.string.settings_status_backup_staged)
					},
					onFailure = { failure ->
						Diag.w("backup", "import failed", failure)
						context.getString(
							R.string.settings_error_import_failed,
							failure.message ?: context.getString(R.string.settings_error_bad_passphrase),
						)
					},
				),
				backupStatusIsError = result.isFailure,
				backupNeedsRestart = result.isSuccess,
			)
		}
	}

	fun cancelImport() {
		pendingImportPassphrase?.fill('\u0000')
		pendingImportPassphrase = null
		pendingImportUri = null
		_state.value = _state.value.copy(backupPreview = null)
	}

	/**
	 * Stage the copy kept by the last import. Also needs a restart, because the
	 * database must not be replaced under Room.
	 */
	fun undoLastImport() {
		if (_state.value.backupBusy) return
		_state.value = _state.value.copy(
			backupBusy = true,
			backupStatus = null,
			backupNeedsRestart = false,
		)
		viewModelScope.launch {
			val staged = runCatching { backup.stageUndo() }.getOrDefault(false)
			_state.value = _state.value.copy(
				backupBusy = false,
				backupStatus = if (staged) {
					context.getString(R.string.settings_status_undo_staged)
				} else {
					context.getString(R.string.settings_status_no_undo)
				},
				backupStatusIsError = !staged,
				backupNeedsRestart = staged,
				backupCanUndo = if (staged) false else _state.value.backupCanUndo,
			)
		}
	}

	fun clearBackupStatus() {
		_state.value = _state.value.copy(backupStatus = null, backupStatusIsError = false)
	}

	/**
	 * Read the rows behind the Usage section.
	 *
	 * Room runs the query on its own executor; the fold over the rows runs on
	 * Default because a long transcript can have thousands of them and this is
	 * a settings screen, not a reason to park a frame.
	 */
	fun refreshUsage() {
		if (_state.value.usageLoading) return
		val revision = ++usageRevision
		_state.update { it.copy(usageLoading = true, usageFailed = false) }
		viewModelScope.launch {
			val loaded = runCatching {
				repository.usageRows() to repository.allMaintenanceRuns()
			}
			loaded.onSuccess { (rows, maintenanceRuns) ->
				usageRows = rows
				usageMaintenanceRuns = maintenanceRuns
				val period = _state.value.usagePeriod
				val report = withContext(Dispatchers.Default) {
					buildUsageReport(rows, paidToolNames, period, maintenanceRuns)
				}
				if (revision == usageRevision) {
					_state.update { it.copy(usageLoading = false, usage = report) }
				}
			}.onFailure { failure ->
				Diag.w("usage", "could not read usage", failure)
				if (revision == usageRevision) {
					_state.update { it.copy(usageLoading = false, usageFailed = true) }
				}
			}
		}
	}

	/**
	 * Switch the Usage window.
	 *
	 * If the first read is still in flight, [usageRows] is null and there is
	 * nothing to rebuild: that read takes the period from the state when it
	 * finishes, so it stays authoritative -- but it must not be made stale by a
	 * revision bump here, or its result would be discarded and nothing would
	 * replace it.
	 */
	fun setUsagePeriod(period: UsagePeriod) {
		if (period == _state.value.usagePeriod) return
		_state.update { it.copy(usagePeriod = period) }
		val rows = usageRows ?: return
		val revision = ++usageRevision
		viewModelScope.launch {
			val report = withContext(Dispatchers.Default) {
				buildUsageReport(rows, paidToolNames, period, usageMaintenanceRuns)
			}
			if (revision == usageRevision) {
				_state.update { it.copy(usageLoading = false, usage = report) }
			}
		}
	}

	override fun onCleared() {
		pendingImportPassphrase?.fill('\u0000')
		pendingImportPassphrase = null
		super.onCleared()
	}
}
