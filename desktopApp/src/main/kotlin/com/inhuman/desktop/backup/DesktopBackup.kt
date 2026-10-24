package com.inhuman.desktop.backup

import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.desktop.DesktopStore
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
 * The desktop half of the Android backup format.
 *
 * A backup is an AES-256-GCM encrypted ZIP of `VACUUM INTO` database snapshot
 * plus every workspace. API keys are deliberately absent: a backup file is the
 * wrong place to make a credential portable.
 *
 * The header and KDF parameters match `BackupRepository` on Android, so a
 * desktop backup is readable by the phone and vice versa.
 *
 * Import is staged and applied on the next launch. Replacing a live Room
 * database under an open connection is a data-loss bug; staging also means a
 * cancelled import cannot leave the current data half-replaced.
 */
object DesktopBackup {

	private val MAGIC = "INHUMAN_BACKUP_1\n".toByteArray(Charsets.US_ASCII)
	private const val SALT_BYTES = 16
	private const val IV_BYTES = 12
	private const val ITERATIONS = 210_000
	private const val STAGING = "restore-staging"
	private const val MARKER = "restore.pending"

	data class BackupInfo(
		val databaseVersion: Int,
		val createdAt: Long,
	)

	data class Outcome(
		val info: BackupInfo? = null,
		val error: String? = null,
	)

	/** Export a consistent database snapshot and the workspace tree. */
	suspend fun export(
		store: DesktopStore,
		dataDir: File,
		target: File,
		passphrase: CharArray,
	): Outcome = withContext(Dispatchers.IO) {
		val work = File.createTempFile("inhuman-backup-", ".work").apply { delete(); mkdirs() }
		try {
			val snapshot = File(work, "inhuman.db")
			store.snapshotDatabase(snapshot)
			val zip = File(work, "backup.zip")
			ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { out ->
				out.putNextEntry(ZipEntry("inhuman.db"))
				FileInputStream(snapshot).use { it.copyTo(out) }
				out.closeEntry()

				out.putNextEntry(ZipEntry("manifest.json"))
				val manifest = """{"format":1,"databaseVersion":${InhumanDatabase.VERSION},"createdAt":${System.currentTimeMillis()}}"""
				out.write(manifest.toByteArray(Charsets.UTF_8))
				out.closeEntry()

				val root = File(dataDir, "files/conversations")
				if (root.isDirectory) {
					root.walkTopDown().filter { it.isFile }.forEach { file ->
						val relative = file.relativeTo(root).invariantSeparatorsPath
						out.putNextEntry(ZipEntry("conversations/$relative"))
						FileInputStream(file).use { it.copyTo(out) }
						out.closeEntry()
					}
				}
			}
			target.parentFile?.mkdirs()
			encrypt(zip, target, passphrase)
			Diag.i("backup", "export bytes=${target.length()}")
			Outcome(
				info = BackupInfo(
					databaseVersion = InhumanDatabase.VERSION,
					createdAt = System.currentTimeMillis(),
				),
			)
		} catch (failure: Exception) {
			Diag.w("backup", "export failed", failure)
			Outcome(error = failure.message ?: failure::class.simpleName)
		} finally {
			work.deleteRecursively()
		}
	}

	/** Decrypt and validate just enough to describe the archive. */
	suspend fun inspect(backup: File, passphrase: CharArray): Outcome =
		withContext(Dispatchers.IO) {
			val work = File.createTempFile("inhuman-inspect-", ".work").apply { delete(); mkdirs() }
			try {
				val zip = File(work, "backup.zip")
				decrypt(backup, zip, passphrase)
				val info = readInfo(zip, work)
				Outcome(info = info)
			} catch (failure: Exception) {
				Outcome(error = failure.message ?: "This backup could not be read.")
			} finally {
				work.deleteRecursively()
			}
		}

	/**
	 * Decrypt and validate into the staging directory, then write the marker.
	 *
	 * Nothing the app is currently using is touched here. The swap happens in
	 * [applyPendingRestore] before Room opens.
	 */
	suspend fun stageRestore(backup: File, passphrase: CharArray, dataDir: File): Outcome =
		withContext(Dispatchers.IO) {
			val staging = File(dataDir, STAGING)
			val temporary = File.createTempFile("inhuman-restore-", ".zip")
			try {
				decrypt(backup, temporary, passphrase)
				staging.deleteRecursively()
				staging.mkdirs()
				val info = extract(temporary, staging)
				val database = File(staging, "inhuman.db")
				if (!isSqlite(database)) {
					throw IOException("The backup does not contain a valid database.")
				}
				File(dataDir, MARKER).writeText(
					"""{"databaseVersion":${info.databaseVersion},"createdAt":${info.createdAt}}""",
				)
				Diag.i("backup", "restore staged version=${info.databaseVersion}")
				Outcome(info = info)
			} catch (failure: Exception) {
				staging.deleteRecursively()
				File(dataDir, MARKER).delete()
				Diag.w("backup", "restore staging failed", failure)
				Outcome(error = failure.message ?: "The backup could not be staged.")
			} finally {
				temporary.delete()
			}
		}

