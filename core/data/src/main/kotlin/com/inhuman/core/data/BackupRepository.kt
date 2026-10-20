package com.inhuman.core.data

import android.content.Context
import android.net.Uri
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.db.Database
import com.inhuman.core.data.db.InhumanDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Explicit, encrypted backup of the whole app-private dataset.
 *
 * ## What is in it
 *
 * The SQLite database (snapshotted with `VACUUM INTO`, not by copying a live
 * file with its WAL), every workspace directory, and a small manifest. Provider
 * API keys are **not** included: they live in the Keystore-backed SecureStore,
 * are not portable by design, and a backup file is exactly the wrong place to
 * make them portable.
 *
 * Rollback snapshots are also omitted. They are derived, can be large, and
 * restoring a database whose snapshot directories refer to old local files would
 * disable rewind anyway. The import clears the current snapshot directory so a
 * restored conversation is never offered a rewind to files that are not here.
 *
 * ## Why import is staged and needs a restart
 *
 * Replacing a live Room database under an open connection is a data-loss bug
 * waiting for a slow disk. Decryption and validation happen into a staging
 * directory while the app runs; the swap happens in `InhumanApp.onCreate` on the
 * next launch, before Room is opened. The UI says so explicitly.
 *
 * ## Format
 *
 * A header (`INHUMAN_BACKUP_1\n`), 16-byte salt, 12-byte IV, then AES-256-GCM
 * ciphertext of a ZIP. PBKDF2-HMAC-SHA256, 210,000 iterations. The ZIP is created
 * and encrypted as a stream; the passphrase never touches disk.
 */
