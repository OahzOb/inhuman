package com.inhuman.web

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.inhuman.core.ai.tools.DownloadUrl
import com.inhuman.core.ai.tools.FetchedPage
import com.inhuman.core.ai.tools.PageRenderer
import com.inhuman.core.common.diag.Diag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume

/**
 * The Android half of the local fetcher's `render = true` path.
 *
 * ## What runs
 *
 * A fresh WebView loads the page with JavaScript enabled (that is the whole
 * point: SPA and lazy-loaded pages), waits for `onPageFinished` plus a short
 * settle, then reads `document.title` and `document.body.innerText`. `innerText`
 * is the visible text, so hidden nodes and script/style bodies never enter the
 * model's context. The WebView is destroyed after every call.
 *
 * ## What is deliberately absent
 *
 * No `addJavascriptInterface` (the page cannot call into the app), no cookie
 * injection, no download listener, no file access, no popups, no multiple
 * windows. The page's own scripts still run and may make their own requests --
 * that is inherent to rendering and is disclosed in Settings and the tool
 * description. A private host is refused before the engine opens anything.
 *
 * This is device-only code; `:core:ai` sees only the [PageRenderer] interface.
 */
class WebViewPageRenderer(context: Context) : PageRenderer {

	private val appContext = context.applicationContext

	override suspend fun render(url: String): FetchedPage {
		// Refuse a private target before a browser engine is involved.
		DownloadUrl.validate(url, requireHttps = true)
		return withContext(Dispatchers.Main) {
			val webView = WebView(appContext)
			try {
				configure(webView)
				val loaded = CompletableDeferred<Unit>()
				webView.webViewClient = object : WebViewClient() {
					override fun onPageFinished(view: WebView, pageUrl: String) {
						loaded.complete(Unit)
					}

					override fun onReceivedError(
						view: WebView,
						request: WebResourceRequest,
						error: WebResourceError,
					) {
						// Subresource failures are normal; only the main frame
						// means "the page did not load".
						if (request.isForMainFrame) {
							loaded.completeExceptionally(
								IOException(
									"the page could not be loaded: " +
										error.description.toString(),
								),
							)
						}
					}

					override fun shouldOverrideUrlLoading(
						view: WebView,
						request: WebResourceRequest,
					): Boolean {
						// Main-frame navigation is re-validated with the same
						// URL rules; a redirect to a private address never
						// reaches the engine.
						if (!request.isForMainFrame) return false
						return runCatching {
							DownloadUrl.validate(
								request.url.toString(),
								requireHttps = true,
							)
							false
						}.getOrDefault(true)
					}
				}

				webView.loadUrl(url)
				if (withTimeoutOrNull(LOAD_TIMEOUT_MS) { loaded.await() } == null) {
					throw IOException(
						"the page did not finish loading within ${LOAD_TIMEOUT_MS / 1000}s",
					)
				}
				// `onPageFinished` fires before late XHR/rendering settles.
				delay(SETTLE_MS)
				readPage(webView)
			} finally {
				runCatching {
					webView.stopLoading()
					webView.loadUrl("about:blank")
					webView.destroy()
				}
				// These stores are WebView-global; this app has no other WebView,
				// so clearing them cannot surprise another screen.
				runCatching { CookieManager.getInstance().removeAllCookies(null) }
				runCatching { WebStorage.getInstance().deleteAllData() }
			}
		}
	}

	private fun configure(webView: WebView) {
		with(webView.settings) {
			javaScriptEnabled = true
			domStorageEnabled = false
			allowFileAccess = false
			allowContentAccess = false
			cacheMode = WebSettings.LOAD_NO_CACHE
			setSupportMultipleWindows(false)
			javaScriptCanOpenWindowsAutomatically = false
			mediaPlaybackRequiresUserGesture = true
			userAgentString = com.inhuman.core.ai.tools.WebHeaders.USER_AGENT
		}
		webView.setDownloadListener { _, _, _, _, _ -> /* never download */ }
	}

	/** Read the rendered page as JSON so the callback is one round trip. */
	private suspend fun readPage(webView: WebView): FetchedPage =
		suspendCancellableCoroutine { continuation ->
			val script = """
				(function () {
					var body = document.body ? document.body.innerText : "";
					if (typeof body !== "string") body = String(body || "");
					return JSON.stringify({
						title: document.title || "",
						text: body
					});
				})()
			""".trimIndent()
			webView.evaluateJavascript(script) { raw ->
				val page = runCatching {
					val decoded = org.json.JSONTokener(raw).nextValue() as? String ?: raw
					val json = JSONObject(decoded)
					FetchedPage(
						title = json.optString("title"),
						content = json.optString("text"),
					)
				}.getOrElse { failure ->
					Diag.w("webfetch", "could not read the rendered page", failure)
					FetchedPage(title = "", content = "")
				}
				if (continuation.isActive) continuation.resume(page)
			}
		}

	private companion object {
		const val LOAD_TIMEOUT_MS = 20_000L
		const val SETTLE_MS = 1_500L
	}
}
