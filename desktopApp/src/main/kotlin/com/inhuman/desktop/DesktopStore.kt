package com.inhuman.desktop

import androidx.room.Room
import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import com.inhuman.core.ai.provider.demo.DemoProvider
import com.inhuman.core.ai.policy.PolicySet
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.ai.ssh.SshLimits
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.Conversation
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.DataPaths
import com.inhuman.core.data.PolicyRepository
import com.inhuman.core.data.SnapshotStore
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.ContextSummaryEntity
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.MessageEntity
import com.inhuman.core.data.db.ProviderIds
import com.inhuman.core.data.db.ProviderProfileEntity
import com.inhuman.core.data.db.StoredToolCall
import com.inhuman.core.data.db.ToolCallsColumn
import com.inhuman.core.data.db.ToolsColumn
import com.inhuman.core.files.SessionWorkspace
import com.inhuman.desktop.secret.DpapiSecretStore
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One row the window can draw. */
data class StoredLine(
	val id: String,
	/** `user`, `assistant` or `tool`; the same names the schema stores. */
	val role: String,
	val text: String,
	val reasoning: String,
	val toolCalls: List<StoredToolCall> = emptyList(),
	val toolName: String? = null,
	val isError: Boolean = false,
	val attachments: List<DesktopAttachment> = emptyList(),
) {
	val fromUser: Boolean get() = role == "user"
}

/** A conversation as the rail shows it. */
data class ConversationSummary(
	val id: String,
	val title: String,
	val updatedAt: Long,
	val pinned: Boolean = false,
)

/** One conversation-level search hit, deduplicated from the message matches. */
data class ConversationMatch(
	val id: String,
	val title: String,
	val snippet: String,
)

/** Where the next turn goes, and what the header should call it. */
data class ResolvedChat(
	val provider: ChatProvider,
	val model: String,
	val label: String,
	val error: String? = null,
)

/**
 * The desktop host's data layer.
 *
 * The window used to keep its transcript in a `mutableStateListOf`: closing it
 * lost the conversation, and the repository code could not be called from a
 * plain JVM anyway. This opens the same `InhumanDatabase` the Android app uses,
 * with the bundled SQLite driver, and stores messages through the same
 * `ConversationRepository`.
 *
 * The layout under the data directory is deliberately the Android one --
 * `inhuman.db`, `files/conversations/<workspaceId>` -- so the two hosts open
 * the same shape of store rather than growing parallel conventions.
 *
 * This class holds no selection. The window owns that, because switching a
 * conversation must reload the transcript and a store that remembered an id
 * would be a second source of truth for what is on screen.
 */
private const val KEY_SSH_ENABLED = "ssh.enabled"
private const val KEY_SSH_COMMAND_SECONDS = "ssh.command_timeout_seconds"
private const val KEY_SSH_OUTPUT_BYTES = "ssh.output_bytes"
private const val KEY_SSH_LIST_ENTRIES = "ssh.list_entries"
private const val KEY_SSH_READ_BYTES = "ssh.read_bytes"
private const val KEY_LANGUAGE = "desktop.language"

private const val COMPACTION_PROMPT =
	"You compress an earlier part of a chat transcript into a compact factual summary. " +
		"Keep decisions, file paths, tool results that matter, unresolved questions and the user's " +
		"preferences. Do not invent anything. Write plain prose, no preamble."

