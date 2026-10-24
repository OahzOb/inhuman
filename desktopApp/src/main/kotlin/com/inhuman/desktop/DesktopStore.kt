package com.inhuman.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.provider.demo.DemoProvider
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.DataPaths
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.MessageEntity
import com.inhuman.core.data.db.ProviderIds
import com.inhuman.core.data.db.ProviderProfileEntity
import com.inhuman.desktop.secret.DpapiSecretStore
import java.io.File
import java.util.Locale
import java.util.UUID

/** One row the window can draw. */
data class StoredLine(
	val id: String,
	val fromUser: Boolean,
	val text: String,
	val reasoning: String,
)

/** A conversation as the rail shows it. */
data class ConversationSummary(
	val id: String,
	val title: String,
	val updatedAt: Long,
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
) {

	/** Newest first, the same ordering the Android conversation list uses. */
	suspend fun list(): List<ConversationSummary> =
		conversations.all().map(::summary)

	suspend fun create(title: String = "New conversation"): ConversationSummary =
		summary(conversations.create(title = title))

	suspend fun defaultAssistant(): AgentEntity? = agents.defaultAgent()

	suspend fun assistants(): List<AgentEntity> = agents.all()

	suspend fun providers(): List<ProviderProfileEntity> = agents.providers()

	suspend fun hasProviderKey(providerId: String): Boolean = agents.hasKey(providerId)

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
	suspend fun resolve(assistant: AgentEntity?): ResolvedChat {
		val providerId = assistant?.providerId ?: ProviderIds.DEEPSEEK
		val profile = agents.provider(providerId)
		val provider = profile?.let { agents.providerFromRow(it) }
		if (provider == null) {
			return ResolvedChat(DemoProvider(chunkDelayMillis = 8), "demo-model", "Demo")
		}
		val model = assistant?.model?.takeIf { it.isNotBlank() }
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
		conversations.conversation(conversationId)?.messages.orEmpty()
			.filter { it.role == "user" || it.role == "assistant" }
			.map { message ->
				StoredLine(
					id = message.id,
					fromUser = message.role == "user",
					text = message.content,
					reasoning = message.reasoning.orEmpty(),
				)
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
		return StoredLine(message.id, fromUser = true, text = text, reasoning = "")
	}

	suspend fun appendAssistant(
		conversationId: String,
		text: String,
		reasoning: String,
		parentId: String?,
	): StoredLine {
		val message = MessageEntity(
			id = UUID.randomUUID().toString(),
			conversationId = conversationId,
			parentId = parentId,
			role = "assistant",
			content = text,
			reasoning = reasoning.takeIf { it.isNotBlank() },
			createdAt = System.currentTimeMillis(),
		)
		conversations.appendMessage(message, parentId)
		return StoredLine(message.id, fromUser = false, text = text, reasoning = reasoning)
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
		)

	companion object {
		/** `%LOCALAPPDATA%\inhuman` on Windows, the XDG data directory elsewhere. */
		fun defaultDataDir(): File {
			val os = System.getProperty("os.name").lowercase(Locale.ROOT)
			val base = if (os.contains("windows")) {
				System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
			} else {
				System.getenv("XDG_DATA_HOME")
					?: File(System.getProperty("user.home"), ".local/share").absolutePath
			}
			return File(base, "inhuman").apply { mkdirs() }
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
			)
		}
	}
}
