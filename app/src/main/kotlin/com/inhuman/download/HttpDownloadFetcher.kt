package com.inhuman.download

import com.inhuman.core.ai.tools.DownloadFetcher
import com.inhuman.core.ai.tools.DownloadUrl
import com.inhuman.core.ai.tools.FetchedDownload
import com.inhuman.ui.files.UrlDownloader

/**
 * The Android-side fetch for `download_url`.
 *
 * It is deliberately the same object the Files screen uses: SSRF checks, the
 * redirect-by-hand loop, the no-cookie client and the content-disposition name
 * already exist there. The only difference this seam asks for is `https`-only,
 * because the model names the URL; the user-facing path keeps accepting `http`
 * for the links people paste.
 *
 * A separate class from [UrlDownloader] so the tool depends on the `core:ai`
 * interface rather than on the UI object, which is what lets the tool run
 * against a fake in a JVM test.
 */
class HttpDownloadFetcher : DownloadFetcher {

	override suspend fun fetch(url: String): FetchedDownload {
		val response = UrlDownloader.open(url, requireHttps = true)
		return FetchedDownload(
			// Already sanitised by the downloader; sanitise again because this
			// name crosses into the workspace and one check is cheaper than a
			// dependency on the other class's order of operations.
			fileName = DownloadUrl.sanitizeName(response.fileName),
			mimeType = response.mimeType,
			contentLength = response.contentLength,
			body = response.body,
			close = response::close,
		)
	}
}
