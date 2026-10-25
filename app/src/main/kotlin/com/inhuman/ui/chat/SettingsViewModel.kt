package com.inhuman.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.R
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.ai.provider.KnownProvider
import com.inhuman.core.ai.provider.ProviderCatalog
import com.inhuman.core.ai.provider.ProviderFactory
import com.inhuman.core.ai.ssh.SshLimits
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.BackupPreview
import com.inhuman.core.data.BackupRepository
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.db.ProviderIds
import com.inhuman.core.data.db.ProviderKinds
import com.inhuman.core.data.db.ProviderProfileEntity
import com.inhuman.core.data.db.ProviderReasoning
import com.inhuman.core.data.secret.SecureStore
import com.inhuman.diagnostics.AppDiagnostics
import com.inhuman.updates.UpdateInfo
import com.inhuman.updates.UpdateInstaller
import com.inhuman.updates.isNewerVersion
import com.inhuman.updates.logUploadUrl
import com.inhuman.updates.normalizeServerUrl
import com.inhuman.updates.parseUpdateInfo
import com.inhuman.updates.updateManifestUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
	/** Known provider: endpoint/kind come from the catalog, so only the key is asked. */
	val isPreset: Boolean = false,
	val testing: Boolean = false,
	val status: String? = null,
	val statusIsError: Boolean = false,
)

data class SettingsUiState(
	/** Result of the last demo/SSH action. */
	val status: String? = null,
	val statusIsError: Boolean = false,
	val providers: List<ProviderProfileEntity> = emptyList(),
	val editingProvider: ProviderDraft? = null,
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
	/** Reply from the built-in scripted source instead of DeepSeek. */
	val demoMode: Boolean = false,
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

	/** The verified download waiting for the Install button. */
	private var downloadedApk: File? = null

	init {
		val packageInfo = runCatching {
			context.packageManager.getPackageInfo(context.packageName, 0)
		}.getOrNull()
		_state.value = SettingsUiState(
			demoMode = secure.get(SecureStore.KEY_DEMO_MODE) == "1",
			toolRoundLimit = readToolRoundLimit(),
			toolCallLimit = readToolCallLimit(),
			layoutPreference = com.inhuman.core.design.LayoutPreference.fromStored(
				secure.get(SecureStore.KEY_LAYOUT_PREFERENCE),
			),
			sshToolEnabled = secure.get(SecureStore.KEY_SSH_TOOL_ENABLED) == "1",
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
			backupCanUndo = backup.hasUndo(),
			localServerUrl = secure.get(SecureStore.KEY_LOCAL_SERVER_URL).orEmpty(),
			currentVersionName = packageInfo?.versionName ?: "?",
			currentVersionCode = packageInfo?.longVersionCode ?: 0L,
		)
		viewModelScope.launch {
			agents.observeProviders().collect { list ->
				_state.value = _state.value.copy(providers = list)
			}
		}
		loadRules()
		loadKnownHosts()
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
		_state.value = _state.value.copy(
			updateChecking = true,
			updateStatus = null,
			updateStatusIsError = false,
			updateInfo = null,
			updateReadyToInstall = false,
			updateProgress = null,
		)
		downloadedApk = null
		viewModelScope.launch {
			val result = withContext(Dispatchers.IO) { runCatching { fetchUpdate(base) } }
			result.onSuccess { info ->
				val newer = isNewerVersion(info.versionCode, _state.value.currentVersionCode)
				Diag.i(
					"update",
					"check ok remote=${info.versionCode} current=${_state.value.currentVersionCode} newer=$newer",
				)
				_state.value = _state.value.copy(
					updateChecking = false,
					updateInfo = info.takeIf { newer },
					updateStatus = if (newer) {
						context.getString(R.string.settings_update_available, info.versionName)
					} else {
						context.getString(R.string.settings_update_uptodate)
					},
					updateStatusIsError = false,
				)
			}.onFailure { failure ->
				Diag.w("update", "check failed ${failure::class.simpleName}")
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
				_state.value = _state.value.copy(
					updateDownloading = false,
					updateProgress = null,
					updateReadyToInstall = false,
					updateStatus = context.getString(
						R.string.settings_update_failed,
						networkFailureMessage(failure),
					),
					updateStatusIsError = true,
				)
			}
		}
	}

	private fun download(info: UpdateInfo): File {
		val dir = File(context.cacheDir, "updates").apply { mkdirs() }
		dir.listFiles()?.forEach { runCatching { it.delete() } }
		val target = File(dir, "inhuman-${info.versionCode}.apk")
		val request = Request.Builder().url(info.apkUrl).build()
		http.newCall(request).execute().use { response ->
			if (!response.isSuccessful) error("HTTP ${response.code}")
			val body = response.body
			val total = body.contentLength()
			body.byteStream().use { input ->
				target.outputStream().use { output ->
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
		val actual = UpdateInstaller.sha256(target)
		if (!actual.equals(info.sha256, ignoreCase = true)) {
			target.delete()
			error("SHA-256 mismatch")
		}
		val mismatch = UpdateInstaller.verify(context, target, info.versionCode)
		if (mismatch != null) {
			target.delete()
			error(mismatch)
		}
		Diag.i("update", "download verified version=${info.versionCode} bytes=${target.length()}")
		return target
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

	fun uploadLog() {
		if (_state.value.logUploading) return
		val base = requireServerUrl()
		if (base == null) {
			_state.value = _state.value.copy(
				logUploadStatus = context.getString(R.string.settings_log_no_server),
				logUploadStatusIsError = true,
			)
			return
		}
		if (AppDiagnostics.sizeBytes() <= 0) {
			_state.value = _state.value.copy(
				logUploadStatus = context.getString(R.string.settings_log_empty),
				logUploadStatusIsError = true,
			)
			return
		}
		Diag.i("diag", "upload requested bytes=${AppDiagnostics.sizeBytes()}")
		_state.value = _state.value.copy(
			logUploading = true,
			logUploadStatus = null,
			logUploadStatusIsError = false,
		)
		viewModelScope.launch {
			val result = withContext(Dispatchers.IO) {
				runCatching {
					val text = AppDiagnostics.readText()
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
				reasoningMode = ProviderReasoning.NONE,
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
				hasKey = key.isNotBlank(),
				isPreset = isPreset,
			),
		)
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
			agents.saveProvider(profile, draft.keyInput)
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

	fun testProvider() {
		val draft = _state.value.editingProvider ?: return
		val key = draft.keyInput.trim().ifBlank {
			secure.get(agents.apiKeySlot(draft.id)).orEmpty()
		}
		if (key.isBlank()) {
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
			val result = runCatching {
				ProviderFactory.create(
					kind = draft.kind,
					apiKey = key,
					baseUrl = draft.baseUrl.trim().trimEnd('/'),
					id = draft.id,
					reasoningMode = draft.reasoningMode,
					includeStreamUsage = draft.includeStreamUsage,
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

	/** Export the database and workspaces to a user-chosen document. */
	fun exportBackup(uri: Uri, passphrase: CharArray) {
		if (_state.value.backupBusy) return
		_state.value = _state.value.copy(
			backupBusy = true,
			backupStatus = null,
			backupNeedsRestart = false,
		)
		viewModelScope.launch {
			val result = runCatching { backup.export(uri, passphrase) }
			passphrase.fill('\u0000')
			_state.value = _state.value.copy(
				backupBusy = false,
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

	override fun onCleared() {
		pendingImportPassphrase?.fill('\u0000')
		pendingImportPassphrase = null
		super.onCleared()
	}
}
