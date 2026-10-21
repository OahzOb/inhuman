package com.inhuman.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * A source of completions.
 *
 * One OpenAI-compatible implementation serves every profile today. This
 * interface exists anyway, and that is a deliberate exception to the rule against
 * abstraction-for-its-own-sake: the streaming pipeline below it (`SseParser` ->
 * provider decoder -> [StreamChunk]) is already transport-agnostic by
 * construction, so a native protocol would be genuinely an addition rather than a
 * refactor. The interface marks where the seam already is.
 *
 * What is *not* here: retries, key management, persistence, tool execution.
 * Those are policy, and policy belongs to the layer that has the context to
 * decide -- the same reason a provider has no opinion about what a tool does.
 */
interface ChatProvider {

	/** Stable identifier, used for persistence and diagnostics. */
	val id: String

	/** Models this provider currently offers. */
	suspend fun listModels(): List<RemoteModel>

	/**
	 * Stream a completion.
	 *
	 * The flow completes normally on a clean finish and emits
	 * [StreamChunk.Failure] for anything that went wrong. Cancelling the
	 * collector must cancel the underlying request.
	 */
	fun stream(request: ChatRequest): Flow<StreamChunk>

	/**
	 * The exact JSON body this provider would send for [request], or null when
	 * the provider has no wire body (the demo source).
	 *
	 * The implementation must call the same builder as [stream]; a preview that
	 * duplicates the serializer is worse than no preview, because it can show a
	 * field the network never receives.
	 */
	fun previewRequest(request: ChatRequest): JsonObject? = null
}
