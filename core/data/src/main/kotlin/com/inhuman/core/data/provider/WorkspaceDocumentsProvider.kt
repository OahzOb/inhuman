package com.inhuman.core.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.db.Database
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.files.SessionWorkspace
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * Exposes every conversation's workspace to the system Files app.
 *
 * ## Why this exists at all
 *
 * A workspace under `filesDir` is invisible to the user. That is the price of
 * storing it there, and it is the right price: `filesDir` is a real POSIX
 * filesystem needing no permission, while `/sdcard` is FUSE (no symlinks, no
 * permission bits) and `Android/data` has been hidden from third-party file
 * managers since Android 11.
 *
 * A `DocumentsProvider` is the **only** way to make app-private files browsable
 * without root and without `MANAGE_EXTERNAL_STORAGE`, which Play gates behind
 * justifications a chat app cannot honestly make. This is the mechanism behind
 * the "disk" that appears in the Files app for apps that offer one.
 *
 * ## Why it lives in `core:data` rather than `core:files`
 *
 * It needs both the sandbox and the conversation table. `core:data` already
 * depends on `core:files`, so putting it here keeps the dependency graph a line
 * rather than a cycle.
 *
 * ## The constraint that shapes the code
 *
 * Every method runs on a **Binder thread pool thread**, and DocumentsUI calls
 * them synchronously while drawing. Doing unbounded work here does not merely
 * make this app slow -- it hangs the *file manager*. So listings are capped,
 * and `runBlocking` appears only around queries that are indexed lookups.
 *
 * ## Document ids
 *
 * Deliberately not real paths. A path baked into a document id leaks the app's
 * private layout into a URI other apps can hold, and breaks the moment a
 * directory moves. The scheme is `root`, then `<workspaceId>`, then relative
 * segments.
 */
class WorkspaceDocumentsProvider : DocumentsProvider() {

	private val db: InhumanDatabase by lazy { Database.open(ctx()) }

	// --- roots ---------------------------------------------------------------

