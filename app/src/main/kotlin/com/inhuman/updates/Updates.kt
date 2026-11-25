package com.inhuman.updates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI

/**
 * What the LAN/Tailscale update endpoint says is available.
 *
 * The manifest is deliberately tiny and static: the app is sideloaded, so the
 * only thing that needs to be reachable is one JSON file and one APK. There is
 * no account, no release service and no server-side state to keep.
 */
data class UpdateInfo(
	val versionCode: Long,
	val versionName: String,
	val apkUrl: String,
	val sha256: String,
	val notes: String,
)

private val json = Json { ignoreUnknownKeys = true; isLenient = true }
private val SHA256 = Regex("^[0-9a-f]{64}$")

/**
 * Trim and validate a server base URL.
 *
 * A trailing slash is removed so `base + "/update.json"` never produces a
 * double slash. Public hosts must use HTTPS; HTTP is accepted only for
 * addresses that are already on a private network, a Tailscale CGNAT address
 * or a Tailscale MagicDNS name. That keeps a typo from silently sending a log
 * or an update request in clear text across the internet.
 */
fun normalizeServerUrl(raw: String): String? {
	val text = raw.trim()
	if (text.isEmpty()) return null
	val uri = runCatching { URI(text) }.getOrNull() ?: return null
	val scheme = uri.scheme?.lowercase() ?: return null
	if (scheme != "http" && scheme != "https") return null
	if (uri.host.isNullOrBlank()) return null
	if (uri.userInfo != null) return null
	if (uri.query != null || uri.fragment != null) return null
	if (scheme == "http" && !isAllowedCleartext(uri)) return null
	return text.trimEnd('/')
}

fun updateManifestUrl(base: String): String = "$base/update.json"

/**
 * Release endpoints the settings screen offers as one-tap presets.
 *
 * The project publishes its own releases on GitHub, and the stable asset URL
 * `releases/latest/download` always resolves to the newest release, so a
 * phone's configured address never has to change between versions. Direct
 * GitHub asset downloads are unreliable from mainland China (measured at
 * ~13 KB/s with connection resets), while the two mirrors fetch the same
 * assets from the same release; they are listed after the canonical host in
 * measured speed order. The mirrors are third parties, which is why they are
 * a fallback and not the only address: the APK's signature and SHA-256 are
 * still verified, so a mirror cannot change what gets installed.
 *
 * These are presets in the settings UI, not hidden network calls: choosing one
 * only fills the same server-address field the user could type, and the field
 * remains visible and editable.
 */
val BUILT_IN_UPDATE_URLS: List<String> = listOf(
	"https://github.com/OahzOb/inhuman/releases/latest/download",
	"https://gh-proxy.com/https://github.com/OahzOb/inhuman/releases/latest/download",
	"https://ghfast.top/https://github.com/OahzOb/inhuman/releases/latest/download",
)

fun logUploadUrl(base: String): String = "$base/log"

/**
 * One conversation, deliberately not the diagnostics endpoint.
 *
 * The two bodies have different content and different retention: `/log` is
 * metadata with no message text, while a conversation is the transcript
 * itself. Keeping the paths separate makes the consent text and the server's
 * storage directory unambiguous.
 */
fun conversationUploadUrl(base: String): String = "$base/conversation"

fun isNewerVersion(remote: Long, current: Long): Boolean = remote > current

/**
 * Parse `update.json`.
 *
 * A missing field is a refusal rather than a default. In particular the
 * SHA-256 is required: an update endpoint that cannot state the hash is not
 * trusted to hand the app an APK, even if the APK's signature is checked
 * later. Relative URLs are resolved against the manifest's own base, so the
 * server can be moved without editing the file.
 */
fun parseUpdateInfo(text: String, baseUrl: String): UpdateInfo? {
	val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
		?: return null
	val versionCode = root["versionCode"]?.jsonPrimitive?.longOrNull ?: return null
	if (versionCode <= 0) return null
	val versionName = root["versionName"]?.jsonPrimitive?.contentOrNull
		?.takeIf { it.isNotBlank() }
		?: versionCode.toString()
	val rawUrl = root["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
		?: return null
	val apkUrl = resolveUpdateUrl(baseUrl, rawUrl) ?: return null
	val sha = root["sha256"]?.jsonPrimitive?.contentOrNull
		?.trim()
		?.lowercase()
		?.takeIf { SHA256.matches(it) }
		?: return null
	val notes = root["notes"]?.jsonPrimitive?.contentOrNull.orEmpty()
	return UpdateInfo(
		versionCode = versionCode,
		versionName = versionName,
		apkUrl = apkUrl,
		sha256 = sha,
		notes = notes,
	)
}

/** Resolve an absolute or relative APK URL against the manifest base. */
fun resolveUpdateUrl(baseUrl: String, raw: String): String? {
	val text = raw.trim()
	if (text.isEmpty()) return null
	val absolute = runCatching { URI(text) }.getOrNull()
	val uri = if (absolute != null && absolute.scheme != null) {
		absolute
	} else {
		// A manual join rather than `URI.resolve`: resolution treats a repeated
		// slash in a path as redundant and collapses it, so the mirror base
		// `https://gh-proxy.com/https://github.com/...` turned its inner
		// `https://` into `https:/` and named a URL the mirror only accepted by
		// accident. The release script writes a plain file name, so the join is
		// the whole rule; a path from the host root is also supported.
		val base = baseUrl.trim()
		val joined = when {
			// Not produced by the release script; refused rather than guessed.
			text.startsWith("//") -> return null
			text.startsWith("/") -> {
				val host = runCatching { URI(base) }.getOrNull()
				if (host?.scheme.isNullOrBlank() || host.rawAuthority.isNullOrBlank()) return null
				"${host.scheme}://${host.rawAuthority}$text"
			}
			else -> base.trimEnd('/') + "/" + text
		}
		runCatching { URI(joined) }.getOrNull() ?: return null
	}
	val scheme = uri.scheme?.lowercase() ?: return null
	if (scheme != "http" && scheme != "https") return null
	if (uri.host.isNullOrBlank()) return null
	if (scheme == "http" && !isAllowedCleartext(uri)) return null
	return uri.toString()
}

/** Whether [url] may be used over plain HTTP. */
fun isAllowedCleartextUrl(url: String): Boolean {
	val uri = runCatching { URI(url) }.getOrNull() ?: return false
	return when (uri.scheme?.lowercase()) {
		"https" -> true
		"http" -> isAllowedCleartext(uri)
		else -> false
	}
}

private fun isAllowedCleartext(uri: URI): Boolean {
	val host = uri.host
		?.lowercase()
		?.removeSurrounding("[", "]")
		?: return false
	if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
	if (host.endsWith(".local") || host.endsWith(".ts.net")) return true
	if (host.startsWith("fd7a:115c:a1e0:")) return true
	val parts = host.split('.').mapNotNull { it.toIntOrNull() }
	if (parts.size != 4 || parts.any { it !in 0..255 }) return false
	return when {
		parts[0] == 10 -> true
		parts[0] == 127 -> true
		parts[0] == 192 && parts[1] == 168 -> true
		parts[0] == 172 && parts[1] in 16..31 -> true
		// Tailscale's CGNAT range.
		parts[0] == 100 && parts[1] in 64..127 -> true
		parts[0] == 169 && parts[1] == 254 -> true
		else -> false
	}
}
