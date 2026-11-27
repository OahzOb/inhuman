package com.inhuman.updates

import com.inhuman.core.data.secret.SecureStore

/**
 * Read the update and upload addresses, migrating the pre-split single address.
 *
 * Before the split there was one `local.server_url` used by both. The migration
 * writes the derived values and removes the legacy key, so it happens once, on
 * whichever surface reads first. Falling back every time instead would make
 * "clear this field" impossible: the old address would come back on the next
 * screen, and the user could not tell a cleared field from an unsaved one.
 *
 * Both readers (Settings and the chat's conversation upload) go through this,
 * because either can be the first surface after an upgrade.
 */
fun loadServerEndpoints(secure: SecureStore): ServerEndpoints {
	val legacy = secure.get(SecureStore.KEY_LOCAL_SERVER_URL)
	val endpoints = resolveServerEndpoints(
		updateRaw = secure.get(SecureStore.KEY_UPDATE_SERVER_URL),
		uploadRaw = secure.get(SecureStore.KEY_UPLOAD_SERVER_URL),
		legacyRaw = legacy,
	)
	if (legacy != null) {
		storeEndpoint(secure, SecureStore.KEY_UPDATE_SERVER_URL, endpoints.updateBaseUrl)
		storeEndpoint(secure, SecureStore.KEY_UPLOAD_SERVER_URL, endpoints.uploadBaseUrl)
		secure.remove(SecureStore.KEY_LOCAL_SERVER_URL)
	}
	return endpoints
}

/**
 * Persist one address; an empty or unusable field removes the stored value.
 *
 * Writing on every edit is what lets the chat screen and the updater see a
 * typed address immediately, and removing an invalid one is what keeps a hidden
 * target from sitting behind a visibly empty field.
 */
fun storeServerUrl(secure: SecureStore, key: String, raw: String) {
	storeEndpoint(secure, key, normalizeServerUrl(raw))
}

private fun storeEndpoint(secure: SecureStore, key: String, value: String?) {
	if (value != null) secure.put(key, value) else secure.remove(key)
}
