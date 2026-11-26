package com.inhuman.core.engine.roleplay

import com.inhuman.core.files.SessionWorkspace
import java.io.File

/** The two file names a story folder uses. */
object RoleplayStoryFiles {
	const val CARD = "card.json"
	const val SCENE = "scene.json"
}

/** One sub-folder offered by the story-folder browser. */
data class StoryFolder(
	/** Workspace-relative path, `/`-separated. */
	val path: String,
	val name: String,
	/** Whether this folder directly contains a `card.json` (i.e. can be a story). */
	val hasCard: Boolean,
)

/**
 * A story read from one workspace folder.
 *
 * [cardJson] is kept verbatim rather than re-encoded: the scene's persona
 * snapshot stores it as text so an unknown field from a newer release survives
 * the copy. [sceneJson] is the normalized scene to store on the conversation
 * (`"{}"` for a story with no scene), while [scene] is the decoded value for
 * callers that need the fields.
 */
data class RoleplayStory(
	val folder: String,
	val cardJson: String,
	val card: RoleplayCard,
	val scene: RoleplayScene?,
	val sceneJson: String,
	val warnings: List<RoleplayValidator.Issue>,
)

/** What reading a story folder produced. */
sealed interface StoryOutcome {
	data class Ok(val story: RoleplayStory) : StoryOutcome

	/**
	 * The folder exists but its files cannot be used: missing card, malformed
	 * JSON or an exceeded limit. [issues] is the same list the `rp_validate`
	 * tool would show, so the dialog and the model agree.
	 */
	data class Invalid(val issues: List<RoleplayValidator.Issue>) : StoryOutcome

	/** The folder itself could not be read (missing, outside the workspace, ...). */
	data class Failed(val reason: String) : StoryOutcome
}

/** What listing one folder produced. */
sealed interface StoryListing {
	data class Ok(
		val path: String,
		val entries: List<StoryFolder>,
		/** True when the folder list was capped by the workspace limit. */
		val truncated: Boolean,
		/** Whether the folder being listed is itself a story. */
		val hasCardHere: Boolean,
	) : StoryListing

	data class Failed(val reason: String) : StoryListing
}

/**
 * Read role-play material from a workspace folder.
 *
 * This is the user-facing counterpart of `rp_validate`: the same
 * [RoleplayValidator] and [RoleplayCodec] decide whether a folder can start a
 * scene, so a story the assistant built and checked with `rp_validate` is
 * accepted by the dialog, and a folder the dialog refuses is refused for the
 * same printed reason. It runs next to the engine rather than in the Android
 * layer because it is plain JVM file access through the one path gate,
 * [SessionWorkspace.resolve], and is tested there.
 *
 * Reading is bounded by `WorkspaceLimits.maxReadBytes`; a card is prompt
 * content and is additionally bounded by [RoleplayLimits.MAX_CARD_CHARS], which
 * the validator reports as an ordinary issue.
 */
object RoleplayStoryLoader {

	/**
	 * Load [folder] as a story.
	 *
	 * `card.json` is required; `scene.json` is optional. A file that exists but
	 * is empty/invalid is an invalid story, not "no card": silently starting
	 * with a different character than the folder describes would be worse than
	 * telling the user which file to fix.
	 */
	fun load(workspace: SessionWorkspace, folder: String): StoryOutcome {
		val cleanFolder = folder.trim().trim('/')
		val cardFile = when (val resolved = resolveFile(workspace, cleanFolder, RoleplayStoryFiles.CARD)) {
			is FileResult.Ok -> resolved.file
			is FileResult.Failed -> return StoryOutcome.Failed(resolved.reason)
		}
		val cardRaw = when (val read = read(workspace, cardFile)) {
			is ReadResult.Ok -> read.text
			is ReadResult.Failed -> return StoryOutcome.Failed(read.reason)
		}
		val cardResult = RoleplayValidator.validateCard(cardRaw)
		if (!cardResult.ok) return StoryOutcome.Invalid(cardResult.issues)
		val card = RoleplayCodec.decodeCard(cardRaw)
			?: return StoryOutcome.Invalid(
				cardResult.issues + RoleplayValidator.Issue(
					"",
					"no field is set; this file would be read as 'not a roleplay card'",
				),
			)

		val warnings = cardResult.issues.filter { it.warning }.toMutableList()
		var scene: RoleplayScene? = null
		when (val resolved = resolveOptionalFile(workspace, cleanFolder, RoleplayStoryFiles.SCENE)) {
			is OptionalFile.Failed -> return StoryOutcome.Failed(resolved.reason)
			OptionalFile.Absent -> Unit
			is OptionalFile.Present -> {
				val sceneRaw = when (val read = read(workspace, resolved.file)) {
					is ReadResult.Ok -> read.text
					is ReadResult.Failed -> return StoryOutcome.Failed(read.reason)
				}
				val sceneResult = RoleplayValidator.validateScene(sceneRaw)
				if (!sceneResult.ok) return StoryOutcome.Invalid(sceneResult.issues)
				warnings += sceneResult.issues.filter { it.warning }
				scene = RoleplayCodec.decodeScene(sceneRaw)
			}
		}
		return StoryOutcome.Ok(
			RoleplayStory(
				folder = cleanFolder,
				cardJson = cardRaw,
				card = card,
				scene = scene,
				sceneJson = RoleplayCodec.encodeScene(scene ?: RoleplayScene()).ifEmpty { "{}" },
				warnings = warnings,
			),
		)
	}

