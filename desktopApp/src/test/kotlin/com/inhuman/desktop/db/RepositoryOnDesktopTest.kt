package com.inhuman.desktop.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.inhuman.core.data.AgentRepository
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.DataPaths
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.MessageEntity
import com.inhuman.core.data.db.ProviderKinds
import com.inhuman.core.data.db.ProviderProfileEntity
import com.inhuman.core.data.db.ProviderReasoning
import com.inhuman.core.data.secret.SecretStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * The repositories on the desktop JVM, against a real file-backed database.
 *
 * This is the step past the spike's in-memory `SpikeDb`: the same
 * `InhumanDatabase`, the same `ConversationRepository` and the same workspace
 * layout the Android app uses, with a desktop `SecretStore` standing in for the
 * Keystore. If this passes, the desktop host has a data layer to build a UI on.
 */
class RepositoryOnDesktopTest {

	@get:Rule
	val tmp = TemporaryFolder()

	@Test
	fun `conversations, messages and providers work on the desktop JVM`() = runBlocking {
		val dbFile = File(tmp.newFolder("db"), "inhuman.db")
		val db = Room.databaseBuilder<InhumanDatabase>(dbFile.absolutePath)
			.setDriver(BundledSQLiteDriver())
			.addMigrations(*InhumanDatabase.MIGRATIONS)
			.addCallback(InhumanDatabase.callback())
			.build()
		try {
			val paths = DataPaths(tmp.newFolder("files"))
			val conversations = ConversationRepository(db, paths)
			val agents = AgentRepository(db, MemorySecretStore())

			// The shared callback seeded the same default assistant and provider
			// the Android install starts with.
			assertNotNull("the default assistant was not seeded", agents.defaultAgent())
			assertNotNull("the DeepSeek profile was not seeded", agents.provider("deepseek"))

			val providerId = UUID.randomUUID().toString()
			agents.saveProvider(
				ProviderProfileEntity(
					id = providerId,
					name = "Desktop",
					kind = ProviderKinds.OPENAI_COMPATIBLE,
					baseUrl = "https://example.invalid/v1",
					reasoningMode = ProviderReasoning.NONE,
					createdAt = System.currentTimeMillis(),
					updatedAt = System.currentTimeMillis(),
				),
				"sk-desktop-test",
			)
			assertTrue(agents.hasKey(providerId))
			assertNotNull(
				"a provider with a key must be constructible on the JVM",
				agents.providerFromRow(agents.provider(providerId)!!),
			)

			val agent = agents.newAgent().copy(name = "Desktop assistant", providerId = providerId)
			agents.save(agent)
			val conversation = conversations.create(title = "Desktop conversation", agent = agent)
			conversations.appendMessage(
				MessageEntity(
					id = "desktop-message",
					conversationId = conversation.id,
					parentId = null,
					role = "user",
					content = "hello from the desktop",
					createdAt = System.currentTimeMillis(),
				),
				null,
			)

			val loaded = conversations.conversation(conversation.id)
			assertEquals("hello from the desktop", loaded?.messages?.single()?.content)
			assertTrue(paths.workspaceDir(conversation.workspaceId).isDirectory)

			// The database is on disk, not in memory: reopening it must see the
			// row the way the next app launch would.
			val reopened = Room.databaseBuilder<InhumanDatabase>(dbFile.absolutePath)
				.setDriver(BundledSQLiteDriver())
				.build()
			try {
				assertEquals(conversation.id, reopened.conversations().byId(conversation.id)?.id)
			} finally {
				reopened.close()
			}
		} finally {
			db.close()
		}
	}

	/** A desktop stand-in: the Keystore is not available here. */
	private class MemorySecretStore : SecretStore {
		private val values = mutableMapOf<String, String>()

		override fun put(key: String, value: String) {
			values[key] = value
		}

		override fun get(key: String): String? = values[key]

		override fun remove(key: String) {
			values.remove(key)
		}

		override fun has(key: String): Boolean = values.containsKey(key)
	}
}
