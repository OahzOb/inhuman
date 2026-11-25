package com.inhuman.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inhuman.core.ai.tools.DownloadSaveResult
import com.inhuman.core.files.SessionWorkspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The one device question the JVM fakes cannot answer: does the real OkHttp
 * client, its SSRF guard and the workspace import path agree on a real phone?
 *
 * This is the only networked test for the download tool. It fetches
 * `https://example.com/` — a small, stable, public https page — and checks the
 * bytes land under `downloads/`. The private-address case proves the refusal
 * happens before any socket is opened (the URL is never resolved).
 */
@RunWith(AndroidJUnit4::class)
class DownloadIntegrationTest {

	private val context = InstrumentationRegistry.getInstrumentation().targetContext

	private fun newWorkspace(): Pair<SessionWorkspace, File> {
		val dir = File(context.cacheDir, "download-it-${System.nanoTime()}").apply { mkdirs() }
		return SessionWorkspace.open(dir) to dir
	}

	@Test
	fun aPublicHttpsFileIsFetchedAndSavedUnderDownloads() = runBlocking {
		val (workspace, root) = newWorkspace()
		val fetched = HttpDownloadFetcher().fetch("https://example.com/")
		try {
			val result = WorkspaceDownloadSink(workspace)
				.save(fetched.fileName, 1024 * 1024, fetched.body)
			assertTrue("save failed: $result", result is DownloadSaveResult.Saved)
			val saved = result as DownloadSaveResult.Saved
			assertTrue(saved.relativePath, saved.relativePath.startsWith("downloads/"))
			assertTrue("expected bytes from example.com", saved.bytes > 0)
			val file = File(root, saved.relativePath)
			assertTrue("the file must exist on disk", file.isFile)
			assertTrue(
				"expected an HTML body, got ${file.length()} bytes",
				file.readText().contains("Example Domain"),
			)
		} finally {
			fetched.close()
		}
	}

	@Test
	fun aLoopbackUrlIsRefusedBeforeTheRequest() = runBlocking {
		val refused = runCatching {
			HttpDownloadFetcher().fetch("https://127.0.0.1/secret")
		}
		assertTrue("loopback must be refused", refused.isFailure)
		val error = refused.exceptionOrNull()
		assertTrue(
			"expected a local validation refusal, got $error",
			error is IllegalArgumentException,
		)
	}
}
