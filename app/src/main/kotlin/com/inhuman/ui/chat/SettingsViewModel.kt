package com.inhuman.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.R
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.ai.provider.deepseek.DeepSeekProvider
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.BackupPreview
import com.inhuman.core.data.BackupRepository
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.db.ProviderIds
import com.inhuman.core.data.db.ProviderKinds
import com.inhuman.core.data.db.ProviderProfileEntity
import com.inhuman.core.data.db.ProviderReasoning
import com.inhuman.core.data.secret.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI
import java.util.UUID

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

	init {
		_state.value = SettingsUiState(
			demoMode = secure.get(SecureStore.KEY_DEMO_MODE) == "1",
			toolRoundLimit = readToolRoundLimit(),
			toolCallLimit = readToolCallLimit(),
			layoutPreference = com.inhuman.core.design.LayoutPreference.fromStored(
				secure.get(SecureStore.KEY_LAYOUT_PREFERENCE),
			),
			sshToolEnabled = secure.get(SecureStore.KEY_SSH_TOOL_ENABLED) == "1",
			webSearchBackend = secure.get(SecureStore.KEY_WEB_SEARCH_BACKEND) ?: "deepseek",
			webFetchBackend = secure.get(SecureStore.KEY_WEB_FETCH_BACKEND) ?: "deepseek",
			webDeepSeekKeyInput = secure.get(SecureStore.KEY_WEB_DEEPSEEK_API_KEY).orEmpty(),
			webExaKeyInput = secure.get(SecureStore.KEY_WEB_EXA_API_KEY).orEmpty(),
			hasWebDeepSeekKey = secure.hasUsable(SecureStore.KEY_WEB_DEEPSEEK_API_KEY),
			hasWebExaKey = secure.hasUsable(SecureStore.KEY_WEB_EXA_API_KEY),
			backupCanUndo = backup.hasUndo(),
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

	fun hasProviderKey(providerId: String): Boolean = agents.hasKey(providerId)

	fun addProvider() {
		val existing = _state.value.providers.map { it.name }.toSet()
		var name = "OpenAI-compatible"
		var suffix = 2
		while (name in existing) {
			name = "OpenAI-compatible $suffix"
			suffix += 1
		}
		_state.value = _state.value.copy(
			editingProvider = ProviderDraft(
				id = UUID.randomUUID().toString(),
				isNew = true,
				isBuiltIn = false,
				name = name,
				kind = ProviderKinds.OPENAI_COMPATIBLE,
				baseUrl = "https://api.openai.com/v1",
				reasoningMode = ProviderReasoning.NONE,
				includeStreamUsage = true,
				manualModels = "",
				keyInput = "",
				hasKey = false,
			),
		)
	}

	fun editProvider(profile: ProviderProfileEntity) {
		val key = secure.get(agents.apiKeySlot(profile.id)).orEmpty()
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

		val now = System.currentTimeMillis()
		val profile = ProviderProfileEntity(
			id = draft.id,
			name = cleanName,
			kind = draft.kind,
			baseUrl = baseUrl,
			reasoningMode = draft.reasoningMode,
			includeStreamUsage = draft.includeStreamUsage,
			manualModels = draft.manualModels.trim(),
			isBuiltIn = draft.isBuiltIn,
			createdAt = now,
			updatedAt = now,
		)
		viewModelScope.launch {
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
				DeepSeekProvider(
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
