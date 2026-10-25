package com.inhuman.core.data

import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.provider.ProviderFactory
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.ProviderIds
import com.inhuman.core.data.db.ProviderProfileEntity
import com.inhuman.core.data.secret.SecretStore
import kotlinx.coroutines.CancellationException
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
	private val db: InhumanDatabase,
	private val secure: SecretStore,
) {

	private val modelsLock = Mutex()
	private val cachedModels = mutableMapOf<String, List<RemoteModel>>()

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

	fun observeProviders(): Flow<List<ProviderProfileEntity>> = db.providers().observeAll()

	suspend fun providers(): List<ProviderProfileEntity> = db.providers().all()

	suspend fun provider(id: String): ProviderProfileEntity? = db.providers().byId(id)

	/** The secure slot a profile's key lives in. DeepSeek keeps the legacy slot. */
	fun apiKeySlot(providerId: String): String =
		if (providerId == ProviderIds.DEEPSEEK) SecretStore.KEY_DEEPSEEK_API_KEY
		else "provider.$providerId.api_key"

	fun hasKey(providerId: String): Boolean =
		secure.hasUsable(apiKeySlot(providerId))

	/**
	 * Insert or update a profile and, when supplied, its key.
	 *
	 * A null key leaves the stored key alone; an empty string removes it. That
	 * distinction is what lets the editor show a masked existing key without
	 * overwriting it with the mask, while still offering a clear action.
	 */
	suspend fun saveProvider(profile: ProviderProfileEntity, apiKey: String?) {
		db.providers().upsert(profile)
		if (apiKey != null) {
			if (apiKey.isBlank()) secure.remove(apiKeySlot(profile.id))
			else secure.put(apiKeySlot(profile.id), apiKey.trim())
		}
		invalidateModels()
	}

	suspend fun deleteProvider(id: String): Result<Unit> {
		if (id == ProviderIds.DEEPSEEK) {
			return Result.failure(IllegalStateException("The built-in DeepSeek provider cannot be deleted."))
		}
		val agents = db.providers().agentCount(id)
		val conversations = db.providers().conversationCount(id)
		if (agents > 0 || conversations > 0) {
			return Result.failure(
				IllegalStateException(
					"This provider is used by $agents assistant(s) and $conversations " +
						"conversation(s). Delete or reassign them first.",
				),
			)
		}
		db.providers().delete(id)
		secure.remove(apiKeySlot(id))
		invalidateModels()
		return Result.success(Unit)
	}

	suspend fun providerFromRow(profile: ProviderProfileEntity): ChatProvider? {
		val key = secure.get(apiKeySlot(profile.id))?.takeIf { it.isNotBlank() } ?: return null
		return ProviderFactory.create(
			kind = profile.kind,
			apiKey = key,
			baseUrl = profile.baseUrl.trimEnd('/'),
			id = profile.id,
			reasoningMode = profile.reasoningMode,
			includeStreamUsage = profile.includeStreamUsage,
		)
	}

	/**
	 * The already-fetched list, or null when nothing has been fetched.
	 *
	 * Separate from [models] on purpose: `models(force = false)` still performs
	 * a request when the cache is empty, and a caller that wants "show what we
	 * have, then refresh" must not accidentally block on that first request.
	 */
	suspend fun cachedModelList(providerId: String = ProviderIds.DEEPSEEK): List<RemoteModel>? =
		modelsLock.withLock { cachedModels[providerId] }

	/**
	 * The models the provider currently offers.
	 *
	 * A provider whose `/models` endpoint is missing can still be used through
	 * the profile's manual model ids. Those are a fallback, not a substitute:
	 * when the endpoint answers, its capabilities (context window, vision,
	 * effort levels) are better than ids with no metadata.
	 */
	suspend fun models(
		providerId: String = ProviderIds.DEEPSEEK,
		force: Boolean = false,
	): Result<List<RemoteModel>> {
		modelsLock.withLock {
			val cached = cachedModels[providerId]
			if (!force && cached != null) return Result.success(cached)
		}

		val profile = db.providers().byId(providerId)
			?: return Result.failure(IllegalStateException("Provider not found."))
		val provider = providerFromRow(profile)
			?: return Result.failure(IllegalStateException("No API key is set for ${profile.name}."))

		val manual = profile.manualModels.lines()
			.map { it.trim() }
			.filter { it.isNotBlank() }
			.map { RemoteModel(id = it, displayName = it) }

		return withContext(Dispatchers.IO) {
			runCatching { provider.listModels() }
				.recoverCatching { failure ->
					if (failure is CancellationException) throw failure
					if (manual.isNotEmpty()) manual else throw failure
				}
				.map { fetched ->
					// An endpoint that answers with an empty list is as unusable as
					// one that is missing; the fallback ids are still better than
					// an empty picker.
					if (fetched.isEmpty() && manual.isNotEmpty()) manual else fetched
				}
				.onSuccess { modelsLock.withLock { cachedModels[providerId] = it } }
		}
	}

	/** Drop the cache, so the next read refetches. Called when a provider or key changes. */
	suspend fun invalidateModels(providerId: String? = null) {
		modelsLock.withLock {
			if (providerId == null) cachedModels.clear() else cachedModels.remove(providerId)
		}
	}

	/**
	 * The effort levels a model advertises, in the provider's recommended order.
	 *
	 * Returned as reported rather than mapped onto an enum of our own. The
	 * levels differ per model -- `deepseek-flash` offers low, high and max while
	 * another may offer a different set -- and inventing a fixed scale would
	 * make the app send values the API does not accept.
	 */
	suspend fun effortLevels(
		modelId: String?,
		providerId: String = ProviderIds.DEEPSEEK,
	): List<String> {
		if (modelId == null) return emptyList()
		val models = models(providerId).getOrNull().orEmpty()
		return models.firstOrNull { it.id == modelId }?.supportedEffortLevels.orEmpty()
	}
}