class BackupRepository(
	context: Context,
	private val db: InhumanDatabase = Database.open(context),
) {

	private val appContext: Context = context.applicationContext

	/** Write an encrypted backup to a SAF document. */
	suspend fun export(uri: Uri, passphrase: CharArray) = withContext(Dispatchers.IO) {
		val work = File(appContext.cacheDir, "backup-${System.currentTimeMillis()}")
		work.mkdirs()
		try {
			val snapshot = File(work, "inhuman.db")
			// VACUUM INTO produces a standalone, checkpointed database. Copying
			// inhuman.db directly can miss committed frames still in the WAL.
			db.openHelper.writableDatabase.execSQL(
				"VACUUM INTO '${snapshot.absolutePath.replace("'", "''")}'",
			)
			val zip = File(work, "backup.zip")
			ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { out ->
				out.putNextEntry(ZipEntry("inhuman.db"))
				FileInputStream(snapshot).use { it.copyTo(out) }
				out.closeEntry()
				out.putNextEntry(ZipEntry("manifest.json"))
				val manifest = """
					{"format":1,"databaseVersion":${InhumanDatabase.VERSION},"createdAt":${System.currentTimeMillis()}}
				""".trimIndent().toByteArray()
				out.write(manifest)
				out.closeEntry()
				val root = Database.workspacesRoot(appContext)
				root.walkTopDown()
					.filter { it.isFile }
					.forEach { file ->
						val relative = file.relativeTo(root).invariantSeparatorsPath
						out.putNextEntry(ZipEntry("conversations/$relative"))
						FileInputStream(file).use { it.copyTo(out) }
						out.closeEntry()
					}
			}
			val encrypted = File(work, "backup.enc")
			encryptFile(zip, encrypted, passphrase)
			appContext.contentResolver.openOutputStream(uri, "w")?.use { target ->
				FileInputStream(encrypted).use { it.copyTo(target) }
			} ?: throw IOException("The selected document is not writable.")
			Diag.i("backup", "export bytes=${encrypted.length()}")
		} finally {
			work.deleteRecursively()
		}
	}

	/**
	 * Decrypt and validate a backup into the staging directory.
	 *
	 * The caller must restart (or otherwise kill the process) before
	 * [applyPendingRestore] runs.
	 */
	suspend fun stageImport(uri: Uri, passphrase: CharArray) = withContext(Dispatchers.IO) {
		val staging = stagingDir(appContext)
		if (staging.exists()) staging.deleteRecursively()
		staging.mkdirs()
		val zip = File(appContext.cacheDir, "restore-${System.currentTimeMillis()}.zip")
		try {
			decryptTo(uri, zip, passphrase)
			extract(zip, staging)
			val restored = File(staging, "inhuman.db")
			if (!isSqlite(restored)) {
				throw IOException("The backup does not contain a valid database.")
			}
			File(appContext.filesDir, PENDING_MARKER).writeText("1")
			Diag.i("backup", "import staged")
		} catch (failure: Exception) {
			staging.deleteRecursively()
			throw failure
		} finally {
			zip.delete()
		}
	}

	private fun encryptFile(source: File, target: File, passphrase: CharArray) {
		val salt = ByteArray(SALT_BYTES)
		val iv = ByteArray(IV_BYTES)
		SecureRandom().nextBytes(salt)
		SecureRandom().nextBytes(iv)
		BufferedOutputStream(FileOutputStream(target)).use { out ->
			out.write(MAGIC)
			out.write(salt)
			out.write(iv)
			val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
				init(
					Cipher.ENCRYPT_MODE,
					SecretKeySpec(derive(passphrase, salt), "AES"),
					GCMParameterSpec(128, iv),
				)
			}
			CipherOutputStream(out, cipher).use { encrypted ->
				FileInputStream(source).use { it.copyTo(encrypted) }
			}
		}
	}

	private fun decryptTo(uri: Uri, target: File, passphrase: CharArray) {
		appContext.contentResolver.openInputStream(uri)?.use { raw ->
			val input = DataInputStream(BufferedInputStream(raw))
			val magic = ByteArray(MAGIC.size)
			input.readFully(magic)
			if (!magic.contentEquals(MAGIC)) {
				throw IOException("This is not an inhuman backup.")
			}
			val salt = ByteArray(SALT_BYTES)
			val iv = ByteArray(IV_BYTES)
			input.readFully(salt)
			input.readFully(iv)
			val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
				init(
					Cipher.DECRYPT_MODE,
					SecretKeySpec(derive(passphrase, salt), "AES"),
					GCMParameterSpec(128, iv),
				)
			}
			CipherInputStream(input, cipher).use { decrypted ->
				BufferedOutputStream(FileOutputStream(target)).use { out -> decrypted.copyTo(out) }
			}
		} ?: throw IOException("The selected document is not readable.")
	}

	private fun extract(zip: File, staging: File) {
		val root = staging.canonicalFile
		ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { input ->
			while (true) {
				val entry = input.nextEntry ?: break
				val name = entry.name
				if (entry.isDirectory) continue
				if (name == "manifest.json") continue
				val allowed = name == "inhuman.db" || name.startsWith("conversations/")
				if (!allowed) throw IOException("Unexpected file in backup: $name")
				val output = File(staging, name).canonicalFile
				if (!output.path.startsWith(root.path + File.separator)) {
					throw IOException("Backup contains an unsafe path.")
				}
				output.parentFile?.mkdirs()
				BufferedOutputStream(FileOutputStream(output)).use { input.copyTo(it) }
			}
		}
	}

	private fun isSqlite(file: File): Boolean {
		if (!file.isFile) return false
		val header = ByteArray(16)
		FileInputStream(file).use { it.read(header) }
		return header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
	}

	private fun derive(passphrase: CharArray, salt: ByteArray): ByteArray {
		val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
		return try {
			SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
				.generateSecret(spec)
				.encoded
		} finally {
			spec.clearPassword()
		}
	}

	companion object {
		const val PENDING_MARKER = "backup-restore.pending"
		private const val STAGING = "backup-restore-staging"
		private val MAGIC = "INHUMAN_BACKUP_1\n".toByteArray(Charsets.US_ASCII)
		private const val SALT_BYTES = 16
		private const val IV_BYTES = 12
		private const val ITERATIONS = 210_000

		private fun stagingDir(context: Context) = File(context.filesDir, STAGING)

		/**
		 * Swap a staged restore into place. Called from `Application.onCreate`
		 * before Room is opened, never from a running screen.
		 */
		fun applyPendingRestore(context: Context) {
			val marker = File(context.filesDir, PENDING_MARKER)
			if (!marker.isFile) return
			val staging = stagingDir(context)
			val restored = File(staging, "inhuman.db")
			if (!restored.isFile) {
				Diag.w("backup", "restore staging is missing its database")
				staging.deleteRecursively()
				marker.delete()
				return
			}
			val files = context.filesDir
			val currentDb = File(files, InhumanDatabase.NAME)
			val oldDb = File(files, "${InhumanDatabase.NAME}.pre-restore")
			val currentWorkspaces = Database.workspacesRoot(context)
			val oldWorkspaces = File(files, "conversations.pre-restore")
			val nextDb = File(files, "${InhumanDatabase.NAME}.restore")
			val nextWorkspaces = File(files, "conversations.restore")
			var movedDb = false
			var movedWorkspaces = false
			var installedDb = false
			var installedWorkspaces = false
			try {
				// Copy first, then swap. A failure before the rename leaves the
				// running install untouched and the staging directory available
				// for another attempt.
				restored.copyTo(nextDb, overwrite = true)
				val restoredWorkspaces = File(staging, "conversations")
				nextWorkspaces.deleteRecursively()
				if (restoredWorkspaces.isDirectory) {
					restoredWorkspaces.copyRecursively(nextWorkspaces, overwrite = true)
				} else {
					nextWorkspaces.mkdirs()
				}
				File(currentDb.absolutePath + "-wal").delete()
				File(currentDb.absolutePath + "-shm").delete()
				oldDb.delete()
				oldWorkspaces.deleteRecursively()
				if (currentDb.exists()) {
					if (!currentDb.renameTo(oldDb)) {
						throw IOException("Could not preserve the current database")
					}
					movedDb = true
				}
				if (currentWorkspaces.exists()) {
					if (!currentWorkspaces.renameTo(oldWorkspaces)) {
						throw IOException("Could not preserve the current workspaces")
					}
					movedWorkspaces = true
				}
				if (!nextDb.renameTo(currentDb)) throw IOException("Could not install database")
				installedDb = true
				if (!nextWorkspaces.renameTo(currentWorkspaces)) {
					throw IOException("Could not install workspaces")
				}
				installedWorkspaces = true
				oldDb.delete()
				oldWorkspaces.deleteRecursively()
				// Snapshots point at a workspace state that no longer matches.
				Database.rollbackRoot(context).deleteRecursively()
				marker.delete()
				staging.deleteRecursively()
				Diag.i("backup", "restore applied")
			} catch (failure: Exception) {
				Diag.e("backup", "restore failed; current data kept", failure)
				if (installedDb) currentDb.delete()
				if (movedDb && oldDb.exists()) oldDb.renameTo(currentDb)
				if (installedWorkspaces) currentWorkspaces.deleteRecursively()
				if (movedWorkspaces && oldWorkspaces.exists()) {
					oldWorkspaces.renameTo(currentWorkspaces)
				}
				nextDb.delete()
				nextWorkspaces.deleteRecursively()
			}
		}
	}
}