class DesktopStore private constructor(
	private val db: InhumanDatabase,
	private val conversations: ConversationRepository,
	private val agents: AgentRepository,
	private val paths: DataPaths,
	private val secrets: DpapiSecretStore,
) {

	/** The outcome of removing the newest turn. */
	data class RewindOutcome(
		val userText: String? = null,
		val attachments: List<DesktopAttachment> = emptyList(),
		val error: String? = null,
	)

	/** The outcome of replacing older turns with a summary. */
	data class CompactOutcome(
		val omittedMessages: Int = 0,
		val error: String? = null,
	)

	/** One user turn offered by the rewind picker. */
	data class RewindPoint(
		val messageId: String,
		val text: String,
		val createdAt: Long,
		val attachments: List<DesktopAttachment>,
		val restorable: Boolean,
	)

	/** Newest first, the same ordering the Android conversation list uses. */
	suspend fun list(): List<ConversationSummary> =
		conversations.all().map(::summary)

	/**
	 * Full-text search, shown as one row per conversation.
	 *
	 * The repository returns one row per matching message; the rail needs one
	 * per conversation, with the first snippet as the reason it matched. Pinned
	 * conversations are not special-cased here -- a search is a search.
	 */
	suspend fun search(rawQuery: String): List<ConversationMatch> =
		conversations.search(rawQuery).distinctBy { it.conversationId }.map { hit ->
			ConversationMatch(
				id = hit.conversationId,
				title = hit.conversationTitle.ifBlank { "Untitled conversation" },
				snippet = hit.snippet,
			)
		}

	suspend fun rename(id: String, title: String) {
		val trimmed = title.trim()
		if (trimmed.isNotBlank()) conversations.rename(id, trimmed)
	}

	suspend fun setPinned(id: String, pinned: Boolean) {
		conversations.setPinned(id, pinned)
	}

	/** Delete the conversation and its temporary workspace, as Android does. */
	suspend fun delete(id: String) {
		val conversation = conversations.conversation(id) ?: return
		conversations.delete(conversation.entity)
	}

	suspend fun create(
		title: String = "New conversation",
		assistant: AgentEntity? = null,
	): ConversationSummary {
		val resolved = assistant ?: defaultAssistant()
		return summary(conversations.create(title = title, agent = resolved))
	}

	suspend fun defaultAssistant(): AgentEntity? = agents.defaultAgent()

	suspend fun assistants(): List<AgentEntity> = agents.all()

	suspend fun assistant(id: String): AgentEntity? = agents.byId(id)

	/**
	 * Save an assistant, stamping the update time.
	 *
	 * A new assistant is the caller's draft with a fresh id; this is the only
	 * writer, so a half-edited draft never reaches the database.
	 */
	suspend fun saveAssistant(agent: AgentEntity) {
		agents.save(agent.copy(updatedAt = System.currentTimeMillis()))
	}

	suspend fun deleteAssistant(agent: AgentEntity) {
		agents.delete(agent)
	}

	/** A draft with the default tool set; not persisted until saved. */
	fun newAssistant(): AgentEntity = AgentEntity(
		id = UUID.randomUUID().toString(),
		name = "New assistant",
		systemPrompt = "",
		providerId = ProviderIds.DEEPSEEK,
		model = null,
		reasoningEffort = null,
		thinking = false,
		toolsJson = ToolsColumn.encode(AgentRepository.DEFAULT_NEW_AGENT_TOOLS),
		createdAt = System.currentTimeMillis(),
		updatedAt = System.currentTimeMillis(),
		isDefault = false,
	)

	suspend fun providers(): List<ProviderProfileEntity> = agents.providers()

	suspend fun hasProviderKey(providerId: String): Boolean = agents.hasKey(providerId)

	/** Cached or fetched model list; empty on a transport failure. */
	suspend fun models(providerId: String, force: Boolean = false): List<RemoteModel> =
		agents.models(providerId, force).getOrElse { emptyList() }

	suspend fun setConversationModel(conversationId: String, model: String?) {
		conversations.setModel(conversationId, model)
	}

	/**
	 * Save a provider key and return whether the profile exists.
	 *
	 * A null key leaves the stored one alone; a blank string removes it. That is
	 * the Android editor's contract, and the caller decides which it means.
	 */
	suspend fun saveProviderKey(providerId: String, apiKey: String?): Boolean {
		val profile = agents.provider(providerId) ?: return false
		agents.saveProvider(profile, apiKey)
		return true
	}

	/** A provider built from the saved profile and key, or null without a key. */
	suspend fun provider(providerId: String): ChatProvider? =
		agents.provider(providerId)?.let { agents.providerFromRow(it) }

	/**
	 * What the next turn should send to.
	 *
	 * No key means demo mode, not an error: that is the state a fresh install
	 * starts in and the state the Android app treats as usable. A key that
	 * exists but whose model list will not answer is different, and is reported
	 * so the composer can say why nothing is being sent.
	 */
	suspend fun resolve(assistant: AgentEntity?, conversationId: String? = null): ResolvedChat {
		val conversation = conversationId?.let { conversations.conversation(it)?.entity }
		val effective = conversation?.agentId?.let { agents.byId(it) } ?: assistant
		val providerId = effective?.providerId ?: conversation?.providerId ?: ProviderIds.DEEPSEEK
		val profile = agents.provider(providerId)
		val provider = profile?.let { agents.providerFromRow(it) }
		if (provider == null) {
			return ResolvedChat(DemoProvider(chunkDelayMillis = 8), "demo-model", "Demo")
		}
		val conversationModel = conversation?.model?.takeIf { it.isNotBlank() }
		val model = conversationModel
			?: effective?.model?.takeIf { it.isNotBlank() }
			?: agents.models(providerId).getOrNull()?.firstOrNull()?.id
		if (model == null) {
			return ResolvedChat(
				provider = DemoProvider(chunkDelayMillis = 8),
				model = "demo-model",
				label = "Demo",
				error = "No model is available for ${profile.name}. Check the provider key and endpoint.",
			)
		}
		return ResolvedChat(provider, model, profile.name)
	}

	suspend fun load(conversationId: String): List<StoredLine> =
		messages(conversationId).map { message ->
			StoredLine(
				id = message.id,
				role = message.role,
				text = message.content,
				reasoning = message.reasoning.orEmpty(),
				toolCalls = ToolCallsColumn.decode(message.toolCallsJson),
				toolName = message.toolName,
				isError = message.error != null,
				attachments = DesktopAttachmentCodec.decode(message.attachmentsJson),
			)
		}

	suspend fun conversation(id: String): Conversation? = conversations.conversation(id)

	suspend fun messages(conversationId: String): List<MessageEntity> =
		conversations.conversation(conversationId)?.messages.orEmpty()

	suspend fun lastMessageId(conversationId: String): String? =
		messages(conversationId).lastOrNull()?.id

	fun workspace(conversation: ConversationEntity): SessionWorkspace =
		conversations.workspace(conversation)

	/** The current conversation's workspace, for the Files window. */
	suspend fun workspaceFor(conversationId: String): SessionWorkspace? =
		conversation(conversationId)?.let { workspace(it.entity) }

	suspend fun contextSummary(conversationId: String): ContextSummaryEntity? =
		conversations.contextSummary(conversationId)

	/**
	 * Whether the model selected for this conversation advertises image input.
	 *
	 * Null means "not known": no model list could be loaded. The UI stays
	 * permissive for null (the provider will answer definitively) but refuses a
	 * known text-only model before importing bytes that could never be sent.
	 */
	suspend fun supportsVision(assistant: AgentEntity?, conversationId: String): Boolean? {
		val resolved = resolve(assistant, conversationId)
		if (resolved.provider is DemoProvider) return true
		if (resolved.error != null) return null
		val conversation = conversation(conversationId)?.entity
		val effective = conversation?.agentId?.let { agents.byId(it) } ?: assistant
		val providerId = effective?.providerId ?: conversation?.providerId ?: ProviderIds.DEEPSEEK
		val models = runCatching { agents.models(providerId).getOrNull() }.getOrNull() ?: return null
		return models.firstOrNull { it.id == resolved.model }?.supportsVision
	}

	/**
	 * Copy a selected image into the conversation workspace and return its
	 * stored descriptor. The original file is never moved or referenced.
	 */
	suspend fun importAttachment(conversationId: String, source: File): DesktopAttachment =
		withContext(Dispatchers.IO) {			val conversation = conversation(conversationId)?.entity
				?: error("The conversation no longer exists.")
			val workspace = workspace(conversation)
			val prepared = DesktopImages.prepare(source, workspace.limits.maxReadBytes)
			val relative = "attachments/${UUID.randomUUID()}.jpg"
			val target = when (val resolved = workspace.resolve(relative)) {
				is SessionWorkspace.Resolution.Ok -> resolved.file
				is SessionWorkspace.Resolution.Rejected -> error(resolved.reason)
			}
			val refusal = workspace.quotaRefusal(
				stagingBytes = 0,
				stagingFiles = 0,
				replacedBytes = 0,
				addsFiles = 1,
				newBytes = prepared.bytes.size.toLong(),
			)
			if (refusal != null) error(refusal)
			target.parentFile?.mkdirs()
			target.writeBytes(prepared.bytes)
			DesktopAttachment(
				relativePath = workspace.relativize(target),
				mimeType = prepared.mimeType,
				sizeBytes = prepared.bytes.size.toLong(),
				displayName = source.name,
			)
		}

	suspend fun readAttachment(
		conversationId: String,
		attachment: DesktopAttachment,
	): ByteArray? = withContext(Dispatchers.IO) {
		val workspace = workspaceFor(conversationId) ?: return@withContext null
		val file = when (val resolved = workspace.resolve(attachment.relativePath)) {
			is SessionWorkspace.Resolution.Ok -> resolved.file
			is SessionWorkspace.Resolution.Rejected -> return@withContext null
		}
		if (!file.isFile || file.length() > workspace.limits.maxReadBytes) return@withContext null
		runCatching { file.readBytes() }.getOrNull()
	}

	suspend fun clearContextSummary(conversationId: String) {
		conversations.clearContextSummary(conversationId)
	}

	/**
	 * A rough context size: the last provider usage when available, otherwise
	 * four characters per token across the transcript.
	 *
	 * The gauge is an estimate, not billing. It exists so "compact" has a number
	 * beside it rather than being a blind action.
	 */
	suspend fun contextTokens(conversationId: String): Int {
		val messages = messages(conversationId)
		val lastUsage = messages.lastOrNull {
			it.role == "assistant" && it.promptTokens != null
		}
		if (lastUsage != null) {
			return (lastUsage.promptTokens ?: 0) + (lastUsage.completionTokens ?: 0)
		}
		val chars = messages.sumOf { it.content.length + it.reasoning.orEmpty().length }
		return (chars / 4).coerceAtLeast(0)
	}

	/**
	 * Summarize older turns and store the projection.
	 *
	 * The original rows are untouched. A demo provider gets a local deterministic
	 * summary so the path is testable offline and does not spend money.
	 */
	suspend fun compact(
		conversationId: String,
		provider: ChatProvider,
		model: String,
		keepTurns: Int = 2,
	): CompactOutcome = withContext(Dispatchers.IO) {
		val all = messages(conversationId)
		if (all.isEmpty()) return@withContext CompactOutcome(error = "There is nothing to compact.")
		val userIndices = all.indices.filter { all[it].role == "user" }
		if (userIndices.size <= keepTurns) {
			return@withContext CompactOutcome(error = "Keep at least $keepTurns turns; there are not enough older turns yet.")
		}
		val keepStart = userIndices[userIndices.size - keepTurns]
		val boundary = keepStart - 1
		if (boundary < 0 || boundary >= all.lastIndex) {
			return@withContext CompactOutcome(error = "There is no older prefix to summarize.")
		}
		val covered = all.subList(0, boundary + 1)
		val summary = if (provider is DemoProvider) {
			demoSummary(covered)
		} else {
			val transcript = covered.joinToString("\n\n") { message ->
				val body = message.content.take(4_000)
				when (message.role) {
					"user" -> "USER: $body"
					"tool" -> "TOOL${message.toolName?.let { "($it)" } ?: ""}: $body"
					else -> "ASSISTANT: $body"
				}
			}.take(24_000)
			val output = StringBuilder()
			try {
				provider.stream(
					ChatRequest(
						model = model,
						messages = listOf(
							ChatMessage(Role.System, COMPACTION_PROMPT),
							ChatMessage(
								Role.User,
								"Summarize this earlier part of the conversation:\n\n$transcript",
							),
						),
						maxTokens = 900,
						thinking = false,
						userId = conversationId,
					),
				).collect { chunk ->
					when (chunk) {
						is StreamChunk.TextDelta -> output.append(chunk.text)
						is StreamChunk.Failure -> throw IllegalStateException(chunk.message)
						else -> Unit
					}
				}
			} catch (failure: Exception) {
				return@withContext CompactOutcome(
					error = "Summarizing failed: ${failure.message}",
				)
			}
			output.toString().trim()
		}
		if (summary.isBlank()) {
			return@withContext CompactOutcome(error = "The summarizer returned nothing.")
		}
		conversations.saveContextSummary(
			conversationId = conversationId,
			throughMessageId = all[boundary].id,
			summary = summary,
		)
		CompactOutcome(omittedMessages = boundary + 1)
	}

	private fun demoSummary(messages: List<MessageEntity>): String {
		val users = messages.count { it.role == "user" }
		val last = messages.lastOrNull { it.role == "assistant" }?.content.orEmpty()
		return "Earlier conversation: $users user turns, ${messages.size} messages. " +
			"The latest assistant answer before the kept turns was: " +
			last.take(500)
	}

	/**
	 * Write a standalone SQLite snapshot with `VACUUM INTO`.
	 *
	 * Copying `inhuman.db` while Room has it open can miss committed frames
	 * still in the WAL; `VACUUM INTO` checkpoints and writes one consistent
	 * file. The target must not exist.
	 */
	suspend fun snapshotDatabase(target: File): Unit = withContext(Dispatchers.IO) {
		target.parentFile?.mkdirs()
		target.delete()
		val escaped = target.absolutePath.replace("'", "''")
		db.useWriterConnection { transactor ->
			transactor.execSQL("VACUUM INTO '$escaped'")
		}
	}

	/**
	 * Snapshot the workspace at the start of a turn.
	 *
	 * A failure is not fatal to the turn -- the user still gets an answer -- it
	 * only means that turn cannot be rewound. `DesktopChat` records the user
	 * message id before this runs, because the id is the checkpoint directory.
	 */
	suspend fun captureTurnStart(conversationId: String, userMessageId: String): Boolean {
		val workspace = workspaceFor(conversationId) ?: return false
		return withContext(Dispatchers.IO) {
			SnapshotStore.capture(
				workspaceRoot = workspace.rootPath(),
				turnDir = SnapshotStore.turnDir(paths.rollbackDir(conversationId), userMessageId),
				checkpointId = "start",
			)
		}
	}

	/**
	 * Remove the newest user turn and restore the workspace to its start.
	 *
	 * Restore happens **before** the rows are deleted: if it fails, the
	 * transcript must stay untouched, or the user loses the text and keeps the
	 * side effects.
	 */
	/**
	 * Every user turn on the active path, newest last.
	 *
	 * A turn without a start checkpoint is listed but not restorable: the
	 * transcript is the source of truth for what the user said, while the
	 * checkpoint decides whether the workspace can be put back.
	 */
	suspend fun rewindPoints(conversationId: String): List<RewindPoint> =
		messages(conversationId)
			.filter { it.role == "user" }
			.map { user ->
				RewindPoint(
					messageId = user.id,
					text = user.content,
					createdAt = user.createdAt,
					attachments = DesktopAttachmentCodec.decode(user.attachmentsJson),
					restorable = SnapshotStore.hasCheckpoint(
						SnapshotStore.turnDir(paths.rollbackDir(conversationId), user.id),
						"start",
					),
				)
			}

	/**
	 * Remove one user turn and every later turn, and restore the workspace to
	 * the checkpoint taken before that turn.
	 *
	 * Restore happens **before** the rows are deleted: if it fails, the
	 * transcript must stay untouched, or the user loses the text and keeps the
	 * side effects. A turn whose suffix contains a remote write cannot be
	 * rewound, because the local snapshot cannot undo the other machine.
	 */
	suspend fun rewindTo(conversationId: String, messageId: String): RewindOutcome {
		val messages = messages(conversationId)
		val index = messages.indexOfFirst { it.id == messageId && it.role == "user" }
		if (index < 0) return RewindOutcome(error = "That user turn is no longer in this conversation.")
		val user = messages[index]
		val turnDir = SnapshotStore.turnDir(paths.rollbackDir(conversationId), user.id)
		if (!SnapshotStore.hasCheckpoint(turnDir, "start")) {
			return RewindOutcome(error = "This turn has no start checkpoint, so it cannot be restored.")
		}
		val removedTurns = messages.subList(index, messages.size)
			.filter { it.role == "user" }
			.map { it.id }
		val irreversible = removedTurns
			.asSequence()
			.flatMap { turnId ->
				val turn = messages.first { it.id == turnId }
				val end = messages.indexOf(turn).let { start ->
					messages.drop(start + 1).indexOfFirst { it.role == "user" }
						.let { relative -> if (relative < 0) messages.size else start + 1 + relative }
				}
				messages.subList(messages.indexOf(turn), end)
					.filter { it.role == "assistant" }
					.flatMap { assistant ->
						ToolCallsColumn.decode(assistant.toolCallsJson).asSequence()
					}
					.filter { call ->
						val name = call.name ?: return@filter false
						ToolCatalog.all.firstOrNull { it.name == name }?.reversible == false
					}
					.map { call -> call.name.orEmpty() }
			}
			.firstOrNull()
		if (irreversible != null) {
			return RewindOutcome(
				error = "This turn used $irreversible, which changes another machine and cannot be rolled back.",
			)
		}
		val workspace = workspaceFor(conversationId)
			?: return RewindOutcome(error = "The workspace is not available.")
		val restored = withContext(Dispatchers.IO) {
			SnapshotStore.restore(workspace.rootPath(), turnDir, "start")
		}
		if (!restored) {
			return RewindOutcome(error = "The workspace could not be restored; nothing was deleted.")
		}
		conversations.deleteSubtree(user.id, user.parentId, conversationId)
		val summary = conversations.contextSummary(conversationId)
		if (summary != null && removedTurns.contains(summary.throughMessageId)) {
			conversations.clearContextSummary(conversationId)
		}
		withContext(Dispatchers.IO) {
			removedTurns.forEach { turnId ->
				SnapshotStore.deleteTurn(paths.rollbackDir(conversationId), turnId)
			}
		}
		return RewindOutcome(
			userText = user.content,
			attachments = DesktopAttachmentCodec.decode(user.attachmentsJson),
		)
	}

	/** Remove the newest user turn; a thin wrapper over [rewindTo]. */
	suspend fun rewindLastTurn(conversationId: String): RewindOutcome {
		val user = messages(conversationId).lastOrNull { it.role == "user" }
			?: return RewindOutcome(error = "There is no user turn to rewind.")
		return rewindTo(conversationId, user.id)
	}

	suspend fun policies(conversationId: String, agentId: String?): PolicySet =
		PolicyRepository(db).forConversation(conversationId, agentId)

	fun sshEnabled(): Boolean = secrets.get(KEY_SSH_ENABLED) == "1"

	/** UI language code; Chinese on a Chinese system, English otherwise. */
	fun language(): String = secrets.get(KEY_LANGUAGE)
		?: if (Locale.getDefault().language == "zh") "zh" else "en"

	fun setLanguage(code: String) {
		secrets.put(KEY_LANGUAGE, if (code == "zh") "zh" else "en")
	}

	/**
	 * Turning the global switch off revokes every conversation's grant.
	 *
	 * A grant made while SSH was on must not survive the switch being turned
	 * off and on again; otherwise "off" is a pause, not a capability boundary.
	 */
	suspend fun setSshEnabled(enabled: Boolean) {
		secrets.put(KEY_SSH_ENABLED, if (enabled) "1" else "0")
		if (!enabled) conversations.clearAllSshAllowed()
	}

	fun sshLimits(): SshLimits = SshLimits.fromStored(
		commandTimeoutSeconds = secrets.get(KEY_SSH_COMMAND_SECONDS),
		outputBytes = secrets.get(KEY_SSH_OUTPUT_BYTES),
		listEntries = secrets.get(KEY_SSH_LIST_ENTRIES),
		readBytes = secrets.get(KEY_SSH_READ_BYTES),
	)

	fun setSshLimits(limits: SshLimits) {
		secrets.put(KEY_SSH_COMMAND_SECONDS, limits.commandTimeoutCeilingSeconds.toString())
		secrets.put(KEY_SSH_OUTPUT_BYTES, limits.outputCeilingBytes.toString())
		secrets.put(KEY_SSH_LIST_ENTRIES, limits.listCeilingEntries.toString())
		secrets.put(KEY_SSH_READ_BYTES, limits.readCeilingBytes.toString())
	}

	suspend fun sshAllowed(conversationId: String): Boolean =
		conversations.sshAllowed(conversationId) ?: false

	suspend fun setSshAllowed(conversationId: String, allowed: Boolean) {
		conversations.setSshAllowed(conversationId, allowed)
	}

	suspend fun clearAllSshAllowed() {
		conversations.clearAllSshAllowed()
	}

	/**
	 * Replace a conversation's tool selection.
	 *
	 * Adding `ssh_exec`, `ssh_upload` or `ssh_write_file` to an existing grant
	 * resets the conversation's SSH consent: the grant was for the narrower
	 * capability set, and a write is not covered by a read-only yes.
	 */
	suspend fun setConversationTools(conversationId: String, tools: List<String>) {
		val entity = conversation(conversationId)?.entity ?: return
		val clean = tools.distinct()
		val before = entity.tools.orEmpty().toSet()
		val added = clean.toSet() - before
		val dangerous = setOf("ssh_exec", "ssh_upload", "ssh_write_file")
		val reset = entity.sshAllowed && added.any { it in dangerous }
		conversations.setTools(conversationId, clean)
		if (reset) conversations.setSshAllowed(conversationId, false)
	}

	/** The global approval rules, as the settings screen will edit them. */
	suspend fun globalRules(): Map<String, ToolPolicy> =
		PolicyRepository(db).globalRules()

	suspend fun setGlobalRule(toolName: String, policy: ToolPolicy) {
		PolicyRepository(db).setGlobalRule(toolName, policy)
	}

	suspend fun appendUser(
		conversationId: String,
		text: String,
		parentId: String?,
		attachments: List<DesktopAttachment> = emptyList(),
	): StoredLine {
		val message = MessageEntity(
			id = UUID.randomUUID().toString(),
			conversationId = conversationId,
			parentId = parentId,
			role = "user",
			content = text,
			createdAt = System.currentTimeMillis(),
			attachmentsJson = DesktopAttachmentCodec.encode(attachments),
		)
		conversations.appendMessage(message, parentId)
		return StoredLine(
			message.id,
			role = "user",
			text = text,
			reasoning = "",
			attachments = attachments,
		)
	}

	suspend fun appendAssistant(
		conversationId: String,
		text: String,
		reasoning: String,
		parentId: String?,
		toolCalls: List<StoredToolCall> = emptyList(),
		error: String? = null,
		finishReason: String? = null,
		promptTokens: Int? = null,
		completionTokens: Int? = null,
		cachedPromptTokens: Int? = null,
		reasoningTokens: Int? = null,
	): StoredLine {
		val message = MessageEntity(
			id = UUID.randomUUID().toString(),
			conversationId = conversationId,
			parentId = parentId,
			role = "assistant",
			content = text,
			reasoning = reasoning.takeIf { it.isNotBlank() },
			error = error,
			finishReason = finishReason,
			promptTokens = promptTokens,
			completionTokens = completionTokens,
			cachedPromptTokens = cachedPromptTokens,
			reasoningTokens = reasoningTokens,
			createdAt = System.currentTimeMillis(),
			toolCallsJson = toolCalls.takeIf { it.isNotEmpty() }?.let(ToolCallsColumn::encode),
		)
		conversations.appendMessage(message, parentId)
		return StoredLine(
			id = message.id,
			role = "assistant",
			text = text,
			reasoning = reasoning,
			toolCalls = toolCalls,
			isError = error != null,
		)
	}

	suspend fun appendTool(
		conversationId: String,
		toolCallId: String?,
		toolName: String?,
		content: String,
		isError: Boolean,
		parentId: String?,
	): StoredLine {
		val message = MessageEntity(
			id = UUID.randomUUID().toString(),
			conversationId = conversationId,
			parentId = parentId,
			role = "tool",
			content = content,
			error = content.takeIf { isError },
			createdAt = System.currentTimeMillis(),
			toolCallId = toolCallId,
			toolName = toolName,
		)
		conversations.appendMessage(message, parentId)
		return StoredLine(
			id = message.id,
			role = "tool",
			text = content,
			reasoning = "",
			toolName = toolName,
			isError = isError,
		)
	}

	/** Name the conversation from its first user message, as Android does. */
	suspend fun autoTitle(conversationId: String, text: String) {
		conversations.autoTitle(conversationId, text)
	}

	fun close() {
		db.close()
	}

	private fun summary(entity: com.inhuman.core.data.db.ConversationEntity) =
		ConversationSummary(
			id = entity.id,
			title = entity.title.ifBlank { "Untitled conversation" },
			updatedAt = entity.updatedAt,
			pinned = entity.pinned,
		)

	companion object {
		/**
		 * `%LOCALAPPDATA%\inhuman-data` on Windows, the XDG data directory elsewhere.
		 *
		 * Deliberately not `%LOCALAPPDATA%\inhuman`: jpackage's per-user MSI
		 * installs the application into that directory, and putting the user's
		 * database and workspaces beside `inhuman.exe` means an uninstall or a
		 * repair can take the data with it.
		 */
		fun defaultDataDir(): File {
			val os = System.getProperty("os.name").lowercase(Locale.ROOT)
			val base = if (os.contains("windows")) {
				System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
			} else {
				System.getenv("XDG_DATA_HOME")
					?: File(System.getProperty("user.home"), ".local/share").absolutePath
			}
			return File(base, "inhuman-data").apply { mkdirs() }
		}

		fun open(dataDir: File = defaultDataDir()): DesktopStore {
			dataDir.mkdirs()
			val db = Room.databaseBuilder<InhumanDatabase>(
				File(dataDir, InhumanDatabase.NAME).absolutePath,
			)
				.setDriver(BundledSQLiteDriver())
				// The same migrations and seed callback as the Android host, from
				// `:core:db`. A desktop copy of either would be a second answer
				// to "what does this database look like on first launch".
				.addMigrations(*InhumanDatabase.MIGRATIONS)
				.addCallback(InhumanDatabase.callback())
				.build()
			val secrets = DpapiSecretStore(File(dataDir, "secrets.bin"))
			return DesktopStore(
				db = db,
				conversations = ConversationRepository(db, DataPaths(File(dataDir, "files"))),
				agents = AgentRepository(db = db, secure = secrets),
				paths = DataPaths(File(dataDir, "files")),
				secrets = secrets,
			)
		}
	}
}
