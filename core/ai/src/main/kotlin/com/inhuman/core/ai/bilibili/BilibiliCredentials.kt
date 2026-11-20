package com.inhuman.core.ai.bilibili

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Bilibili account material a read-only login yields.
 *
 * ## What is stored, and what deliberately is not
 *
 * Only `SESSDATA` and `DedeUserID`: the two cookies read endpoints need to
 * recognise the caller. **`bili_jct` is not stored** even though login returns
 * it — that is the CSRF token write endpoints require, and this app has no
 * write path to Bilibili. Not keeping it means a leaked store cannot post,
 * like, favourite or follow on the user's behalf; it does not even have the
 * token to try.
 *
 * [userName] is display-only (the settings row says which account is linked);
 * [savedAtMillis] lets the UI say when the link was made. Both are inside the
 * platform secret store with the cookie, never in a backup, a log or a prompt.
 */
@Serializable
data class BilibiliCredentials(
	val sessdata: String,
	val dedeUserId: String,
	val userName: String = "",
	val savedAtMillis: Long = 0L,
) {

	/** Both halves are required; one without the other authenticates nothing. */
	val isUsable: Boolean get() = sessdata.isNotBlank() && dedeUserId.isNotBlank()

	fun toStoredJson(): String = STORED_JSON.encodeToString(this)

	companion object {
		private val STORED_JSON = Json { ignoreUnknownKeys = true }

		/**
		 * Decode what was stored, or null.
		 *
		 * A malformed or half-written value reads as "not logged in" rather
		 * than throwing: the next QR scan repairs it, and a settings screen
		 * that cannot open is worse than one that asks for a scan again.
		 */
		fun fromStoredJson(raw: String?): BilibiliCredentials? {
			if (raw.isNullOrBlank()) return null
			val credentials = runCatching {
				STORED_JSON.decodeFromString<BilibiliCredentials>(raw)
			}.getOrNull()
			return credentials?.takeIf { it.isUsable }
		}
	}
}

/** A QR the passport endpoint handed out; the user scans [url]. */
data class BilibiliQr(val key: String, val url: String)

/** What one poll of that QR answered. */
sealed interface BilibiliQrState {
	/** Nobody has scanned it yet. */
	data object Waiting : BilibiliQrState

	/** Scanned, waiting for the confirm tap on the phone. */
	data object Scanned : BilibiliQrState

	/** The QR timed out; generate a fresh one. */
	data object Expired : BilibiliQrState

	/** Confirmed; these are the cookies. */
	data class Confirmed(val credentials: BilibiliCredentials) : BilibiliQrState

	/** A refusal or a response that could not be understood. */
	data class Failed(val message: String) : BilibiliQrState
}

/** The account behind a set of credentials, as Settings shows it. */
data class BilibiliProfile(
	val mid: Long,
	val name: String,
	val vip: Boolean,
)