	/**
	 * Swap a staged restore into place. Call before opening the database.
	 *
	 * Returns true when a restore was applied. A failure attempts to put the
	 * previous files back; the marker stays so the operation can be retried
	 * rather than silently discarded.
	 */
	fun applyPendingRestore(dataDir: File): Boolean {
		val marker = File(dataDir, MARKER)
		val staging = File(dataDir, STAGING)
		if (!marker.isFile || !staging.isDirectory) return false
		val previous = File(dataDir, "restore-old")
		previous.deleteRecursively()
		previous.mkdirs()
		val database = File(dataDir, InhumanDatabase.NAME)
		val files = File(dataDir, "files")
		val rollback = File(files, "rollback")
		try {
			// Move the current data aside first. If anything below fails, the
			// catch puts it back and the app can still start.
			for (suffix in listOf("", "-wal", "-shm", "-lck")) {
				val current = File(dataDir, InhumanDatabase.NAME + suffix)
				if (current.exists()) current.renameTo(File(previous, current.name))
			}
			if (files.exists()) files.renameTo(File(previous, "files"))
			files.mkdirs()
			if (!File(staging, "inhuman.db").renameTo(database)) {
				throw IOException("Could not move the restored database into place.")
			}
			val stagedConversations = File(staging, "conversations")
			if (stagedConversations.isDirectory) {
				stagedConversations.renameTo(File(files, "conversations"))
			}
			rollback.deleteRecursively()
			staging.deleteRecursively()
			previous.deleteRecursively()
			marker.delete()
			Diag.i("backup", "restore applied")
			return true
		} catch (failure: Exception) {
			Diag.e("backup", "restore apply failed; rolling back", failure)
			runCatching {
				database.delete()
				files.deleteRecursively()
				files.mkdirs()
				for (suffix in listOf("", "-wal", "-shm", "-lck")) {
					val saved = File(previous, InhumanDatabase.NAME + suffix)
					if (saved.exists()) saved.renameTo(File(dataDir, saved.name))
				}
				val savedFiles = File(previous, "files")
				if (savedFiles.exists()) savedFiles.renameTo(files)
			}
			return false
		}
	}

	private fun readInfo(zip: File, work: File): BackupInfo {
		var manifest: String? = null
		ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { input ->
			while (true) {
				val entry = input.nextEntry ?: break
				if (entry.isDirectory) continue
				if (entry.name == "manifest.json") {
					manifest = input.readBytes().toString(Charsets.UTF_8)
				}
			}
		}
		val text = manifest ?: throw IOException("The backup has no manifest.")
		val version = Regex("\"databaseVersion\"\\s*:\\s*(\\d+)")
			.find(text)?.groupValues?.get(1)?.toIntOrNull()
			?: throw IOException("The backup manifest has no database version.")
		val createdAt = Regex("\"createdAt\"\\s*:\\s*(\\d+)")
			.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
		return BackupInfo(version, createdAt)
	}

	private fun extract(zip: File, staging: File): BackupInfo {
		val root = staging.canonicalFile
		var version: Int? = null
		var createdAt = 0L
		ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { input ->
			while (true) {
				val entry = input.nextEntry ?: break
				if (entry.isDirectory) continue
				val name = entry.name
				if (name == "manifest.json") {
					val text = input.readBytes().toString(Charsets.UTF_8)
					version = Regex("\"databaseVersion\"\\s*:\\s*(\\d+)")
						.find(text)?.groupValues?.get(1)?.toIntOrNull()
					createdAt = Regex("\"createdAt\"\\s*:\\s*(\\d+)")
						.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
					continue
				}
				val allowed = name == "inhuman.db" || name.startsWith("conversations/")
				if (!allowed) throw IOException("Unexpected file in backup: $name")
				val output = File(staging, name).canonicalFile
				if (!output.path.startsWith(root.path + File.separator)) {
					throw IOException("Backup contains an unsafe path.")
				}
				output.parentFile?.mkdirs()
				BufferedOutputStream(FileOutputStream(output)).use { target ->
					input.copyTo(target)
				}
			}
		}
		return BackupInfo(
			databaseVersion = version ?: throw IOException("The backup manifest has no database version."),
			createdAt = createdAt,
		)
	}

	private fun encrypt(source: File, target: File, passphrase: CharArray) {
		val salt = ByteArray(SALT_BYTES)
		val iv = ByteArray(IV_BYTES)
		SecureRandom().nextBytes(salt)
		SecureRandom().nextBytes(iv)
		val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
			init(
				Cipher.ENCRYPT_MODE,
				SecretKeySpec(derive(passphrase, salt), "AES"),
				GCMParameterSpec(128, iv),
			)
		}
		BufferedOutputStream(FileOutputStream(target)).use { out ->
			out.write(MAGIC)
			out.write(salt)
			out.write(iv)
			CipherOutputStream(out, cipher).use { encrypted ->
				FileInputStream(source).use { it.copyTo(encrypted) }
			}
		}
	}

	private fun decrypt(source: File, target: File, passphrase: CharArray) {
		DataInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
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
		}
	}

	private fun derive(passphrase: CharArray, salt: ByteArray): ByteArray {
		val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
		return try {
			SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
		} finally {
			spec.clearPassword()
		}
	}

	private fun isSqlite(file: File): Boolean {
		if (!file.isFile) return false
		val header = ByteArray(16)
		FileInputStream(file).use { it.read(header) }
		return header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
	}
}
