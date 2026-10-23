package com.inhuman.core.ai.ssh

/** One completed transfer: how many bytes moved, and the host key that was used. */
data class SshFileResult(
	val bytes: Long,
	val hostFingerprint: String,
)

/** The bytes of one remote file, already read into memory. */
class SshDownloadedFile(
	val bytes: ByteArray,
	val hostFingerprint: String,
)

/** The kind of a remote directory entry, as SFTP reports it. */
enum class SshEntryType { FILE, DIRECTORY, SYMLINK, OTHER }

/** One entry from a remote directory listing. */
data class SshRemoteEntry(
	val name: String,
	val type: SshEntryType,
	val size: Long,
	/** Seconds since the epoch, or null when the server did not report one. */
	val modifiedAt: Long?,
)

/**
 * The file-transfer seam, separate from [SshTransport].
 *
 * Kept separate rather than added to [SshTransport] because the exec fake in
 * the tool tests has no business implementing SFTP, and because a tool that
 * transfers files should not be able to run a command by accident. [SshClient]
 * implements both; anything that only executes can keep the smaller interface.
 */
interface SshFileTransport {

	/**
	 * Write [content] to [remotePath].
	 *
	 * @param overwrite when false, an existing remote file is an error. The
	 *   remote side is not a copy: replacing it cannot be undone by deleting the
	 *   conversation.
	 */
	suspend fun upload(
		host: String,
		port: Int,
		username: String,
		remotePath: String,
		content: ByteArray,
		auth: SshAuth,
		hostKeyFingerprint: String? = null,
		overwrite: Boolean = false,
		connectTimeoutMs: Int = SshTransport.DEFAULT_CONNECT_TIMEOUT_MS,
	): SshFileResult

	/**
	 * Read [remotePath] into memory.
	 *
	 * @param maxBytes the largest file this call will accept. Checked against
	 *   the remote size before reading so a huge file does not have to arrive
	 *   first in order to be refused.
	 */
	suspend fun download(
		host: String,
		port: Int,
		username: String,
		remotePath: String,
		auth: SshAuth,
		hostKeyFingerprint: String? = null,
		maxBytes: Long,
		connectTimeoutMs: Int = SshTransport.DEFAULT_CONNECT_TIMEOUT_MS,
	): SshDownloadedFile

	/**
	 * List one remote directory.
	 *
	 * No recursion and no shell: this is SFTP `ls`, not `find`. The cap is
	 * applied after the server returns the listing, because the SFTP protocol
	 * has no streaming readdir; a directory with millions of entries is still
	 * one server response and the caller's cap cannot prevent it arriving.
	 */
	suspend fun list(
		host: String,
		port: Int,
		username: String,
		remotePath: String,
		auth: SshAuth,
		hostKeyFingerprint: String? = null,
		maxEntries: Int = 500,
		connectTimeoutMs: Int = SshTransport.DEFAULT_CONNECT_TIMEOUT_MS,
	): List<SshRemoteEntry> =
		throw UnsupportedOperationException("listing is not supported by this transport")
}