	/**
	 * List the sub-folders of [path], for the dialog's browser.
	 *
	 * The workspace root is the starting path. Only directories are returned:
	 * a story is a folder, and offering files would be a way to select a
	 * `card.json` that is not the folder's card. The listing is capped by the
	 * workspace's own `maxListEntries`, and a truncated list says so instead of
	 * quietly hiding folders.
	 */
	fun list(workspace: SessionWorkspace, path: String): StoryListing {
		val cleanPath = path.trim().trim('/')
		val dir = when (val resolved = workspace.resolve(cleanPath)) {
			is SessionWorkspace.Resolution.Ok -> resolved.file
			is SessionWorkspace.Resolution.Rejected -> return StoryListing.Failed(resolved.reason)
		}
		if (!dir.exists()) return StoryListing.Failed("no such folder: ${displayPath(cleanPath)}")
		if (!dir.isDirectory) {
			return StoryListing.Failed("${displayPath(cleanPath)} is not a folder")
		}

		val all = runCatching { dir.listFiles().orEmpty() }
			.getOrElse { failure -> return StoryListing.Failed("could not list the folder: ${failure.message}") }
			.filter { it.isDirectory }
			.sortedBy { it.name.lowercase() }
		val cap = workspace.limits.maxListEntries
		val entries = all.take(cap).map { child ->
			val childPath = displayPath(
				if (cleanPath.isEmpty()) child.name else "$cleanPath/${child.name}",
			)
			StoryFolder(
				path = childPath,
				name = child.name,
				hasCard = File(child, RoleplayStoryFiles.CARD).isFile,
			)
		}
		return StoryListing.Ok(
			path = cleanPath,
			entries = entries,
			truncated = all.size > cap,
			hasCardHere = File(dir, RoleplayStoryFiles.CARD).isFile,
		)
	}

	/** Whether [folder] directly contains a card file; a cheap check for the UI. */
	fun hasCard(workspace: SessionWorkspace, folder: String): Boolean {
		val clean = folder.trim().trim('/')
		return when (val r = workspace.resolve(clean)) {
			is SessionWorkspace.Resolution.Ok ->
				r.file.isDirectory && File(r.file, RoleplayStoryFiles.CARD).isFile
			is SessionWorkspace.Resolution.Rejected -> false
		}
	}

	private sealed interface FileResult {
		data class Ok(val file: File) : FileResult
		data class Failed(val reason: String) : FileResult
	}

	/** The optional file's three states: present, absent, or a path failure. */
	private sealed interface OptionalFile {
		data class Present(val file: File) : OptionalFile
		data object Absent : OptionalFile
		data class Failed(val reason: String) : OptionalFile
	}

	private fun resolveFile(
		workspace: SessionWorkspace,
		folder: String,
		name: String,
	): FileResult {
		val path = join(folder, name)
		return when (val resolved = workspace.resolve(path)) {
			is SessionWorkspace.Resolution.Rejected -> FileResult.Failed(resolved.reason)
			is SessionWorkspace.Resolution.Ok -> {
				val file = resolved.file
				if (!file.exists() || !file.isFile) {
					return FileResult.Failed("${displayPath(folder)} has no $name")
				}
				FileResult.Ok(file)
			}
		}
	}

	/**
	 * Resolve `folder/name`, where the file is optional.
	 *
	 * A malformed path is still a failure: the folder itself is checked first by
	 * [load], and a rejection here means the scene path escaped the workspace,
	 * which must not be treated as "no scene".
	 */
	private fun resolveOptionalFile(
		workspace: SessionWorkspace,
		folder: String,
		name: String,
	): OptionalFile {
		val path = join(folder, name)
		return when (val resolved = workspace.resolve(path)) {
			is SessionWorkspace.Resolution.Rejected -> OptionalFile.Failed(resolved.reason)
			is SessionWorkspace.Resolution.Ok -> {
				val file = resolved.file
				when {
					!file.exists() -> OptionalFile.Absent
					!file.isFile -> OptionalFile.Failed("${displayPath(path)} is not a file")
					else -> OptionalFile.Present(file)
				}
			}
		}
	}

	/**
	 * Read a text file under the workspace read ceiling.
	 *
	 * A fake absent marker is never read: [load] checks `exists()` before
	 * [read]; this is only here to keep the optional-file branch explicit.
	 */
	private sealed interface ReadResult {
		data class Ok(val text: String) : ReadResult
		data class Failed(val reason: String) : ReadResult
	}

	private fun read(workspace: SessionWorkspace, file: File): ReadResult {
		val limit = workspace.limits.maxReadBytes
		val size = file.length()
		if (size > limit) {
			return ReadResult.Failed(
				"${workspace.relativize(file)} is $size bytes, over the $limit-byte read limit",
			)
		}
		return runCatching { ReadResult.Ok(file.readText()) }
			.getOrElse { failure -> ReadResult.Failed("could not read ${workspace.relativize(file)}: ${failure.message}") }
	}

	private fun join(folder: String, name: String): String =
		if (folder.isEmpty()) name else "$folder/$name"

	private fun displayPath(path: String): String =
		if (path.isEmpty()) "the workspace root" else path
}
