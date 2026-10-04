package com.inhuman.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inhuman.core.ai.provider.deepseek.DeepSeekProvider
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
		)
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

	fun onApiKeyChange(value: String) {
		_state.value = _state.value.copy(apiKeyInput = value, status = null)
	}

	fun save() {
		val key = _state.value.apiKeyInput.trim()
		if (key.isEmpty()) {
			secure.remove(SecureStore.KEY_DEEPSEEK_API_KEY)
			_state.value = _state.value.copy(
				hasSavedKey = false,
				status = "Key removed.",
				statusIsError = false,
			)
			return
		}
		secure.put(SecureStore.KEY_DEEPSEEK_API_KEY, key)
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
