package com.inhuman.web

import com.inhuman.core.ai.provider.deepseek.DeepSeekFetchApi
import com.inhuman.core.ai.provider.deepseek.DeepSeekSearchApi
import com.inhuman.core.ai.provider.exa.ExaFetchApi
import com.inhuman.core.ai.provider.exa.ExaSearchApi
import com.inhuman.core.ai.tools.FetchProvider
import com.inhuman.core.ai.tools.SearchProvider
import com.inhuman.core.data.secret.SecureStore

/**
 * Builds the web-tool backend the user selected.
 *
 * The keys are deliberately **not** the chat key. A user can chat with one
 * provider and search through another, and rotating a chat key must not silently
 * change who bills the searches. A missing key for the selected backend means
 * the tool is not registered at all, rather than registered and always failing.
 */
object WebToolProviders {

	const val DEEPSEEK = "deepseek"
	const val EXA = "exa"

	fun search(secure: SecureStore): SearchProvider? =
		when (secure.get(SecureStore.KEY_WEB_SEARCH_BACKEND) ?: DEEPSEEK) {
			EXA -> secure.get(SecureStore.KEY_WEB_EXA_API_KEY)
				?.takeIf { it.isNotBlank() }
				?.let { ExaSearchApi(it) }

			else -> secure.get(SecureStore.KEY_WEB_DEEPSEEK_API_KEY)
				?.takeIf { it.isNotBlank() }
				?.let { key -> DeepSeekSearchApi(apiKey = { key }) }
		}

	fun fetch(secure: SecureStore): FetchProvider? =
		when (secure.get(SecureStore.KEY_WEB_FETCH_BACKEND) ?: DEEPSEEK) {
			EXA -> secure.get(SecureStore.KEY_WEB_EXA_API_KEY)
				?.takeIf { it.isNotBlank() }
				?.let { ExaFetchApi(it) }

			else -> secure.get(SecureStore.KEY_WEB_DEEPSEEK_API_KEY)
				?.takeIf { it.isNotBlank() }
				?.let { key -> DeepSeekFetchApi(apiKey = { key }) }
		}
}