	override fun queryRoots(projection: Array<out String>?): Cursor {
		val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
		cursor.newRow().apply {
			add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
			add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
			add(DocumentsContract.Root.COLUMN_TITLE, ROOT_TITLE)
			add(DocumentsContract.Root.COLUMN_SUMMARY, "One folder per conversation")
			// LOCAL_ONLY is required for the root to appear at all: without it
			// DocumentsUI treats this as a network document and hides it.
			add(
				DocumentsContract.Root.COLUMN_FLAGS,
				DocumentsContract.Root.FLAG_LOCAL_ONLY or
					DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
					DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
			)
			add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_agenda)
			add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
			add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, null)
		}
		return cursor
	}

	override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
		val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
		includeDocument(cursor, documentId)
		return cursor
	}

	override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
		documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

	// --- listing -------------------------------------------------------------

	/**
	 * The abstract overload: `(String, Array<String>, String sortOrder)`.
	 *
	 * Note which overload is which. The abstract one takes a **String** sort
	 * order; the `Bundle` one is the query-arguments variant and is not
	 * abstract. Overriding the `Bundle` signature with a `CancellationSignal`
	 * parameter compiles to "overrides nothing", because no such method exists.
	 */
	override fun queryChildDocuments(
		parentDocumentId: String,
		projection: Array<out String>?,
		sortOrder: String?,
	): Cursor = buildChildren(parentDocumentId, projection)

	private fun buildChildren(parentDocumentId: String, projection: Array<out String>?): Cursor {
		val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

		// The root's children are the conversations, so a browsing user sees
		// folders named after conversations rather than opaque identifiers.
		if (parentDocumentId == ROOT_ID) {
			val conversations = runBlocking { db.conversations().all() }
			conversations.take(MAX_CHILDREN).forEach { includeConversation(cursor, it) }
			return cursor
		}

		val dir = resolveDirectory(parentDocumentId) ?: return cursor
		val children = dir.listFiles().orEmpty()
			.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })

		children.take(MAX_CHILDREN).forEach { child ->
			includeFile(cursor, "$parentDocumentId/${child.name}", child)
		}
		return cursor
	}

	// --- opening and mutating ------------------------------------------------

	override fun openDocument(
		documentId: String,
		mode: String,
		cancellationSignal: CancellationSignal?,
	): ParcelFileDescriptor {
		val file = resolveFile(documentId)
			?: throw FileNotFoundException("no such document: $documentId")
		return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
	}

	override fun createDocument(
		parentDocumentId: String,
		mimeType: String,
		displayName: String,
	): String {
		val parent = resolveDirectory(parentDocumentId)
			?: throw FileNotFoundException("no such directory: $parentDocumentId")

		// A display name is attacker-controlled here: any app can propose one.
		// Separators are stripped rather than trusted not to be present.
		val safeName = displayName.replace('/', '_').replace('\\', '_')
		File(parent, safeName).createNewFile()
		notifyChange(parentDocumentId)
		return "$parentDocumentId/$safeName"
	}

	override fun deleteDocument(documentId: String) {
		val file = resolveFile(documentId) ?: throw FileNotFoundException(documentId)
		if (!file.deleteRecursively()) throw FileNotFoundException("could not delete: $documentId")
		notifyChange(parentOf(documentId))
	}

	override fun renameDocument(documentId: String, displayName: String): String {
		val file = resolveFile(documentId) ?: throw FileNotFoundException(documentId)
		val safeName = displayName.replace('/', '_').replace('\\', '_')
		val target = File(file.parentFile, safeName)
		if (!file.renameTo(target)) throw FileNotFoundException("could not rename: $documentId")
		notifyChange(parentOf(documentId))
		return "${parentOf(documentId)}/$safeName"
	}

	override fun getDocumentType(documentId: String): String {
		if (documentId == ROOT_ID) return DocumentsContract.Document.MIME_TYPE_DIR
		val file = resolveFile(documentId) ?: return MIME_FALLBACK
		return mimeOf(file)
	}


	// --- rows ----------------------------------------------------------------

	private fun includeConversation(cursor: MatrixCursor, conversation: ConversationEntity) {
		// A conversation restored from a backup may have no directory yet.
		val dir = workspaceDir(conversation.workspaceId).apply { mkdirs() }
		cursor.newRow().apply {
			add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, conversation.workspaceId)
			// The generated title, so folder names match what the app shows.
			add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, conversation.title)
			add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
			add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, conversation.updatedAt)
			add(DocumentsContract.Document.COLUMN_SIZE, dir.length())
			add(DocumentsContract.Document.COLUMN_FLAGS, directoryFlags())
		}
	}

	private fun includeDocument(cursor: MatrixCursor, documentId: String) {
		if (documentId == ROOT_ID) {
			cursor.newRow().apply {
				add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_ID)
				add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, ROOT_TITLE)
				add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
				add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, null)
				add(DocumentsContract.Document.COLUMN_SIZE, null)
				add(DocumentsContract.Document.COLUMN_FLAGS, directoryFlags())
			}
			return
		}

		// A bare workspace id is a conversation, and therefore a directory.
		if (!documentId.contains('/')) {
			val conversation = runBlocking { db.conversations().byId(documentId) }
			if (conversation != null) {
				includeConversation(cursor, conversation)
				return
			}
		}

		val file = resolveFile(documentId) ?: throw FileNotFoundException(documentId)
		includeFile(cursor, documentId, file)
	}

	private fun includeFile(cursor: MatrixCursor, documentId: String, file: File) {
		cursor.newRow().apply {
			add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
			add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
			add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeOf(file))
			add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
			add(DocumentsContract.Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
			add(
				DocumentsContract.Document.COLUMN_FLAGS,
				if (file.isDirectory) directoryFlags() else fileFlags(),
			)
		}
	}

	private fun directoryFlags(): Int =
		DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
			DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
			DocumentsContract.Document.FLAG_SUPPORTS_RENAME

	private fun fileFlags(): Int =
		DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
			DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
			DocumentsContract.Document.FLAG_SUPPORTS_RENAME

	// --- id <-> file ---------------------------------------------------------

	private fun workspaceDir(workspaceId: String): File =
		File(File(ctx().filesDir, "conversations"), workspaceId)

	/**
	 * Turn a document id into a real file, refusing anything that escapes.
	 *
	 * Routed through [SessionWorkspace] rather than joining strings, so this
	 * provider inherits exactly the confinement the model's tools get. A
	 * provider resolving ids on its own would be a second, weaker door into the
	 * same files -- the kind of gap that surfaces later as a security bug.
	 */
	private fun resolveFile(documentId: String): File? {
		if (documentId == ROOT_ID) return null

		val parts = documentId.split('/')
		val workspaceId = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
		if (parts.size <= 1) return workspaceDir(workspaceId).takeIf { it.isDirectory }

		val relative = parts.drop(1).joinToString("/")
		val workspace = SessionWorkspace.open(workspaceDir(workspaceId))
		return when (val r = workspace.resolve(relative)) {
			is SessionWorkspace.Resolution.Ok -> r.file
			is SessionWorkspace.Resolution.Rejected -> null
		}
	}

	private fun resolveDirectory(documentId: String): File? =
		resolveFile(documentId)?.takeIf { it.isDirectory }

	private fun parentOf(documentId: String): String {
		val idx = documentId.lastIndexOf('/')
		return if (idx < 0) ROOT_ID else documentId.substring(0, idx)
	}


	/**
	 * Tell the file manager that a directory changed.
	 *
	 * `DocumentsProvider` has no `notifyChange(String)`; notification goes
	 * through the resolver against the children URI. Without it DocumentsUI
	 * keeps showing a stale listing after a create, rename or delete, which
	 * looks like the operation failed.
	 */
	private fun notifyChange(parentDocumentId: String) {
		val uri = DocumentsContract.buildChildDocumentsUri(authority(), parentDocumentId)
		ctx().contentResolver.notifyChange(uri, null)
	}

	/**
	 * The context, captured once in [onCreate].
	 *
	 * Not `getContext()`: it is not reachable from a companion object, and the
	 * inherited `context` property collides with the Kotlin stdlib's
	 * `context(...)` at any call site that also has a lambda in scope. A field
	 * of our own avoids both.
	 */
	private var appContext: android.content.Context? = null

	override fun onCreate(): Boolean {
		appContext = context
		return true
	}

	private fun ctx(): android.content.Context =
		appContext ?: error("provider used before onCreate")

	/** `"<applicationId>.documents"`, matching the manifest declaration. */
	private fun authority(): String = "${ctx().packageName}.documents"

	private fun mimeOf(file: File): String {
		if (file.isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
		return MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
			// Extension-only, and therefore a display hint rather than a trust
			// decision: the model can write a file called invoice.pdf containing
			// anything at all. Nothing in the app auto-opens what this reports.
			?: MIME_FALLBACK
	}

	private companion object {
		const val ROOT_ID = "root"
		const val ROOT_TITLE = "inhuman"
		const val MIME_FALLBACK = "application/octet-stream"

		/**
		 * Most entries returned in one listing.
		 *
		 * A directory after a clone or a package install can hold tens of
		 * thousands of files, and this runs on a Binder thread while the file
		 * manager waits to draw. An unbounded listing hangs another app.
		 */
		const val MAX_CHILDREN = 500

		val DEFAULT_ROOT_PROJECTION = arrayOf(
			DocumentsContract.Root.COLUMN_ROOT_ID,
			DocumentsContract.Root.COLUMN_DOCUMENT_ID,
			DocumentsContract.Root.COLUMN_TITLE,
			DocumentsContract.Root.COLUMN_SUMMARY,
			DocumentsContract.Root.COLUMN_FLAGS,
			DocumentsContract.Root.COLUMN_ICON,
			DocumentsContract.Root.COLUMN_MIME_TYPES,
			DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
		)

		val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
			DocumentsContract.Document.COLUMN_DOCUMENT_ID,
			DocumentsContract.Document.COLUMN_DISPLAY_NAME,
			DocumentsContract.Document.COLUMN_MIME_TYPE,
			DocumentsContract.Document.COLUMN_LAST_MODIFIED,
			DocumentsContract.Document.COLUMN_SIZE,
			DocumentsContract.Document.COLUMN_FLAGS,
		)
	}
}
