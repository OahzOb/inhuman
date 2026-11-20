package com.inhuman.ui.chat

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * The cache file handed to the system share sheet for one conversation.
 *
 * Each share gets its own directory: the chooser returns immediately, but the
 * receiving app reads the URI afterwards, and a second share must not
 * overwrite the file the first one is still reading. Old directories are swept
 * on the next share; the cache is not a transcript store.
 */
internal object ConversationShare {

    /** A shared file older than this has been read or abandoned. */
    private const val STALE_AFTER_MS = 24L * 60 * 60 * 1000

    private fun directory(context: Context): File =
        File(context.cacheDir, "share").apply { mkdirs() }

    /**
     * A fresh path for [name]. The file is not created here; the caller writes
     * the Markdown first, then asks for its [uri].
     */
    fun newFile(context: Context, name: String): File {
        val root = directory(context)
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        root.listFiles()?.forEach { stale ->
            if (stale.isDirectory && stale.lastModified() < cutoff) stale.deleteRecursively()
        }
        val slot = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        return File(slot, name)
    }

    /** The read grant handed to the app the user picks in the share sheet. */
    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.share", file)
}

/**
 * The share directory's provider.
 *
 * A distinct class again: PackageManager keys providers by component name, so
 * a second `androidx.core.content.FileProvider` declaration would be dropped
 * with "already defined" (the camera provider hit that; see docs/07 B36).
 * Keeping the exported conversation text out of the diagnostics directory is
 * the other reason this is its own authority.
 */
class ShareFileProvider : FileProvider()
