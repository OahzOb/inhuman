package com.inhuman.core.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.Database
import com.inhuman.core.data.db.ProviderIds
import com.inhuman.core.data.db.ProviderKinds
import com.inhuman.core.data.db.ProviderProfileEntity
import com.inhuman.core.data.db.ProviderReasoning
import com.inhuman.core.data.secret.SecureStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProviderProfileTest {

	private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private val db = Database.open(context)
	private val agents = AgentRepository(db, SecureStore(context, "provider-test"))
	private val conversations = ConversationRepository(db, DataPaths(context.filesDir))

	@Test
	fun aConversationCopiesItsAssistantsProvider() = runBlocking {
		val now = System.currentTimeMillis()
		val provider = ProviderProfileEntity(
			id = UUID.randomUUID().toString(),
			name = "Test provider",
			kind = ProviderKinds.OPENAI_COMPATIBLE,
			baseUrl = "https://example.invalid/v1",
			reasoningMode = ProviderReasoning.NONE,
			createdAt = now,
			updatedAt = now,
		)
		db.providers().upsert(provider)
		val agent = AgentEntity(
			id = UUID.randomUUID().toString(),
			name = "Test assistant",
			systemPrompt = "test",
			providerId = provider.id,
			createdAt = now,
			updatedAt = now,
		)
		db.agents().upsert(agent)

		val conversation = conversations.create(agent = agent)
		assertEquals(provider.id, conversation.providerId)

		conversations.delete(conversation)
		db.agents().delete(agent.id)
		assertTrue(agents.deleteProvider(provider.id).isSuccess)
	}

	@Test
	fun aProviderUsedByAnAssistantCannotBeDeleted() = runBlocking {
		val now = System.currentTimeMillis()
		val provider = ProviderProfileEntity(
			id = UUID.randomUUID().toString(),
			name = "Referenced provider",
			baseUrl = "https://example.invalid/v1",
			isBuiltIn = false,
			createdAt = now,
			updatedAt = now,
		)
		db.providers().upsert(provider)
		val agent = AgentEntity(
			id = UUID.randomUUID().toString(),
			name = "Reference",
			systemPrompt = "test",
			providerId = provider.id,
			createdAt = now,
			updatedAt = now,
		)
		db.agents().upsert(agent)

		val result = agents.deleteProvider(provider.id)
		assertTrue(result.isFailure)

		db.agents().delete(agent.id)
		assertTrue(agents.deleteProvider(provider.id).isSuccess)
	}

	@Test
	fun customProviderKeysDoNotShareTheDeepSeekSlot() {
		val custom = agents.apiKeySlot("custom-id")
		assertNotEquals(SecureStore.KEY_DEEPSEEK_API_KEY, custom)
		assertNotEquals(SecureStore.KEY_WEB_DEEPSEEK_API_KEY, custom)
		assertEquals(SecureStore.KEY_DEEPSEEK_API_KEY, agents.apiKeySlot(ProviderIds.DEEPSEEK))
	}
}
