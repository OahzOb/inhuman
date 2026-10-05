package com.inhuman.core.data

import android.content.Context
import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.provider.deepseek.DeepSeekProvider
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.Database
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.secret.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Assistants, and the model list they can choose from.
 *
 * ## Why the model list is fetched and cached rather than stored
 *
 * The list is the provider's, not ours. It reports per-model capabilities that
 * the app genuinely needs -- which models accept images, what the context
 * window is, and which reasoning-effort levels each one supports. Writing that
 * list down would mean shipping a release every time a model is added, and
 * guessing wrong about capabilities in between.
 *
 * Cached in memory only. It is small, it changes rarely, and a stale copy on
 * disk would be worse than a refetch: a model that no longer exists would keep
 * appearing in the picker.
 */
class AgentRepository(
	context: Context,
	private val secure: SecureStore,
	private val db: InhumanDatabase = Database.open(context),
) {

	/**
	 * The application context, never the one that was passed in.
	 *
	 * The caller is a composable, so the argument is the Activity, and this
	 * object is reachable from a view model that survives a configuration
	 * change. See the same note in `ConversationRepository`.
	 */
	private val appContext: Context = context.applicationContext

	private val modelsLock = Mutex()
	private var cachedModels: List<RemoteModel>? = null

	fun observeAll(): Flow<List<AgentEntity>> = db.agents().observeAll()

	suspend fun all(): List<AgentEntity> = db.agents().all()

	suspend fun byId(id: String): AgentEntity? = db.agents().byId(id)

	suspend fun defaultAgent(): AgentEntity? = db.agents().defaultAgent()

	suspend fun save(agent: AgentEntity) = db.agents().upsert(agent)

	suspend fun delete(agent: AgentEntity) {
		// The default cannot be deleted: a conversation with no assistant to
		// attach to has no instructions at all, and the UI would have nothing
		// to fall back to when starting one.
		if (agent.isDefault) return
		db.agents().delete(agent.id)
	}

	fun newAgent(): AgentEntity {
		val now = System.currentTimeMillis()
		return AgentEntity(
			id = UUID.randomUUID().toString(),
			name = "",
			systemPrompt = "",
			createdAt = now,
			updatedAt = now,
			isDefault = false,
		)
	}

	companion object {
		/**
		 * What a newly created assistant starts with.
		 *
		 * An explicit list of today's tools, **not** `null`.
		 *
		 * `null` means "every tool, including ones added later", which is the
		 * right default for an assistant that predates the setting -- it keeps
		 * what it already had. It is the wrong default for a new one, because it
		 * would silently grant every future capability to an assistant somebody
		 * configured deliberately. New tools should arrive switched off and be
		 * turned on, which is the same reasoning the allow-list itself rests on.
		 */
		val DEFAULT_NEW_AGENT_TOOLS: List<String> = listOf(
			"list_files", "read_file", "file_info",
			"read_time", "web_search",
			"write_file", "append_file", "create_directory", "move_file", "delete_file",
		)
	}

	/**
	 * The provider, if one is configured.
	 *
	 * Returns null rather than throwing when no key is stored: "not set up yet"
	 * is the normal state on first launch, not an error.
	 */
	private fun providerOrNull(): ChatProvider? {
		val key = secure.get(SecureStore.KEY_DEEPSEEK_API_KEY) ?: return null
		return DeepSeekProvider(apiKey = key)
	}

	/**
	 * The models the provider currently offers.
	 *
	 * @param force refetch even if a list is already held, for a pull-to-refresh
	 *   or after the key changes.
	 */
	suspend fun models(force: Boolean = false): Result<List<RemoteModel>> {
		modelsLock.withLock {
			val cached = cachedModels
			if (!force && cached != null) return Result.success(cached)
		}

		val provider = providerOrNull()
			?: return Result.failure(IllegalStateException("No API key is set."))

		return withContext(Dispatchers.IO) {
			runCatching { provider.listModels() }
				.onSuccess { modelsLock.withLock { cachedModels = it } }
		}
	}

	/** Drop the cache, so the next read refetches. Called when the key changes. */
	suspend fun invalidateModels() {
		modelsLock.withLock { cachedModels = null }
	}

	/**
	 * The effort levels a model advertises, in the provider's recommended order.
	 *
	 * Returned as reported rather than mapped onto an enum of our own. The
	 * levels differ per model -- `deepseek-flash` offers low, high and max while
	 * another may offer a different set -- and inventing a fixed scale would
	 * make the app send values the API does not accept.
	 */
	suspend fun effortLevels(modelId: String?): List<String> {
		if (modelId == null) return emptyList()
		val models = models().getOrNull().orEmpty()
		return models.firstOrNull { it.id == modelId }?.supportedEffortLevels.orEmpty()
	}
}
