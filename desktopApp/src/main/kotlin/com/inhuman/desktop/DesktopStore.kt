package com.inhuman.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.policy.PolicySet
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.ai.provider.demo.DemoProvider
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.Conversation
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.DataPaths
import com.inhuman.core.data.PolicyRepository
import com.inhuman.core.data.SnapshotStore
import com.inhuman.core.data.db.AgentEntity
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
class DesktopStore private constructor(
	private val db: InhumanDatabase,
	private val conversations: ConversationRepository,
	private val agents: AgentRepository,
	private val paths: DataPaths,
) {

	/** The outcome of removing the newest turn. */
	data class RewindOutcome(
		val userText: String? = null,
		val error: String? = null,
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
	suspend fun rewindLastTurn(conversationId: String): RewindOutcome {
		val messages = messages(conversationId)
		val user = messages.lastOrNull { it.role == "user" }
			?: return RewindOutcome(error = "There is no user turn to rewind.")
		val turnDir = SnapshotStore.turnDir(paths.rollbackDir(conversationId), user.id)
		if (!SnapshotStore.hasCheckpoint(turnDir, "start")) {
			return RewindOutcome(error = "This turn has no start checkpoint, so it cannot be restored.")
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
		withContext(Dispatchers.IO) { SnapshotStore.deleteTurn(paths.rollbackDir(conversationId), user.id) }
		return RewindOutcome(userText = user.content)
	}

	suspend fun policies(conversationId: String, agentId: String?): PolicySet =
		PolicyRepository(db).forConversation(conversationId, agentId)

	/** The global approval rules, as the settings screen will edit them. */
	suspend fun globalRules(): Map<String, ToolPolicy> =
		PolicyRepository(db).globalRules()

	suspend fun setGlobalRule(toolName: String, policy: ToolPolicy) {
		PolicyRepository(db).setGlobalRule(toolName, policy)
	}

	suspend fun appendUser(conversationId: String, text: String, parentId: String?): StoredLine {
		val message = MessageEntity(
			id = UUID.randomUUID().toString(),
			conversationId = conversationId,
			parentId = parentId,
			role = "user",
			content = text,
			createdAt = System.currentTimeMillis(),
		)
		conversations.appendMessage(message, parentId)
		return StoredLine(message.id, role = "user", text = text, reasoning = "")
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
			return DesktopStore(
				db = db,
				conversations = ConversationRepository(db, DataPaths(File(dataDir, "files"))),
				agents = AgentRepository(
					db = db,
					secure = DpapiSecretStore(File(dataDir, "secrets.bin")),
				),
				paths = DataPaths(File(dataDir, "files")),
			)
		}
	}
}
