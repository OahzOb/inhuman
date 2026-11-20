package com.inhuman.ui.chat

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Where the system camera writes a photo taken for the composer.
 *
 * ## Why the cache and a FileProvider, not MediaStore
 *
 * The photo becomes a message attachment; it is not a gallery import. Writing
 * it to shared media would put every picture a user takes for a question into
 * their gallery, and MediaStore would leave the file behind even when the
 * message is never sent. The cache copy is app-private, needs no storage
 * permission, and is deleted as soon as [com.inhuman.ui.chat.ChatViewModel]
 * has prepared and copied it into the conversation workspace. The unchanged
 * original therefore does not survive; a picked file's source is likewise left
 * where it was, never moved into the workspace.
 *
 * The camera app is a separate process and can only write through the URI this
 * provider grants it, so the manifest declares a dedicated `FileProvider`
 * authority (`<applicationId>.capture`) over exactly this directory: exposing
 * the diagnostics provider instead would make "take a photo" a grant over the
 * log directory too.
 */
internal object CameraCapture {

	/** What the camera writes into the supplied URI, by convention. */
	const val MIME_TYPE = "image/jpeg"

	/** The name an imported capture gets in the workspace. */
	const val DISPLAY_NAME = "photo.jpg"

	/**
	 * A capture older than this was abandoned by a process death or a broken
	 * camera app. New captures are swept at launch so the cache cannot grow by
	 * one full-resolution photo per crash.
	 */
	private const val STALE_AFTER_MS = 24L * 60 * 60 * 1000

	/** Must match `android:authorities` in AndroidManifest.xml. */
	private fun authority(context: Context): String = "${context.packageName}.capture"

	private fun directory(context: Context): File =
		File(context.cacheDir, "camera").apply { mkdirs() }

	/**
	 * Create the empty file the camera will write into.
	 *
	 * A unique name, rather than a fixed `capture.jpg`: the previous result can
	 * still be open in the attach coroutine when a second capture starts, and
	 * overwriting it would truncate the image being prepared. The name is
	 * returned so the caller can save it across process death -- the camera
	 * activity routinely outlives this process, and the result is then
	 * delivered to a recreated launcher that has no `remember` left.
	 */
	fun newFile(context: Context): File {
		val dir = directory(context)
		val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
		dir.listFiles()?.forEach { stale ->
			if (stale.isFile && stale.lastModified() < cutoff) stale.delete()
		}
		val file = File(dir, "capture-${UUID.randomUUID()}.jpg")
		check(file.createNewFile()) { "capture file already exists" }
		return file
	}

	/** The file behind a saved [newFile] name, after process death included. */
	fun file(context: Context, name: String): File = File(directory(context), name)

	/** The write grant handed to the camera app. */
	fun uri(context: Context, file: File): Uri =
		FileProvider.getUriForFile(context, authority(context), file)
}

/**
 * The capture directory's provider.
 *
 * This is a distinct class rather than a second `androidx.core.content.FileProvider`
 * declaration, and that is load-bearing: PackageManager registers a provider by
 * component name, so a second `<provider>` with the same class is dropped with
 * "already defined". The surviving diagnostics instance then serves the capture
 * authority and rejects the camera's grant with "The authority
 * com.inhuman.capture does not match the one of the contentProvider:
 * com.inhuman.diagnostics" -- observed on a tablet before this class existed,
 * with the camera app crashing and no attachment staged. The manifest keeps
 * `capture_paths` as this class's meta-data because `FileProvider.getUriForFile`
 * always reads the paths from that meta-data.
 */
class CaptureFileProvider : FileProvider()
