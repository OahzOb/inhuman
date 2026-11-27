package com.inhuman.web

import com.inhuman.core.ai.provider.local.BingRssSearchApi
import com.inhuman.core.ai.provider.local.DuckDuckGoSearchApi
import com.inhuman.core.ai.provider.local.LocalFetchApi
import com.inhuman.core.ai.tools.FetchProvider
import com.inhuman.core.ai.tools.PageRenderer
import com.inhuman.core.ai.tools.SearchProvider
import com.inhuman.core.data.secret.SecureStore

/**
 * Builds the app's own (keyless) web tools from Settings.
 *
 * These are deliberately **not** in [WebToolProviders]: that object is about
 * paid API credentials, while these tools make the request from the device's
 * own network. Two independent switches, both off by default; null means the
 * capability is off, and the tool is then absent rather than present and
 * failing.
 *
 * The closures are evaluated per turn, so flipping a switch in Settings takes
 * effect without reopening the conversation.
 */
object LocalWebProviders {

	const val ENGINE_DUCKDUCKGO = "duckduckgo"
	const val ENGINE_BING = "bing"
	const val DEFAULT_ENGINE = ENGINE_DUCKDUCKGO

	fun searchEnabled(secure: SecureStore): Boolean =
		secure.get(SecureStore.KEY_WEB_LOCAL_SEARCH_ENABLED) == "1"

	fun fetchEnabled(secure: SecureStore): Boolean =
		secure.get(SecureStore.KEY_WEB_LOCAL_FETCH_ENABLED) == "1"

	fun engine(secure: SecureStore): String =
		secure.get(SecureStore.KEY_WEB_LOCAL_SEARCH_ENGINE)
			?.takeIf { it == ENGINE_BING || it == ENGINE_DUCKDUCKGO }
			?: DEFAULT_ENGINE

	fun search(secure: SecureStore): SearchProvider? {
		if (!searchEnabled(secure)) return null
		return when (engine(secure)) {
			ENGINE_BING -> BingRssSearchApi()
			else -> DuckDuckGoSearchApi()
		}
	}

	fun fetch(secure: SecureStore, renderer: PageRenderer?): FetchProvider? {
		if (!fetchEnabled(secure)) return null
		return LocalFetchApi(renderer = renderer)
	}
}
