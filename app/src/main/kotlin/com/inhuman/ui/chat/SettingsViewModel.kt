package com.inhuman.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.ai.provider.deepseek.DeepSeekProvider
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.secret.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
	/** What is in the field, which is not the same as what is stored. */
	val apiKeyInput: String = "",
	val hasSavedKey: Boolean = false,
	val isChecking: Boolean = false,
	val status: String? = null,
	val statusIsError: Boolean = false,
	/** Reply from the built-in scripted source instead of DeepSeek. */
	val demoMode: Boolean = false,
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
	private val secure: SecureStore,
	private val agents: AgentRepository,
	private val repository: ConversationRepository,
) : ViewModel() {

	private val _state = MutableStateFlow(SettingsUiState())
	val state: StateFlow<SettingsUiState> = _state.asStateFlow()

	init {
		// Prefill with the stored key. It is the user's own key on their own
		// device, behind a password-style field, so showing it is less
		// annoying than making them re-enter it to check it is the right one.
		val existing = secure.get(SecureStore.KEY_DEEPSEEK_API_KEY)
		_state.value = SettingsUiState(
			apiKeyInput = existing.orEmpty(),
			hasSavedKey = !existing.isNullOrBlank(),
			demoMode = secure.get(SecureStore.KEY_DEMO_MODE) == "1",
			sshToolEnabled = secure.get(SecureStore.KEY_SSH_TOOL_ENABLED) == "1",
		)
		loadRules()
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

	fun setDemoMode(enabled: Boolean) {
		secure.put(SecureStore.KEY_DEMO_MODE, if (enabled) "1" else "0")
		_state.value = _state.value.copy(
			demoMode = enabled,
			status = if (enabled) {
				"Demo mode on. Replies are scripted locally and no request is sent."
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
				"SSH tool enabled. An assistant must still select it, and the first call in each conversation asks for consent."
			} else {
				"SSH tool disabled. Existing SSH consents were revoked."
			},
			statusIsError = false,
		)
		Diag.i("ssh", "global tool enabled=$enabled")
		if (!enabled) {
			viewModelScope.launch { repository.clearAllSshAllowed() }
		}
	}

	fun onApiKeyChange(value: String) {
		_state.value = _state.value.copy(apiKeyInput = value, status = null)
	}

	fun save() {
		val key = _state.value.apiKeyInput.trim()
		if (key.isEmpty()) {
			Diag.i("settings", "API key removed")
			secure.remove(SecureStore.KEY_DEEPSEEK_API_KEY)
			// The model list was fetched with the old key and is now either
			// stale or was fetched with a key that no longer exists. Dropping it
			// is the only correct thing: a picker offering models the current
			// key cannot reach is worse than an empty one.
			viewModelScope.launch { agents.invalidateModels() }
			_state.value = _state.value.copy(
				hasSavedKey = false,
				status = "Key removed.",
				statusIsError = false,
			)
			return
		}
		secure.put(SecureStore.KEY_DEEPSEEK_API_KEY, key)
		Diag.i("settings", "API key saved (encrypted)")
		viewModelScope.launch { agents.invalidateModels() }
		_state.value = _state.value.copy(
			hasSavedKey = true,
			status = "Saved, encrypted with a Keystore-held key.",
			statusIsError = false,
		)
	}

	/**
	 * Ask DeepSeek whether the key works.
	 *
	 * Reports the models it can see rather than a bare "OK", because that is
	 * evidence rather than an assertion -- and it tells the user which models
	 * the app knows about.
	 */
	fun verify() {
		val key = secure.get(SecureStore.KEY_DEEPSEEK_API_KEY)
		if (key.isNullOrBlank()) {
			_state.value = _state.value.copy(
				status = "Save a key first.",
				statusIsError = true,
			)
			return
		}

		_state.value = _state.value.copy(isChecking = true, status = "Checking…", statusIsError = false)

		viewModelScope.launch {
			val result = runCatching { DeepSeekProvider(apiKey = key).listModels() }
			result.onSuccess { Diag.i("models", "connection check ok models=${it.size}") }
				.onFailure {
					Diag.w("models", "connection check failed: ${it.message}")
				}
			_state.value = result.fold(
				onSuccess = { models ->
					_state.value.copy(
						isChecking = false,
						statusIsError = false,
						status = if (models.isEmpty()) {
							"Connected, but no models were listed."
						} else {
							"Connected. ${models.size} model(s): " +
								models.joinToString(", ") { it.id }
						},
					)
				},
				onFailure = { e ->
					_state.value.copy(
						isChecking = false,
						statusIsError = true,
						status = e.message ?: "Connection failed.",
					)
				},
			)
		}
	}
}
